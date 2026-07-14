# Workspace Index - non

> Journal tracking for AI development sessions.

---

## Current Status

<!-- @@@auto:current-status -->
- **Active File**: `journal-1.md`
- **Total Sessions**: 34
- **Last Active**: 2026-07-14
<!-- @@@/auto:current-status -->

---

## Active Documents

<!-- @@@auto:active-documents -->
| File | Lines | Status |
|------|-------|--------|
| `journal-1.md` | ~1529 | Active |
<!-- @@@/auto:active-documents -->

---

## Session History

<!-- @@@auto:session-history -->
| # | Date | Title | Commits | Branch |
|---|------|-------|---------|--------|
| 34 | 2026-07-14 | SubAgent 上下文归一化与模型兼容 | `9628a69` | `feat/skill-system` |
| 33 | 2026-07-14 | 补充 SubAgent 执行进度事件 | `8e9bd43` | `feat/skill-system` |
| 32 | 2026-07-14 | 完成 Skill 注入角色配置 | `8877d72` | `feat/skill-system` |
| 31 | 2026-07-09 | 归档 trace-telemetry 任务 | `716270b` | `main` |
| 30 | 2026-06-29 | 委派型子代理 SubAgent MVP 实施 + 发版 0.9.0 | `5349d84`, `7f1614f`, `88ca9f4`, `5ce6d23` | `main` |
| 29 | 2026-06-25 | 发版 0.8.5（消息分层 + 工具拦截器）并归档任务 | `c7ce72f` | `feat/06-24-message-layering` |
| 28 | 2026-06-16 | 修复工具参数 JSON 解析支持数组/对象 + 发版 0.8.2 | `a365a9e`, `7398b1e` | `main` |
| 27 | 2026-04-24 | Agent streaming output | `7e4e35d` | `main` |
| 26 | 2026-04-24 | Add PostgresChatMemoryStore | `5081474` | `main` |
| 25 | 2026-04-22 | VLLM 多模态 base64 图片支持 | `685c891`, `9fe0ef8` |
| 24 | 2026-04-16 | Refactor LLM constructors - remove maxCompletionTokens and callback | `758ef00`, `21f172d` |
| 23 | 2026-04-16 | feat(llm): add VLLM provider with thinking support | `fd45360`, `cfdd9fb` |
| 22 | 2026-04-15 | Reranker 集成 + Bug Fix + 发版 0.7.2 | `53e9834`, `3e0bc02` |
| 21 | 2026-04-15 | Multi-provider architecture: AbstractOpenAILLM + OpenAICompatibleLLM | `318f260`, `6f30373` |
| 20 | 2026-04-15 | feat: 对话记忆 (Memory) 模块 — ChatMemory + ChatMemoryStore + Agent 集成 + MySQL 持久化 | uncommitted |
| 19 | 2026-04-14 | Fix tokenUsage tracking in ChatResult and DashscopeLLM | `fc22f7a` |
| 18 | 2026-04-14 | feat(callback): unified ChainCallback interface | `1411fe5`, `d7aa80d` |
| 17 | 2026-04-14 | LlmDocumentSplitter - LLM 语义文档切分器 | `3933a58`, `ca16131` |
| 16 | 2026-04-13 | Add temperature/topP/topK sampling parameters to DashscopeLLM | `b88e5ff`, `56a41bf` |
| 15 | 2026-04-10 | GraphEvent NODE_ERROR/GRAPH_ERROR error handling | `e3e3776`, `01af122` |
| 14 | 2026-04-09 | RapidOCREngine 迁移到 uv run python | `28c209f` |
| 13 | 2026-04-08 | Graph 事件回调功能 + bump-release 命令 | `b986d0c`, `38d278a` |
| 12 | 2026-04-08 | feat(knowledge)!: unify retrieval under Elasticsearch v0.4.0 | `1e15f81`, `46ba67c`, `dc1573b` |
| 11 | 2026-04-07 | Agent Loop & Streaming Support | `cdf255a`, `f47d61d`, `2a3d5cd` |
| 10 | 2026-04-03 | Add multimodal image input support | `6b56680` |
| 9 | 2026-04-03 | 文档切分模块实现 + 示例 | `acdf635`, `3305a48`, `2c1bcdb` |
| 8 | 2026-04-02 | RapidOCR Engine 实现 | `40b7264` |
| 7 | 2026-04-02 | 文档内容清洗模块设计与实现 | `5f4ef1c`, `c5ae922`, `aae9a73` |
| 6 | 2026-04-01 | Document Parsing & Extraction Module | `a32fa90`, `fa4957d`, `1608cd5` |
| 5 | 2026-04-01 | Knowledge Base ES 单索引重构与错误处理 | `6db900e`, `d2999d5`, `bcc45eb` |
| 4 | 2026-03-31 | 支持LLM结构化输出json_object | `1184d49`, `ae5b7d9` |
| 3 | 2026-03-31 | Refactor LLM interface usage | `b01e8fa` |
| 2 | 2026-03-31 | 重做提交与Tag并完成DashScope环境变量改造 | `36bd0bd` |
| 1 | 2026-03-31 | Bootstrap development guidelines | `8c994eb` |
<!-- @@@/auto:session-history -->

---

## Notes

- Sessions are appended to journal files
- New journal file created when current exceeds 2000 lines
- Use `add_session.py` to record sessions