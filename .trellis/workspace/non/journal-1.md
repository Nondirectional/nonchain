# Journal - non (Part 1)

> AI development session journal
> Started: 2026-03-31

---



## Session 1: Bootstrap development guidelines

**Date**: 2026-03-31
**Task**: Bootstrap development guidelines

### Summary

(Add summary)

### Main Changes

## Work Done

| Item | Description |
|------|-------------|
| Backend Guidelines | Filled 5 spec files based on actual codebase analysis |
| .gitignore | Added .claude/, .cursor/, .trellis/, AGENTS.md |
| Git Tag | Created v0.0.1 |

## Guidelines Filled

- directory-structure.md — package layout, naming conventions, module organization
- database-guidelines.md — N/A (no database), State management notes
- error-handling.md — standard Java exceptions, fail-fast patterns
- logging-guidelines.md — no framework, println in examples only
- quality-guidelines.md — immutable objects, builder pattern, forbidden patterns
- index.md — updated status, added pre-development checklist


### Git Commits

| Hash | Message |
|------|---------|
| `8c994eb` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: 重做提交与Tag并完成DashScope环境变量改造

**Date**: 2026-03-31
**Task**: 重做提交与Tag并完成DashScope环境变量改造

### Summary

将DashscopeLLM改为支持显式apiKey优先、环境变量DASHSCOPE_API_KEY兜底；替换示例中硬编码key；按要求删除并重建v0.0.1并重装Maven依赖。

### Main Changes



### Git Commits

| Hash | Message |
|------|---------|
| `36bd0bd` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: Refactor LLM interface usage

**Date**: 2026-03-31
**Task**: Refactor LLM interface usage

### Summary

Removed ChatResult.LLM inner interface, switched DashscopeLLM and all examples to provider.LLM, and verified build with Maven tests.

### Main Changes



### Git Commits

| Hash | Message |
|------|---------|
| `b01e8fa` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: 支持LLM结构化输出json_object

**Date**: 2026-03-31
**Task**: 支持LLM结构化输出json_object

### Summary

将结构化输出能力上提到LLM抽象层，新增OutputFormat并在DashscopeLLM实现json_object response_format，同时更新示例与编译验证。

### Main Changes



### Git Commits

| Hash | Message |
|------|---------|
| `1184d49` | (see git log) |
| `ae5b7d9` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: Knowledge Base ES 单索引重构与错误处理

**Date**: 2026-04-01
**Task**: Knowledge Base ES 单索引重构与错误处理

### Summary

(Add summary)

### Main Changes

| 改动 | 说明 |
|------|------|
| ElasticsearchKnowledgeStore 重构 | 从多索引模式改为单索引模式，indexPrefix -> indexName，kbId 降为过滤字段 |
| createBM25Retriever 工厂方法 | 新增工厂方法，自动对齐索引名和 analyzer，消除手动配置耦合 |
| index_not_found 处理 | BM25Retriever 和 KnowledgeStore 的 search() 优雅处理索引不存在，返回空结果 |
| 批量写入错误详情 | BulkResponse 错误时输出每个失败文档的具体错误信息 |
| Example 修复 | ElasticsearchHybridExample 使用 store.createBM25Retriever()，取消注释写入逻辑 |

**修改文件**:
- `chain-elasticsearch/src/.../ElasticsearchKnowledgeStore.java`
- `chain-elasticsearch/src/.../ElasticsearchBM25Retriever.java`
- `chain-example/src/.../ElasticsearchHybridExample.java`


### Git Commits

| Hash | Message |
|------|---------|
| `6db900e` | (see git log) |
| `d2999d5` | (see git log) |
| `bcc45eb` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 6: Document Parsing & Extraction Module

**Date**: 2026-04-01
**Task**: Document Parsing & Extraction Module

### Summary

(Add summary)

### Main Changes

## Summary

为 nonchain 库新增文档解析与提取模块，补全 RAG 链路上游（原始文档 → 结构化文本）。

| 模块 | 内容 |
|------|------|
| 核心抽象 (chain) | DocumentReader 接口、ParsedDocument、DocumentElement 层次（Text/Heading/Image/Table/CodeBlock）、DocumentSource、DocumentReaders 注册器 |
| Reader 实现 (chain-document) | TXT、Markdown (commonmark)、PDF (PDFBox)、DOCX (POI, 含图片提取)、HTML (jsoup) |
| 示例 (chain-example) | 5 个 Reader 示例 + 3 个样本资源文件 |

**设计决策**:
- 输出模型：扁平元素序列 + 类型标记（方向 C），每个元素带 DocumentPosition
- 模块组织：单模块 chain-document，第三方依赖设为 optional
- 图片提取：ImageElement 持有 byte[]，自包含不依赖文件系统

**新增文件**: 27 个 Java 文件 + 3 个资源文件 + 2 个 pom.xml


### Git Commits

| Hash | Message |
|------|---------|
| `a32fa90` | (see git log) |
| `fa4957d` | (see git log) |
| `1608cd5` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: 文档内容清洗模块设计与实现

