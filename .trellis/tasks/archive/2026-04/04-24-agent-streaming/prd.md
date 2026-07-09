# Agent Streaming Output

## Goal

让 Agent 的 `run()` 方法支持流式输出，使调用者能够实时接收 LLM 的文本内容、思考过程和工具调用进度，而不是等待整个循环结束后才返回结果。

## What I already know

* LLM 层已完整支持流式：`LLM.streamChat()` 接受 `Consumer<ChatChunk>` 回调
* ChatChunk 支持 `deltaContent`、`deltaThinking`、`deltaToolCalls` 三种增量数据
* AbstractOpenAILLM 已有 `ToolCallAccumulator` 处理增量工具调用参数拼接
* Agent 当前仅使用阻塞式 `llm.chat()`，整个 agent loop 是同步阻塞的
* 现有 `StreamingChatExample` 展示了直接 LLM 流式用法，但不涉及 Agent + Tool 循环
* MockLLM 的 streamChat 直接抛 UnsupportedOperationException

## Decisions

### D1: API 风格 — Consumer 回调

与 LLM 层 `streamChat(chatRequest, Consumer<ChatChunk>)` 保持一致。Agent 新增 `run(messages, Consumer<AgentEvent>)` 重载。

### D2: 事件类型 — 完整版

```java
enum AgentEventType {
    TEXT_DELTA,        // LLM 文本输出增量
    THINKING_DELTA,    // LLM 思考输出增量
    TOOL_CALL_DELTA,   // LLM 生成工具参数的增量
    TOOL_START,        // 工具开始执行
    TOOL_END,          // 工具执行完成
    ROUND_START,       // 新一轮 LLM 调用开始
    ROUND_END,         // 一轮结束
    ERROR,             // 错误
    COMPLETE           // Agent 循环结束
}
```

## Requirements

* Agent 提供流式 `run(messages, Consumer<AgentEvent>)` 重载方法
* Agent 内部使用 `llm.streamChat()` 替代 `llm.chat()`，转发 LLM chunk 为 AgentEvent
* 支持 9 种事件类型的完整生命周期
* 多轮工具调用场景下，每轮有 ROUND_START/ROUND_END 标记
* 保持与现有阻塞式 `run()` API 的向后兼容
* AgentEvent 使用 sealed interface + record 变体（Java 17+ 惯用风格）

## Acceptance Criteria

* [ ] Agent 提供流式 run 方法，调用者能实时接收 LLM 输出
* [ ] 多轮工具调用场景下，每轮 LLM 响应都能流式推送
* [ ] 工具调用开始/完成有明确的 TOOL_START/TOOL_END 事件
* [ ] LLM 生成工具参数时有 TOOL_CALL_DELTA 增量事件
* [ ] 每轮有 ROUND_START/ROUND_END 标记
* [ ] 错误场景有 ERROR 事件
* [ ] COMPLETE 事件携带最终 ChatResult
* [ ] 现有阻塞式 API 不受影响
* [ ] MockLLM/测试支持流式场景

## Definition of Done

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes

## Out of Scope (explicit)

* (待确认)

## Technical Approach

### 核心变更

1. **新增 `AgentEvent` sealed interface** — 9 种事件类型，每种对应一个 record
2. **修改 `Agent.runWithLoop()`** — 接受可选 `Consumer<AgentEvent>`，有则走 streamChat 路径，无则保持原 chat 路径
3. **流式循环流程**:
   - 发 ROUND_START → llm.streamChat() → 转发 TEXT_DELTA/THINKING_DELTA/TOOL_CALL_DELTA → 发 ROUND_END
   - 如有 tool calls → 发 TOOL_START → safeExecute() → 发 TOOL_END → 继续循环
   - 循环结束 → 发 COMPLETE(最终 ChatResult)

### 关键文件

* Agent: `chain/src/main/java/com/non/chain/agent/Agent.java` — 核心循环 `runWithLoop()` (L108-148)
* LLM interface: `chain/src/main/java/com/non/chain/provider/LLM.java` — `streamChat()` (L52-88)
* AbstractOpenAILLM: `chain/src/main/java/com/non/chain/provider/AbstractOpenAILLM.java` — `doStreamChat()` (L393-445), `ToolCallAccumulator` (L536-556)
* ChatChunk: `chain/src/main/java/com/non/chain/ChatChunk.java` — 流式 chunk 数据模型
* StreamingChatExample: `chain-example/src/main/java/com/non/chain/example/StreamingChatExample.java`

## Open Questions

* (已全部解决)

## Technical Notes

### 扩展性考量

1. **未来演进**: AgentEvent sealed interface 便于后续添加新事件类型而不破坏现有消费者
2. **一致性**: 与 LLM 层 Consumer 风格保持一致
3. **工具执行流式**: 当前 TOOL_START/TOOL_END 标记工具执行边界；如未来工具本身需流式输出（如长时间运行的任务），可在 TOOL_END 中扩展
