# 应用层消息与 LLM 消息分层 — Design

> 父任务：`06-24-agent-control-flow-extensibility` · 本任务 PRD：`prd.md`
> 设计阶段产物，实现清单见 `implement.md`。

## 1. 设计目标回顾

允许应用产生**不进 LLM 上下文**的消息（UI 状态 / artifact / 通知），它们：
- 进 `messages` 列表、能持久化、UI 可重放；
- 在 **LLM 边界被剥离**，不进 provider 请求；
- 不破坏现有 tool 配对裁剪保护；
- 对现有 `Message` API 与 agent 行为**零破坏**。

## 2. 关键决策（已与需求方逐条确认）

| # | 决策点 | 选定方案 |
|---|--------|----------|
| R1 | 标记机制形态 | **在 `Message` 上加字段**：`boolean llmVisible`（默认 `true`）+ `String kind`（默认 `null`） |
| R2 | 过滤位置 | **边界单点**：`AbstractOpenAILLM.buildMessageListParams` 入口处过滤；静默（遵循项目无日志框架约定），`default: throw` 兜底作唯一可观测信号 |
| R3 | 持久化 | 复用 `MessageSerializer`，新增字段写进 `content_json`，**零 DDL** |
| R4 | 裁剪语义 | **非 LLM 消息不占预算、删除时跳过且原位保留** |
| R5 | AgentEvent | **本任务不新增事件**，UI 靠 `onComplete` 后全量列表重放；实时事件留作 future work |

> 下列各节展开每条决策的依据、契约与权衡。所有"为什么"都锚定在代码证据上，遵循 brainstorm 的 Evidence Rule。

## 3. R1 — 标记机制：字段 + kind

### 3.1 数据模型变更

`Message` 是不可变值对象（`Message.java:7-26`：私有构造 + 全 `final` 字段 + 静态工厂）。新增两个 `final` 字段：

```java
private final boolean llmVisible;   // 默认 true；false 表示不进 LLM 上下文
private final String   kind;        // 可选语义标签；null 或 "chat" = 普通 LLM 消息
                                    // 非 LLM 消息给应用自定义标签，如 "note"/"status"/"ui"
```

**默认值约定**：现有所有工厂（`system`/`user`/`assistant`/`assistantWithToolCalls`/`toolResult`）产出的消息 `llmVisible=true, kind=null` —— R6 零破坏。

### 3.2 新增工厂方法（API 表面）

只加，不改：

```java
/** 应用层消息：不进 LLM 上下文，role 固定为 "note"。 */
public static Message note(String kind, String content) { ... }

/** 全参数反序列化用（扩展现有 of(...)，新增两个参数）。 */
public static Message of(String role, String content, List<ContentPart> contentParts,
                         String toolCallId, List<ToolCall> toolCalls,
                         boolean llmVisible, String kind) { ... }
```

**为什么 `note` 的 role 固定 `"note"` 而不是复用 `"user"`**：
- `AbstractOpenAILLM.buildMessageListParams` 的 role `switch`（`AbstractOpenAILLM.java:192-238`）对非 system/user/assistant/tool 的 role 走 `default: throw`。即便过滤失效，`"note"` role 也会在 provider 侧兜底报错而不是把脏数据发给 LLM —— 双保险。
- `ChatMemoryTrimSupport` 的 `isAssistantWithToolCalls`/`isToolMessage`（`ChatMemoryTrimSupport.java:79-87`）只认 `"assistant"`/`"tool"` role，`"note"` role 天然不进 tool 配对分组扫描 —— 从机制上消除 R4 的配对断裂风险（见 §5）。
- 应用层若需要别的语义（artifact/notification），通过 `kind` 区分，role 始终是 `"note"`，保持转换层简单。

**向后兼容的 `Message.of(...)`**：保留旧的 5 参 `of(...)`（内部转调新 7 参，`llmVisible=true, kind=null`）。`MessageSerializer.deserialize` 改用 7 参版本以读回标记。

### 3.3 访问器

```java
public boolean llmVisible() { return llmVisible; }
public String   kind()      { return kind; }
```

