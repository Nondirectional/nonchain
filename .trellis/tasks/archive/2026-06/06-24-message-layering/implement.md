# 应用层消息与 LLM 消息分层 — Implement

> 设计依据：`design.md`。本文件是实现清单，按顺序执行，每步可独立验证。

## 0. 前置

- 分支已按 Trellis 规范建立（复杂任务，`status=planning → implementing` 由 `task.py start` 切换）。
- 父任务 `06-24-agent-control-flow-extensibility` 已交付 `ToolInterceptor`（`06-24-tool-interceptor`），示例可复用。
- 所有改动为**加性**，无破坏性 API 变更。

## 1. 核心模型（R1）

### 1.1 `chain/src/main/java/com/non/chain/Message.java`
- [ ] 加两个 `final` 字段：`private final boolean llmVisible;` `private final String kind;`
- [ ] 扩展私有构造（`Message.java:19-26`）接收新两参并赋值。
- [ ] 旧 2 参构造 `Message(String role, String content)`（`Message.java:15-17`）转调新私有构造，默认 `llmVisible=true, kind=null`。
- [ ] 新增访问器 `public boolean llmVisible()` / `public String kind()`。
- [ ] 新增工厂 `public static Message note(String kind, String content)` → `role="note", llmVisible=false, kind=kind`。
- [ ] 保留旧 5 参 `of(...)`（`Message.java:84-87`），内部转调新 7 参版（默认 `true/null`）。
- [ ] 新增 7 参 `of(String role, String content, List<ContentPart> contentParts, String toolCallId, List<ToolCall> toolCalls, boolean llmVisible, String kind)`。
- [ ] 现有工厂 `system/user/assistant/assistantWithToolCalls/toolResult` 不改（产出 `llmVisible=true, kind=null`）。

**验证**：`mvn -q -pl chain test -Dtest=MessageTest`（若无 MessageTest，新增最小单测覆盖 `note()`/`of(7参)`/默认值）。

## 2. 序列化与过滤（R3、R2）

### 2.1 `chain/.../memory/MessageSerializer.java`
- [ ] `serialize`（`MessageSerializer.java:28-68`）：`node.put("llmVisible", message.llmVisible());`；`if (message.kind() != null) node.put("kind", message.kind());`
- [ ] `deserialize`（`MessageSerializer.java:70-111`）：`boolean llmVisible = node.has("llmVisible") ? node.get("llmVisible").asBoolean() : true;`；`String kind = node.has("kind") ? node.get("kind").asText() : null;`
- [ ] 末行 `return Message.of(...)`（`MessageSerializer.java:107`）改为 7 参版，传入 `llmVisible, kind`。

### 2.2 `chain/.../provider/AbstractOpenAILLM.java`
- [ ] `buildMessageListParams`（`AbstractOpenAILLM.java:184-241`）：在 role `switch` 之前加过滤循环，构造 `llmMessages`（跳过 `!msg.llmVisible()`）。
- [ ] **过滤静默**（遵循 `logging-guidelines.md`：项目无日志框架，库代码不加 logger/`System.out`）。
- [ ] 后续 `for (Message msg : llmMessages)` 替换原 `for (Message msg : messages)`，switch 分支一字不改。
- [ ] **保留** `default: throw`（`AbstractOpenAILLM.java:236-237`）作 fail-safe（唯一可观测信号）。

**验证**：`MessageSerializerTest` 扩展往返用例（含 note 与旧数据）；`AbstractOpenAILLM` 过滤用单测（mock 或捕获 builder 的 messages —— 见 §4 测试策略）。

## 3. 裁剪（R4）

### 3.1 `chain/.../memory/ChatMemoryTrimSupport.java`
- [ ] `findFirstDeletableIndex`（`ChatMemoryTrimSupport.java:13-19`）：从 start 起向后扫描，**跳过** `llmVisible=false` 的索引，返回首个可删 LLM 消息索引；全为非 LLM / system 时返回 -1。
- [ ] 注意：`countMessageGroup` 无需改 —— note 的 role=`"note"` 天然不命中 `isAssistantWithToolCalls`/`isToolMessage`，且删除候选已不含 note。

### 3.2 `chain/.../memory/MessageWindowChatMemory.java`
- [ ] `trim`（`MessageWindowChatMemory.java:83-95`）：循环条件 `messages.size() > maxMessages` 改为「LLM 可见消息数 > maxMessages」。加私有 `countLlmVisible(List<Message>)`。
- [ ] 删除循环仍调 `findFirstDeletableIndex`（已会跳过 note）+ `countMessageGroup`。

