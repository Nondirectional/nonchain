# Elasticsearch 单独承担检索能力

## Goal

将 nonchain 的知识检索能力收敛为由 Elasticsearch 单独承担向量检索、全文检索和混合检索，重新定义 PostgreSQL 在该能力域中的角色，避免继续把 pgvector 作为一等向量检索后端长期维护。

## What I already know

* 当前仓库是多模块结构，顶层 `pom.xml` 同时包含 `chain-pgvector` 和 `chain-elasticsearch`。
* `KnowledgeStore` 抽象已经存在，`PgvectorKnowledgeStore` 与 `ElasticsearchKnowledgeStore` 都是其实现。
* `chain-elasticsearch` 当前已经同时覆盖三类能力：向量检索（`ElasticsearchKnowledgeStore`）、BM25（`ElasticsearchBM25Retriever`）、混合检索（`HybridRetriever`）。
* `chain-pgvector` 当前只承担向量存储/检索，不承担 BM25 或混合检索。
* 对外文档和 README 仍把 PgVector 与 Elasticsearch 并列描述为两种正式向量后端。
* `chain-example` 仍直接依赖 `chain-pgvector`，并包含 `PgvectorExample`、`GraphKnowledgeExample` 等示例。
* 架构文档已经写明“将 PgVector 切换为 Elasticsearch 只需替换实现”，说明抽象层本身支持这次方向调整。
* 当前 `HybridRetriever` 的向量路径接受 `knowledgeBaseIds/documentIds` 过滤，但 BM25 路径通过 `KeywordRetriever.search(String, int)` 调用，不携带这些过滤条件。
* 当前 `KeywordRetriever` 接口只支持 `search(queryText, topK)`，这会限制 ES-only 方案下混合检索的一致性。

## Assumptions (temporary)

* 你的目标不是简单新增一个 ES 优先选项，而是要把产品主路径明确收敛到 Elasticsearch。
* PostgreSQL 后续即使保留，也不再承担向量索引和向量检索职责。
* 这项改动会影响公开 API 叙事、示例、文档、模块依赖和部分接口设计，而不仅是内部实现替换。

## Open Questions

* 无

## Requirements (evolving)

* 明确 nonchain 在知识检索能力上的官方主路径：Elasticsearch 同时承担向量检索、BM25 和混合检索。
* 从主仓库发布内容中直接移除 `chain-pgvector` 模块，不再作为当前版本支持的检索实现。
* 明确 PostgreSQL 在这次重构后的角色边界，至少不再承担向量索引和向量检索职责。
* 重设计检索接口，使关键词检索与向量检索共享统一的过滤模型和请求语义。
* 重设计 `HybridRetriever`，使混合检索围绕统一请求模型工作，而不是继续分裂为“queryEmbedding + queryText + 部分过滤参数”。
* 混合检索第一版默认采用 Elasticsearch 原生 `RRF` 融合策略，同时在请求模型和实现上预留 `Linear` 融合能力。
* 搜索结果默认保持精简，仅在显式开启 `debug/trace` 时附带混合检索诊断信息。
* 统一检索请求采用自动降级语义：仅有 `queryText` 时走 BM25，仅有 `queryEmbedding` 时走向量检索，两者同时存在时走混合检索。
* 第一版采用保守默认调参：`size=10`，`rankWindowSize=max(50, size*5)`，`numCandidates=max(100, rankWindowSize*2)`。
* 第一版统一请求中移除 `minScore`，避免在 BM25 与 RRF 混合场景暴露误导性的分数过滤语义。
* 第一版保留 `metadataFilter` 作为统一过滤入口，并要求其稳定映射为 Elasticsearch filter query。
* 第一版 BM25 路径仅检索 `content` 字段，不扩展到 metadata 文本字段，也不暴露可配置字段集。
* 第一版分析器固定为 `ik_smart`，不额外开放 analyzer 配置面。
* 将这次改动按 breaking change 处理，给出迁移说明或版本策略。
* 明确 README、架构文档、安装文档、示例代码和模块依赖如何同步收敛。

## Acceptance Criteria (evolving)

