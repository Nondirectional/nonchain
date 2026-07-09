# Implement — nonchain 执行链路遥测 Trace Telemetry

> 依赖 `prd.md` 与 `design.md`。本文件只描述实现顺序、验证方式、风险点和 review gate，不等于开始实现。

## 1. 实现目标

交付可在 `Agent` / `Graph` 自动循环中使用的 trace 录制能力，满足：

- `Span` / `Trace` / `SpanContext` 模型 + `attributes` 载荷。
- `Tracer` + current-span ThreadLocal 栈 + 三边界显式传播。
- `TraceStore` SPI + `InMemoryTraceStore` + JSON 序列化。
- `Agent.builder().trace(store)` / `Graph.builder(name).traceStore(store)` 启用。
- SubAgent 全树下钻、并行工具正确父子、Flow 节点 span。
- `ChatResult.runtimeId()` / `GraphResult.runtimeId()` 暴露成功路径 id；失败路径通过“不改变主异常类型的 trace marker + 提取 API”暴露 runtimeId。
- 默认不配 = 零开销零行为变化。

## 2. 建议实现顺序

### Step 1：trace/ 包骨架类型（纯 POJO，无依赖）

- [ ] 新增 `trace/Span.java`（强类型骨架 + `attributes` Map + builder/end/error）
- [ ] 新增 `trace/Trace.java`（runtimeId/conversationId/spans + toJson/fromJson）
- [ ] 新增 `trace/SpanContext.java`（runtimeId/spanId/parentSpanId 不可变值对象）
- [ ] 新增 `trace/SpanAttributes.java`（公开常量类，挡 key 拼写错误，对应 design §3.2 表）

验证：

```bash
rtk mvn -pl chain compile -q
```

### Step 2：Tracer + current-span 栈

- [ ] 新增 `trace/Tracer.java`
  - 持有 `TraceStore`
  - `ThreadLocal<Deque<SpanContext>> CURRENT`
  - `startSpan(type, name)`：读 current 当 parent，建 span push，返回 `ScopedSpan`
  - `startChild(parentCtx, type, name)`：用显式 parent（边界 2/3 用）
  - `current()` 静态读栈顶（可空）
  - 内部类 `ScopedSpan implements AutoCloseable`：close() pop + end + store.record
- [ ] 单元测试：startSpan 嵌套、push/pop 正确、close 后 record

验证：

```bash
rtk mvn -pl chain test -Dtest=TracerTest -q
```

### Step 3：TraceStore SPI + InMemoryTraceStore

- [ ] 新增 `trace/TraceStore.java`（第一版只锁定 `record(span)` / `getTrace(id)`；不在本任务里定义 `search`）
- [ ] 新增 `trace/InMemoryTraceStore.java`（`ConcurrentHashMap<runtimeId, List<Span>>`，有界 LRU）
- [ ] `Trace.toJson()/fromJson()` 用既有 Jackson 实现（参考 `MessageSerializer` 用法）
- [ ] 单元测试：record → getTrace 往返、JSON 序列化往返等价

验证：

```bash
rtk mvn -pl chain test -Dtest=InMemoryTraceStoreTest,TraceSerializationTest -q
```

### Step 4：RecordingCallback（采集桥）

- [ ] 新增 `trace/RecordingCallback.java implements ChainCallback`
  - 持有 `Tracer`
  - `onLlmStart`：建 `llm` span（startSpan，current 即父轮次），把 `event.messages()`/`tools()` 记进 attributes
  - `onLlmComplete`：close 当前 llm span，补记 result/token/latency
  - `onToolStart`/`onToolComplete`/`onToolError`：串行路径建/关 `tool` span；并行路径只补载荷/标错，不重复建第二个 span
  - `onGraphEvent`：仅保留为用户观察桥；不承担 `state_in/state_out` 完整建模
  - 异常静默隔离（参考 `Graph.java:95-99` try-catch 模式，不污染主流程）
