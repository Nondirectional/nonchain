# 示例代码

`chain-example` 模块包含 20 个完整的示例程序，涵盖 nonchain 框架的所有核心功能。每个示例都可以独立运行，帮助快速理解和使用各个模块。

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

## 依赖说明

`chain-example` 模块依赖公开功能模块：

```xml
<dependencies>
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain</artifactId>
        <version>0.4.0</version>
    </dependency>
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain-elasticsearch</artifactId>
        <version>0.4.0</version>
    </dependency>
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain-document</artifactId>
        <version>0.4.0</version>
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

1. **LLM 基础**：`FunctionCallExample` -> `StructuredOutputExample` -> `ImageInputExample`
2. **文档处理**：`TxtDocumentReaderExample` -> `MarkdownDocumentReaderExample` -> `PdfDocumentReaderExample` -> `DocumentCleanerExample`
3. **文档切分**：`RecursiveCharacterSplitterExample` -> `HeaderDocumentSplitterExample` -> `CompositeDocumentSplitterExample` -> `SemanticSplitterExample`
4. **统一检索**：`EmbeddingModelExample` -> `ElasticsearchHybridExample` -> `GraphKnowledgeExample`
5. **工作流**：`EasyWorkflowExample` -> `GraphKnowledgeExample`
