# 架构

本文档详细介绍 nonchain 的架构设计、模块组织和核心设计原则。

## 整体架构

nonchain 采用分层架构设计，自上而下分为四层：Provider 层、Framework 层、Processing 层和 Storage 层。各层之间通过 Java 接口解耦，上层依赖下层，下层不感知上层的存在。

```
┌─────────────────────────────────────────────────────────────────┐
│                      Provider 层（模型接入）                      │
│                                                                 │
│   ┌──────────────┐    ┌──────────────────┐                     │
│   │  DashscopeLLM │    │ DashScopeEmbedding │                   │
│   │  (LLM 接口实现) │    │ (EmbeddingModel)  │                  │
│   └──────┬───────┘    └────────┬─────────┘                     │
│          │                     │                                │
├──────────┼─────────────────────┼────────────────────────────────┤
│          │       Framework 层（框架核心）                         │
│          │                                                     │
│   ┌──────┴──────┐  ┌───────────┐  ┌──────────────────┐         │
│   │ ToolRegistry│  │   Graph    │  │  Message 模型     │         │
│   │ (工具注册中心) │  │ (工作流引擎) │  │ (多模态消息)      │        │
│   │             │  │  Node/Edge │  │ ContentPart      │         │
│   │ @ToolDef    │  │  State    │  │ TextPart         │         │
│   │ Fluent API  │  │  GraphResult│ │ ImageUrlPart    │         │
│   └─────────────┘  └─────┬─────┘  └──────────────────┘         │
│                          │                                     │
├──────────────────────────┼─────────────────────────────────────┤
│                          │     Processing 层（数据处理）          │
│                          │                                     │
│   ┌──────────────────────┴──────────────────────┐              │
│   │             DocumentReader (接口)            │              │
│   │  ┌──────┐ ┌──────────┐ ┌──────┐ ┌──────┐  │              │
│   │  │ TXT  │ │Markdown │ │ HTML │ │ DOCX │  │              │
│   │  └──────┘ └──────────┘ └──────┘ └──────┘  │              │
│   │  ┌──────┐                                  │              │
│   │  │ PDF  │  (+ OcrEngine)                   │              │
│   │  └──────┘                                  │              │
│   └──────────────────┬───────────────────────┘              │
│                      │                                       │
│   ┌──────────────────┴───────────────────────┐              │
│   │           CleanerPipeline (清洗管道)       │              │
│   │  ControlCharacterRemover                  │              │
│   │  UnicodeNormalizer                        │              │
│   │  WhitespaceNormalizer                     │              │
│   │  BoilerplateRemover                       │              │
│   │  DuplicateRemover                         │              │
│   │  ShortFragmentMerger                      │              │
│   │  ImageStrategyCleaner                     │              │
│   └──────────────────┬───────────────────────┘              │
│                      │                                       │
│   ┌──────────────────┴───────────────────────┐              │
│   │        DocumentSplitter (切分接口)         │              │
│   │  RecursiveCharacterSplitter               │              │
│   │  HeaderDocumentSplitter                   │              │
│   │  SemanticSplitter                         │              │
│   │  CompositeDocumentSplitter                │              │
│   └──────────────────┬───────────────────────┘              │
│                      │                                       │
├──────────────────────┼──────────────────────────────────────┤
│                      │      Storage 层（数据存储）              │
│                      │                                       │
│   ┌──────────────────┴───────────────────────┐              │
│   │        KnowledgeStore (接口)               │              │
│   │                                           │              │
│   │  SearchRequest   SearchResult             │              │
│   │  MetadataFilter  KeywordRetriever         │              │
│   │  TextChunk        DocumentChunk            │              │
│   │                                           │              │
│   │  ┌──────────────┐  ┌─────────────────┐   │              │
│   │  │   PgVector    │  │ Elasticsearch    │   │              │
│   │  │ KnowledgeStore│  │ KnowledgeStore   │   │              │
│   │  │              │  │                  │   │              │
│   │  │  pgvector    │  │  dense_vector    │   │              │
│   │  │  cosine sim  │  │  kNN search      │   │              │
│   │  │  HikariCP    │  │                  │   │              │
│   │  └──────────────┘  │  BM25 Retriever  │   │              │
│   │                     │  HybridRetriever │   │              │
│   │                     │  (RRF fusion)    │   │              │
│   │                     └─────────────────┘   │              │
│   └───────────────────────────────────────────┘              │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

## 层次说明

### Provider 层（模型接入）

Provider 层负责与外部 AI 模型服务的对接，将底层 API 的差异屏蔽在接口之后。

**核心接口：**

- `LLM` — 大语言模型调用接口，定义了 `chat()` 系列方法，支持简单对话、多轮对话、工具调用和结构化输出
- `EmbeddingModel` — 文本向量化接口，定义了 `embed()` 和 `embedAll()` 方法

**当前实现：**

- `DashscopeLLM` — 基于阿里云 DashScope API 的 LLM 实现，支持通义千问系列模型
- `DashScopeEmbeddingModel` — 基于阿里云 DashScope API 的 Embedding 实现

### Framework 层（框架核心）

Framework 层是 nonchain 的核心，提供了三大基础能力：工具管理、工作流编排和多模态消息。

**工具函数框架（ToolRegistry）：**

- `ToolRegistry` 是工具注册中心，统一管理工具的定义和执行
- 支持两种注册方式：注解扫描（`@ToolDef` / `@ToolParam`）和流式 API（`register().param().handle()`）
- `Tool` 值对象描述工具的元信息（名称、描述、参数定义），用于传递给 LLM
- `ToolCall` 值对象表示 LLM 返回的工具调用指令
- `ToolHandler` 函数式接口定义工具的执行逻辑
- `ToolArgs` 封装工具调用的参数，提供类型安全的参数访问

**图工作流引擎（Graph）：**

- `Graph` — 工作流引擎核心，通过 Builder 模式构建，`run()` 方法驱动执行
- `Node` — 工作流节点，封装一个 `Function<State, State>` 处理函数
- `Edge` — 节点间的有向边，支持无条件边（`Edge.of()`）和条件边（`Edge.conditional()`）
- `State` — 工作流状态，包含键值对数据（`Map<String, Object>`）和消息历史（`List<Message>`），在各节点间传递
- `GraphResult` — 执行结果，包含最终状态、完整状态历史和已执行节点列表

**多模态消息模型：**

- `Message` — 消息值对象，支持 system、user、assistant、tool 四种角色
- `ContentPart` — 多模态内容部件的标记接口
- `TextPart` — 文本内容部件
- `ImageUrlPart` — 图片 URL 内容部件
- `ChatResult` — LLM 响应结果，包含回复内容、思考内容和工具调用列表

### Processing 层（数据处理）

Processing 层负责文档的读取、清洗和切分，将原始文档转化为适合向量化和 LLM 处理的文本块。

**文档读取（DocumentReader）：**

- `DocumentReader` — 文档解析器接口，定义 `supports(extension)` 和 `read(source)` 方法
- `DocumentReaders` — 解析器注册中心，根据文件扩展名自动选择合适的解析器
- `ParsedDocument` — 解析后的文档模型，包含元数据（`DocumentMetadata`）和元素列表（`DocumentElement`）
- `DocumentElement` — 文档元素接口，具体实现包括 `TextElement`、`HeadingElement`、`TableElement`、`CodeBlockElement`、`ImageElement`

**文档清洗（CleanerPipeline）：**

- `DocumentCleaner` — 清洗器接口，定义 `clean(document)` 方法
- `CleanerPipeline` — 清洗管道，将多个清洗器按顺序串联执行
- 内置 7 种清洗器：
  - `ControlCharacterRemover` — 移除控制字符
  - `UnicodeNormalizer` — Unicode 标准化
  - `WhitespaceNormalizer` — 空白字符标准化
  - `BoilerplateRemover` — 移除样板文本
  - `DuplicateRemover` — 移除重复内容
  - `ShortFragmentMerger` — 合并过短片段
  - `ImageStrategyCleaner` — 图片处理策略（保留/移除）

**文档切分（DocumentSplitter）：**

- `DocumentSplitter` — 切分器接口，定义 `split(document)` 方法
- `TextChunk` — 切分后的文本块值对象，包含内容、元素类型和元数据
- `ContentMeasure` — 内容度量接口，用于衡量文本长度
- 4 种切分策略实现：
  - `RecursiveCharacterSplitter` — 递归字符切分，按分隔符层级递归拆分，支持 chunk overlap
  - `HeaderDocumentSplitter` — 标题层级切分，基于 Markdown 标题拆分
  - `SemanticSplitter` — 语义切分，基于 Embedding 在话题切换处拆分
  - `CompositeDocumentSplitter` — 组合切分，先结构后字符的二次切分

### Storage 层（数据存储）

Storage 层提供知识数据的持久化存储和检索能力。

**核心接口：**

- `KnowledgeStore` — 知识存储接口，定义 `add()`、`search()`、`delete()` 等基本 CRUD 操作
- `KeywordRetriever` — 关键词检索接口，定义 `search(queryText, topK)` 方法
- `SearchRequest` — 搜索请求值对象，支持查询向量、topK、最低分数、知识库/文档/块 ID 过滤和元数据过滤
- `SearchResult` — 搜索结果值对象，包含知识库 ID、文档 ID、块 ID、内容和分数
- `MetadataFilter` — 元数据过滤器，支持条件组合（AND/OR/NOT）和多种操作符（EQ/NE/GT/GTE/LT/LTE/EXISTS/IN）
- `DocumentChunk` — 文档块值对象，包含内容、向量、元数据和索引位置

**存储实现：**

- `PgvectorKnowledgeStore` — 基于 PostgreSQL + pgvector 扩展的实现
  - 使用余弦相似度（`<=>` 操作符）
  - 通过 HikariCP 管理数据库连接池
  - 首次连接时自动创建扩展、表和索引（ivfflat）
  - 支持 JSONB 格式的元数据存储和过滤

- `ElasticsearchKnowledgeStore` — 基于 Elasticsearch 的实现
  - 使用 `dense_vector` 类型存储向量，通过 kNN 搜索实现近邻查询
  - 支持 `ik_smart` 中文分词器
  - 自动创建索引和映射
  - 支持动态元数据映射

- `ElasticsearchBM25Retriever` — 基于 Elasticsearch 的 BM25 关键词检索
- `HybridRetriever` — 双路混合检索器，结合向量检索和 BM25 检索，通过 RRF（Reciprocal Rank Fusion）算法融合排序

## 模块依赖关系

```
chain-example
    ├── chain-document
    │   └── chain
    ├── chain-elasticsearch
    │   └── chain
    └── chain-pgvector
        └── chain