* [ ] 有一个明确的目标架构说明 Elasticsearch 如何单独覆盖向量检索、全文检索和混合检索。
* [ ] `chain-pgvector` 已从主构建和主文档叙事中移除，并说明兼容性影响。
* [ ] 有一个明确的接口层方案，使向量检索、关键词检索和混合检索在过滤语义上保持一致。
* [ ] 有一个明确的新请求模型或接口改造方案，替代当前过窄的 `KeywordRetriever.search(String, int)`。
* [ ] 有一个明确的融合策略方案：默认 `RRF`，并预留 `Linear` 的请求与实现扩展点。
* [ ] 有一个明确的结果观测方案：默认精简返回，`debug/trace` 模式下可获取混合检索诊断信息。
* [ ] 有一个明确的降级语义方案：统一请求可自动退化为纯 BM25 或纯向量检索。
* [ ] 有一个明确的默认调参方案，替代当前 `topK * 3` 的硬编码候选窗口。
* [ ] 有一个明确的分数语义方案：第一版不再对外暴露统一 `minScore` 过滤。
* [ ] 有一个明确的过滤语义方案：`metadataFilter` 在 BM25、kNN、hybrid 三种路径下都保持一致语义。
* [ ] 有一个明确的 BM25 查询方案：第一版 lexical 路径仅针对 `content` 字段。
* [ ] 有一个明确的 analyzer 方案：第一版统一使用 `ik_smart`。
* [ ] 有一个按小 PR 拆分的实施计划，覆盖代码、示例和文档。

## Definition of Done (team quality bar)

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* 讨论 Elasticsearch 集群部署、索引生命周期管理或容量规划细节
* 引入新的第三种向量数据库
* 在本次规划阶段直接实现代码改动

## Research Notes

### 当前仓库里的真实结构

* 抽象层已经具备切换基础：`KnowledgeStore` 屏蔽了底层向量实现差异。
* Elasticsearch 实现已经具备单独完成检索闭环的能力：
* `ElasticsearchKnowledgeStore` 负责 `dense_vector + kNN`
* `ElasticsearchBM25Retriever` 负责全文检索
* `HybridRetriever` 负责 RRF 融合
* PgVector 实现是单独模块，且没有与 ES 模块形成强耦合，说明从代码组织上可以被降级或移除。

### 当前仓库里的主要约束

* 对外叙事仍是“双后端并行”，所以这次调整本质上是产品边界重定义，不只是内部重构。
* `chain-example` 同时依赖 `chain-pgvector` 和 `chain-elasticsearch`，说明示例模块会直接受影响。
* `README.md`、`docs/overview/*`、`docs/pgvector/*`、`docs/knowledge/store.md`、`docs/examples/overview.md` 都有较多 PgVector 内容。
* 测试目前主要集中在 `chain` 和 `chain-document`，检索相关模块缺少明显的测试覆盖，这会增加重构时的回归风险。
* `KeywordRetriever` 只有文本查询接口，导致混合检索的 BM25 路径天然缺少范围过滤能力；如果 ES 成为唯一检索后端，这会成为核心一致性问题。

### Feasible approaches here

**Approach A: ES 主路径 + PgVector 弃用保留** (Recommended)

* How it works:
* 将 Elasticsearch 定义为官方唯一推荐检索后端。
* 保留 `chain-pgvector` 一个过渡版本，但标记为 deprecated/legacy，不再继续扩展。
* 优先修正 `KeywordRetriever` / `HybridRetriever` 过滤语义，让 ES-only 方案在接口层完整闭环。
* 更新 README、docs、examples，把所有主路径示例切到 ES。
* Pros:
* 迁移风险最低，对已有用户更温和。
* 保留一个观察窗口，可以验证 ES-only 设计是否真的覆盖全部场景。
* 与现有抽象层最兼容。
* Cons:
* 仓库里会暂时残留双实现，产品边界不会立刻“绝对干净”。
* 需要处理 deprecated 文档和兼容承诺。

**Approach B: 直接移除 PgVector 主模块**

* How it works:
* 从顶层模块、示例和文档中移除 `chain-pgvector`，主仓库只保留 ES 检索实现。
* 如有需要，将 pgvector 迁移到实验仓库/历史分支，不再作为当前发布内容。
* 同步调整 README、架构图、安装说明和版本说明。
* Pros:
* 产品定位最清晰，维护面最小。
* 不再需要为双后端叙事付出文档和兼容成本。
* Cons:
* 对已有用户是明确 breaking change。
* 需要较强的版本策略和迁移说明。
* 如果 ES-only 后续发现接口缺口，回旋空间更小。

