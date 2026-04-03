# PgVector 向量存储

`PgvectorKnowledgeStore` 是基于 PostgreSQL + pgvector 扩展的 `KnowledgeStore` 实现。提供向量存储、相似度检索、元数据过滤和批量操作等完整功能。

## 特性

- 自动创建数据库、表和索引（ivfflat 索引，余弦相似度）
- 完整的 `MetadataFilter` 支持（EQ、NE、GT、GTE、LT、LTE、IN、EXISTS、AND、OR、NOT）
- HikariCP 连接池管理
- 批量写入（UPSERT 语义）
- 按知识库、文档、chunk 维度删除

## Maven 依赖

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-pgvector</artifactId>
    <version>0.1.0</version>
</dependency>
```

`chain-pgvector` 自动引入以下依赖：

| 依赖 | 版本 | 说明 |
|------|------|------|
| `com.non:chain` | 0.1.0 | 核心模块 |
| `org.postgresql:postgresql` | 42.7.3 | PostgreSQL JDBC 驱动 |
| `com.zaxxer:HikariCP` | 5.1.0 | 连接池 |

## 前置条件

1. PostgreSQL 已安装并运行
2. 已安装 pgvector 扩展：`CREATE EXTENSION IF NOT EXISTS vector;`（首次使用时会自动执行）

## Builder 配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `jdbcUrl` | JDBC 连接字符串（必填） | - |
| `dimension` | 向量维度（必填） | - |
| `username` | 数据库用户名 | 空字符串 |
| `password` | 数据库密码 | 空字符串 |
| `poolSize` | 连接池大小 | 5 |
| `table` | 存储表名 | `document_chunks` |

```java
PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(
        "jdbc:postgresql://localhost:5432/nonchain", 1024)
        .username("postgres")
        .password("postgres")
        .poolSize(10)
        .table("my_chunks")
        .build();
```

## 自动初始化

首次构建 `PgvectorKnowledgeStore` 时会自动执行以下操作：

1. **创建数据库**（如果不存在）：连接 `postgres` 默认数据库并创建目标数据库
2. **创建扩展**：`CREATE EXTENSION IF NOT EXISTS vector`
3. **创建表**：

```sql
CREATE TABLE IF NOT EXISTS document_chunks (
    chunk_id          TEXT PRIMARY KEY,
    document_id       TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    content           TEXT NOT NULL,
    metadata          JSONB NOT NULL DEFAULT '{}',
    embedding         vector(1024) NOT NULL,
    chunk_index       INTEGER
)
```

4. **创建索引**：

```sql
-- 知识库索引
CREATE INDEX IF NOT EXISTS idx_document_chunks_kb ON document_chunks (knowledge_base_id)
-- 文档索引
CREATE INDEX IF NOT EXISTS idx_document_chunks_doc ON document_chunks (document_id)
-- 向量索引（ivfflat，余弦相似度）
CREATE INDEX IF NOT EXISTS idx_document_chunks_vec ON document_chunks USING ivfflat (embedding vector_cosine_ops)
```

> 注意：ivfflat 索引在空表上创建可能失败，这是正常行为，不影响功能。在有足够数据后可以手动创建。

## API 说明

### 添加文档

```java
// 添加单个 chunk
DocumentChunk chunk = DocumentChunk.builder("doc-001", "kb-demo", "这是文档内容")
        .embedding(embedding)
        .chunkIndex(0)
        .putMetadata("source", "docs")
        .build();
String chunkId = store.add(chunk);

// 批量添加
List<DocumentChunk> chunks = Arrays.asList(
    DocumentChunk.builder("doc-001", "kb-demo", "第一段内容")
            .embedding(embedding1).chunkIndex(0).build(),
    DocumentChunk.builder("doc-001", "kb-demo", "第二段内容")
            .embedding(embedding2).chunkIndex(1).build()
);
List<String> chunkIds = store.addAll(chunks);
```

### 向量检索

```java
// 基础检索
SearchRequest request = SearchRequest.builder(queryEmbedding)
        .topK(5)
        .build();
List<SearchResult> results = store.search(request);

