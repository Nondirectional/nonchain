# Implement — Agent 工具拦截器（beforeToolCall/afterToolCall）

> 依赖 design.md 的 D1-D4 决策。按此顺序实现，每步可独立编译/测试。

## 实现顺序

### Step 1：新增 5 个类型（纯新增，零依赖现有改动）

先建数据类型，不碰 Agent/ToolRegistry，确保编译通过后再动核心。

- [ ] `chain/src/main/java/com/non/chain/agent/ToolCallContext.java`
  - 不可变值对象，两个构造器（before 4 参 / after 6 参），no-prefix accessor（quality-guidelines §1）
  - 字段：toolCallId, toolName, arguments, assistantMessage, result(after), isError(after)
- [ ] `chain/src/main/java/com/non/chain/agent/BeforeResult.java`
  - 不可变，private 构造器，静态工厂 `pass()` / `block(reason)`（block 兜底默认文案）
- [ ] `chain/src/main/java/com/non/chain/agent/AfterResult.java`
  - 不可变，静态工厂 `keep()` / `content()` / `error()` / `builder()`；Boolean isError 三态
- [ ] `chain/src/main/java/com/non/chain/agent/BeforeToolCall.java`
  - `@FunctionalInterface`，`BeforeResult before(ToolCallContext)`
- [ ] `chain/src/main/java/com/non/chain/agent/AfterToolCall.java`
  - `@FunctionalInterface`，`AfterResult after(ToolCallContext)`

**验证**：`mvn -pl chain compile -q`（应零错误）

### Step 2：重构 ToolRegistry.execute 移除 callback（D3 修正）

grep 证实全库 30+ 处 `new ToolRegistry()` 全用无参构造器（callback=noop），`new ToolRegistry(callback)`/`new ToolRegistry(chainContext)` 零调用方——ToolRegistry 的 callback 触发是死代码 + 职责越界。直接移除。

- [ ] `ToolRegistry.execute(name, arguments)`（ToolRegistry.java:121-142）重构为：只做 `entries.get` + 未注册抛异常 + `return doExecute(entry, arguments)`。**删除**其中的 `callback.onToolStart/onToolComplete/onToolError` 三处触发（129/135/139 行）和 `traceId`/`toolCall` 局部变量。
- [ ] **删除** `ToolRegistry(ChainCallback callback)` 构造器（ToolRegistry.java:37-39）
- [ ] **删除** `ToolRegistry(ChainContext chainContext)` 构造器（ToolRegistry.java:41-43）
- [ ] **删除** `private final ChainCallback callback;` 字段（ToolRegistry.java:31）+ 无参构造器里的 `this.callback = ChainCallbackUtil.noop()`
- [ ] **删除** 相关 import（`ChainCallback`、`ChainCallbackUtil`、`ChainContext`、`ChainTrace`、`ToolStartEvent`、`ToolCompleteEvent`、`ToolErrorEvent`）
- [ ] `doExecute`、`parseArguments`、`convertType`、`getTools`、`scan`、`register` 等全部不动

**验证**：`mvn -pl chain compile -q`（确认无残留引用）；`mvn -pl chain test -Dtest=ToolRegistryTest -q`（现有工具测试应全绿——它们从不依赖 ToolRegistry 的 callback）；`mvn -pl chain test -Dtest=ChainCallbackTest -q`（tool 事件测试应全绿——它们通过 Agent.callback 注入 collector，Agent 触发，不依赖 ToolRegistry）

### Step 3：Agent.Builder 加拦截器字段

- [ ] `Agent.java` Builder 新增 `List<BeforeToolCall> beforeInterceptors` / `List<AfterToolCall> afterInterceptors`（初始化为空 ArrayList）
- [ ] 新增 `addBeforeToolCall(BeforeToolCall)` / `addAfterToolCall(AfterToolCall)` 方法（null 防御）
- [ ] `build()` 把两个 list 做不可变拷贝传给 Agent（`Collections.unmodifiableList`）
- [ ] Agent 类新增两个 final 字段持有

**验证**：`mvn -pl chain compile -q`

### Step 4：重构 safeExecute（核心，design §4.2-4.3）

这是最关键一步。改 `safeExecute` 签名为 `safeExecute(ToolCall tc, Message assistantMessage, String traceId)`。

- [ ] safeExecute 新逻辑（按 design §4.2）：
  1. callback.onToolStart（唯一触发点）
  2. before 链：遍历 beforeInterceptors，任一 block → callback.onToolError(reason) → return reason
  3. 执行：`toolRegistry.execute(...)`（Step 2 已重构为不触发 callback）；执行异常 catch → result="工具执行失败:"+msg, isError=true（保持软失败语义）
  4. after 链：遍历 afterInterceptors，链式应用 content/isError 改写
  5. callback: isError ? onToolError : onToolComplete（唯一触发点）
  6. return result
- [ ] **异常区分**（design §4.3）：拦截器异常 → 包装 `AgentException` 抛出；工具执行异常 → 软失败回灌（现状语义）。用嵌套 try 区分。
- [ ] 不配拦截器时，逻辑等价于"callback + execute"，与现状一致（ToolRegistry 的 callback 本就打到 noop）。

**验证**：`mvn -pl chain compile -q`

