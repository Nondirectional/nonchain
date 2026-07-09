# Design — nonchain SubAgent MVP

> 基于 `prd.md` 已锁定决策，定义 nonchain 首个 SubAgent MVP 的公开 API、运行边界、兼容策略和实现落点。

## 1. 设计目标

在不破坏现有 `Agent` / `ToolRegistry` / `Tool` / `Message` / `ChainCallback` 语义的前提下，为 nonchain 增加“委派型子代理”能力：

- 父 Agent 通过 tool calling 自主决定是否委派。
- 子代理作为一等 tool 能力注册在 `ToolRegistry`。
- 父 Agent 默认看到的是独立子代理 tool；必要时可切到显式开启的通用 delegate tool 模式。
- 子代理默认无状态、独立角色、独立工具集、独立拦截器、独立 `maxIterations`。
- 子代理结果按现有工具语义回灌给父 Agent，复用现有多轮循环。

本设计刻意不做多层代理平台，不做递归委派，不做事件嵌套透出，不做手写工具循环完整支持。

## 2. 总体方案

### 2.1 两层职责

SubAgent 能力拆成两层：

1. **注册层：`ToolRegistry`**
   - 负责注册子代理定义。
   - 负责把子代理暴露为 `Tool` schema。
   - 不直接决定父 Agent 最终看到哪一套子代理工具列表。

2. **执行/暴露层：`Agent.Builder` + `Agent`**
   - `Agent.Builder` 决定当前父 Agent 的 SubAgent 暴露模式。
   - `Agent` 在执行工具调用时，识别“这是一个子代理工具”，然后构造子代理运行上下文并执行。
   - 自动裁剪父上下文、父/子回调隔离、软失败回灌，都落在这一层。

这遵循现有项目分层：
- `ToolRegistry` 管“可暴露的工具能力”
- `Agent` 管“LLM + tool 的循环执行”

### 2.2 MVP 支持的两种暴露模式

定义一个小枚举，配置在 `Agent.Builder`：

```java
public enum SubAgentExposureMode {
    DIRECT,    // 默认：每个子代理一个独立 tool
    DELEGATE   // 显式：只暴露一个 delegate_to_subagent(agentName, task)
}
```

契约：
- 默认模式是 `DIRECT`。
- 构建期固定，不支持同一个 `Agent` 在不同 `run(...)` 间切换。
- `DIRECT` 与 `DELEGATE` 默认二选一，不同时暴露。

## 3. 注册层设计

### 3.1 `ToolRegistry.registerSubAgent(...)`

首个版本采用声明式 Builder，而不是直接接收预构建 `Agent`：

```java
ToolRegistry registry = new ToolRegistry();

registry.registerSubAgent("research_agent", "负责调研与归纳")
        .systemPrompt("你是调研代理。优先归纳事实，不编造。")
        .toolRegistry(researchTools)              // 可选
        .maxIterations(3)                         // 可选
        .llm(researchLlm)                         // 可选，默认继承父 LLM
        .contextSelector(selector)                // 可选，子代理级覆盖默认裁剪策略
        .addBeforeToolCall(...)
        .addAfterToolCall(...)
        .build();
```

### 3.2 `SubAgentRegistration` 最小字段

建议新增一个内部不可变定义对象，例如 `SubAgentDefinition`：

```java
final class SubAgentDefinition {
    private final String name;
    private final String description;
    private final String systemPrompt;
    private final ToolRegistry toolRegistry;              // nullable
    private final LLM llmOverride;                        // nullable
    private final Integer maxIterations;                  // nullable
    private final ContextSelector contextSelector;        // nullable
    private final List<BeforeToolCall> beforeInterceptors;
    private final List<AfterToolCall> afterInterceptors;
}
```

约束：
- `name`、`description`、`systemPrompt` 必填。
- `toolRegistry` 非必填；为空表示无工具子代理。
- `llmOverride` 非必填；为空时继承父 Agent 的 `LLM`。
- `maxIterations` 非必填；为空时回退 `Agent.DEFAULT_MAX_ITERATIONS`。
- `contextSelector` 非必填；为空时使用框架默认裁剪策略。

### 3.3 `ToolRegistry` 数据结构扩展

现有 `ToolRegistry` 只维护普通 `ToolEntry`。MVP 建议新增并行存储：

```java
private final Map<String, ToolEntry> entries = new ConcurrentHashMap<>();
private final Map<String, SubAgentDefinition> subAgents = new ConcurrentHashMap<>();
```

并新增：

```java
public SubAgentRegistration registerSubAgent(String name, String description)
public boolean hasSubAgent(String name)
public SubAgentDefinition getSubAgent(String name)
List<Tool> getSubAgentTools()
Tool getDelegateTool()
```

