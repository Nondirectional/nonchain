package com.non.chain.knowledge.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
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
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.KnowledgeStore;
import com.non.chain.knowledge.MetadataFilter;
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
 * 基于 Elasticsearch dense_vector + kNN 的 KnowledgeStore 实现。
 * <p>
 * 所有 chunk 写入同一个索引，knowledgeBaseId 作为过滤字段。
 * <p>
 * 索引结构：
 * <pre>
 * {
 *   "chunk_id":          keyword
 *   "document_id":       keyword
 *   "knowledge_base_id": keyword
 *   "content":           text (analyzer 可配置，默认 ik_smart)
 *   "embedding":         dense_vector (dims, similarity=cosine)
 *   "chunk_index":       integer
 *   "metadata":          object (dynamic)
 * }
 * </pre>
 */
public class ElasticsearchKnowledgeStore implements KnowledgeStore {

    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_CHUNK_INDEX = "chunk_index";
    private static final String FIELD_METADATA = "metadata";

    private final ElasticsearchClient client;
    private final String indexName;
    private final int dims;
    private final String analyzer;

    private ElasticsearchKnowledgeStore(Builder builder) {
        this.client = builder.client;
        this.indexName = builder.indexName;
        this.dims = builder.dims;
        this.analyzer = builder.analyzer;
        ensureIndexExists();
    }

    // -------------------------------------------------------------------------
    // KnowledgeStore
    // -------------------------------------------------------------------------

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

            Map<String, Object> doc = buildDocument(chunk, chunkId);

