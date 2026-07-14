# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

This is a small Java 11+ library (minimum Java 11, compatible with JDK 11/17/21). Quality is enforced through consistent coding patterns rather than tooling. There is no linter, no static analysis, and no CI pipeline configured.

---

## Required Patterns

### 1. Immutable value objects with accessor methods (no getters)

Use private final fields, constructor injection, and no-prefix accessor methods:

```java
// Good — Message.java style
public class ToolCall {
    private final String id;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String name, String arguments) { ... }

    public String id() { return id; }       // NOT getId()
    public String name() { return name; }   // NOT getName()
}
```

### 2. Builder pattern for complex objects

Use static `builder()` factory with inner `Builder` class:

```java
// Good — Tool.java, Graph.java style
Tool tool = Tool.builder("get_weather")
    .description("查询天气")
    .addProperty("city", "string", "城市名", true)
    .build();
```

### 3. Static factory methods over constructors

Named factory methods are clearer than constructor overloading:

```java
// Good — Message.java style
Message.system("You are a helper")
Message.user("What is the weather?")
Message.toolResult(callId, result)

// NOT: new Message("system", "You are a helper")
```

### 4. Interfaces for abstraction

Define contracts as interfaces, keep implementations in the same package:

```java
// Good — provider/LLM.java defines the contract
// DashscopeLLM.java implements it in the same package
```

### 5. Multimodal content parts

For multimodal messages (text + images), use `ContentPart` implementations with `Message.user(List<ContentPart>)`:

```java
// Good — multimodal user message
Message msg = Message.user(Arrays.asList(
    ImageUrlPart.of("https://example.com/image.jpeg"),
    TextPart.of("Describe this image")
));
llm.chat(Arrays.asList(msg));

// Text-only still uses the simple overload
Message msg = Message.user("What is the weather?");
```

**Rules**:
- `ContentPart` is a marker interface in root package (`com.non.chain`)
- Implementations are immutable value objects with static `of()` factory
- Provider layer (`DashscopeLLM`) converts `ContentPart` to SDK types internally
- Core model classes (`ContentPart`, `TextPart`, `ImageUrlPart`) must not import external libraries

### 6. Functional interfaces for extensibility

Use `@FunctionalInterface` for single-method contracts:

```java
// Good — ToolHandler.java
@FunctionalInterface
public interface ToolHandler {
    String execute(ToolArgs args);
}
```

---

## Forbidden Patterns

### 1. No `get`/`set` prefixes

```java
// Bad
public String getContent() { return content; }
public void setContent(String content) { this.content = content; }

// Good
public String content() { return content; }
```

### 2. No mutable public fields

```java
// Bad
public String name;

// Good
private final String name;
public String name() { return name; }
```

### 3. No checked exceptions in public API

```java
// Bad — forces try-catch on every caller, breaks lambda usage
public ChatResult chat(...) throws IOException { ... }

// Good — use unchecked exceptions
public ChatResult chat(...) { ... }
```

### 4. No external dependencies in core models

Core classes (`Message`, `ChatResult`, `ToolCall`) must not import external libraries. Provider-specific code (e.g., OpenAI SDK imports) belongs in `provider/` only.

### 5. No hardcoded API keys in committed code

```java
// Bad — current examples have this
new DashscopeLLM("sk-xxxxxxxxxxxxxxxxxxxxxxxx", "qwen-plus", 512)

// Good — use environment variables
new DashscopeLLM(System.getenv("DASHSCOPE_API_KEY"), "qwen-plus", 512)
```

---

## Testing Requirements

- Test framework: JUnit 3.8.1 (configured in pom.xml — consider upgrading)
- No tests currently exist
- When adding tests:
  - Place in `src/test/java/com/non/chain/` mirroring the source structure
  - Test public API only, not internal implementation details
  - Use the `LLM` interface to mock provider calls

---

## Code Review Checklist

- [ ] Accessor methods use no-prefix style (`content()` not `getContent()`)
- [ ] Value objects are immutable (final fields, no setters)
- [ ] Complex objects use Builder pattern
- [ ] No external dependencies in core model classes
- [ ] No checked exceptions in public API
- [ ] Chinese error messages for user-facing exceptions
- [ ] No hardcoded API keys or secrets
- [ ] New packages follow the existing flat structure (no deep nesting)

---

## Skill Injection Contract

### 1. Scope / Trigger

This contract applies when an Agent exposes a `SkillRegistry` and a model may reject
multiple `system` messages in its Chat Template.

