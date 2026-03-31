package com.non.chain.example;

import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;

import java.util.Arrays;
import java.util.List;

public class EmbeddingModelExample {
    public static void main(String[] args) {
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        float[] singleVector = embeddingModel.embed("非链库是一个轻量级 Java LLM 工具库");
        System.out.println("单条 embedding 维度: " + singleVector.length);
        System.out.println("Embedding 结果：");
        System.out.println(Arrays.toString(singleVector));
        List<float[]> batchVectors = embeddingModel.embedAll(List.of(
                "向量检索用于语义匹配",
                "RRF 可以融合向量检索和关键词检索"
        ));
        System.out.println("批量 embedding 数量: " + batchVectors.size());
        System.out.println("模型维度: " + embeddingModel.dimension());
        System.out.println("Embedding 结果：");
        for (float[] vector : batchVectors) {
            System.out.println(Arrays.toString(vector));
        }
    }
}
