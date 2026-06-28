# 更新日志

所有重要更改均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]
## [0.9.0] - 2026-06-28

### 新增

- 委派型子代理（SubAgent），父 Agent 通过 tool calling 自主把子任务委派给专职子代理，子代理独立运行后把最终文本结果回传
  - 子代理作为一等 tool 能力注册在 `ToolRegistry`，由 `Agent.Builder` 决定暴露模式，两层职责拆分：注册在 `ToolRegistry`、暴露在 `Agent.Builder`
  - 两种暴露模式：`DIRECT`（默认，每个子代理一个独立 tool，schema 仅含 `task`）/ `DELEGATE`（显式开启，单个 `delegate_to_subagent(agentName, task)`，`agentName` 为已注册子代理名枚举）
  - 声明式注册 Builder `registerSubAgent(name, description)`，`description`（进 LLM schema）与 `systemPrompt`（子代理角色）分开定义且均必填；可配 `toolRegistry`（默认无工具子代理）、`llm`（默认继承父 LLM）、`maxIterations`、`contextSelector`、`before/after` 拦截器
  - 新增 4 个类型：`SubAgentExposureMode`（枚举）、`SubAgentDefinition`（不可变值对象）、`ContextSelector`（`@FunctionalInterface`，父上下文裁剪策略）、`ToolRegistry.SubAgentRegistration`（注册 Builder）
  - 子代理默认无状态隔离：独立 systemPrompt/工具集/拦截器/maxIterations、默认继承父 LLM、父/子 callback 与 trace 隔离（子代理用独立 noop callback 与独立 trace，内部事件不透出到父）
  - 上下文裁剪：框架默认从父消息链裁剪上下文注入子代理（含相关 user/assistant/tool，排除 `llmVisible=false`，不含父 `systemPrompt`），可在注册时用 `contextSelector(...)` 覆盖
  - 错误语义：子代理整体失败 → 外层 `ToolErrorEvent` + 错误文本回灌父 Agent，父循环继续；同轮多个子代理可并行并按原始顺序回灌
  - 边界：仅支持一层委派（子代理不能再委派）、仅支持 `Agent` 自动循环，手写 `registry.execute("子代理名", ...)` fail-fast
  - `Agent.safeExecute` 签名增加 `parentMessages` 快照参数（串/并行路径都传 `List.copyOf`），普通工具路径行为不变

### 文档

- `README.md` 新增「委派型子代理（SubAgent）」小节、特性列表与示例表补充
- 新增示例 `SubAgentExample`（DIRECT/DELEGATE 两种模式）
- `.trellis/spec/backend/tool-function-calling.md` 新增「SubAgent Registration vs Exposure」「SubAgent Runtime Isolation」契约章节
- `.trellis/spec/backend/directory-structure.md` 补全 `agent/` 包文件列表与 SubAgent 注册步骤

## [0.8.5] - 2026-06-24

### 新增

- 应用层消息与 LLM 消息分层（`Message.llmVisible` / `kind`），允许应用把 UI-only 状态（"正在思考"、"已读取文件"、"工具审核中"）记录进对话 transcript 供 UI 重放，同时保证这些消息不进 LLM 上下文
  - `Message` 新增 `llmVisible`（默认 `true`）与 `kind`（可选语义标签，如 `status`/`ui`）字段，及 `Message.note(kind, content)` 工厂（产出 `role="note"`、`llmVisible=false` 的应用层消息）
  - `AbstractOpenAILLM` 边界单点过滤：`llmVisible=false` 的消息在送入 provider 请求前被剥离（静默，遵循项目无日志框架约定），所有 provider（Dashscope/OpenAICompatible/VLLM）共用，`default:throw` 保留作 fail-safe
  - 持久化零 DDL：`MessageSerializer` 往返 `llmVisible`/`kind`，旧 `content_json` 不含该字段时默认 `llmVisible=true`，三种 store（InMemory/MySQL/Postgres）自动继承
  - 裁剪策略：非 LLM 消息不计入窗口/token 预算、原位保留，不破坏现有 tool 消息配对保护
  - 现有 `Message` API 零破坏（旧工厂与 5 参 `of(...)` 全部保留，默认产出 `llmVisible=true`）