- [ ] 关键：span 的开/关必须配对；并行路径和串行路径的 tool span 生命周期不能双重创建

验证：

```bash
rtk mvn -pl chain test -Dtest=RecordingCallbackTest -q
```

### Step 5：Agent 接入 trace

- [ ] `Agent` 新增字段 `Tracer tracer`（可空）+ `ChainCallback userCallback` + `RecordingCallback recordingCallback`（可空）
- [ ] `Agent.Builder` 新增 `.trace(TraceStore store)`：store 非空时构造 Tracer + RecordingCallback；用户 callback 与录制 callback 分槽保存
- [ ] `ChatResult` 新增 `runtimeId` 字段 + `runtimeId()` 方法（可空，纯新增）
- [ ] `runWithLoop`（`Agent.java:162-174`）：tracer 非空时建 `agent_run` 根 span（current 为空→新根；非空→startChild，支持被 Flow/SubAgent 嵌套）；把 runtimeId 写进返回的 ChatResult；失败路径通过 suppressed trace marker 暴露 runtimeId，不改变主异常类型
- [ ] `doRunWithLoop`（`Agent.java:176-285`）：LLM 调用走 RecordingCallback 的 onLlmStart/Complete 自动建 llm span；工具执行同样靠 RecordingCallback（串行路径 current 栈正确）
- [ ] **回归检查**：tracer 为 null 时所有 trace 代码走短路，AgentTest 全绿

验证：

```bash
rtk mvn -pl chain test -Dtest=AgentTest -q
```

### Step 6：边界 2 — 并行工具 span 传播

- [ ] `Agent.java:246-247`：`supplyAsync` 前捕获 `final SpanContext parent = Tracer.current();`
- [ ] worker 任务体首行：`tracer.startChild(parent, "tool", tc.name())` 开 tool span（try-with-resources），覆盖 RecordingCallback 在 worker 线程的空 current 问题
- [ ] 调整 RecordingCallback：识别“当前工具 span 已由显式作用域创建”的情形，只补 attributes，不重复 start/close
- [ ] 测试：3 个并行工具 → getTrace 返回 3 个 tool span，parent 都指向同一 llm span，且无重复 span

验证：

```bash
rtk mvn -pl chain test -Dtest=AgentTraceParallelToolTest -q
```

### Step 7：边界 1 — SubAgent 全树下钻

- [ ] `executeSubAgentTool`（`Agent.java:392-425`）：构建子代理时
  - 用户 callback 继续传 `noop()`
  - 单独注入一个**新的 RecordingCallback**（持有同一个 `tracer`）
  - 把父侧 current `SpanContext` 通过 builder 显式注入子代理（新增 `Agent.Builder.parentSpanContext(SpanContext)` 或等价包级入口）
- [ ] 子代理 `runWithLoop`：检测到注入的 parent context → `startChild(parentCtx, "agent_run", subAgentName)` 当根，而非无条件新根
- [ ] 测试：父 run → getTrace(父 runtimeId) → spans 含子代理内部 llm/tool span，parent 链正确指向父委派 tool span
- [ ] **回归检查**：现有 SubAgentTest 全绿（用户面 callback 隔离语义不变）

验证：

```bash
rtk mvn -pl chain test -Dtest=SubAgentTest -q
```

### Step 8：Graph 接入 trace

- [ ] `Graph` 新增字段 `TraceStore traceStore`（可空）+ `Tracer tracer`（可空）
- [ ] `Graph.Builder` 新增 `.traceStore(TraceStore)`
- [ ] `GraphResult` 新增 `runtimeId` 字段 + `runtimeId()`（可空）
- [ ] 新增失败路径 runtimeId 提取辅助 API（例如 `TraceRuntimeIds.find(Throwable)`），并在 `Graph.run` 异常 rethrow 前附加 marker
- [ ] `Graph.run`（`Graph.java:35-88`）：
  - tracer 非空：建 `graph_run` 根 span（current 空→新根；非空→startChild 支持子图嵌套）
  - 每个节点执行前记录 `state_in`，执行后记录 `state_out`，异常时记录 `error`
  - runtimeId 写进 GraphResult
