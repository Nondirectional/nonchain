package com.non.chain.knowledge.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.ObjectProperty;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.DeleteOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import com.non.chain.knowledge.ContextExpansionRequest;
import com.non.chain.knowledge.ContextExpansionResponse;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.KnowledgeStore;
import com.non.chain.knowledge.RetrievalMode;
import com.non.chain.knowledge.RetrievalResponse;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于 Elasticsearch 的统一 KnowledgeStore 实现。
 * 支持 BM25、kNN 以及原生 retriever 驱动的混合检索。
 */
public class ElasticsearchKnowledgeStore implements KnowledgeStore {

    private final ElasticsearchClient client;
    private final String indexName;
    private final int dims;
    private final ElasticsearchSearchSupport support;

    private ElasticsearchKnowledgeStore(Builder builder) {
        this.client = builder.client;
        this.indexName = builder.indexName;
        this.dims = builder.dims;
        this.support = new ElasticsearchSearchSupport(builder.client, ElasticsearchSearchSupport.DEFAULT_ANALYZER);
        ensureIndexExists();
    }

    @Override
    public String add(DocumentChunk chunk) {
        return addAll(List.of(chunk)).get(0);
    }

    @Override
    public List<String> addAll(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<BulkOperation> ops = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            String chunkId = chunk.chunkId() != null ? chunk.chunkId() : UUID.randomUUID().toString();
            ids.add(chunkId);
            ops.add(BulkOperation.of(b -> b.index(
                    IndexOperation.of(i -> i.index(indexName).id(chunkId).document(buildDocument(chunk, chunkId)))
            )));
        }

