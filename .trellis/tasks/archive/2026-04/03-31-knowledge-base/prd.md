# Knowledge Base 抽象层与实现

## Goal

为 nonchain LLM 库定义知识库抽象层，提供可扩展的基本实现。采用多模块架构，核心接口零外部依赖，用户按需引入 pgvector 或 Elasticsearch 实现。支持向量检索 + 可选双路混合检索（RRF 融合）。

## Requirements

1. **独立 EmbeddingModel 接口** — 与现有 LLM 接口平行，职责分离
2. **KnowledgeStore 接口** — 纯向量存储和检索，不绑定 embedding
3. **SourceDocument / DocumentChunk 值对象** — 文档与分块分层建模，分块为检索与召回单位
4. **SearchRequest / SearchResult** — 检索请求和结果封装（结果为 chunk 级）
5. **元数据过滤** — 支持按 metadata 键值对过滤检索结果
6. **删除操作** — 支持按 ID 删除单个/批量 chunk，并可按 document 级删除
7. **TextSplitter 接口** — 预留 chunk 切分抽象，MVP 不提供具体实现
8. **多库/多文档过滤** — 支持单个或多个 knowledgeBaseId、documentId 查询范围
9. **自动建表** — PgvectorKnowledgeStore 初始化时自动创建表和索引
10. **PgvectorKnowledgeStore** — 基于 pgvector 的 KnowledgeStore 实现
11. **ElasticsearchKnowledgeStore** — 基于 ES kNN 的 KnowledgeStore 实现
12. **HybridRetriever** — 组合 KnowledgeStore（向量检索）+ ES（BM25 关键词检索），RRF 融合
13. **与 Graph workflow 集成** — 通过 State 传递实例

## Acceptance Criteria

- [ ] EmbeddingModel 接口定义清晰，支持单条和批量 embed
- [ ] KnowledgeStore 接口包含 add/addAll/search/delete 方法
- [ ] SourceDocument、DocumentChunk、SearchRequest、SearchResult 为不可变值对象
- [ ] SearchRequest 支持 `knowledgeBaseIds`、`documentIds`、可选 `chunkIds`
- [ ] SearchResult 为 chunk 级结果，包含 `knowledgeBaseId/documentId/chunkId`
- [ ] PgvectorKnowledgeStore 可写入、检索、删除，自动建表
- [ ] ElasticsearchKnowledgeStore 可写入、检索（kNN）
- [ ] HybridRetriever 双路检索 + RRF 融合可工作
- [ ] 多模块 Maven 构建通过
- [ ] 示例代码展示典型用法
- [ ] 与现有 Graph workflow 可集成

## Definition of Done

- 核心接口和值对象编写完成
- pgvector 实现编写完成
- ES 实现和 HybridRetriever 编写完成
- Maven 多模块构建通过（core 无外部新依赖）
- 示例代码可编译
- 现有代码和测试不受影响（向后兼容）

## Technical Approach

### 模块结构（多模块拆分）

```
nonchain/ (parent pom, packaging: pom)
├── chain/                     -- 核心模块（现有代码 + 新接口）
│   └── com.non.chain/
│       ├── provider/          -- 现有 LLM + DashscopeLLM
│       ├── embedding/         -- 新增: EmbeddingModel, Embedding
│       ├── knowledge/         -- 新增: KnowledgeStore, KeywordRetriever, SourceDocument,
│       │                         DocumentChunk, SearchResult, SearchRequest, MetadataFilter, TextSplitter
│       ├── tool/              -- 现有
│       └── flow/              -- 现有
├── chain-pgvector/            -- pgvector 实现
│   └── com.non.chain.knowledge.pgvector/
│       └── PgvectorKnowledgeStore
├── chain-elasticsearch/       -- ES 实现 + HybridRetriever
│   └── com.non.chain.knowledge.elasticsearch/
│       ├── ElasticsearchKnowledgeStore
│       ├── ElasticsearchBM25Retriever
│       └── HybridRetriever
```

