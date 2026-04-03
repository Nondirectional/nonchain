# nonchain

一个轻量级的 Java AI 应用开发框架，提供 LLM 调用、工具函数、工作流编排、文档处理和知识检索能力。

## 特性

- **LLM Provider 抽象** — 统一的 LLM 调用接口，已支持阿里云 DashScope
- **工具函数框架** — 注解驱动 + 流式 API 两种方式定义工具，自动注册与调度
- **图工作流引擎** — 基于有向图的多步骤工作流编排，支持条件路由
- **文档处理** — 支持 TXT/Markdown/HTML/DOCX/PDF 解析，含 OCR 和清洗管道
- **向量存储** — PgVector / Elasticsearch 实现，支持元数据过滤
- **混合检索** — 向量搜索 + BM25 关键词检索，RRF 融合排序
- **结构化输出** — 支持 JSON Object 响应格式

## 要求

- Java 11+
- Maven 3.6+

## 快速开始

### 安装

```bash
git clone https://github.com/nondirectionl/nonchain.git
cd nonchain
mvn install -DskipTests
```

### 引入依赖

核心模块：

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain</artifactId>
    <version>0.0.3</version>
</dependency>
```

文档处理（可选）：

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-document</artifactId>
    <version>0.0.3</version>
</dependency>
```

Elasticsearch 向量存储（可选）：

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-elasticsearch</artifactId>
    <version>0.0.3</version>
</dependency>
```

PgVector 向量存储（可选）：

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-pgvector</artifactId>
    <version>0.0.3</version>
</dependency>
```

### LLM 调用

```java
// 创建 LLM 实例
LLM llm = new DashscopeLLM("your-api-key", "qwen-plus");

// 简单对话
ChatResult result = llm.chat("你是一个助手", "你好");
System.out.println(result.getContent());
```

### 工具函数调用

**注解方式：**

```java
public class WeatherService {

    @ToolDef(name = "get_weather", description = "获取指定城市的天气")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称") String city) {
        return city + ": 晴, 25°C";
    }
}

// 注册并使用
ToolRegistry registry = new ToolRegistry();
registry.scan(new WeatherService());

ChatResult result = llm.chat("北京天气怎么样？", registry.getTools());
if (result.hasToolCalls()) {
    for (ToolCall call : result.getToolCalls()) {
        String output = registry.execute(call.getName(), call.getArguments());
        // 将工具结果反馈给 LLM...
    }
}
```

**流式 API 方式：**

```java
ToolRegistry registry = new ToolRegistry();
registry.register("get_weather", "获取城市天气")
        .param("city", "城市名称")
        .handle(args -> args.getString("city") + ": 晴, 25°C");
```

### 工作流编排

```java
Graph graph = Graph.builder()
        .addNode(Node.of("classify", state -> {
            // LLM 分类
            return state;
        }))
        .addNode(Node.of("technical", state -> {
            // 技术问题处理
            return state;
        }))
        .addNode(Node.of("general", state -> {
            // 通用问题处理
            return state;
        }))
        .addEdge(Edge.of("classify", "technical"))  // 或条件路由
        .addEdge(Edge.conditional("classify", state -> {
            String type = state.getOrDefault("type", "general");
            return type.equals("technical") ? "technical" : "general";
        }))
        .startNode("classify")
        .build();

GraphResult result = graph.run(State.of(Map.of(), List.of(
        Message.user("什么是 JVM 调优？")
)));
```

### 文档处理

```java
// 自动识别文件类型并解析
DocumentReaders readers = new DocumentReaders();
readers.register(new PdfDocumentReader());
readers.register(new DocxDocumentReader());
readers.register(new MarkdownDocumentReader());

ParsedDocument doc = readers.read(new File("document.pdf"));

// 清洗管道
CleanerPipeline pipeline = CleanerPipeline.of(
    new ControlCharacterRemover(),
    new UnicodeNormalizer(),
    new WhitespaceNormalizer(),
    new BoilerplateRemover(),
    new DuplicateRemover(),
    new ShortFragmentMerger(),
    new ImageStrategyCleaner(ImageStrategyCleaner.Strategy.REMOVE)
);

ParsedDocument cleaned = pipeline.clean(doc);
```

### 向量存储与检索

**PgVector：**

```java
PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder()
        .host("localhost").port(5432)
        .database("nonchain").user("postgres").password("postgres")
        .embeddingModel(embeddingModel)
        .build();

store.add(chunk);
List<SearchResult> results = store.search(SearchRequest.builder()
        .queryEmbedding(embedding)
        .topK(5)
        .minScore(0.7)
        .build());
```

**Elasticsearch 混合检索：**

```java
ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder()
        .host("localhost").port(9200)
        .embeddingModel(embeddingModel)
        .build();

KeywordRetriever bm25 = store.createBM25Retriever();
HybridRetriever hybrid = HybridRetriever.builder()
        .vectorRetriever(store)
        .keywordRetriever(bm25)
        .build();

List<SearchResult> results = hybrid.search("查询文本", 5);
```

## 模块说明

| 模块 | 说明 |
|------|------|
| `chain` | 核心模块：LLM 抽象、工具函数、图工作流、知识存储接口、文档模型、Embedding |
| `chain-document` | 文档处理：TXT/MD/HTML/DOCX/PDF 解析 + OCR + 清洗管道 |
| `chain-elasticsearch` | Elasticsearch 向量存储、BM25 检索、混合检索（RRF） |
| `chain-pgvector` | PgVector 向量存储 |
| `chain-example` | 示例代码（15 个可运行的 Demo） |

## 架构

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
     │   (interface)   │    │   + 清洗管道     │
     └────────┬────────┘    └─────────────────┘
              │
     ┌────────┼────────┐
     │                 │
 PgVector        Elasticsearch
 KnowledgeStore  KnowledgeStore + BM25 + HybridRetriever
```

## 示例

`chain-example` 模块包含 15 个可运行的示例：

| 示例 | 说明 |
|------|------|
| `FunctionCallExample` | 注解方式工具调用，多轮对话 |
| `FunctionCallRawExample` | 流式 API 工具调用 |
| `FunctionCallMultiParamExample` | 多参数工具：注解 vs 流式对比 |
| `StructuredOutputExample` | JSON Object 结构化输出 |
| `EasyWorkflowExample` | 图工作流 + 条件路由 |
| `GraphKnowledgeExample` | RAG 管道工作流 |
| `EmbeddingModelExample` | Embedding 模型使用 |
| `PgvectorExample` | PgVector 向量存储完整流程 |
| `ElasticsearchHybridExample` | ES 混合检索完整流程 |
| `TxtDocumentReaderExample` | TXT 文档解析 |
| `MarkdownDocumentReaderExample` | Markdown 文档解析 |
| `HtmlDocumentReaderExample` | HTML 文档解析 |
| `DocxDocumentReaderExample` | Word 文档解析 |
| `PdfDocumentReaderExample` | PDF 解析 + OCR |
| `DocumentCleanerExample` | 文档清洗管道 |

运行示例前需设置环境变量：

```bash
export DASHSCOPE_API_KEY=your-api-key
```

## License

MIT
