# fix chatmemory orphan tool trimming

## Goal
修复 `ChatMemory` 在裁剪历史消息时的一个边界情况：当异常顺序导致 `tool` 消息出现在其配对的 `assistant(toolCalls)` 之前时，不能单独删除该 `tool`，否则会留下无对应 `tool_result` 的工具调用上下文，形成无效的 LLM 输入。

## What I already know
- `Message` 已支持 `assistant(toolCalls)` 与 `tool(toolCallId)` 两类工具消息。
- `MessageSerializer` 会持久化并恢复 `toolCalls` / `toolCallId`。
- `Agent` 会在工具循环结束后把新增 `assistant` 与 `tool` 消息同步回 `ChatMemory`。
- `MessageWindowChatMemory` 与 `TokenWindowChatMemory` 当前都只在从 `assistant(toolCalls)` 开始删除时，才会把后续连续 `tool` 一起删掉。
- 当前任务只修复“异常顺序下孤立 `tool` 被单独裁剪”的边界问题，不处理“中间轮次状态提前持久化”的另一个问题。

## Assumptions (temporary)
- 异常顺序主要表现为一个或多个连续 `tool` 消息紧邻其后的 `assistant(toolCalls)`。
- 若 `tool` 找不到可匹配的 `assistant(toolCalls)`，则仍允许把它当作真正孤立消息单独删除。

## Open Questions
- 无阻塞问题；实现时采用防御式匹配并以现有测试风格补回归。

## Requirements (evolving)
- 裁剪时不得留下只有 `assistant(toolCalls)` 没有匹配 `tool` 结果的上下文。
- 正常顺序下现有工具消息配对保护行为保持不变。
- `MessageWindowChatMemory` 与 `TokenWindowChatMemory` 行为保持一致。
- 尽量避免继续复制两份复杂配对逻辑。

## Acceptance Criteria (evolving)
- [ ] `MessageWindowChatMemory` 在 `tool -> assistant(toolCalls)` 异常顺序下，会把相关消息成组删除。
- [ ] `TokenWindowChatMemory` 在相同异常顺序下，也不会留下无效工具调用上下文。
- [ ] 现有正常顺序工具消息保护测试继续通过。

## Definition of Done (team quality bar)
- Tests added/updated (unit/integration where appropriate)
- Lint / typecheck / CI green
- Docs/notes updated if behavior changes
- Rollout/rollback considered if risky

## Out of Scope (explicit)
- 不修复工具调用中间轮次保存时机问题。
- 不修改 `Agent` 的消息持久化策略。
- 不调整 `Message` / `MessageSerializer` 的数据结构。

## Technical Notes
- 涉及文件：`chain/src/main/java/com/non/chain/memory/MessageWindowChatMemory.java`、`chain/src/main/java/com/non/chain/memory/TokenWindowChatMemory.java`
- 现有测试：`chain/src/test/java/com/non/chain/memory/MessageWindowChatMemoryTest.java`、`chain/src/test/java/com/non/chain/memory/TokenWindowChatMemoryTest.java`
- 可考虑抽出共享辅助类，避免两套裁剪逻辑继续漂移。