- Agent 工具拦截器（`BeforeToolCall` / `AfterToolCall`），在不继承 `Agent`、不破坏 `ChainCallback` 的前提下对工具调用进行拦截、阻止、改写
  - `Agent.Builder` 新增 `addBeforeToolCall` / `addAfterToolCall`，可注册多个；before 任一 `block(reason)` 即短路，after 链式叠加（脱敏/截断/标 `isError`）
  - 新增 5 个类型：`ToolCallContext`（before/after 共用上下文，after 额外携带 `result`/`isError`）、`BeforeResult`（`pass()`/`block(reason)`）、`AfterResult`（`keep()`/`content()`/`error()`/`builder()`）、`BeforeToolCall`、`AfterToolCall`（均为 `@FunctionalInterface`）
  - 拦截器异常包装为 `AgentException` 抛出（不静默吞）；工具执行异常仍走软失败回灌 LLM（现状语义不变）
  - 典型场景：危险命令审核、结果脱敏、超长输出截断、工具熔断

### 变更

- `ToolRegistry.execute` 不再触发 `ChainCallback`，callback 改由 `Agent` 编排层统一触发（每次工具调用仅触发一次）
  - 原 `ToolRegistry` 内部的 callback 触发是死代码：全库 30+ 处均用 `new ToolRegistry()`（callback=noop），`new ToolRegistry(callback)`/`new ToolRegistry(chainContext)` 零调用方
  - 删除 `ToolRegistry(ChainCallback)`、`ToolRegistry(ChainContext)` 两个零调用构造器及 `callback` 字段
  - `ToolRegistry` 回归纯执行器职责；`Agent.safeExecute` 重构为 callback → before → 执行 → after 的编排链

### 文档

- `README.md` 新增「应用层消息与 LLM 消息分层」小节、特性列表与示例表补充
- `README.md` 新增「工具拦截器」小节、特性列表与示例表补充
- `.trellis/spec/backend/tool-function-calling.md` 新增「Tool Interceptors vs Callback (control vs observation)」章节，固化拦截器（控制，异常传播）与 callback（观察，异常隔离）的职责边界

## [0.8.4] - 2026-06-16

### 新增

- `PdfDocumentReader` 扫描件检测新增图片覆盖率维度，应对带 OCR 文字层的扫描件
  - 原有文字密度维度无法识别「图片覆盖率高但文字层已被 OCR 填充」的扫描件，新增图片覆盖率维度作为补充（两者为 OR 关系）
  - 新增两个可配阈值：`largeImagePageThreshold`（单页大图覆盖率阈值，默认 0.3，用 CTM 行列式计算图片渲染面积占页面面积比例，正确处理旋转/剪切）和 `imageCoverageThreshold`（文档级图片覆盖率阈值，默认 0.5，图片重页面占总页数比例）
  - 新增两个构造函数重载，支持自定义覆盖率阈值；原有构造函数向后兼容，沿用默认值
  - fail-fast 校验：覆盖率阈值不在 0-1 之间抛出 `IllegalArgumentException`（中文消息）
  - 新增 3 个测试用例：图片覆盖率触发检测、小插图不误判、阈值可配抑制检测
## [0.8.3] - 2026-06-16

### 新增

- `PdfDocumentReader` 支持自定义 OCR 渲染 DPI
  - 新增构造函数 `PdfDocumentReader(OcrEngine, int scanThreshold, int renderDpi)`，可控制扫描件每页渲染为图片的分辨率（默认 300 DPI）
  - DPI 越高越清晰但渲染越慢，适用于扫描质量差或小字号场景（如调至 600）；追求速度可降低（如 150）
  - fail-fast 校验：DPI ≤ 0 抛出 `IllegalArgumentException`（中文消息）
  - 原有构造函数向后兼容，默认沿用 300 DPI

### 文档

- `docs/document/readers.md` 同步 DPI 配置说明与构造函数表格
- `README.md` 补充 Java 版本测试说明（已在 Java 11 / 17 / 21 上测试通过）

## [0.8.2] - 2026-06-16