注意：
- `getTools()` 现有零参方法不改，继续只返回普通工具 + 默认直出子代理工具，避免破坏广泛现有用法。
- 针对暴露模式切换，新增面向 `Agent` 的内部/包级方法，如：

```java
List<Tool> getTools(SubAgentExposureMode mode)
```

或者拆分成：

```java
List<Tool> getRegularTools()
List<Tool> getDirectSubAgentTools()
Optional<Tool> getDelegateSubAgentTool()
```

推荐后者，避免让 `ToolRegistry` 带过强模式意识。

## 4. 工具 schema 设计

### 4.1 独立子代理 tool

每个子代理默认暴露一个 tool，schema 最小化：

```json
{
  "name": "research_agent",
  "description": "负责调研与归纳",
  "parameters": {
    "type": "object",
    "properties": {
      "task": { "type": "string", "description": "需要委派给子代理的任务" }
    },
    "required": ["task"]
  }
}
```

为什么只暴露 `task`：
- 当前 tool subsystem 最擅长窄 schema。
- 父上下文、角色、工具集都由框架在执行时自动装配。
- 避免把父 Agent 变成“手工编排 DSL 填写器”。

### 4.2 通用 delegate tool

仅在 `DELEGATE` 模式显式启用时暴露：

```json
{
  "name": "delegate_to_subagent",
  "description": "将任务委派给已注册的子代理",
  "parameters": {
    "type": "object",
    "properties": {
      "agentName": {
        "type": "string",
        "description": "目标子代理名称",
        "enum": ["research_agent", "review_agent"]
      },
      "task": {
        "type": "string",
        "description": "委派任务"
      }
    },
    "required": ["agentName", "task"]
  }
}
```

`agentName` 必须是已注册子代理名枚举，直接复用 `Tool.Builder.addProperty(..., enumValues)`。

## 5. Agent 执行层设计

### 5.1 `Agent.Builder` 新增配置

新增一个直接方法：

```java
public Builder subAgentExposureMode(SubAgentExposureMode mode)
```

行为：
- 默认值为 `SubAgentExposureMode.DIRECT`
- `null` 时回退默认值

### 5.2 `Agent` 获取工具列表

当前 `Agent.doRunWithLoop(...)` 里固定：

```java
List<Tool> tools = toolRegistry.getTools();
```

MVP 改为：

```java
List<Tool> tools = resolveToolsForCurrentExposureMode();
```

其中：
- `DIRECT`：普通工具 + 所有独立子代理 tool
- `DELEGATE`：普通工具 + 一个通用 delegate tool

这一步只影响父 Agent 暴露给 LLM 的 schema，不改变 `ToolRegistry.execute(...)` 的现有普通工具语义。

### 5.3 识别并执行子代理工具

当前 `safeExecute(...)` 对所有工具统一走 `toolRegistry.execute(...)`。

MVP 改为三路分流：

1. 普通工具：维持现状 `toolRegistry.execute(name, arguments)`
2. 独立子代理 tool：命中 `toolRegistry.hasSubAgent(name)` → 执行子代理
3. 通用 delegate tool：`name.equals("delegate_to_subagent")` → 先解析 `agentName`，再执行目标子代理

推荐新增内部方法：

```java
private String executeSubAgentTool(
        SubAgentDefinition definition,
        ToolCall tc,
        Message assistantMessage,
        List<Message> parentMessages,
        String parentTraceId)
```

### 5.4 为什么 `safeExecute(...)` 需要拿到 `parentMessages`

当前最大缺口是：自动裁剪父上下文需要完整父消息链，但现有 `safeExecute(...)` 只拿到 `ToolCall`、`assistantMessage`、`traceId`。

因此建议把签名改为：

```java
private String safeExecute(
        ToolCall tc,
        Message assistantMessage,
        List<Message> parentMessages,
        String traceId)
```

并由串行/并行路径都传入当前轮的 `messages` 快照。

并行安全性：
- `messages` 本轮在工具执行时只追加 tool result，不会再原地改历史消息。
- 为避免并发读写歧义，执行子代理前应复制父消息快照：

```java
List<Message> parentSnapshot = List.copyOf(parentMessages);
```

子代理只读快照。

## 6. 子代理运行时语义

### 6.1 子代理上下文构造

子代理输入消息列表由三部分组成：

1. 子代理自己的 `systemPrompt`
2. 裁剪后的父上下文消息切片
3. 当前这次委派的 `task` 作为新的 `user` 消息

约束：
- 不传父 `systemPrompt`
- 默认包含相关 `user / assistant / tool`
- 默认排除 `llmVisible=false`

### 6.2 默认裁剪策略

建议新增一个小接口：

```java
@FunctionalInterface
public interface ContextSelector {
    List<Message> select(List<Message> parentMessages, Message assistantMessage, String task);
}
```

首批只支持在注册子代理时注入该策略。