// 带过滤条件的检索
SearchRequest request = SearchRequest.builder(queryEmbedding)
        .topK(10)
        .minScore(0.5)
        .addKnowledgeBaseId("kb-demo")
        .addDocumentId("doc-001")
        .metadataFilter(MetadataFilter.eq("source", "docs"))
        .build();
List<SearchResult> results = store.search(request);

// 多知识库检索
SearchRequest request = SearchRequest.builder(queryEmbedding)
        .topK(5)
        .knowledgeBaseIds(Arrays.asList("kb-1", "kb-2"))
        .build();
```

### 删除操作

```java
// 按 chunkId 删除
store.delete("chunk-id-001");

// 批量删除
store.deleteAll(Arrays.asList("chunk-id-001", "chunk-id-002"));

// 按文档 ID 删除
store.deleteByDocumentId("doc-001");
```

### 关闭连接池

```java
store.close();
```

## MetadataFilter 支持

`MetadataFilter` 存储在 PostgreSQL 的 `JSONB` 字段中，支持丰富的过滤操作：

```java
// 等于
MetadataFilter.eq("source", "docs")

// 不等于
MetadataFilter.ne("source", "internal")

// 大于 / 大于等于
MetadataFilter.gt("version", 2)
MetadataFilter.gte("version", 2)

// 小于 / 小于等于
MetadataFilter.lt("priority", 5)
MetadataFilter.lte("priority", 5)

// 包含
MetadataFilter.in("category", Arrays.asList("tech", "science"))

// 字段存在
MetadataFilter.exists("author")

// 组合条件
MetadataFilter.and(
    MetadataFilter.eq("source", "docs"),
    MetadataFilter.gte("version", 2)
)

MetadataFilter.or(
    MetadataFilter.eq("category", "tech"),
    MetadataFilter.eq("category", "science")
)

MetadataFilter.not(MetadataFilter.eq("status", "draft"))
```

## 完整示例

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.MetadataFilter;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.pgvector.PgvectorKnowledgeStore;

import java.util.Arrays;
import java.util.List;

public class PgvectorStoreExample {
    public static void main(String[] args) {
        // 1. 初始化 Embedding 模型
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 2. 创建 Store（自动建库、建表、建索引）
        PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(
                        "jdbc:postgresql://localhost:5432/nonchain", 1024)
                .username("postgres")
                .password("postgres")
                .poolSize(10)
                .build();

        // 3. 批量存入文档分块
        String kbId = "kb-docs";
        String docId = "doc-001";
        List<String> paragraphs = Arrays.asList(
                "Pgvector 是 PostgreSQL 的开源向量扩展。",
                "它支持多种距离度量方式，包括余弦相似度、L2 距离和内积。",
                "Pgvector 适合中小规模的向量检索场景。"
        );

        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            float[] embedding = embeddingModel.embed(paragraphs.get(i));
            chunks.add(DocumentChunk.builder(docId, kbId, paragraphs.get(i))
                    .embedding(embedding)
                    .chunkIndex(i)
                    .putMetadata("source", "wiki")
                    .putMetadata("language", "zh")
                    .build());
        }

        List<String> chunkIds = store.addAll(chunks);
        System.out.println("已存储 " + chunkIds.size() + " 个 chunk");

        // 4. 相似度检索
        float[] queryVec = embeddingModel.embed("PostgreSQL 向量扩展");
        SearchRequest request = SearchRequest.builder(queryVec)
                .topK(3)
                .minScore(0.5)
                .addKnowledgeBaseId(kbId)
                .build();

        List<SearchResult> results = store.search(request);
        System.out.println("检索结果:");
        for (SearchResult result : results) {
            System.out.printf("[%.4f] %s%n", result.score(), result.content());
            System.out.println("  metadata: " + result.metadata());
        }

        // 5. 带元数据过滤的检索
        SearchRequest filteredRequest = SearchRequest.builder(queryVec)
                .topK(3)
                .metadataFilter(MetadataFilter.eq("language", "zh"))
                .build();

        List<SearchResult> filteredResults = store.search(filteredRequest);
        System.out.println("过滤后结果: " + filteredResults.size() + " 条");

        // 6. 按文档删除
        store.deleteByDocumentId(docId);
        System.out.println("已删除文档: " + docId);

        // 7. 关闭
        store.close();
    }
}
```
