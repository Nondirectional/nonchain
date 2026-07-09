# Agent 工具拦截器（beforeToolCall/afterToolCall）

> 父任务：`06-24-agent-control-flow-extensibility`

## Goal

为 nonchain Agent 的工具执行链路引入**拦截点**，允许应用在不继承 `Agent`/`ToolRegistry`、不破坏现有 `ChainCallback` 观察者机制的前提下，对工具调用进行**拦截、修改、取消**。

解锁场景：工具审核/确认（危险命令要人确认）、工具结果脱敏（去掉 secret、截断超长输出）、工具熔断（黑名单、配额）、强制终止（某工具完成后停止整轮）。

## 现状（已确认事实）

- `Agent.safeExecute`（`Agent.java:273-286`）catch 所有异常 → 软失败字符串回灌 LLM，**无拦截入口**。
- `ChainCallback`（`ChainCallback.java`）是纯观察者：`onToolStart/onToolComplete/onToolError` 全是 default 空实现，`CompositeCallback.safeInvoke`（`CompositeCallback.java:114-120`）静默吞异常。grep `interceptor|middleware|beforeTool|afterTool` 全库**零命中**。
- 工具执行在 `Agent.doRunWithLoop`（`Agent.java:219-260`），并行（`CompletableFuture`）/串行两种模式，结果按源序回填 messages。
- `Agent` 是不可变类（构造后字段全 final），通过 Builder 构建。

## 参考来源

pi-agent-core `src/agent-loop.ts`：
- `beforeToolCall({assistantMessage, toolCall, args, context}, signal)` → 返回 `{ block: true, reason }` 阻止执行（agent-loop.ts:581-604）
- `afterToolCall({assistantMessage, toolCall, args, result, isError, context}, signal)` → 返回 `{ content?, details?, isError?, terminate? }` 修改结果（agent-loop.ts:682-707）
- preflight 串行收集 block/not-found/参数错误为"立即结果"，仅放行的进入并发执行（agent-loop.ts:451-516）

## Requirements

### R1 拦截器接口（控制点）—— 形态已定（D1）

**决策（D1）**：采用**两个独立的 `@FunctionalInterface` 单方法接口**，而非 pi 风格的单接口双方法。

理由：
- 契合 spec `quality-guidelines.md §6` 的 `@FunctionalInterface` 约定（单方法 → 可写 lambda）。
- 契合项目惯用法（`ToolHandler`、`Consumer<AgentEvent>` 都是单方法函数式接口）。
- 常见场景（只审核 before，或只脱敏 after）写一行 lambda 即可，不必实现空方法。

```java
@FunctionalInterface
public interface BeforeToolCall {
    BeforeResult before(ToolCallContext ctx);
}

@FunctionalInterface
public interface AfterToolCall {
    AfterResult after(ToolCallContext ctx);
}
```

挂载：`Agent.Builder` 新增 `addBeforeToolCall(...)` / `addAfterToolCall(...)`，可多次调用、按注册顺序串行执行。

入参上下文对象（`ToolCallContext`）含：toolCall（name、arguments、id）、触发该调用的 assistant 消息。after 的上下文额外含执行结果 content、isError。

**返回类型形态已定（D2）**：静态工厂 + 不可变值对象（契合 `Message.user()` 风格 + quality-guidelines §1/§3）。

```java
// before
BeforeResult.pass()                    // 放行
BeforeResult.block(String reason)      // 阻止，reason 作为 error tool result 回灌 LLM

// after（本任务不含 terminate，见 D4）
AfterResult.keep()                     // 不改
AfterResult.content(String newContent) // 仅改 content（脱敏/截断）
AfterResult.error()                    // 标记为错误（isError=true）
AfterResult.builder()...build()        // 组合 content + isError
```

- `pass()`/`keep()` 显式表达"什么都不做"，无 null 歧义（契合 error-handling 的 fail-fast 精神）。
- 90% 场景用单参数工厂；组合场景用 builder。不强迫所有人面对全字段构造器。

### R2 挂载方式

- `Agent.Builder` 新增添加拦截器的入口（可加多个，按顺序执行）。
- 拦截器在工具执行链路中按注册顺序串行调用。
- before 返回 block 时：跳过该工具的实际执行，直接构造 error tool result（reason 文本）回灌 LLM；仍触发 `ChainCallback.onToolStart/onToolError` 保持观察者可见（**不重复触发**——见 R5）。

### R3 多拦截器组合语义

