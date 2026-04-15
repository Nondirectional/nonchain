package com.non.chain.knowledge;

import java.util.List;

/**
 * Reranker 接口：对检索结果进行语义重排序。
 * 实现类负责调用外部 rerank 服务（如 vLLM 的 /v1/rerank 端点），
 * 根据查询与文档的语义相关性重新打分和排序。
 */
@FunctionalInterface
public interface Reranker {

    /**
     * 对检索结果进行重排序。
     *
     * @param query   查询文本
     * @param results 候选检索结果列表
     * @param topN    返回前 N 个结果
     * @return 按 relevance_score 降序排列的重排结果
     */
    List<SearchResult> rerank(String query, List<SearchResult> results, int topN);
}
