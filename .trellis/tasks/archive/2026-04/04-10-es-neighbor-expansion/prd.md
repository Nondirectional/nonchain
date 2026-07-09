# Design neighbor chunk expansion for chain-elasticsearch

## Goal

为 `chain-elasticsearch` 设计一套“命中 chunk 后按相邻 chunk 扩展上下文”的能力，使 Agent 在发现单个检索片段不完整时，能够基于 `documentId + chunkIndex` 获取前后相邻片段，并为后续实现保留清晰、稳定、可演进的 API 边界。

## What I already know

* 当前 `KnowledgeStore` 只提供统一 `search(SearchRequest)` 与增删接口，没有“按相邻 chunk 扩展”相关 API。
* 当前 `SearchRequest` 支持 `knowledgeBaseIds`、`documentIds`、`chunkIds`、`metadataFilter` 等过滤维度，但不支持 `chunkIndex` 区间、邻接窗口、扩展方向等参数。
* 当前 `DocumentChunk` 持久化了 `chunkIndex`，`SearchResult` 也会把 `chunkIndex` 返回给调用方。
* 当前 `ElasticsearchKnowledgeStore` 建索引时已经落了顶层字段 `chunk_index`。
* 当前 BM25、kNN、hybrid 三条检索路径都只是返回命中的 `SearchResult`，不会自动查询 `index-1` / `index+1`。
* 当前文档切分层支持 `chunkOverlap`，这能缓解边界截断，但不能替代检索后的动态补齐。

## Assumptions (temporary)

* 本次先设计 `chain-elasticsearch` 与上层 Agent 的协作边界，不直接把“完整性判断”硬编码到底层检索库。
* “片段是否完整”更适合作为上层 Agent / Assembler 的策略，而不是 `KnowledgeStore` 底层的通用判断逻辑。
* 本次目标是补齐“邻接 chunk 取回能力”和“可控拼接语义”，而不是重做整套 RAG orchestration。
* 用户已确认第一版采用最小可落地范围：只提供固定窗口扩展能力，不在底层库中实现循环扩展 helper。

## Open Questions

* 无

## Requirements (evolving)

* 检索命中结果必须具备可追溯到原文相邻 chunk 的基础信息，至少包括 `documentId` 与 `chunkIndex`。
* `chain-elasticsearch` 需要提供一个明确的邻接上下文读取能力，用于根据中心 chunk 获取前后相邻 chunk。
* 读取相邻 chunk 时必须限定在同一个 `documentId` 内，不能跨文档扩展。
* 读取结果必须按 `chunkIndex` 升序返回，保证调用方可以稳定拼接上下文。
* 第一版设计必须明确“中心 chunk 是否包含在返回结果中”的语义，避免上层重复去重。
* 第一版设计必须明确“超出文档边界时”的行为，应返回已有片段而非报错。
* 第一版设计必须明确“chunkIndex 缺失时”的行为，避免对历史数据产生歧义。
* 设计需要兼容当前 `search(SearchRequest)` 主路径，避免为了邻接扩展破坏现有 BM25 / kNN / hybrid 用法。
* 设计需要为后续 Agent 循环扩展场景保留足够的信息，例如是否还有前驱 / 后继 chunk。
* 第一版只提供固定窗口扩展能力，例如 `before/after/includeCenter`，不包含“循环直到完整”的流程封装。
* 该能力需要进入 `KnowledgeStore` 通用抽象，而不是只停留在 `ElasticsearchKnowledgeStore` 实现层。
* `KnowledgeStore` 中的第一版通用接口采用“单中心 chunk 扩展”原语，而不是 `chunkId` 便捷入口，也不是“对检索结果批量扩展”的高阶接口。

## Acceptance Criteria (evolving)