- 多个 before 拦截器：任一返回 block 即 block（短路），后续 before 不再调用。
- 多个 after 拦截器：链式，前一个的输出作为后一个的输入（可叠加脱敏/截断）。
- 拦截器自身抛异常：按现有 `AgentException` 规范包装抛出（不静默吞，与 `CompositeCallback` 语义区分——拦截器是控制行为，异常应让 run 失败可见）。

### R4 与 ChainCallback 正交

- `ChainCallback` 行为不变，继续纯观察。
- 同一次工具调用的 callback（onToolStart/onToolComplete/onToolError）照常触发，时机不变。
- before block 场景：onToolStart 触发，然后 onToolError（reason 文本），无 onToolComplete。

### R5 移除 ToolRegistry 的 callback 触发（顺带）—— 方案已定（D3 修正）

**已核实现状（grep 证实）**：ToolRegistry 内部在 `execute` 触发 callback（ToolRegistry.java:129/135/139），但：
- 全代码库 **30+ 处** `new ToolRegistry()` 全部用无参构造器（callback=noop），`new ToolRegistry(callback)` / `new ToolRegistry(chainContext)` **零调用方**。
- 因此 ToolRegistry 里那段 callback 触发逻辑实际**永远打到 noop 上，是死代码**。
- 所谓"重复触发 2 次"只在 Agent 与 ToolRegistry 共享同一 callback 实例时发生——现状无此用法。

**决策（D3 修正）**：**直接重构 `ToolRegistry.execute` 移除 callback 触发**，不新增 `executeWithoutCallback`。callback 全归 Agent 编排层。

- `execute(name, arguments)` 只管执行（调 `doExecute`），不触发任何 callback。
- **删除** `ToolRegistry(ChainCallback)` 和 `ToolRegistry(ChainContext)` 两个零调用构造器 + `callback` 字段。
- `Agent.safeExecute` 的 callback 触发**保持原样**（它本就是唯一真正生效的触发点）。
- 拦截点照常插入（before 在 onToolStart 之后、after 在 onToolComplete 之前）。

**理由**：callback 属于编排层关注点，ToolRegistry 是纯执行器，触发 callback 是职责越界；移除后回归单一职责，R5 的"每次调用只触发一次"自然达成，无需双方法。

### R6 默认行为零变更

- 不配置任何拦截器时，Agent 行为与现状完全一致（回归基线）。
- 现有 `AgentTest` 全绿，包括并行执行、串行回退、工具错误回灌等。

## Acceptance Criteria

- [ ] 新增两个 `@FunctionalInterface`：`BeforeToolCall`（返回 `BeforeResult`）、`AfterToolCall`（返回 `AfterResult`）。
- [ ] `BeforeResult`/`AfterResult` 为不可变值对象，用静态工厂构造（`pass/block`、`keep/content/error/builder`），无 null 歧义。
- [ ] `Agent.Builder` 新增 `addBeforeToolCall` / `addAfterToolCall`，支持注册多个，按顺序串行执行。
- [ ] before 返回 block 时：跳过该工具实际执行，构造 error tool result（reason 文本）回灌 LLM。
- [ ] after 能改写 content（脱敏/截断）、isError；多 after 链式（前一个输出作后一个输入）。
- [ ] 多 before 任一 block 即短路（后续 before 不调用）。
- [ ] 拦截器自身抛异常被包装为 `AgentException`（或现有标准异常）抛出，**不被静默吞**。
- [ ] **R5**：callback（onToolStart/onToolComplete/onToolError）每次工具调用**只触发一次**（在 Agent 层），有测试断言。ToolRegistry 公开 `execute` 行为不变。
- [ ] 不配置任何拦截器时，现有 `AgentTest`/`AgentMemoryTest`/`ToolRegistryTest` 全绿（回归基线）。
- [ ] 新增单测：before block、after 改写 content、after 改 isError、多拦截器链式、**并行模式下**拦截器行为、拦截器抛异常、callback 单次触发断言。
- [ ] `chain-example` 新增一个拦截器示例（危险命令确认 或 输出脱敏）。
- [ ] design.md + implement.md 完成（复杂任务）。

## Out of Scope

- **terminate（after 返回后跳过下一轮 LLM 调用 + 批次投票）—— 本任务不做**（D4）。留给后续 P1 任务与 `shouldStopAfterTurn` 一起做。`AfterResult` 设计为可扩展值对象，未来加 `terminate` 字段是纯增量。
- 不引入 preflight 串行 + execute 并发的两阶段重构（P1，本任务只在现有执行段插入拦截点）。
- 不实现具体的审核 UI / 脱敏规则（只提供机制，示例可演示）。
- 不改 `ToolRegistry` 的参数解析/类型转换逻辑（那是上个任务 `06-16-fix-tool-json-array-parse` 的范围）。
- 不涉及 provider 层。
