# Design — nonchain SubAgent 系统重做:前台/后台并行执行

> 基于 `prd.md` 已锁定的 D1-D13 决策,定义本次重做的技术架构、核心数据结构、关键算法
> (死循环防护 / steer 注入 / join 精确时机 / resume 数据流 / graceful 状态机)与兼容策略。
> 这是 0.9.0 MVP(`06-27-subagent-upgrade-research/design.md`)的演进,保留其双层职责与隔离承诺,
> 新增异步后台执行、steer、resume、graceful 等能力。

## 1. 设计目标

在**不破坏 0.9.0 既有契约**的前提下(前台同步语义、普通工具、trace 全树下钻),为 SubAgent 引入:

- **异步后台执行**(D1):父代理 spawn 后台子代理后不阻塞,继续推理
- **自动 join + 主动拉取**(D3):轮末自动消费已完成的后台结果 + `get_subagent_result` 工具
- **并发控制**(D4):独立线程池 + 运行上限 + 自适应熔断
- **生命周期可观测**(D5):新增 AgentEvent,内部事件仍隔离
- **运行中转向**(D6):后台子代理支持 steer
- **会话恢复**(D7):opt-in 有状态子代理,复用 `ChatMemoryStore`
- **优雅轮数限制**(D9):graceful max turns,grace 期间允许工具

明确**不**做(D8/D10/D11/D12/D13):调度、多层嵌套、声明式定义、worktree、RPC 总线等。

## 2. 保留的 0.9.0 架构(不变项)

以下来自 MVP design,本次重做**完全保留**:

| 0.9.0 设计 | 本次态度 |
|---|---|
| 两层职责:注册在 `ToolRegistry`,暴露模式在 `Agent.Builder` | ✅ 保留 |
| `SubAgentDefinition` 不可变值对象 | ✅ 保留,新增字段 |
| `SubAgentExposureMode`(DIRECT/DELEGATE) | ✅ 保留 |
| `ContextSelector` 函数式接口 | ✅ 保留,新增后台默认截断策略 |
| 三路分流 `dispatchExecute`(普通/独立子代理/delegate) | ✅ 保留,扩展后台分支 |
| 子代理动态构造(运行时 build,不预构建) | ✅ 保留 |
| callback 隔离(子代理 `noop()`) | ✅ 保留(D5:内部事件不透传) |
| trace 不隔离(span 挂父委派 tool span 下) | ✅ 保留(D5) |
| 仅一层委派 | ✅ 保留 + fail-fast(D10) |
| Builder API(不声明式) | ✅ 保留(D11) |
| prompt_mode = replace | ✅ 保留(D11) |

## 3. 核心数据结构

### 3.1 `SubAgentDefinition` 扩展

0.9.0 字段全部保留,新增一个字段:

```java
public final class SubAgentDefinition {
    // ---- 0.9.0 既有(不变)----
    private final String name;
    private final String description;
    private final String systemPrompt;
    private final ToolRegistry toolRegistry;          // nullable
    private final LLM llmOverride;                    // nullable
    private final Integer maxIterations;              // nullable
    private final ContextSelector contextSelector;    // nullable
    private final List<BeforeToolCall> beforeInterceptors;
    private final List<AfterToolCall> afterInterceptors;

    // ---- 本次新增(D7)----
    private final ChatMemoryStore chatMemoryStore;    // nullable: null = 无状态(0.9.0 语义)
}
```

Builder(`SubAgentRegistration`)新增 `.chatMemoryStore(store)`,默认 null。

**fail-fast 校验(D10):** `build()` 时若 `toolRegistry != null` 且该 registry 注册了 subAgent,
抛 `IllegalStateException("子代理不支持嵌套委派: <name> 的 toolRegistry 含 subAgent")`。

### 3.2 `SubAgentExposureMode` 不变

DIRECT/DELEGATE 不变。前后台是**调用级**参数(D11),不进暴露模式。