### 修复

- 工具参数 JSON 解析支持数组/对象，修复 `ClassCastException`
  - 用 Jackson `ObjectMapper` 替换手写 `parseSimpleJson`（原解析器不识别 `[`/`{` 嵌套、不支持字符串转义与值内逗号），使 schema 声明的 `array`/`object` 类型能被正确解析为 `List`/`Map`
  - `ToolRegistry.javaTypeToJsonType` 增加 `array`/`object` 类型判断；新增 `inferItemsType` 从方法签名泛型自动推断数组元素类型（零 `@ToolParam` API 变更）
  - `ToolRegistry.convertType` 适配 `List`/`Set`/Java 数组/`Map` 目标类型（数组元素递归装箱）
  - `Tool` 的 `Property` 增加 `items` 字段，`Builder` 新增带 `itemsType` 的 `addProperty` 重载，`toFunctionDefinition` 在 `type=array` 时输出 `items`（现有标量行为不变）
  - 新增 `ToolRegistryTest`（14 个用例）覆盖数组/对象/转义/标量回归/非法 JSON/注解 array schema/注解容器端到端

## [0.8.1] - 2026-06-11

### 修复

- ChatMemory 裁剪边界修复：当异常消息顺序导致 `tool` 消息出现在其配对的 `assistant(toolCalls)` 之前时，不再单独删除该 `tool` 消息，避免留下无对应 `tool_result` 的无效 LLM 上下文
  - 新增 `ChatMemoryTrimSupport` 共享辅助类，统一处理正向（assistant→tool）和反向（tool→assistant）的配对裁剪
  - `MessageWindowChatMemory` 和 `TokenWindowChatMemory` 委托给共享类，消除重复逻辑
  - 真正孤立的 `tool` 消息（无匹配的 assistant）仍可被单独删除

## [0.8.0] - 2026-06-11

### 新增

- Agent 工具并行执行：当单次 LLM 响应包含多个工具调用时，可通过 `Executor` 并行执行，按原始顺序组装结果
  - `Agent.Builder.executor(Executor)`：设置线程池，默认使用 `ForkJoinPool.commonPool()`；设为 `null` 时串行执行
  - 并行模式下各工具独立执行，`AgentEvent.ToolStart`/`ToolEnd` 事件按实际执行时序触发，结果按调用顺序追加到 messages
  - 单个工具调用或未配置 executor 时保持原有串行执行逻辑，完全向后兼容
- `AbstractOpenAILLM` 新增连接超时配置：connect 30s、read 180s、write 60s

### 变更

- `DashscopeLLM.applyThinkingParams()` 覆写：始终显式发送 `enable_thinking` 参数，修复 Qwen3 模型非流式调用时 thinking 默认开启导致请求失败的问题

## [0.7.7] - 2026-04-24

### 新增

- Agent 流式输出：`run()` 方法新增 `Consumer<AgentEvent>` 回调重载，支持实时接收 Agent 循环过程中的增量事件
  - `AgentEvent` 接口，9 种事件类型：`TextDelta`、`ThinkingDelta`、`ToolCallDelta`、`ToolStart`、`ToolEnd`、`RoundStart`、`RoundEnd`、`AgentError`、`Complete`
  - `Agent.run(String query, Consumer<AgentEvent>)` 和 `Agent.run(List<Message>, Consumer<AgentEvent>)` 流式入口
  - 有回调时内部使用 `llm.streamChat()`，无回调时保持原有 `llm.chat()` 行为，完全向后兼容
- `StreamingAgentExample` 示例：演示 Agent 流式事件消费

## [0.7.6] - 2026-04-24

### 新增

- `chain-postgres` 子模块：PostgreSQL 持久化存储
  - `PostgresChatMemoryStore`：基于 JDBC + DataSource 的对话消息持久化，与 `MysqlChatMemoryStore` 保持一致的事务语义
  - `chat_memory_message.sql` 建表脚本（PostgreSQL 语法：BIGSERIAL、CREATE INDEX IF NOT EXISTS）
- `chain` 核心模块新增 `jackson-databind` 依赖

### 变更