```

依赖关系遵循以下原则：

- `chain` 是基础模块，不依赖任何其他 nonchain 模块
- `chain-document`、`chain-elasticsearch`、`chain-pgvector` 均仅依赖 `chain`
- 三个功能扩展模块之间互不依赖，可以按需独立引入
- `chain-example` 依赖所有模块，仅用于演示

## 设计原则

### 接口驱动

nonchain 的核心能力均通过 Java 接口定义，实现与使用解耦：

| 接口 | 职责 | 实现类 |
|------|------|--------|
| `LLM` | 大语言模型调用 | `DashscopeLLM` |
| `EmbeddingModel` | 文本向量化 | `DashScopeEmbeddingModel` |
| `DocumentReader` | 文档解析 | `TxtDocumentReader`, `MarkdownDocumentReader`, `HtmlDocumentReader`, `DocxDocumentReader`, `PdfDocumentReader` |
| `DocumentCleaner` | 文档清洗 | `ControlCharacterRemover`, `UnicodeNormalizer`, 等 |
| `DocumentSplitter` | 文档切分 | `RecursiveCharacterSplitter`, `HeaderDocumentSplitter`, `SemanticSplitter`, `CompositeDocumentSplitter` |
| `KnowledgeStore` | 知识存储 | `PgvectorKnowledgeStore`, `ElasticsearchKnowledgeStore` |
| `KeywordRetriever` | 关键词检索 | `ElasticsearchBM25Retriever` |
| `ContentMeasure` | 内容度量 | `CharacterMeasure`, `TokenMeasure` |
| `ContentPart` | 多模态内容部件 | `TextPart`, `ImageUrlPart` |

这种设计使得替换底层实现变得简单。例如，要将 PgVector 切换为 Elasticsearch，只需将 `PgvectorKnowledgeStore` 替换为 `ElasticsearchKnowledgeStore`，其他代码无需修改。

### Builder 模式

nonchain 广泛使用 Builder 模式来构造复杂对象，提供流畅的 API 体验：

```java
// Graph 构建
Graph graph = Graph.builder("workflow")
    .addNode(Node.of("step1", state -> { ... }))
    .addNode(Node.of("step2", state -> { ... }))
    .addEdge(Edge.of("step1", "step2"))
    .start("step1")
    .build();