### 3.3 新增 `BackgroundSubAgentManager`(父 Agent run() 内作用域)

这是后台执行的核心编排器,**生命周期绑定在一次父 agent run() 调用内**(D2)。
在 `doRunWithLoop` 入口创建,run() 结束(正常返回/异常)时关闭。

```java
/**
 * 后台子代理管理器:管理一次父 agent run() 内的后台子代理派发、并发队列、
 * 完成结果收集与轮末 join。作用域 = 单次 run()。
 */
class BackgroundSubAgentManager implements AutoCloseable {

    /** 运行中的后台任务(用于 join 判定) */
    private final Map<String, SubAgentRecord> running = new ConcurrentHashMap<>();
    /** FIFO 等待队列(运行上限触发) */
    private final Deque<QueuedSpawn> pending = new ConcurrentLinkedDeque<>();
    /** 已完成但未被 join 消费的结果 */
    private final List<SubAgentRecord> completedUnconsumed = new CopyOnWriteArrayList<>();
    /** 运行上限(默认4)+ 总派发熔断 */
    private final int maxRunning;
    private final int spawnCeiling;
    private final ExecutorService bgExecutor;         // 独立线程池(D4)
    private int totalSpawned = 0;                     // 熔断计数
    private final Consumer<AgentEvent> eventSink;     // 生命周期事件(D5)
    private final Agent parentAgent;                  // 用于构造子 Agent

    /** spawn 一个后台子代理。返回值 = 给父 LLM 的即时 tool result */
    String spawn(SubAgentDefinition def, String task, Message assistantMsg,
                 List<Message> parentSnapshot, String traceId);

    /** 轮末 join:收集已完成的结果,返回合并后的消息(D3/D13)。未完成的不阻塞 */
    JoinResult joinCompleted();

    /** Complete 前强制等待:阻塞直到所有后台完成或全局超时(D3) */
    JoinResult awaitAll(long timeoutMs);

    /** run() 结束时清理:取消未完成的后台任务 */
    @Override void close();
}
```

### 3.4 新增 `SubAgentRecord`(后台子代理运行时状态)

```java
class SubAgentRecord {
    final String id;                    // UUID
    final String name;                  // 子代理名
    final String task;
    final Instant spawnedAt;
    final CompletableFuture<SubAgentResult> future;  // 后台执行
    volatile Instant completedAt;
    volatile SubAgentStatus status;     // RUNNING / COMPLETED / STEERED / ABORTED / FAILED
    volatile String result;             // 子代理最终文本
    volatile boolean resultConsumed;    // 是否已被 join/get_subagent_result 消费
    final BlockingQueue<String> pendingSteers = new LinkedBlockingQueue<>();  // D6
}
```

### 3.5 新增 `SubAgentResult`(子代理结果 + 状态标记 D9)

```java
record SubAgentResult(String content, SubAgentStatus status) {
    /** aborted/steered 时追加警告(D9 status note) */
    String displayText() {
        return switch (status) {
            case COMPLETED -> content;
            case STEERED   -> content + "\n\n(限时收尾,输出可能不完整)";
            case ABORTED   -> content + "\n\n(超 maxIterations 硬中断,输出可能不完整)";
            case FAILED    -> content;
        };
    }
}
```

### 3.6 新增 `SubAgentStatus` 枚举

```java
enum SubAgentStatus { RUNNING, COMPLETED, STEERED, ABORTED, FAILED }
```

## 4. 关键算法

### 4.1 死循环防护(D3 核心)— 轮末 join 的精确时机

**问题:** 自动 join 若设计不当会死循环——父代理每轮 spawn 新后台,join 又触发新轮,无限跑。

**解法:join 只注入已完成的结果,不触发新工具调用。**

父循环结构(改造 `doRunWithLoop`,标 ★ 为新增/修改点):

