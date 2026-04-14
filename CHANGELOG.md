# 更新日志

所有重要更改均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.6.0] - 2026-04-14

### 新增

- `ChainCallback` 统一回调接口：覆盖 LLM、Tool、Retrieval、Graph 四大组件的 Start/Complete/Error 生命周期事件
  - 所有方法均有 default no-op 实现，用户可选择性实现感兴趣的回调
- 事件模型（`callback/event/` 包）：`LlmStartEvent`、`LlmCompleteEvent`、`LlmErrorEvent`、`ToolStartEvent`、`ToolCompleteEvent`、`ToolErrorEvent`、`RetrievalStartEvent`、`RetrievalCompleteEvent`、`RetrievalErrorEvent`
  - Complete 事件包含耗时（`latencyMs`），LLM Complete 事件包含 `TokenUsage`
- `ChainTrace`：基于 ThreadLocal 的 traceId 管理，Agent 自动为同一次迭代的 LLM + Tool 调用关联相同 traceId
- `CompositeCallback`：多订阅者组合回调，每个回调异常独立捕获，不中断其他回调和主流程
- `ChainContext`：共享上下文，持有 callback 引用，可注入到各组件
- 各组件 Builder 同时支持 `.callback(ChainCallback)` 和 `.chainContext(ChainContext)` 两种注册方式
- `ChainCallbackTest`：10 个集成测试覆盖 Agent 回调、Graph 桥接、多订阅者、异常隔离、traceId 关联等场景

### 变更

- `Agent` 移除 `logger(Consumer<String>)`，由 `ChainCallback` 替代
- `Graph.Builder` 新增 `callback()` 和 `chainContext()` 方法，Graph 事件同时通过 ChainCallback 发出
- `DashscopeLLM` 新增带 `ChainCallback` 的构造函数和 `fromContext()` 静态工厂方法
- `ToolRegistry` 新增带 `ChainCallback` 和 `ChainContext` 的构造函数
- `ElasticsearchKnowledgeStore.Builder` 新增 `callback()` 和 `chainContext()` 方法
- `AgentLoopExample` 更新为使用 `ChainCallback` 替代 `logger`

### 破坏性变更

- `Agent.logger(Consumer<String>)` 已移除，需迁移到 `Agent.builder().callback(ChainCallback)`

## [0.5.5] - 2026-04-13

### 新增

- `LlmDocumentSplitter`：基于 LLM 的语义切分器，单次 LLM 调用完成清洗与切分，输出 JSON 数组格式的语义完整 chunk
  - Hybrid 架构：先用规则清洗管线处理确定性噪音，再由 LLM 完成语义清洗和切分
  - 预分段机制：将文档按 `segmentSize` 分段后逐段发送 LLM，支持大文档处理
  - 原子元素透传：TABLE、CODE_BLOCK、IMAGE 绕过 LLM 通过 `SplitterSupport` 直接输出
  - JSON 解析容错：`extractJsonArray()` 处理 LLM 在 JSON 前后添加额外文字的情况
  - 重试与降级：最多 2 次重试，全部失败后自动降级到 `RecursiveCharacterSplitter`
  - 可配置参数：`targetChunkSize`、`segmentSize`、`promptTemplate`（中文默认 prompt）
- `LlmChunkResult` 值对象：对应 LLM JSON 输出的 `content` + `title` 字段
- `LlmDocumentSplitterExample` 示例：演示纯文本切分、含原子元素切分、自定义 prompt 三种场景
- `LlmDocumentSplitterTest`：13 个单元测试覆盖正常切分、原子元素透传、JSON 容错、重试降级、Builder 校验等

## [0.5.4] - 2026-04-13

### 新增

- `DashscopeLLM` 支持 `temperature`、`topP`、`topK` 采样参数，通过链式 setter 配置
  - `temperature(Double)`：采样温度，控制生成文本多样性，范围 [0, 2)
  - `topP(Double)`：核采样概率阈值，范围 (0, 1.0]
  - `topK(Integer)`：候选 Token 数量，非 OpenAI 标准参数，通过 additional body property 传递

