# Design — nonchain 执行链路遥测 Trace Telemetry

> 基于 `prd.md` 已锁定的 9 项决策，定义 span 模型、传播机制、`TraceStore` SPI、三处边界编辑点和公开 API。本文件只描述技术设计，不等于开始实现。

## 1. 设计目标

在不破坏现有 `Agent` / `Graph` / `ToolRegistry` / `Message` / `ChainCallback` / `ChainTrace` 语义的前提下，新增一个**正交于用户面 callback 的录制层**：

- 复用 `ChainCallback` 事件里已经携带的数据（messages/result/toolCall/state）做采集，不重打点。
- 录制上下文走自己的 span 传播路径（不寄生 callback），绕开 SubAgent 的 `.callback(noop())` 隔离。
- 默认全关；显式 `.trace(store)` 才录。
- 录制数据装进 OTel 风格 span 树，按 runtime id 存进可插拔 store，Java API 取回 + JSON 序列化。

## 2. 总体方案

### 2.1 三层职责

1. **采集 → 录制桥**：一个 `RecordingCallback implements ChainCallback`，把已有事件转写成 span 载荷。这是"复用采集"的主路径——但注意它**不是**唯一的采集与传播路径（见 2.2）。
2. **span 传播与构建**：`Tracer` + `SpanContext` + current-span ThreadLocal 栈。建 span、push/pop、写 store；正交于用户 callback。
3. **存储与取回**：`TraceStore` SPI + `InMemoryTraceStore` + JSON 序列化。

### 2.2 为什么录制不能只靠 RecordingCallback

`RecordingCallback` 解决"同线程、单 Agent 内部"的大部分采集——`onLlmStart`/`onLlmComplete`/`onToolStart`/`onToolComplete` 事件都在父 Agent 线程，能靠 current-span 栈读到 parent。

但它**够不到**三个硬边界：

- **SubAgent 内部**：`Agent.java:410` 子代理 `.callback(noop())`，RecordingCallback 根本收不到子代理事件。→ 必须在子代理构建点把 `SpanContext` 显式带进去，子代理用一个**自己的 RecordingCallback 实例**（不是 noop），但其 current-span 栈的 parent 是父委派 tool 的 span。
- **并行工具**：`Agent.java:246` `supplyAsync(..., executor)`，RecordingCallback 的 `onToolComplete` 在 worker 线程触发，current-span 栈是空的。→ 必须在 `supplyAsync` 前捕获 current `SpanContext`，作为闭包变量传进 worker 任务。
- **Flow 节点**：`Graph.java:62` `node.apply(state)` 是 `Function<State,State>`，RecordingCallback 收到的是节点级 `GraphEvent`，且 `GraphEvent` 只带单份 `state`，不足以同时表达 `state_in/state_out`。→ `Graph.run` 必须在建 node span 后 push current，再调 `node.apply`，并直接抓取前后 state，让节点体内的子调用靠 ThreadLocal 自然继承。

所以设计是：**RecordingCallback 做 Agent/LLM/Tool 路径的采集主力，Tracer + SpanContext 显式传播做三个边界的补丁，Graph.run 对节点前后 state 做最小直接采集**。三者共同保证"整棵树不断、不错位"。

## 3. Span 模型

### 3.1 核心类型

新增 `trace/` 包（`chain/src/main/java/com/non/chain/trace/`），含：

```java
public final class Span {
    // 强类型骨架
    private final String spanId;        // UUID
    private final String parentSpanId;  // 根 span 为 null
    private final String runtimeId;     // = 根 span 的 spanId；同棵树共享
    private final String type;          // agent_run / graph_run / llm / tool / graph_node / retrieval
    private final String name;          // 人类可读：tool 名 / node 名 / "llm"
    private final long startTimeMs;
    private long endTimeMs;
    private String status;              // "ok" / "error"
    private String error;               // status=error 时的消息，可空

    // 载荷：schemaless，按 type 约定 key
    private final Map<String, Object> attributes;

    // status/error/endTime 通过 builder 的 end()/error() 写
}
```

```java
public final class Trace {
    private final String runtimeId;     // = root spanId
    private final String conversationId;// 可空，来自 ChatMemory，Q3 次级聚合用
    private final List<Span> spans;     // 扁平列表，按 startTime 排序；前端/取回方自己重建树
    // 提供 toJson() / static fromJson()
}
```

```java
public final class SpanContext {
    private final String runtimeId;
    private final String spanId;        // current span
    private final String parentSpanId;
    // 用于跨线程/跨 Agent 传递 current
}
```

