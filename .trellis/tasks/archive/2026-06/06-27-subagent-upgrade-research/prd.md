# 调研 SubAgent 升级方案

## Goal

调研 nonchain 当前框架在 Agent、Tool、Graph 等层面的现状，明确“支持 SubAgent”应落在哪个抽象层、解决哪些真实使用场景、保持哪些兼容性约束，并通过逐轮问题收敛出可实施的需求边界。

## Requirements

- 基于仓库现有代码、测试、文档和近期变更，整理与 SubAgent 相关的已确认事实。
- 明确当前框架是“单 Agent + 工具循环”为主，还是已经存在可复用的多代理编排能力。
- 明确用户期望的 SubAgent 能力边界，包括但不限于：
  - SubAgent 是框架级一等抽象，还是用工具/工作流模式即可表达
  - SubAgent 主要解决委派、并行、多专家协作、上下文隔离、审核升级中的哪几类问题
  - 父子 Agent 的消息边界、工具边界、memory 边界、callback/trace 边界是否需要隔离
- 在不破坏现有 Agent / Tool / Graph / Message / Callback 兼容性的前提下，收敛最小可行范围（MVP）。
- 通过逐题拷问，把仍然无法从代码库回答的问题压缩到真正的产品决策与取舍问题。

## Acceptance Criteria

- [ ] 已记录与 SubAgent 相关的当前框架事实，包括代码入口、测试覆盖和缺失能力。
- [ ] 已确认仓库中是否存在原生 SubAgent / multi-agent / delegate 抽象，而不是依赖猜测。
- [ ] 已形成一组可测试、可设计的需求边界，能够支撑后续 `design.md` 和 `implement.md`。
- [ ] 仅对代码库无法回答的事项向用户提问，并且每次只问一个高价值问题。
- [ ] 每轮用户回答后，`prd.md` 持续更新，记录最新共识和剩余开放问题。

## Confirmed Facts

- 仓库当前没有 `SubAgent`、`sub-agent`、`multi-agent`、`delegate` 等相关实现或文档命名。
- 当前框架 README 将 Agent 描述为“LLM + 工具自动调用循环”，并强调 memory、callback、流式事件、工具并行执行和工具拦截器。
- `chain/src/main/java/com/non/chain/agent/Agent.java` 当前核心职责是：
  - 维护单个对话消息列表
  - 调用单个 `LLM`
  - 解析 `ToolCall`
  - 执行 `ToolRegistry` 中的工具
  - 把工具结果回灌给同一条消息链后继续下一轮
- `Agent` 已支持：
  - `ChatMemory` 注入
  - `ChainCallback` 生命周期回调
  - 流式 `AgentEvent`
  - 多工具并行执行
  - `BeforeToolCall` / `AfterToolCall` 拦截器
- `Agent.run(List<Message>)` 是显式消息入口，不走 `ChatMemory` 自动读写；这为“无状态子代理 + 显式注入父上下文”提供了天然接入点。
- `ToolCallContext` 当前只携带 `toolCallId/toolName/arguments/assistantMessage/result/isError`，没有父级完整 transcript。
- `ToolRegistry` 当前负责工具 schema 暴露、JSON 参数解析、类型转换和执行；它不知道 Agent、会话或子代理。
- 当前工具结果主通道是字符串：
  - `ToolHandler.execute(ToolArgs)` 返回 `String`
  - `ToolRegistry.execute(...)` 返回 `String`
  - `Message.toolResult(toolCallId, content)` 只承载字符串内容
  - `ToolCompleteEvent` 结果字段也是 `String`
- `LLM` 接口支持 `OutputFormat.TEXT / JSON_OBJECT`，但当前 `Agent` 循环调用使用默认文本路径，没有对子代理结构化结果回传提供现成编排。
- 应用层消息已支持 `llmVisible=false`，并在 provider 边界被自动剥离；这意味着父上下文共享时可复用现有消息过滤语义。
- 当前可观测性模型是扁平的：
  - `ChainCallback` 只有 LLM / Tool / Retrieval / Graph 事件
  - `AgentEvent` 只有轮次、文本增量、思考增量、工具开始/结束、完成/错误
  - 没有现成的“child agent event envelope”或父子 trace 关联协议
