# Implement — SubAgent 上下文归一化与模型兼容

## 实施前置

- [x] 评审 `prd.md`、`design.md`，确认父 system 隔离、工具组丢弃和 provider 请求副本边界。
- [x] 运行 `python3 ./.trellis/scripts/get_context.py --mode packages` 并读取 backend 规范（已完成，
      实施前由 `trellis-before-dev` 再确认）。
- [x] 保留当前工作树中已有的 SubAgent system 过滤修复和 USER Skill 示例改动，不覆盖无关修改。

## 实施步骤

### 1. LLM 能力契约

- [x] 在 `chain/src/main/java/com/non/chain/provider/LLM.java` 增加默认
      `supportsMultipleSystemMessages()`、链式配置方法与请求副本准备方法，确保旧的 LLM 实现无需修改即可编译。
- [x] 在 `AbstractOpenAILLM` 增加默认值为 `true` 的能力字段和 fluent setter；在
      `OpenAICompatibleLLM`、`VLLM`、`DashscopeLLM` 提供协变返回类型。
- [x] 明确 JavaDoc：能力是模型实例属性，不按 provider 类型推断，不引入 AUTO。

### 2. System 请求归一化

- [x] 新增无外部依赖的消息归一化辅助逻辑，保证返回副本、输入不变、重复执行幂等。
- [x] 实现支持/不支持多 system 两条路径、首条 system 保留规则、user 边界前缀和 tool 组延迟队列。
- [x] 在 Agent 同步/流式 LLM 请求前使用 requestMessages；callback 继续接收原始 messages。
- [x] 在 `AbstractOpenAILLM.buildMessageListParams()` 入口再次调用同一逻辑，保证直接 provider 调用安全。

### 3. SubAgent 上下文归一化

- [x] 把 selector 结果统一经过 `llmVisible` 过滤、父 system 丢弃和工具组完整性检查。
- [x] 处理 null selector 结果、孤立 tool、未完成 assistant(toolCalls)、多 tool call 组和后台窗口截断。
- [x] 保持前台全量、后台最近 4 条和 resume 语义不变；只改变非法片段的处理。
- [x] 更新 `ContextSelector` JavaDoc，说明 system 与不可见消息会被框架过滤，工具组会被校验。

### 4. 测试

- [x] 扩展 `SubAgentTest`：父 system、自定义 selector 的 system/note、孤立工具调用、前后台窗口配对。
- [x] 扩展 `SubAgentSkillTest`：父 USER Skill 可传递，父 SYSTEM Skill 不传递；子代理使用不支持多
      system 的 mock 时 SYSTEM Skill 请求副本变为边界 user。
- [x] 新增 LLM 消息归一化测试：默认 true 保持原序；false 的首条/非首条 system、tool 组延迟、
      多次归一化幂等。
- [x] 扩展 provider 消息构造测试，确认 `llmVisible=false` 不进入 SDK 请求，直接 provider 调用也
      会执行 system 归一化。

## 验证命令

```bash
mvn -pl chain -DskipTests compile
mvn -pl chain -Dtest=SubAgentTest,SubAgentSkillTest,AgentSkillTest test
mvn -pl chain -Dtest=AbstractOpenAILLMFilterTest test
mvn test
```

## 风险与检查点

- [x] `prepareMessages` 不得修改 Agent 的原始 `messages`，否则会污染 ChatMemory、ContextSelector
      和 callback。
- [x] provider 二次归一化必须幂等，避免首条 system 或 `[Framework System Instruction]` 被重复转换。
- [x] 并行工具路径中 Skill 注入可能位于多个 tool result 之间，必须覆盖 system 延迟测试。
- [x] 子代理 systemPrompt 仍是第一条消息；不得把父 system 转 user。
- [x] 无 skill / 无 sub-agent 的 Agent 全量回归通过后再处理文档和规范更新。

## 完成前置审查

- [x] 运行 `trellis-check` 质量检查，覆盖 spec、类型、测试、跨层消息流和代码复用。
- [x] 根据最终实现更新 `.trellis/spec/backend/quality-guidelines.md` 中过时的“只能手动选择 USER”说明，
      记录能力声明与自动降级契约。
- [x] 用户已审阅规划产物并执行 `python3 ./.trellis/scripts/task.py start`，进入实现阶段。
