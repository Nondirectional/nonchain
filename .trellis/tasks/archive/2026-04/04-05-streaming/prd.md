# Streaming Output Support

## Goal
Add streaming (token-by-token) output support to the LLM provider layer, enabling chat applications to display responses incrementally.

## Requirements

### API Design
- Consumer callback pattern: `streamChat(messages, Consumer<ChatChunk> callback)` parallel to existing `chat()` methods
- All `streamChat()` methods return `ChatResult` (the assembled full result after stream ends)
- Callback receives `ChatChunk` for each token delta

### New Model: ChatChunk
- `deltaContent` (String) — text token fragment
- `deltaThinking` (String) — thinking content fragment (for thinking mode)
- `deltaToolCalls` (List<DeltaToolCall>) — tool call fragments with index-based concatenation
- `finishReason` (String) — stream finish reason (only on last chunk)

### LLM Interface Extensions
Mirror all 8 `chat()` overloads as `streamChat()` with added `Consumer<ChatChunk>` parameter:
- `streamChat(String, String, Consumer<ChatChunk>)`
- `streamChat(List<Message>, Consumer<ChatChunk>)`
- `streamChat(String, String, List<Tool>, Consumer<ChatChunk>)`
- `streamChat(List<Message>, List<Tool>, Consumer<ChatChunk>)`
- `streamChat(String, String, OutputFormat, Consumer<ChatChunk>)`
- `streamChat(List<Message>, OutputFormat, Consumer<ChatChunk>)`
- `streamChat(String, String, List<Tool>, OutputFormat, Consumer<ChatChunk>)`
- `streamChat(List<Message>, List<Tool>, OutputFormat, Consumer<ChatChunk>)`

### Tool Call Handling
- Streaming collects tool_call fragments by index, concatenating function arguments
- After stream ends, returns complete `ChatResult` with assembled toolCalls

### Implementation: DashscopeLLM
- Use SDK's `client.chat().completions().createStreaming(params)` returning `Flowable<ChatCompletionChunk>`
- Consume via `blockingForEach` (Java 11 compatible, no RxJava knowledge required from users)
- Map SDK's `ChatCompletionChunk` to `ChatChunk`
- Assemble final `ChatResult` from accumulated chunks

## Acceptance Criteria
- [ ] `ChatChunk` class with delta fields + static factory methods
- [ ] `LLM` interface extended with `streamChat()` methods (default delegates to abstract methods)
- [ ] `DashscopeLLM` implements streaming via `createStreaming()`
- [ ] Streaming tool calls correctly concatenated by index
- [ ] Streaming thinking content supported
- [ ] Streaming example in `example/` package
- [ ] Unit tests for ChatChunk and streaming assembly logic
- [ ] Existing `chat()` methods unaffected

## Technical Notes
- SDK: `com.openai:openai-java:4.30.0`, streaming returns `io.reactivex.Flowable<ChatCompletionChunk>`
- Java 11 target, no new dependencies needed
- Follow project conventions: immutable value objects, builder pattern, no `get` prefix on accessors