**Date**: 2026-04-02
**Task**: 文档内容清洗模块设计与实现

### Summary

完成文档清洗 Pipeline（8个 Cleaner）+ OcrEngine 接口 + PDF 扫描件检测

### Main Changes

## 完成内容

| 模块 | 内容 | 文件数 |
|------|------|--------|
| 清洗核心接口 | DocumentCleaner + CleanerPipeline + TextContentCleaner 基类 | 3 |
| 基础 Cleaner | WhitespaceNormalizer, ControlCharacterRemover, UnicodeNormalizer | 3 |
| 内容 Cleaner | DuplicateRemover, ShortFragmentMerger, BoilerplateRemover | 3 |
| 结构 Cleaner | TableSerializer, ImageStrategyCleaner | 2 |
| OCR 接口 | OcrEngine 标准接口（无具体实现，留给未来） | 1 |
| PDF 扫描件检测 | PdfDocumentReader 集成扫描件检测 + OCR 路径 | 1 |
| 单元测试 | 9 个测试类，71 个测试用例，全部通过 | 9 |
| Example 更新 | 扫描件 PDF 检测展示 + scanned.pdf 资源 | 2 |

## 关键设计决策

- **Pipeline 模式**: 每个 Cleaner 独立可组合，CleanerPipeline 串联执行
- **OcrEngine 标准接口**: `BufferedImage → String`，未来可接入 Tesseract / 多模态 LLM / 专用 OCR
- **扫描件检测**: Reader 阶段完成，基于文本密度（平均每页字符数 < 阈值）
- **OCR 不在清洗层**: OCR 属于内容提取职责，在 Reader 阶段完成

## 变更文件

- `chain/src/main/java/com/non/chain/document/OcrEngine.java`
- `chain-document/src/main/java/com/non/chain/document/cleaner/` (11 files)
- `chain-document/src/main/java/com/non/chain/document/pdf/PdfDocumentReader.java`
- `chain-document/src/test/java/com/non/chain/document/cleaner/` (8 files)
- `chain-document/src/test/java/com/non/chain/document/pdf/PdfDocumentReaderScannedTest.java`
- `chain-example/src/main/java/com/non/chain/example/PdfDocumentReaderExample.java`
- `chain-example/src/main/resources/document/scanned.pdf`


### Git Commits

| Hash | Message |
|------|---------|
| `5f4ef1c` | (see git log) |
| `c5ae922` | (see git log) |
| `aae9a73` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: RapidOCR Engine 实现

**Date**: 2026-04-02
**Task**: RapidOCR Engine 实现

### Summary

(Add summary)

### Main Changes

## 完成内容

| 项目 | 说明 |
|------|------|
| RapidOCREngine | 通过 `python3 -c` 内联脚本调用 RapidOCR Python API，仅输出纯文本 |
| TesseractOCREngine | 通过 ProcessBuilder 调用 tesseract CLI |
| Example 更新 | PdfDocumentReaderExample 改用 RapidOCREngine |

## 关键决策

- **不使用 `rapidocr` CLI**：CLI 的 `print(result)` 输出 RapidOCROutput 对象结构化字符串，不是纯文本
- **内联 Python 脚本**：`python3 -c` 直接调用 API，`print('\n'.join(r.txts))` 输出纯文本
- **分离 stderr**：`redirectErrorStream(false)` + Python 端 `logging.disable(CRITICAL)` 双重过滤 INFO 日志
- **移除 lang_type 参数**：RapidOCR 的 `Rec.lang_type` 要求枚举类型，默认 `ch` 已满足中英文需求

**新增文件**:
- `chain/src/main/java/com/non/chain/document/RapidOCREngine.java`
- `chain/src/main/java/com/non/chain/document/TesseractOCREngine.java`


### Git Commits

| Hash | Message |
|------|---------|
| `40b7264` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: 文档切分模块实现 + 示例

**Date**: 2026-04-03
**Task**: 文档切分模块实现 + 示例

### Summary

完成文档切分模块的全量实现，包括接口设计、4种切分策略、测试和示例

### Main Changes



### Git Commits

| Hash | Message |
|------|---------|
| `acdf635` | (see git log) |
| `3305a48` | (see git log) |
| `2c1bcdb` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: Add multimodal image input support

**Date**: 2026-04-03
**Task**: Add multimodal image input support

### Summary

Add ContentPart/TextPart/ImageUrlPart for multimodal messages, extend Message and DashscopeLLM to support image input, add ImageInputExample

### Main Changes



### Git Commits

| Hash | Message |
|------|---------|
| `6b56680` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 11: Agent Loop & Streaming Support

**Date**: 2026-04-07
**Task**: Agent Loop & Streaming Support

### Summary

(Add summary)

### Main Changes