### 核心接口设计

**EmbeddingModel**（com.non.chain.embedding）
```java
public interface EmbeddingModel {
    List<float[]> embedAll(List<String> texts);   // 批量，核心方法
    float[] embed(String text);                    // 单条，default 委托 embedAll
    int dimension();                               // 向量维度，default 从 embed 推断
}
```

**KnowledgeStore**（com.non.chain.knowledge）
```java
public interface KnowledgeStore {
    String add(DocumentChunk chunk);                       // 写入 chunk，返回 chunkId
    List<String> addAll(List<DocumentChunk> chunks);       // 批量写入 chunk
    List<SearchResult> search(SearchRequest request);      // 向量检索（chunk 级召回）
    void delete(String chunkId);                           // 按 chunk 删除
    void deleteAll(List<String> chunkIds);                 // 批量删除 chunk
    void deleteByDocumentId(String documentId);            // 按 document 删除
}
```

**TextSplitter**（com.non.chain.knowledge）
```java
public interface TextSplitter {
    List<String> split(String text);
}
```

**KeywordRetriever**（com.non.chain.knowledge）— 关键词检索抽象，支持双路混合
```java
public interface KeywordRetriever {
    List<SearchResult> search(String queryText, int topK);
}
```

**值对象**（均为不可变 + Builder）
- `SourceDocument` — documentId, knowledgeBaseId, sourceType, sourceName, metadata(Map)
- `DocumentChunk` — chunkId, documentId, knowledgeBaseId, content, metadata(Map), embedding(float[]可选), chunkIndex
- `SearchRequest` — queryEmbedding, topK, minScore, knowledgeBaseIds, documentIds, chunkIds, metadataFilter
- `SearchResult` — knowledgeBaseId, documentId, chunkId, content, metadata, score, chunkIndex(可选)
- `MetadataFilter` — 递归过滤条件（key, operator, value, AND/OR 组合）

### 依赖关系

```
chain (core)          ← 零新外部依赖
chain-pgvector        ← chain + pgvector + postgresql + hikaricp
chain-elasticsearch   ← chain + elasticsearch-java + jackson
```

### 混合检索架构（渐进式：单路 → 双路）

**层级1（基础）**: EmbeddingModel + PgvectorKnowledgeStore → 纯向量检索
**层级2（进阶）**: 加入 ES → 双路混合检索（pgvector 向量 + ES BM25）

```
单路模式:                              双路模式:
EmbeddingModel                         EmbeddingModel
     │                                      │
     ▼                                      ▼
PgvectorKnowledgeStore                 HybridRetriever
     │                                      ├── PgvectorKnowledgeStore (向量路径)
     ▼                                      └── ElasticsearchBM25Retriever (关键词路径)
List<SearchResult>                                   │
                                                 ES BM25 检索
                                                      │
                                                 List<SearchResult>
                                                      │
                                                RRF 融合
                                                      │
                                                      ▼
                                                 List<SearchResult>
```

**数据写入**：用户负责同步写入两个存储，不自动同步
```java
// 单路：写入 chunk 到 pgvector
store.add(DocumentChunk.of(knowledgeBaseId, documentId, content, embedding, metadata));

// 双路：写 pgvector + ES
store.add(DocumentChunk.of(knowledgeBaseId, documentId, content, embedding, metadata)); // pgvector
esStore.add(DocumentChunk.of(knowledgeBaseId, documentId, content, metadata));           // ES（BM25）
```

**HybridRetriever**（chain-elasticsearch 模块）
```
HybridRetriever
├── KnowledgeStore vectorStore          // 任意实现（通常是 PgvectorKnowledgeStore）
├── KeywordRetriever keywordRetriever   // 通过 store.createBM25Retriever() 创建，自动对齐索引名和 analyzer
├── int rrfK = 60                       // RRF 常数，可配置
│
└── search(queryEmbedding, queryText, topK, knowledgeBaseIds, documentIds):
    1. vectorStore.search() → 向量检索结果
    2. keywordRetriever.search() → 关键词检索结果
    3. RRF 融合（chunk 级）→ 最终排序结果
```

