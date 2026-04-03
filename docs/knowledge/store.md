# KnowledgeStore 知识存储

## 概述

nonchain 的知识存储模块（`com.non.chain.knowledge`）提供了文档切分、向量存储、语义检索的完整抽象。通过 `KnowledgeStore` 接口统一不同底层存储（如 Pgvector、Elasticsearch）的访问方式，配合 `EmbeddingModel` 实现端到端的知识库管理。

核心组件：

- **KnowledgeStore**：向量存储接口，定义增删查操作
- **KeywordRetriever**：关键词/BM25 检索接口
- **DocumentSplitter**：文档切分器接口
- **TextChunk**：文本块，切分的输出单元
- **DocumentChunk**：存储单元，包含向量和元数据
- **SearchRequest / SearchResult**：检索请求与结果
- **SourceDocument**：源文档描述
- **ContentMeasure**：内容度量接口

## API 参考

### KnowledgeStore 接口

| 方法 | 说明 |
|------|------|
| `add(DocumentChunk chunk)` | 添加单个文档分块，返回 `chunkId` |
| `addAll(List<DocumentChunk> chunks)` | 批量添加文档分块，返回 `chunkId` 列表 |
| `search(SearchRequest request)` | 向量相似度搜索，返回 `SearchResult` 列表 |
| `delete(String chunkId)` | 按 chunk ID 删除 |
| `deleteAll(List<String> chunkIds)` | 按 chunk ID 列表批量删除 |
| `deleteByDocumentId(String documentId)` | 按文档 ID 删除该文档的所有分块 |

### KeywordRetriever 接口

| 方法 | 说明 |
|------|------|
| `search(String queryText, int topK)` | 关键词/BM25 搜索，返回 `SearchResult` 列表 |

### DocumentSplitter 接口

| 方法 | 说明 |
|------|------|
| `split(ParsedDocument document)` | 对文档执行切分，返回 `TextChunk` 列表 |
| `split(String text)` | 纯文本便捷方法（default 方法），内部转换为 `ParsedDocument` 后切分 |

### ContentMeasure 接口

| 方法 | 说明 |
|------|------|
| `measure(String text)` | 度量文本长度（如字符数或 token 数），`null` 返回 0 |

## 值对象

### DocumentChunk

文档分块，是存入 KnowledgeStore 的基本单元。

```java
DocumentChunk chunk = DocumentChunk.builder(documentId, knowledgeBaseId, content)
        .chunkId("custom-chunk-id")   // 可选，通常由存储层自动生成
        .embedding(embeddingVector)     // 向量
        .chunkIndex(0)                  // 分块索引
        .putMetadata("source", "docs")  // 元数据
        .build();
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `chunkId` | `String` | 分块唯一标识 |
| `documentId` | `String` | 所属文档 ID |
| `knowledgeBaseId` | `String` | 所属知识库 ID |
| `content` | `String` | 分块文本内容 |
| `metadata` | `Map<String, Object>` | 元数据（不可变） |
| `embedding` | `float[]` | 向量（防御性拷贝） |
| `chunkIndex` | `Integer` | 分块在文档中的顺序索引 |

### TextChunk

文本块，文档切分器的输出单元。

```java
// 快捷构造
TextChunk chunk = TextChunk.text("这是一段文本内容");

// Builder 构造
TextChunk chunk = TextChunk.builder("表格内容", ElementType.TABLE)
        .putMetadata("page", 3)
        .build();
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | `String` | 文本内容（IMAGE 类型允许空内容） |
| `elementType` | `ElementType` | 元素类型（TEXT、HEADING、CODE、TABLE、IMAGE 等） |
| `metadata` | `Map<String, Object>` | 元数据（不可变） |

| 静态工厂 | 说明 |
|----------|------|
| `TextChunk.text(String content)` | 快捷构造 TEXT 类型的文本块 |

### SearchRequest

检索请求，支持多种过滤条件。

