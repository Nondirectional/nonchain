# 简介

## 项目概述

nonchain 是一个轻量级的 Java AI 应用开发框架，当前版本 0.5.3。它为开发者提供了一套完整的 AI 应用构建工具链，涵盖大语言模型（LLM）调用、工具函数管理、工作流编排、文档处理和知识检索等核心能力。

### 项目目标

nonchain 的核心设计目标是：

- **简洁** — 提供最小化的 API 表面积，降低 AI 应用开发的入门门槛
- **模块化** — 各功能模块独立可插拔，按需引入，避免不必要的依赖
- **可扩展** — 通过接口抽象和 Builder 模式，方便替换底层实现或扩展新能力
- **实用性** — 覆盖 AI 应用开发中最常见的需求场景，从简单的 LLM 对话到复杂的 RAG 管道

## 核心特性

### LLM Provider 抽象

通过统一的 `LLM` 接口抽象大语言模型的调用方式。当前已内置阿里云 DashScope（通义千问系列）的实现，支持多种调用方式：

- 简单对话（系统提示 + 用户消息）
- 多轮对话（消息列表）
- 工具调用（携带工具定义）
- 结构化输出（JSON Object 格式）
- 多模态输入（文本 + 图片混合消息）

### 工具函数框架

提供两种工具定义方式，统一由 `ToolRegistry` 管理注册、调度和执行：

- **注解方式** — 使用 `@ToolDef` 和 `@ToolParam` 注解标记工具方法，通过反射自动扫描注册
- **流式 API 方式** — 使用链式调用（`register().param().handle()`）手动注册工具

LLM 返回的工具调用指令可通过 `ToolRegistry.execute()` 统一执行，支持自动类型转换。

### 图工作流引擎

基于有向图的 workflow 编排引擎（`Graph`），支持：

- 多步骤顺序执行
- 条件路由（`Edge.conditional()`）—— 根据当前 State 动态决定下一个节点
- 事件回调（`onEvent(Consumer<GraphEvent>)`）—— 监听图执行的每个阶段（GRAPH_START / NODE_START / NODE_END / GRAPH_END）
- 节点间通过 `State` 对象传递数据，State 包含键值对数据（`Map<String, Object>`）和消息历史（`List<Message>`）
- 完整的执行轨迹记录（`GraphResult`），支持回溯每个节点的状态变化

### 多模态输入

支持在一条用户消息中混合文本和图片内容。通过 `ContentPart` 接口抽象内容部件，内置 `TextPart`（文本）和 `ImageUrlPart`（图片 URL）两种实现，配合视觉模型（如 qwen-vl-plus）进行图片理解。

### 文档处理

支持多种文档格式的解析和结构化处理：

- **TXT** — 纯文本解析
- **Markdown** — 基于 commonmark 库解析，保留标题层级、代码块、表格等结构
- **HTML** — 基于 jsoup 库解析，自动提取正文内容
- **DOCX** — 基于 Apache POI 解析 Word 文档，提取段落和表格
- **PDF** — 基于 Apache PDFBox 解析，支持 OCR（Tesseract / RapidOCR 引擎）

### 文档切分

提供 5 种切分策略，适用于不同的文档处理场景：

| 策略 | 类名 | 适用场景 |
|------|------|----------|
| 递归字符切分 | `RecursiveCharacterSplitter` | 通用场景，按分隔符层级递归切分，支持字符数和 Token 数度量 |
| 标题层级切分 | `HeaderDocumentSplitter` | Markdown 等结构化文档，按标题层级拆分 |
| 语义切分 | `SemanticSplitter` | 基于 Embedding 计算语义相似度，在话题切换处切分 |
| 组合切分 | `CompositeDocumentSplitter` | 先按结构切分，再对每个 chunk 二次细分 |
| LLM 语义切分 | `LlmDocumentSplitter` | 基于 LLM 进行语义清洗和智能切分，chunk 质量最优 |

### 统一检索

通过 `KnowledgeStore` 接口抽象知识检索，当前官方实现收敛为 Elasticsearch：

