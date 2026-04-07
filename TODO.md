# Nonchain TODO

> 大模型应用开发库待完善功能清单

---

## 高优先级（核心能力缺口）

- [ ] **流式输出 (Streaming)** — 当前只有同步 `chat()` 调用，无法逐 token 流式返回，对聊天类应用是刚需
- [ ] **多 LLM Provider 支持** — 目前只有 DashScope 实现，缺少 OpenAI、Anthropic、Ollama、Gemini 等主流提供商适配
- [ ] **Prompt 模板系统** — 没有 Prompt 模板管理（变量替换、模板复用），实际开发中大量 prompt 需要参数化
- [ ] **对话记忆 (Memory)** — 缺少对话历史管理：滑动窗口、摘要记忆、Token 限制裁剪
- [ ] **结构化输出解析 (Output Parser)** — 目前只有 `OutputFormat.JSON_OBJECT`，缺少将 JSON 反序列化为 Java POJO 的 `OutputParser<T>` 抽象

## 中优先级（开发体验 & 易用性）

- [ ] **RAG 高级封装** — 底层组件齐全，但缺少开箱即用的 RAG Pipeline（retrieve → inject prompt → call LLM）
- [ ] **回调/可观测性 (Callback & Observability)** — 缺少统一回调机制（onStart、onToken、onComplete、onError）、Token 用量统计、延迟追踪、OpenTelemetry/Micrometer 集成
- [ ] **Agent 高级抽象** — 缺少 ReAct Agent、Plan-and-Execute 等高层 Agent 模式，缺少自动工具循环（LLM 调工具 → 观察结果 → 继续推理）
- [ ] **重试 & 容错** — 缺少内置重试机制（Rate Limit 429、网络超时）、fallback 主备切换、速率限制器
- [ ] **响应缓存** — 缺少 LLM 响应缓存（相同 prompt 不重复调用），降低开发调试成本

## 低优先级（生态扩展）

- [ ] **更多向量存储** — 目前只有 ES，可考虑 Milvus、Chroma、Redis 等
- [ ] **更多文档格式** — 缺少 Excel、CSV、PPT 解析
- [ ] **网页加载器** — URL 抓取 + HTML 转 ParsedDocument
- [ ] **异步/响应式支持** — CompletableFuture 或 Reactive Stream
- [ ] **Token 计数** — jtokkit 已在 document 模块使用，但未暴露到 LLM 层做 Token 限制管理
