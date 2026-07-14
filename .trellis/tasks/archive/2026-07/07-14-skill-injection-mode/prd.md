# Skill 注入模式兼容 vLLM

## Goal

Skill 被 LLM 点选后，调用方可选择将过程性知识注入为 `system` 或 `user` 消息；使用不支持多条 `system` 消息的 vLLM Chat Template 时仍可正常执行。

## Confirmed Facts

- `Agent.executeSkill` 当前对每个已选 Skill 生成 `tool` result 与 `Message.system(content)`，注入消息在当前运行内持续保留。
- 顶层 Agent 与子代理都经由同一 `Agent` 注入路径；子代理会继承其自身注册的 `SkillRegistry`。
- `VLLM` 是 `OpenAICompatibleLLM` 的专用子类，差异集中在 provider 层 thinking 参数。
- 现有顶层与子代理测试均断言 Skill 注入角色为 `system`；多 Skill 测试断言两条 system 注入可共存。
- 旧的 Skill 设计计划已记录：若 provider 不接受中途追加 system，应改用带 `[Skill: name]` 标记的 user 消息，但该回退未实施。

## Requirements

- R1：提供公开、构建期固定的 Skill 注入角色配置，至少支持 `SYSTEM` 与 `USER`。
- R2：默认值固定为 `SYSTEM`，保留现有 system 注入语义；使用不支持多 system Chat Template 的模型时，调用方显式选择 `USER`，无需改写 Skill 内容。
- R3：`USER` 注入内容必须带稳定的 Skill 边界/名称标记，避免与原始用户输入混淆。
- R4：顶层 Agent、前台子代理与后台子代理使用相同策略；未配置 Skill 时行为不变。
- R5：更新示例与文档，说明默认行为及 vLLM 的显式配置方式。

## Acceptance Criteria

- [x] `SYSTEM` 模式保留当前 tool result + system Skill 注入，并通过既有顶层/子代理回归测试。
- [x] `USER` 模式产出 tool result + 带 Skill 名称边界的 user 消息；连续点选多个 Skill 时均持续可见。
- [x] vLLM 兼容策略有单元测试，且不依赖在线 vLLM 服务。
- [x] 子代理在所选模式下产生与顶层一致的注入角色。
- [x] README、示例和 API 文档与最终默认策略一致。

## Out of Scope

- 自动探测远程 vLLM 服务或其 Chat Template 能力。
- 修改 provider 请求序列、合并既有 system prompt，或改变 memory 裁剪策略。

## Decisions

- 不引入 `AUTO`。provider 类型不能可靠表达实际部署模型的 Chat Template 能力；默认 `SYSTEM`，由调用方显式切换 `USER`。
- 注入模式配置在 `Agent.Builder`。`SkillRegistry` 与 `SkillDefinition` 只描述可复用的知识内容；运行期注入角色属于 Agent 策略。Agent 构造子代理时必须传播此配置。
