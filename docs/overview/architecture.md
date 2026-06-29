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
│   ┌──────────────────────┐                                     │
│   │ ChatMemory (策略接口) │                                     │
│   │ ChatMemoryStore      │                                     │
│   │ (存储接口)            │                                     │
│   │ MessageWindowChat    │                                     │
│   │ TokenWindowChat      │                                     │
│   │ Tokenizer / Jtokkit  │                                     │
│   └──────────────────────┘                                     │
│                                                                │
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
│   │  LlmDocumentSplitter                      │              │
│   └──────────────────┬───────────────────────┘              │
│                      │                                       │
├──────────────────────┼──────────────────────────────────────┤
│                      │      Storage 层（数据存储）              │
│                      │                                       │
│   ┌──────────────────┴───────────────────────┐              │
│   │        KnowledgeStore (接口)               │              │
│   │                                           │              │
│   │  SearchRequest   RetrievalResponse        │              │
│   │  MetadataFilter  KeywordRetriever         │              │
│   │  TextChunk        DocumentChunk            │              │
│   │                                           │              │
│   │              ┌──────────────────────┐     │              │
│   │              │ Elasticsearch         │     │              │
│   │              │ KnowledgeStore        │     │              │
│   │              │ dense_vector + kNN    │     │              │
│   │              │ BM25(content,ik_smart)│     │              │
│   │              │ Hybrid Retriever      │     │              │
│   │              │ (ES native retriever) │     │              │
│   │              └──────────────────────┘     │              │
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
- `OpenAICompatibleLLM` — 通用 OpenAI 兼容 provider，适用于 vllm-openai、Ollama、LiteLLM 等
- `VLLM` — vLLM 专用 provider，支持 vLLM 特有的 thinking 参数格式（嵌套 `chat_template_kwargs`）
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
- 支持注册委派型子代理（SubAgent）：`registerSubAgent(name, description)` 声明式注册，由 `Agent` 按暴露模式（`DIRECT`/`DELEGATE`）决定如何把子代理暴露给 LLM；子代理默认无状态隔离（独立 systemPrompt/工具集/拦截器，父/子 callback 与 trace 隔离）

**图工作流引擎（Graph）：**

- `Graph` — 工作流引擎核心，通过 Builder 模式构建，`run()` 方法驱动执行
- `Node` — 工作流节点，封装一个 `Function<State, State>` 处理函数
- `Edge` — 节点间的有向边，支持无条件边（`Edge.of()`）和条件边（`Edge.conditional()`）
- `State` — 工作流状态，包含键值对数据（`Map<String, Object>`）和消息历史（`List<Message>`），在各节点间传递
- `GraphResult` — 执行结果，包含最终状态、完整状态历史和已执行节点列表
- `GraphEvent` — 执行事件，支持通过 `onEvent(Consumer<GraphEvent>)` 回调监听图的执行过程（GRAPH_START / NODE_START / NODE_END / GRAPH_END）

**多模态消息模型：**

- `Message` — 消息值对象，支持 system、user、assistant、tool 四种角色
- `ContentPart` — 多模态内容部件的标记接口
- `TextPart` — 文本内容部件
- `ImageUrlPart` — 图片 URL 内容部件
- `ImageDataPart` — Base64 图片数据内容部件
- `ChatResult` — LLM 响应结果，包含回复内容、思考内容和工具调用列表

**执行链路遥测（trace/）：**

- 为 Agent / Flow / SubAgent 的整棵执行链路录制 OTel 风格的 span 树（含 prompt/messages、入参出参、状态快照），供执行链路可视化与归档分析
- **正交于用户面 `ChainCallback`**：录制层有自己的 span 传播路径（`SpanContext` 真相源 + `Tracer` current-span ThreadLocal 栈 + 三处硬边界显式传播），不寄生回调，因此能绕开 SubAgent 的 `noop()` 隔离实现全树下钻
- **opt-in，默认零开销**：`Agent.builder(...).trace(store)` / `Graph.builder(name).traceStore(store)` 启用；不配置不录制，不引入全局 static 开关
- 核心类型：`Span`（强类型骨架 + schemaless `attributes`）、`Trace`（一棵执行树 + JSON 序列化）、`SpanContext`（传播真相源）、`Tracer`（span 构建 + current 栈）、`TraceStore` SPI + `InMemoryTraceStore`、`RecordingCallback`（事件→span 载荷桥）、`TraceRuntimeIds`（失败路径 runtimeId 提取）
- **单一 runtimeId，整棵树不切新根**：根 span 类型 `agent_run` / `graph_run`；失败路径保留原异常类型，runtimeId 通过 suppressed marker + `TraceRuntimeIds.find(throwable)` 暴露
- 边界：库只到 Java API（`getTrace(id)` + JSON），可视化是独立消费端；OTel/LangSmith exporter 与持久化 store（MySQL/Postgres）作为独立可选模块后置

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
- 5 种切分策略实现：
  - `RecursiveCharacterSplitter` — 递归字符切分，按分隔符层级递归拆分，支持 chunk overlap
  - `HeaderDocumentSplitter` — 标题层级切分，基于 Markdown 标题拆分
  - `SemanticSplitter` — 语义切分，基于 Embedding 在话题切换处拆分
  - `CompositeDocumentSplitter` — 组合切分，先结构后字符的二次切分
  - `LlmDocumentSplitter` — LLM 语义切分，基于 LLM 进行语义清洗和智能切分