- **kNN 向量检索** — 基于 `dense_vector`
- **BM25 全文检索** — 基于 `content` 字段和 `ik_smart`
- **Hybrid 混合检索** — 基于 Elasticsearch 原生 `retriever`

统一请求模型 `SearchRequest` 支持自动降级：

- 仅 `queryText` → BM25
- 仅 `queryEmbedding` → kNN
- 同时提供 → Hybrid

过滤条件统一通过 `knowledgeBaseIds`、`documentIds`、`chunkIds`、`metadataFilter` 下推到 Elasticsearch `filter` 语义。

### 上下文扩展

命中 chunk 后，可通过 `KnowledgeStore.expandContext(ContextExpansionRequest)` 基于中心 chunk 的 `chunkIndex` 向前后扩展邻居 chunk，补齐上下文窗口。`ContextExpansionResponse` 返回扩展后的 chunk 列表，并携带 `hasPrevious` / `hasNext` 分页标识。

### 混合检索

第一版混合检索默认采用 Elasticsearch 原生 `RRF`，同时在 API 形态上预留 `Linear` 扩展位。结果默认保持精简，仅在 `debug/trace` 模式下返回诊断元数据。

### 结构化输出

支持通过 `OutputFormat.JSON_OBJECT` 指定 LLM 以 JSON Object 格式返回响应，适用于需要结构化数据的场景。

## 架构概览

nonchain 采用分层架构设计，各层之间通过接口解耦：

```
                    ┌─────────┐
                    │   LLM   │  (DashscopeLLM)
                    └────┬────┘
                         │
            ┌────────────┼────────────┐
            │            │            │
      ┌─────┴─────┐ ┌───┴───┐ ┌─────┴─────┐
      │ Tool 框架  │ │ Graph │ │ Embedding │
      │ Registry  │ │ 引擎  │ │  Model    │
      └───────────┘ └───┬───┘ └───────────┘
                        │
            ┌───────────┼───────────┐
            │                       │
     ┌────────┴────────┐    ┌────────┴────────┐
     │ KnowledgeStore  │    │  DocumentReader │
     │   (interface)   │    │ + 清洗管道       │
     └────────┬────────┘    │ + 文档切分       │
            │             └─────────────────┘
     ┌───────────────┐
     │ Elasticsearch │
     │ KnowledgeStore│
     │ + BM25        │
     │ + Hybrid      │
     └───────────────┘
```

## 模块说明

| 模块 | Maven artifactId | 说明 |
|------|-----------------|------|
| `chain` | `chain` | 核心模块：LLM 抽象（`LLM`、`DashscopeLLM`）、工具函数（`ToolRegistry`）、图工作流（`Graph`、`Node`、`Edge`、`State`）、知识存储接口（`KnowledgeStore`、`SearchRequest`、`SearchResult`、`ContextExpansionRequest`、`ContextExpansionResponse`）、文档模型（`DocumentReader`、`ParsedDocument`、`DocumentElement`）、Embedding（`EmbeddingModel`、`DashScopeEmbeddingModel`）、多模态消息（`Message`、`ContentPart`、`TextPart`、`ImageUrlPart`）、文档切分接口（`DocumentSplitter`、`TextChunk`） |
| `chain-document` | `chain-document` | 文档处理模块：TXT/MD/HTML/DOCX/PDF 格式解析器、OCR 引擎（Tesseract / RapidOCR）、清洗管道（`CleanerPipeline`，包含 7 种清洗器）、5 种文档切分策略实现、内容度量（字符数 / Token 数） |
| `chain-elasticsearch` | `chain-elasticsearch` | Elasticsearch 集成模块：`ElasticsearchKnowledgeStore`（统一检索入口，支持 kNN / BM25 / Hybrid）、`ElasticsearchBM25Retriever`（专用 BM25 包装器）、`HybridRetriever`（原生 retriever 混合检索包装器） |
| `chain-example` | `chain-example` | 示例模块：包含 22 个可运行的示例程序，覆盖框架的所有功能特性 |