```
for (round = 0; round < maxIterations; round++) {
    ★ // 入口:初始化 manager(仅 round 0)
    ★ bgManager = (round == 0) ? new BackgroundSubAgentManager(...) : bgManager;

    LLM 推理 → 得到 result(含 toolCalls 或纯文本)

    if (!result.hasToolCalls()) {
        ★ // 准备 Complete:强制等待所有后台完成或超时(D3)
        ★ JoinResult jr = bgManager.awaitAll(AWAIT_TIMEOUT);
        ★ if (jr.hasUncosumed()) {
        ★     messages.add(jr.mergedMessage());   // 注入合并结果
        ★     continue;  // ★关键:让 LLM 再看一轮后台结果后决定是否真 Complete
        ★ }
        return result;  // 真正 Complete:无遗留后台
    }

    // 有 toolCalls:执行(前台/后台混合)
    for (tc : toolCalls) {
        if (tc 是后台 spawn)  ★ result_i = bgManager.spawn(...)   // 立即返回
        else                  ★ result_i = safeExecute(tc, ...)    // 同步
        messages.add(toolResult(tc.id, result_i))
    }

    ★ // 本轮 tool 执行后:轮末 join(D3)
    ★ JoinResult jr = bgManager.joinCompleted();
    ★ if (!jr.isEmpty()) {
    ★     messages.add(jr.mergedMessage());   // 注入已完成的后台结果
    ★ }
    // 进入下一轮 LLM 推理,LLM 会看到注入的后台结果
}
```

**死循环防护三重保证:**
1. `joinCompleted()` 只注入**已完成**的结果,不 spawn 新任务,不强制 `continue`
2. spawn 的后台任务数量受熔断限制(D4:`maxIterations × maxRunning × 2`)
3. `awaitAll` 有全局超时(默认如 60s),超时后强制取消未完成的,允许 Complete

**关键不变量:** `joinCompleted()` 和 `awaitAll()` 都只向 `messages` 追加消息,不修改循环控制变量。循环终止仍由 `maxIterations` 和"LLM 不再产生 toolCalls 且无未消费后台"双重决定。

### 4.1.1 dispatchExecute 五路分流(瑕疵B 修正)

0.9.0 是三路(普通/独立子代理/delegate)。本次后台模式新增 `get_subagent_result` 和
`steer_subagent` 两个工具,分流扩展为**五路**:

```
dispatchExecute(tc, assistantMessage, parentSnapshot, traceId):
  1. toolRegistry.hasSubAgent(tc.name)?
       → 解析 run_in_background 参数
         · true  → bgManager.spawn(def, task, ...)     // 后台,立即返回
         · false → executeSubAgentTool(def, task, ...)  // 前台同步(0.9.0 路径)
  2. ToolRegistry.DELEGATE_TOOL_NAME.equals(tc.name)?
       → 解析 agentName + run_in_background,同上分流
  3. GET_RESULT_TOOL_NAME.equals(tc.name)?            ★ 新增(D3)
       → 解析 subagent_id + wait
         · bgManager.getResult(id, wait, WAIT_TIMEOUT)
  4. STEER_TOOL_NAME.equals(tc.name)?                 ★ 新增(D6)
       → 解析 subagent_id + message
         · bgManager.steer(id, message)
  5. else → toolRegistry.execute(tc.name, tc.arguments())  // 普通工具(0.9.0 不变)
```

**注:** 第 3、4 路仅在存在已注册子代理时才会出现(工具 schema 按条件暴露,见 §5)。
无子代理的 Agent,dispatchExecute 退化为 0.9.0 的三路(实际只走第 1、2、5 路)。

### 4.2 join 合并消息格式(D13)

同轮完成多个后台结果,合并成**一条** `user` 消息注入:

```
[子代理 "research" 完成]
<research 的结果文本>

[子代理 "writer" 完成(限时收尾,输出可能不完整)]
<writer 的结果文本>
```

用 `Message.user(merged)` 注入(对 LLM 可见)。合并降低消息膨胀,且一次触发让 LLM 综合判断。