## [0.5.3] - 2026-04-10

### 新增

- 知识库上下文扩展：`KnowledgeStore.expandContext(ContextExpansionRequest)` 接口方法，基于中心 chunk 向前后扩展邻居 chunk
- `ContextExpansionRequest` 值对象：指定 documentId、centerChunkIndex、before/after 窗口大小，支持 includeCenter 和 knowledgeBaseId 过滤
- `ContextExpansionResponse` 值对象：返回扩展后的 chunk 列表，携带 hasPrevious/hasNext 分页标识和 startChunkIndex/endChunkIndex 范围
- `ElasticsearchKnowledgeStore` 上下文扩展实现：基于 chunkIndex 范围查询，自动校验中心 chunk 存在性
- `ElasticsearchContextExpansionExample` 示例：演示上下文扩展用法
- `ContextExpansionRequestTest` / `ContextExpansionResponseTest` 单元测试

## [0.5.2] - 2026-04-09

### 新增

- Graph 错误事件：`NODE_ERROR` 和 `GRAPH_ERROR` 事件类型，节点执行或边路由异常时通过事件回调通知
- `GraphEvent.error()` 字段：NODE_ERROR / GRAPH_ERROR 事件携带异常消息
- `GraphEvent.nodeError()` / `GraphEvent.graphError()` 工厂方法
- 异常时保证 `GRAPH_END` 始终发出，`executedNodes` 仅包含成功执行的节点
- `GraphTest`：5 个错误场景单元测试（节点异常、边路由异常、异常传播、无回调异常、节点未找到）

## [0.5.1] - 2026-04-09

### 变更

- `RapidOCREngine` 改用 `uv run python` 调用 RapidOCR，适配无直接 python3 的环境

## [0.5.0] - 2026-04-08

### 新增

- Graph 事件回调：`GraphEvent` 事件类（GRAPH_START / NODE_START / NODE_END / GRAPH_END），通过 `Graph.Builder.onEvent(Consumer<GraphEvent>)` 设置，支持监听图的执行过程
- `GraphTest`：4 个单元测试覆盖事件回调

## [0.4.0] - 2026-04-07

### 新增

- 统一检索请求模型 `SearchRequest`：支持 BM25 / kNN / hybrid 自动降级（仅文本→BM25，仅向量→kNN，两者→hybrid）
- `RetrievalResponse` / `RetrievalDebugInfo`：默认精简返回，`debug` / `trace` 开关按需返回诊断信息
- `RetrievalMode` 枚举（BM25 / KNN / HYBRID）和 `FusionStrategy` 枚举（RRF / LINEAR）
- `MetadataFilter` 树形过滤模型：支持 AND / OR / NOT 逻辑组合及 EQ / NE / GT / GTE / LT / LTE / IN / EXISTS 操作符
- `ElasticsearchSearchSupport` 包级共享工具：统一过滤构建、BM25 查询、结果映射、诊断信息构建
- 客户端侧 RRF 融合：不依赖 ES 许可证，兼容所有版本
- 客户端侧 Linear 融合（min-max 归一化 + 加权求和）
- 140 个单元测试覆盖 `SearchRequest` / `RetrievalResponse` / `RetrievalDebugInfo` / `MetadataFilter` / `FusionStrategy` / `RetrievalMode`

### 变更

- 知识检索能力收敛为 Elasticsearch 单一路线，移除 `chain-pgvector` 模块与 `PgvectorKnowledgeStore`
- `KnowledgeStore.search(SearchRequest)` 返回 `RetrievalResponse`
- `KeywordRetriever.search(...)` 改为接收 `SearchRequest`
- `HybridRetriever` 改为客户端侧融合（RRF / Linear），不再依赖 ES 原生 retriever
- BM25 路径固定检索 `content` 字段，分析器固定 `ik_smart`
- 统一过滤入口（knowledgeBaseIds / documentIds / chunkIds / metadataFilter）在 BM25、kNN、hybrid 三条路径保持一致
- 默认调参：size=10，rankWindowSize=max(50, size×5)，numCandidates=max(100, rankWindowSize×2)
- 移除统一 `minScore`，避免跨检索路径分数语义歧义
- README、架构文档、安装文档、示例文档全部收敛为 ES-only 叙事
- 新增 Elasticsearch-only 迁移指南（`docs/migrations/elasticsearch-only.md`）