### Storage 层（数据存储）

Storage 层提供知识数据的持久化存储和检索能力。

**核心接口：**

- `KnowledgeStore` — 知识存储接口，定义 `add()`、`search()`、`expandContext()`、`delete()` 等基本 CRUD 操作
- `KeywordRetriever` — 关键词检索接口，定义 `search(SearchRequest)` 方法
- `SearchRequest` — 统一搜索请求值对象，支持 `queryText`、`queryEmbedding`、`size`、`rankWindowSize`、`numCandidates`、过滤条件、`debug/trace`
- `RetrievalResponse` — 顶层检索响应，包含 `results` 和可选 `debugInfo`
- `SearchResult` — 单条检索结果值对象，包含知识库 ID、文档 ID、块 ID、内容和分数
- `MetadataFilter` — 元数据过滤器，支持条件组合（AND/OR/NOT）和多种操作符（EQ/NE/GT/GTE/LT/LTE/EXISTS/IN）
- `DocumentChunk` — 文档块值对象，包含内容、向量、元数据和索引位置

**存储实现：**

- `ElasticsearchKnowledgeStore` — 基于 Elasticsearch 的统一实现
  - 使用 `dense_vector` 类型存储向量，通过 kNN 搜索实现近邻查询
  - `content` 字段固定使用 `ik_smart`
  - 自动创建索引和映射
  - 支持动态元数据映射
  - 支持统一请求下的 BM25 / kNN / hybrid 自动分流

- `ElasticsearchBM25Retriever` — 基于 Elasticsearch 的 BM25 关键词检索
- `HybridRetriever` — Elasticsearch 原生 retriever 的混合检索包装器，第一版默认 `RRF`

## 模块依赖关系

```
chain-example
    ├── chain-document
    │   └── chain
    ├── chain-elasticsearch
    │   └── chain
    ├── chain-mysql
    │   └── chain
    └── chain-postgres
        └── chain
```

依赖关系遵循以下原则：

- `chain` 是基础模块，不依赖任何其他 nonchain 模块
- `chain-document`、`chain-elasticsearch` 均仅依赖 `chain`
- 功能扩展模块之间互不依赖，可以按需独立引入
- `chain-example` 依赖公开模块，仅用于演示

## 设计原则

### 接口驱动

nonchain 的核心能力均通过 Java 接口定义，实现与使用解耦：

| 接口 | 职责 | 实现类 |
|------|------|--------|
| `LLM` | 大语言模型调用 | `DashscopeLLM`, `OpenAICompatibleLLM`, `VLLM` |
| `EmbeddingModel` | 文本向量化 | `DashScopeEmbeddingModel` |
| `DocumentReader` | 文档解析 | `TxtDocumentReader`, `MarkdownDocumentReader`, `HtmlDocumentReader`, `DocxDocumentReader`, `PdfDocumentReader` |
| `DocumentCleaner` | 文档清洗 | `ControlCharacterRemover`, `UnicodeNormalizer`, 等 |
| `DocumentSplitter` | 文档切分 | `RecursiveCharacterSplitter`, `HeaderDocumentSplitter`, `SemanticSplitter`, `CompositeDocumentSplitter`, `LlmDocumentSplitter` |
| `KnowledgeStore` | 知识存储 | `ElasticsearchKnowledgeStore` |
| `KeywordRetriever` | 关键词检索 | `ElasticsearchBM25Retriever` |
| `ContentMeasure` | 内容度量 | `CharacterMeasure`, `TokenMeasure` |
| `ContentPart` | 多模态内容部件 | `TextPart`, `ImageUrlPart`, `ImageDataPart` |
| `ChatMemory` | 对话记忆策略 | `MessageWindowChatMemory`, `TokenWindowChatMemory` |
| `ChatMemoryStore` | 对话记忆存储 | `InMemoryChatMemoryStore`, `MysqlChatMemoryStore`, `PostgresChatMemoryStore` |
| `Tokenizer` | Token 计数 | `JtokkitTokenizer` |
| `AgentEvent` | Agent 流式事件 | `TextDelta`, `ThinkingDelta`, `ToolCallDelta`, `ToolStart`, `ToolEnd`, `RoundStart`, `RoundEnd`, `AgentError`, `Complete` |

