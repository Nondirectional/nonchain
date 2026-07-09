# GraphEvent 支持 NODE_ERROR / GRAPH_ERROR

## Goal

为 Graph workflow 引擎添加错误事件类型（NODE_ERROR / GRAPH_ERROR），使调用方能够通过 onEvent 回调感知并处理节点执行异常，而非让异常直接传播导致 GRAPH_END 事件丢失。

## Requirements

* 新增 `NODE_ERROR` 事件类型（节点执行或边路由异常时发出）
* 新增 `GRAPH_ERROR` 事件类型（节点/边异常后、GRAPH_END 前发出）
* GraphEvent 新增 `String error` 字段，携带异常消息
* 异常时事件序列：NODE_ERROR → GRAPH_ERROR → GRAPH_END
* `edge.route()` 异常也统一走 NODE_ERROR → GRAPH_ERROR → GRAPH_END 流程
* 异常发出事件后仍向上传播（throw），调用方可通过事件和异常双重感知

## Acceptance Criteria

* [ ] 节点执行异常时发出 NODE_ERROR 事件，error 包含异常消息
* [ ] edge.route() 异常时发出 NODE_ERROR 事件（node 为当前节点名）
* [ ] NODE_ERROR 后发出 GRAPH_ERROR 事件
* [ ] 异常场景下 GRAPH_END 仍然正常发出
* [ ] GRAPH_END 事件的 executedNodes 仅包含成功执行的节点
* [ ] 异常仍然向上 throw
* [ ] 现有测试不受影响
* [ ] 新增测试：节点执行异常的事件序列验证
* [ ] 新增测试：edge.route() 异常的事件序列验证
* [ ] 新增测试：无回调时异常仍正常 throw

## Definition of Done

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs updated (workflow.md)

## Technical Approach

### 1. GraphEvent.java

* enum Type 新增 `NODE_ERROR`, `GRAPH_ERROR`
* 新增 `String error` 字段（可为 null）
* 构造函数扩展：`GraphEvent(Type, String node, State state, List<String> executedNodes, String error)`
* 新增工厂方法 `GraphEvent.nodeError(String node, State state, String error)` 和 `GraphEvent.graphError(State state, String error)`
* 现有工厂方法保持兼容（error 传 null）

### 2. Graph.java run()

用 try/catch 包裹 while 循环体：
```
try {
    // NODE_START
    // node.apply()
    // NODE_END
    // edge.route()
} catch (Exception e) {
    emit(NODE_ERROR: node, state, e.getMessage())
    emit(GRAPH_ERROR: state, e.getMessage())
    emit(GRAPH_END: state, executedNodes)
    throw e;
}
```

### 3. 测试

* `shouldEmitErrorEventsOnNodeFailure()` - 节点抛异常，验证事件序列
* `shouldEmitErrorEventsOnEdgeRouteFailure()` - edge.route() 抛异常
* `shouldPropagateExceptionAfterErrorEvents()` - 验证异常仍 throw
* `shouldThrowWithoutCallbackOnError()` - 无回调时异常行为

## Decision (ADR-lite)

**Context**: Graph 引擎无错误处理，节点异常导致 GRAPH_END 丢失，监听方无法感知失败
**Decision**: NODE_ERROR → GRAPH_ERROR → GRAPH_END 三事件序列 + String error 字段 + 异常仍 throw
**Consequences**: 回调方可在 switch 中完整处理错误场景；异常仍然传播不改变现有调用约定；GRAPH_END 始终发出保证生命周期完整

## Out of Scope

* 重试机制
* fallback 主备切换
* 超时控制
* GraphResult 改造（不新增 error 字段）

## Technical Notes

* GraphEvent.java: 事件定义，4 个 enum + 4 字段 + 2 工厂方法
* Graph.java:33-70: run() 方法，无 try/catch
* Graph.java:72-76: emit() 空安全
* GraphTest.java: 4 个测试，无错误场景
* TODO.md:18 明确标记 onError 缺失
