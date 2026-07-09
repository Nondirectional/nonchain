# 统一 ChainCallback 接口设计

## Goal

设计一个统一的 ChainCallback 接口，让用户可以在一个地方订阅 LLM 调用、工具执行、知识检索、Graph 工作流等所有组件的生命周期事件，用于可观测性（日志/指标/追踪）和调试。

## Decision (ADR-lite)

**Context**: 项目现有 3 种互不关联的回调机制（Agent.logger、Graph.onEvent、LLM streaming），缺乏统一的可观测性入口。
**Decision**:
1. 设计 ChainCallback 接口，覆盖 LLM/Tool/Retrieval/Graph 四大组件的 Start/Complete/Error 生命周期
2. 使用 ChainContext 共享上下文，支持 traceId 关联同一次 Agent 迭代中的 LLM+Tool 调用
3. CompositeCallback 支持多订阅者
4. 同时支持 Builder 直接传入和 ChainContext 注入两种注册方式
5. 直接移除 Agent.logger(Consumer<String>)，由 ChainCallback 替代

**Consequences**: Agent API 有 breaking change（移除 logger），但统一回调体系带来的可观测性收益远大于迁移成本。

## Requirements

* ChainCallback 接口定义，覆盖 LLM/Tool/Retrieval/Graph 四大组件
* 每种组件有 Start/Complete/Error 三个生命周期钩子（Graph 复用 onGraphEvent）
* Complete 事件包含计时信息（延迟/耗时毫秒数）
* LLM Complete 事件包含 token 用量（promptTokens, completionTokens, totalTokens）
* Tool Complete 事件包含执行耗时
* Retrieval Complete 事件包含命中数和耗时
* Graph 事件复用现有 GraphEvent
* 所有事件携带 traceId（nullable），通过 ThreadLocal 自动关联
* 支持多个 ChainCallback 订阅者（CompositeCallback）
* 回调异常不中断业务逻辑（CompositeCallback 内部 try-catch）
* 回调是同步的，在调用线程中执行
* 移除 Agent.logger(Consumer<String>)
* Graph.Builder.onEvent(Consumer<GraphEvent>) 保留但内部桥接到 ChainCallback

## Acceptance Criteria

* [ ] ChainCallback 接口定义完整，所有方法有 default no-op 实现
* [ ] 各事件对象（LlmStartEvent, LlmCompleteEvent 等）为不可变类，遵循 Builder 模式
* [ ] ChainContext 类：持有 callback，可注入到各组件
* [ ] ChainTrace 工具类：ThreadLocal 管理 traceId，Agent.run() 自动设置/清理
* [ ] CompositeCallback 实现多订阅者，每个回调异常独立捕获
* [ ] DashscopeLLM 在所有 chat/streamChat 方法中触发 LLM 回调（含计时和 token 用量）
* [ ] ToolRegistry.execute() 中触发 Tool 回调（含计时）
* [ ] Agent 在 runWithLoop 中设置 traceId，触发 Agent 级别事件
* [ ] ElasticsearchKnowledgeStore.search() 中触发 Retrieval 回调（含命中数和耗时）
* [ ] Graph 通过 onGraphEvent 桥接到 ChainCallback
* [ ] 回调异常不中断业务逻辑
* [ ] 各组件 Builder 同时支持 .callback() 和 .chainContext() 注册
* [ ] 移除 Agent.logger 字段和相关代码
* [ ] 现有测试通过
* [ ] 新增 ChainCallback 集成测试

## Definition of Done

* 核心模块 (chain) 中添加 ChainCallback 接口和事件模型
* 各组件集成回调触发
* 现有测试通过（向后兼容）
* 新增 ChainCallback 集成测试
* Javadoc 完整，包含使用示例

## Out of Scope

* 异步回调 / 事件总线
* 分布式追踪（OpenTelemetry 集成）
* 持久化事件存储
* Web UI 监控面板
* 性能指标聚合（Prometheus/Micrometer）
* Embedding 组件的回调（可后续扩展）

## Technical Approach

### 类图

```
com.non.chain.callback/
├── ChainCallback.java          (接口，10 个 default 方法)
├── CompositeCallback.java      (多订阅者组合)
├── ChainContext.java            (共享上下文：callback)
├── ChainTrace.java              (ThreadLocal traceId 管理)
└── event/
    ├── LlmStartEvent.java       (messages, tools, outputFormat, traceId)
    ├── LlmCompleteEvent.java    (chatResult, tokenUsage, latencyMs, traceId)
    ├── LlmErrorEvent.java       (messages, error, latencyMs, traceId)
    ├── ToolStartEvent.java      (toolCall, traceId)
    ├── ToolCompleteEvent.java   (toolCallId, toolName, result, latencyMs, traceId)
    ├── ToolErrorEvent.java      (toolCallId, toolName, arguments, error, latencyMs, traceId)
    ├── RetrievalStartEvent.java (searchRequest, traceId)
    ├── RetrievalCompleteEvent.java (response, hitCount, latencyMs, traceId)
    └── RetrievalErrorEvent.java (searchRequest, error, latencyMs, traceId)
```

