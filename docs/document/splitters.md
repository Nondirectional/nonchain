# 文档切分策略

nonchain 提供 4 种文档切分策略，将 `ParsedDocument` 切分为适合向量检索和 LLM 处理的 `TextChunk`。不同的切分策略适用于不同的文档结构和应用场景。

## 核心接口

### DocumentSplitter 接口

```java
public interface DocumentSplitter {
    List<TextChunk> split(ParsedDocument document);
    default List<TextChunk> split(String text) {
        return split(ParsedDocument.fromText(text));
    }
}
```

| 方法 | 说明 |
|------|------|
| `split(ParsedDocument)` | 对结构化文档执行切分 |
| `split(String)` | 便捷方法，将纯文本包装为 ParsedDocument 后切分 |

### ContentMeasure 接口

内容度量接口，抽象长度计算方式。使 `chunkSize` / `chunkOverlap` 的单位可以是字符或 token。

```java
public interface ContentMeasure {
    int measure(String text);
}
```

### CharacterMeasure

字符计数度量，以 `String.length()` 为单位。

```java
ContentMeasure measure = new CharacterMeasure();
int len = measure.measure("你好世界");  // 返回 4
```

### TokenMeasure

基于 tokenizer 的 token 计数度量，依赖 jtokkit 库。

```java
// 按编码类型构造
ContentMeasure measure = new TokenMeasure(EncodingType.CL100K_BASE);

// 按模型名构造（自动选择对应编码）
ContentMeasure measure = new TokenMeasure(ModelType.GPT_4);
```

| 构造函数 | 说明 |
|---------|------|
| `TokenMeasure(EncodingType)` | 按编码类型构造（cl100k_base, o200k_base 等） |
| `TokenMeasure(ModelType)` | 按模型名构造（自动选择对应编码） |

## 切分策略详解

### RecursiveCharacterSplitter

递归字符切分器，按分隔符层级递归切分文本内容。遇到原子元素（TABLE、CODE_BLOCK、IMAGE、PAGE_BREAK）时保持完整，不参与切分。

**工作原理：**
1. 遍历文档元素，TEXT/HEADING 内容累积到文本缓冲区
2. 遇到原子元素或 PAGE_BREAK 时，先刷新文本缓冲区
3. 文本缓冲区按分隔符层级递归切分：先用第一个分隔符切，如果某段仍超长则用下一级分隔符继续切
4. 切分后的 chunk 之间添加 overlap（重叠）以保持上下文连续性

**默认分隔符层级：**

```java
List.of("\n\n", "\n", "。", "？", "！", "；", ".", "?", "!", ";", " ", "")
```

**原子元素保护：** TABLE、CODE_BLOCK、IMAGE 元素始终作为独立的 chunk 输出，不会被截断。如果原子元素内容超过 `chunkSize`，会在 metadata 中标记 `oversized: true`。

#### Builder 配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `chunkSize` | 每个 chunk 的最大度量值 | 1000 |
| `chunkOverlap` | 相邻 chunk 之间的重叠量 | 200 |
| `separators` | 分隔符层级列表（从粗到细） | `\n\n`, `\n`, `。`, `?`, `!`, `;`, `.`, ` `, `""` |
| `keepSeparator` | 是否在切分后保留分隔符 | true |
| `contentMeasure` | 内容度量方式 | CharacterMeasure |

```java
RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
        .chunkSize(1000)
        .chunkOverlap(200)
        .build();

// 按 token 切分
RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
        .chunkSize(500)
        .chunkOverlap(50)
        .contentMeasure(new TokenMeasure(EncodingType.CL100K_BASE))
        .build();

// 自定义分隔符
RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
        .chunkSize(500)
        .chunkOverlap(0)
        .separators(List.of("。", "！", "？", "\n", " "))
        .build();
```

#### 使用示例