**Approach C: 文档层 ES-first，代码层暂不动 PgVector**

* How it works:
* 先把官方推荐改成 Elasticsearch，保留 `chain-pgvector` 模块和示例，但降为“兼容实现”。
* 第一阶段只修正文档、示例入口和路线图，后续再决定是否删除 pgvector。
* Pros:
* 成本最低，适合先验证方向。
* 兼容性影响最小。
* Cons:
* 技术债保留最多。
* 仓库与文档容易出现“名义收敛、实际未收敛”。

## Technical Notes

* 任务目录：`.trellis/tasks/04-07-elasticsearch-only-retrieval`
* 已检查的核心文件：
* `pom.xml`
* `chain/src/main/java/com/non/chain/knowledge/KnowledgeStore.java`
* `chain/src/main/java/com/non/chain/knowledge/SearchRequest.java`
* `chain/src/main/java/com/non/chain/knowledge/KeywordRetriever.java`
* `chain-pgvector/src/main/java/com/non/chain/knowledge/pgvector/PgvectorKnowledgeStore.java`
* `chain-elasticsearch/src/main/java/com/non/chain/knowledge/elasticsearch/ElasticsearchKnowledgeStore.java`
* `chain-elasticsearch/src/main/java/com/non/chain/knowledge/elasticsearch/ElasticsearchBM25Retriever.java`
* `chain-elasticsearch/src/main/java/com/non/chain/knowledge/elasticsearch/HybridRetriever.java`
* `README.md`
* `docs/overview/architecture.md`
* `docs/overview/introduction.md`
* `docs/elasticsearch/hybrid-retrieval.md`
* `docs/elasticsearch/store.md`
* 关键设计缺口：
* `HybridRetriever.search(...)` 只把范围过滤传给向量路径，没有传给 BM25 路径。
* `KeywordRetriever` 当前接口过窄，若 ES 成为唯一检索主路径，建议基于统一请求模型重做，而不是继续停留在 `search(queryText, topK)`。
* 当前 `chain-elasticsearch` 依赖 `elasticsearch-java 8.13.4`，低于 Elasticsearch retriever 框架引入版本。
* Elasticsearch 官方文档显示：retriever 抽象在 `8.14.0` 引入，在 `8.16.0` GA；`rrf` 和 `linear` retriever 都支持顶层 `filter` 统一下推到各子检索器。
* 用户已确认目标环境为 Elasticsearch `9.3.2`，这是一个晚于 retriever GA 的版本，因此本次可以直接围绕 Elasticsearch 原生混合检索设计，而不必继续为 `8.13.x` 兼容面保守。
* 用户已确认第一版默认融合策略为 `RRF`，但要在 API 和实现上预留 `Linear` 融合能力。
* 用户已确认结果模型采用“默认精简 + 可选诊断”模式，避免常规调用被调试字段污染。
* 用户已确认统一请求采用自动降级语义，而不是强制所有查询都提供文本和向量。
* 用户已确认第一版默认调参采用保守策略：`size=10`，`rankWindowSize=max(50, size*5)`，`numCandidates=max(100, rankWindowSize*2)`。
* 用户已确认第一版统一请求移除 `minScore`，避免错误的跨检索路径分数语义。
* 用户已确认第一版保留 `metadataFilter`，而不是只保留 ID 过滤。
* 用户已确认第一版 BM25 路径只检索 `content` 字段。
* 用户已确认 analyzer 统一使用 `ik_smart`。
* 用户将诊断字段选择权交给实现方案，因此由本 PRD 直接收敛为稳定、克制的 debug/trace 字段集合。

## Technical Approach

### 目标架构

