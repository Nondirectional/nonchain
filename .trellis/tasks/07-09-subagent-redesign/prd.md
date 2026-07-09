# SubAgent 系统重做:前台/后台并行执行

## Goal

参照 [tintinweb/pi-subagents](https://github.com/tintinweb/pi-subagents) 的前台/后台并行执行模型,
对 nonchain 现有 SubAgent 系统进行重做升级。不是完全照搬,而是根据 nonchain 作为嵌入式
Java SDK 的实际定位进行裁剪。

**现状(nonchain 0.9.0):** 子代理是同步内联的——父代理调用工具,阻塞等子代理跑完,结果作为
tool result 直接回灌(`Agent.java:487 executeSubAgentTool`)。同一轮多个 toolCall 虽用
`CompletableFuture` 并行(`Agent.java:286-311`),但 `allOf(futures).join()` 仍同步阻塞等齐,
父代理无法"派出去自己继续干"。

**目标:** 引入异步后台执行能力,父代理可并行派发多个子代理、自己继续推理,完成后通过自动 join +
主动拉取消费结果。保留前台同步语义,不破坏既有契约。

---

## 已定决策(Grill 记录)

### D1. 执行模型:前台/后台并存

- **决策:** 保留同步内联前台,新增异步后台 + 并发队列 + 完成通知。
- **理由:**
  - `CompletableFuture` 异步基础设施已具雏形,扩展成本低
  - 前台同步语义对 SDK 用户最简单直观,应保留;后台异步是新增能力,不破坏既有契约
  - pi-subagents 本身也是前台/后台并存,这条路被验证过
- **状态:** ✅ 已确认

### D2. 后台结果流向:仅父 agent 内部

- **决策:** 后台子代理的生命周期**绑定在父 agent 的 run() 循环内**。结果只在父 agent run()
  内被消费。父 agent run() 退出时,未完成的后台子代理被取消(或等待,取决于 D5 join 策略)。
  不向 SDK 外部调用方暴露后台任务管理器。
- **理由:**
  - nonchain 是嵌入式库,不是终端工具。外部任务管理器(像 pi 的 SubAgentManager)对 SDK 场景
    过度复杂
  - 父 agent run() 返回后,应用层本就该拿到最终 ChatResult,不应再有"遗留后台任务"概念
  - 排除了 pi 的 session resume 对后台模式的意义(因为后台任务不会跨 run() 存活)
- **状态:** ✅ 已确认
- **D7 修订:** "任务执行"绑定 run() 内不变;"对话记忆"是正交维度,可通过 `ChatMemoryStore`
  跨 run() 存活(见 D7)。两个维度解耦
- **影响:** 排除了 independent task manager 方案;后台任务执行不跨 run()

### D3. 通知语义:自动 join + 主动拉取

- **决策:**
  1. **轮末自动 join:** 每一轮 tool 执行完、进入下一轮 LLM 推理之前,框架自动检查已完成的后台
     子代理,把结果拼成消息注入 `messages`,LLM 在下一轮看到
  2. **主动拉取:** 提供 `get_subagent_result` 工具,让 LLM 主动查未完成的后台子代理(可带
     `wait:true` 阻塞等待)
  3. **Complete 前强制等齐:** 父代理准备 Complete(本轮无新 toolCall)前,必须等待所有未消费的
     后台子代理完成或超时取消
- **理由:**
  - 纯自动 join(D3-alt-1):LLM 无法主动查特定后台任务状态,透明度不足
  - 纯显式拉取(D3-alt-2):依赖 LLM "记得去查",漏查就丢结果;且 nonchain 没有 pi 的
    "followUp 触发新轮"机制,LLM 不主动查就感知不到完成
  - 两者结合最稳:自动 join 保证不丢结果,get_subagent_result 给 LLM 主动控制权
- **状态:** ✅ 已确认
- **关键边界(死循环防护):**
  - "本轮 spawn 了后台任务"不会无脑触发新轮——自动 join 只在已有 toolCall 的轮末发生
  - Complete 前强制 join,且 join 本身不 spawn 新任务,避免无限循环
  - 需要在 design.md 明确 join 的精确时机和死循环防护机制

---

## 已定决策(Grill 记录·续)

### D4. 并发控制:独立线程池 + 运行上限 + 自适应熔断

- **决策:**
  1. **独立线程池:** 后台子代理用独立的 `Executors.newFixedThreadPool(4)`,不复用父 agent 的
     `executor`(commonPool)。Builder 可配置。理由:LLM 调用是阻塞 IO,ForkJoinPool 不适合;
     与父代理同轮 toolCall 并行隔离,避免互相饿死
  2. **运行上限:** 同时运行的后台子代理默认最多 4 个,超出的进 FIFO 队列。有任务完成时 drain
     队列。此上限作用在单个父 agent run() 内
  3. **总派发熔断:** 防止 LLM 自主循环失控狂 spawn。默认 = `maxIterations × 运行上限 × 2`
     (自适应父 agent 规模),可显式配置。超过则拒绝新 spawn(返回错误 tool result 给 LLM)
- **理由:**
  - nonchain 是 LLM 自主循环,没有人工叫停(不像 pi 父 agent 人驱动),需额外防失控
  - 自适应熔断避免与高 maxIterations 场景冲突(固定值会误杀正常用例)
- **状态:** ✅ 已确认

### D5. 可观测性:新增后台子代理生命周期事件

- **决策:**
  1. 扩展 `AgentEvent`,新增后台子代理生命周期事件类型(至少包含):
     - `SubAgentSpawned`(带 subAgentId、name、task、foreground/background)
     - `SubAgentStarted`(从队列被调度执行)
     - `SubAgentCompleted`(正常完成)
     - `SubAgentFailed`(异常)
     - `SubAgentAborted`(超 max_turns/熔断,关联 D9)
     - `SubAgentSteered`(被转向,关联 D6)
  2. **子代理内部事件隔离:** 子代理的 `TextDelta`/`ToolStart`/`ToolEnd` 等**不透传**到父,
     沿用 0.9.0 的 `callback = noop()` 承诺
  3. **trace 不隔离保持不变:** 子代理 span 仍挂到父委派 tool span 下(0.9.0 边界1),后台模式
     同样适用——这是唯一跨隔离的通道,供 trace 全树下钻
- **理由:**
  - 沿用 0.9.0 隔离承诺,不破坏既有契约
  - 生命周期事件是后台模式的必需可观测维度(没有它应用层看不到后台在干什么)
  - 事件量可控,不淹没父代理主事件流
  - 对齐 pi 的做法(只透生命周期 + 完成通知,不透子代理内部细节)
- **状态:** ✅ 已确认

### D6. Mid-run Steering:做,仅后台

- **决策:**
  1. **仅后台子代理支持 steer**(前台同步无触发源:父代理在 `child.run()` 里阻塞,无法发 steer)
  2. 给 `Agent` 增加 `steer(message)` 方法 + 内部 `BlockingQueue<String> pendingSteers`
  3. 子代理循环**每轮 LLM 调用前** drain `pendingSteers`,作为 user message 注入对话
  4. 暴露 `steer_subagent` 工具给父 LLM(对齐 pi),也允许应用层代码直接调
     `manager.steer(id, message)`
  5. steer 的实现深度(是否联动 graceful max turns)见 D9
- **理由:**
  - 后台子代理跑偏时能纠偏,而不是干等跑完浪费 token
  - 给 `Agent.run()` 加可注入能力是基础改造,但收益明确
- **影响:** Agent 基础能力改造(steer 注入),不只影响 SubAgent
- **状态:** ✅ 已确认

### D7. Session Resume:ChatMemoryStore 复用,opt-in 有状态

- **决策:**
  1. `SubAgentDefinition` 增加可选的 `ChatMemoryStore` 字段(默认 `null` = 无状态,沿用 0.9.0)
  2. 配置了 `ChatMemoryStore` 的子代理变成**有状态**:
     - 每次委派完成,对话历史 `updateMessages(conversationId, messages)` 存入
     - resume(再次委派同一子代理)时 `getMessages(conversationId)` 拉回作为初始消息
  3. `conversationId` = 子代理名 + 父 agent 会话标识(需在 design.md 定义生成规则)
  4. 存活范围由 `ChatMemoryStore` 实现决定:
     - `InMemoryChatMemoryStore` → JVM 进程内(范围甲/乙)
     - `chain-mysql` / `chain-postgres` 实现 → 跨进程/重启(范围丙)
  5. **默认无状态**(无 ChatMemoryStore)时,子代理仍是"每次动态构造、无记忆"——0.9.0 哲学不变
- **关键发现:** nonchain 已有 `ChatMemoryStore` SPI(`getMessages`/`updateMessages`/`deleteMessages`),
  几乎就是为 resume 准备的抽象;`chain-mysql`/`chain-postgres` 模块已提供数据库实现。
  复用现成 SPI,零新抽象
- **对 D2 的修订:** 后台子代理"任务执行"仍绑定 run() 内(不变);"对话记忆"是正交维度,
  可通过 `ChatMemoryStore` 跨 run() 存活。两个维度解耦
- **【review 关键点3 修正】前后台都支持 resume:** 原 grill 倾向"resume 仅前台",review 时
  改为**前后台都支持**。conversationId 区分并发冲突(瑕疵C):
  - 前台:`<parentRunId>:<subAgentName>`(连续 resume)
  - 后台:`<parentRunId>:<subAgentName>:<recordId>`(并发隔离)
- **状态:** ✅ 已确认(经 D7-detail 深入拷问 + review 关键点3 修正)

### D8. 调度 Scheduling:裁剪,留给应用层

- **决策:** 不做 cron/interval/one-shot 定时调度,也不做 spawn 延迟执行(delay)。
- **理由:**
  - Java 生态已有成熟调度方案(ScheduledExecutorService / Spring @Scheduled / Quartz),
    SDK 内置调度等于重造轮子
  - 职责错位:nonchain 是 Agent 框架,不是调度框架。调度是应用编排的关注点
  - pi 需要它因为它是终端工具,无外部调度系统可用;nonchain 嵌入应用,应用天然有调度能力
- **应用层替代方案:** 用 Spring @Scheduled 定时 → 调用 `agent.run()` → 在 run() 内委派子代理
- **状态:** ✅ 已确认(明确裁剪项)

### D9. Graceful Max Turns:完整 graceful,grace 期间允许工具

- **决策:**
  1. **三段式 graceful:** `maxIterations` 到达 → 自动 steer "立即收尾" → grace turns(默认 3)
     → grace 也超才硬中断
  2. **grace 期间允许工具调用**(对齐 pi,不强制禁用)——子代理可借 grace 继续完成必要操作
  3. **引入状态标记:** `completed`(正常)/ `steered`(grace 内收尾成功)/ `aborted`(硬中断)
  4. **硬中断不抛异常:** 返回已有部分结果 + `aborted` 标记(与现状 0.9.0 的 `AgentException` 语义不同)
  5. **前后台都适用**
  6. **【review 关键点1 修正】顶层 Agent 也走 graceful:** 原 grill 倾向"顶层保持 0.9.0 抛异常",
     review 时改为**顶层和子代理统一走 graceful**——消除 isSubAgent 分叉,顶层应用层也能从
     graceful 受益。这是**破坏性变更**(0.9.0 顶层超限抛异常 → 本次返回部分结果)。
     回退方式:`graceTurns(0)` 恢复硬截断抛异常语义。需 CHANGELOG 显著标注
- **理由:**
  - D6 已建 steer 基础设施,graceful 复用它发"收尾"消息,增量成本极低
  - 提升子代理产出质量(体面收尾 vs 硬砍)
  - 前台子代理同样撞 maxIterations,同样需要收尾机会
  - 状态标记让父代理判断产出完整性,对 D3 自动 join 重要(join `aborted` 结果应带警告)
- **与 D6 关系:** graceful 的"收尾"消息就是一种自动 steer,复用 D6 的 pendingSteers 机制
- **与 D5 关系:** `SubAgentSteered` / `SubAgentAborted` 事件归入 D5 生命周期事件
- **状态:** ✅ 已确认

### D10. 嵌套层级:仅一层 + fail-fast

- **决策:**
  1. 保持 0.9.0 的"仅一层"限制,子代理不能再委派
  2. **fail-fast:** 子代理的 `toolRegistry` 如果配置了 `registerSubAgent`,构建时直接抛异常,
     引导用户用父代理编排
  3. 复杂任务的分治靠父代理编排多个子代理(D3 自动 join 天然支持),而非子代理嵌套
- **理由:**
  - 0.9.0 已有明确契约,pi 也主要一层,这条路被验证够用
  - 多层嵌套复杂度指数级:每加一层,D2-D9 几乎所有决策都要重答"嵌套时怎么办"
  - 大多数分治需求可由父代理编排解决
  - fail-fast 让错误早暴露,API 干净
- **状态:** ✅ 已确认

### D11. Agent 定义方式:Builder API + 调用级前后台 + 保持 replace

- **决策:**
  1. **保持 Java Builder API**(0.9.0 不变),不引入 pi 的 Markdown+YAML 声明式定义。
     Builder 是 Java SDK 的天然形态;Markdown 适合终端工具(人类手写),不适合嵌入式库
  2. **前后台 = 调用级**(对齐 pi):由父 LLM 在 tool 调用时通过参数 `run_in_background` 决定。
     同一子代理可被前台或后台派发。DIRECT/DELEGATE 两种暴露模式的 tool schema 都需支持此参数
  3. **prompt_mode 保持 replace**(0.9.0 不变),不引入 append:
     - 子代理用自己的 systemPrompt,不继承父的(`Agent.java:496` 现状不变)
     - 父上下文通过 `ContextSelector` 注入(D12),不靠 systemPrompt 继承
     - append 是 pi 为"general-purpose=父孪生"特定用例设计,nonchain 子代理是专职角色,replace 合适
  4. `SubAgentDefinition` 新增字段:`ChatMemoryStore`(D7)。grace turns 用框架常量(D9),
     不做成可配字段
- **状态:** ✅ 已确认

### D12. 资源隔离:后台截断 context + resume 隔离父上下文 + 裁剪 worktree

- **决策:**
  1. **裁剪 git worktree 隔离:** pi 的文件系统级 worktree 隔离不适用于 nonchain
     (pi 操作真实文件系统,nonchain 是抽象 Agent 框架)。明确裁剪项
  2. **后台默认截断 context:** 引入后台专属上下文策略,后台子代理默认只拿最近 N 条父消息
     (如 4 条)+ 本次 task。前台保持 0.9.0 全量可见消息。Builder 可覆盖
  3. **resume 不注入父上下文:** resume 时子代理构造顺序 =
     `子代理 systemPrompt` + `子代理自己的历史(ChatMemoryStore)` + `本次 task`,
     **不注入父上下文切片**(resume 语义是"继续子代理自己的对话",父上下文已在历史里)。
     非 resume(首次委派)才注入父上下文切片。两种模式语义清晰
  4. 0.9.0 的逻辑级隔离(Context 裁剪 + callback noop + trace 不隔离)在子代理**内部事件**
     层面不变(见 D5);生命周期事件是新透出层
- **理由:**
  - 后台并行多子代理时,每个背全量历史会导致 token 爆炸
  - resume 与首次委派的 context 来源区分,避免重复/冲突
- **状态:** ✅ 已确认

### D13. 裁剪清单:裁剪四项 + join 合并成一条消息

- **裁剪的 pi 特性(汇总):**

| pi 特性 | 裁剪理由 | 来源决策 |
|---|---|---|
| git worktree 隔离 | 文件系统级,不适用抽象 Agent 框架 | D12 |
| cron/interval/one-shot 调度 | Java 生态有成熟方案,职责错位 | D8 |
| spawn delay 延迟执行 | YAGNI | D8 |
| 独立 SubAgentManager 外部管理器 | 后台绑定 run() 内 | D2 |
| Markdown+YAML 声明式定义 | 用 Builder API | D11 |
| prompt_mode append | 保持 replace | D11 |
| followUp 触发新轮机制 | 用轮末自动 join 替代 | D3 |
| 多层嵌套 | 仅一层 + fail-fast | D10 |
| cross-extension RPC / event bus | 单体 SDK 无"其他扩展";AgentEvent 已够 | D5 |
| skill 预加载 | nonchain 无技能系统 | — |
| model scope 守卫 | LLM 对象强类型已天然守卫 | — |
| transcript 文件输出 | 用 trace(有 MySQL/Postgres 持久化)替代 | D5 |

- **join 合并方式:** 同轮完成的后台子代理结果**合并成一条消息注入**(类似 pi group,但实现
  简单——join 时把多个结果拼进一条 user message 或合并的 tool result)。不采用 async 逐个注入
- **状态:** ✅ 已确认

---

## 决策树概览(Grill 路线图)

```
① 执行模型 → D1 ✅ 前台/后台并存
   ├─ ② 结果传递机制 → D2 ✅ 仅父agent内部, D3 ✅ 自动join+主动拉取
   ├─ ③ 并发控制 → D4 ✅ 独立池+运行上限+自适应熔断
   └─ ④ 生命周期事件 → D5 ✅ 新增生命周期事件
⑤ 交互能力
   ├─ ⑥ Mid-run steering → D6 ✅ 做steer,仅后台
   ├─ ⑦ Session resume → D7 ✅ ChatMemoryStore复用,opt-in有状态
   └─ ⑧ 调度 scheduling → D8 ✅ 裁剪,留给应用层
⑨ 边界控制
   ├─ ⑩ Graceful max turns → D9 ✅ 完整graceful,grace允许工具
   └─ ⑪ 嵌套层级 → D10 ✅ 仅一层+fail-fast
⑫ 类型/配置系统
   └─ ⑬ Agent 定义方式 + prompt_mode → D11 ✅ Builder+调用级前后台+保持replace
⑭ 资源隔离(JVM 语境下的等价物)
   └─ ⑮ 持久记忆 / context 继承 → D12 ✅ 后台截断+resume隔离父上下文+裁剪worktree
⑯ 裁剪清单(哪些 pi 特性不适合 Java SDK)→ D13 ✅ 裁剪四项+join合并
```

**Grill 完成。13 个决策全部敲定。**

---

## Acceptance Criteria

### 执行模型与结果传递(D1/D2/D3)

- [ ] 前台子代理语义与 0.9.0 兼容:同步内联,父阻塞等待,结果作为 tool result 回灌
- [ ] 后台子代理可并行运行,父代理 spawn 后不阻塞,继续推理
- [ ] 轮末自动 join:每轮 tool 执行后、下一轮 LLM 推理前,已完成的后台结果注入 messages
- [ ] `get_subagent_result` 工具可用,支持 `wait:true` 阻塞等待未完成的后台子代理
- [ ] Complete 前(本轮无新 toolCall)强制等待所有未消费后台子代理完成或超时取消
- [ ] 死循环防护:自动 join 不 spawn 新任务;本轮 spawn 后台任务不无脑触发新轮
- [ ] 同轮完成多个后台结果时,合并成一条消息注入(D13)
- [ ] 后台子代理生命周期绑定父 agent run() 内,run() 退出时未完成任务被处理(取消/等待)

### 并发控制(D4)

- [ ] 后台子代理使用独立线程池(默认 newFixedThreadPool(4)),不复用父 executor
- [ ] 运行上限默认 4,超出进 FIFO 队列,任务完成时 drain
- [ ] 总派发熔断默认 = maxIterations × 运行上限 × 2,超限拒绝新 spawn 返回错误 tool result
- [ ] 熔断值可显式配置

### 可观测性(D5)

- [ ] 新增 AgentEvent 生命周期事件:SubAgentSpawned/Started/Completed/Failed/Aborted/Steered
- [ ] 子代理内部事件(TextDelta/ToolStart 等)不透传到父(隔离承诺不变)
- [ ] trace 不隔离:后台子代理 span 挂父委派 tool span 下,全树下钻可用

### 交互能力(D6/D7)

- [ ] 后台子代理支持 steer:Agent.steer(message) + 内部 pendingSteers 队列
- [ ] steer 消息在子代理每轮 LLM 调用前作为 user message 注入
- [ ] 父 LLM 可通过 steer_subagent 工具转向后台子代理
- [ ] 前台子代理不支持 steer(无触发源)
- [ ] resume(opt-in):SubAgentDefinition 配置 ChatMemoryStore 后子代理有状态
- [ ] resume 时对话历史通过 ChatMemoryStore 存取(updateMessages/getMessages)
- [ ] **【review】前后台都支持 resume**(关键点3):前台连续 resume + 后台 recordId 隔离(瑕疵C)
- [ ] 默认无 ChatMemoryStore 时,子代理保持 0.9.0 无状态语义

### 边界控制(D9/D10)

- [ ] graceful max turns:maxIterations 到达→自动 steer 收尾→grace turns(默认3)→硬中断
- [ ] grace 期间允许工具调用
- [ ] 硬中断不抛异常,返回部分结果 + aborted/steered 状态标记
- [ ] **【review】顶层和子代理统一走 graceful**(关键点1):顶层超限不再抛异常。
      `graceTurns(0)` 回退硬截断抛异常(0.9.0 语义)
- [ ] runInternal 分层传递 status(瑕疵A):子代理/后台读 SubAgentResult.status
- [ ] 子代理 toolRegistry 配置 registerSubAgent 时构建抛异常(fail-fast,仅一层)

### 配置与隔离(D11/D12)

- [ ] 保持 Builder API,不引入 Markdown 声明式定义
- [ ] 前后台由调用级参数 run_in_background 决定,DIRECT/DELEGATE 模式 schema 均支持
- [ ] prompt_mode 保持 replace(0.9.0 不变)
- [ ] 后台子代理默认截断父上下文(最近 N 条 + task),前台保持全量
- [ ] resume 时不注入父上下文切片(仅子代理 systemPrompt + 自己历史 + task)

### 裁剪验证(D8/D13)

- [ ] 不含 cron/interval/one-shot 调度、spawn delay、git worktree、cross-extension RPC、
      skill 预加载、model scope 守卫、transcript 文件输出、prompt_mode append、多层嵌套

## Notes

- 前序研究任务:`.trellis/tasks/archive/2026-06/06-27-subagent-upgrade-research/`(已完成,产出
  0.9.0 现有 SubAgent 设计)
- 参考实现:`pi-subagents` 仓库 master 分支,核心文件 `agent-runner.ts` / `agent-manager.ts` /
  `group-join.ts` / `index.ts`
- 每次 grill 拷问完成后,将结果追加到本文件的"已定决策"或"待定决策"章节
- 本任务为复杂任务,grill 完成后需补 `design.md` + `implement.md` 方可 `task.py start`
