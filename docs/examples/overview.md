# 示例代码

`chain-example` 模块包含 34 个完整的示例程序，涵盖 nonchain 框架的所有核心功能。每个示例都可以独立运行，帮助快速理解和使用各个模块。

## 运行示例

### 环境要求

- Java 11 及以上版本
- Maven 3.6+

### 环境变量

部分示例需要配置 API 密钥：

```bash
# DashScope API（用于 LLM 调用和 Embedding）
export DASHSCOPE_API_KEY=your_api_key_here

# Elasticsearch（用于统一检索示例）
# 确保 Elasticsearch 运行在 localhost:9200，服务端支持原生 retriever API，并安装 IK 分词插件

# Python（用于 RapidOCR 示例，通过 uv 运行）
# uv add rapidocr
```

### Maven 运行

```bash
# 在项目根目录下
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.FunctionCallExample"

# 指定类名运行
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.ElasticsearchHybridExample"
```

### IDE 运行

在 IDE（IntelliJ IDEA、Eclipse 等）中直接打开示例类并运行 `main` 方法。确保已设置环境变量。

## 示例列表

### LLM 调用

| 示例类 | 说明 |
|--------|------|
| `FunctionCallExample` | 注解方式工具调用，多轮对话 |
| `FunctionCallRawExample` | 流式 API 工具调用 |
| `FunctionCallMultiParamExample` | 多参数工具：注解 vs 流式对比 |
| `StructuredOutputExample` | JSON Object 结构化输出 |
| `ImageInputExample` | 多模态图片输入 |
| `StreamingChatExample` | LLM 流式输出：文本、思考内容和工具调用增量 |
| `VLLMExample` | vLLM provider：thinking 模式与思考预算配置 |
| `VLLMMultimodalExample` | vLLM 多模态：URL、本地文件、base64 图片输入 |
| `LocalModelSmokeTestExample` | 本地 LLM、Embedding、Reranker 服务连通性验证 |
| `AgentLoopExample` | Agent 循环：旅行助手多工具多步骤推理 |
| `StreamingAgentExample` | Agent 流式输出：实时接收 LLM 文本/工具调用事件 |
| `ToolInterceptorExample` | 工具拦截器：before 审核危险命令、after 结果脱敏 |
| `SubAgentExample` | 委派型子代理：DIRECT/DELEGATE 两种暴露模式，主 Agent 委派调研/撰写子代理 |
| `BackgroundSubAgentExample` | 后台子代理：并发执行、结果 join、查询与 steer |
| `SkillExample` | Skill 过程性知识注入：模型自主点选与注入模式配置 |
| `SubAgentSkillExample` | SubAgent + Skill：预加载检查清单并实时观察子代理事件 |
| `TraceTelemetryExample` | 执行链路遥测：录制 Agent/SubAgent/Flow 整棵 span 树，按 runtimeId 拉回并序列化为 JSON |
| `MessageLayeringExample` | 应用层消息分层：UI 状态消息进 transcript 不进 LLM |

#### FunctionCallExample

演示通过注解方式定义工具（`@Tool`、`@ToolParam`），并在多轮对话中自动调用工具。展示如何注册工具、发起对话并获取工具调用结果。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.FunctionCallExample"
```

#### FunctionCallRawExample

演示通过流式 API 定义工具调用，与注解方式不同，此方式更加灵活，适合动态工具定义的场景。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.FunctionCallRawExample"
```

#### FunctionCallMultiParamExample

演示多参数工具的定义和使用，对比注解方式和流式 API 两种定义方式的差异。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.FunctionCallMultiParamExample"
```

#### StructuredOutputExample

演示 JSON Object 结构化输出，通过 `OutputFormat` 控制 LLM 返回指定格式的 JSON 数据。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.StructuredOutputExample"
```

#### ImageInputExample

演示多模态图片输入，将图片和文本一起发送给 LLM 进行理解。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.ImageInputExample"
```

#### VLLMMultimodalExample

演示 vLLM 多模态图片输入，支持三种方式：URL 图片、本地文件（自动转 base64）、base64 数据。适用于 vLLM 本地部署的视觉模型（如 Qwen2-VL）。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.VLLMMultimodalExample"
```

前置条件：
- vLLM 服务运行中，且部署了视觉模型

#### StreamingChatExample

演示 `streamChat(...)` 的文本、思考内容和工具调用增量回调。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.StreamingChatExample"
```

#### VLLMExample

演示 `VLLM` provider 的 thinking 模式、思考预算和 OpenAI 兼容调用配置。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.VLLMExample"
```

#### LocalModelSmokeTestExample

验证本地部署的 LLM、Embedding 和 Reranker 三类服务是否可用。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.LocalModelSmokeTestExample"
```

#### BackgroundSubAgentExample

演示后台 SubAgent 并发、自动 join、`get_subagent_result` 查询和运行中 steer。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.BackgroundSubAgentExample"
```

#### SkillExample