### ChainCallback 接口

```java
public interface ChainCallback {
    // LLM 生命周期
    default void onLlmStart(LlmStartEvent event) {}
    default void onLlmComplete(LlmCompleteEvent event) {}
    default void onLlmError(LlmErrorEvent event) {}

    // Tool 生命周期
    default void onToolStart(ToolStartEvent event) {}
    default void onToolComplete(ToolCompleteEvent event) {}
    default void onToolError(ToolErrorEvent event) {}

    // Retrieval 生命周期
    default void onRetrievalStart(RetrievalStartEvent event) {}
    default void onRetrievalComplete(RetrievalCompleteEvent event) {}
    default void onRetrievalError(RetrievalErrorEvent event) {}

    // Graph 事件（复用现有 GraphEvent）
    default void onGraphEvent(GraphEvent event) {}
}
```

### traceId 关联机制

```java
// ChainTrace — ThreadLocal 管理
public final class ChainTrace {
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    public static void set(String traceId) { TRACE_ID.set(traceId); }
    public static String get() { return TRACE_ID.get(); }
    public static void clear() { TRACE_ID.remove(); }
    public static String generate() { return UUID.randomUUID().toString(); }
}

// Agent.runWithLoop() 中：
String traceId = ChainTrace.generate();
ChainTrace.set(traceId);
try {
    // LLM 和 Tool 调用自动携带 traceId
    ...
} finally {
    ChainTrace.clear();
}
```

### 注册方式

```java
// 方式1: Builder 直接传入
Agent agent = Agent.builder(llm, registry)
    .callback(myCallback)
    .build();

// 方式2: ChainContext 注入
ChainContext ctx = ChainContext.builder()
    .callback(myCallback)
    .build();
Agent agent = Agent.builder(llm, registry)
    .chainContext(ctx)
    .build();
```

### 多订阅者

```java
ChainCallback multi = CompositeCallback.of(
    new LoggingCallback(),
    new MetricsCallback()
);
Agent agent = Agent.builder(llm, registry)
    .callback(multi)
    .build();
```

### 组件集成要点

* **DashscopeLLM**: 在 doChat() 核心方法中触发回调（所有 8 个 chat/streamChat 重载最终调用 doChat）
* **ToolRegistry**: execute() 方法入口触发 Start，成功后 Complete，异常时 Error
* **Agent**: runWithLoop 中设置 traceId，每次迭代触发 LLM+Tool 事件（通过组件自身的回调）
* **Graph**: 现有 onEvent Consumer 桥接到 ChainCallback.onGraphEvent
* **KnowledgeStore**: search() 方法入口触发，利用已有的 tookMs 信息

## Technical Notes

### 关键文件
* `chain/src/main/java/com/non/chain/provider/LLM.java` — LLM 接口
* `chain/src/main/java/com/non/chain/provider/DashscopeLLM.java` — LLM 实现（doChat 是核心方法）
* `chain/src/main/java/com/non/chain/tool/ToolRegistry.java` — 工具注册与执行
* `chain/src/main/java/com/non/chain/agent/Agent.java` — Agent 循环
* `chain/src/main/java/com/non/chain/flow/Graph.java` — Graph 工作流
* `chain/src/main/java/com/non/chain/flow/GraphEvent.java` — 现有事件模型
* `chain/src/main/java/com/non/chain/knowledge/KnowledgeStore.java` — 检索接口
* `chain/src/main/java/com/non/chain/knowledge/ElasticsearchKnowledgeStore.java` — ES 检索实现

### 设计参考
* GraphEvent 的 Type enum + 静态工厂方法是项目内成熟的事件模型，新事件类参考其风格
* DashscopeLLM 的 doChat() 是所有 chat 重载的最终入口，只需在此处植入回调
* ToolRegistry.execute() 是单一入口，容易植入
* TokenUsage 信息从 OpenAI SDK 的 ChatCompletion 对象中提取（getUsage()）

## Implementation Plan

* PR1: ChainCallback 接口 + 事件模型 + ChainContext + ChainTrace + CompositeCallback（纯新增，不改现有代码）
* PR2: 集成到 DashscopeLLM + ToolRegistry（核心组件植入回调）
* PR3: 集成到 Agent（traceId + 移除 logger）+ Graph 桥接 + KnowledgeStore
* PR4: 集成测试 + Javadoc