* Elasticsearch 成为唯一检索后端：
* `ElasticsearchKnowledgeStore` 负责向量写入、向量检索、删除和过滤
* `ElasticsearchBM25Retriever` 负责全文检索
* 混合检索优先下沉到 Elasticsearch 内部，通过原生 `retriever` 框架完成融合和过滤统一下推
* 客户端如保留 `HybridRetriever`，其角色应降级为兼容包装层或请求构造器，而不是主要融合实现
* PostgreSQL 不再承担向量检索职责，`chain-pgvector` 从主仓库移除
* 第一版默认使用 Elasticsearch 原生 `rrf` retriever，后续可在相同请求模型下扩展到 `linear` retriever
* 搜索结果默认只暴露稳定字段，诊断字段通过显式调试开关返回
* 统一检索请求支持自动降级：
* 仅 `queryText` -> BM25
* 仅 `queryEmbedding` -> kNN
* 同时提供 -> hybrid
* 两者都为空 -> 非法请求
* 第一版默认调参：
* `size=10`
* `rankWindowSize=max(50, size*5)`
* `numCandidates=max(100, rankWindowSize*2)`
* 统一过滤入口继续保留：
* `knowledgeBaseIds`
* `documentIds`
* `chunkIds`
* `metadataFilter`
* 第一版 BM25 只对 `content` 字段执行 lexical 检索
* 第一版分析器固定为 `ik_smart`

### 接口收敛方向

**方向 1：引入统一检索请求模型** (Recommended)

* 新增一个统一请求对象，例如 `RetrievalRequest` 或 `HybridSearchRequest`
* 该请求对象至少包含：
* `queryText`
* `queryEmbedding`
* `topK`
* `knowledgeBaseIds`
* `documentIds`
* `chunkIds`
* `metadataFilter`
* 各检索器根据自身能力消费同一请求对象：
* 向量路径读取 `queryEmbedding`
* BM25 路径读取 `queryText`
* 混合路径同时读取二者并共享过滤条件

**方向 2：保留 `SearchRequest`，再补一个 `KeywordSearchRequest`**

* 优点是改动更局部
* 但仍然会留下两个并行请求模型，长期不如统一请求模型干净

### 推荐接口形态

* `KnowledgeStore` 继续保留 `search(SearchRequest)` 或升级到新统一请求模型
* `KeywordRetriever` 从 `search(String queryText, int topK)` 升级为接收请求对象
* 混合检索入口升级为接收请求对象，并将其映射为 Elasticsearch 原生 `retriever` 请求
* 请求模型中应显式携带 `fusionStrategy`，默认 `RRF`，并为 `Linear` 预留权重参数
* 请求模型中应显式携带 `debug` 或 `trace` 开关，用于决定是否返回分路分数、排名、命中 retriever 等诊断信息
* 请求模型需支持 `queryText` 和 `queryEmbedding` 任一独立存在，并在执行层按能力自动选择 BM25、kNN 或 hybrid 路径
* 请求模型中应显式携带 `size`、`rankWindowSize`、`numCandidates`，但在缺省时提供上述保守默认值
* 第一版请求模型不再包含统一 `minScore`
* 请求模型继续包含 `metadataFilter`，并要求统一映射到 Elasticsearch `filter` 语义，而不是 query-time scoring 语义
* 第一版不额外引入 BM25 字段集配置，lexical 路径固定查询 `content`
* 第一版不额外引入 analyzer 配置，lexical 路径统一使用 `ik_smart`
* 若为了迁移平滑，可短期保留旧方法并标记 deprecated，但既然本次已经是 breaking change，也可以直接替换

### Debug / Trace 设计

* 为避免污染默认结果模型，建议引入顶层响应对象，例如 `RetrievalResponse`
* 默认返回：
* `results`
* 仅稳定字段，不附带调试元数据
* `debug=true` 时返回顶层 `debugInfo`：
* `mode`：`bm25` / `knn` / `hybrid`
* `fusionStrategy`：`rrf` / `linear`
* `analyzer`：第一版固定 `ik_smart`
* `size`
* `rankWindowSize`
* `numCandidates`
* `filtersApplied`：是否使用了 `knowledgeBaseIds` / `documentIds` / `chunkIds` / `metadataFilter`
* `trace=true` 时在 `debugInfo` 基础上追加：
* `profileIncluded`：是否附带 Elasticsearch profiling
* `tookMs`
* `matchedRetrievers`：本次请求实际启用的 retriever，例如 `standard`、`knn`、`rrf`
* 第一版不把 `vectorRank`、`keywordRank`、原始 explain/profile 明细作为稳定公共字段
* 如需深度排障，可在 `trace` 模式下额外透传原始 profile 片段，但应视为诊断载荷，不纳入稳定 API 承诺

### 模块与文档改造范围