// SearchRequest 构建
SearchRequest request = SearchRequest.builder(embedding)
    .topK(5)
    .minScore(0.7)
    .addKnowledgeBaseId("kb1")
    .metadataFilter(filter)
    .build();

// PgvectorKnowledgeStore 构建
PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(jdbcUrl, dimension)
    .username("postgres")
    .password("postgres")
    .poolSize(10)
    .build();
```

Builder 模式的优势在于：

- 参数自描述（方法名即参数含义）
- 参数可选且可链式组合
- 构建过程中进行参数校验，将错误前置到构建阶段
- 构建后的对象不可变

### 不可变值对象

nonchain 中的数据模型（`Message`、`ChatResult`、`ParsedDocument`、`TextChunk`、`SearchResult`、`DocumentChunk` 等）均设计为不可变对象：

- 所有字段为 `final`
- 仅通过构造器或 Builder 创建实例
- 集合类型字段返回不可变视图（`Collections.unmodifiableList` / `Collections.unmodifiableMap`）
- 不提供 setter 方法

不可变对象的优势：

- **线程安全** — 天然支持多线程并发访问，无需同步
- **可预测性** — 对象创建后状态不会改变，减少调试难度
- **可共享** — 可以安全地在不同组件间共享引用，减少拷贝开销

### 单一职责

每个类和接口都有明确的单一职责：

- `ToolRegistry` 只负责工具的注册和执行调度，不关心工具的具体实现
- `Graph` 只负责工作流的执行编排，不关心每个 Node 的具体逻辑
- `CleanerPipeline` 只负责串联清洗器，不关心每种清洗的具体策略
- `HybridRetriever` 只负责融合两路检索结果，不关心具体的检索实现

### 最小依赖

nonchain 在依赖管理上保持克制：

- 核心模块仅依赖 `openai-java` 一个外部库
- 各功能模块的 optional 依赖不会自动传递
- 不引入 Spring、Guava 等重量级框架
- 使用 JDK 内置的 `java.util.function`、`java.util.stream` 等标准 API

## 数据流

典型的 RAG（Retrieval-Augmented Generation）场景下的数据流如下：

```
原始文档
    │
    ▼