### 4.3 steer 注入改造(D6 核心)— Agent.run() 可中断性

**0.9.0 现状:** `Agent.run(messages)` 是同步阻塞,消息构造时传入,运行中无法注入。

**改造:** 给 `Agent` 增加内部 steer 队列 + 循环检查点。这是基础能力改造,但**仅后台子代理的 Agent 实例启用**(前台/顶层 Agent 不启用,保持 0.9.0 语义)。

```java
public class Agent {
    // ---- 新增(D6):可运行中注入 steer,仅子代理实例用 ----
    private final BlockingQueue<String> pendingSteers;   // null = 未启用(顶层/前台 Agent)

    /** 运行中注入消息(仅后台子代理支持,D6)。前台/顶层 Agent 调用抛 UnsupportedOperationException */
    public void steer(String message) {
        if (pendingSteers == null) throw new UnsupportedOperationException(
            "steer 仅支持后台子代理");
        pendingSteers.add(message);
    }
}
```

**Builder 新增内部方法(不公开):**

```java
Builder(LLM llm, ToolRegistry registry) {
    ...
    this.pendingSteers = null;  // 默认不启用
}

/** 内部:构造子代理时启用 steer 队列 */
Builder enableSteer() {
    this.pendingSteers = new LinkedBlockingQueue<>();
    return this;
}
```

**循环检查点(D6 核心):** 在 `doRunWithLoop` 每轮 LLM 调用**前**drain `pendingSteers`:

```
for (round = 0; round < maxIterations; round++) {
    ★ // steer 检查点:每轮 LLM 调用前,把注入的消息加入 messages
    ★ if (pendingSteers != null) {
    ★     String steer;
    ★     while ((steer = pendingSteers.poll()) != null) {
    ★         messages.add(Message.user(steer));  // 作为 user message 注入(对齐 pi)
    ★     }
    ★ }
    LLM 推理 → ...
}
```

**后台子代理的 `steer` 调用链:**
父 LLM 调 `steer_subagent` 工具 → `bgManager.steer(recordId, message)` → `record.pendingSteers` 或直接调底层 child Agent 的 `steer()`。

**线程安全:** `LinkedBlockingQueue` 线程安全;父循环(主线程)drain,steer 调用(可能来自 LLM 工具执行线程或应用层)add,无锁竞争。

### 4.4 graceful max turns 状态机(D9)

改造 `doRunWithLoop` 的循环上界判定。引入 grace 阶段。**顶层和子代理统一走 graceful**
(review 关键点1),消除 `isSubAgent` 分叉。

```
int hardLimit = maxIterations;
int graceTurns = this.graceTurns;   // 默认 DEFAULT_GRACE_TURNS=3,可配;0=禁用 graceful(回退0.9.0硬截断)
SubAgentStatus finalStatus = COMPLETED;

for (round = 0; round < hardLimit + graceTurns; round++) {
    if (round == hardLimit) {
        // 自动 steer 收尾(D6 复用):注入 "立即收尾" 消息
        if (pendingSteers != null) pendingSteers.add("已达轮数上限,请立即收尾输出最终结果。");
        else messages.add(Message.user("已达轮数上限,请立即收尾输出最终结果。"));
        finalStatus = STEERED;   // 标记:进入 grace,若 grace 内完成则为 STEERED
    }
    if (round >= hardLimit + graceTurns) {
        // 硬中断(D9):不抛异常,返回部分结果 + ABORTED
        finalStatus = ABORTED;
        break;
    }
    ... 正常循环(含 steer 检查点、tool 执行、join)...
    if (!result.hasToolCalls()) {
        // 正常 Complete(无 toolCalls 且无遗留后台):finalStatus 保持 COMPLETED,或已是 STEERED
        return wrapResult(result, finalStatus);
    }
}
// 循环耗尽(hardLimit+graceTurns 用尽):finalStatus 为 ABORTED
return wrapResult(lastResult, finalStatus);
```

