# 更新日志

所有重要更改均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.3.0] - 2026-04-07

### 新增

- 流式输出支持：`LLM` 接口新增 `streamChat()` 系列方法，接受 `Consumer<ChatChunk>` 回调
- `ChatChunk` 增量块模型：支持文本、思考内容、工具调用的流式片段
- `DashscopeLLM` 流式实现：基于 `createStreaming()` API，含工具调用按 index 累积
- `StreamingChatExample` 示例：基础流式、思考模式、工具调用流式
- Agent 循环：`Agent` 类封装 LLM + 工具自动调用循环，Builder 模式
- `AgentException`：超出最大迭代次数时抛出
- 工具执行失败时错误信息传回 LLM 自我修复，不中断循环
- Agent 支持 `Consumer<String>` 日志回调，可观察执行过程
- `AgentTest`：8 个单元测试覆盖核心场景
- `AgentLoopExample`：旅行助手示例，演示多工具多步骤推理

## [0.2.0] - 2026-04-03

### 新增

- 多模态图片输入支持（`ImageUrlPart`、`TextPart`），配合视觉模型进行图片理解
- `ImageInputExample` 示例，演示多模态图片输入用法
- `ParsedDocument` 和 `TextChunk` 单元测试

## [0.1.0] - 2026-04-02

### 新增

- 转为多模块 Maven 项目结构（chain、chain-document、chain-pgvector、chain-elasticsearch、chain-example）
- `chain` 模块：`EmbeddingModel` 接口及 DashScope 实现
- `chain-knowledge` 模块：知识库核心接口与值对象、pgvector 和 Elasticsearch 实现
- `chain-document` 模块：文档解析核心抽象（`DocumentReader`、`Document`、`DocumentCleaner`）
- 5 种文档 Reader 实现：TXT、Markdown、PDF、DOCX、HTML
- 文档清洁管线，包含 8 种 `Cleanable` 实现
- `OcrEngine` 接口及扫描版 PDF 检测，支持 RapidOCR 和 Tesseract 两种引擎
- 知识库示例和文档 Reader/Cleaner 示例

### 修复

- 文档 Reader 示例改用 classpath 资源加载 PDF 和 DOCX 文件

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