| Feature | Description |
|---------|-------------|
| Streaming Output | DashscopeLLM streaming support with ChatChunk callback |
| Agent Loop | Agent class with automatic tool calling loop |
| Agent Builder | Builder pattern: LLM + ToolRegistry required, systemPrompt/maxIterations optional |
| Error Handling | AgentException for max iterations, tool errors passed back to LLM |
| Tests | 8 unit tests covering all Agent scenarios |
| Example | AgentLoopExample - travel assistant with 3 tools |

**Key Commits**:
- `2a3d5cd` feat(llm): add streaming output support
- `cdf255a` feat(agent): add agent loop for automatic tool calling
- `f47d61d` chore: bump version to 0.3.0


### Git Commits

| Hash | Message |
|------|---------|
| `cdf255a` | (see git log) |
| `f47d61d` | (see git log) |
| `2a3d5cd` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 12: feat(knowledge)!: unify retrieval under Elasticsearch v0.4.0

**Date**: 2026-04-08
**Task**: feat(knowledge)!: unify retrieval under Elasticsearch v0.4.0

### Summary

(Add summary)

### Main Changes

## Summary

Unified all knowledge retrieval (vector/BM25/hybrid) under Elasticsearch, removed chain-pgvector, redesigned the retrieval API with client-side RRF/Linear fusion.

## Key Changes

| Area | Changes |
|------|---------|
| **Unified Request Model** | SearchRequest with auto-degradation (text->BM25, embedding->kNN, both->hybrid) |
| **Response Model** | RetrievalResponse + RetrievalDebugInfo (debug/trace switches) |
| **New Types** | RetrievalMode, FusionStrategy, MetadataFilter (AND/OR/NOT + 8 operators) |
| **HybridRetriever** | Client-side RRF and Linear fusion (no ES license dependency) |
| **ElasticsearchSearchSupport** | Shared utility: filter building, BM25 query, result mapping, debug info |
| **Removed** | chain-pgvector module, PgvectorKnowledgeStore, PgvectorExample |
| **Tests** | 140 unit tests for all new models |
| **Docs** | All docs converged to ES-only, migration guide added |
| **Version** | 0.3.0 -> 0.4.0, tag v0.4.0 created |

## Commits

1. `1e15f81` feat(knowledge)!: unify retrieval under Elasticsearch with client-side RRF fusion
2. `46ba67c` docs: converge documentation to Elasticsearch-only retrieval
3. `dc1573b` chore: bump version to 0.4.0

## Bug Fix During Session

- ES native RRF retriever requires Platinum license (403 error). Fixed by switching to client-side RRF fusion.


### Git Commits