**status 状态判定:**
- 正常 Complete(无 toolCalls,round < hardLimit):`COMPLETED`
- grace 内收尾成功(round 在 [hardLimit, hardLimit+graceTurns) 内 Complete):`STEERED`
- 硬中断(round == hardLimit+graceTurns):`ABORTED`

**grace 期间允许工具调用(D9):** 不改工具列表,不特殊处理——grace 轮里仍可调工具,
只是收到了"收尾"提示。

**status 传递通道(瑕疵A 修正)— 分层设计:**

`Agent` 内部分层,顶层与子代理共用 graceful 逻辑,但 status 通道不同:

```java
public class Agent {
    /** 内部:返回 ChatResult + 最终 status(子代理/后台用) */
    SubAgentResult runInternal(List<Message> messages, Consumer<AgentEvent> sink) {
        ... doRunWithLoop 改造,内部维护 finalStatus ...
        return new SubAgentResult(result.content(), finalStatus);
    }

    /** 公开:顶层 Agent 入口,返回 ChatResult(忽略 status,0.9.0 契约不变) */
    public ChatResult run(String query, Consumer<AgentEvent> sink) {
        SubAgentResult sr = runInternal(...);
        return ChatResult.of(sr.content(), ...);   // status 丢弃(顶层不关心)
    }

    /** 公开重载:无事件回调 */
    public ChatResult run(String query) { return run(query, null); }
}
```

- 顶层 Agent:`run()` 返回 ChatResult,status 被丢弃——但**行为已从抛异常改为 graceful**
  (review 关键点1:顶层也 graceful,这是与 0.9.0 的行为差异,需文档说明)
- 子代理/后台:`runInternal()` 返回 SubAgentResult,`executeSubAgentTool`/manager 读 status

**与 0.9.0 的差异(重要,需 CHANGELOG 说明):**
- 0.9.0:超 `maxIterations` 抛 `AgentException`(`Agent.java:342`)
- 本次:**所有 Agent(顶层+子代理)统一走 graceful**,硬中断不抛异常,返回部分结果
- 应用层若依赖"超限抛异常"做控制流,需改为检查结果完整性或显式设 `graceTurns(0)`
  回退硬截断(此时恢复抛异常语义)

### 4.5 resume 数据流(D7)

**首次委派(无 ChatMemoryStore 或历史为空):**
```
childMessages = [systemPrompt(def)] + parentSlice(ContextSelector 注入) + [user(task)]
```

**resume(有 ChatMemoryStore 且历史非空):**
```
List<Message> history = chatMemoryStore.getMessages(conversationId);
if (history.isEmpty()) {
    // 首次:走上面注入父上下文
} else {
    // resume:不注入父上下文(D12)
    childMessages = [systemPrompt(def)] + history + [user(task)]
}
// 委派完成后,存回:
childMessages 追加本次产生的所有消息
chatMemoryStore.updateMessages(conversationId, childMessages 去掉 systemPrompt);
```

**conversationId 生成规则(瑕疵C 修正):** 区分前台/后台,解决并发冲突:

| 模式 | conversationId | 说明 |
|---|---|---|
| 前台(同步) | `<parentRunId>:<subAgentName>` | 同一 run() 内多次委派同名子代理 → 走 resume(连续对话) |
| 后台(并发) | `<parentRunId>:<subAgentName>:<recordId>` | 同名子代理并发 spawn → 各自独立 recordId,互不覆盖 |

`parentRunId` = 父 agent run() 的 traceId/runtimeId(一次 run() 内稳定)。
`recordId` = 后台子代理的 UUID(§3.4 SubAgentRecord.id)。

**语义:**
- 前台 resume = "继续上次对话"(连续性,适合多轮深入)
- 后台 resume = "恢复某个具体后台任务"(独立性,适合并发隔离)
- 跨 run() 隔离(parentRunId 不同);持久化 ChatMemoryStore 实现则跨 run() 可恢复(用相同的 parentRunId 约定)

