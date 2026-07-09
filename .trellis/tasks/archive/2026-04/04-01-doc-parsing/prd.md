# Document Parsing & Extraction Module

## Goal

为 nonchain 库添加文档解析与提取能力，补全知识库 RAG 链路中「原始文档 → 结构化文本」这一缺失环节。

## Requirements

* 定义 DocumentReader 统一抽象接口（核心模块）
* 定义 ParsedDocument + DocumentElement 输出模型（扁平序列 + 类型标记）
* 定义 DocumentSource 输入源值对象
* 定义 DocumentReaders 工具类（Reader 注册与自动选择）
* 新建 chain-document 模块，提供常见格式的解析实现
* 支持从文档中提取嵌入的图片资源（byte[] + mimeType）
* 记录内容（文本、图片）在原始文档中的位置信息

## Acceptance Criteria

* [ ] DocumentReader 接口定义完成
* [ ] DocumentSource、ParsedDocument、DocumentMetadata 值对象定义完成
* [ ] DocumentElement 抽象基类 + 所有子类（Text/Heading/Image/Table/CodeBlock）定义完成
* [ ] DocumentPosition 值对象定义完成
* [ ] DocumentReaders 工具类定义完成
* [ ] TXT Reader 实现完成
* [ ] Markdown Reader 实现完成
* [ ] PDF Reader 实现完成
* [ ] DOCX Reader 实现完成（含图片提取）
* [ ] HTML Reader 实现完成
* [ ] 单元测试覆盖核心逻辑

## Definition of Done

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes

## Decision (ADR-lite)

### Decision 1: 输出数据模型 — 方向 C（扁平序列 + 类型标记）[已确认]

**Context**: 需要同时支持文本提取和图片提取，并保留位置信息。纯文本模型不够用，树结构过于复杂。

**Decision**: 采用扁平元素序列模型。文档解析为 `List<DocumentElement>`，每个元素带有 `ElementType` 标记和 `DocumentPosition` 位置信息。通过元素类型的有序出现隐式表达文档结构。

**Consequences**: 下游消费简单，格式无关易扩展；丢失显式嵌套层级，可通过 metadata 弥补。

### Decision 2: 模块组织 — 方案 A（单模块 chain-document）[已确认]

**Context**: 解析实现需要一个独立模块来承载第三方依赖（PDFBox、POI、jsoup 等）。

**Decision**: 单一 `chain-document` 模块，所有格式解析实现放在同一模块。第三方依赖设为 `<optional>true</optional>`。

**Consequences**: Maven 结构简单；用户不用的格式不会传递依赖；模块内按格式分包。

### Decision 3: 输入源 — DocumentSource 值对象 [已确认]

**Context**: Reader 需要同时获取内容流和文件格式信息。

**Decision**: 引入 `DocumentSource` 值对象，包含 `InputStream`、`fileName`（可选）、`contentType`（可选）和 `metadata`。

### Decision 4: Reader 选择 — DocumentReaders 工具类 [已确认]

**Context**: 需要根据文件类型自动选择对应 Reader。

**Decision**: `DocumentReaders` 工具类，`create()` 静态工厂自动注册所有内置 Reader，`read()` 方法自动选择。硬编码注册（Java 11 无 SPI）。

### Decision 5: 格式优先级 [已确认]

| 优先级 | 格式 | 依赖库 |
|--------|------|--------|
| P0 | TXT | 无 |
| P0 | Markdown | commonmark |
| P1 | PDF | Apache PDFBox |
| P1 | DOCX | Apache POI |
| P2 | HTML | jsoup |

## Out of Scope

* TextSplitter 实现
* Ingestion Pipeline 编排（读取→分块→向量化→存储 的完整流程）
* OCR 图片转文字能力

## Technical Approach

### 包结构

```
chain/src/main/java/com/non/chain/document/
├── DocumentReader.java          (接口)
├── DocumentSource.java          (值对象)
├── ParsedDocument.java          (值对象)
├── DocumentMetadata.java        (值对象)
├── DocumentElement.java         (抽象基类)
├── TextElement.java
├── HeadingElement.java
├── ImageElement.java
├── TableElement.java
├── CodeBlockElement.java
├── DocumentPosition.java        (值对象)
├── ElementType.java             (枚举)
└── DocumentReaders.java         (工具类)

chain-document/src/main/java/com/non/chain/document/
├── txt/TxtDocumentReader.java
├── markdown/MarkdownDocumentReader.java
├── pdf/PdfDocumentReader.java
├── docx/DocxDocumentReader.java
└── html/HtmlDocumentReader.java
```

### 关键接口

```java
public interface DocumentReader {
    boolean supports(String extension);
    ParsedDocument read(DocumentSource source);
}
```

### 图片处理

ImageElement 持有 `byte[]` 数据，自包含不依赖文件系统。调用者自由决定处理方式。

### 数据流

```
文件 → DocumentReaders.read(File) → ParsedDocument (List<DocumentElement>)
                                           ↓
                                 TextSplitter (消费 elements, 未来)
                                           ↓
                                 List<DocumentChunk> (存入 KnowledgeStore)
```

### 项目设计约束

- 接口定义在核心模块，实现在独立模块
- 值对象不可变 + Builder 模式
- 访问器方法无 `get` 前缀（如 `content()`、`documentId()`）
- Java 11 兼容