### 3.4 为什么不选方案 B（新增 `AppMessage` 类型）

方案 B 要求 `ChatMemory` / `ChatMemoryStore` / `Agent.run(List<...>)` 的签名引入共同基类（如 `MessageEntry`），是一次全库公共 API 迁移，违反父任务原则 #1「increment, not rewrite」和 R6。Java 没有 TS 的 declaration merging，方案 B 拿不到 pi 的 ergonomics，却付出更大的破坏面。字段方案把冲击面收敛到「`Message` 内部 + 一个新工厂 + 序列化器几行」。

## 4. R2 — LLM 边界单点过滤

### 4.1 过滤点

唯一过滤点：`AbstractOpenAILLM.buildMessageListParams`（`AbstractOpenAILLM.java:184`），在进入 role `switch` 之前。

```java
private ChatCompletionCreateParams.Builder buildMessageListParams(List<Message> messages) {
    ChatCompletionCreateParams.Builder builder = ...;
    // —— 新增：边界过滤 llmVisible=false 的应用层消息（静默，遵循项目无日志框架约定）——
    List<Message> llmMessages = new ArrayList<>(messages.size());
    for (Message msg : messages) {
        if (!msg.llmVisible()) { continue; }
        llmMessages.add(msg);
    }
    for (Message msg : llmMessages) {
        switch (msg.role()) { ... }   // 原 switch 不变
    }
    return builder;
}
```

### 4.2 为什么是边界而不是 Agent 调用前

- **覆盖面**：所有 provider（Dashscope / OpenAICompatible / VLLM）都继承 `AbstractOpenAILLM`，单点覆盖 `Agent` / `StreamingAgent` / 直调 `llm.chat(...)` 的所有示例与用户代码。在 `Agent` 内过滤会漏掉直调路径（`FunctionCallExample`、`StreamingChatExample` 等），用户加了 note 后 provider 返回 400（unknown role）。
- **PRD R2 原文**：「过滤发生在 LLM 边界」字面指向 provider 边界。
- **不动各 role 的 switch 翻译**（PRD Out-of-Scope）：过滤在 switch 之前，switch 分支一字不改。

### 4.3 可观测性

- **项目无日志框架**（`logging-guidelines.md`：「This project does not use a logging framework... There is no logging in library code」）。因此过滤在库代码中**静默** —— 不加 slf4j、不加 `System.out`（后者仅限 `example/` 包）。
- **唯一可观测信号**：`switch` 的 `default: throw new IllegalArgumentException("不支持的消息角色: " + msg.role())`（`AbstractOpenAILLM.java:236-237`）原样保留，作 fail-safe。正常路径下 note 被过滤掉，不会触发 throw；若用户手构造 `llmVisible=true` 但 role 非法，仍由 throw 兜底。
- **可观测性如何满足**（PRD R2「避免消息神秘消失难调试」）：由**测试**断言（implement.md §4 的过滤单测）保证语义正确；运行期可观测性留作 future work —— 若未来引入日志框架，可在过滤点加 debug 日志，当前不阻塞 MVP。

## 5. R4 — 裁剪语义：不占预算 + 原位保留

### 5.1 风险锚点（来自代码）

`ChatMemoryTrimSupport` 的 tool 配对扫描对**位置敏感**：
- `countForwardToolGroup`（`ChatMemoryTrimSupport.java:32-42`）从 `assistant(toolCalls)` 向后扫连续 tool 消息；
- `countReversedToolGroup`（`ChatMemoryTrimSupport.java:44-61`）从 tool 向后找配对的 `assistant(toolCalls)`。

如果一条非 LLM 消息被允许进入删除候选并落在 `assistant(toolCalls)` 与其 tool result 之间，扫描会在 note 处中断（`isToolMessage(note)` 为 false）→ tool result 被当孤立消息单独删除 → **破坏配对保护**（PRD R4 红线）。

### 5.2 选定语义（三层）