**注:** 子代理内部的 messages 是它自己 LLM 循环产生的完整对话。resume 时把历史 + 新 task 喂给它,它"记得"上次。前台连续 resume 会累积历史;后台每次 spawn 是独立 record,除非显式 resume 某个 recordId。

### 4.6 后台默认截断 context(D12)

新增框架常量策略,后台子代理默认用:

```java
private static final ContextSelector BACKGROUND_CONTEXT_SELECTOR =
    (parentMessages, assistantMessage, task) -> {
        List<Message> visible = parentMessages.stream()
            .filter(Message::llmVisible)
            .toList();
        // 只取最近 N 条(默认 4)
        int n = Math.min(visible.size(), 4);
        return new ArrayList<>(visible.subList(visible.size() - n, visible.size()));
    };
```

前台保持 `DEFAULT_CONTEXT_SELECTOR`(全量可见,0.9.0)。子代理注册的 `contextSelector` 优先级最高(覆盖两者)。

## 5. 工具 schema 变更(D11 调用级前后台)

### 5.1 独立子代理 tool schema(新增 run_in_background)

```json
{
  "name": "research",
  "description": "负责调研与归纳",
  "parameters": {
    "type": "object",
    "properties": {
      "task": { "type": "string", "description": "委派任务" },
      "run_in_background": { "type": "boolean", "description": "是否后台执行(默认 false)", "default": false }
    },
    "required": ["task"]
  }
}
```

### 5.2 delegate tool schema(新增 run_in_background)

`delegate_to_subagent` 的 parameters 增加 `run_in_background`(同上)。

### 5.3 新增工具:get_subagent_result(D3)

```json
{
  "name": "get_subagent_result",
  "description": "查询/等待后台子代理的结果",
  "parameters": {
    "type": "object",
    "properties": {
      "subagent_id": { "type": "string", "description": "后台子代理 ID" },
      "wait": { "type": "boolean", "description": "是否阻塞等待完成(默认 false)", "default": false }
    },
    "required": ["subagent_id"]
  }
}
```

**暴露条件:** 当且仅当存在已注册子代理时,与 DIRECT/DELEGATE 子代理工具一起暴露。

**wait:true 超时边界(瑕疵E 修正):** `wait=true` 时阻塞等待后台完成,但必须有超时保护,
避免后台子代理失控(steer 失败 + grace 也没拦住)导致父 LLM 工具线程永久阻塞。
- 超时复用全局 `WAIT_TIMEOUT`(与 `awaitAll` 共用,默认 60s)
- 超时后返回当前状态(running 则返回"仍在运行";已有部分结果则返回部分结果 + 状态)
- 超时不抛异常,返回信息性文本让父 LLM 决定下一步

### 5.4 新增工具:steer_subagent(D6)

```json
{
  "name": "steer_subagent",
  "description": "向运行中的后台子代理注入转向消息",
  "parameters": {
    "type": "object",
    "properties": {
      "subagent_id": { "type": "string" },
      "message": { "type": "string", "description": "转向指令" }
    },
    "required": ["subagent_id", "message"]
  }
}
```

## 6. AgentEvent 扩展(D5)

新增生命周期事件,均为 `AgentEvent` 的内部类:

```java
class SubAgentSpawned implements AgentEvent {
    String subAgentId; String name; String task; boolean background;
}
class SubAgentStarted implements AgentEvent { String subAgentId; String name; }
class SubAgentCompleted implements AgentEvent { String subAgentId; String name; SubAgentStatus status; String resultPreview; }
class SubAgentFailed implements AgentEvent { String subAgentId; String name; Throwable error; }
class SubAgentSteered implements AgentEvent { String subAgentId; String message; }
class SubAgentAborted implements AgentEvent { String subAgentId; String name; }
```

**发射点:** `BackgroundSubAgentManager` 在状态转换时通过 `eventSink` 发射。
**子代理内部事件隔离不变:** 子代理的 `TextDelta`/`ToolStart` 等用 `callback = noop()`,不透出。

