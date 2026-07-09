# 应用层消息与 LLM 消息分层

> 父任务：`06-24-agent-control-flow-extensibility`

## Goal

为 nonchain 引入**应用层消息 vs LLM 消息**的分层，允许应用把 UI-only 状态（"正在思考"、"已读取文件 X"、"工具审核中"、artifact、通知）记录进对话 transcript 供 UI 重放，同时保证这些消息**不进入 LLM 上下文**，避免污染。

现状：单一 `Message` 模型（`Message.java:9`），进 `messages` 列表的东西最终都会被 `AbstractOpenAILLM.buildMessageListParams` 送到 LLM。UI 状态无处安放——塞进 messages 会污染上下文，不塞则 UI 无法重建历史。

## 现状（已确认事实）

- `Message`（`Message.java:9-13`）：role（system/user/assistant/tool）+ content + contentParts + toolCallId + toolCalls。
- LLM 转换在 provider 内部 `AbstractOpenAILLM.buildMessageListParams`（`AbstractOpenAILLM.java:184-241`），按 role switch 翻译成 SDK 的 `ChatCompletion*MessageParam`。无独立 `convertToLlm` 转换器。
- 流式增量：`AgentEvent`（`AgentEvent.java`）有 TextDelta/ThinkingDelta/ToolCallDelta 等，是事件不是消息。
- 持久化：`MessageSerializer`（`MessageSerializer.java`）序列化 `Message` 到 JSON 存 DB（`chat_memory_message` 表）。
- 裁剪：`MessageWindowChatMemory`/`TokenWindowChatMemory`，含 tool 消息配对保护（`ChatMemoryTrimSupport`）。

## 参考来源

pi-agent-core：
- `AgentMessage = Message | CustomAgentMessages[keyof ...]`（types.ts），应用可通过 declaration merging 加 notification/artifact/status 等自定义消息。
- `convertToLlm`：AgentMessage[] → Message[]，在 LLM 边界过滤掉非 LLM 消息（agent-loop.ts:289）。
- `CustomMessageEntry`（harness/types.ts:385）：带 `display: boolean` 标记是否进 UI、`customType`、`content`、`details`。
- pi 用 declaration merging（TS 特有），nonchain 用 Java 惯用方式表达。

## Requirements

### R1 标记机制（哪些消息不进 LLM）

- 提供一种方式让应用产生"不进 LLM 上下文"的消息。候选方案（design 决策）：
  - **方案 A**：给 `Message` 加可选字段（如 `boolean llmVisible`，默认 true；或 `boolean transient`，默认 false）。
  - **方案 B**：新增独立的 `AppMessage`/`SystemNote` 类型，与 `Message` 并列，统一存进 transcript 但 LLM 边界过滤。
- 选型原则：最小化对现有 `Message` 消费链路（provider 转换、序列化、裁剪）的冲击，且对现有 API 零破坏（向后兼容）。

### R2 LLM 边界过滤

- 在 LLM 调用边界（`AbstractOpenAILLM.buildMessageListParams` 或其上游）过滤掉非 LLM 可见消息，使它们不进 provider 请求。
- 过滤是**单点**的，所有 provider（Dashscope/OpenAICompatible/VLLM，都继承 AbstractOpenAILLM）共用。
- 过滤行为可观测（ChainCallback 可见，或至少有日志）——避免"消息神秘消失"难调试。

### R3 持久化与 UI 重放

- 非 LLM 消息必须能持久化（复用 `MessageSerializer` 或扩展），按 conversationId 存取，供 UI 重建历史时展示。
- 持久化格式不破坏现有 `chat_memory_message` 表结构（向后兼容；新字段可空/可选）。
- 持久化消息在读取后能正确恢复 llmVisible 标记。

### R4 与裁剪策略的交互

- `MessageWindowChatMemory`/`TokenWindowChatMemory` 裁剪时如何对待非 LLM 消息？
  - 候选：裁剪只针对 LLM 可见消息计数/token，非 LLM 消息（如 UI 状态）不占 LLM 预算——但 design 需决策是否计入"消息条数窗口"。
  - 至少：非 LLM 消息不应破坏现有 tool 消息配对保护逻辑（`ChatMemoryTrimSupport`）。

### R5 事件层打通（可选，design 决策）

- 现状 `AgentEvent` 是事件流（瞬时），`Message` 是持久态。非 LLM 消息的"产生"是否对应一个新的 AgentEvent（如 `StatusDelta`/`Note`），让流式消费者也能收到？还是只通过 messages 列表暴露？
- 若引入，需保证事件与消息的语义不混淆。

### R6 默认行为零变更

- 不产生非 LLM 消息时，Agent 行为与现状完全一致。
- 现有 `Message` API（`Message.user/assistant/toolResult/...` 工厂方法）全部保留，默认产出的消息都是 LLM 可见。
- 现有测试全绿。

## Acceptance Criteria

- [x] 有明确的标记机制（字段或新类型），应用能产生"不进 LLM"的消息。
- [x] LLM 边界单点过滤生效：非 LLM 消息不出现在 provider 请求 payload（有测试断言）。
- [x] 非 LLM 消息能持久化（存 + 取），UI 能从 transcript 重建含非 LLM 消息的完整历史。
- [x] 裁剪策略对非 LLM 消息的处理明确且有测试（不破坏 tool 配对保护）。
- [x] 现有 `Message` API 零破坏，现有 `AgentTest`/`AgentMemoryTest` 全绿。
- [x] 新增单测：产生非 LLM 消息、过滤生效、持久化往返、裁剪交互。
- [x] `chain-example` 新增示例：一次 run 中产生 UI 状态消息（如"已读取文件"），验证它进 transcript 但不进 LLM。
- [x] design.md + implement.md 完成（本任务属复杂任务）。

## Out of Scope

- 不引入 pi 的 declaration merging / AgentMessage 联合类型体系（TS 特有，Java 用标记字段或子类型表达即可）。
- 不实现完整的 artifact / notification 消息类型族（只提供机制 + 一个示例类型）。
- 不改 provider 的消息转换逻辑（只在边界加过滤，不动各 role 的 switch 翻译）。
- 不引入 compaction（P2，独立任务）。
- 不涉及会话树/分支（P3）。
