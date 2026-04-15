# Nonchain TODO

> 大模型应用开发库待完善功能清单

---

## 高优先级（核心能力缺口）

- [x] **流式输出 (Streaming)** — `streamChat()` 已支持逐 token 流式返回，包含思考内容和工具调用流式
- [x] **多 LLM Provider 支持** — 已通过 `AbstractOpenAILLM` 抽象基类 + `OpenAICompatibleLLM` 通用 provider 实现多 Provider 架构，支持 vllm-openai、Ollama、LiteLLM 等任何 OpenAI 兼容端点。DashScope 已重构为子类
- [ ] **Prompt 模板系统** — 没有 Prompt 模板管理（变量替换、模板复用），实际开发中大量 prompt 需要参数化
- [x] **对话记忆 (Memory)** — ChatMemory 策略接口 + ChatMemoryStore 存储接口，内置滑动窗口 (`MessageWindowChatMemory`) 和 Token 裁剪 (`TokenWindowChatMemory`) 策略，InMemory 和 MySQL 持久化实现，Agent 集成支持多轮对话
- [ ] **结构化输出解析 (Output Parser)** — 目前只有 `OutputFormat.JSON_OBJECT`，缺少将 JSON 反序列化为 Java POJO 的 `OutputParser<T>` 抽象

## 中优先级（开发体验 & 易用性）

- [ ] **RAG 高级封装** — 底层组件齐全，但缺少开箱即用的 RAG Pipeline（retrieve → inject prompt → call LLM）
- [x] **回调/可观测性 (Callback & Observability)** — `ChainCallback` 统一回调机制已实现，覆盖 LLM/Tool/Retrieval/Graph 的 Start/Complete/Error 生命周期，含 Token 用量统计（`TokenUsage`）、延迟追踪（`latencyMs`）、traceId 关联（`ChainTrace`）和多订阅者组合（`CompositeCallback`）。OpenTelemetry/Micrometer 集成仍为 out-of-scope
- [x] **Agent 高级抽象** — 基础 Agent 自动工具循环已实现（`Agent` 类，LLM 调工具 → 观察结果 → 继续推理）。ReAct Agent、Plan-and-Execute 等高层 Agent 模式仍待实现
- [ ] **重试 & 容错** — 缺少内置重试机制（Rate Limit 429、网络超时）、fallback 主备切换、速率限制器
- [ ] **响应缓存** — 缺少 LLM 响应缓存（相同 prompt 不重复调用），降低开发调试成本

## 低优先级（生态扩展）

- [ ] **更多向量存储** — 目前只有 ES，可考虑 Milvus、Chroma、Redis 等
- [ ] **更多文档格式** — 缺少 Excel、CSV、PPT 解析
- [ ] **网页加载器** — URL 抓取 + HTML 转 ParsedDocument
- [ ] **异步/响应式支持** — CompletableFuture 或 Reactive Stream
- [ ] **Token 计数** — jtokkit 已在 document 模块使用，但未暴露到 LLM 层做 Token 限制管理