| Hash | Message |
|------|---------|
| `1e15f81` | (see git log) |
| `46ba67c` | (see git log) |
| `dc1573b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 13: Graph 事件回调功能 + bump-release 命令

**Date**: 2026-04-08
**Task**: Graph 事件回调功能 + bump-release 命令

### Summary

(Add summary)

### Main Changes

## 完成内容

| 类别 | 内容 |
|------|------|
| 功能 | Graph 引擎添加 `GraphEvent` 事件回调，支持 GRAPH_START / NODE_START / NODE_END / GRAPH_END 四种事件 |
| API | `Graph.Builder.onEvent(Consumer<GraphEvent>)` 方法，事件携带状态快照（深拷贝） |
| 测试 | `GraphTest` 4 个单元测试：全事件类型、无回调兼容、状态快照正确性、条件路由 |
| 文档 | 更新 workflow.md / architecture.md / introduction.md / README.md / examples/overview.md / TODO.md / CHANGELOG.md |
| Skills | 更新 nonchain-workflow skill（新增 GraphEvent 内容），6 个 skills 去掉硬编码版本号 |
| 命令 | 创建 `/trellis:bump-release` 发版一站式更新命令 |
| 版本 | bump 0.4.0 -> 0.5.0 |

## 关键决策

- 选择 `Consumer<GraphEvent>` 结构化事件而非 `Consumer<String>`（与 Agent logger 区分）
- 事件中的 State 使用深拷贝快照，避免可变引用问题
- Skills 版本号统一替换为 `<!-- use latest -->` 占位，避免每次发版更新

## 变更文件

- `chain/src/main/java/com/non/chain/flow/GraphEvent.java` (新建)
- `chain/src/test/java/com/non/chain/flow/GraphTest.java` (新建)
- `chain/src/main/java/com/non/chain/flow/Graph.java` (修改)
- `chain-example/src/main/java/com/non/chain/example/GraphKnowledgeExample.java` (修改)
- `pom.xml` + 4 个子模块 pom.xml (版本号)
- `docs/` 下 4 个文档 + README + CHANGELOG + TODO
- `~/.claude/skills/` 下 6 个 skill 文件
- `.claude/commands/trellis/bump-release.md` (新建)
- `.cursor/commands/trellis-bump-release.md` (新建)


### Git Commits

| Hash | Message |
|------|---------|
| `b986d0c` | (see git log) |
| `38d278a` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 14: RapidOCREngine 迁移到 uv run python

**Date**: 2026-04-09
**Task**: RapidOCREngine 迁移到 uv run python

### Summary

(Add summary)

### Main Changes

## 变更内容

| 类别 | 说明 |
|------|------|
| 代码变更 | `RapidOCREngine.buildCommand()` 从 `python3` 改为 `uv run python` |
| 版本号 | 0.5.0 → 0.5.1（所有 pom.xml） |
| CHANGELOG | 新增 [0.5.1] - 2026-04-09 条目 |
| 文档更新 | model.md / readers.md / examples/overview.md 中 pip → uv |
| Skills | nonchain-document SKILL.md 中 pip → uv |

**修改文件**:
- `chain/src/main/java/com/non/chain/document/RapidOCREngine.java`
- `pom.xml`, `chain/pom.xml`, `chain-document/pom.xml`, `chain-elasticsearch/pom.xml`, `chain-example/pom.xml`
- `CHANGELOG.md`
- `docs/document/model.md`, `docs/document/readers.md`, `docs/examples/overview.md`


### Git Commits

| Hash | Message |
|------|---------|
| `28c209f` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 15: GraphEvent NODE_ERROR/GRAPH_ERROR error handling

**Date**: 2026-04-10
**Task**: GraphEvent NODE_ERROR/GRAPH_ERROR error handling

### Summary

Add NODE_ERROR and GRAPH_ERROR event types to Graph workflow engine for exception handling, bump version to 0.5.2

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `e3e3776` | (see git log) |
| `01af122` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 16: Add temperature/topP/topK sampling parameters to DashscopeLLM

**Date**: 2026-04-13
**Task**: Add temperature/topP/topK sampling parameters to DashscopeLLM

### Summary

Extend DashscopeLLM with temperature, topP, topK chain setters; bump version to 0.5.4

### Main Changes



### Git Commits

| Hash | Message |
|------|---------|
| `b88e5ff` | (see git log) |
| `56a41bf` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 17: LlmDocumentSplitter - LLM 语义文档切分器

**Date**: 2026-04-14
**Task**: LlmDocumentSplitter - LLM 语义文档切分器

### Summary

(Add summary)

### Main Changes

## 概要

基于 LLM 实现语义文档切分器，采用 Hybrid 架构（规则清洗 + LLM 切分），产出自洽的 RAG chunk。

## 实现内容

| 组件 | 说明 |
|------|------|
| `LlmDocumentSplitter` | 核心切分器，单次 LLM 调用完成清洗+切分，JSON 数组输出，重试降级机制 |
| `LlmChunkResult` | JSON 输出模型（content + title） |
| `LlmDocumentSplitterTest` | 13 个单元测试 |
| `LlmDocumentSplitterExample` | 3 种用法示例 |

## 关键决策

- Hybrid 而非纯 LLM：规则清洗做粗活，LLM 做语义细活
- 单次 LLM 调用完成清洗+切分（非两阶段），降低成本
- JSON 数组输出 + extractJsonArray 容错 + 最多 2 次重试 + RecursiveCharacterSplitter 降级
- 原子元素（TABLE/CODE_BLOCK/IMAGE）绕过 LLM 直接透传

## 变更文件

- `chain-document/src/main/java/com/non/chain/document/splitter/LlmDocumentSplitter.java`
- `chain-document/src/main/java/com/non/chain/document/splitter/LlmChunkResult.java`
- `chain-document/src/test/java/com/non/chain/document/splitter/LlmDocumentSplitterTest.java`
- `chain-example/src/main/java/com/non/chain/example/LlmDocumentSplitterExample.java`
- 版本号 0.5.4 → 0.5.5，CHANGELOG/README/docs/Skills 同步更新


### Git Commits

| Hash | Message |
|------|---------|
| `3933a58` | (see git log) |
| `ca16131` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 18: feat(callback): unified ChainCallback interface

**Date**: 2026-04-14
**Task**: feat(callback): unified ChainCallback interface

### Summary

设计并实现统一的 ChainCallback 接口，覆盖 LLM/Tool/Retrieval/Graph 四大组件的生命周期事件，支持 traceId 关联和多订阅者组合

### Main Changes

## 新增

| 组件 | 说明 |
|------|------|
| `ChainCallback` 接口 | 10 个 default 方法，覆盖 LLM/Tool/Retrieval/Graph 的 Start/Complete/Error 生命周期 |
| 事件模型 (`callback/event/`) | 9 个不可变事件类 + TokenUsage 数据类，含 traceId、latencyMs、tokenUsage |
| `ChainTrace` | ThreadLocal traceId 管理，Agent 自动关联同一次迭代的 LLM+Tool 调用 |
| `CompositeCallback` | 多订阅者组合回调，异常隔离 |
| `ChainContext` | 共享上下文，支持注入到各组件 |
| 集成测试 | 10 个测试覆盖 Agent 回调、Graph 桥接、多订阅者、异常隔离、traceId、ChainContext |

## 变更

| 文件 | 改动 |
|------|------|
| `Agent.java` | 移除 `logger(Consumer<String>)`，新增 `callback`/`chainContext`，触发 LLM+Tool 回调 |
| `DashscopeLLM.java` | 新增 `doChatWithCallback`/`doStreamChatWithCallback`，带计时和 token 用量 |
| `ToolRegistry.java` | `execute()` 拆分触发 Tool 回调，新增 callback 构造函数 |
| `Graph.java` | `emit()` 桥接到 `ChainCallback.onGraphEvent`，新增 `callback`/`chainContext` |
| `ElasticsearchKnowledgeStore.java` | `search()` 触发 Retrieval 回调，新增 `callback`/`chainContext` |
| `AgentLoopExample.java` | 更新为使用 ChainCallback |

## 破坏性变更

- `Agent.logger(Consumer<String>)` 已移除，需迁移到 `Agent.builder().callback(ChainCallback)`

## 版本

- 0.5.5 → 0.6.0


### Git Commits

| Hash | Message |
|------|---------|
| `1411fe5` | (see git log) |
| `d7aa80d` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 19: Fix tokenUsage tracking in ChatResult and DashscopeLLM

**Date**: 2026-04-14
**Task**: Fix tokenUsage tracking in ChatResult and DashscopeLLM

### Summary

(Add summary)

### Main Changes

## Summary

修复 tokenUsage 数据流，使 TokenUsage 从死代码变为实际可用的功能。

## Changes

| File | Change |
|------|--------|
| ChatResult.java | 新增 TokenUsage 字段 + 4 参数构造函数 + tokenUsage() 访问器 |
| DashscopeLLM.doChat() | 从 ChatCompletion.usage() 提取 token 用量 |
| DashscopeLLM.doStreamChat() | 从最后一个 stream chunk 提取 token 用量 |
| doChatWithCallback() | result.tokenUsage() 替代硬编码 null |
| doStreamChatWithCallback() | result.tokenUsage() 替代硬编码 null |

## Key Decisions

- 使用单元素数组 `CompletionUsage[] lastUsage = {null}` 在流式 lambda 中累积 usage，避免额外 import
- ChatResult 新增 4 参数构造函数，原有 2/3 参数构造函数委托到新构造函数（tokenUsage 默认 null），保持向后兼容

## Release

- Bump 0.6.0 → 0.6.1 (pom.xml + CHANGELOG.md + nonchain-llm skill)


### Git Commits

| Hash | Message |
|------|---------|
| `fc22f7a` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 20: feat: 对话记忆 (Memory) 模块 — ChatMemory + ChatMemoryStore + Agent 集成 + MySQL 持久化

**Date**: 2026-04-15
**Task**: feat: 对话记忆 (Memory) 模块 — ChatMemory + ChatMemoryStore + Agent 集成 + MySQL 持久化

### Summary

(Add summary)

### Main Changes

## Summary

为 nonchain 框架 0.7.0 版本实现完整的对话记忆 (Memory) 模块，包括核心接口设计、两种裁剪策略、内存/MySQL 持久化存储、Agent 集成。

## Key Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| 抽象层设计 | 策略 + 存储分离 | ChatMemory (策略) + ChatMemoryStore (存储)，参考 LangChain4j/Spring AI |
| 模块组织 | chain 核心 + chain-mysql 子模块 | 接口在核心，MySQL 实现独立，可扩展 Postgres/Mongo |
| MySQL 访问层 | 原生 JDBC + DataSource | 轻量，用户可选连接池 |
| Token 计数 | Tokenizer 接口 + jtokkit | 接口在核心，jtokkit 从 chain-document 提升 |
| 消息序列化 | role + content_json (JSON) | Message 结构变化只影响序列化逻辑 |
| Agent 集成 | 仅 run(String) 使用 Memory | run(List<Message>) 保持原有语义 |

## New Files

### chain 核心模块 (`com.non.chain.memory`)
| File | Description |
|------|-------------|
| `ChatMemory.java` | 策略接口 (conversationId/add/addAll/messages/clear) |
| `ChatMemoryStore.java` | 存储接口 (getMessages/updateMessages/deleteMessages) |
| `Tokenizer.java` | Token 计数接口 |
| `JtokkitTokenizer.java` | jtokkit 实现 (ofEncoding/ofModel/defaults) |
| `InMemoryChatMemoryStore.java` | ConcurrentHashMap 内存存储 |
| `MessageWindowChatMemory.java` | 滑动窗口策略 + SystemMessage/工具配对保护 |
| `TokenWindowChatMemory.java` | Token 裁剪策略 |

### chain-mysql 子模块 (`com.non.chain.mysql`)
| File | Description |
|------|-------------|
| `MysqlChatMemoryStore.java` | JDBC + DataSource MySQL 持久化 |
| `MessageSerializer.java` | Message JSON 序列化/反序列化 |
| `chat_memory_message.sql` | 建表脚本 |

### Tests (40 new tests)
| Test Class | Tests |
|------------|-------|
| `MessageWindowChatMemoryTest` | 10 |
| `TokenWindowChatMemoryTest` | 9 |
| `JtokkitTokenizerTest` | 9 |
| `InMemoryChatMemoryStoreTest` | 6 |
| `AgentMemoryTest` | 6 |
| `MysqlChatMemoryStoreTest` | 8 |
| `MessageSerializerTest` | 12 |

## Modified Files

| File | Change |
|------|--------|
| `Agent.java` | 新增 memory 字段 + Builder.memory() + run(String) Memory 集成逻辑 |
| `Message.java` | 新增 Message.of() 工厂方法（反序列化用） |
| `chain/pom.xml` | 添加 jtokkit 依赖 |
| `pom.xml` | 添加 chain-mysql 子模块 |

## Documentation Updates

| File | Change |
|------|--------|
| `CHANGELOG.md` | 新增 [0.7.0] - 2026-04-15 条目 |
| `TODO.md` | 对话记忆标记为 [x] 已完成 |
| `docs/overview/architecture.md` | 架构图、模块依赖图、接口表更新 |
| `docs/overview/introduction.md` | 版本号、特性描述、模块表更新 |
| `docs/overview/getting-started.md` | 版本号、新增 chain-mysql 依赖 |
| `.trellis/spec/backend/directory-structure.md` | 目录结构添加 memory 包 |
| `.trellis/spec/backend/database-guidelines.md` | 重写为支持持久化架构 |
| `nonchain-agent/SKILL.md` | 新增对话记忆章节 |

## Version Bump: 0.6.1 → 0.7.0

All pom.xml files updated (root + 5 submodules).


### Git Commits

| Hash | Message |
|------|---------|
| `uncommitted` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 21: Multi-provider architecture: AbstractOpenAILLM + OpenAICompatibleLLM

**Date**: 2026-04-15
**Task**: Multi-provider architecture: AbstractOpenAILLM + OpenAICompatibleLLM

### Summary

Refactor DashscopeLLM into multi-provider architecture, add generic OpenAI-compatible provider for vllm-openai/Ollama/LiteLLM

### Main Changes

| Category | Changes |
|----------|---------|
| **New Classes** | `AbstractOpenAILLM`, `OpenAICompatibleLLM`, `AbstractOpenAIEmbeddingModel`, `OpenAICompatibleEmbeddingModel` |
| **Refactored** | `DashscopeLLM` (538→100 lines, extends base), `DashScopeEmbeddingModel` (110→40 lines, extends base) |
| **Docs** | New `openai-compatible-llm.md`, updated `architecture.md`, `dashscope-llm.md`, `README.md`, `TODO.md` |
| **Skills** | Updated `nonchain-llm` and `nonchain-embedding` with provider hierarchy and usage examples |
| **Version** | 0.7.0 → 0.7.1 |

**Key Design Decisions**:
- `AbstractOpenAILLM` contains all generic OpenAI API logic (message building, tool calls, streaming, thinking mode, callbacks)
- Thinking mode (`enable_thinking`/`reasoning_content`) in base class — vllm also supports for qwen3
- `topK` kept only in `DashscopeLLM` subclass (DashScope-specific, non-standard)
- API Key optional for `OpenAICompatibleLLM` (uses placeholder "no-api-key" for intranet no-auth deployments)

**Production Target**: vllm-openai at 10.100.10.21:40000, model qwen3.5-35b-a3b (named qwen3-14b)


### Git Commits

| Hash | Message |
|------|---------|
| `318f260` | (see git log) |
| `6f30373` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 22: Reranker 集成 + Bug Fix + 发版 0.7.2

**Date**: 2026-04-15
**Task**: Reranker 集成 + Bug Fix + 发版 0.7.2

### Summary

(Add summary)

### Main Changes

## 完成内容

| 类别 | 内容 |
|------|------|
| Feature | `Reranker` 函数式接口 + `OpenAICompatibleReranker` 实现（vLLM /v1/rerank） |
| Feature | `ElasticsearchKnowledgeStore.Builder.reranker()` 可选注入 |
| Feature | `LocalModelSmokeTestExample` 示例（LLM/Embedding/Reranker 三合一验证） |
| Fix | `AbstractOpenAILLM.buildSimpleParams()` 消息顺序错误导致 vLLM 400 |
| Fix | `OpenAICompatibleReranker.sendRequest()` null stream NPE |
| Test | 16 个单元测试覆盖构造/输入/JSON/解析 |
| Release | bump version 0.7.1 → 0.7.2 |

## 关键文件

- `chain/src/main/java/com/non/chain/knowledge/Reranker.java` — 新增
- `chain/src/main/java/com/non/chain/knowledge/OpenAICompatibleReranker.java` — 新增
- `chain/src/test/java/com/non/chain/knowledge/OpenAICompatibleRerankerTest.java` — 新增
- `chain-elasticsearch/.../ElasticsearchKnowledgeStore.java` — 修改（reranker 集成）
- `chain/.../provider/AbstractOpenAILLM.java` — 修改（消息顺序修复）
- `chain-example/.../LocalModelSmokeTestExample.java` — 新增

## 过程中发现的问题

1. vLLM 严格要求 system message 在 user message 之前，OpenAI Java SDK 按调用顺序追加消息
2. `HttpURLConnection.getErrorStream()` 可返回 null，需要 null 检查


### Git Commits

| Hash | Message |
|------|---------|
| `53e9834` | (see git log) |
| `3e0bc02` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 23: feat(llm): add VLLM provider with thinking support

**Date**: 2026-04-16
**Task**: feat(llm): add VLLM provider with thinking support

### Summary

(Add summary)

### Main Changes

| 变更 | 说明 |
|------|------|
| VLLM.java | 新增 vLLM provider，覆写 `applyThinkingParams()` 和 `getThinkingFieldName()` |
| AbstractOpenAILLM | 重构：拆分 `applyThinkingParams()`/`applyCommonParams()`，新增 `getThinkingFieldName()` hook |
| VLLMExample | 4 种场景示例：基础对话、thinking、budget、流式 |
| docs/llm/vllm.md | VLLM provider 使用指南 |
| 版本号 | 0.7.2 → 0.7.3 |

**关键修复**: vLLM 响应中思考内容字段名为 `reasoning`（非 DashScope 的 `reasoning_content`），通过 `getThinkingFieldName()` hook 解决

**涉及文件**:
- `chain/src/main/java/com/non/chain/provider/VLLM.java` (新)
- `chain/src/main/java/com/non/chain/provider/AbstractOpenAILLM.java`
- `chain-example/src/main/java/com/non/chain/example/VLLMExample.java` (新)
- `docs/llm/vllm.md` (新)
- `docs/overview/architecture.md`
- `docs/llm/openai-compatible-llm.md`
- `README.md`


### Git Commits

| Hash | Message |
|------|---------|
| `fd45360` | (see git log) |
| `cfdd9fb` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 24: Refactor LLM constructors - remove maxCompletionTokens and callback

**Date**: 2026-04-16
**Task**: Refactor LLM constructors - remove maxCompletionTokens and callback

### Summary

将 maxCompletionTokens 和 callback 从 LLM Provider 构造方法中移除，改为 fluent setter 配置。更新文档、示例和 Skills。版本号 bump 至 0.7.4。

### Main Changes



### Git Commits

| Hash | Message |
|------|---------|
| `758ef00` | (see git log) |
| `21f172d` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 25: VLLM 多模态 base64 图片支持

**Date**: 2026-04-22
**Task**: VLLM 多模态 base64 图片支持

### Summary

(Add summary)

### Main Changes

| 变更 | 说明 |
|------|------|
| 新增 ImageDataPart | base64 图片数据内容部件，支持 of(base64, mimeType) 和 fromFile(path) |
| AbstractOpenAILLM | toSdkContentPart() 增加 ImageDataPart 分支，转为 data URI 格式 |
| MessageSerializer | 序列化/反序列化支持 ImageDataPart |
| VLLMMultimodalExample | 演示 URL、本地文件、base64 三种图片输入 |
| 文档更新 | multimodal.md、vllm.md、architecture.md、examples overview |
| 版本更新 | 0.7.4 → 0.7.5 |

**修改文件**:
- `chain/src/main/java/com/non/chain/ImageDataPart.java` (新增)
- `chain/src/main/java/com/non/chain/provider/AbstractOpenAILLM.java`
- `chain-mysql/src/main/java/com/non/chain/mysql/MessageSerializer.java`
- `chain-example/src/main/java/com/non/chain/example/VLLMMultimodalExample.java` (新增)
- `docs/llm/multimodal.md`, `docs/llm/vllm.md`, `docs/overview/architecture.md`, `docs/examples/overview.md`
- `CHANGELOG.md`, `README.md`, 6 个 pom.xml


### Git Commits

| Hash | Message |
|------|---------|
| `685c891` | (see git log) |
| `9fe0ef8` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 26: Add PostgresChatMemoryStore

**Date**: 2026-04-24
**Task**: Add PostgresChatMemoryStore
**Branch**: `main`

### Summary

新增 chain-postgres 模块实现 PostgreSQL 持久化 ChatMemoryStore，将 MessageSerializer 从 chain-mysql 提取到 chain core 共用，版本升至 0.7.6

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `5081474` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 27: Agent streaming output

**Date**: 2026-04-24
**Task**: Agent streaming output
**Branch**: `main`

### Summary

为 Agent 添加流式输出支持，新增 AgentEvent 接口（9 种事件类型）和 Consumer 回调风格的 run 重载，完整向后兼容，249 测试全通过

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `7e4e35d` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 28: 修复工具参数 JSON 解析支持数组/对象 + 发版 0.8.2

**Date**: 2026-06-16
**Task**: 修复工具参数 JSON 解析支持数组/对象 + 发版 0.8.2
**Branch**: `main`

### Summary

修复 parseSimpleJson 手写解析器不认 [/[{ 嵌套导致 ClassCastException。用 Jackson ObjectMapper 替换 parser，增强注解方式 schema 生成（javaTypeToJsonType 认 array/object、inferItemsType 从方法签名泛型自动推断元素类型、零 @ToolParam API 变更），convertType 适配 List/Set/Java 数组/Map 目标类型，Tool.Property 增加 items 字段。新增 ToolRegistryTest 14 用例（chain 测试 270 全绿）。沉淀 spec: backend/tool-function-calling.md 跨层契约。发版 0.8.2，同步 CHANGELOG/docs/nonchain-tool skill。两轮决策：D1 纳入注解 array、D2 自动泛型推断。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `a365a9e` | (see git log) |
| `7398b1e` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 29: 发版 0.8.5（消息分层 + 工具拦截器）并归档任务

**Date**: 2026-06-25
**Task**: 发版 0.8.5（消息分层 + 工具拦截器）并归档任务
**Branch**: `feat/06-24-message-layering`

### Summary

将版本从 0.8.4 升至 0.8.5，覆盖本轮两个功能（应用层消息分层、Agent 工具拦截器）的发版收尾：更新 7 个 pom.xml 版本号、CHANGELOG 标记 [0.8.5] - 2026-06-24 并补空 [Unreleased] 节、为 nonchain-agent skill 补充工具拦截器与 Message.note 两个新公开 API 的说明/示例/类表。归档已完成的 3 个 Trellis 任务（tool-interceptor、message-layering 及父任务 agent-control-flow-extensibility）。注：.trellis/ 被 .gitignore 忽略，archive/journal 均不产生 git commit，仅写盘。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `c7ce72f` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 30: 委派型子代理 SubAgent MVP 实施 + 发版 0.9.0

**Date**: 2026-06-29
**Task**: 委派型子代理 SubAgent MVP 实施 + 发版 0.9.0
**Branch**: `main`

### Summary

实施委派型 SubAgent MVP：父 Agent 通过 tool calling 自主委派子任务给专职子代理。两层职责拆分（注册在 ToolRegistry、暴露在 Agent.Builder），DIRECT/DELEGATE 双模式；子代理默认无状态隔离（独立 systemPrompt/工具集/拦截器/maxIterations，默认继承父 LLM，父/子 callback 与 trace 隔离）；上下文裁剪排除 llmVisible=false 且不含父 systemPrompt；错误语义软失败回灌；仅一层委派、Agent 自动循环外 fail-fast。新增 4 个类型 + 13 个测试（全量回归通过）。配套 SubAgentExample、README/docs/spec/skills 同步。随后执行 bump-release 0.8.5→0.9.0（5 个 pom + CHANGELOG + TODO + architecture），打 tag v0.9.0。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `5349d84` | (see git log) |
| `7f1614f` | (see git log) |
| `88ca9f4` | (see git log) |
| `5ce6d23` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 31: 归档 trace-telemetry 任务

**Date**: 2026-07-09
**Task**: 归档 trace-telemetry 任务
**Branch**: `main`

### Summary

执行链路遥测 Trace Telemetry + MySQL/PostgreSQL 持久化 store 已合入 main,走 finish-work 归档收尾。任务产物(prd/design/implement/check)完整,从 .trellis/tasks/06-29-trace-telemetry 移至 archive/2026-07/。注:.trellis/ 被 gitignore 忽略,archive/session 记录未自动提交。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `716270b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 32: 完成 Skill 注入角色配置