        try {
            BulkResponse response = client.bulk(BulkRequest.of(b -> b.operations(ops)));
            if (response.errors()) {
                String details = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> "文档 " + item.id() + ": [" + item.error().type() + "] " + item.error().reason())
                        .collect(Collectors.joining("; "));
                throw new RuntimeException("批量写入 Elasticsearch 时发生错误: " + details);
            }
            return ids;
        } catch (IOException e) {
            throw new RuntimeException("写入 Elasticsearch 失败", e);
        }
    }

    @Override
    public RetrievalResponse search(SearchRequest request) {
        switch (request.mode()) {
            case BM25:
                return createBM25Retriever().search(request);
            case HYBRID:
                return createHybridRetriever().search(request);
            case KNN:
            default:
                return searchKnn(request);
        }
    }

    @Override
    public ContextExpansionResponse expandContext(ContextExpansionRequest request) {
        ensureCenterChunkExists(request);

        int startIndex = Math.max(0, request.centerChunkIndex() - request.before());
        int endIndex = request.centerChunkIndex() + request.after();

        try {
            SearchResponse<Map> response = client.search(
                    s -> s.index(indexName)
                            .query(buildWindowQuery(request, startIndex, endIndex))
                            .size(request.before() + request.after() + 1)
                            .sort(sort -> sort.field(f -> f
                                    .field(ElasticsearchSearchSupport.FIELD_CHUNK_INDEX)
                                    .order(SortOrder.Asc))),
                    Map.class
            );

            List<SearchResult> chunks = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                SearchResult result = support.toSearchResult(hit.source(), hit.score() != null ? hit.score() : 0.0);
                if (!request.includeCenter() && result.chunkIndex() != null
                        && result.chunkIndex() == request.centerChunkIndex()) {
                    continue;
                }
                chunks.add(result);
            }

            Integer resultStartIndex = chunks.isEmpty() ? null : chunks.get(0).chunkIndex();
            Integer resultEndIndex = chunks.isEmpty() ? null : chunks.get(chunks.size() - 1).chunkIndex();

            return ContextExpansionResponse.builder()
                    .chunks(chunks)
                    .hasPrevious(startIndex > 0 && existsChunkBefore(request, startIndex))
                    .hasNext(existsChunkAfter(request, endIndex))
                    .startChunkIndex(resultStartIndex)
                    .endChunkIndex(resultEndIndex)
                    .build();
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                throw new IllegalArgumentException(buildCenterNotFoundMessage(request));
            }
            throw new RuntimeException("Elasticsearch 上下文扩展失败", e);
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch 上下文扩展失败", e);
        }
    }

    @Override
    public void delete(String chunkId) {
        deleteAll(List.of(chunkId));
    }

    @Override
    public void deleteAll(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        List<BulkOperation> ops = chunkIds.stream()
                .map(id -> BulkOperation.of(b -> b.delete(
                        DeleteOperation.of(d -> d.index(indexName).id(id))
                )))
                .collect(Collectors.toList());
        try {
            client.bulk(BulkRequest.of(b -> b.operations(ops)));
        } catch (IOException e) {
            throw new RuntimeException("批量删除 Elasticsearch 文档失败", e);
        }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        try {
            client.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.term(t -> t.field(ElasticsearchSearchSupport.FIELD_DOCUMENT_ID).value(documentId)))
            );
        } catch (IOException e) {
            throw new RuntimeException("按 documentId 删除 Elasticsearch 文档失败", e);
        }
    }

    public String indexName() {
        return indexName;
    }

    public ElasticsearchBM25Retriever createBM25Retriever() {
        return ElasticsearchBM25Retriever.builder(client)
                .addIndex(indexName)
                .build();
    }

    public HybridRetriever createHybridRetriever() {
        return HybridRetriever.builder(client)
                .indexName(indexName)
                .build();
    }

    RetrievalResponse searchKnn(SearchRequest request) {
        Query filter = support.buildFilter(request);
        KnnSearch.Builder knnBuilder = new KnnSearch.Builder()
                .field(ElasticsearchSearchSupport.FIELD_EMBEDDING)
                .queryVector(support.toQueryVector(request.queryEmbedding()))
                .k((long) request.size())
                .numCandidates((long) request.numCandidates());
        if (filter != null) {
            knnBuilder.filter(filter);
        }

        try {
            SearchResponse<Map> response = client.search(
                    s -> s.index(indexName)
                            .knn(knnBuilder.build())
                            .size(request.size())
                            .profile(request.trace()),
                    Map.class
            );
            List<SearchResult> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                results.add(support.toSearchResult(hit.source(), hit.score() != null ? hit.score() : 0.0));
            }
            return support.buildResponse(
                    request,
                    RetrievalMode.KNN,
                    results,
                    response.took(),
                    response.profile() != null,
                    List.of("knn")
            );
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                return support.buildResponse(request, RetrievalMode.KNN, List.of(), 0L, false, List.of("knn"));
            }
            throw new RuntimeException("Elasticsearch kNN 检索失败", e);
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch kNN 检索失败", e);
        }
    }

    private void ensureIndexExists() {
        try {
            boolean exists = client.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
            if (!exists) {
                createIndex();
            }
        } catch (IOException e) {
            throw new RuntimeException("检查/创建索引失败: " + indexName, e);
        }
    }

    private void ensureCenterChunkExists(ContextExpansionRequest request) {
        try {
            SearchResponse<Map> response = client.search(
                    s -> s.index(indexName)
                            .query(buildCenterChunkQuery(request))
                            .size(1),
                    Map.class
            );
            if (response.hits().hits().isEmpty()) {
                throw new IllegalArgumentException(buildCenterNotFoundMessage(request));
            }
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                throw new IllegalArgumentException(buildCenterNotFoundMessage(request));
            }
            throw new RuntimeException("检查中心 chunk 失败", e);
        } catch (IOException e) {
            throw new RuntimeException("检查中心 chunk 失败", e);
        }
    }

    private boolean existsChunkBefore(ContextExpansionRequest request, int startIndex) throws IOException {
        SearchResponse<Map> response = client.search(
                s -> s.index(indexName)
                        .query(buildBeforeQuery(request, startIndex))
                        .size(1),
                Map.class
        );
        return !response.hits().hits().isEmpty();
    }

    private boolean existsChunkAfter(ContextExpansionRequest request, int endIndex) throws IOException {
        SearchResponse<Map> response = client.search(
                s -> s.index(indexName)
                        .query(buildAfterQuery(request, endIndex))
                        .size(1),
                Map.class
        );
        return !response.hits().hits().isEmpty();
    }

    private Query buildCenterChunkQuery(ContextExpansionRequest request) {
        return buildScopedQuery(
                request,
                Query.of(q -> q.range(r -> r.field(ElasticsearchSearchSupport.FIELD_CHUNK_INDEX)
                        .gte(JsonData.of(request.centerChunkIndex()))
                        .lte(JsonData.of(request.centerChunkIndex()))))
        );
    }

    private Query buildWindowQuery(ContextExpansionRequest request, int startIndex, int endIndex) {
        return buildScopedQuery(
                request,
                Query.of(q -> q.range(r -> r.field(ElasticsearchSearchSupport.FIELD_CHUNK_INDEX)
                        .gte(JsonData.of(startIndex))
                        .lte(JsonData.of(endIndex))))
        );
    }

    private Query buildBeforeQuery(ContextExpansionRequest request, int startIndex) {
        return buildScopedQuery(
                request,
                Query.of(q -> q.range(r -> r.field(ElasticsearchSearchSupport.FIELD_CHUNK_INDEX)
                        .lt(JsonData.of(startIndex))))
        );
    }

    private Query buildAfterQuery(ContextExpansionRequest request, int endIndex) {
        return buildScopedQuery(
                request,
                Query.of(q -> q.range(r -> r.field(ElasticsearchSearchSupport.FIELD_CHUNK_INDEX)
                        .gt(JsonData.of(endIndex))))
        );
    }

    private Query buildScopedQuery(ContextExpansionRequest request, Query chunkIndexQuery) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t
                .field(ElasticsearchSearchSupport.FIELD_DOCUMENT_ID)
                .value(request.documentId()))));
        if (request.knowledgeBaseId() != null) {
            filters.add(Query.of(q -> q.term(t -> t
                    .field(ElasticsearchSearchSupport.FIELD_KNOWLEDGE_BASE_ID)
                    .value(request.knowledgeBaseId()))));
        }
        filters.add(chunkIndexQuery);
        return Query.of(q -> q.bool(b -> b.filter(filters)));
    }

    private String buildCenterNotFoundMessage(ContextExpansionRequest request) {
        String message = "未找到中心 chunk: documentId=" + request.documentId()
                + ", chunkIndex=" + request.centerChunkIndex();
        if (request.knowledgeBaseId() != null) {
            message += ", knowledgeBaseId=" + request.knowledgeBaseId();
        }
        return message;
    }

    private void createIndex() throws IOException {
        final int finalDims = dims;
        client.indices().create(CreateIndexRequest.of(c -> c
                .index(indexName)
                .mappings(m -> m
                        .properties(ElasticsearchSearchSupport.FIELD_CHUNK_ID, p -> p.keyword(KeywordProperty.of(k -> k)))
                        .properties(ElasticsearchSearchSupport.FIELD_DOCUMENT_ID, p -> p.keyword(KeywordProperty.of(k -> k)))
                        .properties(ElasticsearchSearchSupport.FIELD_KNOWLEDGE_BASE_ID, p -> p.keyword(KeywordProperty.of(k -> k)))
                        .properties(ElasticsearchSearchSupport.FIELD_CONTENT, p -> p.text(TextProperty.of(t -> t.analyzer(ElasticsearchSearchSupport.DEFAULT_ANALYZER))))
                        .properties(ElasticsearchSearchSupport.FIELD_CHUNK_INDEX, p -> p.integer(i -> i))
                        .properties(ElasticsearchSearchSupport.FIELD_METADATA, p -> p.object(ObjectProperty.of(o -> o.dynamic(co.elastic.clients.elasticsearch._types.mapping.DynamicMapping.True))))
                        .properties(ElasticsearchSearchSupport.FIELD_EMBEDDING, p -> p.denseVector(DenseVectorProperty.of(d -> d
                                .dims(finalDims)
                                .index(true)
                                .similarity("cosine")
                        )))
                )
        ));
    }

    private Map<String, Object> buildDocument(DocumentChunk chunk, String chunkId) {
        Map<String, Object> doc = new HashMap<>();
        doc.put(ElasticsearchSearchSupport.FIELD_CHUNK_ID, chunkId);
        doc.put(ElasticsearchSearchSupport.FIELD_DOCUMENT_ID, chunk.documentId());
        doc.put(ElasticsearchSearchSupport.FIELD_KNOWLEDGE_BASE_ID, chunk.knowledgeBaseId());
        doc.put(ElasticsearchSearchSupport.FIELD_CONTENT, chunk.content());
        doc.put(ElasticsearchSearchSupport.FIELD_CHUNK_INDEX, chunk.chunkIndex());
        doc.put(ElasticsearchSearchSupport.FIELD_METADATA, chunk.metadata());
        float[] embedding = chunk.embedding();
        if (embedding != null) {
            doc.put(ElasticsearchSearchSupport.FIELD_EMBEDDING, support.toQueryVector(embedding));
        }
        return doc;
    }

    public static Builder builder(ElasticsearchClient client, int dims) {
        return new Builder(client, dims);
    }

    public static class Builder {
        private final ElasticsearchClient client;
        private final int dims;
        private String indexName = "knowledge_chunks";

        private Builder(ElasticsearchClient client, int dims) {
            this.client = client;
            this.dims = dims;
        }

        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        public ElasticsearchKnowledgeStore build() {
            return new ElasticsearchKnowledgeStore(this);
        }
    }
}
