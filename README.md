# nonchain

一个轻量级的 Java AI 应用开发框架，提供 LLM 调用、工具函数、工作流编排、文档处理和知识检索能力。

## 特性

- **LLM Provider 抽象** — 统一的 LLM 调用接口，支持阿里云 DashScope、vLLM 及任何 OpenAI 兼容端点（Ollama、LiteLLM）
- **流式输出** — `streamChat()` 逐 token 输出，支持思考内容和工具调用流式
- **工具函数框架** — 注解驱动 + 流式 API 两种方式定义工具，自动注册与调度
- **Agent 循环** — LLM + 工具自动调用循环，Builder 模式，支持 ChainCallback 统一回调、流式事件输出、工具并行执行、工具拦截器（before/after，可阻止/改写工具调用）和委派型子代理（SubAgent，父 Agent 自主委派子任务并拿回结果）
- **应用层消息分层** — `Message.note()` 产生 UI-only 状态消息（"正在思考"、"已读取文件"），进 transcript 供 UI 重放，在 LLM 边界被剥离不污染上下文
- **图工作流引擎** — 基于有向图的多步骤工作流编排，支持条件路由和事件回调
- **多模态输入** — 支持文本 + 图片混合消息，配合视觉模型进行图片理解
- **文档处理** — 支持 TXT/Markdown/HTML/DOCX/PDF 解析，含 OCR 和清洗管道
- **文档切分** — 5 种切分策略：递归字符、标题层级、语义、组合切分、LLM 语义切分
- **统一检索** — Elasticsearch 单独承担向量检索、BM25 与混合检索，支持元数据过滤和 RRF / Linear 融合策略
- **上下文扩展** — 命中 chunk 后基于 chunkIndex 向前后扩展邻居 chunk，补齐上下文窗口
- **统一回调 (ChainCallback)** — 覆盖 LLM、Tool、Retrieval、Graph 的 Start/Complete/Error 生命周期，支持 traceId 关联和多订阅者组合
- **结构化输出** — 支持 JSON Object 响应格式

## 要求

- Java 11+（已在 Java 11 / 17 / 21 上测试通过）
- Maven 3.6+

## 快速开始

### 安装

```bash
git clone https://github.com/nondirectionl/nonchain.git
cd nonchain
mvn install -DskipTests
```

### 引入依赖

核心模块：

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain</artifactId>
    <version>0.8.0</version>
</dependency>
```

文档处理（可选）：

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-document</artifactId>
    <version>0.8.0</version>
</dependency>
```

Elasticsearch 向量存储（可选）：

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-elasticsearch</artifactId>
    <version>0.8.0</version>
```

</dependency>
```

### LLM 调用

```java
// 创建 LLM 实例
LLM llm = new DashscopeLLM("your-api-key", "qwen-plus");

// 简单对话
ChatResult result = llm.chat("你是一个助手", "你好");
System.out.println(result.getContent());
```

### 多模态图片输入

```java
LLM llm = new DashscopeLLM("qwen-vl-plus");

Message userMessage = Message.user(Arrays.asList(
        ImageUrlPart.of("https://example.com/image.jpg"),
        TextPart.of("图片中有什么？")
));

ChatResult result = llm.chat(Arrays.asList(userMessage));
System.out.println(result.content());
```

### 工具函数调用

**注解方式：**

```java
public class WeatherService {

    @ToolDef(name = "get_weather", description = "获取指定城市的天气")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称") String city) {
        return city + ": 晴, 25°C";
    }
}

// 注册并使用
ToolRegistry registry = new ToolRegistry();
registry.scan(new WeatherService());

ChatResult result = llm.chat("北京天气怎么样？", registry.getTools());
if (result.hasToolCalls()) {
    for (ToolCall call : result.getToolCalls()) {
        String output = registry.execute(call.getName(), call.getArguments());
        // 将工具结果反馈给 LLM...
    }
}
```

**流式 API 方式：**

```java
ToolRegistry registry = new ToolRegistry();
registry.register("get_weather", "获取城市天气")
        .param("city", "城市名称")
        .handle(args -> args.getString("city") + ": 晴, 25°C");
```

### Agent 自动调用

