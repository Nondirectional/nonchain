# 混合检索

`HybridRetriever` 实现了双路混合检索：向量检索路径和 BM25 关键词检索路径，通过 RRF（Reciprocal Rank Fusion）算法融合两路排序结果。

## 概述

混合检索结合了向量检索和关键词检索的优势：

- **向量检索**：擅长语义匹配，能理解查询意图，即使查询词与文档词不同也能匹配
- **关键词检索（BM25）**：擅长精确匹配，对专有名词、缩写、代码等效果更好

`HybridRetriever` 将两路检索结果通过 RRF 算法融合为统一的排序结果。

## RRF 融合算法

RRF（Reciprocal Rank Fusion）是一种简单有效的排序融合方法。它基于文档在各路检索结果中的排名计算融合分数，不依赖原始评分的分布。

**公式：**

```
score(d) = sum(1 / (k + rank_i(d)))
```

其中：
- `d` 表示文档
- `k` 是平滑参数（默认 60），防止高排名文档获得过大的权重
- `rank_i(d)` 表示文档 `d` 在第 `i` 路检索结果中的排名（从 0 开始）

**示例：**

假设文档 A 在向量检索中排第 0 名，在 BM25 检索中排第 2 名：

```
score(A) = 1/(60 + 0 + 1) + 1/(60 + 2 + 1) = 1/61 + 1/63 = 0.01639 + 0.01587 = 0.03226
```

## 架构

```
                    用户查询 (queryEmbedding + queryText)
                               |
                    +----------+-----------+
                    |                      |
                    v                      v
            向量检索路径              关键词检索路径
        (KnowledgeStore.search)  (KeywordRetriever.search)
            topK * 3                 topK * 3
                    |                      |
                    +----------+-----------+
                               |
                          RRF 融合
                               |
                          最终结果
                           (topK)
```

## Builder 配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `vectorRetriever` | 向量检索器，即 `KnowledgeStore` 实现（必填） | - |
| `keywordRetriever` | 关键词检索器，即 `KeywordRetriever` 实现（必填） | - |
| `rrfK` | RRF 平滑参数 k | 60 |

```java
HybridRetriever hybrid = HybridRetriever.builder(vectorStore, bm25Retriever)
        .rrfK(60)
        .build();
```

## API 说明

### 检索

```java
public List<SearchResult> search(
        float[] queryEmbedding,    // 查询向量（向量路径）
        String queryText,          // 查询文本（BM25 路径）
        int topK,                  // 最终返回条数
        List<String> knowledgeBaseIds,  // 限定知识库（空列表 = 不限）
        List<String> documentIds        // 限定文档（空列表 = 不限）
)
```

**内部流程：**
1. 向量路径：使用 `topK * 3` 作为候选数量执行向量检索
2. 关键词路径：使用 `topK * 3` 作为候选数量执行 BM25 检索
3. RRF 融合：按 chunkId 合并两路结果，计算 RRF 分数
4. 排序截取：按 RRF 分数降序排列，返回前 `topK` 条结果

### 使用示例

```java
// 基础使用
List<SearchResult> results = hybrid.search(
        queryEmbedding, "搜索关键词", 10,
        List.of("kb-demo"), List.of()
);

// 不限定知识库和文档
List<SearchResult> results = hybrid.search(
        queryEmbedding, "搜索关键词", 10,
        Collections.emptyList(), Collections.emptyList()
);
```

### 搜索结果

`SearchResult` 中的 `score()` 字段为 RRF 融合分数（非原始向量相似度或 BM25 分数）。

## 完整示例

```java
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.elasticsearch.ElasticsearchBM25Retriever;
import com.non.chain.knowledge.elasticsearch.ElasticsearchKnowledgeStore;
import com.non.chain.knowledge.elasticsearch.HybridRetriever;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HybridRetrievalExample {
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
                .build();

        // 4. 写入测试数据
        String kbId = "kb-hybrid-demo";
        String docId = "doc-hybrid-001";

        List<String> paragraphs = Arrays.asList(
                "Java 是一种面向对象的编程语言，由 Sun Microsystems 公司开发。",
                "Spring Framework 是 Java 生态中最流行的企业级应用开发框架。",
                "Elasticsearch 是基于 Lucene 的分布式搜索和分析引擎。",
                "PostgreSQL 是功能强大的开源关系型数据库。"
        );

        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            float[] embedding = embeddingModel.embed(paragraphs.get(i));
            chunks.add(DocumentChunk.builder(docId, kbId, paragraphs.get(i))
                    .embedding(embedding)
                    .chunkIndex(i)
                    .putMetadata("source", "tech-docs")
                    .build());
        }
        store.addAll(chunks);
        System.out.println("已存储 " + chunks.size() + " 个 chunk");

        // 5. 创建 BM25 检索器
        ElasticsearchBM25Retriever bm25 = store.createBM25Retriever();

        // 6. 构建混合检索器
        HybridRetriever hybrid = HybridRetriever.builder(store, bm25)
                .rrfK(60)
                .build();

        // 7. 执行混合检索
        String queryText = "Java 编程语言和框架";
        float[] queryVec = embeddingModel.embed(queryText);

        List<SearchResult> results = hybrid.search(
                queryVec, queryText, 5,
                Collections.singletonList(kbId),
                Collections.emptyList()
        );

        // 8. 输出结果
        System.out.println("混合检索结果 (RRF 融合):");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            System.out.printf("%d. [RRF=%.6f] %s%n", i + 1, result.score(), result.content());
            System.out.println("   chunkIndex: " + result.chunkIndex());
        }

        // 9. 对比纯向量检索和纯 BM25 检索
        System.out.println();
        System.out.println("--- 纯向量检索 ---");
        List<SearchResult> vectorOnly = store.search(
                com.non.chain.knowledge.SearchRequest.builder(queryVec)
                        .topK(3)
                        .addKnowledgeBaseId(kbId)
                        .build());
        for (SearchResult r : vectorOnly) {
            System.out.printf("[%.4f] %s%n", r.score(), r.content());
        }

        System.out.println();
        System.out.println("--- 纯 BM25 检索 ---");
        List<SearchResult> bm25Only = bm25.search(queryText, 3);
        for (SearchResult r : bm25Only) {
            System.out.printf("[%.4f] %s%n", r.score(), r.content());
        }

        // 10. 清理
        store.deleteByDocumentId(docId);
        restClient.close();
    }
}
```

## RRF 参数调优

`rrfK` 参数影响排名靠前的文档在融合结果中的权重：

| rrfK 值 | 效果 |
|---------|------|
| 较小值（如 10-30） | 高排名文档权重更大，更偏向各路检索的 top 结果 |
| 默认值（60） | 平衡的融合效果，适合大多数场景 |
| 较大值（如 100-200） | 各路排名的影响更均匀，融合更"民主" |

**调优建议：**
- 如果发现某一路检索的结果被过度压制，可以尝试降低 `rrfK`
- 如果发现融合结果不够稳定，可以尝试增大 `rrfK`
- 默认值 60 是经过大量实验验证的推荐值，通常无需调整