**Date**: 2026-07-14
**Task**: 完成 Skill 注入角色配置
**Branch**: `feat/skill-system`

### Summary

新增 SkillInjectionMode，默认 SYSTEM，支持 Agent.Builder 显式 USER 注入；子代理传播配置，补齐测试、README、VLLM 示例和后端规范。定向、chain、chain-example 与全仓 Maven 测试通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `8877d72` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 33: 补充 SubAgent 执行进度事件

**Date**: 2026-07-14
**Task**: 补充 SubAgent 执行进度事件
**Branch**: `feat/skill-system`

### Summary

新增 AgentEvent.SubAgentProgress，转发前后台 SubAgent 全部内部事件并携带调用上下文；补充 Skill 注入、重复调用 ID、后台关联与观察者异常测试，更新 SubAgentSkillExample/README/spec。修复后台 future 完成与 join 竞态。chain、chain-example 和全仓 Maven 测试通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `8e9bd43` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 34: SubAgent 上下文归一化与模型兼容

**Date**: 2026-07-14
**Task**: SubAgent 上下文归一化与模型兼容
**Branch**: `feat/skill-system`

### Summary

完成 SubAgent 父 system 隔离、不可见消息和工具调用组归一化；新增 LLM 多 system 能力声明与请求副本降级，SYSTEM Skill 在不支持多 system 的模型上自动转为带框架边界的 USER 消息。补充前台/后台/Skill/provider 回归测试，全仓 414 个测试通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `9628a69` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete
