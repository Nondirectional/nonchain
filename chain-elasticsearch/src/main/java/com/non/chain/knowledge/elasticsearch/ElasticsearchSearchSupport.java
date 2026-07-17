package com.non.chain.knowledge.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.non.chain.knowledge.MetadataFilter;
import com.non.chain.knowledge.RetrievalDebugInfo;
import com.non.chain.knowledge.RetrievalMode;
import com.non.chain.knowledge.RetrievalResponse;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class ElasticsearchSearchSupport {

    static final String DEFAULT_ANALYZER = "ik_smart";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_DOCUMENT_ID = "document_id";
    static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_EMBEDDING = "embedding";
    static final String FIELD_CHUNK_INDEX = "chunk_index";
    static final String FIELD_METADATA = "metadata";

    private final ElasticsearchClient client;
    private final String analyzer;

    ElasticsearchSearchSupport(ElasticsearchClient client, String analyzer) {
        this.client = client;
        this.analyzer = analyzer;
    }

    Query buildFilter(SearchRequest request) {
        List<Query> filters = new ArrayList<>();

        if (!request.knowledgeBaseIds().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t
                    .field(FIELD_KNOWLEDGE_BASE_ID)
                    .terms(tv -> tv.value(request.knowledgeBaseIds().stream()
                            .map(FieldValue::of)
                            .collect(Collectors.toList())))
            )));
        }

        if (!request.documentIds().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t
                    .field(FIELD_DOCUMENT_ID)
                    .terms(tv -> tv.value(request.documentIds().stream()
                            .map(FieldValue::of)
                            .collect(Collectors.toList())))
            )));
        }

        if (!request.chunkIds().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t
                    .field(FIELD_CHUNK_ID)
                    .terms(tv -> tv.value(request.chunkIds().stream()
                            .map(FieldValue::of)
                            .collect(Collectors.toList())))
            )));
        }

        if (request.metadataFilter() != null) {
            filters.add(metadataFilterToQuery(request.metadataFilter()));
        }

        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return Query.of(q -> q.bool(b -> b.filter(filters)));
    }

    Query buildMatchQuery(String queryText) {
        return Query.of(q -> q.match(m -> m
                .field(FIELD_CONTENT)
                .query(queryText)
                .analyzer(analyzer)
        ));
    }

    Query combineQueryAndFilter(Query query, Query filter) {
        if (query == null) {
            return filter;
        }
        if (filter == null) {
            return query;
        }
        return Query.of(q -> q.bool(b -> b.must(query).filter(filter)));
    }

    List<Float> toQueryVector(float[] queryEmbedding) {
        List<Float> queryVector = new ArrayList<>(queryEmbedding.length);
        for (float value : queryEmbedding) {
            queryVector.add(value);
        }
        return queryVector;
    }

    @SuppressWarnings("unchecked")
    SearchResult toSearchResult(Map<String, Object> source, double score) {
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

    RetrievalResponse buildResponse(
            SearchRequest request,
            RetrievalMode mode,
            List<SearchResult> results,
            Long tookMs,
            boolean profileIncluded,
            List<String> matchedRetrievers
    ) {
        RetrievalResponse.Builder builder = RetrievalResponse.builder().results(results);
        if (request.debug() || request.trace()) {
            builder.debugInfo(buildDebugInfo(request, mode, tookMs, profileIncluded, matchedRetrievers));
        }
        return builder.build();
    }

    RetrievalDebugInfo buildDebugInfo(
            SearchRequest request,
            RetrievalMode mode,
            Long tookMs,
            boolean profileIncluded,
            List<String> matchedRetrievers
    ) {
        RetrievalDebugInfo.Builder builder = RetrievalDebugInfo.builder(mode, request.size())
                .analyzer(analyzer)
                .filtersApplied(filtersApplied(request))
                .profileIncluded(profileIncluded)
                .matchedRetrievers(matchedRetrievers)
                .tookMs(tookMs);
        if (mode == RetrievalMode.HYBRID) {
            builder.fusionStrategy(request.fusionStrategy())
                    .rankWindowSize(request.rankWindowSize())
                    .numCandidates(request.numCandidates());
        } else if (mode == RetrievalMode.KNN) {
            builder.rankWindowSize(request.rankWindowSize())
                    .numCandidates(request.numCandidates());
        }
        return builder.build();
    }

    private List<String> filtersApplied(SearchRequest request) {
        List<String> filters = new ArrayList<>();
        if (!request.knowledgeBaseIds().isEmpty()) {
            filters.add("knowledgeBaseIds");
        }
        if (!request.documentIds().isEmpty()) {
            filters.add("documentIds");
        }
        if (!request.chunkIds().isEmpty()) {
            filters.add("chunkIds");
        }
        if (request.metadataFilter() != null) {
            filters.add("metadataFilter");
        }
        return filters;
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
                return Query.of(q -> q.range(r -> r.untyped(u -> u.field(field).gt(toJsonData(value)))));
            case GTE:
                return Query.of(q -> q.range(r -> r.untyped(u -> u.field(field).gte(toJsonData(value)))));
            case LT:
                return Query.of(q -> q.range(r -> r.untyped(u -> u.field(field).lt(toJsonData(value)))));
            case LTE:
                return Query.of(q -> q.range(r -> r.untyped(u -> u.field(field).lte(toJsonData(value)))));
            case IN: {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) value;
                List<FieldValue> fieldValues = values.stream()
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

    private FieldValue toFieldValue(Object value) {
        if (value instanceof String) {
            return FieldValue.of((String) value);
        }
        if (value instanceof Long) {
            return FieldValue.of((Long) value);
        }
        if (value instanceof Integer) {
            return FieldValue.of((long) (Integer) value);
        }
        if (value instanceof Double) {
            return FieldValue.of((Double) value);
        }
        if (value instanceof Boolean) {
            return FieldValue.of((Boolean) value);
        }
        return FieldValue.of(value.toString());
    }

    private JsonData toJsonData(Object value) {
        return JsonData.of(value);
    }
}
