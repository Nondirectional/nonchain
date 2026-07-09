# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

This project provides optional database support for conversation memory persistence. The core library (`chain`) has no database dependency — persistence is abstracted behind the `ChatMemoryStore` interface, with implementations in separate modules.

**Architecture**: Interface in core → Implementations in separate Maven modules.

---

## Conversation Memory Persistence

### Core abstractions (no database dependency)

- **`ChatMemory`** (`com.non.chain.memory`) — Strategy interface for conversation history management (sliding window, token pruning)
- **`ChatMemoryStore`** (`com.non.chain.memory`) — Storage interface: `getMessages` / `updateMessages` / `deleteMessages`
- **`InMemoryChatMemoryStore`** — Default in-memory implementation, no external dependencies

### Module: chain-mysql

- **`MysqlChatMemoryStore`** (`com.non.chain.mysql`) — MySQL persistence via JDBC + DataSource
- **`MessageSerializer`** (`com.non.chain.mysql`) — Message ↔ JSON serialization for storage
- **Schema**: `chat_memory_message` table (conversation_id, message_order, role, content_json)

---

## When Adding a New Storage Backend

1. **Create a new Maven module** — e.g., `chain-postgres`, `chain-mongo`
2. **Implement `ChatMemoryStore`** — `getMessages`, `updateMessages`, `deleteMessages`
3. **Handle serialization** — Messages are immutable value objects; use `Message.of()` factory for deserialization
4. **Keep it out of core models** — `Message`, `ChatResult`, `ToolCall` remain plain Java objects
5. **Use the `provider/` pattern as a reference** — Abstract behind an interface, provide implementations
6. **Do not couple with the workflow engine** — Persistence should be opt-in, not required for Graph execution

---

## State Management (Non-Database)

- **`State`** (`com.non.chain.flow.State`) — A mutable key-value map (`Map<String, Object>`) with message history (`List<Message>`), used by the workflow engine to pass data between nodes.
- State is not persisted between runs.
- Copy constructor `new State(other)` is used for history snapshots.