1. **不计入预算**：
   - `MessageWindowChatMemory.trim`（`MessageWindowChatMemory.java:83-95`）的循环条件从 `messages.size() > maxMessages` 改为「LLM 可见消息数 > maxMessages」。
   - `TokenWindowChatMemory.trim`（`TokenWindowChatMemory.java:85-100`）的 `tokenizer.estimateTokenCount(messages) > maxTokens` 改为只对 LLM 可见子序列计数（见 §5.3 关于 tokenizer 签名的权衡）。
2. **删除时跳过且原位保留**：`findFirstDeletableIndex` 找到的候选若 `llmVisible=false`，跳过该索引往下找下一个可删的 LLM 消息；非 LLM 消息**不删除、不前移**。
3. **配对保护天然成立**：因为非 LLM 消息 role=`"note"`，`isAssistantWithToolCalls`/`isToolMessage` 都不命中，分组扫描只在 LLM 可见消息子序列内运作；又因为非 LLM 消息不在删除候选，`countForwardToolGroup` 即便途经 note 也不会把 note 算进组、删除循环也跳过 note —— 双重隔离。

### 5.3 Token 计数签名权衡

`Tokenizer.estimateTokenCount(List<Message>)` 是既有公共接口。两种实现：
- **(a)** 在 `TokenWindowChatMemory.trim` 内部构造「LLM 可见子列表」传入 —— 不改 `Tokenizer` 接口，但每轮裁剪多一次过滤拷贝（消息量级小，可忽略）。
- **(b)** 给 `Tokenizer` 加重载 `estimateTokenCount(List<Message>, Predicate<Message> filter)` —— 接口扩展，更通用。

**选 (a)**：最小冲击，`Tokenizer` 接口零改动，符合 R6。子列表构造放在 `trim` 私有方法里。

### 5.4 为什么不让非 LLM 消息占预算

UI 状态（"正在思考""已读文件"）高频且瞬时。若占窗口预算，会挤掉真正的 user/assistant 轮次，导致 LLM 丢失关键上下文 —— 与「分层」初衷相悖。若占预算（方案 B），note 落在 assistant-tool 之间时还会**确实**触发 §5.1 的配对断裂（因为它参与分组扫描又挡在中间）。方案 A 的「跳过+原位」从机制上规避。

## 6. R3 — 持久化（零 DDL 往返）

### 6.1 序列化扩展

`chat_memory_message` 表把整条消息存为 `content_json TEXT`（`chain-mysql/.../chat_memory_message.sql:6`、`chain-postgres/.../chat_memory_message.sql:6`），三种 store（`InMemoryChatMemoryStore` / `MysqlChatMemoryStore` / `PostgresChatMemoryStore`）全部经 `MessageSerializer` 读写 —— **加字段 = 改序列化器，store 与 DDL 全不动**。

`MessageSerializer.serialize`（`MessageSerializer.java:28-68`）追加：
```java
node.put("llmVisible", message.llmVisible());           // 总是写（布尔，省去 has 判断）
if (message.kind() != null) node.put("kind", message.kind());
```

`MessageSerializer.deserialize`（`MessageSerializer.java:70-111`）追加：
```java
boolean llmVisible = node.has("llmVisible") ? node.get("llmVisible").asBoolean() : true; // 旧数据兜底 true
String kind = node.has("kind") ? node.get("kind").asText() : null;
```
最后 `return Message.of(role, content, contentParts, toolCallId, toolCalls, llmVisible, kind);`（7 参版本）。

### 6.2 旧数据兼容

- 旧 `content_json` 不含 `llmVisible` 字段 → `deserialize` 默认 `true`，`kind=null` → 行为与现状完全一致。
- 旧 5 参 `Message.of(...)` 保留（转调 7 参，默认值），任何手写 `Message.of(...)` 的用户代码不破。

## 7. R5 — 事件层：本任务不新增（已确认）

