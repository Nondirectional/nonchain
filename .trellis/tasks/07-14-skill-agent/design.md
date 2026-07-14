# Design — Skill 机制(顶层 Agent):MVP

> 基于 `prd.md` 已锁定的 D1-D12 决策(见父任务 `07-14-skill-system/prd.md`),定义技术架构、
> 核心数据结构、关键算法(skill 注入的消息序列 / dispatchExecute 第六路 / 命名校验)与兼容策略。

## 1. 设计目标

在**不破坏 0.10.0 既有契约**的前提下(无 skill 的 Agent 行为完全一致),为顶层 Agent 引入:

- **skill 系统**(D1-D3):独立 `SkillRegistry` + `SkillDefinition` 值对象,skill 是过程性知识而非可执行工具
- **模型自选触发**(D2/D8):skill 转为无参数 function 喂 LLM,LLM 点选后注入内容
- **system 注入**(D4):skill 内容以 `Message.system()` 追加对话
- **可观测**(D9):`AgentEvent.SkillActivated` + trace span,不走 interceptor
- **安全防护**(D12):build 时跨 registry 命名冲突 fail-fast

明确**不**做(留 v2 / `skill-subagent` 子任务):EPHEMERAL、动态 content、SkillLoader、SubAgent 接入。

## 2. 不变项(0.10.0 架构保留)

| 0.10.0 设计 | 本次态度 |
|---|---|
| `ToolRegistry` 注册中心(注解 + fluent 双入口) | ✅ 保留,skill 照抄此模式但独立成 `SkillRegistry` |
| `dispatchExecute` 五路分叉 | ⚠️ 扩展为六路,skill 分支前置 |
| `resolveToolsForCurrentExposureMode` 拼 LLM schema | ⚠️ 扩展,末尾追加 skill tools |
| `Agent.Builder` 构造模式 | ✅ 保留,新增 `.skillRegistry()` |
| `Message.system()` 工厂 | ✅ 复用,零模型改动 |
| `AgentEvent` 内部 class 事件模式 | ✅ 复用,新增 `SkillActivated` |
| `Tracer.ScopedSpan` span 模式 | ✅ 复用,新增 skill span |
| 主循环消息序列(append-only) | ✅ 保留(PERSISTENT 不引入移除操作) |

## 3. 核心数据结构

### 3.1 `SkillDefinition`(不可变值对象)

```java
package com.non.chain.skill;

public final class SkillDefinition {
    private final String name;         // 唯一标识,也是 LLM 点选的 function name
    private final String description;  // 暴露给 LLM 的"什么时候用我",召回路由依据
    private final String content;      // skill 正文,注入 system 消息的内容

    // 私有全参构造 + Builder
    // name/description/content 均必填,空值 fail-fast(IllegalArgumentException)
}
```

Builder:
```java
SkillDefinition.builder()
    .name("code-review")
    .description("当用户要求审查代码时使用。提供结构化的代码审查流程。")
    .content("# 代码审查流程\n1. 先看整体结构...\n2. ...")
    .build();
```

**为什么没有 `lifecycle` 字段:** D5 锁定 MVP 只做 PERSISTENT。lifecycle 字段留待
`skill-subagent` 子任务(v2)引入 EPHEMERAL 时再加——MVP 阶段所有 skill 都是 PERSISTENT,
不需要字段表达。

### 3.2 `SkillRegistry`(注册中心)

```java
package com.non.chain.skill;

public class SkillRegistry {
    private final Map<String, SkillDefinition> skills = Collections.synchronizedMap(new LinkedHashMap<>());

    // 双入口(与 ToolRegistry 的 register + scan 对称)
    public SkillRegistration register(String name, String description);  // fluent
    public SkillRegistry register(SkillDefinition skill);                 // 值对象直传

    // 查询
    public boolean contains(String name);
    public SkillDefinition get(String name);
    public List<String> skillNames();

    // 转 LLM function schema:每个 skill → 无参数 Tool
    public List<Tool> getSkillTools();
}
```