- [ ] **关键验证（边界 3）**：节点体内部若 `agent.run()`，靠 ThreadLocal 自然继承 node span 当 parent → 测试覆盖
- [ ] 回归：现有 Graph 测试全绿

验证：

```bash
rtk mvn -pl chain test -Dtest=GraphTest,GraphTraceTest -q
```

### Step 9：嵌套与端到端

- [ ] 测试 Flow → 节点内 Agent → SubAgent → 工具 的完整嵌套树
- [ ] 断言：单一 runtimeId、单一根（graph_run）、parent 链从根贯穿到叶子、所有层不断
- [ ] 断言：失败路径也能拿到 runtimeId 并成功回捞 trace
- [ ] JSON 序列化整棵树往返

验证：

```bash
rtk mvn -pl chain test -Dtest=TraceE2ETest -q
```

### Step 10：示例与文档

- [ ] 新增 `chain-example/.../TraceTelemetryExample.java`：
  - 一个 Agent run（带工具）→ 拿 runtimeId → getTrace → 打印 JSON
  - 一个 SubAgent 委派 → 展示树下钻
  - 一个 Graph + 节点内 Agent → 展示嵌套
- [ ] 更新 `README.md`：trace 能力介绍、`.trace(store)` 用法、runtime id 拉取示例、"可视化是独立消费端"边界声明
- [ ] 更新 `docs/overview/architecture.md`：trace 层在架构图里的位置
- [ ] 更新 `.trellis/spec/backend/directory-structure.md`：新增 `trace/` 包说明 + index.md 索引
- [ ] 更新 `TODO.md`：trace 项标记 done，附设计要点摘要
- [ ] 更新 `CHANGELOG.md`

## 3. 测试清单

- [ ] `TracerTest`：startSpan 嵌套、push/pop、close 后 record、startChild 显式 parent
- [ ] `InMemoryTraceStoreTest`：record/getTrace 往返、LRU 淘汰、并发安全
- [ ] `TraceSerializationTest`：toJson/fromJson 等价、含各 type attributes
- [ ] `RecordingCallbackTest`：llm/tool span 开关配对、载荷字段正确、异常静默隔离、并行路径不重复开 span
- [ ] `AgentTraceTest`：单 Agent run → 树结构正确、runtimeId 回填
- [ ] `AgentTraceErrorTest`：异常路径保留原异常类型，且能提取 runtimeId 并回捞 trace
- [ ] `AgentTraceParallelToolTest`：并行工具 parent 正确（边界 2）
- [ ] `SubAgentTraceTest`：全树下钻（边界 1）、用户面 callback 隔离不变
- [ ] `GraphTraceTest`：graph_run 根 + graph_node span（边界 3）
- [ ] `GraphTraceErrorTest`：节点异常时保留原异常类型，runtimeId 仍可提取，`graph_node` / `graph_run` 状态正确
- [ ] `TraceE2ETest`：Flow → Agent → SubAgent → 工具 完整嵌套树
- [ ] 回归：`AgentTest` / `SubAgentTest` / `GraphTest` / `ChainCallbackTest` 全绿
- [ ] 零开销验证：不配 trace 时 AgentTest 耗时无显著变化（可选 perf 断言）

推荐执行：

```bash
rtk mvn -pl chain test -q
rtk mvn -pl chain-example compile -q
```

## 4. 风险点

### 高风险

