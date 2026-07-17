# nonchain

[![CI](https://github.com/Nondirectional/nonchain/actions/workflows/ci.yml/badge.svg)](https://github.com/Nondirectional/nonchain/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-11%2B-blue)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-MIT-green)](https://opensource.org/licenses/MIT)

一个轻量级的 Java AI 应用开发框架，提供 LLM 调用、工具函数、Agent 循环、工作流编排、文档处理和知识检索能力。最小化 API 表面积，模块化按需引入。

## 特性

- **LLM Provider 抽象** — 统一 `LLM` 接口，内置阿里云 DashScope、vLLM 及任何 OpenAI 兼容端点（Ollama、LiteLLM）；支持流式输出、多模态输入、结构化输出（JSON Object）
- **工具函数框架** — 注解（`@ToolDef`/`@ToolParam`）+ 流式 API 两种定义方式，`ToolRegistry` 自动注册与调度
- **Agent 循环** — LLM + 工具自动调用循环，Builder 模式；支持 ChainCallback 统一回调、流式事件输出、工具并行执行、工具拦截器（before/after，可阻止/改写）、委派型子代理（SubAgent）与前台/后台并行执行
- **应用层消息分层** — `Message.note()` 产生 UI-only 状态消息，进 transcript 供 UI 重放，在 LLM 边界被剥离不污染上下文
- **Skill（过程性知识注入）** — 过程性知识/指令文本，LLM 通过 tool-calling 自主点选后按配置注入对话，指导 Agent 行为方式
- **图工作流引擎** — 基于有向图的多步骤编排，支持条件路由和事件回调
- **文档处理** — TXT/Markdown/HTML/DOCX/PDF 解析（含 OCR）+ 清洗管道；5 种切分策略（递归字符、标题层级、语义、组合、LLM 语义切分）
- **统一检索** — Elasticsearch 单独承担向量检索、BM25 与混合检索，支持元数据过滤、RRF/Linear 融合、上下文扩展
- **统一回调 (ChainCallback)** — 覆盖 LLM、Tool、Retrieval、Graph 的 Start/Complete/Error 生命周期，支持 traceId 关联和多订阅者组合
- **执行链路遥测 (Trace Telemetry)** — opt-in 录制整棵执行链路（Agent/Flow/SubAgent/LLM/工具）的 OTel 风格 span 树，可插拔存储 + JSON 序列化

## 要求

- Java 11+（已在 Java 11 / 17 / 21 上测试通过）
- Maven 3.6+

## 快速开始

### 安装

```bash
git clone https://github.com/Nondirectional/nonchain.git
cd nonchain
mvn install -DskipTests
```

### 引入依赖

groupId 为 `io.github.nondirectional`（注意：`com.non.chain.*` 是 Java 包名，与 Maven groupId 不同）。

核心模块（必需）：

```xml
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain</artifactId>
    <version>0.11.0</version>
</dependency>
```

可选模块：

```xml
<!-- 文档处理（解析 + 清洗 + 切分） -->
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain-document</artifactId>
    <version>0.11.0</version>
</dependency>

<!-- Elasticsearch 向量检索 / BM25 / 混合检索 -->
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain-elasticsearch</artifactId>
    <version>0.11.0</version>
</dependency>

<!-- 持久化：对话记忆 + 执行链路遥测（二选一） -->
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain-mysql</artifactId>      <!-- 或 chain-postgres -->
    <version>0.11.0</version>
</dependency>
```

### 首次对话

```java
LLM llm = new DashscopeLLM("your-api-key", "qwen-plus");

ChatResult result = llm.chat("你是一个助手", "你好");
System.out.println(result.getContent());
```

运行示例需设置环境变量：`export DASHSCOPE_API_KEY=your-api-key`

## 核心能力

> 完整分模块文档见 [`docs/`](docs/)，下面每节给出最常用的用法和对应文档链接。

### LLM 调用

支持简单对话、多轮对话、工具调用、结构化输出与多模态输入。

```java
// 多模态：文本 + 图片
LLM llm = new DashscopeLLM("qwen-vl-plus");
Message userMessage = Message.user(Arrays.asList(
        ImageUrlPart.of("https://example.com/image.jpg"),
        TextPart.of("图片中有什么？")));
ChatResult result = llm.chat(Arrays.asList(userMessage));
```

📖 文档：[DashscopeLLM](docs/llm/dashscope-llm.md) · [OpenAI 兼容](docs/llm/openai-compatible-llm.md) · [vLLM](docs/llm/vllm.md) · [多模态](docs/llm/multimodal.md) · [结构化输出](docs/llm/structured-output.md) · [Message 模型](docs/llm/message.md)

### 工具函数

**注解方式：**

```java
public class WeatherService {
    @ToolDef(name = "get_weather", description = "获取指定城市的天气")
    public String getWeather(@ToolParam(name = "city", description = "城市名称") String city) {
        return city + ": 晴, 25°C";
    }
}

ToolRegistry registry = new ToolRegistry();
registry.scan(new WeatherService());
```

**流式 API 方式：**

```java
ToolRegistry registry = new ToolRegistry();
registry.register("get_weather", "获取城市天气")
        .param("city", "城市名称")
        .handle(args -> args.getString("city") + ": 晴, 25°C");
```

📖 文档：[注解方式](docs/tool/annotation.md) · [流式 API](docs/tool/fluent-api.md) · [ToolRegistry](docs/tool/tool-registry.md)

### Agent 循环

Agent 自动驱动 LLM 与工具的多轮调用循环，一行 `agent.run(...)` 完成「推理 → 调工具 → 再推理」直到给出最终答案。

```java
ToolRegistry registry = new ToolRegistry().scan(new WeatherService());

Agent agent = Agent.builder(llm, registry)
        .systemPrompt("你是一个旅行助手")
        .maxIterations(5)
        .callback(new ChainCallback() {            // 可选：观察执行过程
            @Override public void onToolComplete(ToolCompleteEvent e) {
                System.out.println("[Tool] " + e.toolName() + " → " + e.result());
            }
        })
        .build();

ChatResult result = agent.run("北京和上海天气怎么样？");
System.out.println(result.content());
```

> **graceful max turns（⚠️ 0.10.0 破坏性变更）**：超 `maxIterations` 不再抛异常，改为注入「收尾」提示给 `graceTurns`（默认 3）轮收尾；`.graceTurns(0)` 回退 0.9.x 硬截断。

#### 工具拦截器

`BeforeToolCall` / `AfterToolCall` 在不修改工具实现、不继承 `Agent` 的前提下对工具调用进行**拦截、阻止、改写**。与 `ChainCallback`（只读观察）正交——拦截器是**控制**层。

- **before**：工具执行前调用，可 `block(reason)` 阻止执行（reason 回灌 LLM）
- **after**：工具执行后、结果回灌 LLM 前调用，可改写 `content`（脱敏/截断）或标记 `isError`

```java
Agent agent = Agent.builder(llm, registry)
        .addBeforeToolCall(ctx -> ctx.arguments().contains("rm -rf")
                ? BeforeResult.block("危险命令禁止") : BeforeResult.pass())
        .addAfterToolCall(ctx ->
                AfterResult.content(ctx.result().replaceAll("\\d{11}", "***********")))
        .build();
```

典型场景：危险命令审核、工具结果脱敏、超长输出截断、工具熔断。拦截器异常会包装为 `AgentException` 抛出（不静默吞）；`ChainCallback` 的异常则被静默隔离——两者职责不同，可在同一 Agent 共存。

#### 委派型子代理（SubAgent）

父 Agent 通过 tool calling 自主把子任务委派给专职子代理，子代理独立运行后回传结果。适合「调研 / 撰写」「规划 / 执行」等可分工场景。子代理作为一等 tool 能力注册在 `ToolRegistry`，默认 **DIRECT**（每个子代理暴露为一个独立 tool），也可切到 **DELEGATE**（统一入口 `delegate_to_subagent`）。

```java
ToolRegistry registry = new ToolRegistry();
registry.registerSubAgent("research", "负责调研与归纳")
        .systemPrompt("你是调研代理。优先归纳事实，不编造。")
        .toolRegistry(researchTools)   // 可选：子代理专属工具集
        .maxIterations(3)              // 可选：默认回退框架默认值
        .build();
registry.registerSubAgent("writer", "负责撰写回复")
        .systemPrompt("你是撰写代理。")
        .build();

Agent agent = Agent.builder(llm, registry)
        .systemPrompt("你是主助手，把子任务委派给合适的子代理。")
        .build();
agent.run("帮我调研量子计算并写一段介绍");
```

**前台 / 后台并行**：前台子代理保持同步内联（父 Agent 阻塞等待）；**后台子代理**（`run_in_background=true`）让父 Agent 派发后不阻塞、继续推理，支持自动 join、运行中 `steer_subagent` 转向、会话 resume、graceful max turns。

```java
Agent agent = Agent.builder(llm, registry)
        .maxBackgroundRunning(4)   // 后台并发上限（默认 4）
        .graceTurns(3)             // graceful 收尾轮数（默认 3）
        .build();
```

- 子代理默认**无状态**：独立 `systemPrompt`、工具集、`maxIterations`、`before/after` 拦截器，默认**继承父 LLM**
- **上下文裁剪**：框架自动从父消息链裁剪上下文（排除 `llmVisible=false` 消息、隔离父 `system` 消息、移除不完整调用组），可用 `contextSelector(...)` 覆盖裁剪窗口但不能绕过安全边界
- **仅一层**：子代理的 `toolRegistry` 若再注册 subAgent → `build()` fail-fast；仅支持 `Agent` 自动循环
- 后台并发受熔断保护（默认 = `maxIterations × maxRunning × 2`），死循环防护与全局超时（默认 60s）

📖 示例：[SubAgentExample](chain-example/src/main/java/com/non/chain/example/SubAgentExample.java) · [BackgroundSubAgentExample](chain-example/src/main/java/com/non/chain/example/BackgroundSubAgentExample.java)

### Skill（过程性知识注入）

Skill 是**过程性知识/指令文本**——告诉 Agent「怎么做某事」。当相关场景出现时，LLM 通过 tool-calling 自主点选 skill，内容按注入模式（默认 `SYSTEM`，可显式 `USER`）进入对话。skill 本身不含可执行工具（区别于 Tool 的「执行有副作用的动作」。

```java
SkillRegistry skillRegistry = new SkillRegistry();
skillRegistry.register("code-review", "当用户请求审查代码时使用")
        .content("# 代码审查流程\n1. 看整体结构\n2. 看命名规范\n3. ...")
        .build();

Agent agent = Agent.builder(llm, registry)
        .skillRegistry(skillRegistry)    // 挂载 skill
        // .skillInjectionMode(SkillInjectionMode.USER)  // 可选：始终按 user 消息注入
        .build();
// 用户提问后，LLM 在 function 列表里看到 [Skill] code-review，自主决定是否点选
```

skill 在 function 列表里以**无参数 function** 出现，激活时触发 `AgentEvent.SkillActivated` 事件 + trace span；注入消息常驻整轮对话，多 skill 可叠加；skill 名与 tool/sub-agent/保留名互斥（`build()` 时 fail-fast）。子代理也可通过 `.skillRegistry(skills)` 挂载 skill。

📖 示例：[SkillExample](chain-example/src/main/java/com/non/chain/example/SkillExample.java) · [SubAgentSkillExample](chain-example/src/main/java/com/non/chain/example/SubAgentSkillExample.java)

### 工作流编排

基于有向图的多步骤编排，节点处理 + 条件路由，`State` 在节点间流转。

```java
Graph graph = Graph.builder()
        .addNode(Node.of("classify", state -> state))      // 分类节点
        .addNode(Node.of("technical", state -> state))     // 技术问题处理
        .addNode(Node.of("general", state -> state))       // 通用问题处理
        .addEdge(Edge.conditional("classify", state -> {
            String type = state.getOrDefault("type", "general");
            return type.equals("technical") ? "technical" : "general";
        }))
        .startNode("classify")
        .build();

GraphResult result = graph.run(State.of(Map.of(), List.of(
        Message.user("什么是 JVM 调优？")));
```

📖 文档：[图工作流引擎](docs/graph/workflow.md)

### 应用层消息与 LLM 消息分层

`Message.note(kind, content)` 记录 UI-only 状态进 transcript，供 UI 重放历史，但**不进入 LLM 上下文**——在 LLM 调用边界自动剥离。应用层消息不计入窗口/token 预算、原位保留，不破坏 tool 消息配对保护。

```java
List<Message> messages = new ArrayList<>();
messages.add(Message.user("读取配置文件"));
messages.add(Message.note("status", "正在读取文件 config.json"));  // UI 会看到，LLM 看不到
messages.add(Message.assistant("..."));

ChatResult result = llm.chat(messages, OutputFormat.TEXT);  // 请求里不含 note
```

典型场景：UI 状态条（"正在思考"、"工具审核中"）、artifact 记录、通知重放。现有 `Message.user/assistant/...` 工厂产出的消息默认 `llmVisible=true`，行为与改动前一致。

📖 示例：[MessageLayeringExample](chain-example/src/main/java/com/non/chain/example/MessageLayeringExample.java)

### 执行链路遥测（Trace Telemetry）

opt-in（默认零开销）为 Agent / Flow / SubAgent 的整棵执行链路录制 OTel 风格 span 树（含 prompt/messages、入参出参、状态快照），供执行链路可视化与归档分析。单一 `runtimeId` 贯穿整棵树；SubAgent 内部的 LLM/工具调用也录制进同一棵树。

```java
InMemoryTraceStore store = new InMemoryTraceStore();

Agent agent = Agent.builder(llm, registry)
        .systemPrompt("...")
        .trace(store)            // ← 启用录制
        .build();

ChatResult result = agent.run("你好");
String runtimeId = result.runtimeId();                      // 成功路径直接拿 id
Trace trace = store.getTrace(runtimeId).orElseThrow();
System.out.println(trace.toJson());                          // 序列化整棵 span 树
```

`TraceStore` 是 SPI，内置 `InMemoryTraceStore`；`chain-mysql` / `chain-postgres` 提供持久化实现（`MysqlTraceStore` / `PostgresTraceStore`，建表 SQL 见各模块 `trace_span.sql`）。**失败路径**也能从异常链提取 runtimeId：`TraceRuntimeIds.find(e)`。

> 边界声明：nonchain 是库，**只到 Java API（`getTrace(id)` + JSON 序列化）**，不起 HTTP、不画 UI。可视化是独立消费端。

📖 示例：[TraceTelemetryExample](chain-example/src/main/java/com/non/chain/example/TraceTelemetryExample.java)

### 文档处理与切分

自动识别文件类型并解析，支持 TXT / Markdown / HTML / DOCX / PDF（含 OCR），配合可组合的清洗管道（控制字符、Unicode 归一化、空白、样板、去重、短片段合并、图片策略）。

```java
DocumentReaders readers = new DocumentReaders();
readers.register(new PdfDocumentReader());
ParsedDocument doc = readers.read(new File("document.pdf"));

CleanerPipeline pipeline = CleanerPipeline.of(
        new ControlCharacterRemover(), new UnicodeNormalizer(),
        new WhitespaceNormalizer(), new BoilerplateRemover());
ParsedDocument cleaned = pipeline.clean(doc);
```

提供 5 种切分策略：**递归字符切分**（按分隔符层级递归，支持字符/Token 度量）、**标题层级切分**（按 Markdown 标题结构）、**语义切分**（基于 Embedding 相似度在话题切换处切分）、**组合切分**（先结构后细分）、**LLM 语义切分**（基于 LLM 智能切分，质量最优）。

📖 文档：[文档模型](docs/document/model.md) · [解析器](docs/document/readers.md) · [清洗管道](docs/document/cleaners.md) · [切分策略](docs/document/splitters.md)

### 知识检索

`ElasticsearchKnowledgeStore.search(SearchRequest)` 是统一检索入口：仅文本 → BM25；仅向量 → kNN；两者同时 → hybrid（RRF/Linear 融合）。支持元数据过滤与命中后上下文扩展。

```java
ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 1024).build();

RetrievalResponse response = store.search(SearchRequest.builder()
        .queryText("查询文本")
        .queryEmbedding(queryEmbedding)   // hybrid 检索
        .size(5)
        .addKnowledgeBaseId("kb-demo")
        .build());

for (SearchResult r : response.results()) {
    System.out.printf("[%.4f] %s%n", r.score(), r.content());
}
```

📖 文档：[KnowledgeStore](docs/knowledge/store.md) · [元数据过滤](docs/knowledge/metadata-filter.md) · [BM25](docs/elasticsearch/bm25.md) · [混合检索](docs/elasticsearch/hybrid-retrieval.md) · [ES 存储](docs/elasticsearch/store.md) · [Embedding 模型](docs/embedding/model.md)

## 模块说明

| 模块 | 说明 |
|------|------|
| `chain` | 核心模块：LLM 抽象、工具函数、Agent 循环、图工作流、统一回调 (ChainCallback)、知识存储接口、文档模型、Embedding、多模态消息、MessageSerializer、执行链路遥测 (trace) |
| `chain-document` | 文档处理：TXT/MD/HTML/DOCX/PDF 解析 + OCR + 清洗管道 + 5 种文档切分策略 |
| `chain-elasticsearch` | Elasticsearch 向量存储、BM25 检索、原生 retriever 混合检索 |
| `chain-mysql` | MySQL 持久化：对话记忆（`MysqlChatMemoryStore`）、执行链路遥测（`MysqlTraceStore`） |
| `chain-postgres` | PostgreSQL 持久化：对话记忆（`PostgresChatMemoryStore`）、执行链路遥测（`PostgresTraceStore`） |
| `chain-example` | 示例代码（34 个可运行 Demo） |

## 示例

`chain-example` 模块包含 34 个可运行示例，覆盖 LLM 调用、Agent/SubAgent/Skill、工作流、文档处理与切分、Elasticsearch 检索等场景。**完整示例索引、运行命令（`mvn exec:java`）与学习路径见 [docs/examples/overview.md](docs/examples/overview.md)**。

几个重点示例：

| 示例 | 说明 |
|------|------|
| `AgentLoopExample` | Agent 循环：旅行助手多工具多步骤推理 |
| `StreamingAgentExample` | Agent 流式输出：实时接收 LLM 文本/工具调用事件 |
| `BackgroundSubAgentExample` | 后台并行子代理：spawn / join / steer / resume / graceful max turns |
| `TraceTelemetryExample` | 执行链路遥测：录制整棵 span 树并序列化为 JSON |
| `ElasticsearchHybridExample` | ES 混合检索完整流程 |

## 架构

```
                      ┌─────────┐
                      │   LLM   │  (DashscopeLLM / VLLM / OpenAICompatibleLLM)
                      └────┬────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
        ┌─────┴─────┐ ┌───┴───┐ ┌─────┴─────┐
        │ Tool 框架  │ │ Graph │ │ Embedding │
        │ Registry  │ │ 引擎  │ │  Model    │
        └─────┬─────┘ └───┬───┘ └───────────┘
              │             │
         ┌────┴────┐       │
         │  Agent  │       │
         │  Loop   │       │
         └─────────┘       │
                          │
              ┌───────────┼───────────┐
              │                       │
     ┌────────┴────────┐    ┌────────┴────────┐
     │ KnowledgeStore  │    │  DocumentReader │
     │   (interface)   │    │ + 清洗管道       │
     └────────┬────────┘    │ + 文档切分       │
              │             └─────────────────┘
     ┌───────────────┐
     │ Elasticsearch │
     │ KnowledgeStore│
     │ + BM25        │
     │ + Hybrid      │
     └───────────────┘
```

## 文档与链接

- **分模块文档** — [`docs/`](docs/)：LLM / Tool / Agent / Graph / Document / Knowledge / Elasticsearch / Embedding 各模块详细说明
- **LLM 友好索引** — [`llms.txt`](llms.txt)：供 AI 工具消费的文档站点地图
- **更新日志** — [`CHANGELOG.md`](CHANGELOG.md)：版本变更记录
- **路线图** — [`TODO.md`](TODO.md)：待办与计划

## License

MIT