```java
// 注册工具
ToolRegistry registry = new ToolRegistry().scan(new WeatherService());

// 构建 Agent（可选：通过 ChainCallback 观察执行过程）
ChainCallback callback = new ChainCallback() {
    @Override
    public void onLlmComplete(LlmCompleteEvent event) {
        System.out.println("[LLM] 耗时: " + event.latencyMs() + "ms");
    }
    @Override
    public void onToolComplete(ToolCompleteEvent event) {
        System.out.println("[Tool] " + event.toolName() + " → " + event.result());
    }
};
Agent agent = Agent.builder(llm, registry)
        .systemPrompt("你是一个旅行助手")
        .maxIterations(5)
        .callback(callback)
        .build();

// 一行搞定：自动循环调用工具直到完成
ChatResult result = agent.run("北京和上海天气怎么样？");
System.out.println(result.content());
```

### 工具拦截器

工具拦截器（`BeforeToolCall` / `AfterToolCall`）在不修改工具实现、不继承 `Agent` 的前提下，对工具调用进行**拦截、阻止、改写**。与 `ChainCallback`（只读观察）正交——拦截器是**控制**层。

- **before**：工具执行前调用，可 `block(reason)` 阻止执行（reason 回灌 LLM）；多个 before 任一 block 即短路
- **after**：工具执行后、结果回灌 LLM 前调用，可改写 `content`（脱敏/截断）或标记 `isError`；多个 after 链式叠加

```java
Agent agent = Agent.builder(llm, registry)
        .systemPrompt("你是查询助手")
        // before：拦截危险关键词
        .addBeforeToolCall(ctx -> {
            if (ctx.arguments().contains("rm -rf")) {
                return BeforeResult.block("危险命令禁止");
            }
            return BeforeResult.pass();
        })
        // after：对结果脱敏（手机号替换为 ***）
        .addAfterToolCall(ctx ->
                AfterResult.content(ctx.result().replaceAll("\\d{11}", "***********")))
        .build();

agent.run("查询用户信息");
```

典型场景：危险命令审核/确认、工具结果脱敏、超长输出截断、工具熔断（黑名单/配额）。

> **注意**：拦截器异常会被包装为 `AgentException` 抛出（不静默吞，让失败可见）；而 `ChainCallback` 的异常被静默隔离（观察失败不影响主流程）。两者职责不同，可在同一 Agent 共存。

### 委派型子代理（SubAgent）

委派型子代理让**父 Agent 通过 tool calling 自主把子任务委派给一个专职子代理**，子代理独立运行后把最终文本结果回传，父 Agent 继续后续推理。适合「调研 / 撰写」「规划 / 执行」这类可分工的场景。

子代理作为一等 tool 能力注册在 `ToolRegistry`，由 `Agent.Builder` 决定暴露方式：

- **DIRECT**（默认）：每个子代理暴露为一个独立 tool（schema 仅含 `task` 参数），父 Agent 直接 `research(task)` 委派
- **DELEGATE**（显式开启）：只暴露一个通用 `delegate_to_subagent(agentName, task)`，`agentName` 为已注册子代理名的枚举

```java
ToolRegistry registry = new ToolRegistry();

// 声明式注册子代理：description（给 LLM）与 systemPrompt（子代理角色）分开，description 必填
registry.registerSubAgent("research", "负责调研与归纳")
        .systemPrompt("你是调研代理。优先归纳事实，不编造。")
        .toolRegistry(researchTools)   // 可选：子代理专属工具集（不设为无工具子代理）
        .maxIterations(3)              // 可选：默认回退框架默认值
        // .llm(researchLlm)           // 可选：默认继承父 Agent 的 LLM
        .build();

registry.registerSubAgent("writer", "负责撰写回复")
        .systemPrompt("你是撰写代理。")
        .build();

// 父 Agent：默认 DIRECT 模式
Agent agent = Agent.builder(llm, registry)
        .systemPrompt("你是主助手，把子任务委派给合适的子代理。")
        .build();

agent.run("帮我调研量子计算并写一段介绍");
```

需要统一入口时切到 DELEGATE 模式：

```java
Agent agent = Agent.builder(llm, registry)
        .subAgentExposureMode(SubAgentExposureMode.DELEGATE)
        .build();
```

**默认行为与边界**：

- 子代理默认**无状态**：独立 `systemPrompt`、独立工具集、独立 `before/after` 拦截器、独立 `maxIterations`，默认**继承父 LLM**
- **上下文裁剪**：框架自动从父消息链裁剪上下文注入子代理（含相关 user/assistant/tool，**排除 `llmVisible=false` 应用层消息**，**不含父 `systemPrompt`**）；可在注册时用 `contextSelector(...)` 覆盖默认裁剪策略
- **callback / trace 隔离**：父侧把子代理视为一次普通工具调用（`onToolStart`/`onToolComplete`/`onToolError`），子代理内部事件不透出到父
- **错误语义**：子代理整体失败 → 外层记 `ToolErrorEvent` 并把错误文本回灌父 Agent，父循环继续
- **并行**：同一轮多个子代理调用可并行执行，结果按原始 tool call 顺序回灌
- **仅一层**：子代理不能再委派；**仅支持 `Agent` 自动循环**，手写 `registry.execute("子代理名", ...)` 会 fail-fast

