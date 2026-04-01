package com.non.chain.example;

import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.pgvector.PgvectorKnowledgeStore;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;

/**
 * 示例：使用 PgvectorKnowledgeStore 进行向量存储与检索。
 *
 * 前置条件：
 *   1. PostgreSQL 已安装 pgvector 扩展
 *   2. 执行过 CREATE EXTENSION IF NOT EXISTS vector;
 */
public class PgvectorExample {

    public static void main(String[] args) {
        // 1. 数据源
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"localhost"});
        ds.setPortNumbers(new int[]{5432});
        ds.setDatabaseName("nonchain");
        ds.setUser("postgres");
        ds.setPassword("postgres");

        // 2. EmbeddingModel — 此处用 mock；实际替换为 DashScopeEmbeddingModel 等
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 3. 初始化 Store（dimension=1024，自动建表）
        PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(
                        "jdbc:postgresql://localhost:5432/nonchain", 1024)
                .username("postgres")
                .password("postgres")
                .build();

        // 4. 存入文档分块
        String kbId = "kb-demo";
        String docId = "doc-001";
        float[] embedding = embeddingModel.embed("pgvector 是 PostgreSQL 的向量扩展");

        DocumentChunk chunk = DocumentChunk.builder(docId, kbId, "pgvector 是 PostgreSQL 的向量扩展")
                .embedding(embedding)
                .chunkIndex(0)
                .putMetadata("source", "docs")
                .build();

        String chunkId = store.add(chunk);
        System.out.println("已存储 chunkId: " + chunkId);

        // 5. 相似度检索
        float[] queryVec = embeddingModel.embed("pg");
        SearchRequest request = SearchRequest.builder(queryVec)
                .topK(3)
                .minScore(0.5)
                .build();

        List<SearchResult> results = store.search(request);
        results.forEach(r -> System.out.printf(
                "[%.4f] %s%n", r.score(), r.content()));
    }
}
