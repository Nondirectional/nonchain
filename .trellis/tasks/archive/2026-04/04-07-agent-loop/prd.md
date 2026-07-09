# Agent Loop

## Goal

为 nonchain 框架添加 Agent 能力的核心：agent loop（工具调用循环）。一个工具 + 一个循环 = 一个 Agent。

## Requirements

### 核心组件：Agent 类（`com.non.chain.agent.Agent`）
- 构造：接受 `LLM`、`ToolRegistry`（必需），system prompt、maxIterations（可选）
- 入口方法：
  - `ChatResult run(String query)` — 简单查询入口
  - `ChatResult run(List<Message> messages)` — 多轮对话入口
- 循环调用 LLM，自动执行工具，直到无工具调用或达到最大迭代次数
- 返回最终的 `ChatResult`
- Builder 模式构造（遵循项目风格）

### 控制流
- 默认最大迭代次数：10
- 退出条件：`!result.hasToolCalls()`（模型认为任务完成）
- 超出最大迭代：抛出 `AgentException`

### 工具执行
- 遍历 `ChatResult.toolCalls()`，逐一调用 `ToolRegistry.execute(name, arguments)`
- 将结果作为 `Message.toolResult(id, content)` 追加到消息列表
- 工具执行异常时，将错误信息作为 tool result 返回给 LLM（让它自我修复），不中断循环

### 错误处理
- 新增 `AgentException` 运行时异常（超出最大迭代等）
- 工具执行失败：catch 后将错误消息作为 tool result 传回 LLM

## Acceptance Criteria

- [ ] `Agent` 类实现 agent loop 核心逻辑（同步模式）
- [ ] Builder 模式构造（llm, toolRegistry 必需；systemPrompt, maxIterations 可选）
- [ ] `run(String)` 和 `run(List<Message>)` 两个入口
- [ ] 工具调用自动执行并追加结果到对话历史
- [ ] 最大迭代次数保护，超出时抛出 `AgentException`
- [ ] 工具执行失败时错误信息传回 LLM
- [ ] 示例代码：`chain-example/AgentLoopExample.java`
- [ ] 单元测试覆盖核心循环逻辑

## Definition of Done

- Tests added/updated
- Lint / build green
- Example 代码可运行
- 与现有 LLM / Tool / Message 模型无破坏性变更

## Technical Approach

### 新增文件
1. `chain/src/main/java/com/non/chain/agent/Agent.java` — 核心 agent loop
2. `chain/src/main/java/com/non/chain/agent/AgentException.java` — 异常类
3. `chain-example/src/main/java/com/non/chain/example/AgentLoopExample.java` — 示例
4. `chain/src/test/java/com/non/chain/agent/AgentTest.java` — 单元测试

### 核心逻辑伪代码
```java
public ChatResult run(String query) {
    List<Message> messages = new ArrayList<>();
    if (systemPrompt != null) messages.add(Message.system(systemPrompt));
    messages.add(Message.user(query));
    return runWithLoop(messages);
}

private ChatResult runWithLoop(List<Message> messages) {
    List<Tool> tools = toolRegistry.getTools();
    for (int i = 0; i < maxIterations; i++) {
        ChatResult result = llm.chat(messages, tools);
        messages.add(result.toMessage());
        if (!result.hasToolCalls()) return result;
        for (ToolCall tc : result.toolCalls()) {
            String output = safeExecute(tc);
            messages.add(Message.toolResult(tc.id(), output));
        }
    }
    throw new AgentException("超出最大迭代次数: " + maxIterations);
}

private String safeExecute(ToolCall tc) {
    try {
        return toolRegistry.execute(tc.name(), tc.arguments());
    } catch (Exception e) {
        return "工具执行失败: " + e.getMessage();
    }
}
```

## Out of Scope

- 流式 agent loop（后续迭代）
- 多 Agent 协作
- Agent 记忆/上下文管理
- 权限/安全控制
- 事件回调/观察者模式

## Technical Notes

### 关键依赖文件
- `provider/LLM.java` — LLM 接口
- `ChatResult.java` — toMessage(), hasToolCalls()
- `Message.java` — toolResult(), assistantWithToolCalls()
- `tool/ToolRegistry.java` — getTools(), execute()
- `tool/ToolCall.java` — id(), name(), arguments()

### 参考来源
- learn-claude-code s01-the-agent-loop: https://github.com/shareAI-lab/learn-claude-code/blob/main/docs/zh/s01-the-agent-loop.md
