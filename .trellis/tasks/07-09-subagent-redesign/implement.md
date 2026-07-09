# Implement — nonchain SubAgent 系统重做:前台/后台并行执行

> 依赖 `prd.md`(D1-D13)与 `design.md`。本文件描述实现顺序、验证命令、风险点与 review gate。
> 核心原则:**前台路径零回归**——每一步都要保证不引入后台能力的 Agent 与 0.9.0 行为完全一致。

## 0. 实现策略

按"数据结构 → 基础能力 → 编排器 → 循环接入 → 高级能力 → 测试与文档"分层推进。
每一步独立可编译/可测试,降低 Agent.java 大改的风险。前台同步路径作为回归基准贯穿始终。

---

## Step 1:核心枚举与值对象

新增基础类型,无逻辑,仅编译验证。

- [ ] 新增 `SubAgentStatus` 枚举(`RUNNING/COMPLETED/STEERED/ABORTED/FAILED`)
  - `chain/src/main/java/com/non/chain/agent/SubAgentStatus.java`
- [ ] 新增 `SubAgentResult` record(`content` + `status` + `displayText()` 含 status note)
  - `chain/src/main/java/com/non/chain/agent/SubAgentResult.java`
- [ ] 新增 `JoinResult`(轮末 join 的产物:`mergedMessage()` + `hasUnconsumed()` + `isEmpty()`)
  - `chain/src/main/java/com/non/chain/agent/JoinResult.java`

验证:

```bash
rtk mvn -pl chain compile -q
```

---

## Step 2:SubAgentDefinition 扩展(D7 + D10)

- [ ] `SubAgentDefinition` 新增 `chatMemoryStore` 字段(nullable,null = 无状态 0.9.0 语义)
- [ ] `SubAgentDefinition` 新增 `chatMemoryStore()` accessor
- [ ] `SubAgentRegistration` Builder 新增 `.chatMemoryStore(ChatMemoryStore)` 方法
- [ ] **D10 fail-fast:** `build()` 时检查 `toolRegistry` 是否注册了 subAgent,是则抛
      `IllegalStateException("子代理不支持嵌套委派: <name>")`

验证:

```bash
rtk mvn -pl chain compile -q
rtk mvn -pl chain test -Dtest=*SubAgent* -q
```

新增测试 `SubAgentDefinitionTest`:
- chatMemoryStore 默认 null
- build() 时 toolRegistry 含 subAgent 抛异常(D10)

---

## Step 3:Agent steer 基础能力(D6)

给 Agent 增加 steer 队列与循环检查点。**仅子代理实例启用,顶层 Agent 不启用。**

- [ ] `Agent` 新增字段 `BlockingQueue<String> pendingSteers`(默认 null = 未启用)
- [ ] `Agent` 新增公开方法 `steer(String message)`:pendingSteers==null 时抛
      `UnsupportedOperationException("steer 仅支持后台子代理")`
- [ ] `Agent.Builder` 新增**包级**方法 `enableSteer()`(设 pendingSteers 为新 LinkedBlockingQueue)
- [ ] `doRunWithLoop` 每轮 LLM 调用**前**插入 steer 检查点:drain pendingSteers,作为
      `Message.user()` 加入 messages
- [ ] **回归验证:** 顶层 Agent(未调 enableSteer)行为与 0.9.0 完全一致

验证:

```bash
rtk mvn -pl chain test -Dtest=AgentTest -q
```

关键回归点:
- 顶层 Agent run() 仍正常工作(steer() 抛异常)
- enableSteer 的 Agent 能通过 steer() 注入消息,下一轮生效

---

## Step 4:graceful max turns 状态机(D9)+ runInternal 分层(瑕疵A)

改造循环上界,引入 grace 阶段。**顶层和子代理统一走 graceful**(review 关键点1,消除 isSubAgent 分叉)。

- [ ] `Agent` 新增常量 `DEFAULT_GRACE_TURNS = 3`
- [ ] `Agent.Builder` 新增**公开**方法 `graceTurns(int)`(默认 3;**0 = 禁用 graceful,回退 0.9.0 硬截断抛异常**)
- [ ] `Agent` 新增字段 `int graceTurns`(替代原 isSubAgent 设计)
- [ ] **瑕疵A 分层:** 新增内部方法 `runInternal(messages, sink)` 返回 `SubAgentResult`(content + status);
      公开 `run(query)` / `run(query, sink)` 包装它返回 ChatResult(丢弃 status)