```java
// 基础用法
RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
        .chunkSize(50)
        .chunkOverlap(10)
        .build();

String text = "非链库是一个轻量级Java LLM工具库。它提供了文档处理、向量化存储和检索等功能。";
List<TextChunk> chunks = splitter.split(text);
chunks.forEach(c -> System.out.println(c.content()));

// 包含原子元素
ParsedDocument document = ParsedDocument.builder(
        DocumentMetadata.builder().fileName("demo.md").format("md").build())
        .addElement(TextElement.builder("这是一段介绍文字。").build())
        .addElement(TableElement.builder()
                .addHeader("功能").addHeader("状态")
                .addRow(Arrays.asList("文档读取", "已完成"))
                .build())
        .addElement(CodeBlockElement.builder("System.out.println(\"Hello\");").language("java").build())
        .addElement(TextElement.builder("上面展示了表格和代码块作为原子元素不会被截断。").build())
        .build();

List<TextChunk> chunks = splitter.split(document);
for (TextChunk chunk : chunks) {
    System.out.printf("[type=%s] %s%n", chunk.elementType(), chunk.content());
}
// 输出:
// [type=TEXT] 这是一段介绍文字。
// [type=TABLE] | 功能 | 状态 |  --- | --- | 文档读取 | 已完成 |
// [type=CODE_BLOCK] System.out.println("Hello");
// [type=TEXT] 上面展示了表格和代码块作为原子元素不会被截断。
```

### HeaderDocumentSplitter

标题/结构层级切分器。基于 `HeadingElement` 按文档结构层级拆分，自动维护标题路径元数据。

**工作原理：**
1. 遍历文档元素，维护标题路径栈
2. 遇到指定级别的标题时，刷新当前章节内容，开始新章节
3. 标题路径栈按级别管理：遇到更高级别标题时弹出栈中更低级别的条目
4. 每个 chunk 的 metadata 中包含 `heading`、`headingLevel`、`headingPath`

**特点：**
- 保持文档结构完整性，适合结构化文档
- 标题路径帮助在检索时定位文档位置
- 原子元素独立输出并携带所属章节的标题信息
- PAGE_BREAK 在 HeaderSplitter 中不作为切分边界

#### 构造函数

| 参数 | 说明 |
|------|------|
| `headersToSplitOn` | 在哪些标题级别处切分 |
| `includeHeadingInContent` | 是否将标题内容包含在切分后的 chunk 中 |

```java
// 在 H1 和 H2 处切分，包含标题内容
HeaderDocumentSplitter splitter = new HeaderDocumentSplitter(List.of(1, 2), true);

// 仅在 H1 处切分，不包含标题
HeaderDocumentSplitter splitter = new HeaderDocumentSplitter(List.of(1), false);
```

#### 使用示例

```java
ParsedDocument document = ParsedDocument.builder(
        DocumentMetadata.builder().fileName("guide.md").format("md").build())
        .addElement(TextElement.builder("这是一篇介绍文档。").build())
        .addElement(HeadingElement.builder(1, "快速开始").build())
        .addElement(TextElement.builder("首先安装依赖。").build())
        .addElement(TextElement.builder("框架支持多种文档格式。").build())
        .addElement(HeadingElement.builder(2, "环境要求").build())
        .addElement(TextElement.builder("Java 11 及以上版本。").build())
        .addElement(HeadingElement.builder(1, "核心概念").build())
        .addElement(TextElement.builder("包括文档处理、向量化、检索三大模块。").build())
        .build();

HeaderDocumentSplitter splitter = new HeaderDocumentSplitter(List.of(1, 2), true);
List<TextChunk> chunks = splitter.split(document);

for (TextChunk chunk : chunks) {
    System.out.println("heading: " + chunk.metadata().get("heading"));
    System.out.println("headingPath: " + chunk.metadata().get("headingPath"));
    System.out.println("content: " + chunk.content());
    System.out.println();
}
// 输出:
// heading: null, headingPath: []
// content: 这是一篇介绍文档。
//
// heading: 快速开始, headingPath: [快速开始]
// content: 快速开始 首先安装依赖。 框架支持多种文档格式。
//
// heading: 环境要求, headingPath: [快速开始, 环境要求]
// content: 环境要求 Java 11 及以上版本。
//
// heading: 核心概念, headingPath: [核心概念]
// content: 核心概念 包括文档处理、向量化、检索三大模块。
```