演示使用 `SkillRegistry` 注册 PRD 审查与 Git 分支命名知识，由 LLM 自主点选，并展示 `SkillInjectionMode.USER` 配置。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.SkillExample"
```

#### SubAgentSkillExample

演示给代码审查子代理预加载 OWASP Skill，并通过 `AgentEvent.SubAgentProgress` 观察子代理内部 Round、Tool、Skill、Text、Error 和 Complete 事件。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.SubAgentSkillExample"
```

#### MessageLayeringExample

演示应用层消息与 LLM 消息分层：用 `Message.note(kind, content)` 产生 UI-only 状态消息（如"正在思考"、"已读取文件"），它进对话 transcript 供 UI 重放，但在 LLM 边界被剥离，不污染 LLM 上下文。本示例纯本地运行，不需要 API 密钥，依次演示产生应用层消息、序列化往返、裁剪交互（不占预算/原位保留）、LLM 边界过滤四层语义。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.MessageLayeringExample"
```

### 工作流

| 示例类 | 说明 |
|--------|------|
| `EasyWorkflowExample` | 图工作流 + 条件路由 |
| `GraphKnowledgeExample` | RAG 管道工作流 |

#### EasyWorkflowExample

演示基于图（Graph）的工作流引擎，包括节点定义、边连接和条件路由。展示如何构建复杂的处理流程。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.EasyWorkflowExample"
```

#### GraphKnowledgeExample

演示将 RAG（检索增强生成）管道构建为图工作流，展示文档检索、上下文组装和 LLM 生成的完整流程。同时展示了 `onEvent` 事件回调的用法。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.GraphKnowledgeExample"
```

### Embedding 与存储

| 示例类 | 说明 |
|--------|------|
| `EmbeddingModelExample` | Embedding 模型使用 |
| `ElasticsearchHybridExample` | ES 混合检索完整流程 |
| `ElasticsearchContextExpansionExample` | 命中后扩展相邻上下文窗口 |

#### EmbeddingModelExample

演示 `EmbeddingModel` 的使用，包括单文本嵌入和批量嵌入。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.EmbeddingModelExample"
```

#### ElasticsearchHybridExample

演示 Elasticsearch 统一检索的完整流程：创建 Store、写入数据、构造统一 `SearchRequest`，并在 ES 内部完成 hybrid 检索。需要服务端支持原生 retriever API。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.ElasticsearchHybridExample"
```

前置条件：
1. Elasticsearch 运行在 localhost:9200，且服务端支持原生 retriever API
2. 已安装 IK Analysis 插件

#### ElasticsearchContextExpansionExample

演示先通过 BM25 命中一个中心 chunk，再调用 `expandContext(...)` 获取前后相邻 chunk。适合展示 Agent 在发现片段不完整时如何补齐上下文窗口。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.ElasticsearchContextExpansionExample"
```

前置条件：
1. Elasticsearch 运行在 localhost:9200
2. 已安装 IK Analysis 插件

### 文档处理

| 示例类 | 说明 |
|--------|------|
| `TxtDocumentReaderExample` | TXT 文档解析 |
| `MarkdownDocumentReaderExample` | Markdown 文档解析 |
| `HtmlDocumentReaderExample` | HTML 文档解析 |
| `DocxDocumentReaderExample` | Word 文档解析 |
| `PdfDocumentReaderExample` | PDF 解析 + OCR |
| `DocumentCleanerExample` | 文档清洗管道 |

#### TxtDocumentReaderExample

演示 `TxtDocumentReader` 的使用，解析纯文本文件为 `ParsedDocument`。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.TxtDocumentReaderExample"
```

#### MarkdownDocumentReaderExample

演示 `MarkdownDocumentReader` 的使用，解析 Markdown 文件，提取标题、段落和代码块。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.MarkdownDocumentReaderExample"
```

#### HtmlDocumentReaderExample

演示 `HtmlDocumentReader` 的使用，解析 HTML 文件，提取标题、表格、代码块和文本。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.HtmlDocumentReaderExample"
```

#### DocxDocumentReaderExample

演示 `DocxDocumentReader` 的使用，解析 Word 文档，提取标题、段落、表格和嵌入图片。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.DocxDocumentReaderExample"
```

#### PdfDocumentReaderExample

演示 `PdfDocumentReader` 的使用，包括普通 PDF 的文本和图片提取、扫描件 PDF 的 OCR 识别。需要 RapidOCR 或 Tesseract OCR 环境。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.PdfDocumentReaderExample"
```

前置条件：
- 安装 RapidOCR：`uv add rapidocr`
- 或安装 Tesseract：确保 `tesseract` 在系统 PATH 中

#### DocumentCleanerExample

演示 `CleanerPipeline` 的使用，串联多个清洗器对 PDF 解析结果进行清洗，包括控制字符移除、Unicode 规范化、空白合并、样板移除、去重、短片段合并和图片策略处理。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.DocumentCleanerExample"
```

