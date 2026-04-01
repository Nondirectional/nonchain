package com.non.chain.example;

import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.flow.Edge;
import com.non.chain.flow.Graph;
import com.non.chain.flow.GraphResult;
import com.non.chain.flow.Node;
import com.non.chain.flow.State;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.pgvector.PgvectorKnowledgeStore;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;

/**
 * 示例：将 KnowledgeStore 集成进 Graph workflow（RAG 模式）。
 *
 * Flow 结构：embedQuery -> retrieve -> generate -> END
 */
public class GraphKnowledgeExample {

    public static void main(String[] args) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"localhost"});
        ds.setPortNumbers(new int[]{5432});
        ds.setDatabaseName("nonchain");
        ds.setUser("postgres");
        ds.setPassword("postgres");

        EmbeddingModel embeddingModel = texts -> texts.stream()
                .map(t -> new float[]{0.1f, 0.2f, 0.3f, 0.4f})
                .collect(java.util.stream.Collectors.toList());

        PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(
                        "jdbc:postgresql://localhost:5432/nonchain", 4)
                .username("postgres")
                .password("postgres")
                .build();

        // Node 1: 将用户 query 转为向量
        Node embedQuery = new Node("embedQuery", state -> {
            String query = state.<String>get("query").orElseThrow();
            float[] vec = embeddingModel.embed(query);
            return state.put("queryEmbedding", vec);
        });

        // Node 2: 向量检索
        Node retrieve = new Node("retrieve", state -> {
            float[] vec = state.<float[]>get("queryEmbedding").orElseThrow();
            SearchRequest req = SearchRequest.builder(vec)
                    .topK(3)
                    .addKnowledgeBaseId("kb-demo")
                    .build();
            List<SearchResult> results = store.search(req);
            return state.put("retrievedChunks", results);
        });

        // Node 3: 基于检索结果生成回答（此处仅打印，实际可调用 LLM）
        Node generate = new Node("generate", state -> {
            @SuppressWarnings("unchecked")
            List<SearchResult> chunks = state.<List<SearchResult>>get("retrievedChunks").orElseThrow();
            StringBuilder context = new StringBuilder();
            for (SearchResult r : chunks) {
                context.append(r.content()).append("\n");
            }
            System.out.println("=== 检索到的上下文 ===");
            System.out.println(context);
            // 实际场景：将 context + query 传给 LLM 生成答案
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
    }
}
