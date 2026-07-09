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