* 删除顶层 `pom.xml` 中的 `chain-pgvector`
* 删除 `chain-pgvector/` 模块
* 删除 `chain-example` 对 `chain-pgvector` 的依赖及对应示例
* 将 README 和 docs 中所有主路径示例收敛到 Elasticsearch
* 改写架构图和模块说明，移除 PgVector 作为官方支持实现
* 增加 breaking change / migration notes，说明从 pgvector 迁移到 Elasticsearch 的影响

## Decision (ADR-lite)

**Context**: 仓库当前同时维护 `chain-pgvector` 和 `chain-elasticsearch` 两条检索实现路线，但 Elasticsearch 已经具备单独覆盖向量、全文和混合检索的能力。继续并行维护两条主路径会放大文档、示例和长期维护成本。

**Decision**:
* `chain-pgvector` 不走弃用过渡，直接从主仓库发布内容中移除，知识检索能力收敛为 Elasticsearch 单一路线。
* 不仅移除模块，也同步重设计检索接口，统一向量检索、关键词检索和混合检索的请求语义。
* 混合检索优先下沉到 Elasticsearch 内部执行，不再以客户端侧 RRF 融合为主实现。
* 第一版默认采用 Elasticsearch 原生 `RRF` 融合，同时在 API 与实现中预留 `Linear` 扩展点。
* 结果模型默认保持精简，诊断信息按需返回。
* 统一检索请求支持自动降级，以减少调用方的心智负担。
* 第一版默认参数将偏向稳妥召回，而不是极限低延迟。
* 第一版不再提供统一 `minScore`，避免对分数可解释性作出错误承诺。
* 第一版继续保留 metadata 过滤能力，以维持现有 KnowledgeStore 的重要能力边界。
* 第一版有意约束 lexical 检索范围，只在 `content` 字段上做 BM25，避免把 metadata 文本化检索复杂度提前引入。
* 第一版有意固定 `ik_smart`，避免在能力收敛阶段引入额外分析器矩阵。
* 第一版诊断信息以顶层响应元数据为主，而不是把不稳定的 ES 内部细节固化进每条命中的公共结构。

**Consequences**:
* 这是明确的 breaking change，需要在版本和迁移说明中体现。
* README、安装、架构图、示例和示例依赖都要同步改写。
* 先前被双后端掩盖的 ES-only 接口缺口，尤其是混合检索过滤语义不一致，需要在本次一起解决。
* 这次不是“模块删减”，而是一次面向产品边界的 API 收敛。
* `chain-elasticsearch` 的依赖和封装需要随之升级，以支持 Elasticsearch 原生 retriever 请求模型。
* 相关 Java API 不能把融合策略硬编码在类名或调用路径里，否则后续切到 `Linear` 会再次引发 breaking change。
* 相关 Java API 也不能把调试字段混入默认结果模型，否则会污染正常使用场景。
* 执行层需要清晰区分“自动降级”与“非法空请求”，避免接口行为含糊。
* 当前 `topK * 3` 的候选策略需要被明确替换为更可解释的 `size/rankWindowSize/numCandidates` 三元参数模型。
* `minScore` 若未来需要回归，应按“不同检索策略拥有不同分数语义”的前提重新设计，而不是简单恢复旧字段。
* `metadataFilter` 必须映射到 Elasticsearch 的 `filter` 子句，避免把过滤条件混入评分逻辑。
* `trace` 模式可以利用 Elasticsearch `rrf` 对 profiling 的支持，但 profiling 结果应被视为诊断载荷而非稳定契约。

## Implementation Plan (small PRs)

* PR1: 检索接口重构
* 定义统一请求模型
* 升级 `KeywordRetriever`
* 升级 `HybridRetriever`
* 补齐过滤语义的一致性
* 增加核心单元测试
* PR2: Elasticsearch 实现收口
* 适配 `ElasticsearchBM25Retriever` 和 `ElasticsearchKnowledgeStore`
* 清理 ES-only 路线上的遗留 API
* 校验 examples/调用点编译通过
* PR3: 移除 `chain-pgvector`
* 从顶层 `pom.xml`、`chain-example` 和源码树删除模块与依赖
* 删除 PgVector 示例与相关引用
* PR4: 文档与迁移说明
* 更新 README、overview、knowledge、examples 文档
* 增加 breaking change / migration notes