### 3.2 type 与 attributes 约定

每个 type 的载荷 key 以常量类 `SpanAttributes` 公开（挡拼写错误）：

| type | attributes key | 来源事件/数据 |
|------|----------------|--------------|
| `agent_run`（根） | `system_prompt`, `max_iterations`, `query`(可空), `conversation_id`(可空) | Agent builder 配置 + run 入参 |
| `graph_run`（根） | `graph_name`, `start_node` | Graph.run 入参 |
| `llm` | `messages`, `tools`, `result_content`, `result_thinking`, `result_tool_calls`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `latency_ms` | LlmStartEvent / LlmCompleteEvent |
| `tool` | `tool_call_id`, `tool_name`, `arguments`, `result`, `is_error`, `latency_ms` | ToolStartEvent / ToolCompleteEvent / ToolErrorEvent |
| `graph_node` | `node_name`, `state_in`, `state_out`, `error`(可空) | `Graph.run` 本地采集 + GraphEvent 辅助 |
载荷值类型：messages 用 `List<Message>`（序列化时转结构化 JSON）、result/arguments 用 String、tokens 用 Long、state 用 `Map`（`State.data()` 已是 Map）。`attributes` 内部是 `Map<String,Object>`，JSON 序列化靠既有 Jackson（`MessageSerializer` 已用）。

`retrieval` type 不进入 MVP 必做范围。只有在本任务里补齐 retrieval 事件发射链路时，才一并落表进 `SpanAttributes` 与测试矩阵。

### 3.3 与 ChainTrace 的关系

**并行存在，互不替代**：
- `ChainTrace`（ThreadLocal traceId）原样不动，继续做用户面事件关联 id。
- 新 span 系统用**自己的** current-span ThreadLocal 栈（`Tracer.CURRENT`），不共用 `ChainTrace.TRACE_ID`。
- 事件里的 `traceId` 字段不动；span 的 `runtimeId` 是另一套。

## 4. 传播机制（混合方案）

### 4.1 Tracer

```java
public final class Tracer {
    private final TraceStore store;
    private static final ThreadLocal<Deque<SpanContext>> CURRENT = new ThreadLocal<>();

    // 建一个 span 并 push 为 current；返回 ScopedSpan（try-with-resources 自动 pop + end + record）
    public ScopedSpan startSpan(String type, String name) { ... }

    // 读 current（可能为 null：顶层无录制 / worker 线程未注入）
    public static SpanContext current() { ... }

    // 在 worker 线程恢复一个捕获的 parent（用于并行工具）
    public ScopedSpan startChild(SpanContext parent, String type, String name) { ... }
}
```

`ScopedSpan implements AutoCloseable`：`close()` 时 pop 栈、设 endTime/status、调 `store.record(span)`。

`ScopedSpan` 还应提供：
- `context()`：返回当前 span 的 `SpanContext`
- `markError(Throwable)` / `markErrorMessage(String)`：失败时写 `status/error`
- `putAttribute(key, value)` / `putAllAttributes(map)`：允许在 complete/error 时补全载荷

### 4.2 三处边界

**边界 1：SubAgent 构建点（`Agent.java:408-423`）。**
- 在 `executeSubAgentTool(...)` 里，父侧已经处于"委派 tool span"的 current 下。
- 改动：`Agent` 内部把"用户观察 callback"与"录制 callback"拆成两个槽位。构建子代理 Agent 时，用户 callback 继续传 `noop()` 以维持隔离；录制 callback 则传一个**新的 RecordingCallback**（持有同一个 `Tracer`），并通过 builder 把"当前 current `SpanContext`"显式注入子代理。
- 子代理 `runWithLoop` 启动时：检测到"注入了 parent context" → 第一轮不新建根 span，而是 `startChild(parentCtx, "agent_run", subAgentName)` 当根，后续 LLM/Tool span 靠 current 栈自然挂在它下面。
- **关键**：子代理的用户面 callback **仍**隔离（保持既有承诺），但**录制 callback** 不隔离——两者是不同的 callback 实例，不能再靠单一 `callback` 字段承载。

**边界 2：并行工具（`Agent.java:246-247`）。**
- 在 `CompletableFuture.supplyAsync` 前捕获 `Tracer.current()`（`final SpanContext parent = Tracer.current();`）。
- worker 任务体首行：用 `tracer.startChild(parent, "tool", tc.name())` 开 tool span，并把该 `ScopedSpan` 作为当前工具执行作用域。
- 并行路径里 **tool span 的创建与关闭以这个显式 `ScopedSpan` 为准**；`RecordingCallback` 在并行工具线程上只负责**补载荷/标错**，不再重复创建第二个 tool span。
- 串行路径（`Agent.java:266-277`）current 栈正常，无需特殊处理。

