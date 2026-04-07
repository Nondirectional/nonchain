package com.non.chain.knowledge.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.non.chain.knowledge.KeywordRetriever;
import com.non.chain.knowledge.RetrievalMode;
import com.non.chain.knowledge.RetrievalResponse;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Elasticsearch 的 BM25 检索器。
 * 第一版固定使用 content + ik_smart。
 */
public class ElasticsearchBM25Retriever implements KeywordRetriever {

    private final ElasticsearchClient client;
    private final List<String> indices;
    private final ElasticsearchSearchSupport support;

    private ElasticsearchBM25Retriever(Builder builder) {
        this.client = builder.client;
        this.indices = List.copyOf(builder.indices);
        this.support = new ElasticsearchSearchSupport(builder.client, ElasticsearchSearchSupport.DEFAULT_ANALYZER);
    }

    @Override
    public RetrievalResponse search(SearchRequest request) {
        if (!request.hasQueryText()) {
            throw new IllegalArgumentException("BM25 检索缺少 queryText");
        }

        Query query = support.combineQueryAndFilter(
                support.buildMatchQuery(request.queryText()),
                support.buildFilter(request)
        );

        try {
            SearchResponse<Map> response = client.search(
                    s -> s.index(indices)
                            .query(query)
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
                    RetrievalMode.BM25,
                    results,
                    response.took(),
                    response.profile() != null,
                    List.of("standard")
            );
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                return support.buildResponse(request, RetrievalMode.BM25, List.of(), 0L, false, List.of("standard"));
            }
            throw new RuntimeException("Elasticsearch BM25 检索失败", e);
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch BM25 检索失败", e);
        }
    }

    public static Builder builder(ElasticsearchClient client) {
        return new Builder(client);
    }

    public static class Builder {
        private final ElasticsearchClient client;
        private final List<String> indices = new ArrayList<>();

        private Builder(ElasticsearchClient client) {
            this.client = client;
        }

        public Builder addIndex(String index) {
            this.indices.add(index);
            return this;
        }

        public Builder indices(List<String> indices) {
            this.indices.clear();
            if (indices != null) {
                this.indices.addAll(indices);
            }
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
