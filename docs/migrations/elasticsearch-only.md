# Elasticsearch-Only Migration

本次版本将知识检索能力收敛为 Elasticsearch 单一路线，并移除 `chain-pgvector`。

## Breaking Changes

- 顶层模块不再包含 `chain-pgvector`
- `PgvectorKnowledgeStore` 被移除
- `KnowledgeStore.search(SearchRequest)` 现在返回 `RetrievalResponse`
- `KeywordRetriever.search(...)` 改为接收 `SearchRequest`
- `SearchRequest` 改为统一请求模型，不再暴露统一 `minScore`

## 新的检索语义

统一请求模型支持自动降级：

- 仅 `queryText` → BM25
- 仅 `queryEmbedding` → kNN
- 同时提供 → hybrid

第一版默认：

- `fusionStrategy = RRF`
- `analyzer = ik_smart`
- `size = 10`
- `rankWindowSize = max(50, size * 5)`
- `numCandidates = max(100, rankWindowSize * 2)`

## 迁移示例

旧写法：

```java
List<SearchResult> results = store.search(SearchRequest.builder(queryEmbedding)
        .topK(5)
        .build());
```

新写法：

```java
RetrievalResponse response = store.search(SearchRequest.builder()
        .queryText("查询文本")
        .queryEmbedding(queryEmbedding)
        .size(5)
        .build());

List<SearchResult> results = response.results();
```
