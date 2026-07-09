# brainstorm: vLLM LLM 实现类

## Goal

创建 `VLLM` 实现类，为 vLLM 推理服务器提供专门的支持，特别是处理 vLLM 独特的 thinking 模式参数传递方式（嵌套 JSON 结构 `chat_template_kwargs`）和思考预算控制（`thinking_token_budget`）。

## What I already know

* 项目 LLM 模块架构：`LLM` 接口 → `AbstractOpenAILLM` 抽象基类 → 具体实现
* 已有两个实现：`DashscopeLLM`（阿里云）和 `OpenAICompatibleLLM`（通用 OpenAI 兼容）
* 扩展点：`applyAdditionalParams()` 是子类注入提供商特有参数的 protected 方法
* 当前 thinking 支持：`enable_thinking` 和 `thinking_budget` 作为平级 body 属性发送
* vLLM 兼容 OpenAI API，可直接使用 `OpenAICompatibleLLM` 的基础连接能力
* vLLM thinking 响应同样使用 `reasoning_content` 字段返回思考内容
* 项目使用 OpenAI Java SDK `com.openai:openai-java:4.30.0`，支持 `putAdditionalBodyProperty()`

## vLLM vs DashScope 参数差异

| 功能 | DashScope | vLLM |
|------|-----------|------|
| thinking 开关 | `enable_thinking: true` (平级) | `chat_template_kwargs: {enable_thinking: true}` (嵌套) |
| 思考预算 | `thinking_budget: 1024` (平级) | `thinking_token_budget: 1024` (平级，字段名不同) |

## Assumptions (temporary)

* vLLM 响应的 thinking 内容格式与 DashScope 一致（`reasoning_content` 字段）
* vLLM 的 tool calling / streaming 行为与标准 OpenAI API 兼容
* vLLM 不需要额外的认证机制（或使用简单的 API key）

## Open Questions

* vLLM 是否有其他需要特殊处理的参数（除 thinking 外）？
* `VLLM` 应该作为 `OpenAICompatibleLLM` 的子类还是独立继承 `AbstractOpenAILLM`？

## Requirements (evolving)

* `VLLM` 类继承 `AbstractOpenAILLM`，复用 OpenAI 兼容的请求/响应处理
* thinking 开关通过 `chat_template_kwargs` 嵌套 JSON 传递
* 思考预算通过 `thinking_token_budget` 字段传递
* 保持与现有实现一致的 fluent setter 链式调用风格
* 保持 `fromContext()` 静态工厂方法模式

## Acceptance Criteria (evolving)

* [ ] `VLLM` 正确发送嵌套的 `chat_template_kwargs.enable_thinking` 参数
* [ ] `VLLM` 正确发送 `thinking_token_budget` 参数
* [ ] 支持同步和流式两种调用方式
* [ ] thinking 内容正确从响应中提取
* [ ] 与现有 `Agent`、`ChainCallback` 等组件兼容
* [ ] 提供 chain-example 示例代码

## Definition of Done

* 代码通过编译（mvn compile）
* Lint / typecheck 通过
* 示例代码可运行
* 文档更新

## Out of Scope (explicit)

* （待确认）

## Technical Notes

* 关键文件：
  - `chain/src/main/java/com/non/chain/provider/AbstractOpenAILLM.java` (504行)
  - `chain/src/main/java/com/non/chain/provider/OpenAICompatibleLLM.java`
  - `chain/src/main/java/com/non/chain/provider/DashscopeLLM.java`
* OpenAI Java SDK `putAdditionalBodyProperty()` 支持嵌套 JSON（`JsonValue`）
* `DashscopeLLM` 的 `applyAdditionalParams()` 模式可作为参考
* 现有 `AbstractOpenAILLM` 的 thinking 处理（`enable_thinking` / `thinking_budget`）需要被 vLLM 覆盖