### 移除

- `chain-pgvector` 模块、`PgvectorKnowledgeStore`、`PgvectorExample`
- `docs/pgvector/` 文档目录
- 顶层 `pom.xml` 中 `chain-pgvector` 模块声明

## [0.3.0] - 2026-04-07

### 新增

- 流式输出支持：`LLM` 接口新增 `streamChat()` 系列方法，接受 `Consumer<ChatChunk>` 回调
- `ChatChunk` 增量块模型：支持文本、思考内容、工具调用的流式片段
- `DashscopeLLM` 流式实现：基于 `createStreaming()` API，含工具调用按 index 累积
- `StreamingChatExample` 示例：基础流式、思考模式、工具调用流式
- Agent 循环：`Agent` 类封装 LLM + 工具自动调用循环，Builder 模式
- `AgentException`：超出最大迭代次数时抛出
- 工具执行失败时错误信息传回 LLM 自我修复，不中断循环
- Agent 支持 `Consumer<String>` 日志回调，可观察执行过程
- `AgentTest`：8 个单元测试覆盖核心场景
- `AgentLoopExample`：旅行助手示例，演示多工具多步骤推理

## [0.2.0] - 2026-04-03

### 新增

- 多模态图片输入支持（`ImageUrlPart`、`TextPart`），配合视觉模型进行图片理解
- `ImageInputExample` 示例，演示多模态图片输入用法
- `ParsedDocument` 和 `TextChunk` 单元测试

## [0.1.0] - 2026-04-02

### 新增

- 转为多模块 Maven 项目结构（chain、chain-document、chain-pgvector、chain-elasticsearch、chain-example）
- `chain` 模块：`EmbeddingModel` 接口及 DashScope 实现
- `chain-knowledge` 模块：知识库核心接口与值对象、pgvector 和 Elasticsearch 实现
- `chain-document` 模块：文档解析核心抽象（`DocumentReader`、`Document`、`DocumentCleaner`）
- 5 种文档 Reader 实现：TXT、Markdown、PDF、DOCX、HTML
- 文档清洁管线，包含 8 种 `Cleanable` 实现
- `OcrEngine` 接口及扫描版 PDF 检测，支持 RapidOCR 和 Tesseract 两种引擎
- 知识库示例和文档 Reader/Cleaner 示例

### 修复

- 文档 Reader 示例改用 classpath 资源加载 PDF 和 DOCX 文件

## [0.0.3] - 2026-03-31

### 新增

- 支持结构化输出 `json_object` 响应格式，新增 `OutputFormat` 枚举（`TEXT`、`JSON_OBJECT`）
- `LLM` 接口新增 `OutputFormat` 参数，原有方法保持向后兼容
- `DashscopeLLM` 实现 `json_object` 响应格式
- 添加 `StructuredOutputExample` 示例，演示 JSON 对象输出用法
- 添加 `json_object` 与工具调用同时使用的校验，防止冲突

### 变更

- `DashscopeLLM` 构造函数中 `maxCompletionTokens` 改为可选参数

## [0.0.2] - 2026-03-31

### 重构

- 将 `LLM` 接口从 `ChatResult` 内部类提取为顶层接口 `provider.LLM`
- 更新所有示例代码中 `ChatResult.LLM` 的引用

### 其他

- `.gitignore` 添加 `.env` 排除规则

## [0.0.1] - 2026-03-31

### 新增

- 初始化 nonchain 库，包含 LLM Provider 抽象、工具调用框架及基于图的工作流引擎
- 支持 Dashscope（阿里云）作为 LLM 提供商
- 支持函数调用（单参数、多参数、原始请求）
- 支持工作流编排（条件路由、链式执行）
- 提供完整的示例代码