**边界 3：Flow 节点（`Graph.java:60-66`）。**
- `Graph.run` 首先建 `graph_run` 根 span（如果 current 为空 → 新根；否则 startChild——支持子图嵌套）。
- 每个节点：在 `node.apply(state)` 前 `startSpan("graph_node", node.name())` 并记录 `state_in`，执行后记录 `state_out`，异常时记录 `error` 并 `close()`。
- 节点体内的 `agent.run()` 靠 ThreadLocal 自然读到 node span 当 parent——`Function<State,State>` 签名不动。
- Graph 的 traceStore 来自 `Graph.builder(name).traceStore(store)`；`GraphEvent` 继续给用户 callback 用，但 node span 的前后 state 采集不再依赖 `GraphEvent` 完整表达。

### 4.3 异步范围（确认）

当前框架异步**仅止于并行工具单跳**（`supplyAsync` 一次性进 worker，无 `.thenComposeAsync` 链）。边界 2 的"捕获一次 current"足够。若将来引入多跳异步链，再加 context-propagating executor（Out Of Scope）。

## 5. TraceStore SPI

```java
public interface TraceStore {
    void record(Span span);
    Optional<Trace> getTrace(String runtimeId);
}
```

内置：

```java
public class InMemoryTraceStore implements TraceStore {
    // ConcurrentHashMap<runtimeId, List<Span>>，有界 LRU（容量可配，默认如 1000 棵树）
    // record: 追加到对应 runtimeId 的列表
    // getTrace: 组装 Trace（runtimeId + spans 排序）
}
```

序列化：`Trace.toJson()`（Jackson）+ `Trace.fromJson()`。JSON 结构稳定，便于将来外部 store 建 `spans` 表（列：span_id/parent_span_id/runtime_id/type/name/start_ms/end_ms/status/error/attributes_json）。

## 6. 公开 API（builder opt-in）

```java
// Agent
Agent agent = Agent.builder(llm, registry)
        .systemPrompt("...")
        .trace(traceStore)        // ← 启用录制；不配 = 不录 = 零开销
        .build();
ChatResult r = agent.run("你好");
String runtimeId = r.runtimeId();  // 成功路径
Trace trace = traceStore.getTrace(runtimeId).orElseThrow();
System.out.println(trace.toJson());
```

```java
// Graph
Graph graph = Graph.builder("flow")
        .traceStore(traceStore)   // ← 启用录制
        .addNode(...).addEdge(...).start("a").build();
GraphResult gr = graph.run(initialState);
String runtimeId = gr.runtimeId(); // GraphResult 附带 runtime id
```

### 6.1 runtime id 怎么拿回给用户

- 成功路径：
  - `Agent.run(...)` 返回 `ChatResult`：**新增** `ChatResult.runtimeId()`（可空——未启用 trace 时为 null）。
  - `Graph.run(...)` 返回 `GraphResult`：**新增** `GraphResult.runtimeId()`（同上）。
- 失败路径：
  - 不能只靠返回值，因为 `Agent.run` / `Graph.run` 失败时直接抛异常。
  - MVP **锁定**为：**保留原异常对象/类型不变，在 rethrow 前附加一个 suppressed trace marker**，其中携带 `runtimeId`。
  - 同时新增辅助 API，例如 `TraceRuntimeIds.find(Throwable): Optional<String>`，统一从异常链/被抑制异常里提取 runtimeId；调用方不需要预注册 callback。
  - 不采用包装异常（如 `TraceAwareException`）作为 MVP 方案，因为那会改变既有 `catch` 语义，和“不破坏现有行为”目标冲突。
- 因此，“成功返回值 + 失败异常上的可提取 trace marker”共同构成 runtime id 的官方取回面，而不是只有 `ChatResult` / `GraphResult`。

### 6.2 SubAgent 全树下钻的 API 体现

子代理 span 自动进父 run 的同一棵树（边界 1）。用户无需为子代理单独配 traceStore——它继承父 Agent 的 `Tracer` 实例。`getTrace(父 runtimeId)` 返回的 `Trace.spans` 里就含子代理内部的所有 span，parent 指向父委派 tool span。

## 7. Agent 内部改动点（精确）

`chain/src/main/java/com/non/chain/agent/Agent.java`：