### Step 5：改造调用点（串行 + 并行）

- [ ] 串行路径（Agent.java:250-259）：`safeExecute(tc, result.toMessage(), traceId)`（`result` 是当前轮 ChatResult，`.toMessage()` 得 assistant Message）
  - 注意：doRunWithLoop 里 `result` 是 ChatResult，第 205 行 `messages.add(result.toMessage())`。safeExecute 需要 Message，用 `result.toMessage()`。但更直接：result 本身可转，或直接传 `messages.get(messages.size()-1)`（刚 add 的 assistant）。选 `result.toMessage()` 避免依赖 list 状态。
- [ ] 并行路径（Agent.java:225-238）：lambda 内 `safeExecute(tc, result.toMessage(), traceId)`
  - 并行 lambda 捕获 `result`（effectively final），线程安全（ChatResult 不可变）。

**验证**：`mvn -pl chain test -q`（全套，重点看 AgentTest）

### Step 6：回归测试（R6 + R5）

先确保现有全绿，再加新测试。

- [ ] `mvn -pl chain test -q` 全绿（重点：testToolExecutionErrorPassedToLLM 软失败语义、testParallelExecutionWithOneFailure、testParallelToolExecution）
- [ ] 若 testToolExecutionErrorPassedToLLM 失败：检查软失败路径，确保执行异常仍产出 "工具执行失败:连接超时" 文本回灌

### Step 7：新增拦截器单测（design 对应场景）

在 `AgentTest.java` 或新建 `ToolInterceptorTest.java` 加测试：

- [ ] `testBeforeToolCallBlock`：before block → tool 不执行（用标志位验证 handler 未被调）→ reason 回灌 LLM → 第二轮 LLM 收到 reason
- [ ] `testBeforeToolCallPass`：before pass → 正常执行
- [ ] `testAfterToolCallModifyContent`：after 改 content（脱敏）→ LLM 收到改写后内容
- [ ] `testAfterToolCallMarkError`：after 标 isError → callback.onToolError 触发
- [ ] `testMultipleBeforeInterceptorsShortCircuit`：2 个 before，第一个 block → 第二个不调
- [ ] `testMultipleAfterInterceptorsChain`：2 个 after 链式（第一个改 content，第二个在改后基础上再改）
- [ ] `testInterceptorInParallelMode`：并行多工具 + before/after 拦截器，结果按源序、拦截生效
- [ ] `testInterceptorThrowsBecomesAgentException`：拦截器抛异常 → 抛 AgentException（不静默吞）
- [ ] **`testCallbackFiredOncePerToolCall`**（R5 核心）：计数 onToolStart/onToolComplete，断言每次工具调用各只 1 次（修复前是 2 次）

**验证**：`mvn -pl chain test -q` 全绿

### Step 8：chain-example 示例

- [ ] `chain-example/src/main/java/com/non/chain/example/ToolInterceptorExample.java`
  - 演示：before 拦截危险命令（如 args 含 "rm -rf" → block）+ after 脱敏（结果中的数字替换为 ***）
  - 用 MockLLM 或注释说明（example 可能需要真实 API，看现有 example 风格——参考 FunctionCallRawExample）

**验证**：`mvn -pl chain-example compile -q`

### Step 9：spec 更新（Phase 3.3）

- [ ] 评估是否在 `.trellis/spec/backend/` 新增 `tool-interceptor.md` 或更新 `tool-function-calling.md`
  - 记录：拦截器 ≠ callback（控制 vs 观察）、callback 单一触发点约定、并行模式线程安全约定
  - 至少在 tool-function-calling.md 加一节"Tool Interceptors vs Callback"

## 验证命令汇总

```bash
# 编译
mvn -pl chain compile -q
mvn -pl chain-example compile -q

# 全套测试（核心）
mvn -pl chain test -q

# 单测类
mvn -pl chain test -Dtest=AgentTest -q
mvn -pl chain test -Dtest=ToolRegistryTest -q
mvn -pl chain test -Dtest=ToolInterceptorTest -q   # 新增
```

## 风险文件 & 回滚点

| 文件 | 风险 | 回滚 |
|---|---|---|
| `Agent.java` safeExecute | 最高：改 callback 触发 + 拦截逻辑 | git revert Agent.java，其余新类可保留（无害） |
| `ToolRegistry.java` | 中：重构 execute + 删构造器（回归靠 ToolRegistryTest/ChainCallbackTest） | git revert ToolRegistry.java |
| 5 个新类 | 无：纯新增 | 删除即可 |

**分步回滚策略**：
1. Step 1-3 可独立保留（新类型 + Builder 字段不影响现有行为，未使用 = 无害）
2. Step 4-5 是行为变更点，若回归失败，单独 revert Agent.java 的 safeExecute + 调用点
3. ToolRegistry 改动（Step 2）与 Agent 独立，可单独回滚

## Follow-up（本任务不做，记录给 P1）

- terminate hint（after 返回 terminate + 批次投票跳过下一轮）→ AfterResult 加字段 + doRunWithLoop 加检查
- shouldStopAfterTurn（turn 结束后优雅停止）
- preflight 串行 + execute 并发两阶段重构（让 block/not-found 不占线程池）