### SemanticSplitter

语义切分器。基于 `EmbeddingModel` 计算相邻文本段的语义相似度，在语义断点处切分。

**工作原理：**
1. 将 TEXT/HEADING 元素按句子边界（。？！.?!\n）拆分为句子
2. 将句子按 `bufferSize` 分组，对每组计算 embedding
3. 计算相邻分组的余弦相似度
4. 当相似度低于 `breakpointThreshold` 时，在该位置设为断点
5. 按断点组装 chunks，原子元素和 PAGE_BREAK 天然作为断点
6. 如果某个 chunk 超过 `maxChunkSize`，使用 RecursiveCharacterSplitter 作为回退进行强制拆分

**特点：**
- 切分位置与内容语义相关，避免在句子中间截断
- 适合主题多变的文档
- 需要配置 EmbeddingModel

#### Builder 配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `embeddingModel` | Embedding 模型（必填） | - |
| `bufferSize` | 每组句子数（影响断点检测粒度） | 1 |
| `breakpointThreshold` | 语义断点阈值（余弦相似度低于此值则切分） | 0.5 |
| `contentMeasure` | 内容度量方式 | CharacterMeasure |
| `maxChunkSize` | 单个 chunk 最大度量值（超出则强制拆分，0 表示不限制） | 0 |

```java
SemanticSplitter splitter = SemanticSplitter.builder(embeddingModel)
        .bufferSize(1)
        .breakpointThreshold(0.5)
        .maxChunkSize(1000)
        .build();
```

#### 使用示例

```java
EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

SemanticSplitter splitter = SemanticSplitter.builder(embeddingModel)
        .bufferSize(1)
        .breakpointThreshold(0.5)
        .build();

String text = "人工智能正在改变软件开发的方式。机器学习模型可以自动完成代码审查。" +
        "足球世界杯每四年举办一次。今年的比赛将在多个城市同时进行。";

List<TextChunk> chunks = splitter.split(text);
// 期望在 AI 话题和体育话题之间切分
```

### CompositeDocumentSplitter

组合切分器，支持二级切分。先用一级切分器按结构拆分，再用二级切分器对每个 TEXT 类型的 chunk 进行细分。

**工作原理：**
1. 使用 `primary` 切分器对文档进行一级切分
2. 对一级切分结果中的 TEXT 类型 chunk，使用 `secondary` 切分器进行二级切分
3. 原子元素（TABLE、CODE_BLOCK、IMAGE）直接透传，不参与二级切分
4. metadata 合并策略：父 chunk 的 metadata 作为基础，子 chunk 的 metadata 覆盖同名键

**特点：**
- 一级切分保留文档结构（标题路径等元数据）
- 二级切分控制 chunk 粒度（适合 embedding 和 LLM 上下文窗口）
- 灵活组合不同的切分策略

#### 构造函数

| 参数 | 说明 |
|------|------|
| `primary` | 一级切分器（如 HeaderDocumentSplitter） |
| `secondary` | 二级切分器（如 RecursiveCharacterSplitter） |

```java
// 一级：按标题切分；二级：按字符数细分
HeaderDocumentSplitter primary = new HeaderDocumentSplitter(List.of(1, 2), true);
RecursiveCharacterSplitter secondary = RecursiveCharacterSplitter.builder()
        .chunkSize(200)
        .chunkOverlap(20)
        .build();

CompositeDocumentSplitter splitter = new CompositeDocumentSplitter(primary, secondary);
```

#### 使用示例

