# SubAgent 上下文归一化与模型兼容

## Goal

让 SubAgent 在继承父 Agent 可见上下文时，始终得到适合自身模型和 Chat Template 的消息序列，
避免因父 system 消息、多 system 能力差异或未配对的工具调用导致请求失败，同时保持父子角色边界清晰。

## Background / Confirmed Facts

- `Agent.runSubAgentInternal()` 当前先注入子代理自己的 `systemPrompt`，再拼接父上下文和本次 task
  (`chain/src/main/java/com/non/chain/agent/Agent.java:763-783`)。
- 父上下文由 `ContextSelector` 选择；前台默认传递全部可见消息，后台默认只取最近 4 条可见消息。
- 当前实现会过滤父上下文中的所有 `system` 消息，因此父 Agent 的 systemPrompt 不会传给 SubAgent。
  该行为已确认保留：子代理自己的 systemPrompt 是权威角色指令。
- 子代理默认继承父 LLM，也可以通过 `llmOverride` 使用独立 LLM；父/子 SkillRegistry 不自动共享，
  但 Skill 注入模式会传给动态构造的子代理。
- OpenAI 兼容 provider 当前按消息原序把每条 system 转成 system 参数
  (`chain/src/main/java/com/non/chain/provider/AbstractOpenAILLM.java:184-235`)；不支持多 system 的模型
  可能拒绝非首条 system。
- 父级工具调用的消息快照可能含有带 `toolCalls` 的 assistant 消息，但尚未包含对应 tool result；
  后台 4 条窗口也可能把 assistant(toolCalls) 与 tool result 拆开。

## Requirements

### R1. 父 system 隔离

- 默认不向 SubAgent 传递父 Agent 的任何 `system` 消息，包括父 systemPrompt 和父级 system Skill 注入。
- 子代理自己的 `systemPrompt` 保持为首条 system 消息并拥有权威解释权。
- `ContextSelector` 返回的 system 消息也必须遵守该边界，不能通过自定义 selector 绕过默认策略。
- 父 Skill 若使用 `USER` 注入，则按普通可见 user 消息处理并可随父上下文传递；框架不因 Skill 来源
  而额外过滤，业务可通过自定义 `ContextSelector` 排除。

### R2. 父上下文安全归一化

- 对传入 SubAgent 的父上下文执行统一归一化，而不是要求业务方手工过滤消息。
- 保留合法的 user/assistant/tool 语义和消息顺序；无论默认还是自定义 `ContextSelector`，在进入
  SubAgent 前都强制过滤 `llmVisible=false` 的应用层消息。
- 不得生成孤立的 assistant(toolCalls) 或 tool result；必要时按完整工具调用组丢弃不完整片段。
- 前台全量与后台窗口两种选择器都必须经过同一归一化流程。

### R3. 模型兼容

- 支持多 system 的模型保留其原始 system 消息语义，但 SubAgent 父 system 隔离规则仍优先。
- LLM 实例提供多 system 能力声明：`supportsMultipleSystemMessages()` 默认返回 `true`，调用方可对
  不支持的实例显式设置为 `false`；不按 `VLLM` 类型硬编码能力。
- 对声明不支持多 system 的模型，消息归一化规则为：保留首条可见 system；后续 system 转为带
  `[Framework System Instruction]` 边界的 user 消息。
- 如果首条可见消息不是 system，则所有 system 都转换为上述 user 消息。
- 如果待转换 system 位于 assistant(toolCalls) 与连续 tool result 之间，则转换后的 user 消息延迟到
  该连续 tool result 组之后，避免破坏工具调用配对。
- Skill 的 system/user 注入模式仍由现有 Agent 配置控制；归一化只在模型声明不支持多 system 时兜底。
- 当 `SkillInjectionMode.SYSTEM` 与“不支持多 system”能力声明冲突时，框架自动降级为边界明确的
  user 注入；显式 `SkillInjectionMode.USER` 始终保持 user 注入。能力声明默认 `true`，不引入
  `AUTO` 模式，也不按 provider 类型推断。

### R4. 可观测与兼容性

- 归一化不得改变父 Agent 的 tool result 回灌、SubAgent 返回值、事件传播和 trace 行为。
- 无自定义上下文选择器时保持现有前台/后台窗口语义，新增逻辑只负责合法性和模型兼容。
- 自定义 `ContextSelector` 仍可裁剪消息，但不能破坏上述 system 隔离和工具消息配对不变量。

## Acceptance Criteria

- [x] 子代理实际收到的第一条可见消息始终是自己的 systemPrompt；父 system 永不出现在子代理消息中。
- [x] 自定义 selector 返回 system、note 或不完整工具组时，框架仍输出合法的子代理消息序列。
- [x] 前台全量、后台最近 4 条、以及自定义窗口均不会留下孤立 assistant(toolCalls) 或 tool result。
- [x] 覆盖父级 system Skill、USER Skill、父历史工具调用和后台窗口截断的回归测试通过。
- [x] 现有 `SubAgentTest`、`SubAgentSkillTest`、provider 消息构造测试和全仓测试通过。
- [x] 不支持多 system 的 OpenAI 兼容模型不再因 SubAgent 上下文排列触发 “System message must be at the beginning”。

## Out of Scope

- 不把父 systemPrompt 合并、复制或转换为 user 消息。
- 不自动继承父 Agent 的 ToolRegistry 或 SkillRegistry。
- 不重新设计 ContextSelector 的公开 API；只在其结果进入子代理前增加框架级归一化。
- 不改变 Agent 内部消息链、ChatMemory 持久化角色或事件载荷中的原始 system/user 语义。
- system 兼容处理位于统一的 LLM 请求准备边界：Agent 使用请求副本调用 LLM，OpenAI 兼容 provider
  在 SDK 参数构造处再次保持幂等；不会修改原始消息列表。

## Open Questions

（无。已确认：父上下文工具组在 SubAgent 边界归一化；system 多消息兼容在 LLM provider 请求边界对请求副本归一化。）