框架默认实现建议保守：
- 过滤 `llmVisible=false`
- 保留最近一段相关上下文
- 包含触发本次工具调用的 `assistantMessage`
- 包含与该 assistant 相关联的前序 user/assistant/tool 片段

具体“相关”算法第一版不必做复杂语义检索，优先规则化、可测试。

### 6.3 子代理实例构造

执行时动态构造子代理：

```java
Agent child = Agent.builder(resolveChildLlm(def), resolveChildRegistry(def))
        .systemPrompt(def.systemPrompt())
        .maxIterations(resolveChildMaxIterations(def))
        .addBeforeToolCall(...)
        .addAfterToolCall(...)
        .build();
```

这里故意不在注册时预构建 `Agent`，因为：
- 子代理默认继承父 `LLM`
- 上下文运行时才知道
- 父/子 trace 和 callback 需要隔离

### 6.4 callback / trace 隔离

父侧只把子代理视为一次普通工具调用。

因此：
- 子代理内部不复用父 `ChainCallback`
- 子代理内部不复用父 `traceId`
- 子代理运行时生成自己的 trace

父侧外层事件：
- 开始：`ToolStartEvent`
- 成功：`ToolCompleteEvent`
- 软失败：`ToolErrorEvent` + 错误文本回灌

这和当前普通工具语义保持一致。

### 6.5 错误语义

分两层：

1. **子代理内部工具失败**
   - 子代理自身按现有 `Agent` 语义软失败处理
   - 最终由子代理产出文本结果

2. **子代理整体执行失败**
   - 外层委派工具记 `ToolErrorEvent`
   - 返回 `"工具执行失败: ..."` 或更明确的子代理失败文本给父 Agent
   - 父 Agent 继续下一轮推理

### 6.6 非 Agent 自动循环误用

若在手写 `registry.execute("research_agent", ...)` 中直接执行子代理：
- fail-fast
- 抛出清晰中文异常，例如：

```java
throw new IllegalStateException("SubAgent 仅支持在 Agent 自动循环中执行");
```

避免产生看似可用但语义不完整的降级行为。

## 7. 拦截器边界

分两层：

1. **父拦截器**
   - 作用于外层子代理 tool 调用
   - 可阻止整次委派
   - 可改写子代理最终返回文本

2. **子拦截器**
   - 仅作用于子代理内部自己的工具调用
   - 不继承父拦截器

这样既保持父层统一治理入口，又保持子代理内部自治。

## 8. 兼容性策略

### 8.1 不破坏现有普通工具用法

保持：
- `ToolRegistry.register(...)`
- `ToolRegistry.scan(...)`
- `ToolRegistry.execute(...)`
- `ToolRegistry.getTools()`
- `Agent.builder(llm, registry)`

都继续可用。

### 8.2 手写工具循环兼容策略

手写循环继续能用普通工具，但首批不支持完整 SubAgent 语义。
这不是回归，而是显式能力边界。

### 8.3 文档与示例兼容

现有示例不必修改行为。
只需新增一个 SubAgent 示例，展示：
- 注册两个子代理
- 默认 DIRECT 模式
- 切换到 DELEGATE 模式

## 9. 主要改动文件

建议涉及：

- `chain/src/main/java/com/non/chain/tool/ToolRegistry.java`
- `chain/src/main/java/com/non/chain/agent/Agent.java`
- `chain/src/main/java/com/non/chain/agent/SubAgentExposureMode.java`（新增）
- `chain/src/main/java/com/non/chain/tool/SubAgentDefinition.java`（新增）
- `chain/src/main/java/com/non/chain/tool/SubAgentRegistration.java`（新增）
- `chain/src/main/java/com/non/chain/agent/ContextSelector.java`（新增，或放 `tool/` 包）
- `chain/src/test/java/com/non/chain/agent/...`
- `chain/src/test/java/com/non/chain/tool/...`
- `chain-example/...`（新增示例）

## 10. 测试重点

至少覆盖：

- `DIRECT` 模式下，父 Agent 能看到独立子代理 tool
- `DELEGATE` 模式下，只看到通用 delegate tool
- `agentName` 枚举正确生成
- 子代理默认只暴露 `task`
- 子代理默认继承父 `LLM`
- 子代理可覆盖 `LLM` / `maxIterations`
- 子代理默认无状态，父上下文由自动裁剪生成
- 外层父拦截器可阻止/改写子代理调用
- 子代理内部拦截器仅作用于内部工具
- 子代理失败外层记 `ToolErrorEvent` 但仍回灌错误文本
- 非 Agent 自动循环误用时 fail-fast
- 同轮多个子代理可并行执行并按原顺序回灌

## 11. 后续但不在 MVP

- 运行时逐次覆盖委派上下文
- 递归/多层委派
- 父侧展开子代理内部事件
- 注解式注册子代理
- transcript / JSON rich result 回传
- 手写工具循环下的完整 SubAgent 执行 API