```java
SearchRequest request = SearchRequest.builder(queryEmbedding)
        .topK(5)                              // 返回 top 5
        .minScore(0.7)                        // 最低相似度阈值
        .addKnowledgeBaseId("kb-001")         // 限定知识库
        .addDocumentId("doc-001")             // 限定文档
        .metadataFilter(myFilter)             // 元数据过滤
        .build();
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `queryEmbedding` | `float[]` | 查询向量（必填） |
| `topK` | `int` | 返回结果数量，默认 5 |
| `minScore` | `Double` | 最低相似度分数阈值 |
| `knowledgeBaseIds` | `List<String>` | 限定知识库范围 |
| `documentIds` | `List<String>` | 限定文档范围 |
| `chunkIds` | `List<String>` | 限定分块范围 |
| `metadataFilter` | `MetadataFilter` | 元数据过滤条件 |

### SearchResult

检索结果。

| 字段 | 类型 | 说明 |
|------|------|------|
| `knowledgeBaseId` | `String` | 所属知识库 ID |
| `documentId` | `String` | 所属文档 ID |
| `chunkId` | `String` | 分块 ID |
| `content` | `String` | 分块文本内容 |
| `metadata` | `Map<String, Object>` | 元数据 |
| `score` | `double` | 相似度分数 |
| `chunkIndex` | `Integer` | 分块索引 |

```java
SearchResult result = SearchResult.builder(kbId, docId, chunkId, content, 0.95)
        .chunkIndex(0)
        .putMetadata("source", "wiki")
        .build();
```

### SourceDocument

源文档描述，用于记录文档来源信息。

| 字段 | 类型 | 说明 |
|------|------|------|
| `documentId` | `String` | 文档唯一标识 |
| `knowledgeBaseId` | `String` | 所属知识库 ID |
| `sourceType` | `String` | 来源类型（如 `pdf`、`url`、`txt`） |
| `sourceName` | `String` | 来源名称（如文件名、URL） |
| `metadata` | `Map<String, Object>` | 元数据 |

```java
SourceDocument sourceDoc = SourceDocument.builder(
                "doc-001", "kb-001", "pdf", "knowledge-base.pdf")
        .putMetadata("author", "team")
        .putMetadata("createdAt", "2026-01-01")
        .build();
```

## 使用示例

### 典型知识库工作流

以下示例展示了从文档切分、向量化、存储到检索的完整流程。

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class KnowledgeBaseWorkflow {

    public static void main(String[] args) {
        // 1. 初始化组件
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");
        DocumentSplitter splitter = ...;     // 你的 DocumentSplitter 实例
        KnowledgeStore store = ...;          // 你的 KnowledgeStore 实例（如 PgvectorKnowledgeStore）

        String kbId = "kb-demo";
        String docId = "doc-001";

        // 2. 切分文档
        ParsedDocument document = ...;       // 通过 DocumentReader 读取文档
        List<TextChunk> textChunks = splitter.split(document);

        // 3. 批量向量化
        List<String> texts = textChunks.stream()
                .map(TextChunk::content)
                .collect(Collectors.toList());
        List<float[]> embeddings = embeddingModel.embedAll(texts);

        // 4. 构建 DocumentChunk 并批量存储
        List<DocumentChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            TextChunk textChunk = textChunks.get(i);
            DocumentChunk chunk = DocumentChunk.builder(docId, kbId, textChunk.content())
                    .embedding(embeddings.get(i))
                    .chunkIndex(i)
                    .metadata(textChunk.metadata())  // 继承切分时的元数据
                    .build();
            chunks.add(chunk);
        }

        List<String> chunkIds = store.addAll(chunks);
        System.out.println("已存储 " + chunkIds.size() + " 个分块");

        // 5. 检索
        String query = "如何使用向量数据库进行语义检索？";
        float[] queryVec = embeddingModel.embed(query);

        SearchRequest request = SearchRequest.builder(queryVec)
                .topK(5)
                .minScore(0.5)
                .addKnowledgeBaseId(kbId)
                .build();

        List<SearchResult> results = store.search(request);
        for (SearchResult result : results) {
            System.out.printf("[%.4f] (doc=%s, chunk=%d) %s%n",
                    result.score(),
                    result.documentId(),
                    result.chunkIndex(),
                    result.content());
        }

        // 6. 按文档删除（更新文档时先删除旧分块）
        store.deleteByDocumentId(docId);
    }
}
```

### Pgvector 实现

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.pgvector.PgvectorKnowledgeStore;

import java.util.List;