这种设计使得统一请求模型可以在不同检索模式下复用。调用方通过 `SearchRequest` 描述查询意图，底层由 Elasticsearch 负责自动选择 BM25、kNN 或 hybrid 路径。

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
SearchRequest request = SearchRequest.builder()
    .queryText("向量数据库")
    .queryEmbedding(embedding)
    .size(5)
    .addKnowledgeBaseId("kb1")
    .metadataFilter(filter)
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
- `HybridRetriever` 只负责对 Elasticsearch 原生混合检索做包装，不重复在客户端实现融合逻辑

### 最小依赖

nonchain 在依赖管理上保持克制：

- 核心模块仅依赖 `openai-java`、`jackson-databind`（JSON 序列化）和 `jtokkit`（Token 计数）三个外部库
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
KnowledgeStore.search()        ← 统一检索入口
    │   + KeywordRetriever.search()  ← 专用 BM25 包装
    │   + HybridRetriever             ← ES 原生混合检索包装
    ▼
RetrievalResponse              ← 检索响应
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

nonchain 提供了 `AbstractOpenAILLM` 抽象基类，封装了 OpenAI Chat Completions API 的通用逻辑。对于兼容 OpenAI 协议的服务（如 vllm-openai、Ollama、LiteLLM），可以直接使用 `OpenAICompatibleLLM`：

```java
// 直接使用通用 provider 连接任何 OpenAI 兼容端点
LLM llm = new OpenAICompatibleLLM("http://10.100.10.21:40000/v1", "qwen3-14b")
    .temperature(0.7)
    .enableThinking(true);
```

对于有特殊参数的服务，可以继承 `AbstractOpenAILLM`：

```java
// 示例：覆写 thinking 参数格式
public class MyCustomLLM extends AbstractOpenAILLM {
    public MyCustomLLM(String baseUrl, String model) {
        super(baseUrl, "my-api-key", model, null, null);
    }

    @Override
    protected void applyAdditionalParams(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        super.applyAdditionalParams(builder, outputFormat);
        // 添加自定义参数
    }
}
```

也可以直接实现 `LLM` 接口接入非 OpenAI 协议的服务：

```java
public class MyCustomLLM implements LLM {
    @Override
    public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
        // 调用你的模型 API
    }
    // ... 实现其他 chat 方法
}
```

### 集成 Reranker

实现 `Reranker` 接口可以对检索结果进行语义重排序，提升 RAG 召回质量：

```java
// 使用 OpenAI 兼容 reranker（vLLM、Jina、Cohere）
Reranker reranker = new OpenAICompatibleReranker("http://10.100.10.21:40000/v1", "bge-reranker-large");

// 注入到 ElasticsearchKnowledgeStore
ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(client, 1024)
    .reranker(reranker)
    .build();

// 检索时自动 rerank：检索 → rerank → 截断到 size
RetrievalResponse response = store.search(request);
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

实现 `KnowledgeStore` 接口即可接入新的检索后端：

```java
public class MyKnowledgeStore implements KnowledgeStore {
    @Override
    public String add(DocumentChunk chunk) { /* ... */ }
    @Override
    public List<String> addAll(List<DocumentChunk> chunks) { /* ... */ }
    @Override
    public RetrievalResponse search(SearchRequest request) { /* ... */ }
    @Override
    public ContextExpansionResponse expandContext(ContextExpansionRequest request) { /* ... */ }
    @Override
    public void delete(String chunkId) { /* ... */ }
    @Override
    public void deleteAll(List<String> chunkIds) { /* ... */ }
    @Override
    public void deleteByDocumentId(String documentId) { /* ... */ }
}
```