- `MessageSerializer` 从 `chain-mysql` 提取到 `chain` 核心模块（`com.non.chain.memory` 包），供所有存储实现共用
- `MysqlChatMemoryStore` 改为引用核心模块的 `MessageSerializer`，`chain-mysql` 不再单独依赖 `jackson-databind`

## [0.7.5] - 2026-04-22

### 新增

- 多模态 base64 图片支持：新增 `ImageDataPart` 内容部件
  - `ImageDataPart.of(String base64Data, String mimeType)`：直接传入 base64 编码的图片数据
  - `ImageDataPart.fromFile(String filePath)`：从本地文件读取图片，自动检测 MIME 类型并转为 base64
  - 支持 png、jpeg、gif、webp 等常见图片格式
- `AbstractOpenAILLM.toSdkContentPart()` 增加 `ImageDataPart` 分支，通过 data URI 格式发送 base64 图片
- `MessageSerializer` 支持新增 `ImageDataPart` 的序列化与反序列化
- `VLLMMultimodalExample` 示例：演示 VLLM 提供者的三种图片输入方式（URL、本地文件、base64）
- 更新 `docs/llm/multimodal.md` 文档：补充 `ImageDataPart` 说明和 VLLM 多模态用法
- 更新 `docs/llm/vllm.md` 文档：新增多模态支持章节

## [0.7.4] - 2026-04-16

### 变更

- LLM Provider 构造方法简化：`maxCompletionTokens` 和 `callback` 从构造方法参数移除，改为 fluent setter 配置
  - `AbstractOpenAILLM` 构造方法精简为 `(baseUrl, apiKey, model)`，新增 `maxCompletionTokens()` 和 `callback()` fluent setter
  - `DashscopeLLM` 构造方法简化为 `(String model)` 和 `(String apiKey, String model)`
  - `OpenAICompatibleLLM` 构造方法简化为 `(String baseUrl, String model)` 和 `(String baseUrl, String apiKey, String model)`
  - `VLLM` 构造方法同步简化
  - `fromContext()` 静态工厂方法保留 `maxCompletionTokens` 和 `callback` 参数，内部通过 fluent setter 设置

## [0.7.3] - 2026-04-16

### 新增

- vLLM LLM provider：专门为 vLLM 推理服务器设计的 LLM 实现
  - `VLLM` 类：继承 `OpenAICompatibleLLM`，处理 vLLM 特有的 thinking 参数格式
  - thinking 开关通过 `chat_template_kwargs: {enable_thinking: true}` 嵌套 JSON 传递
  - 思考预算通过 `thinking_token_budget` 字段传递
  - `fromContext()` 静态工厂方法，支持 `ChainContext` 注入
  - `VLLMExample` 示例：基础对话、thinking 开关、思考预算控制、流式 + thinking
- 新增 `docs/llm/vllm.md` 文档：vLLM provider 使用指南

### 变更

- `AbstractOpenAILLM` 重构：将 `applyAdditionalParams()` 拆分为 `applyThinkingParams()` + `applyCommonParams()`，thinking 参数注入可被子类覆写
- `AbstractOpenAILLM` 新增 `getThinkingFieldName()` hook：子类可覆写思考内容响应字段名（默认 `reasoning_content`，vLLM 使用 `reasoning`）
- `AbstractOpenAILLM` 新增 `isEnableThinking()` / `getThinkingBudget()` protected 访问器
- `AbstractOpenAILLM` 的 `extractThinking()` / `extractDeltaThinking()` 改为 `protected`，使用 `getThinkingFieldName()` 获取字段名
- `OpenAICompatibleLLM` 继承体系图更新，包含 VLLM

## [0.7.2] - 2026-04-15

### 新增

- Reranker 检索重排序能力
  - `Reranker` 函数式接口：对检索结果进行语义重排序
  - `OpenAICompatibleReranker`：基于 vLLM /v1/rerank 端点实现，支持 bge-reranker-large 等模型，API Key 可选
  - `ElasticsearchKnowledgeStore.Builder.reranker(Reranker)`：可选注入，配置后检索自动重排序（检索 → rerank → 截断到 size）
  - `OpenAICompatibleRerankerTest`：16 个单元测试覆盖构造校验、输入校验、JSON 构建、响应解析
