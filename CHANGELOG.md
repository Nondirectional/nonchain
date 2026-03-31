# 更新日志

所有重要更改均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.0.3] - 2026-03-31

### 新增

- 支持结构化输出 `json_object` 响应格式，新增 `OutputFormat` 枚举（`TEXT`、`JSON_OBJECT`）
- `LLM` 接口新增 `OutputFormat` 参数，原有方法保持向后兼容
- `DashscopeLLM` 实现 `json_object` 响应格式
- 添加 `StructuredOutputExample` 示例，演示 JSON 对象输出用法
- 添加 `json_object` 与工具调用同时使用的校验，防止冲突

### 变更

- `DashscopeLLM` 构造函数中 `maxCompletionTokens` 改为可选参数

## [0.0.2] - 2026-03-31

### 重构

- 将 `LLM` 接口从 `ChatResult` 内部类提取为顶层接口 `provider.LLM`
- 更新所有示例代码中 `ChatResult.LLM` 的引用

### 其他

- `.gitignore` 添加 `.env` 排除规则

## [0.0.1] - 2026-03-31

### 新增

- 初始化 nonchain 库，包含 LLM Provider 抽象、工具调用框架及基于图的工作流引擎
- 支持 Dashscope（阿里云）作为 LLM 提供商
- 支持函数调用（单参数、多参数、原始请求）
- 支持工作流编排（条件路由、链式执行）
- 提供完整的示例代码
