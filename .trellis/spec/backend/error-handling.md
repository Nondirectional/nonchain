# Error Handling

> How errors are handled in this project.

---

## Overview

This project uses standard Java exceptions. No custom exception hierarchy is defined. Errors are handled close to where they occur, with descriptive messages in Chinese.

---

## Error Types

| Exception | Usage | Example |
|-----------|-------|---------|
| `IllegalArgumentException` | Invalid arguments, unsupported operations | `"不支持的消息角色: " + msg.role()` (DashscopeLLM.java:113), `"未注册的工具: " + name` (ToolRegistry.java:88) |
| `IllegalStateException` | Precondition violations, invalid configuration | `"未找到节点: " + nextNode` (Graph.java:42), `"Graph 必须包含至少一个 Node"` (Graph.java:91) |
| `RuntimeException` | Wrapping reflective invocation failures | `"工具执行失败: " + name` wrapping the cause (ToolRegistry.java:113) |

---

## Error Handling Patterns

### 1. Fail fast with descriptive messages

Preconditions are checked at the top of methods with clear Chinese error messages:

```java
// Graph.java:89-95
public Graph build() {
    if (nodes.isEmpty()) {
        throw new IllegalStateException("Graph 必须包含至少一个 Node");
    }
    if (startNode == null) {
        throw new IllegalStateException("必须指定起始节点");
    }
    return new Graph(name, startNode, nodes, edges);
}
```

### 2. Unwrap cause for reflective calls

When reflection-based tool invocation fails, unwrap the cause:

```java
// ToolRegistry.java:109-114
try {
    Object result = entry.method.invoke(entry.target, callArgs);
    return result != null ? result.toString() : "null";
} catch (Exception e) {
    throw new RuntimeException("工具执行失败: " + name, e.getCause() != null ? e.getCause() : e);
}
```

### 3. Graceful fallback for optional values

Use `Optional` and null checks instead of throwing:

```java
// DashscopeLLM.java:141-171
return completion.choices().stream()
    .findFirst()
    .map(choice -> { /* build ChatResult */ })
    .orElse(new ChatResult("无响应", null));
```

---

## API Error Responses

This is a library, not a web service. There is no API error response format. Consumers of this library should catch `IllegalArgumentException` and `RuntimeException` as needed for their application layer.

---

## Common Mistakes

1. **Don't add custom exception classes** — The project intentionally uses standard Java exceptions. Adding a hierarchy would add complexity without benefit at this scale.
2. **Don't silently swallow errors** — Always include the cause exception when wrapping.
3. **Don't use checked exceptions** — All methods use unchecked exceptions to keep the API ergonomic for lambda/functional use.