### 2. Signatures

```java
public enum SkillInjectionMode { SYSTEM, USER }

Agent.Builder skillInjectionMode(SkillInjectionMode mode)
```

The setting is immutable after `Agent.Builder.build()` and is copied to dynamically
constructed sub-agents.

### 3. Contracts

- Default mode: `SYSTEM`.
- `SYSTEM`: selected Skill produces `Message.system(content)` in addition to the required
  tool result message.
- `USER`: selected Skill produces `Message.user("[Skill: " + name + "]\n" + content)` in
  addition to the required tool result message.
- `USER` mode keeps the Skill name boundary so injected knowledge is distinguishable from
  the user's original text.
- Passing `null` to `skillInjectionMode` resolves to `SYSTEM`.
- Model compatibility is declared on the LLM instance, not inferred from `VLLM` or another
  provider class. `SYSTEM` is automatically converted to a framework-bounded user message
  when the instance declares that it does not support multiple system messages; explicit
  `USER` remains user injection.

### 4. Validation & Error Matrix

| Condition | Result |
|---|---|
| Mode omitted | `SYSTEM` |
| Mode is `SYSTEM` and model supports multiple system | One `system` Skill injection per activation |
| Mode is `SYSTEM` and model does not support multiple system | One user injection with `[Framework System Instruction]` boundary |
| Mode is `USER` | One marked `user` Skill injection per activation |
| Mode is `null` | Fallback to `SYSTEM` |
| Child Agent is dynamically built | Inherit parent mode |
| No `SkillRegistry` | No Skill messages; existing Agent behavior |

### 5. Good / Base / Bad Cases

- Good: `llm.supportsMultipleSystemMessages(false)` for the concrete model instance whose
  Chat Template rejects non-leading system messages.
- Base: omit the setting and rely on default `SYSTEM` for existing deployments.
- Bad: infer the mode from `VLLM` class alone; a provider type does not guarantee the model's
  actual Chat Template capability.

### 6. Tests Required

- Top-level default test asserts a selected Skill remains a `system` message.
- Top-level `USER` test asserts tool result, `[Skill: name]` boundary, full content, and no
  duplicate Skill `system` message.
- Unsupported-model test asserts `SYSTEM` is converted only in the request copy and the
  Agent transcript remains a system message.
- Sub-agent `USER` test asserts dynamic child construction inherits the parent mode.
- Full `chain` Maven tests must pass without an online provider.

### 7. Wrong vs Correct

Wrong:

```java
if (llm instanceof VLLM) {
    // Assume every vLLM deployment rejects multiple system messages.
}
```

Correct:

```java
llm.supportsMultipleSystemMessages(false);
Agent.builder(llm, tools).skillRegistry(skills).build();
```

---

## LLM Request Message Normalization Contract

### 1. Scope / Trigger

This contract applies whenever an LLM Chat Template may reject multiple `system` messages or
when a SubAgent receives a sliced parent transcript containing incomplete tool-call groups.

### 2. Signatures

```java
interface LLM {
    default boolean supportsMultipleSystemMessages(); // default true
    default LLM supportsMultipleSystemMessages(boolean supported);
    default List<Message> prepareMessages(List<Message> messages);
}
```

`AbstractOpenAILLM` implements a fluent setter with default `true`; `OpenAICompatibleLLM`,
`VLLM`, and `DashscopeLLM` return their concrete type. `Agent` passes a request copy to
`chat`/`streamChat`; `AbstractOpenAILLM` repeats the same idempotent normalization before
building SDK parameters for direct provider calls.

### 3. Contracts

- Parent `system` messages are always excluded at the SubAgent context boundary; they are never
  converted to user messages or merged into the child system prompt.
- Parent `USER` Skill messages remain ordinary visible user context and are subject to the selector.
- Selector results always drop `llmVisible=false` messages and incomplete/orphaned
  `assistant(toolCalls)` / `tool` groups.
- For `supportsMultipleSystemMessages=true`, request message order and system roles are preserved.
- For `false`, retain only the first visible system when it is the first visible message; convert
  later systems (or all systems when the first visible message is not system) to
  `Message.user("[Framework System Instruction]\\n" + content)`.
- A converted system between an assistant tool call and its continuous tool results is deferred
  until all matching tool results have been appended.
- Normalization never mutates the original Agent transcript, ChatMemory, callback payload, or trace.

### 4. Validation & Error Matrix

