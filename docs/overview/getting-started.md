# 快速开始

本文档将引导你在几分钟内完成 nonchain 的安装、配置和首次使用。

## 前置条件

在开始之前，请确保你的开发环境满足以下要求：

| 项目 | 要求 |
|------|------|
| JDK | 11 或更高版本 |
| Maven | 3.6 或更高版本 |

验证环境：

```bash
java -version
# 输出应包含 "11" 或更高版本号

mvn -version
# 输出应包含 "3.6" 或更高版本号
```

## 克隆与构建

从 GitHub 克隆项目并安装到本地 Maven 仓库：

```bash
git clone https://github.com/Nondirectional/nonchain.git
cd nonchain
mvn install -DskipTests
```

构建成功后，所有模块将被安装到本地 Maven 仓库，可以在其他项目中通过 Maven 依赖引用。

## 添加 Maven 依赖

根据你的需求，在项目的 `pom.xml` 中引入相应的模块依赖。

### 核心模块（必需）

核心模块提供了 LLM 调用、工具函数、工作流引擎、文档模型和知识存储接口：

```xml
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain</artifactId>
    <version>0.11.0</version>
</dependency>
```

### 文档处理模块（可选）

如需使用文档解析、清洗和切分功能：

```xml
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain-document</artifactId>
    <version>0.11.0</version>
</dependency>
```

### Elasticsearch 检索模块（可选）

如需使用 Elasticsearch 作为统一检索后端：

```xml
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain-elasticsearch</artifactId>
    <version>0.11.0</version>
</dependency>
```

### MySQL 持久化模块（可选）

如需使用 MySQL 持久化对话记忆：

```xml
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain-mysql</artifactId>
    <version>0.11.0</version>
</dependency>
```

## 配置环境变量

nonchain 的 LLM 调用和 Embedding 功能依赖阿里云 DashScope API。使用前需要设置 API Key 环境变量：

```bash
export DASHSCOPE_API_KEY=your-api-key-here
```

> **提示**：你也可以在创建 `DashscopeLLM` 实例时直接传入 API Key，但推荐使用环境变量方式，避免在代码中硬编码密钥。

## 第一个 LLM 对话

以下是一个最简单的 LLM 对话示例：

```java
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.ChatResult;

public class QuickStart {
    public static void main(String[] args) {
        // 创建 LLM 实例，指定 API Key 和模型名称
        LLM llm = new DashscopeLLM("your-api-key", "qwen-plus");

        // 发送对话请求
        ChatResult result = llm.chat("你是一个助手", "你好");

        // 输出回复内容
        System.out.println(result.getContent());
    }
}
```

运行以上代码，你将看到 LLM 的回复输出。

## 更多示例

nonchain 的 `chain-example` 模块包含 20 个可运行的示例，覆盖框架的所有功能：

| 示例类名 | 功能说明 |
|---------|---------|
| `FunctionCallExample` | 注解方式工具调用，多轮对话 |
| `FunctionCallRawExample` | 流式 API 工具调用 |
| `FunctionCallMultiParamExample` | 多参数工具：注解 vs 流式对比 |
| `StructuredOutputExample` | JSON Object 结构化输出 |
| `ImageInputExample` | 多模态图片输入 |
| `EasyWorkflowExample` | 图工作流 + 条件路由 |
| `GraphKnowledgeExample` | RAG 管道工作流 |
| `EmbeddingModelExample` | Embedding 模型使用 |
| `ElasticsearchHybridExample` | ES 混合检索完整流程 |
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

运行示例前，请确保已设置 `DASHSCOPE_API_KEY` 环境变量，然后在 IDE 中直接运行对应的 main 方法即可。

## 下一步

完成快速开始后，你可以继续阅读以下文档来深入了解各功能模块：

- [安装](installation.md) — 详细的安装和依赖说明
- [架构](architecture.md) — 框架的架构设计和设计原则