DocumentReader.read()          ← 文档解析
    │
    ▼
ParsedDocument                 ← 结构化文档（元素列表）
    │
    ▼
CleanerPipeline.clean()        ← 文档清洗
    │
    ▼
DocumentSplitter.split()       ← 文档切分
    │
    ▼
List<TextChunk>                ← 文本块列表
    │
    ▼
EmbeddingModel.embedAll()      ← 向量化
    │
    ▼
KnowledgeStore.addAll()        ← 入库
    │
    ▼
[用户查询]
    │
    ▼
EmbeddingModel.embed()         ← 查询向量化
    │
    ▼
KnowledgeStore.search()        ← 向量检索
    │   + KeywordRetriever.search()  ← 关键词检索
    │   + HybridRetriever             ← 混合检索（RRF）
    ▼
List<SearchResult>             ← 检索结果
    │
    ▼
LLM.chat(查询 + 检索结果)       ← LLM 生成回答
    │
    ▼
ChatResult                     ← 最终回复
```

## 扩展指南

nonchain 的接口驱动设计使得扩展新能力非常直接：

### 添加新的 LLM Provider

实现 `LLM` 接口即可接入新的模型服务：

```java
public class MyCustomLLM implements LLM {
    @Override
    public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
        // 调用你的模型 API
    }
    // ... 实现其他 chat 方法
}
```

### 添加新的文档解析器

实现 `DocumentReader` 接口并注册到 `DocumentReaders`：

```java
public class MyDocumentReader implements DocumentReader {
    @Override
    public boolean supports(String extension) {
        return "myext".equals(extension);
    }

    @Override
    public ParsedDocument read(DocumentSource source) throws IOException {
        // 解析文档并返回 ParsedDocument
    }
}
```

### 添加新的知识存储

实现 `KnowledgeStore` 接口即可接入新的向量数据库：

```java
public class MyKnowledgeStore implements KnowledgeStore {
    @Override
    public String add(DocumentChunk chunk) { /* ... */ }
    @Override
    public List<String> addAll(List<DocumentChunk> chunks) { /* ... */ }
    @Override
    public List<SearchResult> search(SearchRequest request) { /* ... */ }
    @Override
    public void delete(String chunkId) { /* ... */ }
    @Override
    public void deleteAll(List<String> chunkIds) { /* ... */ }
    @Override
    public void deleteByDocumentId(String documentId) { /* ... */ }
}
```