- [ ] 改造 `doRunWithLoop`(实际逻辑移入 `runInternal`):循环上界 = maxIterations + graceTurns;
      round==maxIterations 时自动注入"收尾"消息(复用 pendingSteers 或 add user message);
      超界 break 返回部分结果 + `ABORTED`;grace 内完成标 `STEERED`;正常完成标 `COMPLETED`
- [ ] **顶层也 graceful(§9.2 破坏性变更):** 顶层 Agent 超 maxIterations 不再抛异常,
      走 graceful 返回部分结果。`graceTurns(0)` 时回退硬截断(抛异常,0.9.0 语义)

验证:

```bash
rtk mvn -pl chain test -Dtest=AgentTest -q
```

新增测试 `GracefulMaxTurnsTest`:
- Agent(顶层)到达 maxIterations → 收到"收尾"消息 → grace 内完成 → STEERED
- Agent grace 也超 → ABORTED,不抛异常,返回部分结果
- `graceTurns(0)` → 超 maxIterations 抛 AgentException(0.9.0 回退路径)
- runInternal 返回的 SubAgentResult.status 正确(COMPLETED/STEERED/ABORTED)

---

## Step 5:子代理构造改造(steer + graceful + resume)

改造 `executeSubAgentTool` 及子 Agent 构造逻辑。

- [ ] 子 Agent Builder 调用 `.enableSteer()`(后台子代理)或保持不启用(前台)
- [ ] 子 Agent Builder 设置 `graceTurns`(与顶层统一,不再有 isSubAgent 分叉)
- [ ] **resume 数据流(D7):** `executeSubAgentTool` 构造 childMessages 时:
  - if (def.chatMemoryStore != null): `history = store.getMessages(conversationId)`
    - history 非空 → resume:`[systemPrompt] + history + [user(task)]`,不注入父上下文(D12)
    - history 空 → 首次:走现有父上下文注入逻辑
  - if (def.chatMemoryStore == null): 走 0.9.0 逻辑(无状态)
- [ ] 委派完成后(前台/后台),若有 chatMemoryStore:`store.updateMessages(conversationId, 去掉systemPrompt的childMessages)`
- [ ] **conversationId 生成(瑕疵C):**
  - 前台:`<parentRunId>:<subAgentName>`(连续 resume)
  - 后台:`<parentRunId>:<subAgentName>:<recordId>`(并发隔离,互不覆盖)

验证:

```bash
rtk mvn -pl chain test -Dtest=*SubAgent* -q
```

新增测试 `SubAgentResumeTest`:
- 配置 InMemoryChatMemoryStore 的前台子代理:首次委派注入父上下文
- 第二次委派同一前台子代理:走 resume,不注入父上下文,带历史
- 后台子代理并发 spawn 两个同名:conversationId 含 recordId,历史互不覆盖
- 无 chatMemoryStore 的子代理:两次委派独立(0.9.0 回归)

---

## Step 6:后台默认截断 context(D12)

- [ ] `Agent` 新增常量 `BACKGROUND_CONTEXT_SELECTOR`(最近 4 条可见消息)
- [ ] `executeSubAgentTool`(及后台 spawn 路径)根据前台/后台选择默认 selector:
  - 前台:`DEFAULT_CONTEXT_SELECTOR`(0.9.0 全量)
  - 后台:`BACKGROUND_CONTEXT_SELECTOR`(截断)
  - 子代理注册的 `contextSelector` 优先级最高(覆盖两者)

验证:

```bash
rtk mvn -pl chain compile -q
```

---

## Step 7:SubAgentRecord 与工具 schema 扩展

- [ ] 新增 `SubAgentRecord`(id/name/task/future/status/result/pendingSteers/resultConsumed)
  - `chain/src/main/java/com/non/chain/agent/SubAgentRecord.java`
- [ ] `ToolRegistry` 子代理 tool schema 新增 `run_in_background` 可选参数(D11)
- [ ] `ToolRegistry` 新增 `get_subagent_result` 工具的 schema(D3)
- [ ] `ToolRegistry` 新增 `steer_subagent` 工具的 schema(D6)
- [ ] `resolveToolsForCurrentExposureMode` 把 `get_subagent_result`/`steer_subagent` 加入
      工具列表(仅当存在已注册子代理时)

验证:

```bash
rtk mvn -pl chain test -Dtest=ToolRegistryTest -q
```

测试点:
- 独立子代理 tool schema 含 run_in_background
- delegate tool schema 含 run_in_background
- 有子代理时 get_subagent_result / steer_subagent 出现在工具列表
- 无子代理时不出现

---

## Step 8:BackgroundSubAgentManager(D1/D2/D3/D4 核心编排器)

本次最重的实现单元。

