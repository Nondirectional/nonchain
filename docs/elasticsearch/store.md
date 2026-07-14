# Elasticsearch 向量存储

`ElasticsearchKnowledgeStore` 是 nonchain 当前官方的统一检索实现。它基于 Elasticsearch `dense_vector`、BM25 和原生 retriever，提供统一的写入、过滤和检索能力。

## 特性

- 自动创建索引（如不存在）
- 固定使用 `ik_smart`
- 完整的 `MetadataFilter` 支持（EQ、NE、GT、GTE、LT、LTE、IN、EXISTS、AND、OR、NOT）
- kNN 向量检索（余弦相似度）
- 批量写入（Bulk API）
- 支持通过 `createBM25Retriever()` 创建预配置的 BM25 检索器

## Maven 依赖

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-elasticsearch</artifactId>
    <version>0.11.0</version>
</dependency>
```

`chain-elasticsearch` 自动引入以下依赖：

| 依赖 | 版本 | 说明 |
|------|------|------|
| `com.non:chain` | 0.4.0 | 核心模块 |
| `co.elastic.clients:elasticsearch-java` | 8.13.4 | 与 Java 11 兼容的 Elasticsearch Java 客户端 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.1 | JSON 序列化 |

## 前置条件

- Elasticsearch 服务运行中，且服务端支持原生 retriever API
- 如使用 `ik_smart` 分词器，需安装 IK Analysis 插件

## 索引结构

`ElasticsearchKnowledgeStore` 自动创建的索引 mapping 如下：

```json
{
  "mappings": {
    "properties": {
      "chunk_id":          { "type": "keyword" },
      "document_id":       { "type": "keyword" },
      "knowledge_base_id": { "type": "keyword" },
      "content":           { "type": "text", "analyzer": "ik_smart" },
      "embedding":         { "type": "dense_vector", "dims": 1024, "index": true, "similarity": "cosine" },
      "chunk_index":       { "type": "integer" },
      "metadata":          { "type": "object", "dynamic": true }
    }
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `chunk_id` | keyword | 文档块唯一标识 |
| `document_id` | keyword | 所属文档 ID |
| `knowledge_base_id` | keyword | 所属知识库 ID |
| `content` | text | 文档块内容，固定使用 `ik_smart` |
| `embedding` | dense_vector | 向量嵌入，支持 kNN 检索，余弦相似度 |
| `chunk_index` | integer | 文档块在原文中的序号 |
| `metadata` | object (dynamic) | 自定义元数据，支持动态字段 |

## Builder 配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `client` | ElasticsearchClient（必填） | - |
| `dims` | 向量维度（必填） | - |
| `indexName` | 索引名 | `knowledge_chunks` |

```java
ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 1024)
        .indexName("my_knowledge_chunks")
        .build();
```

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

### kNN 向量检索

```java
// 基础检索
SearchRequest request = SearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .size(5)
        .build();
RetrievalResponse response = store.search(request);

// 带过滤条件的检索
SearchRequest request = SearchRequest.builder()
        .queryText("检索词")
        .queryEmbedding(queryEmbedding)
        .size(10)
        .addKnowledgeBaseId("kb-demo")
        .addDocumentId("doc-001")
        .metadataFilter(MetadataFilter.eq("source", "docs"))
        .build();
RetrievalResponse filtered = store.search(request);
```

### 删除操作

```java
// 按 chunkId 删除
store.delete("chunk-id-001");

// 批量删除
store.deleteAll(Arrays.asList("chunk-id-001", "chunk-id-002"));

// 按文档 ID 删除（deleteByQuery）
store.deleteByDocumentId("doc-001");
```

### 创建 BM25 检索器

通过 `createBM25Retriever()` 方法可以快速创建指向同一索引、使用相同分词器的 BM25 检索器：

```java
ElasticsearchBM25Retriever bm25 = store.createBM25Retriever();
```

等效于手动创建：

```java
ElasticsearchBM25Retriever bm25 = ElasticsearchBM25Retriever.builder(esClient)
        .addIndex(store.indexName())
        .build();
```

### 扩展相邻上下文

通过 `expandContext(...)` 可以根据 `documentId + centerChunkIndex` 获取固定窗口的相邻 chunk：

```java
ContextExpansionResponse context = store.expandContext(
        ContextExpansionRequest.builder("doc-001", 3)
                .before(1)
                .after(2)
                .includeCenter(true)
                .build()
);

for (SearchResult chunk : context.chunks()) {
    System.out.printf("[%d] %s%n", chunk.chunkIndex(), chunk.content());
}
```

返回结果保证：

- 仅返回同一 `documentId` 下的 chunk
- 按 `chunk_index` 升序返回
- 超出文档边界时自动截断
- 通过 `hasPrevious()` / `hasNext()` 提示窗口外是否还有更多 chunk

## MetadataFilter 支持

`MetadataFilter` 映射到 Elasticsearch 的 `metadata.*` 字段查询：

```java
// 等于 -> term query
MetadataFilter.eq("source", "docs")

// 不等于 -> bool must_not + term
MetadataFilter.ne("source", "internal")

// 范围查询 -> range query
MetadataFilter.gt("version", 2)
MetadataFilter.gte("version", 2)
MetadataFilter.lt("priority", 5)
MetadataFilter.lte("priority", 5)

// 包含 -> terms query
MetadataFilter.in("category", Arrays.asList("tech", "science"))

// 字段存在 -> exists query
MetadataFilter.exists("author")

// 组合条件 -> bool query
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
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.elasticsearch.ElasticsearchKnowledgeStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.Arrays;
import java.util.List;

public class ElasticsearchStoreExample {
    public static void main(String[] args) throws Exception {
        // 1. 构建 ES 客户端
        RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());
        ElasticsearchClient esClient = new ElasticsearchClient(transport);

        // 2. 初始化 Embedding 模型
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 3. 创建 Store（自动创建索引）
        ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 1024)
                .indexName("knowledge_chunks")
                .build();

        System.out.println("索引名: " + store.indexName());

        // 4. 批量存入文档分块
        String kbId = "kb-es-demo";
        String docId = "doc-es-001";
        List<String> paragraphs = Arrays.asList(
                "Elasticsearch 是分布式搜索和分析引擎。",
                "它基于 Lucene 构建，提供全文搜索功能。",
                "Elasticsearch 也支持向量检索和 kNN 搜索。"
        );

        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            float[] embedding = embeddingModel.embed(paragraphs.get(i));
            chunks.add(DocumentChunk.builder(docId, kbId, paragraphs.get(i))
                    .embedding(embedding)
                    .chunkIndex(i)
                    .putMetadata("source", "wiki")
                    .build());
        }

        List<String> chunkIds = store.addAll(chunks);
        System.out.println("已存储 " + chunkIds.size() + " 个 chunk");

        // 5. kNN 向量检索
        float[] queryVec = embeddingModel.embed("分布式搜索引擎");
        SearchRequest request = SearchRequest.builder()
                .queryEmbedding(queryVec)
                .size(3)
                .addKnowledgeBaseId(kbId)
                .build();

        RetrievalResponse response = store.search(request);
        System.out.println("检索结果:");
        for (SearchResult result : response.results()) {
            System.out.printf("[%.4f] %s%n", result.score(), result.content());
        }

        // 6. 创建 BM25 检索器
        ElasticsearchBM25Retriever bm25 = store.createBM25Retriever();
        RetrievalResponse bm25Results = bm25.search(SearchRequest.builder()
                .queryText("全文搜索")
                .size(3)
                .build());
        System.out.println("BM25 结果:");
        for (SearchResult result : bm25Results.results()) {
            System.out.printf("[%.4f] %s%n", result.score(), result.content());
        }

        // 7. 按文档删除
        store.deleteByDocumentId(docId);
        System.out.println("已删除文档: " + docId);

        // 8. 关闭客户端
        restClient.close();
    }
}
```
