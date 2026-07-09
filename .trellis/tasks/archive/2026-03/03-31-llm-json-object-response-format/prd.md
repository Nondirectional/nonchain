# 支持 LLM 结构化输出 json_object

## Goal
在 DashscopeLLM 的 chat completions 请求中支持设置 `response_format={"type":"json_object"}`，让调用方可以显式要求模型返回 JSON 对象。

## Requirements
- 在 `DashscopeLLM` 增加可配置开关，用于开启/关闭 json_object 输出模式
- 构建 `completions.create` 请求时，开启模式后写入 `response_format` 参数
- 与现有 thinking 配置共存，不影响原有普通对话流程
- 当 tools 与 json_object 同时启用时，采用明确且可预期的冲突策略
- 提供可运行示例展示结构化输出用法

## Acceptance Criteria
- [ ] `DashscopeLLM` 提供 fluent 配置接口以启用 json_object 模式
- [ ] 启用后请求中包含 `response_format={"type":"json_object"}`
- [ ] 未启用时行为与当前版本一致
- [ ] 示例代码展示启用方式与 JSON 输出提示词
- [ ] 项目可通过编译（`mvn -q -DskipTests compile`）

## Technical Notes
- 优先使用 openai-java 4.30.0 的强类型 API（`ResponseFormatJsonObject`），避免 additionalBodyProperty 方式
- 冲突策略采用 fail-fast：当 tools 非空且启用 json_object 模式时抛出 `IllegalArgumentException`