- [ ] 新增 `BackgroundSubAgentManager implements AutoCloseable`
  - `chain/src/main/java/com/non/chain/agent/BackgroundSubAgentManager.java`
- [ ] 字段:running map / pending 队列 / completedUnconsumed / bgExecutor / maxRunning /
      spawnCeiling / totalSpawned / eventSink
- [ ] `spawn(def, task, ...)`:熔断检查 → 记录 → 入队或提交 → 返回即时 tool result
- [ ] 后台任务体:构造子 Agent(enableSteer + graceful)→ run → 填 record → completedUnconsumed
- [ ] `joinCompleted()`:收集 completedUnconsumed,合并成 JoinResult(D13 合并一条消息),标记 consumed
- [ ] `awaitAll(timeoutMs)`:阻塞等所有 running 完成,超时取消
- [ ] `steer(recordId, message)`:注入到 record.pendingSteers
- [ ] `getResult(recordId, wait)`:D3 get_subagent_result 后端
- [ ] `close()`:取消 running future + shutdown bgExecutor(D2 清理)
- [ ] 独立线程池:`Executors.newFixedThreadPool(maxRunning)`(D4)

验证:

```bash
rtk mvn -pl chain test -Dtest=BackgroundSubAgentManagerTest -q
```

测试点:
- spawn 后立即返回,task 在后台跑
- 运行上限:超 maxRunning 进队列,完成一个 drain 一个
- 熔断:超 spawnCeiling 拒绝 spawn
- joinCompleted:合并多个完成结果
- awaitAll:超时取消未完成的
- close:取消所有 running

---

## Step 9:Agent 循环接入 manager(D3 死循环防护 + 瑕疵B 五路分流)

改造 `doRunWithLoop`,这是最易回归的一步。

- [ ] `doRunWithLoop` 入口(round 0)创建 `BackgroundSubAgentManager`,存入局部变量
- [ ] **瑕疵B 五路分流:** `dispatchExecute` 扩展为五路(design §4.1.1):
  1. hasSubAgent → 解析 run_in_background → 前台同步 / 后台 spawn
  2. DELEGATE_TOOL_NAME → 同上分流
  3. GET_RESULT_TOOL_NAME → bgManager.getResult(id, wait, WAIT_TIMEOUT)  ★ 瑕疵E:wait 有超时
  4. STEER_TOOL_NAME → bgManager.steer(id, message)
  5. else → 普通工具
- [ ] 本轮 tool 执行后(串行/并行路径之后)插入轮末 join:
      `JoinResult jr = bgManager.joinCompleted(); if (!jr.isEmpty()) messages.add(jr.mergedMessage());`
- [ ] Complete 前(`!result.hasToolCalls()` 分支):`bgManager.awaitAll()` → 若有未消费,
      注入 + continue(让 LLM 再看一轮);无则真正 return
- [ ] finally 块:`bgManager.close()`
- [ ] **死循环防护验证(4.1 三重保证):**
  - joinCompleted 只注入已完成,不 spawn
  - spawn 受熔断
  - awaitAll 有超时

验证:

```bash
rtk mvn -pl chain test -Dtest=AgentTest -q
rtk mvn -pl chain test -Dtest=BackgroundSubAgentTest -q
```

新增测试 `BackgroundSubAgentTest`:
- 父代理 spawn 后台子代理,本轮不阻塞,继续推理
- 后台完成后,下一轮 LLM 看到注入的结果
- 父代理调 get_subagent_result(wait=true)阻塞等待,有超时保护(瑕疵E)
- 父代理调 steer_subagent 转向后台子代理
- Complete 前自动等待后台完成
- 死循环场景:连续 spawn 不无限跑(熔断 + awaitAll 超时)
- **瑕疵D:同轮前台+后台混合 toolCall**——一个前台 writer(同步)+ 一个后台 research(立即返回),
  allOf.join() 等齐,结果按序回灌,语义正确
- **回归:** 不 spawn 后台的 Agent run() 与 0.9.0 一致(除顶层 graceful,见 Step 4)

---

## Step 10:AgentEvent 生命周期事件(D5)

- [ ] `AgentEvent` 新增 6 个内部类:`SubAgentSpawned/Started/Completed/Failed/Steered/Aborted`
- [ ] `BackgroundSubAgentManager` 在状态转换点通过 `eventSink` 发射事件
- [ ] **验证隔离:** 子代理内部 TextDelta/ToolStart 不透出(callback=noop 不变)

验证:

```bash
rtk mvn -pl chain test -Dtest=*SubAgent*Event* -q
```

