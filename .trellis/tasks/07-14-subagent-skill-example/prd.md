# SubAgent 执行事件与 Skill 注入可观测性

## Goal

消除 SubAgent 执行黑盒：父 Agent 的事件消费者能够观察并区分每次子代理调用内部的 LLM 轮次、文本、工具和 Skill 激活过程；同时用确定性测试证明 Skill 实际注入。

## Confirmed Facts

- `SubAgentSkillExample` 已把 `owasp-checklist` 注册到 `security-reviewer` 的 `SkillRegistry`，并通过子代理 `systemPrompt` 要求首次调用该 Skill。
- 子代理动态构造于 `Agent.runSubAgentInternal`；Skill tool schema 会通过 `childBuilder.skillRegistry(def.skillRegistry())` 挂载。
- 子代理实际执行使用 `child.run(childMessages)`，没有传入父级 `eventConsumer`；因此内部 `RoundStart`、`TextDelta`、`ToolStart/End`、`SkillActivated`、`Complete` 均不会到达父级事件消费者。
- 现有设计明确子代理用户侧 `ChainCallback` 隔离；`AgentEvent` 是独立的流式事件 API，可新增显式子代理事件封装而不取消 `ChainCallback` 隔离。
- 前台子代理在父循环中作为普通 tool 执行，父级目前只能看到外层 `ToolStart/ToolEnd`；后台子代理额外暴露 `Spawned/Started/Completed/Failed` 生命周期，内部过程仍隔离。
- Trace 已能记录完整嵌套 span 树，但它是执行后拉取的遥测，不替代实时 `AgentEvent`。
- 示例使用 VLLM；本任务不修改其 provider 配置，也不以在线 VLLM 结果作为测试依据。

## Requirements

- R1：父 Agent 的 `eventConsumer` 能实时收到每次 SubAgent 内部执行事件，并明确区分父级事件与子代理事件。
- R2：每个子代理内部事件必须携带稳定的调用关联信息，支持同名子代理并行或重复调用时正确归属。
- R3：保留原始子事件类型和载荷，使调用方能观察 LLM 轮次、流式文本、内部工具调用、Skill 激活、完成或失败。
- R4：提供离线 Mock LLM 测试，确认子代理 Skill tool 暴露、tool call、tool result 与实际注入消息的完整链路。
- R5：不破坏已有子代理 `ChainCallback` 隔离、Trace 树、外层 Tool 事件与后台生命周期语义。

## Acceptance Criteria

- [x] 父级事件消费者能收到带子代理调用上下文的 `SkillActivated`，且不会误判为父 Agent 的直接 Skill 事件。
- [x] 前台与后台 SubAgent 内部事件均有明确、可测试的传播语义；并行事件可按调用 ID 归属。
- [x] 无在线模型依赖的测试验证子代理 Skill tool 被暴露并实际激活，且默认 `SYSTEM` 注入包含完整 Skill 内容。
- [x] 测试验证 Skill tool result 与注入消息均存在，父 Agent 最终结果不变。
- [x] `SubAgentSkillExample` 能打印可识别的子代理 Skill 激活过程。
- [x] 既有 `chain` 与 `chain-example` 测试全部通过。

## Out of Scope

- 不修改 VLLM provider、注入模式默认值或自动探测模型 Chat Template。
- 不改变 Skill 选择逻辑、SkillRegistry schema 或 tool-calling 协议。
- 不取消 `ChainCallback` 的父子隔离。

## Decisions

- 采用统一包装事件 `AgentEvent.SubAgentProgress`，包含 `subAgentId`、`name`、`task`、`parentToolCallId`、`background` 和原始 `AgentEvent event`；不直接混流原始子事件。
- 转发全部子代理 `AgentEvent`，包括轮次、思考/文本增量、工具、Skill、完成与错误；调用方自行按内部事件类型过滤。
- 每次调用使用统一 `subAgentId`：前台生成 UUID，后台复用 `SubAgentRecord.id`；`parentToolCallId` 关联触发本次委派的父 tool call。
- 不把后台专用生命周期事件扩展到前台；前台继续使用外层 `ToolStart/ToolEnd` 与包装后的完整内部事件，避免重复完成信号。
- 后台并行子代理直接在各自执行线程投递事件：单个调用内有序，不同调用可交错，消费者必须线程安全。
- 只要父级提供 `eventConsumer` 就自动收到 `SubAgentProgress`；不新增重复的 opt-in Builder 配置，未提供 consumer 时不创建包装事件。
- `task` 直接进入包装事件，调用方负责敏感内容展示策略。
- 隔离 `SubAgentProgress` 消费者异常，前后台均不因观察失败改变子代理业务执行。
- `SubAgentSkillExample` 打印 Round、Tool、Skill、Text、Error、Complete 等代表性过程；`ThinkingDelta` 仍可由 API 获取，但示例默认不打印。