- **SubAgent 构建点改动（`Agent.java:392-425`）**：这是现有委派语义的核心路径。把 `noop()` 换成 RecordingCallback 时，必须保证"用户面 callback 仍隔离"这条既有承诺不破——录制 callback 与用户 callback 是两个独立实例，分别挂不同槽位。改错了会破坏 SubAgent 既有行为。
- **`runWithLoop` 根 span 逻辑（`Agent.java:162-174`）**：现在无条件 `ChainTrace.generate()` 新根。trace 这边要"current 空→新根、非空→startChild"，两种逻辑并存且互不干扰（ChainTrace 还是每次新根，不动）。
- **失败路径 runtimeId 暴露**：如果只把 id 塞进 `ChatResult` / `GraphResult`，异常场景会丢失主要调试入口。MVP 已锁定为“保留原异常类型 + 附加 marker + 提取 API”；实现时要避免误写成包装异常。

### 中风险

- **并行工具 span 传播（边界 2）**：RecordingCallback 的 onToolComplete 在 worker 线程触发，必须保证 `startChild` 注入的 span 在整个工具执行期间是 current（worker 任务体用 try-with-resources 包住整个 safeExecute 路径，而非只包一段），同时避免 RecordingCallback 再建第二个 tool span。
- **RecordingCallback 异常隔离**：录制失败不能污染主流程，但要小心不能反过来吞掉真正的业务异常。复用 `Graph.java:95-99` 的隔离模式。
- **JSON 序列化稳定性**：`Message` / `State` 的序列化形态决定了将来外部 store 的 schema。第一版要把 `attributes` 内部的值类型约定清楚（messages→结构化对象数组、state→map、tokens→number）。
- **Graph 节点前后状态来源**：`GraphEvent` 只有单份 state，不能误以为 callback 桥就够了；node span 前后 state 需要在 `Graph.run` 本地采样。

### 低风险

- 纯 POJO（Span/Trace/SpanContext/TraceStore/InMemoryTraceStore）：API 设计与测试覆盖问题。
- `ChatResult`/`GraphResult` 新增 `runtimeId()`：纯新增字段，不破坏既有。

## 5. 回滚点

- 若 **Agent trace 接入** 回归：回滚 `Agent.java` trace 改动，保留 `trace/` 包与 `RecordingCallback`（无副作用，未启用即不录）。
- 若 **SubAgent 下钻** 破坏既有委派语义：回滚 `executeSubAgentTool` 改动，子代理临时退回 `noop()`（SubAgent 委派能力不受影响，仅 trace 不下钻）。
- 若 **并行工具传播** 不稳定：临时关闭并行工具的 span 下钻（串行路径仍正确），保留 Agent/Flow 主路径。
- 若 **Graph 接入** 回归：回滚 `Graph.java`，trace 仅支持 Agent 路径（Flow trace 作为后续）。

## 6. Review Gate

进入实现前应确认：

- [ ] `prd.md` 9 项 Decisions Locked 已被接受，尤其：
  - Decision 2（SubAgent 全树下钻）—— 这是支点，决定整套设计形态
  - Decision 4（混合传播）—— 三边界显式传播是必须的
  - Decision 8（默认全关 opt-in）—— 不引全局 static
- [ ] 可以接受 `TraceStore` 第一版不含 `search(...)`，仅锁定 `record/getTrace`
- [ ] 可以接受 retrieval 不进 MVP，除非本任务显式补齐 retrieval 事件发射链路
- [ ] `design.md` §2.2 的核心结论被接受：**录制层正交于用户面 callback**，RecordingCallback 是采集主力但不是唯一传播路径。
- [ ] 可以接受第一版：默认 in-memory、持久化/可视化/exporter 全部后置。
- [ ] 可以接受 `ChatResult`/`GraphResult` 新增 `runtimeId()`（纯新增，可空），以及失败路径采用“原异常不变 + marker 提取”的 runtimeId 暴露机制。
- [ ] 可以接受 `trace/` 作为新包，与 `callback/` 并列（callback 是用户面观察，trace 是录制面观察，职责正交）。

只有以上 review 通过，才执行：

```bash
rtk python3 ./.trellis/scripts/task.py start .trellis/tasks/06-29-trace-telemetry
```
