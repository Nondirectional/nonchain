# Design — SubAgent 上下文归一化与模型兼容

## 1. 设计目标与边界

本任务拆成两个互补边界：

1. **Agent 上下文边界**：把 `ContextSelector` 的结果变成合法的 SubAgent 输入，隔离父 system，
   过滤不可见消息，并清除不完整的工具调用组。
2. **LLM 请求边界**：保留 Agent 内部 transcript 的原始角色；在发送给模型的请求副本上，依据
   `LLM` 实例能力声明归一化多 system 消息。

两层都只操作副本，不修改父 Agent 的 `messages`、ChatMemory、事件载荷或 trace 数据。

不按 `VLLM`、`DashscopeLLM` 等 provider 类型判断能力；所有 LLM 默认支持多 system，调用方可对
具体实例显式关闭。`SkillInjectionMode` 继续保留 `SYSTEM` / `USER` 两个可见选项，不引入 `AUTO`。

## 2. 数据流

```text
父 Agent messages
    │
    ├─ ContextSelector.select(...)
    │
    └─ SubAgentContextNormalizer
         ├─ 过滤 llmVisible=false
         ├─ 删除全部父 system
         └─ 保留完整 assistant(toolCalls)+tool 组
                │
                ▼
        子 systemPrompt + 父上下文 + task(user)
                │
                ▼
        Agent 内部消息链（保留原始角色）
                │
        LLM.prepareMessages(messages)  ← 请求副本
                │
        MessageNormalizer(system capability)
                │
        AbstractOpenAILLM.buildMessageListParams()
                │
                ▼
             provider 请求
```

## 3. SubAgent 上下文归一化

### 3.1 入口

在 `Agent.runSubAgentInternal()` 中，`ContextSelector.select(...)` 返回后统一调用内部归一化函数，
再把结果追加到子代理自己的 `systemPrompt` 后面。resume 模式保持现有语义：有子代理历史时不再
注入父上下文。

### 3.2 规则

按以下顺序处理 selector 结果：

1. 对 `null` 结果按空列表处理。
2. 丢弃 `llmVisible=false` 消息；默认 selector 和自定义 selector 走同一规则。
3. 丢弃所有 `role=system` 消息。父 system 不转换成 user，也不合并进子 systemPrompt。
4. 保留普通 user/assistant 消息及其顺序。
5. 只保留完整工具调用组：
   - assistant 带 `toolCalls` 时，必须在后续连续 tool 消息中找到全部 call id；
   - 缺少任一结果、出现非 tool 消息打断、或 tool 消息找不到配对 assistant 时，丢弃该不完整组；
   - 不伪造 tool result，不保留孤立 assistant(toolCalls) 或孤立 tool。
6. 父 Skill 如果是 `USER` 注入，已经是普通 user 消息，按上述可见 user 规则保留；框架不根据
   Skill 来源额外过滤。

归一化不改变原始 `Message` 对象；结果列表只包含原消息引用，后续 Agent 仍以追加方式运行。

### 3.3 工具组算法

实现一个线性扫描：维护当前 assistant(toolCalls) 的 expected call id 集合和已看到的 tool id 集合。
遇到完整组时整体追加；遇到不完整组时跳过 assistant 及其紧邻的 tool 消息；遇到没有活动组的
孤立 tool 时跳过。扫描前先过滤不可见消息，使 note 不会人为打断工具组。

## 4. LLM 能力声明与请求副本

### 4.1 `LLM` 接口

新增向后兼容的默认能力方法：

```java
default boolean supportsMultipleSystemMessages() { return true; }
default LLM supportsMultipleSystemMessages(boolean supported) { ... }
default List<Message> prepareMessages(List<Message> messages) { ... }
```

默认实现不改变支持多 system 的消息列表；能力为 `false` 时，调用统一的 system 归一化器返回
请求副本。内置 provider 支持链式 setter；自定义 LLM 若需要运行时切换能力可覆写 setter，
否则调用 setter 会显式失败，避免静默忽略兼容性配置。已有自定义 LLM 无需实现新方法，默认行为
保持原样；自定义不支持多 system 的 LLM 可覆写能力方法或 `prepareMessages` 使用自己的请求格式。

### 4.2 内置 OpenAI 兼容 provider

`AbstractOpenAILLM` 增加可链式配置：

```java
llm.supportsMultipleSystemMessages(false)
```

默认值为 `true`。`OpenAICompatibleLLM`、`VLLM`、`DashscopeLLM` 按现有 fluent setter 模式提供
协变返回类型。`AbstractOpenAILLM.buildMessageListParams()` 在 SDK 参数构造前再次调用同一归一化器，
保证绕过 Agent 直接调用 provider 时也安全；归一化必须幂等。

### 4.3 Agent 调用点

在同步和流式 LLM 调用前：

1. callback 仍接收原始 `messages`，保持事件和 trace 语义不变；
2. 调用 `llm.prepareMessages(messages)` 得到 requestMessages；
3. 将 requestMessages 传给 `chat` / `streamChat`。

因此 Skill 的 `executeSkill()` 不需要根据 provider 类型修改消息；`SYSTEM` 模式在支持模型上仍
发送 system，在不支持模型上只在请求副本中降级为 user；显式 `USER` 始终是 user。

## 5. 不支持多 system 时的归一化

归一化器只在能力为 `false` 时执行，输入先过滤 `llmVisible=false`：

- 如果第一条可见消息是 system，保留它；之后的 system 转为：
  `[Framework System Instruction]\n<原内容>`；
- 如果第一条可见消息不是 system，所有 system 都转为上述 user 消息；
- system 出现在 assistant(toolCalls) 与其连续 tool result 之间时，先转成延迟队列，待该工具组
  的全部结果追加后再追加 user 边界消息；
- 支持多 system 时不做上述转换，保留原始可见消息顺序。

转换产生新的 `Message.user`，不修改原 system；文本前缀固定，便于模型和日志识别框架指令边界。

## 6. 兼容性与风险

| 场景 | 行为 |
|---|---|
| 无 Skill、无 SubAgent | 仅增加 request 副本准备；默认能力 true 时行为等价 |
| 父 system + 子 system | 父 system 在 SubAgent 边界丢弃，子 system 保留 |
| 父 USER Skill | 作为普通 user 上下文传递 |
| 子 SYSTEM Skill + 能力 false | Agent 内部仍是 system；provider 请求副本变为边界 user |
| 自定义 selector 返回 note/system/孤立 tool | 进入子代理前统一过滤/丢弃 |
| resume 子代理 | 只用子代理 ChatMemory 历史，不重新注入父上下文 |
| 直接调用 AbstractOpenAILLM | provider 参数构造再次归一化，避免绕过 Agent 失效 |

主要风险是第三方自定义 LLM 忽略 `prepareMessages`；该类实现应自行遵守 `LLM` 能力契约。内置
OpenAI 兼容 provider 有第二道幂等保护。

## 7. 回滚策略

所有改动均为新增默认方法、请求副本处理和 SubAgent 输入过滤。若 provider 兼容性验证失败，
可回滚 `prepareMessages` / provider 归一化调用，而不需要恢复 Agent 内部消息链或数据格式；
父 system 隔离和工具组过滤可独立保留。
