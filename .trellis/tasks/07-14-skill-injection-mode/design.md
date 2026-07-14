# Skill 注入模式兼容 vLLM：设计

## 范围与边界

在 `com.non.chain.agent` 新增 `SkillInjectionMode`，仅控制 Skill 被点选后额外注入消息的 role。它不改变 Skill 的 function schema、tool result、事件、trace、memory、provider 请求构造或已有 system prompt。

## 公共 API

```java
Agent agent = Agent.builder(llm, tools)
        .skillRegistry(skills)
        .skillInjectionMode(SkillInjectionMode.USER)
        .build();
```

- `SkillInjectionMode.SYSTEM`：默认值，保持当前 `Message.system(content)` 行为。
- `SkillInjectionMode.USER`：生成 `Message.user("[Skill: " + name + "]\n" + content)`。
- `Agent.Builder.skillInjectionMode(SkillInjectionMode)` 是构建期配置；传 `null` 回退为 `SYSTEM`，与现有 Builder 枚举配置一致。
- 不提供 `AUTO`，不根据 `VLLM` 或任何 provider 类型推断模型 Chat Template 能力。

## 数据流

```text
LLM tool call (skill)
  -> Agent.executeSkill
  -> tool result + injection message
       SYSTEM: Message.system(content)
       USER:   Message.user("[Skill: name]\n" + content)
  -> 下一轮 LLM
```

`[Skill: name]` 是稳定边界。它保留 Skill 名称，区分原始用户输入与框架追加的过程性知识。

## 子代理

`runSubAgentInternal` 动态创建子 Agent 时，将父 Agent 的 `skillInjectionMode` 传入 `childBuilder`。因此前台、后台、resume 子代理都使用父 Agent 的同一模式。子代理注册定义不新增模式字段，避免同一运行中出现难以预期的 Skill role 混用。

## 兼容性与回滚

- 现有调用不配置模式时，默认 `SYSTEM`，消息序列保持不变。
- `USER` 只影响 Skill 注入消息；tool-calling 协议所需的 tool result 始终保留且先追加。
- 用户可删去 `.skillInjectionMode(USER)` 立即回滚至旧行为；无存储迁移、无 provider 网络探测。

## 验证

- 顶层默认 `SYSTEM` 现有测试继续通过。
- 顶层 `USER` 测试验证 role、`[Skill: name]` 边界及内容。
- 子代理 `USER` 测试验证父配置已传播。
- 多 Skill `USER` 测试验证每条注入均持续可见。
- 完整 `chain` Maven 测试回归。