- `Graph` 工作流引擎当前是通用状态流转编排，不是 Agent 生命周期管理器，也没有父子 Agent 抽象。
- 现有公开 API 风格偏向注册中心 + Builder：`ToolRegistry.register(...).param(...).handle(...)`、`Agent.builder(...)`、`Graph.builder(...)`。
- 测试覆盖已证明的能力集中在单 Agent 循环、工具调用、memory 和记拦截器；未见子代理协作测试。
- `chain-example` 当前提供 Agent 循环和 Graph 工作流示例，但没有任何多 Agent / delegate / SubAgent 示例。
- Agent 功能是逐步演进出来的：先有基本循环，再加 callback、memory、streaming、并行工具和拦截器。
- 用户已确认首个 MVP 场景是“委派型子代理”，不是多专家并行协作、监督审查链或 Graph 编排型多代理。

## Requirements Clarified

- 首个 SubAgent MVP 以“父 Agent 委派一个子任务给子代理执行，并拿回结果”为核心能力。
- 当前阶段不要求先支持多子代理辩论、投票、仲裁或复杂图式协作。

## Out Of Scope

- 本阶段不直接开始实现 SubAgent。
- 本阶段不默认引入外部调度框架、线程模型大改或分布式执行能力。
- 本阶段不把普通工作流节点误当成 SubAgent 能力完成品。

## Decisions Locked

- 委派触发：MVP 主路径是“父 Agent 通过 LLM tool calling 自主触发委派”。
- 场景边界：首个 MVP 是委派型子代理，不做多专家并行协作、监督审查链或 Graph 编排型多代理。
- 子代理默认状态：默认无状态执行，但预留共享父代理上下文能力。
- 父上下文策略：
  - 默认采用裁剪后的委派上下文，不传完整父 transcript
  - 由框架提供自动裁剪，全局默认 + 子代理级可选覆盖
  - 默认包含相关 `user / assistant / tool` 消息
  - 继续排除 `llmVisible=false` 的应用层消息
  - 默认不包含父 Agent 的 `systemPrompt`
- 子代理角色与结果：
  - 每个子代理独立定义 `systemPrompt`
  - 父代理默认只接收子代理最终文本结果
  - 子代理软失败复用现有普通工具的软失败语义
  - 父代理外层事件记为 `ToolErrorEvent`，但仍回灌错误文本
- 工具边界：
  - 子代理默认只使用自己的专属工具集
  - `toolRegistry` 非必填；未配置即无工具子代理
  - 子代理支持独立 `before/after` 拦截器，不继承父拦截器
  - 父 Agent 的 `before/after` 仍作用于外层子代理 tool 调用
- 循环与执行：
  - 仅支持一层委派，子代理不能再委派
  - 子代理默认继承父 Agent 的 `LLM`，但允许显式覆盖
  - `maxIterations` 独立配置，未设置回退框架默认值
  - 同一轮多个子代理调用允许按现有多工具语义并行执行，并按原始顺序回灌结果
- 可观测性：
  - 父子 `callback/trace` 默认隔离
  - 父侧默认把子代理视为一次普通工具调用，不展开内部事件
- 注册与暴露：
  - SubAgent 注册主入口在 `ToolRegistry`
  - 第一版只支持 fluent API 注册，不要求注解式
  - `ToolRegistry.registerSubAgent(...)` 采用声明式配置 Builder 作为主形态，而不是直接注册一个预构建 `Agent`
  - 子代理注册时 `description` 与 `systemPrompt` 分开定义，且 `description` 必填
  - 每个独立子代理 tool 默认只暴露一个必填参数：`task`
- 双模式支持：
  - 首批同时支持两种暴露方式：独立子代理 tool、通用 delegate tool
  - 默认暴露模式是独立子代理 tool；通用 delegate 模式需显式选择
  - 通用 delegate tool 必须显式开启
  - `agentName` 约束为已注册子代理名的枚举
  - 两种模式默认二选一暴露，不在同一轮工具列表里同时出现
  - 模式选择按父 Agent 级别决定，在构建期固定
  - 模式配置入口放在 `Agent.Builder`
  - `Agent.Builder` 上采用“小枚举 + 直接方法”配置暴露模式，而不是独立配置对象
- 支持范围：
  - 首批只保证在 `Agent` 自动循环中可用
  - 在非 `Agent` 自动循环里误用 SubAgent 时，fail-fast 并给出清晰错误
- 上下文覆盖：
  - 首批只支持在子代理注册时注入上下文裁剪/选择策略
  - 不提供运行时逐次覆盖接口

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
