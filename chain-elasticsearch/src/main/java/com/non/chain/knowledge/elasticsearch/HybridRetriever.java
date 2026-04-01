package com.non.chain.knowledge.elasticsearch;

import com.non.chain.knowledge.KeywordRetriever;
import com.non.chain.knowledge.KnowledgeStore;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双路混合检索器：向量检索（KnowledgeStore）+ 关键词检索（KeywordRetriever），
 * 通过 RRF（Reciprocal Rank Fusion）融合排序结果。
 *
 * <p>RRF 公式：score(d) = Σ 1 / (k + rank_i(d))，默认 k=60。
 *
 * <p>用法：
 * <pre>{@code
 * HybridRetriever retriever = HybridRetriever.builder(vectorStore, bm25Retriever)
 *         .rrfK(60)
 *         .build();
 *
 * List<SearchResult> results = retriever.search(queryEmbedding, queryText, 10,
 *         List.of("kb1"), List.of());
 * }</pre>
 */
public class HybridRetriever {

    private final KnowledgeStore vectorStore;
    private final KeywordRetriever keywordRetriever;
    private final int rrfK;

    private HybridRetriever(Builder builder) {
        this.vectorStore = builder.vectorStore;
        this.keywordRetriever = builder.keywordRetriever;
        this.rrfK = builder.rrfK;
    }

    /**
     * 双路检索并 RRF 融合。
     *
     * @param queryEmbedding  查询向量（用于向量路径）
     * @param queryText       查询文本（用于 BM25 路径）
     * @param topK            最终返回条数
     * @param knowledgeBaseIds 限定知识库（空 = 不限）
     * @param documentIds      限定文档（空 = 不限）
     * @return 按 RRF 分数降序排列的结果列表
     */
    public List<SearchResult> search(
            float[] queryEmbedding,
            String queryText,
            int topK,
            List<String> knowledgeBaseIds,
            List<String> documentIds
    ) {
        int candidateSize = topK * 3;

        // 向量路径
        SearchRequest vectorRequest = SearchRequest.builder(queryEmbedding)
                .topK(candidateSize)
                .knowledgeBaseIds(knowledgeBaseIds)
                .documentIds(documentIds)
                .build();
        List<SearchResult> vectorResults = vectorStore.search(vectorRequest);

        // 关键词路径
        List<SearchResult> keywordResults = keywordRetriever.search(queryText, candidateSize);

        return rrf(vectorResults, keywordResults, topK);
    }

    private List<SearchResult> rrf(List<SearchResult> vectorResults, List<SearchResult> keywordResults, int topK) {
        // chunkId -> rrf 累计分
        Map<String, Double> scoreMap = new HashMap<>();
        // chunkId -> SearchResult（保留向量路径结果优先，用于构造最终结果）
        Map<String, SearchResult> resultMap = new LinkedHashMap<>();

        accumulateRrf(vectorResults, scoreMap, resultMap);
        accumulateRrf(keywordResults, scoreMap, resultMap);

        // 按 RRF 分数降序排序，截取 topK
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    SearchResult original = resultMap.get(e.getKey());
                    // 用 RRF 融合分数替换原始分数
                    return SearchResult.builder(
                            original.knowledgeBaseId(),
                            original.documentId(),
                            original.chunkId(),
                            original.content(),
                            e.getValue()
                    ).metadata(original.metadata()).chunkIndex(original.chunkIndex()).build();
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private void accumulateRrf(
            List<SearchResult> results,
            Map<String, Double> scoreMap,
            Map<String, SearchResult> resultMap
    ) {
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String id = r.chunkId();
            double rrfScore = 1.0 / (rrfK + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            resultMap.putIfAbsent(id, r);
        }
    }

    public static Builder builder(KnowledgeStore vectorStore, KeywordRetriever keywordRetriever) {
        return new Builder(vectorStore, keywordRetriever);
    }

    public static class Builder {
        private final KnowledgeStore vectorStore;
        private final KeywordRetriever keywordRetriever;
        private int rrfK = 60;

        private Builder(KnowledgeStore vectorStore, KeywordRetriever keywordRetriever) {
            this.vectorStore = vectorStore;
            this.keywordRetriever = keywordRetriever;
        }

        public Builder rrfK(int rrfK) {
            if (rrfK <= 0) throw new IllegalArgumentException("rrfK 必须大于 0");
            this.rrfK = rrfK;
            return this;
        }

        public HybridRetriever build() {
            return new HybridRetriever(this);
        }
    }
}
