# Skill 注入模式兼容 vLLM：执行计划

## 实施

- [x] 新增 `chain/src/main/java/com/non/chain/agent/SkillInjectionMode.java`，定义 `SYSTEM`、`USER`。
- [x] 在 `Agent` 保存不可变 `skillInjectionMode`；Builder 默认 `SYSTEM`，新增公开 fluent 方法并处理 `null` 回退。
- [x] 修改 `executeSkill`：保留 tool result、事件和 trace；按模式生成 system 或带 `[Skill: <name>]` 前缀的 user 消息。
- [x] 在 `runSubAgentInternal` 将父模式传给 `childBuilder`。
- [x] 同步 JavaDoc 中“system 注入”的固定表述为“按配置注入”，但明确默认值为 `SYSTEM`。

## 测试

- [x] 保留并运行既有顶层默认 system、多个 system Skill、并行 skill 路径测试。
- [x] 在 `AgentSkillTest` 新增 USER 模式测试，断言第二轮同时有 tool result、一个 user Skill 注入、稳定前缀与完整内容；断言没有同内容的 system 注入。
- [x] 在 `SubAgentSkillTest` 新增 USER 模式测试，断言动态子代理继承父 Agent 配置并按 USER 注入。
- [x] 运行 `rtk mvn -pl chain test -Dtest=AgentSkillTest,SubAgentSkillTest -q`。
- [x] 运行 `rtk mvn -pl chain test -q`。

## 文档与示例

- [x] 更新 README 的 Skill 定义、示例和子代理说明：默认 `SYSTEM`，不支持多 system 的模型可显式选择 `USER`。
- [x] 更新 `SkillExample`：因其直接使用 `VLLM`，展示 `.skillInjectionMode(SkillInjectionMode.USER)`；不更改 Skill 内容。
- [x] 更新示例 JavaDoc，避免声称所有 Skill 永远注入 system。

## 风险与回滚

- 风险：USER 注入在消息序列中位于 tool result 后。测试只验证框架生成的序列，不依赖在线模型或 Chat Template。
- 回滚：删除新配置调用或将其设为 `SYSTEM`；默认调用方无行为变化。
