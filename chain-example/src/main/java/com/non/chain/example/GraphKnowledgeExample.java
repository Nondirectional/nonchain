package com.non.chain.example;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.flow.Edge;
import com.non.chain.flow.Graph;
import com.non.chain.flow.GraphResult;
import com.non.chain.flow.Node;
import com.non.chain.flow.State;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.RetrievalResponse;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.elasticsearch.ElasticsearchKnowledgeStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.List;

/**
 * 示例：将 ElasticsearchKnowledgeStore 集成进 Graph workflow（RAG 模式）。
 */
public class  GraphKnowledgeExample {

    public static void main(String[] args) throws Exception {
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient esClient = new ElasticsearchClient(transport);

        EmbeddingModel embeddingModel = texts -> texts.stream()
                .map(t -> new float[]{0.1f, 0.2f, 0.3f, 0.4f})
                .collect(java.util.stream.Collectors.toList());

        ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 4)
                .indexName("graph_knowledge_demo")
                .build();

        store.add(DocumentChunk.builder("doc-001", "kb-demo", "向量数据库用于存储和检索 embedding。")
                .embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f})
                .chunkIndex(0)
                .putMetadata("source", "docs")
                .build());

        Node embedQuery = new Node("embedQuery", state -> {
            String query = state.<String>get("query").orElseThrow();
            float[] vec = embeddingModel.embed(query);
            return state.put("queryEmbedding", vec);
        });

        Node retrieve = new Node("retrieve", state -> {
            float[] vec = state.<float[]>get("queryEmbedding").orElseThrow();
            SearchRequest req = SearchRequest.builder()
                    .queryText(state.<String>get("query").orElseThrow())
                    .queryEmbedding(vec)
                    .size(3)
                    .addKnowledgeBaseId("kb-demo")
                    .build();
            RetrievalResponse response = store.search(req);
            return state.put("retrievedChunks", response.results());
        });

        Node generate = new Node("generate", state -> {
            @SuppressWarnings("unchecked")
            List<SearchResult> chunks = state.<List<SearchResult>>get("retrievedChunks").orElseThrow();
            StringBuilder context = new StringBuilder();
            for (SearchResult result : chunks) {
                context.append(result.content()).append("\n");
            }
            System.out.println("=== 检索到的上下文 ===");
            System.out.println(context);
            return state;
        });

        Graph graph = Graph.builder("rag-demo")
                .addNode(embedQuery)
                .addNode(retrieve)
                .addNode(generate)
                .start("embedQuery")
                .addEdge(Edge.of("embedQuery", "retrieve"))
                .addEdge(Edge.of("retrieve", "generate"))
                .addEdge(Edge.of("generate", Graph.END))
                .build();

        State initial = new State().put("query", "什么是向量数据库？");
        GraphResult result = graph.run(initial);
        System.out.println("执行节点: " + result.executedNodes());
        restClient.close();
    }
}