`getSkillTools()` 实现:
```java
public List<Tool> getSkillTools() {
    List<Tool> tools = new ArrayList<>();
    synchronized (skills) {
        for (SkillDefinition def : skills.values()) {
            // 无参数 function:只有 name + description,无 properties
            tools.add(Tool.builder(def.name())
                    .description("[Skill] " + def.description())
                    .build());
        }
    }
    return tools;
}
```

**设计决策:`[Skill]` 前缀。** description 前加 `[Skill]` 标记,让 LLM 在 function 列表里
区分 skill 和真 tool。这是 D8 里"语义隔离靠标记而非路径"的落地——skill 走独立执行路径,
但在 LLM schema 层用前缀做可辨识度。可选,但推荐(帮助 LLM 理解"这个 function 是知识来源
不是执行动作")。

### 3.3 `SkillRegistration`(fluent Builder,类比 `ToolRegistry.Registration`)

```java
public class SkillRegistration {
    private final SkillRegistry registry;
    private final String name;
    private final String description;
    private String content;

    SkillRegistration(SkillRegistry registry, String name, String description) { ... }

    public SkillRegistration content(String content) { this.content = content; return this; }

    public SkillRegistry build() {
        // content 必填校验 → 构造 SkillDefinition → 写入 registry.skills
    }
}
```

### 3.4 `AgentEvent.SkillActivated`(新增事件)

```java
// 在 AgentEvent.java 内部,照抄 SubAgentSpawned 等 class 的模式
class SkillActivated implements AgentEvent {
    private final String skillName;
    private final int contentLength;   // 注入内容长度(不存全文,避免事件膨胀)

    public SkillActivated(String skillName, int contentLength) { ... }
    public String skillName() { return skillName; }
    public int contentLength() { return contentLength; }
}
```

**为什么存 contentLength 不存全文:** 事件是观测信号,不是数据通道。全文已在对话 messages
里,trace span 也能记。事件里存长度足以让应用层知道"注入了多大块的知识"。

## 4. 关键算法:skill 注入的消息序列

**这是本设计最关键、也最微妙的部分。** skill 走 system 注入(D4),但寻址走 tool-calling
(D2)。两者协议衔接需仔细论证。

### 4.1 约束:tool-calling 协议要求 toolCall 后跟 tool result

主循环(`Agent.java:361-409`)在 LLM 返回 toolCalls 后,无论并行(line 388-395)还是串行
(line 399-408)路径,都执行:

```java
String output = dispatchExecute(...);
messages.add(Message.toolResult(tc.id(), output));   // ← 强制:每个 toolCall 必须跟一条 tool result
```

**这意味着:skill 被 LLM 点选(产生 toolCall)后,必须产出一条 tool result 消息,**否则下一轮
LLM 调用会因"assistant 发了 toolCall 但对话里没有对应 tool result"而报错(provider 校验失败)。

### 4.2 双消息注入方案(主方案,system 注入)

skill 被点选后,产出**两条**消息:

1. **`Message.toolResult(tc.id(), "(skill {name} 已加载,详见下方的系统指令)")`** —— 满足
   tool-calling 协议。返回值是轻量确认文本,不承载 skill 全文(全文走 system 注入)。
2. **`Message.system(content)`** —— skill 正文,作为持续生效的行为指令。

实现上,`dispatchExecute` 的 skill 分支返回确认文本,然后**在调用方(主循环)的 toolResult
追加之后,额外追加一条 system 消息**。

但主循环目前是 `dispatchExecute` 返回 String → 包成 toolResult → add。skill 需要追加**额外的
system 消息**,这超出了 dispatchExecute "返回一个 String" 的契约。

**解决方案:dispatchExecute 的 skill 分支需要一个副作用通道把 system 消息传回主循环。**

两种实现选择:

**选择 A(推荐):dispatchExecute 签名扩展,返回一个结果对象**

```java
// 新增内部类
private static class DispatchResult {
    final String toolResultText;       // tool result 消息内容
    final List<Message> extraMessages; // 额外追加的消息(skill 的 system 注入);空 list = 无

    static DispatchResult of(String text) { return new DispatchResult(text, List.of()); }
    static DispatchResult of(String text, List<Message> extra) { return new DispatchResult(text, extra); }
}
```

主循环改为:
```java
DispatchResult dr = dispatchExecute(...);
messages.add(Message.toolResult(tc.id(), dr.toolResultText));
messages.addAll(dr.extraMessages);   // skill 注入的 system 消息在此追加
```

普通 tool / sub-agent 路径返回 `DispatchResult.of(output)`(无 extraMessages),行为不变。

**选择 B:dispatchExecute 保持返回 String,skill 注入通过 mutable 容器传回**

主循环传入一个 `List<Message> skillInjectionSink`,skill 分支往里塞 system 消息,主循环在
toolResult 追加后 drain sink。侵入性更小,但 mutable sink 是隐藏副作用,可读性差。

**推荐选择 A**——显式的 DispatchResult 比隐藏 mutable sink 更清晰,且 DispatchResult 是内部
实现细节(私有静态类),不污染公开 API。代价是 dispatchExecute 签名变化 + 两个调用点(并行/
串行)都要改,但改动机械、可控。

### 4.3 注入后的对话形态(PERSISTENT, D5)

skill 注入后,system 消息常驻。对话形态:
```
[system] 你是助手...
[user]   帮我审查这段代码
[assistant toolCalls: [code-review]]        ← LLM 点选 skill
[tool] (skill code-review 已加载...)         ← 协议要求的 tool result
[system] # 代码审查流程\n1. ...               ← skill 正文注入(PERSISTENT 常驻)
[assistant] 好的,按代码审查流程...           ← LLM 看到 skill 指令后响应
```

下一轮若 LLM 再点别的 skill,再追加一条 system 消息。多 skill 叠加 = 多条 system 消息共存。

### 4.4 provider 兼容性待验证(V1, prd R7)

D4 选 system 注入,但"对话中途追加 system 消息"的 provider 兼容性**必须在实现前先验证**:

- **DashScope(qwen 系列):** 待测
- **OpenAI 兼容(vLLM):** 待测

**验证方法:** 写一个最小测试,对话进行到第 2 轮时追加 `Message.system("...")`,观察 provider
是否报错、LLM 是否在后续轮次遵循该指令。

**fallback(D4 已锁定):** 若任一 provider 异常,改注入为
`Message.user("[Skill: {name}]\n" + content)`。代码上只需改 dispatchExecute skill 分支里
`Message.system(...)` 一行为 `Message.user(...)`,dispatchExecute 其余结构不变。

**本 design 以 system 注入为主方案撰写;若 V1 fallback,user 注入的改动点在第 4.2 节
skill 分支的 extraMessages 构造那一行。**

### 4.5 V1 验证结论(代码库证据,2026-07-14)

**结论:system 注入可行,无需手动调 provider 测试。fallback 不触发。**

证据来自 `AbstractOpenAILLM.buildMessageListParams()`(line 184-199):该方法遍历**整个**
messages 列表,对每个 `role=="system"` 的消息调 `builder.addSystemMessage(content)`——
**无"只处理首条 system"的限制**。中途追加的 system 消息会被原样透传进 provider 请求。

- openai-java SDK 的 `addSystemMessage` 是 append 语义,可多次调用
- DashScope / vLLM 均 OpenAI 兼容,协议本身允许多条 system 消息穿插
- nonchain provider 层对中途 system 完全透明,不做特殊处理

(注:`Agent.java:731` 的 `if (!"system".equals(m.role()))` 是 SubAgent resume 存历史时
**剥离** system 消息,与 V1 无关——V1 问的是"中途 system 进 provider 是否被接受",答案是 yes。)

**D4 终值确定:system 注入(`Message.system(content)`)。**

## 5. 关键算法:dispatchExecute 第六路(skill 分支)

### 5.1 分支位置:前置

`dispatchExecute`(`Agent.java:576-612`)现有五路,判断顺序:
1. `toolRegistry.hasSubAgent(name)` → 子代理
2. `DELEGATE_TOOL_NAME` → delegate
3. `GET_RESULT_TOOL_NAME` → get_result
4. `STEER_TOOL_NAME` → steer
5. 兜底 → `toolRegistry.execute()` 普通工具

**skill 分支前置到第 0 位**:
```java
private DispatchResult dispatchExecute(ToolCall tc, ...) {
    // 0. skill:LLM 点选了 skill function
    if (skillRegistry != null && skillRegistry.contains(tc.name())) {
        return executeSkill(tc);   // 返回 DispatchResult,含 toolResult + system 注入
    }
    // 1-5. 原五路不变,返回 DispatchResult.of(...)
    ...
}
```

**为什么前置:** skill 名与 tool/sub-agent/保留名在 build() 时已校验互斥(D12),不存在歧义。
前置让 skill 判断短路,避免落入普通 tool 兜底(`toolRegistry.execute` 会因找不到 skill 名抛异常)。

### 5.2 `executeSkill` 实现

```java
private DispatchResult executeSkill(ToolCall tc) {
    SkillDefinition def = skillRegistry.get(tc.name());
    String content = def.content();

    // skill 激活事件(D9)
    if (eventConsumer != null) {  // 注意:AgentEvent 走 eventConsumer,不走 callback
        eventConsumer.accept(new AgentEvent.SkillActivated(def.name(), content.length()));
    }

    // 双消息注入(D4 + 4.2)
    String toolResultText = "(skill " + def.name() + " 已加载,详见系统指令)";
    Message injection = Message.system(content);  // V1 fallback: Message.user("[Skill: "+def.name()+"]\n"+content)

    return DispatchResult.of(toolResultText, List.of(injection));
}
```

### 5.3 trace span(D9)

skill 激活记一个 span,挂当前 llm span 下(与 tool span 同级)。在 `executeSkill` 内:

```java
final Tracer.ScopedSpan skillSpan = tracer != null
        ? tracer.startSpan(SpanAttributes.SpanType.SKILL, "skill:" + def.name()) : null;
try {
    // ... 注入逻辑 ...
} finally {
    if (skillSpan != null) skillSpan.close();
}
```

**注:** `SpanAttributes.SpanType` 可能需新增 `SKILL` 枚举值(取决于现有枚举结构,
implement 时确认)。若新增枚举成本高,复用 `LLM` 或 `TOOL` 类型 + span name 区分也可接受。

## 6. 关键算法:LLM schema 拼接

### 6.1 `resolveToolsForCurrentExposureMode` 扩展

```java
private List<Tool> resolveToolsForCurrentExposureMode() {
    List<Tool> tools = new ArrayList<>(toolRegistry.getRegularTools());
    if (subAgentExposureMode == SubAgentExposureMode.DELEGATE) {
        toolRegistry.getDelegateSubAgentTool().ifPresent(tools::add);
    } else {
        tools.addAll(toolRegistry.getDirectSubAgentTools());
    }
    tools.addAll(toolRegistry.getSubAgentControlTools());
    // ↓↓↓ 新增:拼接 skill tools(无 skill 时为空 list,零影响)
    if (skillRegistry != null) {
        tools.addAll(skillRegistry.getSkillTools());
    }
    return tools;
}
```

skill tools 放在列表**末尾**。理由:真 tool(有副作用的执行动作)在前,skill(知识来源)
在后,让 LLM 优先看到可执行能力。

### 6.2 无参数 function 的 schema 形态

skill 转成的 Tool 无 properties,`toFunctionDefinition()` 产出的 schema:
```json
{
  "name": "code-review",
  "description": "[Skill] 当用户要求审查代码时使用...",
  "parameters": { "type": "object", "properties": {} }
}
```

LLM 点选时 `arguments` 通常为 `"{}"` 或空字符串,`executeSkill` 不解析参数(skill 无参数)。

## 7. 关键算法:命名冲突校验(D12)

### 7.1 校验时机:`Agent.Builder.build()`

```java
public Agent build() {
    // ... 现有构造 ...

    // D12:skill 命名冲突校验(仅 skillRegistry != null 时)
    if (skillRegistry != null) {
        validateSkillNaming(skillRegistry, toolRegistry);
    }

    return new Agent(this);
}
```

### 7.2 校验逻辑

收集所有"被 LLM 看到的 function name",检测重复:

```java
private void validateSkillNaming(SkillRegistry sr, ToolRegistry tr) {
    Set<String> reserved = new HashSet<>();
    // 普通工具名
    for (Tool t : tr.getRegularTools()) reserved.add(t.name());
    // 子代理名(DIRECT 模式会暴露为 tool)
    reserved.addAll(tr.subAgentNames());
    // DELEGATE 模式的保留 tool 名(总是检查,不依赖模式,防御性)
    reserved.add(ToolRegistry.DELEGATE_TOOL_NAME);
    reserved.add(ToolRegistry.GET_RESULT_TOOL_NAME);
    reserved.add(ToolRegistry.STEER_TOOL_NAME);

    for (String skillName : sr.skillNames()) {
        if (reserved.contains(skillName)) {
            throw new IllegalStateException(
                "skill 名 '" + skillName + "' 与已注册的工具/子代理/保留名冲突");
        }
        // skill 内部重名(SkillRegistry.register 已防,这里双重保险)
    }
}
```

**注:** `ToolRegistry.subAgentNames()` 是 public(见 `ToolRegistry.java:112`),可直接调。
`getRegularTools()` 也是 public。

## 8. Agent.Builder 扩展

```java
public static class Builder {
    // ... 现有字段 ...
    private SkillRegistry skillRegistry;   // 默认 null = 无 skill(0.10.0 行为)

    /** 注册 skill 中心;不调用则该 Agent 无 skill 能力,行为与 0.10.0 一致。 */
    public Builder skillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        return this;
    }
}
```

Agent 构造函数新增 `this.skillRegistry = builder.skillRegistry;`。

## 9. ChatMemory 边界(MVP 注意项)

skill 注入的 system 消息属"框架运行时构造"。若 Agent 配了 ChatMemory(`run(String)` 自动
管理历史),注入的 system 消息会随对话历史一起被 ChatMemory 持久化。

**MVP(PERSISTENT)的影响:** resume 时,上次注入的 skill system 消息仍在历史里。这在
PERSISTENT 语义下**可接受**(skill 本就是常驻的),但多轮累积可能堆积多条 skill system 消息。

**MVP 的处理:** 不做特殊处理(保持简单)。这是 PERSISTENT 的固有特性——skill 常驻。
若堆积成为问题,应用层可控制 skill 数量,或等 v2 的 EPHEMERAL。

**v2(EPHEMERAL)的边界问题** 留 `skill-subagent` 子任务,届时需在"进 LLM 上下文"和
"进 ChatMemory 持久化"两通道间做分离。MVP 不碰。

## 10. 包结构与文件清单

```
chain/src/main/java/com/non/chain/skill/
├── SkillDefinition.java       # 不可变值对象 + Builder
├── SkillRegistry.java         # 注册中心(双入口 + getSkillTools)
└── SkillRegistration.java     # fluent Builder(或作为 SkillRegistry 内部类)

chain/src/main/java/com/non/chain/agent/
├── Agent.java                 # 修改:Builder.skillRegistry + dispatchExecute 第六路
│                              #       + resolveToolsForCurrentExposureMode 扩展
│                              #       + build() 命名校验 + DispatchResult 内部类
└── AgentEvent.java            # 修改:新增 SkillActivated 内部 class

chain/src/test/java/com/non/chain/skill/
├── SkillDefinitionTest.java
└── SkillRegistryTest.java

chain/src/test/java/com/non/chain/agent/
└── AgentSkillTest.java        # 集成:LLM 点选 skill → system 注入 → 下一轮遵循
```

**SkillRegistration 放独立文件还是 SkillRegistry 内部类:** 倾向内部类(与
`ToolRegistry.Registration` 一致,Registration 是 ToolRegistry 的内部类)。

## 11. 兼容策略(零回归保证)

1. **skillRegistry 默认 null** → `resolveToolsForCurrentExposureMode` 的 skill 拼接跳过,
   `dispatchExecute` 的 skill 分支跳过。无 skill 的 Agent 行为与 0.10.0 字节级一致。
2. **DispatchResult 改造对普通路径透明** → 普通 tool / sub-agent 路径返回
   `DispatchResult.of(output)`,主循环 `messages.add(toolResult)` 行为不变,只是多了一步
   `addAll(emptyList)` 空操作。
3. **V1 fallback 局部化** → provider 兼容性问题只影响 `executeSkill` 里一行
   (`Message.system` vs `Message.user`),不波及其他设计。