```java
HeaderDocumentSplitter primary = new HeaderDocumentSplitter(List.of(1, 2), true);

RecursiveCharacterSplitter secondary = RecursiveCharacterSplitter.builder()
        .chunkSize(30)
        .chunkOverlap(0)
        .separators(List.of("。", "，", "", ""))
        .build();

CompositeDocumentSplitter splitter = new CompositeDocumentSplitter(primary, secondary);

ParsedDocument document = ParsedDocument.builder(
        DocumentMetadata.builder().fileName("article.md").format("md").build())
        .addElement(HeadingElement.builder(1, "机器学习入门").build())
        .addElement(TextElement.builder("机器学习是人工智能的一个分支。"
                + "常见的算法包括线性回归、决策树、神经网络等。"
                + "深度学习是机器学习的子集，使用多层神经网络处理复杂模式。").build())
        .addElement(HeadingElement.builder(2, "监督学习").build())
        .addElement(TextElement.builder("监督学习使用标注数据训练模型。"
                + "分类和回归是两种主要任务。").build())
        .addElement(TableElement.builder()
                .addHeader("算法").addHeader("类型")
                .addRow(Arrays.asList("SVM", "分类"))
                .build())
        .build();

List<TextChunk> chunks = splitter.split(document);
for (TextChunk chunk : chunks) {
    System.out.printf("[type=%s] headingPath=%s%n",
            chunk.elementType(), chunk.metadata().get("headingPath"));
    System.out.println("  " + chunk.content().substring(0, Math.min(50, chunk.content().length())));
}
// 一级切分按 H1/H2 拆出 3 个章节
// 二级切分对每个章节的文本按 30 字符细分
// TABLE 原子元素直接透传，携带 headingPath 元数据
```

## 策略选择指南

| 场景 | 推荐策略 | 说明 |
|------|---------|------|
| 无结构纯文本 | RecursiveCharacterSplitter | 按字符/token 数切分，简单通用 |
| 结构化文档（有标题） | HeaderDocumentSplitter | 保持文档结构，适合技术文档 |
| 主题多变的文档 | SemanticSplitter | 按语义边界切分，避免截断句子 |
| 长篇结构化文档 | CompositeDocumentSplitter | 先按标题分章节，再按长度细分 |
| 需要精确控制 chunk 长度 | RecursiveCharacterSplitter + TokenMeasure | 按 token 数控制，适配 LLM 上下文窗口 |
| 包含大量表格/代码 | RecursiveCharacterSplitter 或 CompositeDocumentSplitter | 原子元素保护确保表格/代码不被截断 |

### 决策流程

```
文档是否有明确的标题结构？
  |
  +-- 是 --> 文档是否很长（单章节超过 LLM 上下文）？
  |            |
  |            +-- 是 --> CompositeDocumentSplitter
  |            |           (primary=Header, secondary=Recursive)
  |            |
  |            +-- 否 --> HeaderDocumentSplitter
  |
  +-- 否 --> 是否需要语义感知切分？
               |
               +-- 是 --> SemanticSplitter
               |
               +-- 否 --> RecursiveCharacterSplitter
```

### 常见配置模板

**通用配置（适合大多数场景）：**
```java
RecursiveCharacterSplitter.builder()
        .chunkSize(1000)
        .chunkOverlap(200)
        .build()
```

**按 token 控制适配 LLM：**
```java
RecursiveCharacterSplitter.builder()
        .chunkSize(500)
        .chunkOverlap(50)
        .contentMeasure(new TokenMeasure(EncodingType.CL100K_BASE))
        .build()
```

**结构化文档精细控制：**
```java
new CompositeDocumentSplitter(
    new HeaderDocumentSplitter(List.of(1, 2), true),
    RecursiveCharacterSplitter.builder()
        .chunkSize(500)
        .chunkOverlap(50)
        .contentMeasure(new TokenMeasure(EncodingType.CL100K_BASE))
        .build()
)
```

**中文文档优化：**
```java
RecursiveCharacterSplitter.builder()
        .chunkSize(500)
        .chunkOverlap(100)
        .separators(List.of("\n\n", "\n", "。", "！", "？", "；", "，", " ", ""))
        .build()
```
