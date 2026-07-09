# 对话记忆 (Memory) 模块设计

## Goal

为 nonchain 框架添加对话记忆模块，使 Agent 支持跨调用的多轮对话上下文保持。提供 ChatMemory（策略层）+ ChatMemoryStore（存储层）的分离设计，内置滑动窗口和 Token 裁剪策略，支持内存和 MySQL 两种持久化实现。

## Requirements

* ChatMemory 接口 — 策略层，管理对话历史的裁剪逻辑
* ChatMemoryStore 接口 — 存储层，抽象消息的读写和删除
* MessageWindowChatMemory — 滑动窗口策略，保留最近 N 条消息
* TokenWindowChatMemory — Token 裁剪策略，按 token 数量限制上下文
* InMemoryChatMemoryStore — 内存存储实现
* MysqlChatMemoryStore — MySQL 存储实现（chain-mysql 子模块）
* Tokenizer 接口 — Token 计数抽象，内置 jtokkit 实现
* Agent.builder().memory() — Agent 集成
* conversationId 多会话支持
* SystemMessage 保护（永不裁剪）
* 工具消息配对保护（assistant + tool result 成对保留/删除）

## Acceptance Criteria

* [ ] ChatMemory 接口定义清晰：conversationId / add / addAll / messages / clear
* [ ] ChatMemoryStore 接口定义清晰：getMessages / updateMessages / deleteMessages
* [ ] MessageWindowChatMemory 通过单元测试（裁剪逻辑、SystemMessage 保护、工具配对）
* [ ] TokenWindowChatMemory 通过单元测试（token 裁剪、SystemMessage 保护）
* [ ] InMemoryChatMemoryStore 通过单元测试
* [ ] MysqlChatMemoryStore 通过测试（消息序列化/反序列化、多会话隔离）
* [ ] Agent.builder().memory(chatMemory) 集成后，run(String) 能保持多轮对话
* [ ] Example 类展示完整使用方式

## Definition of Done

* 单元测试覆盖所有核心路径
* Agent 现有行为不被破坏（无 Memory 时逻辑不变）
* TODO.md 更新此项为已完成
* chain-mysql 模块添加到根 POM

## Technical Approach

### 核心接口

```java
// com.non.chain.memory.ChatMemory — 策略层
public interface ChatMemory {
    String conversationId();
    void add(Message message);
    void addAll(List<Message> messages);
    List<Message> messages();
    void clear();
}

// com.non.chain.memory.ChatMemoryStore — 存储层
public interface ChatMemoryStore {
    List<Message> getMessages(String conversationId);
    void updateMessages(String conversationId, List<Message> messages);
    void deleteMessages(String conversationId);
}
```

### 策略实现

**MessageWindowChatMemory**:
- 内部组合 ChatMemoryStore
- 构造参数：store, maxMessages, conversationId
- add() 后检查消息数，超出则从最老的非 SystemMessage 开始删除
- 删除时保证工具消息配对完整性

**TokenWindowChatMemory**:
- 内部组合 ChatMemoryStore + Tokenizer
- 构造参数：store, tokenizer, maxTokens, conversationId
- add() 后计算总 token 数，超出则裁剪最老的消息
- SystemMessage 永不裁剪

### 存储实现

**InMemoryChatMemoryStore** (`com.non.chain.memory`):
- 内部 ConcurrentHashMap<String, List<Message>>
- 纯内存，无外部依赖

**MysqlChatMemoryStore** (`com.non.chain.mysql`):
- 原生 JDBC + DataSource
- 表结构：

```sql
CREATE TABLE chat_memory_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    message_order INT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conv_order (conversation_id, message_order)
);
```

- updateMessages: 删除旧消息 → 批量插入新消息（事务保证）
- JSON 序列化使用 Jackson（项目已有依赖）

### Agent 集成

```java
// Agent.Builder 新增
public Builder memory(ChatMemory memory) {
    this.memory = memory;
    return this;
}

// run(String query) 修改
public ChatResult run(String query) {
    if (memory != null) {
        memory.add(Message.user(query));
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        int historyEnd = messages.size() + memory.messages().size();
        messages.addAll(memory.messages());
        ChatResult result = runWithLoop(messages);
        // 同步新消息（assistant + tool results）到 memory
        for (int i = historyEnd; i < messages.size(); i++) {
            memory.add(messages.get(i));
        }
        return result;
    }
    // 原有逻辑不变
}
```

### 模块结构

```
chain/                           # 核心模块
  com.non.chain.memory           # 新增包
    ChatMemory.java              # 策略接口
    ChatMemoryStore.java         # 存储接口
    MessageWindowChatMemory.java # 滑动窗口策略
    TokenWindowChatMemory.java   # Token 裁剪策略
    InMemoryChatMemoryStore.java # 内存存储

chain-mysql/                     # 新增子模块
  com.non.chain.mysql            # 新增包
    MysqlChatMemoryStore.java    # MySQL 存储
    MessageSerializer.java       # 消息 JSON 序列化
```

## Decision (ADR-lite)

### Decision 1: 策略 + 存储分离
**Context**: 需要确定 Memory 模块的核心抽象层设计
**Decision**: ChatMemory（策略接口）+ ChatMemoryStore（存储接口）。Agent 持有 ChatMemory，ChatMemory 内部组合 ChatMemoryStore
**Consequences**: 新增存储后端只需实现 ChatMemoryStore，新增裁剪策略只需实现 ChatMemory

### Decision 2: 模块组织
**Context**: 模块组织和 MySQL 访问方式
**Decision**: 接口 + 策略 + InMemory 放 chain 核心。MysqlChatMemoryStore 放新增 chain-mysql 子模块。使用原生 JDBC + DataSource
**Consequences**: chain 核心不引入 MySQL 依赖；新存储后端只需新增子模块

### Decision 3: Token 计数
**Context**: TokenWindowChatMemory 需要 token 计数能力
**Decision**: chain 核心新增 Tokenizer 接口 + jtokkit 内置实现
**Consequences**: jtokkit 依赖提升到 chain 核心

### Decision 4: 消息序列化
**Context**: MySQL 如何存储 Message
**Decision**: role + content_json 两列。JSON 存储完整 Message
**Consequences**: Message 结构变化只影响序列化逻辑

### Decision 5: Agent 集成
**Context**: Agent 的 run(String) 和 run(List<Message>) 如何与 Memory 集成
**Decision**: 仅 run(String) 使用 Memory。run(List<Message>) 保持原有行为不变
**Consequences**: 两个入口语义清晰，互不干扰

## Out of Scope

* 摘要记忆策略（需额外 LLM 调用，复杂度高，后续考虑）
* 向量检索式记忆（RAG + Memory 融合）
* Redis / Postgres / Mongo 持久化实现（接口可扩展，本次不实现）
* Memory 操作的 ChainCallback 可观测性
* 线程安全（Memory 实例不跨线程共享）

## Technical Notes

* Message 是不可变的，Memory 通过列表管理消息集合
* jtokkit 需从 chain-document 提升到 chain 核心（或 chain-core 直接引入）
- Agent 的 runWithLoop 在内部修改 messages 列表（add assistant + tool results），Memory 集成需正确同步这些新增消息
- MySQL content_json 使用 Jackson 序列化（chain-elasticsearch 模块已有 jackson-databind 依赖，chain 核心需评估是否引入或让 chain-mysql 单独引入）
