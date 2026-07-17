package com.non.chain.knowledge.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.non.chain.knowledge.FusionStrategy;
import com.non.chain.knowledge.RetrievalMode;
import com.non.chain.knowledge.RetrievalResponse;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户端侧混合检索器。
 * 分别执行 BM25 和 kNN 查询，然后在 Java 侧进行融合（RRF 或 Linear）。
 */
public class HybridRetriever {

    private static final int RANK_CONSTANT = 60;

    private final ElasticsearchClient client;
    private final String indexName;
    private final ElasticsearchSearchSupport support;

    private HybridRetriever(Builder builder) {
        this.client = builder.client;
        this.indexName = builder.indexName;
        this.support = new ElasticsearchSearchSupport(builder.client, ElasticsearchSearchSupport.DEFAULT_ANALYZER);
    }

    public RetrievalResponse search(SearchRequest request) {
        if (request.mode() != RetrievalMode.HYBRID) {
            throw new IllegalArgumentException("HybridRetriever 需要同时提供 queryText 和 queryEmbedding");
        }

        long startMs = System.currentTimeMillis();

        Query filter = support.buildFilter(request);

        List<SearchResult> bm25Results = executeBM25(request, filter);
        List<SearchResult> knnResults = executeKnn(request, filter);

        List<SearchResult> fused;
        List<String> matchedRetrievers;
        if (request.fusionStrategy() == FusionStrategy.LINEAR) {
            fused = linearFusion(bm25Results, knnResults, request);
            matchedRetrievers = List.of("linear", "standard", "knn");
        } else {
            fused = rrfFusion(bm25Results, knnResults, request.size());
            matchedRetrievers = List.of("rrf", "standard", "knn");
        }

        long tookMs = System.currentTimeMillis() - startMs;

        return support.buildResponse(
                request, RetrievalMode.HYBRID, fused, tookMs, false, matchedRetrievers
        );
    }

    private List<SearchResult> executeBM25(SearchRequest request, Query filter) {
        Query query = support.combineQueryAndFilter(
                support.buildMatchQuery(request.queryText()),
                filter
        );

        try {
            SearchResponse<Map> response = client.search(
                    s -> s.index(indexName)
                            .query(query)
                            .size(request.rankWindowSize())
                            .profile(request.trace()),
                    Map.class
            );
            return toResults(response);
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                return List.of();
            }
            throw new RuntimeException("Elasticsearch BM25 检索失败", e);
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch BM25 检索失败", e);
        }
    }

    private List<SearchResult> executeKnn(SearchRequest request, Query filter) {
        KnnSearch.Builder knnBuilder = new KnnSearch.Builder()
                .field(ElasticsearchSearchSupport.FIELD_EMBEDDING)
                .queryVector(support.toQueryVector(request.queryEmbedding()))
                .k(request.rankWindowSize())
                .numCandidates(request.numCandidates());
        if (filter != null) {
            knnBuilder.filter(filter);
        }

        try {
            SearchResponse<Map> response = client.search(
                    s -> s.index(indexName)
                            .knn(knnBuilder.build())
                            .size(request.rankWindowSize())
                            .profile(request.trace()),
                    Map.class
            );
            return toResults(response);
        } catch (ElasticsearchException e) {
            if (e.error() != null && "index_not_found_exception".equals(e.error().type())) {
                return List.of();
            }
            throw new RuntimeException("Elasticsearch kNN 检索失败", e);
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch kNN 检索失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<SearchResult> toResults(SearchResponse<Map> response) {
        List<SearchResult> results = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            double score = hit.score() != null ? hit.score() : 0.0;
            results.add(support.toSearchResult(source != null ? source : Map.of(), score));
        }
        return results;
    }

    /**
     * RRF 融合：score(d) = Σ 1/(rank_constant + rank_i)
     */
    private List<SearchResult> rrfFusion(List<SearchResult> bm25Results, List<SearchResult> knnResults, int size) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, SearchResult> resultByChunkId = new LinkedHashMap<>();

        for (int i = 0; i < bm25Results.size(); i++) {
            String chunkId = bm25Results.get(i).chunkId();
            scores.merge(chunkId, 1.0 / (RANK_CONSTANT + i + 1), Double::sum);
            resultByChunkId.putIfAbsent(chunkId, bm25Results.get(i));
        }

        for (int i = 0; i < knnResults.size(); i++) {
            String chunkId = knnResults.get(i).chunkId();
            scores.merge(chunkId, 1.0 / (RANK_CONSTANT + i + 1), Double::sum);
            resultByChunkId.putIfAbsent(chunkId, knnResults.get(i));
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(size)
                .map(entry -> rebuildWithScore(resultByChunkId.get(entry.getKey()), entry.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Linear 融合：score = keywordWeight * norm_bm25 + vectorWeight * norm_knn
     * 使用 min-max 归一化。
     */
    private List<SearchResult> linearFusion(List<SearchResult> bm25Results, List<SearchResult> knnResults, SearchRequest request) {
        Map<String, Double> bm25Scores = new LinkedHashMap<>();
        Map<String, Double> knnScores = new LinkedHashMap<>();
        Map<String, SearchResult> resultByChunkId = new LinkedHashMap<>();

        for (SearchResult r : bm25Results) {
            bm25Scores.put(r.chunkId(), r.score());
            resultByChunkId.putIfAbsent(r.chunkId(), r);
        }
        for (SearchResult r : knnResults) {
            knnScores.put(r.chunkId(), r.score());
            resultByChunkId.putIfAbsent(r.chunkId(), r);
        }

        double bm25Min = bm25Scores.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double bm25Max = bm25Scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double knnMin = knnScores.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double knnMax = knnScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double bm25Range = bm25Max - bm25Min;
        double knnRange = knnMax - knnMin;

        Set<String> allChunkIds = new LinkedHashSet<>();
        allChunkIds.addAll(bm25Scores.keySet());
        allChunkIds.addAll(knnScores.keySet());

        Map<String, Double> fusedScores = new LinkedHashMap<>();
        for (String chunkId : allChunkIds) {
            double score = 0;
            if (bm25Scores.containsKey(chunkId)) {
                double normalized = bm25Range > 0 ? (bm25Scores.get(chunkId) - bm25Min) / bm25Range : 0;
                score += request.keywordWeight() * normalized;
            }
            if (knnScores.containsKey(chunkId)) {
                double normalized = knnRange > 0 ? (knnScores.get(chunkId) - knnMin) / knnRange : 0;
                score += request.vectorWeight() * normalized;
            }
            fusedScores.put(chunkId, score);
        }

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(request.size())
                .map(entry -> rebuildWithScore(resultByChunkId.get(entry.getKey()), entry.getValue()))
                .collect(Collectors.toList());
    }

    private SearchResult rebuildWithScore(SearchResult original, double newScore) {
        return SearchResult.builder(
                original.knowledgeBaseId(),
                original.documentId(),
                original.chunkId(),
                original.content(),
                newScore
        ).metadata(original.metadata()).chunkIndex(original.chunkIndex()).build();
    }

    public static Builder builder(ElasticsearchClient client) {
        return new Builder(client);
    }

    public static class Builder {
        private final ElasticsearchClient client;
        private String indexName = "knowledge_chunks";

        private Builder(ElasticsearchClient client) {
            this.client = client;
        }

        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        public HybridRetriever build() {
            if (indexName == null || indexName.isBlank()) {
                throw new IllegalArgumentException("indexName 不能为空");
            }
            return new HybridRetriever(this);
        }
    }
}