            ops.add(BulkOperation.of(b -> b.index(
                    IndexOperation.of(i -> i.index(indexName).id(chunkId).document(doc))
            )));
        }

        try {
            BulkResponse resp = client.bulk(BulkRequest.of(b -> b.operations(ops)));
            if (resp.errors()) {
                String details = resp.items().stream()
                        .filter(i -> i.error() != null)
                        .map(i -> "文档 " + i.id() + ": [" + i.error().type() + "] " + i.error().reason())
                        .collect(Collectors.joining("; "));
                throw new RuntimeException("批量写入 Elasticsearch 时发生错误: " + details);
            }
        } catch (IOException e) {
            throw new RuntimeException("写入 Elasticsearch 失败", e);
        }

        return ids;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        float[] qe = request.queryEmbedding();
        List<Float> queryVector = new ArrayList<>(qe.length);
        for (float v : qe) queryVector.add(v);

        Query filter = buildFilter(request);

        KnnSearch.Builder knnBuilder = new KnnSearch.Builder()
                .field(FIELD_EMBEDDING)
                .queryVector(queryVector)
                .numCandidates((long) request.topK() * 5)
                .k((long) request.topK());
        if (filter != null) {
            knnBuilder.filter(filter);
        }
        KnnSearch knn = knnBuilder.build();

        try {
            SearchResponse<Map> resp = client.search(
                    s -> s.index(indexName).knn(knn).size(request.topK()),
                    Map.class
            );

            List<SearchResult> results = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                double score = hit.score() != null ? hit.score() : 0.0;
                if (request.minScore() != null && score < request.minScore()) {
                    continue;
                }
                results.add(toSearchResult(hit.source(), score));
            }
            return results;
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                return List.of();
            }
            throw new RuntimeException("Elasticsearch kNN 检索失败", e);
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch kNN 检索失败", e);
        }
    }

    @Override
    public void delete(String chunkId) {
        deleteAll(List.of(chunkId));
    }

    @Override
    public void deleteAll(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
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
                    .query(q -> q.term(t -> t.field(FIELD_DOCUMENT_ID).value(documentId)))
            );
        } catch (IOException e) {
            throw new RuntimeException("按 documentId 删除 Elasticsearch 文档失败", e);
        }
    }

    // -------------------------------------------------------------------------
    // 公开辅助
    // -------------------------------------------------------------------------

    /**
     * 返回当前使用的索引名。
     */
    public String indexName() {
        return indexName;
    }

    /**
     * 创建预配置的 BM25 检索器，指向同一索引，使用相同的 analyzer。
     */
    public ElasticsearchBM25Retriever createBM25Retriever() {
        return ElasticsearchBM25Retriever.builder(client)
                .addIndex(indexName)
                .analyzer(analyzer)
                .build();
    }

    // -------------------------------------------------------------------------
    // 内部辅助
    // -------------------------------------------------------------------------

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

    private void createIndex() throws IOException {
        final int finalDims = this.dims;
        final String finalAnalyzer = this.analyzer;
        client.indices().create(CreateIndexRequest.of(c -> c
                .index(indexName)
                .mappings(m -> m
                        .properties(FIELD_CHUNK_ID, p -> p.keyword(KeywordProperty.of(k -> k)))
                        .properties(FIELD_DOCUMENT_ID, p -> p.keyword(KeywordProperty.of(k -> k)))
                        .properties(FIELD_KNOWLEDGE_BASE_ID, p -> p.keyword(KeywordProperty.of(k -> k)))
                        .properties(FIELD_CONTENT, p -> p.text(TextProperty.of(t -> t.analyzer(finalAnalyzer))))
                        .properties(FIELD_CHUNK_INDEX, p -> p.integer(i -> i))
                        .properties(FIELD_METADATA, p -> p.object(ObjectProperty.of(o -> o.dynamic(co.elastic.clients.elasticsearch._types.mapping.DynamicMapping.True))))
                        .properties(FIELD_EMBEDDING, p -> p.denseVector(DenseVectorProperty.of(d -> d
                                .dims(finalDims)
                                .index(true)
                                .similarity("cosine")
                        )))
                )
        ));
    }

    private Map<String, Object> buildDocument(DocumentChunk chunk, String chunkId) {
        Map<String, Object> doc = new HashMap<>();
        doc.put(FIELD_CHUNK_ID, chunkId);
        doc.put(FIELD_DOCUMENT_ID, chunk.documentId());
        doc.put(FIELD_KNOWLEDGE_BASE_ID, chunk.knowledgeBaseId());
        doc.put(FIELD_CONTENT, chunk.content());
        doc.put(FIELD_CHUNK_INDEX, chunk.chunkIndex());
        doc.put(FIELD_METADATA, chunk.metadata());

        float[] emb = chunk.embedding();
        if (emb != null) {
            List<Float> vec = new ArrayList<>(emb.length);
            for (float v : emb) vec.add(v);
            doc.put(FIELD_EMBEDDING, vec);
        }
        return doc;
    }

    private Query buildFilter(SearchRequest request) {
        List<Query> filters = new ArrayList<>();

        if (request.knowledgeBaseIds() != null && !request.knowledgeBaseIds().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t
                    .field(FIELD_KNOWLEDGE_BASE_ID)
                    .terms(tv -> tv.value(request.knowledgeBaseIds().stream()
                            .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                            .collect(Collectors.toList())))
            )));
        }

        if (request.documentIds() != null && !request.documentIds().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t
                    .field(FIELD_DOCUMENT_ID)
                    .terms(tv -> tv.value(request.documentIds().stream()
                            .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                            .collect(Collectors.toList())))
            )));
        }

        if (request.chunkIds() != null && !request.chunkIds().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t
                    .field(FIELD_CHUNK_ID)
                    .terms(tv -> tv.value(request.chunkIds().stream()
                            .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                            .collect(Collectors.toList())))
            )));
        }

        MetadataFilter mf = request.metadataFilter();
        if (mf != null) {
            filters.add(metadataFilterToQuery(mf));
        }

        if (filters.isEmpty()) return null;
        if (filters.size() == 1) return filters.get(0);
        return Query.of(q -> q.bool(b -> b.filter(filters)));
    }

    private Query metadataFilterToQuery(MetadataFilter filter) {
        switch (filter.type()) {
            case AND: {
                List<Query> children = filter.children().stream()
                        .map(this::metadataFilterToQuery)
                        .collect(Collectors.toList());
                return Query.of(q -> q.bool(b -> b.filter(children)));
            }
            case OR: {
                List<Query> children = filter.children().stream()
                        .map(this::metadataFilterToQuery)
                        .collect(Collectors.toList());
                return Query.of(q -> q.bool(b -> b.should(children).minimumShouldMatch("1")));
            }
            case NOT: {
                Query child = metadataFilterToQuery(filter.children().get(0));
                return Query.of(q -> q.bool(b -> b.mustNot(child)));
            }
            case CONDITION:
            default:
                return conditionToQuery(filter);
        }
    }

    private Query conditionToQuery(MetadataFilter filter) {
        String field = FIELD_METADATA + "." + filter.key();
        Object value = filter.value();
        switch (filter.operator()) {
            case EQ:
                return Query.of(q -> q.term(t -> t.field(field).value(toFieldValue(value))));
            case NE:
                return Query.of(q -> q.bool(b -> b.mustNot(
                        Query.of(q2 -> q2.term(t -> t.field(field).value(toFieldValue(value))))
                )));
            case GT:
                return Query.of(q -> q.range(r -> r.field(field).gt(toJsonData(value))));
            case GTE:
                return Query.of(q -> q.range(r -> r.field(field).gte(toJsonData(value))));
            case LT:
                return Query.of(q -> q.range(r -> r.field(field).lt(toJsonData(value))));
            case LTE:
                return Query.of(q -> q.range(r -> r.field(field).lte(toJsonData(value))));
            case IN: {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) value;
                List<co.elastic.clients.elasticsearch._types.FieldValue> fieldValues = values.stream()
                        .map(this::toFieldValue)
                        .collect(Collectors.toList());
                return Query.of(q -> q.terms(t -> t.field(field)
                        .terms(tv -> tv.value(fieldValues))));
            }
            case EXISTS:
                return Query.of(q -> q.exists(e -> e.field(field)));
            default:
                throw new IllegalArgumentException("不支持的 MetadataFilter operator: " + filter.operator());
        }
    }

    private co.elastic.clients.elasticsearch._types.FieldValue toFieldValue(Object value) {
        if (value instanceof String) return co.elastic.clients.elasticsearch._types.FieldValue.of((String) value);
        if (value instanceof Long) return co.elastic.clients.elasticsearch._types.FieldValue.of((Long) value);
        if (value instanceof Integer) return co.elastic.clients.elasticsearch._types.FieldValue.of((long) (Integer) value);
        if (value instanceof Double) return co.elastic.clients.elasticsearch._types.FieldValue.of((Double) value);
        if (value instanceof Boolean) return co.elastic.clients.elasticsearch._types.FieldValue.of((Boolean) value);
        return co.elastic.clients.elasticsearch._types.FieldValue.of(value.toString());
    }

    private co.elastic.clients.json.JsonData toJsonData(Object value) {
        return co.elastic.clients.json.JsonData.of(value);
    }

    @SuppressWarnings("unchecked")
    private SearchResult toSearchResult(Map source, double score) {
        Map<String, Object> metadata = source.containsKey(FIELD_METADATA)
                ? (Map<String, Object>) source.get(FIELD_METADATA)
                : Map.of();
        Integer chunkIndex = source.get(FIELD_CHUNK_INDEX) != null
                ? ((Number) source.get(FIELD_CHUNK_INDEX)).intValue()
                : null;
        return SearchResult.builder(
                (String) source.get(FIELD_KNOWLEDGE_BASE_ID),
                (String) source.get(FIELD_DOCUMENT_ID),
                (String) source.get(FIELD_CHUNK_ID),
                (String) source.get(FIELD_CONTENT),
                score
        ).metadata(metadata).chunkIndex(chunkIndex).build();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder(ElasticsearchClient client, int dims) {
        return new Builder(client, dims);
    }

    public static class Builder {
        private final ElasticsearchClient client;
        private final int dims;
        private String indexName = "knowledge_chunks";
        private String analyzer = "ik_smart";

        private Builder(ElasticsearchClient client, int dims) {
            this.client = client;
            this.dims = dims;
        }

        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        public Builder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public ElasticsearchKnowledgeStore build() {
            return new ElasticsearchKnowledgeStore(this);
        }
    }
}