- `AgentEvent`（`AgentEvent.java`）是瞬时事件流；非 LLM 消息是持久态。两者语义不同。
- 应用层**主动**加 note 时自己知晓（在 `ToolInterceptor` / 业务逻辑里加），不需要 Agent 再发一次事件 —— 冗余回调。
- MVP 阶段 UI 重放走 `onComplete` 后的全量 `messages` 列表，足够演示分层语义。
- PRD Out-of-Scope 明确排除「完整的 artifact/notification 类型族」。新增 `onNonLlmMessage` 事件即半个 notification 系统，属下个任务。
- **Future work 记录**（写入本节备查，不在本任务实现）：若后续需要实时非 LLM 事件，可在 `Agent` 检测到 `llmVisible=false` 消息新增时发 `onNonLlmMessage(NoteEvent)`，载荷携带 `kind` 与 `content`。

## 8. 数据流（含 note 的完整一轮）

```
应用/ToolInterceptor 注入 note:
  Message.note("status", "已读取文件 X")   → llmVisible=false, role="note", kind="status"

memory.add(note):
  进 messages 列表 → trim 跳过(不占预算、原位保留) → store 持久化(含 llmVisible/kind)

Agent.run → 拼装 messages(含 note) → llm.chat(messages):
  AbstractOpenAILLM.buildMessageListParams:
    静默过滤掉 note → provider 请求 payload 只含 system/user/assistant/tool
    （库代码无日志；default: throw 仅在 role 非法时触发）

UI 重放:
  memory.messages() → 含 note → UI 按 kind 渲染状态条
```

## 9. 影响面与文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `chain/.../Message.java` | 加字段+工厂+访问器 | `llmVisible`/`kind`/`note()`/`of(...7参)`/`llmVisible()`/`kind()` |
| `chain/.../memory/MessageSerializer.java` | 扩展读写 | serialize 加两字段；deserialize 读回 + 转调 7 参 `of` |
| `chain/.../provider/AbstractOpenAILLM.java` | 边界过滤 | `buildMessageListParams` 入口过滤 + debug 日志 |
| `chain/.../memory/ChatMemoryTrimSupport.java` | 跳过删除 | `findFirstDeletableIndex` 跳过 `llmVisible=false` |
| `chain/.../memory/MessageWindowChatMemory.java` | 预算口径 | `trim` 循环条件改为 LLM 可见计数 |
| `chain/.../memory/TokenWindowChatMemory.java` | 预算口径 | `trim` 对 LLM 可见子序列计 token |
| `chain-example/.../MessageLayeringExample.java` | 新增示例 | 演示 note 进 transcript 不进 LLM |
| 测试（见 implement.md §4） | 新增+扩展 | 过滤/往返/裁剪/配对 |

**不动**：`ChatMemory`/`ChatMemoryStore` 接口、三种 store 实现、provider 的 role switch 分支、`Agent` loop、`AgentEvent`、DB schema。

## 10. 验收映射（Acceptance → 设计条款）

| Acceptance | 设计条款 |
|------------|----------|
| 标记机制 | §3（字段+kind+`note()` 工厂） |
| 边界单点过滤 + 测试断言 | §4 + implement.md 测试 |
| 持久化往返 + UI 重放 | §6 + 序列化往返测试 |
| 裁剪不破坏 tool 配对 | §5 + 裁剪测试 |
| Message API 零破坏 + 现有测试全绿 | §3.2（旧工厂/旧 of 保留）+ 全量回归 |
| 新增单测 | implement.md §4 |
| chain-example 示例 | §8 + implement.md §3 |
| design.md + implement.md | 本文件 + implement.md |

## 11. 回滚策略

改动均为**加性**（新字段、新工厂、新过滤分支、序列化器加字段），无破坏性变更：
- 回滚 = revert 本次 commit 即可恢复原状。
- 序列化兼容：即使部署了带 `llmVisible/kind` 的新数据后回滚到旧版本，旧 `deserialize` 忽略未知字段（Jackson `readTree` + 显式 `has` 判断），不会报错 —— 但会丢失 note 标记（note 被当成 role=`"note"` 的普通消息，在旧 provider 的 switch 里会 throw）。**因此回滚前需确认无 note 消息残留**，或先清理 `chat_memory_message` 中 role=`"note"` 的行。implement.md §5 会写明这一点。