## 7. 并发控制实现(D4)

### 7.1 独立线程池

`BackgroundSubAgentManager` 构造时创建独立 `ExecutorService`:

```java
this.bgExecutor = Executors.newFixedThreadPool(maxRunning);  // 默认 4
```

Builder 新增配置(父 Agent 级):

```java
public Builder backgroundExecutor(ExecutorService exec) { ... }  // 可选覆盖
public Builder maxBackgroundRunning(int n) { ... }               // 默认 4
```

### 7.2 spawn 流程(含队列 + 熔断)

```
spawn(def, task, ...):
    1. 熔断检查:if (totalSpawned >= spawnCeiling) return "已达后台派发上限,拒绝新任务"
       spawnCeiling = maxIterations × maxRunning × 2(D4 自适应)
    2. totalSpawned++
    3. 创建 SubAgentRecord,发 SubAgentSpawned 事件
    4. if (running.size() < maxRunning) 立即提交到 bgExecutor
       else 加入 pending 队列,等有任务完成时 drain
    5. 返回即时 tool result:
       "后台子代理已派发,id=<id>,稍后用 get_subagent_result 查询或等待自动 join"
```

### 7.3 后台任务完成回调

```
bgExecutor 提交的任务:
    child = 构造子 Agent(enableSteer)
    result = child.run(childMessages)  → SubAgentResult(content, status)
    record.result = result.content
    record.status = result.status
    record.completedAt = now
    completedUnconsumed.add(record)
    running.remove(record.id)
    drain pending 队列(有 slot 了)
    发 SubAgentCompleted/Aborted/Failed 事件
```

### 7.4 run() 结束清理(D2)

`doRunWithLoop` 的 finally 块调 `bgManager.close()`:
- 取消所有 `running` 中的 future(`future.cancel(true)`)
- shutdown `bgExecutor`(awaitTermination 短超时)
- 不保留任何状态(D2:绑定 run() 内)

## 8. 改动文件清单

### 新增
- `chain/src/main/java/com/non/chain/agent/SubAgentStatus.java`
- `chain/src/main/java/com/non/chain/agent/SubAgentResult.java`
- `chain/src/main/java/com/non/chain/agent/SubAgentRecord.java`
- `chain/src/main/java/com/non/chain/agent/BackgroundSubAgentManager.java`
- `chain/src/main/java/com/non/chain/agent/JoinResult.java`

### 修改
- `chain/src/main/java/com/non/chain/agent/SubAgentDefinition.java` — 新增 `chatMemoryStore` 字段 + D10 fail-fast
- `chain/src/main/java/com/non/chain/agent/Agent.java` — steer 队列 + graceful 状态机 + `runInternal` 分层(瑕疵A) + run() 内 manager 生命周期 + 五路分流(瑕疵B)
- `chain/src/main/java/com/non/chain/agent/AgentEvent.java` — 新增 6 个生命周期事件
- `chain/src/main/java/com/non/chain/agent/Agent.java` Builder — `backgroundExecutor` / `maxBackgroundRunning` / `graceTurns`(D9,0=禁用 graceful) / `enableSteer`(内部)
- `chain/src/main/java/com/non/chain/tool/ToolRegistry.java` — `SubAgentRegistration` 加 `.chatMemoryStore()`;子代理 tool schema 加 `run_in_background`;新增 `get_subagent_result`/`steer_subagent` 工具暴露;常量 `GET_RESULT_TOOL_NAME`/`STEER_TOOL_NAME`

### 测试
- `chain/src/test/java/com/non/chain/agent/BackgroundSubAgentTest.java`(新增)
- `chain/src/test/java/com/non/chain/agent/SubAgentSteerTest.java`(新增)
- `chain/src/test/java/com/non/chain/agent/SubAgentResumeTest.java`(新增)
- `chain/src/test/java/com/non/chain/agent/GracefulMaxTurnsTest.java`(新增)
- 现有 `AgentTest` / `SubAgentTest` 回归

