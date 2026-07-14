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
- No provider-type or remote-model capability detection is performed; callers choose the
  mode for their deployed Chat Template.

### 4. Validation & Error Matrix

| Condition | Result |
|---|---|
| Mode omitted | `SYSTEM` |
| Mode is `SYSTEM` | One `system` Skill injection per activation |
| Mode is `USER` | One marked `user` Skill injection per activation |
| Mode is `null` | Fallback to `SYSTEM` |
| Child Agent is dynamically built | Inherit parent mode |
| No `SkillRegistry` | No Skill messages; existing Agent behavior |

### 5. Good / Base / Bad Cases

- Good: `.skillInjectionMode(SkillInjectionMode.USER)` for a deployed model whose template
  cannot accept multiple system messages.
- Base: omit the setting and rely on default `SYSTEM` for existing deployments.
- Bad: infer the mode from `VLLM` class alone; a provider type does not guarantee the model's
  actual Chat Template capability.

### 6. Tests Required

- Top-level default test asserts a selected Skill remains a `system` message.
- Top-level `USER` test asserts tool result, `[Skill: name]` boundary, full content, and no
  duplicate Skill `system` message.
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
Agent.builder(llm, tools)
    .skillRegistry(skills)
    .skillInjectionMode(SkillInjectionMode.USER)
    .build();
```
