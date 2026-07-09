# Graph 引擎层事件回调

## Goal

在 Graph 引擎的 `run()` while 循环中添加结构化事件回调机制，使调用方能够监听和响应图的执行过程。

## Requirements

* 新建 `GraphEvent` record 类，包含事件类型枚举 `Type`（GRAPH_START, NODE_START, NODE_END, GRAPH_END）
* `Graph.Builder` 添加 `onEvent(Consumer<GraphEvent>)` 方法
* 在 `run()` 中触发四种事件：
  - `GRAPH_START`：while 循环前，携带初始 state
  - `NODE_START`：每个节点执行前，携带节点名和当前 state
  - `NODE_END`：每个节点执行后，携带节点名和执行后 state
  - `GRAPH_END`：while 循环后，携带最终 state 和 executedNodes
* 回调为可选设置，不设则完全静默（与当前行为一致）

## Acceptance Criteria

* [ ] `GraphEvent` record + `Type` 枚举创建完成
* [ ] `Graph.Builder.onEvent()` 方法可用
* [ ] 四种事件类型在正确的时机触发
* [ ] 不设置回调时 Graph 行为与当前完全一致
* [ ] 添加 Graph 单元测试覆盖事件回调
* [ ] 更新示例代码展示回调用法

## Definition of Done

* Tests added/updated
* Lint / typecheck / CI green
* Example code updated

## Technical Approach

### 核心设计

```java
// GraphEvent.java
public record GraphEvent(
    Type type,
    String node,              // null for GRAPH_START/GRAPH_END
    State state,
    List<String> executedNodes // only for GRAPH_END, else null
) {
    public enum Type { GRAPH_START, NODE_START, NODE_END, GRAPH_END }
}
```

### Graph.run() 修改点

```
run(State initialState):
  emit(GRAPH_START, null, current, null)     // ← 新增
  while (nextNode != null && !END):
    emit(NODE_START, nextNode, current, null) // ← 新增
    current = node.apply(current)
    emit(NODE_END, nextNode, current, null)   // ← 新增
    ... 记录 + 路由 ...
  emit(GRAPH_END, null, current, executedNodes) // ← 新增
  return GraphResult
```

### 文件变更清单

1. **新建** `chain/src/main/java/com/non/chain/flow/GraphEvent.java`
2. **修改** `chain/src/main/java/com/non/chain/flow/Graph.java`（Builder + run）
3. **新建** `chain/src/test/java/com/non/chain/flow/GraphTest.java`
4. **修改** example 代码展示用法

## Decision (ADR-lite)

**Context**: Graph 引擎缺乏可观测性，调用方无法监听执行过程
**Decision**: 采用 `Consumer<GraphEvent>` 结构化事件回调（4 种事件类型）
**Consequences**: 调用方可编程响应事件；单一消费者设计保持简单；未来可按需扩展事件类型

## Out of Scope

* Agent 层的事件回调改造
* 多监听器/事件总线
* 流式/异步事件
* 节点执行异常事件
* 持久化事件日志

## Technical Notes

* 关键文件：`chain/src/main/java/com/non/chain/flow/Graph.java`（run 在 30-58 行，Builder 在 64-98 行）
* 参考：`Agent.java` logger 模式、`LLM.java` Consumer 回调模式
* 项目规范：库代码无日志框架，回调是纯 Java 函数式接口