测试点:
- spawn → SubAgentSpawned 事件
- 完成 → SubAgentCompleted 事件
- steer → SubAgentSteered 事件
- 子代理内部 ToolStart 不出现在父事件流

---

## Step 11:Agent.Builder 后台配置(D4)

- [ ] `Builder` 新增 `backgroundExecutor(ExecutorService)`(可选,默认 newFixedThreadPool(4))
- [ ] `Builder` 新增 `maxBackgroundRunning(int)`(默认 4)
- [ ] 熔断 spawnCeiling 计算:`maxIterations × maxBackgroundRunning × 2`(D4 自适应)
- [ ] 可选:`spawnCeiling(int)` 显式覆盖

验证:

```bash
rtk mvn -pl chain compile -q
```

---

## Step 12:trace 接入(D5 不变承诺)

- [ ] 后台子代理构造时注入父 tracer + parentSpanContext(0.9.0 边界1 不变)
- [ ] 后台 worker 线程的 ThreadLocal 处理:参考现有并行路径(Agent.java:290 捕获 llmCtx 模式)
- [ ] 验证后台子代理 span 挂父委派 tool span 下,全树下钻可用

验证:

```bash
rtk mvn -pl chain test -Dtest=*Trace* -q
```

---

## Step 13:示例与文档

- [ ] 新增 `chain-example/.../BackgroundSubAgentExample.java`:
  - research + writer 两个子代理,演示后台并行 spawn + get_subagent_result + steer
- [ ] 更新 `README.md` SubAgent 章节:后台模式、steer、resume、graceful
- [ ] 更新 `CHANGELOG.md`

验证:

```bash
rtk mvn -pl chain-example compile -q
```

---

## Step 14:全量回归

- [ ] `rtk mvn -pl chain test -q`(全部 chain 测试)
- [ ] `rtk mvn -pl chain-example compile -q`
- [ ] 重点回归:
  - 0.9.0 前台 SubAgent 行为(SubAgentExample 仍正常)
  - 顶层 Agent maxIterations 抛异常
  - 普通工具(非子代理)行为不变
  - trace 全树下钻

---

## 风险点

### 高风险
- **Step 9(Agent 循环接入):** 最易回归。前台路径、普通工具、死循环防护都在此步。
  缓解:joinCompleted/awaitAll 设计为空操作安全(无后台任务时零影响);每一步独立测试
- **Step 4(graceful 状态机 + 顶层破坏性变更):** 顶层超限从抛异常改为 graceful(§9.2)。
  缓解:CHANGELOG 显著标注;`graceTurns(0)` 回退路径;回归测试覆盖抛异常路径

### 中风险
- **Step 8(manager 并发):** 多线程状态管理。缓解:用 ConcurrentHashMap/CopyOnWriteArrayList/
  LinkedBlockingQueue 等线程安全集合,volatile 标记状态
- **Step 3(steer 时序):** steer 非即时中断(下一轮才生效)。缓解:文档明确,测试验证时序

### 低风险
- Step 1/2/6/7/10/11/12/13:新增类型或配置,回归面小

## 回滚点

- **Agent 循环回归(Step 9):** 回滚 doRunWithLoop 改动,保留 Step 1-8 的类型(无害,未接入)
- **graceful 回归(Step 4):** 设 graceTurns=0 禁用,退化为 0.9.0 硬截断
- **后台整体问题:** 不调 run_in_background=true 即退化为纯前台(0.9.0)
- **manager 并发问题:** 回滚 BackgroundSubAgentManager,前台子代理仍可用

## Review Gate

进入实现前(`task.py start`)应确认:

- [ ] `prd.md` D1-D13 全部已被接受
- [ ] `design.md` 以下关键设计被接受:
  - 死循环防护三重保证(4.1)
  - 五路分流(4.1.1,瑕疵B)
  - steer 仅后台子代理启用(4.3)
  - **顶层和子代理统一走 graceful(4.4,review 关键点1)——破坏性变更,顶层超限不再抛异常**
  - runInternal 分层传递 status(4.4,瑕疵A)
  - resume 复用 ChatMemoryStore,前后台都支持,conversationId 区分(4.5,瑕疵C)
  - 后台绑定 run() 内(D2,close 清理)
- [ ] 可接受顶层 graceful 破坏性变更(§9.2),`graceTurns(0)` 作为回退路径
- [ ] 可接受 grace 期间允许工具(D9)、join 合并一条消息(D13)、熔断自适应(D4)

确认后执行:

```bash
rtk python3 ./.trellis/scripts/task.py start .trellis/tasks/07-09-subagent-redesign
```