### 中文分词支持

- **向量检索路径**（pgvector）：不受影响，embedding 模型内部已处理语义
- **关键词检索路径**（ES BM25）：需要中文分词器
  - ES 默认 `standard` 分析器对中文按单字切分，检索效果差
  - 推荐 ES 集群安装 **IK Analyzer** 插件（`ik_smart` / `ik_max_word`）
  - `ElasticsearchBM25Retriever` 和 `ElasticsearchKnowledgeStore` 需支持 **analyzer 配置**
  - 默认值设为 `ik_smart`，用户可通过 Builder 覆盖
  - `ElasticsearchKnowledgeStore` 自动建索引时，content 字段使用用户指定的 analyzer
- 分词是 ES **部署层面**的关注点（安装插件 + 配置 mapping），代码层面确保可配置即可

### 检索策略

- pgvector：余弦距离（`<=>` 操作符），ivfflat 索引，单表 `document_chunks`，knowledgeBaseId 为过滤字段
- ES kNN：`dense_vector` + `cosine` similarity，单索引（默认 `knowledge_chunks`），knowledgeBaseId 为过滤字段
- ES BM25：match query + 用户配置的 analyzer（默认 `ik_smart`），通过 `store.createBM25Retriever()` 创建同索引检索器
- RRF 融合：`score(d) = Σ 1/(k + rank_i(d))`，k=60

## Decision (ADR-lite)

**Context**: 需要为知识库功能选择架构模式
**Decision**:
- EmbeddingModel 独立于 LLM 接口（业界共识，职责分离）
- KnowledgeStore 不绑定 EmbeddingModel（灵活，支持预计算向量）
- 混合检索在 Store 外部组合（HybridRetriever 组合 KnowledgeStore + ES）
- 多模块按需引入（核心零新依赖，pgvector/ES 可选）
**Consequences**:
- 用户需要手动组合 embed → store 流程，但换来了灵活性
- 多模块增加构建复杂度，但避免了不必要的依赖传递
- HybridRetriever 依赖 ES 客户端，放在 ES 模块中

## Out of Scope

- Web API 层
- 文档解析（PDF/Word 等）
- 具体的 TextSplitter 实现（如按长度、按句号切分）
- 可视化管理界面
- 分布式/集群部署方案
- chunk 切分管道

## Technical Notes

- 现有模式参考：`LLM` 接口设计（纯接口 + default 便捷方法）
- 值对象参考：`Message`（不可变、私有构造器、静态工厂）
- Graph 集成：通过 State 传递 KnowledgeStore / EmbeddingModel 实例
- pgvector 依赖：`com.pgvector:pgvector:0.1.4` + `org.postgresql:postgresql:42.7.x` + HikariCP 5.x
- ES 依赖：`co.elastic.clients:elasticsearch-java:8.13.x`
- 所有新依赖兼容 Java 11

## Implementation Plan (small PRs)

- **PR1**: Maven 多模块重构 — 将现有代码迁入 chain 子模块，建立 parent pom
- **PR2**: 核心接口与值对象 — EmbeddingModel、KnowledgeStore、KeywordRetriever、SourceDocument、DocumentChunk、SearchRequest、SearchResult、MetadataFilter、TextSplitter
- **PR3**: PgvectorKnowledgeStore 实现 — JDBC + pgvector + HikariCP，自动建表
- **PR4**: Elasticsearch 实现 + HybridRetriever — ES kNN store + BM25Retriever + HybridRetriever + 中文 analyzer 配置 + RRF 融合
- **PR5**: 示例代码 — pgvector 基础用法、ES 混合检索、Graph 集成示例
