# KnowledgeStore 知识存储

## 概述

nonchain 的知识存储模块（`com.non.chain.knowledge`）提供统一的检索抽象。当前官方实现收敛为 Elasticsearch，由 `KnowledgeStore` 负责统一入口，支持：

- kNN 向量检索
- BM25 全文检索
- Elasticsearch 原生 retriever 混合检索
- 统一的 ID / metadata 过滤

核心组件：

- `KnowledgeStore`：统一检索入口，定义增删查操作
- `KeywordRetriever`：BM25 检索接口
- `SearchRequest`：统一检索请求
- `RetrievalResponse`：顶层检索响应
- `SearchResult`：单条检索结果
- `MetadataFilter`：元数据过滤条件
- `DocumentChunk`：存储单元，包含文本、向量和元数据

## API 参考

### KnowledgeStore

| 方法 | 说明 |
|------|------|
| `add(DocumentChunk chunk)` | 添加单个文档分块，返回 `chunkId` |
| `addAll(List<DocumentChunk> chunks)` | 批量添加文档分块，返回 `chunkId` 列表 |
| `search(SearchRequest request)` | 统一检索入口，返回 `RetrievalResponse` |
| `expandContext(ContextExpansionRequest request)` | 以中心 chunk 为基准读取同文档上下文窗口 |
| `delete(String chunkId)` | 按 chunk ID 删除 |
| `deleteAll(List<String> chunkIds)` | 按 chunk ID 列表批量删除 |
| `deleteByDocumentId(String documentId)` | 按文档 ID 删除该文档的所有分块 |

### KeywordRetriever

| 方法 | 说明 |
|------|------|
| `search(SearchRequest request)` | 专用 BM25 检索，返回 `RetrievalResponse` |

## SearchRequest

`SearchRequest` 是统一请求模型。它支持自动降级：

- 仅 `queryText` → BM25
- 仅 `queryEmbedding` → kNN
- 同时提供 → hybrid
- 两者都为空 → 报错

```java
SearchRequest request = SearchRequest.builder()
        .queryText("向量数据库")
        .queryEmbedding(queryEmbedding)
        .size(5)
        .rankWindowSize(50)
        .numCandidates(100)
        .addKnowledgeBaseId("kb-001")
        .addDocumentId("doc-001")
        .metadataFilter(myFilter)
        .debug(true)
        .build();
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `queryText` | `String` | 文本查询，可选 |
| `queryEmbedding` | `float[]` | 查询向量，可选 |
| `size` | `int` | 返回结果数量，默认 `10` |
| `rankWindowSize` | `int` | hybrid 融合窗口，默认 `max(50, size * 5)` |
| `numCandidates` | `int` | kNN 候选数，默认 `max(100, rankWindowSize * 2)` |
| `knowledgeBaseIds` | `List<String>` | 限定知识库范围 |
| `documentIds` | `List<String>` | 限定文档范围 |
| `chunkIds` | `List<String>` | 限定分块范围 |
| `metadataFilter` | `MetadataFilter` | 元数据过滤条件 |
| `debug` | `boolean` | 返回顶层调试信息 |
| `trace` | `boolean` | 返回更详细的诊断载荷 |
| `fusionStrategy` | `FusionStrategy` | hybrid 融合策略，默认 `RRF`，预留 `LINEAR` |

## RetrievalResponse

`RetrievalResponse` 是顶层检索响应：

- `results()`：返回 `List<SearchResult>`
- `debugInfo()`：默认 `null`；仅在 `debug/trace` 模式下返回

调试模式下的 `debugInfo` 主要包含：

- `mode`
- `fusionStrategy`
- `analyzer`
- `size`
- `rankWindowSize`
- `numCandidates`
- `filtersApplied`
- `profileIncluded`
- `tookMs`
- `matchedRetrievers`

## ContextExpansionRequest

`ContextExpansionRequest` 用于按中心 chunk 扩展上下文窗口：

```java
ContextExpansionRequest request = ContextExpansionRequest.builder("doc-001", 10)
        .before(1)
        .after(2)
        .includeCenter(true)
        .knowledgeBaseId("kb-001")
        .build();
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `documentId` | `String` | 必填，限定扩展范围为同一文档 |
| `centerChunkIndex` | `int` | 必填，中心 chunk 的序号 |
| `before` | `int` | 向前扩展的 chunk 数，默认 `0` |
| `after` | `int` | 向后扩展的 chunk 数，默认 `0` |
| `includeCenter` | `boolean` | 是否返回中心 chunk，默认 `true` |
| `knowledgeBaseId` | `String` | 可选，额外做知识库范围校验 |

## ContextExpansionResponse

`ContextExpansionResponse` 返回固定窗口扩展结果：

- `chunks()`：按 `chunkIndex ASC` 排序的上下文片段
- `hasPrevious()`：窗口左侧是否还有更早的 chunk
- `hasNext()`：窗口右侧是否还有更晚的 chunk
- `startChunkIndex()`：本次返回结果中的起始 chunkIndex
- `endChunkIndex()`：本次返回结果中的结束 chunkIndex

## SearchResult

| 字段 | 类型 | 说明 |
|------|------|------|
| `knowledgeBaseId` | `String` | 所属知识库 ID |
| `documentId` | `String` | 所属文档 ID |
| `chunkId` | `String` | 分块 ID |
| `content` | `String` | 分块文本内容 |
| `metadata` | `Map<String, Object>` | 元数据 |
| `score` | `double` | Elasticsearch 返回的最终分数 |
| `chunkIndex` | `Integer` | 分块索引 |

## 使用示例

### 统一检索入口

```java
EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");
KnowledgeStore store = ...; // ElasticsearchKnowledgeStore

String query = "如何使用向量数据库进行语义检索？";
float[] queryVec = embeddingModel.embed(query);

SearchRequest request = SearchRequest.builder()
        .queryText(query)
        .queryEmbedding(queryVec)
        .size(5)
        .addKnowledgeBaseId("kb-demo")
        .build();

RetrievalResponse response = store.search(request);
for (SearchResult result : response.results()) {
    System.out.printf("[%.4f] %s%n", result.score(), result.content());
}
```

### 固定窗口上下文扩展

```java
ContextExpansionResponse context = store.expandContext(
        ContextExpansionRequest.builder("doc-001", 10)
                .before(1)
                .after(1)
                .build()
);

for (SearchResult chunk : context.chunks()) {
    System.out.printf("[%d] %s%n", chunk.chunkIndex(), chunk.content());
}
```

### ElasticsearchKnowledgeStore

```java
ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 1024)
        .indexName("knowledge_chunks")
        .build();

DocumentChunk chunk = DocumentChunk.builder("doc-001", "kb-demo", "Elasticsearch 支持向量和全文检索")
        .embedding(embedding)
        .chunkIndex(0)
        .putMetadata("source", "docs")
        .build();

store.add(chunk);

RetrievalResponse response = store.search(SearchRequest.builder()
        .queryText("全文和向量检索")
        .queryEmbedding(queryEmbedding)
        .size(5)
        .addKnowledgeBaseId("kb-demo")
        .build());
```

### ElasticsearchBM25Retriever

```java
ElasticsearchBM25Retriever bm25 = store.createBM25Retriever();

RetrievalResponse response = bm25.search(SearchRequest.builder()
        .queryText("Elasticsearch")
        .size(5)
        .addKnowledgeBaseId("kb-demo")
        .build());
```
