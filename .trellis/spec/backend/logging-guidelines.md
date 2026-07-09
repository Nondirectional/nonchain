# Logging Guidelines

> How logging is done in this project.

---

## Overview

**This project does not use a logging framework.** Output is done via `System.out.println()` for demo/example code only. There is no logging in library code.

---

## Current Output Convention

Only the `example/` package produces console output:

```java
// Example pattern — System.out.println for demo output
System.out.println("用户问题: " + userQuestion);
System.out.println("正在调用工具 [" + toolCall.name() + "]，参数: " + toolCall.arguments());
System.out.println("工具返回: " + toolResult);
System.out.println("助手最终回复: " + response.content());
```

Workflow nodes use `System.out.println` for trace output:

```java
// EasyWorkflowExample.java — node trace output
System.out.println("[classify] 分类结果: " + state.getOrDefault("category", ""));
System.out.println("[technical] 技术解答完成");
System.out.println("[summarize] 总结完成");
```

---

## What to Output (in examples only)

- User input and final assistant response
- Tool call invocations and results (for debugging function calling)
- Node execution progress in workflows (prefixed with `[nodeName]`)
- Execution path and final state trace

---

## What NOT to Output

- **API keys** — Examples currently hardcode keys (this is a known issue; should use environment variables)
- **Full LLM request/response payloads** — Too verbose, only output summaries
- **Internal state in library code** — Keep library code silent; output belongs in examples

---

## Future Considerations

If a logging framework is introduced:

1. Use SLF4J as the logging facade (standard for Java libraries)
2. Library code should log at `DEBUG` level, not `INFO`
3. Examples can use `INFO` level
4. Never log API keys or user message content at `INFO` or above
