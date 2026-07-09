# 实现PostgresChatMemoryStore

## Goal

新建 `chain-postgres` 模块，提供基于 PostgreSQL 的 `ChatMemoryStore` 持久化实现，与现有 `chain-mysql` 模块保持一致的架构模式。

## What I already know

* `ChatMemoryStore` 接口定义了 3 个方法：`getMessages`、`updateMessages`、`deleteMessages`
* `chain-mysql` 是参考模板：使用 `javax.sql.DataSource` + 纯 JDBC + Jackson 序列化
* `MysqlChatMemoryStore` 的 SQL 模式：`chat_memory_message` 表，列包括 `id`、`conversation_id`、`message_order`、`role`、`content_json`、`created_at`
* `MessageSerializer` 使用 Jackson 做 Message 对象的 JSON 序列化/反序列化
* 测试使用 H2 内存数据库模拟
* 不引入连接池依赖，由消费者提供 `DataSource`

## Requirements

* 将 `MessageSerializer` 从 `chain-mysql` 提取到 `chain` core 模块（`com.non.chain.memory` 包）
* `chain-mysql` 改为依赖 core 中的 `MessageSerializer`，删除本地副本
* 新建 `chain-postgres` Maven 模块
* 实现 `PostgresChatMemoryStore`，接受 `javax.sql.DataSource`
* 提供 PostgreSQL 适配的建表 DDL
* 编写单元测试（使用 H2 的 PostgreSQL 兼容模式）

## Acceptance Criteria

* [ ] `MessageSerializer` 移至 `chain` core 的 `com.non.chain.memory` 包
* [ ] `chain-mysql` 中删除 `MessageSerializer`，改为 import core 版本
* [ ] `PostgresChatMemoryStore` 实现了 `ChatMemoryStore` 接口
* [ ] SQL DDL 文件使用 PostgreSQL 语法（`BIGSERIAL`、无 `ENGINE` 子句等）
* [ ] 单元测试通过
* [ ] 根 POM 中添加 `chain-postgres` 模块
* [ ] `chain` core 的 `pom.xml` 添加 `jackson-databind` 依赖

## Definition of Done

* Tests added/updated
* Maven build 通过
* 根 POM modules 列表更新

## Out of Scope

* 连接池集成
* PostgreSQL 驱动依赖（由消费者提供）

## Decision (ADR-lite)

**Context**: `MessageSerializer` 被 `chain-mysql` 和 `chain-postgres` 共同需要。
**Decision**: 提取到 `chain` core 模块，两个模块共用同一份代码。
**Consequences**: `chain-mysql` 需要小范围改动（删除本地 MessageSerializer，改为 import core 版本）。core 模块新增 `jackson-databind` 依赖。

## Technical Approach

1. 将 `MessageSerializer` 从 `chain-mysql` 移至 `chain/src/main/java/com/non/chain/memory/`
2. `chain/pom.xml` 添加 `jackson-databind` 依赖
3. `chain-mysql` 删除 `MessageSerializer.java`，更新 import
4. 新建 `chain-postgres` 模块，参照 `chain-mysql` 结构
5. 实现 `PostgresChatMemoryStore`（SQL 语法适配 PostgreSQL）
6. 编写 DDL 和测试
7. 更新根 POM

## Technical Notes

* 参考文件：`chain-mysql/src/main/java/com/non/chain/mysql/MysqlChatMemoryStore.java`
* 参考文件：`chain-mysql/src/main/java/com/non/chain/mysql/MessageSerializer.java`
* 参考文件：`chain-mysql/src/main/resources/chat_memory_message.sql`
* 参考文件：`chain-mysql/pom.xml`
