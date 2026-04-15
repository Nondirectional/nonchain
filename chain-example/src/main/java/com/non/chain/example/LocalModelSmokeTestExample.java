package com.non.chain.example;

import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.embedding.OpenAICompatibleEmbeddingModel;
import com.non.chain.knowledge.OpenAICompatibleReranker;
import com.non.chain.knowledge.Reranker;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.ChatResult;
import com.non.chain.provider.LLM;
import com.non.chain.provider.OpenAICompatibleLLM;

import java.util.Arrays;
import java.util.List;

/**
 * 示例：验证本地部署的 LLM / Embedding / Reranker 三个服务可用。
 *
 * <p>三个模型部署在同一台机器，不同端口：
 * <ul>
 *   <li>LLM (Qwen)：      port 40000</li>
 *   <li>Embedding (BGE)：  port 40001</li>
 *   <li>Reranker (BGE)：   port 40002</li>
 * </ul>
 *
 * <p>修改下面的 HOST 和端口号即可适配你的部署环境。
 */
public class LocalModelSmokeTestExample {

    // ===== 按实际部署修改 =====
    private static final String HOST = "10.100.10.21";
    private static final String LLM_PORT = "40000";
    private static final String EMBEDDING_PORT = "40100";
    private static final String RERANKER_PORT = "40200";

    public static void main(String[] args) {
        System.out.println("===== 1. 测试 LLM =====");
        testLLM();

        System.out.println("\n===== 2. 测试 Embedding =====");
        testEmbedding();

        System.out.println("\n===== 3. 测试 Reranker =====");
        testReranker();

        System.out.println("\n===== 全部通过 =====");
    }

    private static void testLLM() {
        LLM llm = new OpenAICompatibleLLM(
                "http://" + HOST + ":" + LLM_PORT + "/v1",
                "qwen3-14b"
        );

        ChatResult result = llm.chat("你是一个助手", "用一句话介绍你自己");
        System.out.println("LLM 响应: " + result.content());
        System.out.println("Token 使用: " + result.tokenUsage());
    }

    private static void testEmbedding() {
        EmbeddingModel embedding = new OpenAICompatibleEmbeddingModel(
                "http://" + HOST + ":" + EMBEDDING_PORT + "/v1",
                "bge-m3"
        );

        float[] vector = embedding.embed("非链库是一个轻量级 Java LLM 工具库");
        System.out.println("Embedding 维度: " + vector.length);
        System.out.println("前 5 维: " + Arrays.toString(Arrays.copyOf(vector, 5)));
        System.out.println("模型维度: " + embedding.dimension());
    }

    private static void testReranker() {
        Reranker reranker = new OpenAICompatibleReranker(
                "http://" + HOST + ":" + RERANKER_PORT + "/v1",
                "bge-reranker-large"
        );

        List<SearchResult> candidates = List.of(
                createResult("c1", "Paris is the capital of France."),
                createResult("c2", "Berlin is the capital of Germany."),
                createResult("c3", "London is the capital of the United Kingdom.")
        );

        List<SearchResult> reranked = reranker.rerank("What is the capital of France?", candidates, 3);
        System.out.println("Rerank 结果:");
        for (int i = 0; i < reranked.size(); i++) {
            SearchResult r = reranked.get(i);
            System.out.printf("  #%d [%s] score=%.4f — %s%n",
                    i + 1, r.chunkId(), r.score(), r.content());
        }
    }

    private static SearchResult createResult(String chunkId, String content) {
        return SearchResult.builder("kb1", "doc1", chunkId, content, 1.0)
                .chunkIndex(0)
                .build();
    }
}
