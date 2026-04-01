package com.non.chain.knowledge.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.non.chain.knowledge.KeywordRetriever;
import com.non.chain.knowledge.SearchResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Elasticsearch BM25 全文检索的 KeywordRetriever 实现。
 * 使用 match query 对 content 字段进行检索，支持中文分析器（默认 ik_smart）。
 */
public class ElasticsearchBM25Retriever implements KeywordRetriever {

    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_CHUNK_INDEX = "chunk_index";
    private static final String FIELD_METADATA = "metadata";

    private final ElasticsearchClient client;
    private final List<String> indices;
    private final String analyzer;

    private ElasticsearchBM25Retriever(Builder builder) {
        this.client = builder.client;
        this.indices = List.copyOf(builder.indices);
        this.analyzer = builder.analyzer;
    }

    @Override
    public List<SearchResult> search(String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        final String finalAnalyzer = this.analyzer;
        Query matchQuery = Query.of(q -> q.match(m -> m
                .field(FIELD_CONTENT)
                .query(queryText)
                .analyzer(finalAnalyzer)
        ));

        try {
            SearchResponse<Map> resp = client.search(
                    s -> s.index(indices).query(matchQuery).size(topK),
                    Map.class
            );

            List<SearchResult> results = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                double score = hit.score() != null ? hit.score() : 0.0;
                results.add(toSearchResult(hit.source(), score));
            }
            return results;
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                return List.of();
            }
            throw new RuntimeException("Elasticsearch BM25 检索失败", e);
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch BM25 检索失败", e);
        }
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

    public static Builder builder(ElasticsearchClient client) {
        return new Builder(client);
    }

    public static class Builder {
        private final ElasticsearchClient client;
        private final List<String> indices = new ArrayList<>();
        private String analyzer = "ik_smart";

        private Builder(ElasticsearchClient client) {
            this.client = client;
        }

        public Builder addIndex(String index) {
            this.indices.add(index);
            return this;
        }

        public Builder indices(List<String> indices) {
            this.indices.clear();
            this.indices.addAll(indices);
            return this;
        }

        public Builder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public ElasticsearchBM25Retriever build() {
            if (indices.isEmpty()) {
                throw new IllegalArgumentException("至少需要指定一个索引");
            }
            return new ElasticsearchBM25Retriever(this);
        }
    }
}
