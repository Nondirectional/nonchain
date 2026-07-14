# 安装

本文档详细介绍 nonchain 的系统要求、构建方式和各模块的依赖配置。

## 系统要求

| 项目 | 最低版本 | 推荐版本 | 说明 |
|------|---------|---------|------|
| JDK | 11 | 17+ | nonchain 使用 Java 11 编译，兼容 Java 11 及以上版本 |
| Maven | 3.6 | 3.8+ | 用于构建项目和依赖管理 |

### 可选依赖环境

以下环境仅在需要对应功能时才需要准备：

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| Elasticsearch | 支持 retriever API 的版本 | 使用 Elasticsearch 统一检索时需要 |
| Tesseract | 4.x+ | 使用 Tesseract OCR 引擎解析 PDF 中的图片时需要 |

## 从源码构建

### 克隆项目

```bash
git clone https://github.com/nondirectionl/nonchain.git
cd nonchain
```

### 构建

跳过测试执行快速构建（推荐首次安装时使用）：

```bash
mvn install -DskipTests
```

完整构建（包含单元测试）：

```bash
mvn install
```

仅构建特定模块：

```bash
mvn install -pl chain -am        # 仅构建核心模块
mvn install -pl chain-document -am  # 仅构建文档处理模块
```

构建成功后，各模块的 JAR 包将安装到本地 Maven 仓库（`~/.m2/repository/com/non/`）。

## Maven 依赖

以下列出各模块的 Maven 依赖配置及说明。

### 核心模块 `chain`

核心模块是使用 nonchain 的基础依赖，提供了 LLM 调用、工具函数、工作流引擎、消息模型、文档模型和知识存储接口。

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain</artifactId>
    <version>0.11.0</version>
</dependency>
```

核心模块会自动引入以下传递依赖：

| 依赖 | 版本 | 说明 |
|------|------|------|
| `com.openai:openai-java` | 4.30.0 | OpenAI Java SDK，用于 DashScope 兼容的 API 调用 |

### 文档处理模块 `chain-document`

文档处理模块提供了 TXT、Markdown、HTML、DOCX、PDF 格式的解析器、清洗管道和 4 种切分策略。

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-document</artifactId>
    <version>0.11.0</version>
</dependency>
```

文档处理模块中，各格式解析器的依赖为 optional（可选），需要按需显式引入：

```xml
<!-- Markdown 解析（使用 MarkdownDocumentReader 时需要） -->
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
    <version>0.22.0</version>
</dependency>

<!-- PDF 解析（使用 PdfDocumentReader 时需要） -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>2.0.31</version>
</dependency>

<!-- DOCX 解析（使用 DocxDocumentReader 时需要） -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>

<!-- HTML 解析（使用 HtmlDocumentReader 时需要） -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.17.2</version>
</dependency>

<!-- Token 度量（使用 TokenMeasure 进行 Token 数切分时需要） -->
<dependency>
    <groupId>com.knuddels</groupId>
    <artifactId>jtokkit</artifactId>
    <version>1.1.0</version>
</dependency>
```

> **说明**：如果只需要使用 TXT 解析、清洗管道和递归字符切分（基于字符数度量），则无需引入以上任何 optional 依赖，仅引入 `chain-document` 模块即可。

### Elasticsearch 检索模块 `chain-elasticsearch`

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-elasticsearch</artifactId>
    <version>0.11.0</version>
</dependency>
```

该模块会自动引入以下传递依赖：

| 依赖 | 版本 | 说明 |
|------|------|------|
| `co.elastic.clients:elasticsearch-java` | 8.13.4 | 与 Java 11 兼容的 Elasticsearch 官方 Java 客户端 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.1 | JSON 序列化/反序列化 |

> **注意**：使用 Elasticsearch 模块时，需要确保服务端已安装并运行、支持原生 retriever API，并安装 IK 分词插件。

## 完整依赖配置示例

以下是一个同时使用所有功能模块的完整 `pom.xml` 依赖配置示例：

```xml
<dependencies>
    <!-- 核心模块 -->
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain</artifactId>
        <version>0.11.0</version>
    </dependency>

    <!-- 文档处理模块 -->
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain-document</artifactId>
        <version>0.11.0</version>
    </dependency>

    <!-- 文档解析 optional 依赖 -->
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
    <dependency>
        <groupId>com.knuddels</groupId>
        <artifactId>jtokkit</artifactId>
        <version>1.1.0</version>
    </dependency>

    <!-- Elasticsearch 检索 -->
    <dependency>
        <groupId>com.non</groupId>
        <artifactId>chain-elasticsearch</artifactId>
        <version>0.11.0</version>
    </dependency>
</dependencies>
```

## 环境变量

| 环境变量 | 必需 | 说明 |
|---------|------|------|
| `DASHSCOPE_API_KEY` | 是 | 阿里云 DashScope API Key，用于 LLM 调用和 Embedding 生成 |

设置方式：

```bash
# Linux / macOS
export DASHSCOPE_API_KEY=your-api-key-here

# 或在 Java 启动参数中指定
java -DDASHSCOPE_API_KEY=your-api-key-here -jar your-app.jar
```

## 故障排查

### 构建失败

- 确认 JDK 版本 >= 11：`java -version`
- 确认 Maven 版本 >= 3.6：`mvn -version`
- 尝试清理后重新构建：`mvn clean install -DskipTests`

### LLM 调用失败

- 确认 `DASHSCOPE_API_KEY` 环境变量已正确设置
- 确认 API Key 有效且有对应的模型访问权限
- 确认网络可以访问阿里云 DashScope API 服务

### Elasticsearch 连接失败

- 确认 Elasticsearch 服务已启动且支持原生 retriever API
- 确认连接参数（host、port）正确
- 确认已安装 ik 分词插件