* [ ] 有一个明确的 API 设计，说明如何根据命中的 chunk 获取相邻 chunk。
* [ ] 有一个明确的结果模型，说明返回哪些 chunk、是否包含中心 chunk、如何排序。
* [ ] 有一个明确的边界语义，覆盖首块、尾块、缺失 `chunkIndex`、重复 chunk 等情况。
* [ ] 有一个明确的分层设计，说明哪些职责属于 `chain-elasticsearch`，哪些职责属于 Agent / 上层组装器。
* [ ] 有一个明确的实现拆分建议，说明最小可落地版本与后续增强版本。
* [ ] 有一个明确的 MVP 范围，说明第一版只覆盖固定窗口扩展。
* [ ] 有一个明确的通用抽象方案，说明该能力如何进入 `KnowledgeStore` 且不破坏现有调用语义。
* [ ] 有一个明确的 API 粒度方案，说明第一版为何选择“单中心 chunk 扩展”原语。

## Definition of Done (team quality bar)

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* 在本次设计阶段直接实现完整代码
* 让底层 `KnowledgeStore` 自己判断语义是否“完整”
* 设计与特定 LLM 强绑定的 prompt 或 Agent policy
* 处理跨文档、跨知识库的自动拼接

## Research Notes

### 当前仓库里的真实能力

* `KnowledgeStore` 只有统一检索与删除接口，没有上下文扩展接口。
* `SearchRequest` 当前没有 `chunkIndex` 范围过滤能力。
* `ElasticsearchSearchSupport` 已经识别并回填 `chunk_index`，但只用于结果返回，不参与邻接查询。
* `ElasticsearchKnowledgeStore` 的索引结构已经具备实现邻接查询所需的 `document_id + chunk_index` 基础字段。

### 设计约束

* 若把“完整性判断”放进底层库，会把偏业务、偏模型的启发式逻辑固化到通用检索层，复用性差。
* 若只给上层返回 `chunkIndex` 而不提供邻接查询 API，上层每个调用方都要自己拼 ES 查询，接口一致性差。
* 当前已有 `search(SearchRequest)` 主路径，新增能力应避免污染统一检索请求模型，除非收益明显大于复杂度。
* 用户已确认该能力应进入 `KnowledgeStore` 通用抽象，而不是先做 ES 专属扩展。
* 用户已确认第一版通用 API 采用“单中心 chunk 扩展”原语：由上层显式传入 `documentId + centerChunkIndex + before/after/includeCenter`。

### Feasible approaches here

**Approach A: 在通用抽象中新增独立的邻接上下文 API** (Recommended)

* How it works:
* 保持 `search(SearchRequest)` 不变。
* 在 `KnowledgeStore` 中新增一个专门的方法，例如 `expandContext(...)` / `getAdjacentChunks(...)`，输入中心 chunk 定位信息与窗口参数，返回有序 chunk 列表。
* 上层 Agent 拿到命中结果后，按需调用该 API 做一轮或多轮扩展。
* Pros:
* 分层清晰，不污染现有检索请求模型。
* 能成为不同存储实现共享的通用能力。
* 易于单测，边界清楚。
* 更适合给 Agent 做循环控制。
* Cons:
* 需要所有 `KnowledgeStore` 实现都补齐该能力。

**Approach B: 在 SearchRequest 中加入邻接扩展参数**

* How it works:
* 给 `SearchRequest` 增加例如 `expandBefore/expandAfter/includeCenter`。
* `search()` 在命中后自动补查相邻 chunk，并返回扩展后的结果。
* Pros:
* 调用方只调一次 `search()`。
* 对简单调用者表面上更方便。
* Cons:
* 把“召回”和“上下文组装”耦合在一起，语义变重。
* 对 topK、多命中、多文档场景会变复杂，结果排序与去重也更难讲清楚。

**Approach C: 不新增 API，只用 chunkIds/documentIds 让上层自行二次 search**

* How it works:
* 上层基于 `documentId + chunkIndex` 自己构造查询，再调用现有 store 的检索接口。
* Pros:
* 表面上改动最少。
* Cons:
* 现有 `SearchRequest` 没有 `chunkIndex` 范围过滤，实际上还得绕过公共抽象。
* 上层会感知 ES 实现细节，破坏抽象边界。