### 3.3 `chain/.../memory/TokenWindowChatMemory.java`
- [ ] `trim`（`TokenWindowChatMemory.java:85-100`）：构造 LLM 可见子列表 `llmVisibleMessages`，循环条件改 `tokenizer.estimateTokenCount(llmVisibleMessages) > maxTokens`。**不改 `Tokenizer` 接口**。
- [ ] 每轮删除后重建子列表（消息量级小，可接受）。
- [ ] 保留「删到只剩 system 停止」的兜底（`TokenWindowChatMemory.java:96-98`）。

**验证**：`MessageWindowChatMemoryTest` / `TokenWindowChatMemoryTest` 扩展：note 不占预算、note 原位保留、note 不破坏 tool 配对。

## 4. 测试（对应 Acceptance）

- [ ] **`MessageTest`（新建或扩展）**：`note()` 产出 `llmVisible=false, kind=给定, role="note"`；旧工厂默认 `llmVisible=true, kind=null`；旧 5 参 `of` 仍可用。
- [ ] **`MessageSerializerTest` 扩展**：
  - note 往返：`serialize(note) → deserialize` 字段全保留。
  - 旧数据兼容：手工构造不含 `llmVisible` 的 JSON → `deserialize` 得 `llmVisible=true`。
- [ ] **`AbstractOpenAILLM` 过滤测试**：注入含 note 的 messages → 断言最终 builder 不含 note。策略：用真实 `DashscopeOpenAILLM`/测试桩，通过子类化或反射捕获 `ChatCompletionCreateParams` 的 messages；或抽 `buildMessageListParams` 为 package-private 便于单测（若当前 private，改 package-private + 放宽可见性是最小改动）。
- [ ] **`MessageWindowChatMemoryTest` 扩展**：
  - `maxMessages=2`，加 user/assistant/note/user → note 不占预算、不被删、user/assistant 正常裁剪。
  - tool 配对保护：`assistant(toolCalls) → note → tool(result)` 序列下裁剪不破坏配对（note 不被删、不阻断配对扫描）。
- [ ] **`TokenWindowChatMemoryTest` 扩展**：note 不计入 token 预算。
- [ ] **回归**：`AgentTest`、`AgentMemoryTest`、`InMemoryChatMemoryStoreTest`、`MysqlChatMemoryStoreTest`、`PostgresChatMemoryStoreTest` 全绿。

## 5. 示例（Acceptance：chain-example）

- [ ] 新增 `chain-example/.../MessageLayeringExample.java`：
  - 构建含 `Message.note("status", "已读取文件 X")` 的对话；
  - 配合一个 `ToolInterceptor`（兄弟任务成果）在工具调用前后注入 note；
  - 跑 `Agent.run`，打印「LLM 实际看到的请求（不含 note，可由日志或回调体现）」与「`memory.messages()` 最终列表（含 note）」；
  - 控制台输出明确标注分层效果。

## 6. 文档

- [ ] 若 `design.md` 有未决项回填；更新 PRD Acceptance 勾选。
- [ ] （可选）在 `Message.note` 的 Javadoc 引用本任务 design。

## 7. 回滚

- 全部加性改动 → `git revert` 即可。
- **注意**：序列化兼容是单向的。若已写入含 `llmVisible/kind` 的 note 数据后回滚到旧版本，旧 `deserialize` 会忽略 `llmVisible` 字段，但 note 的 `role="note"` 在旧 provider switch 里命中 `default: throw`。回滚前须清理 `chat_memory_message` 中 `role='note'` 的行：
  ```sql
  DELETE FROM chat_memory_message WHERE role = 'note';
  ```
  旧 LLM 消息（role=system/user/assistant/tool）含 `llmVisible` 字段的，旧版本忽略该字段，无影响。

## 8. 验证总命令

```bash
mvn -q -pl chain test                                    # 核心模块全测
mvn -q -pl chain-mysql test                              # store 往返（若配了 DB）
mvn -q -pl chain-example compile                         # 示例编译
mvn -q -pl chain,chain-example -am package               # 整体打包
```

## 9. Done 定义

- design.md §10 所有 Acceptance 条款对应的测试通过；
- 现有测试全绿（R6）；
- `MessageLayeringExample` 可运行并演示分层；
- 序列化旧数据兼容用例通过。