- `LocalModelSmokeTestExample` 示例：验证本地部署的 LLM / Embedding / Reranker 三个服务可用

### 修复

- `AbstractOpenAILLM.buildSimpleParams()` 消息顺序错误：system message 未放在 user message 之前，导致 vLLM 返回 400 错误
- `OpenAICompatibleReranker.sendRequest()` 空响应体处理：`getErrorStream()` 返回 null 时抛出 NPE，改为抛出清晰的错误消息

## [0.7.1] - 2026-04-15

### 新增

- 多 LLM Provider 架构：提取 OpenAI 兼容通用基类，支持 vllm-openai、Ollama、LiteLLM 等任何 OpenAI 兼容端点
  - `AbstractOpenAILLM`：抽象基类，封装 OpenAI Chat Completions API 全部通用逻辑（消息构建、工具调用、流式响应、思考模式、回调）
  - `OpenAICompatibleLLM`：通用 provider，base URL 和 API Key 完全可配置，API Key 可选（内网无认证场景）
  - `OpenAICompatibleEmbeddingModel`：通用 Embedding provider，base URL 完全可配置
  - `AbstractOpenAIEmbeddingModel`：Embedding 抽象基类
- 新增 `docs/llm/openai-compatible-llm.md` 文档：通用 provider 使用指南

### 变更

- `DashscopeLLM` 重构为继承 `AbstractOpenAILLM`（538行 → 100行），保留 DashScope 特有的 `topK` 参数和 API Key 解析逻辑
- `DashScopeEmbeddingModel` 重构为继承 `AbstractOpenAIEmbeddingModel`（110行 → 40行）
- 所有 DashScope 公共 API 保持向后兼容

## [0.7.0] - 2026-04-15

### 新增

- 对话记忆 (Memory) 模块 (`com.non.chain.memory`)：支持多轮对话上下文保持
  - `ChatMemory` 策略接口：管理对话历史的裁剪逻辑 (add/addAll/messages/clear)
  - `ChatMemoryStore` 存储接口：抽象消息的读写和删除，与裁剪策略分离
  - `MessageWindowChatMemory`：滑动窗口策略，保留最近 N 条消息，保护 SystemMessage 和工具消息配对
  - `TokenWindowChatMemory`：基于 Token 数量的裁剪策略，按 token 数限制上下文大小
  - `Tokenizer` 接口 + `JtokkitTokenizer` 实现：基于 jtokkit 的 token 计数能力
  - `InMemoryChatMemoryStore`：内存存储实现
- `chain-mysql` 子模块：MySQL 持久化存储
  - `MysqlChatMemoryStore`：基于 JDBC + DataSource 的对话消息持久化，事务保证
  - `MessageSerializer`：Message 与 JSON 之间的序列化/反序列化工具
  - `chat_memory_message.sql` 建表脚本
- `Agent.builder().memory(ChatMemory)` 集成：配置后 `run(String)` 自动管理多轮对话历史
  - systemPrompt 由 Agent 管理，不存入 Memory
  - 工具调用消息 (assistant + tool result) 自动同步到 Memory
- `Message.of()` 工厂方法：支持从完整参数构造 Message（反序列化场景）
- jtokkit 依赖提升到 chain 核心模块

### 变更

- `Agent` 新增 `memory` 字段和 Builder 方法，`run(String)` 支持 Memory 集成
- `Message` 新增 `of()` 静态工厂方法

## [0.6.1] - 2026-04-14

### 修复

- `ChatResult` 新增 `TokenUsage tokenUsage` 字段，从 LLM 响应中提取实际 token 用量
- `DashscopeLLM.doChat()` 从 `ChatCompletion.usage()` 提取 token 用量传入 ChatResult
- `DashscopeLLM.doStreamChat()` 从流式响应最后一个 chunk 提取 token 用量
- `doChatWithCallback()` 和 `doStreamChatWithCallback()` 将 `result.tokenUsage()` 传入 `LlmCompleteEvent`，替代原来硬编码的 `null`

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