### 示例与文档
- `chain-example/.../BackgroundSubAgentExample.java`(新增)
- `README.md` SubAgent 章节扩展

## 9. 兼容性策略

### 9.1 前台子代理语义零回归
- 前台子代理(`run_in_background=false` 或缺省)走 0.9.0 的 `executeSubAgentTool` 同步路径,
  **委派机制完全不变**
- 未启用后台的 Agent(没 spawn 任何后台任务)的 run() 循环结构与 0.9.0 一致——
  `bgManager` 仍会创建,但因无后台任务,join/awaitAll 都是空操作,零开销

### 9.2 ⚠️ 顶层 Agent maxIterations 行为变化(review 关键点1)
- **0.9.0:** 顶层 Agent 超 `maxIterations` 抛 `AgentException`
- **本次:** 顶层 Agent 也走 graceful(超限 → 收尾 steer → grace turns → 返回部分结果,**不抛异常**)
- **这是有意的破坏性变更**(review 已确认),理由:统一行为,顶层应用层也能从 graceful 受益
- **回退方式:** 应用层若需保留 0.9.0 抛异常语义,显式 `.graceTurns(0)`,此时恢复硬截断 + 抛异常
- **需 CHANGELOG 显著标注:** "Breaking: Agent 超限行为从抛异常改为 graceful,设 graceTurns(0) 回退"

### 9.3 默认无状态(D7)
- `chatMemoryStore` 默认 null,子代理保持 0.9.0 无状态语义
- 只有显式配置 `chatMemoryStore` 的子代理才有 resume 能力

### 9.2 默认无状态(D7)
- `chatMemoryStore` 默认 null,子代理保持 0.9.0 无状态语义
- 只有显式配置 `chatMemoryStore` 的子代理才有 resume 能力

### 9.3 现有 API 不破坏
- `SubAgentRegistration` 所有 0.9.0 方法保留,新增 `.chatMemoryStore()` 为可选
- `Agent.Builder` 所有 0.9.0 方法保留,新增后台相关为可选
- `ToolRegistry.getTools()` 等现有方法不变

## 10. 风险点与权衡

### 高风险
- **Agent.java 改动面大:** steer 队列 + graceful 状态机 + manager 生命周期 + runInternal 分层都在核心循环。需保证前台路径(无后台、graceTurns 默认)行为可控
- **顶层 graceful 破坏性变更(§9.2):** 0.9.0 依赖"超限抛异常"的应用层会受影响。缓解:CHANGELOG 显著标注 + `graceTurns(0)` 回退路径 + 回归测试覆盖
- **死循环防护:** 4.1 的三重保证必须经测试验证,尤其"spawn 后台 + join continue"的组合

### 中风险
- **并发正确性:** `BackgroundSubAgentManager` 的 running/pending/completedUnconsumed 并发访问,需用正确的并发集合 + volatile
- **steer 时序:** 子代理已在 LLM 调用中时,steer 消息要等下一轮才生效(队列 poll 在轮首)。需文档明确"steer 不是即时中断"

### 低风险
- 新增值对象/枚举/事件类:主要是 API 设计
- 工具 schema 扩展:新增可选参数,对老 LLM 调用兼容(忽略新参数)

### 关键权衡记录
- **grace 期间允许工具(D9):** 牺牲一些"收尾强制力",换取灵活性(对齐 pi)。通过 status 标记让父代理判断完整性
- **join 合并成一条消息(D13):** 牺牲单个结果的独立性,换取消息不膨胀。LLM 仍可从文本区分
- **熔断自适应(D4):** 牺牲配置简单性,换取不误杀高 maxIterations 场景

## 11. 后续(不在本次)
- 多层嵌套松绑(D10 明确本次不做)
- 完整 transcript 文件输出(用 trace 替代)
- join 策略可配(smart/async/group)— 本次固定合并