| 行号 | 改动 |
|------|------|
| 新增字段 | `private final Tracer tracer;`（可空，null=不录）+ `private final ChainCallback userCallback;` + `private final RecordingCallback recordingCallback;` |
| Builder | 新增 `.trace(TraceStore)` 方法；`build()` 时若 store 非空，构造 `Tracer` + `RecordingCallback`。用户 callback 与录制 callback 分开保存；对外触发时再组合或分别调用，不能把“子代理用户隔离/录制贯通”压回单槽位 |
| `runWithLoop`（162-174） | 若 tracer 非空：建 `agent_run` 根 span（current 为空 → 新根；非空 → startChild，支持被嵌套）；把 runtimeId 写进返回的 ChatResult；异常路径通过 suppressed trace marker 附带 runtimeId |
| `doRunWithLoop`（176-285） | LLM 调用前后建 `llm` span；工具执行前后建 `tool` span。串行路径由 RecordingCallback 建关；并行路径由显式 `ScopedSpan` 建关，RecordingCallback 只补全载荷 |
| `executeSubAgentTool`（392-425） | 边界 1：构建子代理时注入 `Tracer` + 父 current `SpanContext`，子代理用注入的 tracer 跑（其 RecordingCallback 自动下钻） |

`chain/src/main/java/com/non/chain/flow/Graph.java`：

| 行号 | 改动 |
|------|------|
| 新增字段 | `private final TraceStore traceStore;`（可空）+ 录制 Tracer |
| Builder | 新增 `.traceStore(TraceStore)` |
| `run`（35-88） | 建 `graph_run` 根 span；每个节点 NODE_START/NODE_END 包 `graph_node` span；runtimeId 写进 GraphResult |

## 8. 新增文件

- `chain/src/main/java/com/non/chain/trace/Span.java`
- `chain/src/main/java/com/non/chain/trace/Trace.java`
- `chain/src/main/java/com/non/chain/trace/SpanContext.java`
- `chain/src/main/java/com/non/chain/trace/Tracer.java`（含 `ScopedSpan` 内部类）
- `chain/src/main/java/com/non/chain/trace/TraceStore.java`
- `chain/src/main/java/com/non/chain/trace/InMemoryTraceStore.java`
- `chain/src/main/java/com/non/chain/trace/RecordingCallback.java`（`implements ChainCallback`，事件 → span 载荷）
- `chain/src/test/java/com/non/chain/trace/...`（见 implement.md 测试清单）
- `chain-example/.../TraceTelemetryExample.java`

## 9. 兼容性策略

- 不配置 `.trace(...)`：`tracer` 为 null，所有建 span 的代码走 `if (tracer != null)` 短路，**零额外开销、零行为变化**。
- `ChainTrace` / `ChainCallback` / 事件 `traceId` 字段：原样不动。
- `ChatResult` / `GraphResult` 新增 `runtimeId()` 方法（返回 null 当未启用）——纯新增，不破坏既有调用。
- 失败路径必须保留原异常语义与栈信息；trace 信息通过附加 marker 提供，而不是替换主异常类型。
- 用户面 callback 与 RecordingCallback 都要保持异常隔离；RecordingCallback 失败不能污染主流程。

## 10. 测试重点

- 单 Agent run → `getTrace(id)` 返回一棵含 `agent_run + N×(llm + tool)` 的树，parent 关系正确。
- Agent/Graph 异常路径：调用方仍能拿到 `runtimeId`，并可从 store 拉回已录制的失败 trace。
- SubAgent 全树下钻：父树里能看到子代理的 `agent_run`(child) + 其内部 llm/tool span，parent 指向父委派 tool span。
- 并行工具：多个 tool span 正确挂在同一 llm 调用下，跨 worker 线程 parent 不丢。
- Flow：`graph_run` 根 + 每节点一个 `graph_node` span；节点体内 `agent.run()` 的 span 挂在 node span 下。
- 并行工具无重复 tool span：每个 toolCall 恰好对应一个 tool span。
- 不配置 trace：行为/耗时与现状一致（无 span 构建开销）。
- JSON 序列化往返：`Trace.toJson()` → `Trace.fromJson()` 内容等价。
- 回归：现有 AgentTest / SubAgent 测试 / Graph 测试全绿。

## 11. 后续但不在 MVP

- 持久化 store 实现（`chain-trace-mysql` / `chain-trace-postgres` 模块）。
- OTel / LangSmith exporter。
- 可视化前端。
- 会话级聚合查询（按 conversationId 取多棵树）。
- 采样。
- 多跳异步链 context 传播。