## Technical Notes

* 任务目录：`.trellis/tasks/04-10-es-neighbor-expansion`
* 已检查的核心文件：
* `chain/src/main/java/com/non/chain/knowledge/KnowledgeStore.java`
* `chain/src/main/java/com/non/chain/knowledge/SearchRequest.java`
* `chain/src/main/java/com/non/chain/knowledge/DocumentChunk.java`
* `chain/src/main/java/com/non/chain/knowledge/SearchResult.java`
* `chain-elasticsearch/src/main/java/com/non/chain/knowledge/elasticsearch/ElasticsearchKnowledgeStore.java`
* `chain-elasticsearch/src/main/java/com/non/chain/knowledge/elasticsearch/ElasticsearchSearchSupport.java`
* `chain-elasticsearch/src/main/java/com/non/chain/knowledge/elasticsearch/HybridRetriever.java`
* `docs/elasticsearch/store.md`
* `docs/knowledge/store.md`

## Technical Approach

### 分层建议

* `KnowledgeStore` 通用抽象负责：
* 暴露稳定的邻接上下文读取契约
* 约束返回顺序、边界语义与错误语义
* `chain-elasticsearch` 负责：
* 根据中心 chunk 的定位信息读取同文档相邻 chunk
* 保证边界安全、顺序稳定、结果去重
* 返回足够的结构化信息给上层继续决策
* 上层 Agent / Assembler 负责：
* 判断当前片段是否“足够完整”
* 决定向前扩展、向后扩展，还是双向扩展
* 决定最多扩展几轮、何时停止
* 决定最终如何拼接为给 LLM 的上下文

### 推荐的第一版 API 方向

推荐在 `KnowledgeStore` 中新增独立上下文扩展接口，而不是修改 `search(SearchRequest)`：

```java
ContextExpansionResponse expandContext(ContextExpansionRequest request);
```

候选请求字段：

* `documentId`
* `centerChunkIndex`
* `before`
* `after`
* `includeCenter`
* `knowledgeBaseId`（可选，用于额外范围校验）

候选返回字段：

* `List<SearchResult> chunks`
* `boolean hasPrevious`
* `boolean hasNext`
* `Integer startChunkIndex`
* `Integer endChunkIndex`

### 语义建议

* 第一版使用 `documentId + centerChunkIndex` 作为中心定位主键，不在通用抽象中引入 `centerChunkId` 便捷入口。
* 返回结果按 `chunkIndex ASC` 排序。
* `includeCenter=true` 作为默认值，便于上层一次拿到完整窗口。
* 超出边界时直接截断，例如首块向前扩展只返回可用片段。
* 若中心 chunk 缺失 `chunkIndex`，第一版建议返回明确错误，而不是静默降级。
* 第一版只支持“固定窗口扩展”；“循环直到完整”由上层多次调用实现。
* 第一版只处理单个中心 chunk，不在底层一次性接收 `List<SearchResult>` 做批量扩展。

## Decision (ADR-lite)

**Context**: 当前命中结果虽然带有 `chunkIndex`，但库内没有稳定、统一的相邻 chunk 读取能力；而“是否完整”的判断又不适合放到底层检索库。

**Decision**: 用户已确认该能力需要进入 `KnowledgeStore` 通用抽象；并确认第一版采用“单中心 chunk 扩展”原语。在不修改 `search(SearchRequest)` 的前提下，新增独立的邻接上下文扩展 API，由具体实现负责相邻 chunk 查询和有序返回，由上层 Agent 负责多轮扩展与停止判断。

**Consequences**:

* 优点是分层清晰、接口稳定、便于测试和后续演进。
* 代价是调用方需要两段式调用：先检索，再按需扩展上下文。
* 后续若需要，也可以在 Agent 层封装 `expandUntilComplete(...)` 之类更高阶能力，而不用污染底层 `KnowledgeStore` 抽象。
