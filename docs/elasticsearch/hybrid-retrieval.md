# 混合检索

`HybridRetriever` 是对 Elasticsearch 原生 retriever 混合检索的包装。第一版默认使用 `RRF`，同时在请求模型中预留 `LINEAR` 扩展位。

## 设计原则

- 混合检索优先在 Elasticsearch 内部执行，不再在 Java 客户端手工做双路融合
- `content` 字段固定使用 `ik_smart`
- 过滤统一通过 Elasticsearch `filter` 语义下推
- 结果默认精简，`debug/trace` 时返回顶层诊断信息

## 请求模型

混合检索复用统一 `SearchRequest`：

```java
SearchRequest request = SearchRequest.builder()
        .queryText("Java 编程语言和框架")
        .queryEmbedding(queryVec)
        .size(5)
        .rankWindowSize(50)
        .numCandidates(100)
        .addKnowledgeBaseId("kb-hybrid-demo")
        .debug(true)
        .build();
```

默认参数：

- `size = 10`
- `rankWindowSize = max(50, size * 5)`
- `numCandidates = max(100, rankWindowSize * 2)`
- `fusionStrategy = RRF`

## API

```java
HybridRetriever hybrid = HybridRetriever.builder(esClient)
        .indexName("knowledge_chunks")
        .build();

RetrievalResponse response = hybrid.search(request);
```

`HybridRetriever` 要求同时提供 `queryText` 和 `queryEmbedding`。如果你希望使用自动降级语义，请直接调用 `ElasticsearchKnowledgeStore.search(SearchRequest)`。

## 返回值

默认情况下：

- `response.results()` 返回最终排序后的 `SearchResult`
- `SearchResult.score()` 是 Elasticsearch 返回的最终分数

当 `debug=true` 或 `trace=true` 时：

- `response.debugInfo().mode()` = `HYBRID`
- `response.debugInfo().fusionStrategy()` = `RRF` 或 `LINEAR`
- `response.debugInfo().matchedRetrievers()` 会包含 `rrf/linear`、`standard`、`knn`

## 完整示例

```java
ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 1024)
        .build();

HybridRetriever hybrid = store.createHybridRetriever();

RetrievalResponse response = hybrid.search(SearchRequest.builder()
        .queryText("Java 编程语言和框架")
        .queryEmbedding(queryVec)
        .size(5)
        .addKnowledgeBaseId("kb-hybrid-demo")
        .debug(true)
        .build());

for (SearchResult result : response.results()) {
    System.out.printf("[%.4f] %s%n", result.score(), result.content());
}
```
