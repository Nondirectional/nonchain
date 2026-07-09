# 集成 Reranker 模型

## Goal

为 nonchain 的知识检索管线增加 reranker（重排序）能力，利用本地部署的 bge-reranker-large 模型对检索结果进行语义精排，提升 RAG 的最终召回质量。

## What I already know

* vLLM 暴露 `/v1/rerank` 端点，兼容 Jina/Cohere API 格式
* 请求格式：`{ model, query, documents: string[], top_n }`
* 响应格式：`{ id, model, results: [{ index, document: { text }, relevance_score }], usage }`
* 本地部署：bge-reranker-large（与 LLM 同一 vLLM 实例或独立实例）
* 代码库中无任何 reranker 相关代码
* `SearchResult` 已有 `score` 字段，且有 `rebuildWithScore()` 模式
* `HybridRetriever` 已有 overfetch→fusion→truncate 模式（rankWindowSize）
* `rankWindowSize` 默认 max(50, size*5)，是 reranker 的天然候选池

## Assumptions (temporary)

* bge-reranker-large 部署在某个 vLLM 实例上（可能与其他模型共用或独立）
* reranker 的 base URL 可配置（与 LLM provider 模式一致）
* reranker 不是每次检索都必须的（可选功能）

## Open Questions

* (暂无阻塞问题)

## Requirements (evolving)

* 新增 `Reranker` 接口（chain 核心模块）
* 新增 `OpenAICompatibleReranker` 实现，调用 `/v1/rerank` 端点
* `ElasticsearchKnowledgeStore.Builder` 支持 `.reranker(Reranker)` 可选配置
* 配置 reranker 后，search 流程变为：检索 → rerank → 截断到 size
* 无 reranker 配置时行为完全不变

## Acceptance Criteria (evailing)

* [ ] `Reranker` 接口定义清晰，输入 query + List<SearchResult>，输出重排后的 List<SearchResult>
* [ ] `OpenAICompatibleReranker` 可连接 vLLM 的 /v1/rerank 端点
* [ ] 配置 reranker 后，检索结果按 reranker 的 relevance_score 重排
* [ ] 无 reranker 时行为不变
* [ ] API Key 可选（内网无认证场景）
* [ ] 单元测试覆盖

## Definition of Done

* 编译通过，测试通过
* 文档更新
* 向后兼容

## Technical Notes

### vLLM /v1/rerank API

```
POST /v1/rerank
Content-Type: application/json

{
  "model": "bge-reranker-large",
  "query": "What is the capital of France?",
  "documents": ["Paris is...", "Berlin is...", ...],
  "top_n": 3
}

Response:
{
  "id": "rerank-abc123",
  "model": "bge-reranker-large",
  "results": [
    { "index": 0, "document": { "text": "Paris is..." }, "relevance_score": 0.98 },
    { "index": 2, "document": { "text": "London is..." }, "relevance_score": 0.03 }
  ],
  "usage": { "prompt_tokens": 42, "total_tokens": 42 }
}
```

### 检索管线插入点

```
SearchRequest
  → ElasticsearchKnowledgeStore.search()
    → BM25 / KNN / Hybrid 检索（返回 rankWindowSize 个候选）
    → [Reranker.rerank(query, candidates, size)]  ← 新增步骤
    → 截断到 size
  → RetrievalResponse
```

### 关键文件

| 文件 | 作用 | 变更 |
|------|------|------|
| `chain/.../knowledge/Reranker.java` | Reranker 接口 | **新增** |
| `chain/.../knowledge/OpenAICompatibleReranker.java` | vLLM reranker 实现 | **新增** |
| `chain/.../knowledge/SearchResult.java` | 搜索结果 | 可能需要新增 rebuildWithScore 变体 |
| `chain-elasticsearch/.../ElasticsearchKnowledgeStore.java` | ES 知识库 | 添加 reranker 字段和调用 |

### 方案分析

**方案 A: Reranker 作为独立组件** (推荐)
- 新增 `Reranker` 接口在 chain 核心
- `OpenAICompatibleReranker` 实现（OkHttp 调用 /v1/rerank）
- `ElasticsearchKnowledgeStore.Builder.reranker(Reranker)` 可选注入
- Pros: 与 LLM/Embedding 架构一致，松耦合，可扩展
- Cons: 需要新增 HTTP 调用（openai-java SDK 不支持 rerank API）

**方案 B: Reranker 内嵌在 ElasticsearchKnowledgeStore**
- 直接在 ES store 内写 rerank 逻辑
- Pros: 简单
- Cons: 强耦合，无法复用于其他知识库实现