public class PgvectorStoreExample {
    public static void main(String[] args) {
        // 1. 初始化 Store（自动建表）
        PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(
                        "jdbc:postgresql://localhost:5432/nonchain", 1024)
                .username("postgres")
                .password("postgres")
                .build();

        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 2. 存入分块
        String kbId = "kb-demo";
        String docId = "doc-001";
        float[] embedding = embeddingModel.embed("pgvector 是 PostgreSQL 的向量扩展");

        DocumentChunk chunk = DocumentChunk.builder(docId, kbId,
                        "pgvector 是 PostgreSQL 的向量扩展")
                .embedding(embedding)
                .chunkIndex(0)
                .putMetadata("source", "docs")
                .build();

        String chunkId = store.add(chunk);
        System.out.println("已存储 chunkId: " + chunkId);

        // 3. 检索
        float[] queryVec = embeddingModel.embed("pg");
        SearchRequest request = SearchRequest.builder(queryVec)
                .topK(3)
                .minScore(0.5)
                .build();

        List<SearchResult> results = store.search(request);
        results.forEach(r -> System.out.printf(
                "[%.4f] %s%n", r.score(), r.content()));
    }
}
```

### Elasticsearch 实现

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.elasticsearch.ElasticsearchKnowledgeStore;
import com.non.chain.knowledge.elasticsearch.ElasticsearchBM25Retriever;
import com.non.chain.knowledge.elasticsearch.HybridRetriever;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.Collections;
import java.util.List;

public class ElasticsearchStoreExample {
    public static void main(String[] args) throws Exception {
        // 1. 构建 ES 客户端
        RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());
        ElasticsearchClient esClient = new ElasticsearchClient(transport);

        // 2. 初始化组件
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 3. 初始化 Store（自动创建索引）
        ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(
                        esClient, 1024)
                .build();

        // 4. 存入分块
        String kbId = "kb-es-demo";
        String docId = "doc-es-001";
        float[] embedding = embeddingModel.embed("Elasticsearch 是分布式搜索引擎");

        DocumentChunk chunk = DocumentChunk.builder(docId, kbId,
                        "Elasticsearch 是分布式搜索引擎")
                .embedding(embedding)
                .chunkIndex(0)
                .putMetadata("source", "wiki")
                .build();

        store.add(chunk);

        // 5. 向量检索
        float[] queryVec = embeddingModel.embed("分布式搜索引擎");
        SearchRequest request = SearchRequest.builder(queryVec)
                .topK(5)
                .addKnowledgeBaseId(kbId)
                .build();
        List<SearchResult> results = store.search(request);

        // 6. BM25 关键词检索
        ElasticsearchBM25Retriever bm25 = store.createBM25Retriever();
        List<SearchResult> bm25Results = bm25.search("搜索引擎", 5);

        // 7. 混合检索（向量 + BM25 RRF 融合）
        HybridRetriever hybrid = HybridRetriever.builder(store, bm25).build();
        List<SearchResult> hybridResults = hybrid.search(
                queryVec, "搜索引擎", 5,
                Collections.singletonList(kbId),
                Collections.emptyList()
        );

        restClient.close();
    }
}
```

### 文档切分

```java
import com.non.chain.document.ParsedDocument;
import com.non.chain.knowledge.DocumentSplitter;
import com.non.chain.knowledge.TextChunk;

import java.util.List;

public class SplitterExample {
    public static void main(String[] args) {
        DocumentSplitter splitter = ...; // 如 RecursiveCharacterSplitter

        // 从 ParsedDocument 切分
        ParsedDocument document = ...;
        List<TextChunk> chunks = splitter.split(document);

        // 纯文本切分（便捷方法）
        List<TextChunk> textChunks = splitter.split("这是一段很长的文本...");

        for (int i = 0; i < textChunks.size(); i++) {
            TextChunk chunk = textChunks.get(i);
            System.out.printf("Chunk[%d] (%s): %s%n",
                    i, chunk.elementType(), chunk.content());
        }
    }
}
```

## 模块依赖

| 模块 | 说明 |
|------|------|
| `chain` | 核心接口定义（KnowledgeStore、TextChunk、SearchRequest 等） |
| `chain-pgvector` | PostgreSQL + pgvector 实现 |
| `chain-elasticsearch` | Elasticsearch 实现（含 BM25、Hybrid 检索） |
| `chain-document` | 文档读取和切分实现 |