> **拦截器边界**：父 Agent 的 `before/after` 拦截器作用于外层子代理 tool 调用（可阻止/改写整次委派）；子代理注册时配置的拦截器仅作用于子代理内部工具，不继承父拦截器。

### 应用层消息与 LLM 消息分层

应用层消息（`Message.note(kind, content)`）记录 UI-only 状态进对话 transcript，供 UI 重放历史，但**不进入 LLM 上下文**——在 LLM 调用边界被自动剥离。

- **产生**：`Message.note("status", "已读取文件 X")` 产出 `role="note"`、`llmVisible=false` 的应用层消息，`kind` 为应用自定义语义标签（如 `status`/`ui`/`artifact`）
- **过滤**：`llmVisible=false` 的消息在 provider 请求构建时被单点剥离，所有 LLM provider 共用
- **持久化**：随 `ChatMemory` 正常存取（`MessageSerializer` 往返标记），UI 可从 transcript 重建含应用层消息的完整历史
- **裁剪**：应用层消息不计入窗口/token 预算、原位保留，不破坏 tool 消息配对保护

```java
// 应用层消息：进 transcript，不进 LLM
Message note = Message.note("status", "正在读取文件 config.json");

List<Message> messages = new ArrayList<>();
messages.add(Message.user("读取配置文件"));
messages.add(note);                       // UI 会看到，LLM 看不到
messages.add(Message.assistant("..."));

// LLM 边界自动过滤：provider 请求只含 user/assistant，不含 note
ChatResult result = llm.chat(messages, OutputFormat.TEXT);
```

典型场景：UI 状态条（"正在思考"、"工具审核中"）、artifact 记录、通知重放。现有 `Message.user/assistant/...` 工厂产出的消息默认 `llmVisible=true`，行为与改动前完全一致。

### 工作流编排

```java
Graph graph = Graph.builder()
        .addNode(Node.of("classify", state -> {
            // LLM 分类
            return state;
        }))
        .addNode(Node.of("technical", state -> {
            // 技术问题处理
            return state;
        }))
        .addNode(Node.of("general", state -> {
            // 通用问题处理
            return state;
        }))
        .addEdge(Edge.of("classify", "technical"))  // 或条件路由
        .addEdge(Edge.conditional("classify", state -> {
            String type = state.getOrDefault("type", "general");
            return type.equals("technical") ? "technical" : "general";
        }))
        .startNode("classify")
        .build();

GraphResult result = graph.run(State.of(Map.of(), List.of(
        Message.user("什么是 JVM 调优？")
)));
```

### 文档处理

```java
// 自动识别文件类型并解析
DocumentReaders readers = new DocumentReaders();
readers.register(new PdfDocumentReader());
readers.register(new DocxDocumentReader());
readers.register(new MarkdownDocumentReader());

ParsedDocument doc = readers.read(new File("document.pdf"));

// 清洗管道
CleanerPipeline pipeline = CleanerPipeline.of(
    new ControlCharacterRemover(),
    new UnicodeNormalizer(),
    new WhitespaceNormalizer(),
    new BoilerplateRemover(),
    new DuplicateRemover(),
    new ShortFragmentMerger(),
    new ImageStrategyCleaner(ImageStrategyCleaner.Strategy.REMOVE)
);

ParsedDocument cleaned = pipeline.clean(doc);
```

### 文档切分

提供 5 种切分策略，适用于不同的文档处理场景：

**递归字符切分** — 按分隔符层级递归切分，支持字符数和 Token 数度量：

```java
RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
        .chunkSize(500)
        .chunkOverlap(50)
        .build();

List<TextChunk> chunks = splitter.split(document);
```

**标题层级切分** — 基于 Markdown 标题按文档结构拆分：

```java
HeaderDocumentSplitter splitter = new HeaderDocumentSplitter(
        List.of(1, 2),  // 在 H1 和 H2 处切分
        true            // 将标题包含在内容中
);
```

**语义切分** — 基于 Embedding 计算语义相似度，在话题切换处切分：

```java
SemanticSplitter splitter = SemanticSplitter.builder(embeddingModel)
        .bufferSize(1)
        .breakpointThreshold(0.5)
        .maxChunkSize(1000)
        .build();
```

**组合切分** — 先按结构切分，再对每个 chunk 二次细分：

