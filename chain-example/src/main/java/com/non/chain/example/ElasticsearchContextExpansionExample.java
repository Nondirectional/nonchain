package com.non.chain.example;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.non.chain.knowledge.ContextExpansionRequest;
import com.non.chain.knowledge.ContextExpansionResponse;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.RetrievalResponse;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.elasticsearch.ElasticsearchKnowledgeStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

/**
 * 示例：先检索命中中心 chunk，再通过 expandContext 读取相邻上下文窗口。
 *
 * 前置条件：Elasticsearch 运行在 localhost:9200，并安装 IK 分词插件。
 */
public class ElasticsearchContextExpansionExample {

    public static void main(String[] args) throws Exception {
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient esClient = new ElasticsearchClient(transport);

        ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 4)
                .indexName("context_expansion_demo")
                .build();

        String kbId = "kb-context-demo";
        String docId = "doc-context-001";

        store.deleteByDocumentId(docId);
        store.add(DocumentChunk.builder(docId, kbId, "Elasticsearch 提供 BM25、向量检索和混合检索能力。")
                .chunkIndex(0)
                .build());
        store.add(DocumentChunk.builder(docId, kbId, "当命中的片段不完整时，可以根据 chunkIndex 获取相邻上下文。")
                .chunkIndex(1)
                .build());
        store.add(DocumentChunk.builder(docId, kbId, "expandContext 负责读取固定窗口，不负责判断语义是否完整。")
                .chunkIndex(2)
                .build());
        store.add(DocumentChunk.builder(docId, kbId, "Agent 可以根据 hasPrevious 和 hasNext 决定是否继续扩展。")
                .chunkIndex(3)
                .build());

        RetrievalResponse retrieval = store.search(SearchRequest.builder()
                .queryText("语义是否完整")
                .size(1)
                .addKnowledgeBaseId(kbId)
                .build());

        if (retrieval.results().isEmpty()) {
            System.out.println("没有检索到中心 chunk");
            restClient.close();
            return;
        }

        SearchResult center = retrieval.results().get(0);
        System.out.println("=== 中心 chunk ===");
        System.out.println("chunkIndex: " + center.chunkIndex());
        System.out.println("content: " + center.content());

        ContextExpansionResponse context = store.expandContext(
                ContextExpansionRequest.builder(docId, center.chunkIndex())
                        .knowledgeBaseId(kbId)
                        .before(1)
                        .after(1)
                        .build()
        );

        System.out.println("\n=== 扩展后的上下文窗口 ===");
        for (SearchResult chunk : context.chunks()) {
            System.out.printf("[%d] %s%n", chunk.chunkIndex(), chunk.content());
        }
        System.out.println("hasPrevious: " + context.hasPrevious());
        System.out.println("hasNext: " + context.hasNext());

        restClient.close();
    }
}
