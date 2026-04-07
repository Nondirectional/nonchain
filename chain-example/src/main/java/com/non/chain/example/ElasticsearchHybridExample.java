package com.non.chain.example;

import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.RetrievalResponse;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.elasticsearch.ElasticsearchKnowledgeStore;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

/**
 * 示例：使用 ElasticsearchKnowledgeStore 的统一检索入口进行混合检索。
 *
 * 前置条件：Elasticsearch 运行在 localhost:9200，且服务端支持原生 retriever API，并安装 IK 分词插件。
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

        // 5. 混合检索（向量 + BM25，ES 原生 retriever）
        float[] queryVec = embeddingModel.embed("Elasticsearch 是分布式搜索引擎");

        RetrievalResponse response = store.search(SearchRequest.builder()
                .queryText("Elasticsearch 是分布式搜索引擎")
                .queryEmbedding(queryVec)
                .size(5)
                .addKnowledgeBaseId(kbId)
                .debug(true)
                .build());

        for (SearchResult result : response.results()) {
            System.out.printf(
                    "[%.4f] %s%n", result.score(), result.content());
        }
        if (response.debugInfo() != null) {
            System.out.println("检索模式: " + response.debugInfo().mode());
            System.out.println("融合策略: " + response.debugInfo().fusionStrategy());
        }

        restClient.close();
    }
}