### 文档切分

| 示例类 | 说明 |
|--------|------|
| `RecursiveCharacterSplitterExample` | 递归字符切分 |
| `HeaderDocumentSplitterExample` | 标题层级切分 |
| `CompositeDocumentSplitterExample` | 组合切分 |
| `SemanticSplitterExample` | 语义切分 |
| `LlmDocumentSplitterExample` | LLM 语义切分 |

#### RecursiveCharacterSplitterExample

演示 `RecursiveCharacterSplitter` 的三种用法：
1. 基础用法 -- 按字符数切分纯文本
2. 原子元素保护 -- 表格、代码块、图片保持完整
3. Token 切分 -- 配合 `TokenMeasure` 按 token 数切分

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.RecursiveCharacterSplitterExample"
```

#### HeaderDocumentSplitterExample

演示 `HeaderDocumentSplitter` 的两种用法：
1. 基础用法 -- 按 H1/H2 标题层级切分，自动维护标题路径
2. 原子元素 -- 表格等在标题切分下独立输出并携带章节信息

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.HeaderDocumentSplitterExample"
```

#### CompositeDocumentSplitterExample

演示 `CompositeDocumentSplitter` 的用法：先用 `HeaderDocumentSplitter` 按标题拆章节，再用 `RecursiveCharacterSplitter` 对每个章节细分。展示组合切分如何同时保留文档结构和控制 chunk 粒度。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.CompositeDocumentSplitterExample"
```

#### SemanticSplitterExample

演示 `SemanticSplitter` 的用法：基于 `EmbeddingModel` 计算相邻文本段的语义相似度，在语义断点处切分。包括纯文本切分和包含原子元素的切分两种场景。

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.SemanticSplitterExample"
```

前置条件：
- 需要配置 `DASHSCOPE_API_KEY` 环境变量（使用 DashScope Embedding 模型）

#### LlmDocumentSplitterExample

演示 `LlmDocumentSplitter` 的三种用法：
1. 纯文本切分 -- 对文本进行语义清洗和智能切分
2. 含原子元素切分 -- 表格、代码块自动透传，仅文本部分走 LLM
3. 自定义 prompt -- 使用英文 prompt 进行切分

```bash
mvn compile exec:java -pl chain-example \
    -Dexec.mainClass="com.non.chain.example.LlmDocumentSplitterExample"
```

前置条件：
- 需要配置 `DASHSCOPE_API_KEY` 环境变量（使用 DashScope LLM）

## 依赖说明

`chain-example` 模块依赖公开功能模块：

```xml
<dependencies>
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain</artifactId>
        <version>0.11.0</version>
    </dependency>
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain-elasticsearch</artifactId>
        <version>0.11.0</version>
    </dependency>
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain-document</artifactId>
        <version>0.11.0</version>
    </dependency>
    <!-- LLM API -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-dashscope</artifactId>
        <version>0.30.0</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-ollama</artifactId>
        <version>0.30.0</version>
    </dependency>
    <!-- Tokenizer -->
    <dependency>
        <groupId>com.knuddels</groupId>
        <artifactId>jtokkit</artifactId>
        <version>1.1.0</version>
    </dependency>
    <!-- Document reader dependencies -->
    <dependency>
        <groupId>org.commonmark</groupId>
        <artifactId>commonmark</artifactId>
        <version>0.22.0</version>
    </dependency>
    <dependency>
        <groupId>org.apache.pdfbox</groupId>
        <artifactId>pdfbox</artifactId>
        <version>2.0.31</version>
    </dependency>
    <dependency>
        <groupId>org.apache.poi</groupId>
        <artifactId>poi-ooxml</artifactId>
        <version>5.2.5</version>
    </dependency>
    <dependency>
        <groupId>org.jsoup</groupId>
        <artifactId>jsoup</artifactId>
        <version>1.17.2</version>
    </dependency>
</dependencies>
```

## 推荐学习路径

1. **LLM 基础**：`FunctionCallExample` -> `StreamingChatExample` -> `StructuredOutputExample` -> `ImageInputExample` -> `VLLMMultimodalExample`
2. **Agent 与 Skill**：`AgentLoopExample` -> `SubAgentExample` -> `BackgroundSubAgentExample` -> `SkillExample` -> `SubAgentSkillExample`
3. **文档处理**：`TxtDocumentReaderExample` -> `MarkdownDocumentReaderExample` -> `PdfDocumentReaderExample` -> `DocumentCleanerExample`
4. **文档切分**：`RecursiveCharacterSplitterExample` -> `HeaderDocumentSplitterExample` -> `CompositeDocumentSplitterExample` -> `SemanticSplitterExample` -> `LlmDocumentSplitterExample`
5. **统一检索**：`EmbeddingModelExample` -> `ElasticsearchHybridExample` -> `GraphKnowledgeExample`
6. **工作流**：`EasyWorkflowExample` -> `GraphKnowledgeExample`
