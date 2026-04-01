package com.non.chain.example;

import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.elasticsearch.ElasticsearchBM25Retriever;
import com.non.chain.knowledge.elasticsearch.ElasticsearchKnowledgeStore;
import com.non.chain.knowledge.elasticsearch.HybridRetriever;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * 示例：使用 ElasticsearchKnowledgeStore + HybridRetriever 进行混合检索。
 *
 * 前置条件：Elasticsearch 8.x 运行在 localhost:9200
 */
public class ElasticsearchHybridExample {

    public static void main(String[] args) throws Exception {
        // 1. 构建 ES 客户端
        RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());
        ElasticsearchClient esClient = new ElasticsearchClient(transport);

        // 2. EmbeddingModel
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 3. Store（dimension=1024，自动创建索引 "knowledge_chunks"）
        ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 1024)
                .build();

        // 4. 存入分块
        String kbId = "kb-es-demo";
        String docId = "doc-es-001";
        float[] embedding = embeddingModel.embed("Elasticsearch 是分布式搜索引擎");

        DocumentChunk chunk = DocumentChunk.builder(docId, kbId, "Elasticsearch 是分布式搜索引擎")
                .embedding(embedding)
                .chunkIndex(0)
                .putMetadata("source", "wiki")
                .build();

        store.add(chunk);
        System.out.println("文档已写入 Elasticsearch 索引: " + store.indexName());

        // 5. 混合检索（向量 + BM25 RRF 融合）
        float[] queryVec = embeddingModel.embed("Elasticsearch 是分布式搜索引擎");

        ElasticsearchBM25Retriever bm25 = store.createBM25Retriever();

        HybridRetriever hybrid = HybridRetriever.builder(store, bm25)
                .build();

        List<SearchResult> results = hybrid.search(
                queryVec, "Elasticsearch 是分布式搜索引擎", 5,
                Collections.singletonList(kbId),
                Collections.emptyList());
        results.forEach(r -> System.out.printf(
                "[%.4f] %s%n", r.score(), r.content()));

        restClient.close();
    }
}