| Condition | Result |
|---|---|
| Capability omitted | `supportsMultipleSystemMessages() == true` |
| Built-in provider setter called | Instance capability changes and setter remains chainable |
| Custom LLM uses default setter | `UnsupportedOperationException` (no silent no-op) |
| Selector returns `null` | Empty parent context before child task is appended |
| Selector returns parent system | Dropped before SubAgent execution |
| Selector returns invisible note | Dropped before SubAgent execution |
| Tool group misses a result or has an orphan result | Entire invalid fragment is dropped |

### 5. Good / Base / Bad Cases

- Good: declare `llm.supportsMultipleSystemMessages(false)` on the actual deployed model instance;
  `SYSTEM` Skill content becomes a bounded user message only in the provider request.
- Base: default capability `true` preserves existing multi-system behavior.
- Bad: append parent system after the child system prompt or manually filter messages in every
  application selector; both reintroduce provider-order and protocol bugs.

### 6. Tests Required

- Capability default/setter tests through the `LLM` interface.
- First-system, non-leading-system, deferred-system, and idempotence tests.
- SubAgent selector tests for parent system, invisible messages, complete and incomplete tool groups.
- Full module and reactor Maven tests without an online provider.

### 7. Wrong vs Correct

Wrong:

```java
if (llm instanceof VLLM) {
    // Treat every vLLM deployment as if it has the same Chat Template capability.
}
```

Correct:

```java
llm.supportsMultipleSystemMessages(false);
// Agent keeps its internal transcript unchanged and normalizes only the request copy.
```

---

## SubAgent Progress Event Contract

### 1. Scope / Trigger

This contract applies when a parent Agent exposes a streaming `eventConsumer` and delegates
foreground or background work to a SubAgent.

### 2. Signatures

```java
interface AgentEvent {
    final class SubAgentProgress implements AgentEvent {
        String subAgentId();
        String name();
        String task();
        String parentToolCallId();
        boolean background();
        AgentEvent event();
    }
}
```

### 3. Contracts

- A parent event consumer automatically receives one `SubAgentProgress` per child
  `AgentEvent`; no additional Builder opt-in exists.
- `event()` preserves the original child event type and payload (`RoundStart`, deltas,
  tool events, `SkillActivated`, `Complete`, or `AgentError`).
- `subAgentId` is a UUID for foreground calls and the existing `SubAgentRecord.id` for
  background calls; it is stable for that invocation.
- `parentToolCallId` is the triggering `ToolCall.id()` and may be null.
- Events are ordered per invocation, may interleave across background invocations, and the
  consumer must be thread-safe.
- Consumer exceptions are isolated for progress events and cannot change child business
  results. Existing parent event behavior remains unchanged.
- No parent lifecycle events are added for foreground calls; existing background lifecycle
  events remain unchanged.

### 4. Validation & Error Matrix

| Condition | Result |
|---|---|
| No parent event consumer | Child runs through non-streaming overload; no progress wrappers |
| Parent consumer + foreground child | Progress wrappers with `background=false` |
| Parent consumer + background child | Progress wrappers with record ID and `background=true` |
| Same child name called twice | Different progress IDs |
| Consumer throws on progress | Child result and parent tool result remain unchanged |
| Child emits an error | Wrapped `AgentError`; existing outer error path remains |

### 5. Good / Base / Bad Cases

- Good: route `SubAgentProgress.event()` by `instanceof` and group UI state by `subAgentId`.
- Base: ignore unknown event types while preserving existing parent event handling.
- Bad: cast every incoming event directly to `TextDelta`; progress wrappers intentionally add
  one level of envelope.

### 6. Tests Required

- Foreground Mock LLM test asserts Skill schema, Skill tool call, tool result, injected message,
  wrapped `SkillActivated`, context fields, and original Round/Text/Complete events.
- Repeated-call test asserts distinct IDs for same-name invocations.
- Background test asserts progress ID equals `SubAgentSpawned.subAgentId`, parent tool-call ID,
  task, and background flag.
- Consumer-failure test asserts final parent result remains successful.
- Full `chain` and `chain-example` Maven tests pass.

### 7. Wrong vs Correct

Wrong:

```java
event -> ((AgentEvent.SkillActivated) event).skillName();
```

Correct:

```java
event -> {
    if (event instanceof AgentEvent.SubAgentProgress) {
        AgentEvent.SubAgentProgress progress = (AgentEvent.SubAgentProgress) event;
        AgentEvent childEvent = progress.event();
        // Group by progress.subAgentId(), then inspect childEvent with instanceof.
    }
}
```