```java
CompositeDocumentSplitter splitter = new CompositeDocumentSplitter(
        new HeaderDocumentSplitter(List.of(1)),
        RecursiveCharacterSplitter.builder().chunkSize(300).build()
);
```

**LLM 语义切分** — 基于 LLM 进行语义清洗和智能切分，chunk 质量最优：

```java
LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(llm)
        .targetChunkSize(500)
        .contentMeasure(new TokenMeasure(EncodingType.CL100K_BASE))
        .build();
```

### 检索

统一检索入口由 `ElasticsearchKnowledgeStore.search(SearchRequest)` 提供：

```java
ElasticsearchKnowledgeStore store = ElasticsearchKnowledgeStore.builder(esClient, 1024)
        .build();

RetrievalResponse response = store.search(SearchRequest.builder()
        .queryText("查询文本")
        .queryEmbedding(queryEmbedding)   // 仅文本 -> BM25；仅向量 -> kNN；两者同时存在 -> hybrid
        .size(5)
        .addKnowledgeBaseId("kb-demo")
        .debug(true)
        .build();

for (SearchResult result : response.results()) {
    System.out.printf("[%.4f] %s%n", result.score(), result.content());
}
```

## 模块说明

| 模块 | 说明 |
|------|------|
| `chain` | 核心模块：LLM 抽象、工具函数、图工作流、统一回调 (ChainCallback)、知识存储接口、文档模型、Embedding、多模态消息、MessageSerializer |
| `chain-document` | 文档处理：TXT/MD/HTML/DOCX/PDF 解析 + OCR + 清洗管道 + 5 种文档切分策略 |
| `chain-elasticsearch` | Elasticsearch 向量存储、BM25 检索、原生 retriever 混合检索 |
| `chain-mysql` | MySQL 对话记忆持久化存储 |
| `chain-postgres` | PostgreSQL 对话记忆持久化存储 |
| `chain-example` | 示例代码（可运行 Demo） |

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

## 示例

`chain-example` 模块包含多组可运行示例：

| 示例 | 说明 |
|------|------|
| `FunctionCallExample` | 注解方式工具调用，多轮对话 |
| `FunctionCallRawExample` | 流式 API 工具调用 |
| `FunctionCallMultiParamExample` | 多参数工具：注解 vs 流式对比 |
| `StructuredOutputExample` | JSON Object 结构化输出 |
| `ImageInputExample` | 多模态图片输入 |
| `StreamingChatExample` | 流式输出：基础流式、思考模式、工具调用 |
| `AgentLoopExample` | Agent 循环：旅行助手多工具多步骤推理 |
| `StreamingAgentExample` | Agent 流式输出：实时接收 LLM 文本/工具调用事件 |
| `ToolInterceptorExample` | 工具拦截器：before 审核危险命令、after 结果脱敏 |
| `SubAgentExample` | 委派型子代理：DIRECT/DELEGATE 两种暴露模式，主 Agent 委派调研/撰写子代理 |
| `MessageLayeringExample` | 应用层消息分层：UI 状态消息进 transcript 不进 LLM |
| `VLLMExample` | vLLM provider：thinking 模式、思考预算控制 |
| `VLLMMultimodalExample` | vLLM 多模态：URL、本地文件、base64 图片输入 |
| `EasyWorkflowExample` | 图工作流 + 条件路由 |
| `GraphKnowledgeExample` | RAG 管道工作流 |
| `EmbeddingModelExample` | Embedding 模型使用 |
| `ElasticsearchHybridExample` | ES 混合检索完整流程 |
| `ElasticsearchContextExpansionExample` | 命中后扩展相邻上下文窗口 |
| `TxtDocumentReaderExample` | TXT 文档解析 |
| `MarkdownDocumentReaderExample` | Markdown 文档解析 |
| `HtmlDocumentReaderExample` | HTML 文档解析 |
| `DocxDocumentReaderExample` | Word 文档解析 |
| `PdfDocumentReaderExample` | PDF 解析 + OCR |
| `DocumentCleanerExample` | 文档清洗管道 |
| `RecursiveCharacterSplitterExample` | 递归字符切分（字符数 / Token 数） |
| `HeaderDocumentSplitterExample` | 标题层级切分 |
| `CompositeDocumentSplitterExample` | 组合切分（标题 + 字符） |
| `SemanticSplitterExample` | 语义切分（基于 Embedding） |
| `LlmDocumentSplitterExample` | LLM 语义切分 |

运行示例前需设置环境变量：

```bash
export DASHSCOPE_API_KEY=your-api-key
```

## License

MIT
