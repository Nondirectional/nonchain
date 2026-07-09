# 文档切分（Text Splitting）总体设计

## Goal

为 nonchain 框架设计并实现文档切分模块，将文档内容切分为适合向量检索和 LLM 处理的文本块（chunks）。作为知识库构建流程中 DocumentReader → Cleaner → **Splitter** → Embedding → KnowledgeStore 的关键环节。

## Requirements

### 1. 接口设计（统一接口）

删除现有未使用的 `TextSplitter` 接口，统一为单一的 `DocumentSplitter`：

```java
public interface DocumentSplitter {

    List<TextChunk> split(ParsedDocument document);

    // 纯文本便捷方法
    default List<TextChunk> split(String text) {
        return split(ParsedDocument.fromText(text));
    }
}
```

- `null` 输入：抛 `NullPointerException`（`Objects.requireNonNull`）
- 空 elements：返回空列表

### 2. 内容度量（ContentMeasure）

抽象长度计算方式，使 chunkSize / chunkOverlap 的单位可以是字符或 token：

```java
public interface ContentMeasure {
    int measure(String text);
}
```

**内置实现**：

| 实现 | 说明 | 依赖 |
|------|------|------|
| `CharacterMeasure` | `String::length`，字符计数（默认） | 无 |
| `TokenMeasure` | 基于 tokenizer 的 token 计数 | jtokkit (optional) |

- `null` 输入返回 0

**CharacterMeasure**：
```java
public class CharacterMeasure implements ContentMeasure {
    @Override
    public int measure(String text) {
        return text == null ? 0 : text.length();
    }
}
```

**TokenMeasure**：
```java
public class TokenMeasure implements ContentMeasure {
    private final Encoding encoding;

    // 按编码类型构造（cl100k_base, o200k_base 等）
    public TokenMeasure(EncodingType encodingType) { ... }

    // 按模型名构造（自动选择对应编码）
    public TokenMeasure(ModelType modelType) { ... }

    @Override
    public int measure(String text) {
        return text == null ? 0 : encoding.countTokens(text);
    }
}
```

**jtokkit 依赖**（chain-document 模块，optional）：
```xml
<dependency>
    <groupId>com.knuddels</groupId>
    <artifactId>jtokkit</artifactId>
    <version>1.1.0</version>
    <optional>true</optional>
</dependency>
```

**使用示例**：
```java
ContentMeasure measure = new TokenMeasure(EncodingType.CL100K_BASE);

DocumentSplitter splitter = RecursiveCharacterSplitter.builder()
    .chunkSize(512)          // 512 tokens
    .chunkOverlap(64)        // 64 tokens overlap
    .contentMeasure(measure)
    .build();
```

### 3. 输出模型（TextChunk）

```java
public class TextChunk {
    private final String content;               // 文本内容（IMAGE 类型可为空串）
    private final ElementType elementType;      // 元素类型：TEXT, TABLE, CODE_BLOCK, IMAGE
    private final Map<String, Object> metadata; // 元数据
    // Builder 模式，不可变
}
```

**空值策略**：
- `elementType`：必填，不允许为 null
- `content`：IMAGE 类型允许为空串 `""`，其他类型不允许为空/空串
- `metadata`：可为空 Map，不允许为 null

**便捷工厂方法**：
```java
// 快捷构造 TEXT 类型 chunk
TextChunk chunk = TextChunk.text("这是一段文本");
```

**metadata 约定**：

| key | 类型 | 说明 | 来源 |
|-----|------|------|------|
| `heading` | String | 所属标题文本 | HeaderSplitter |
| `headingLevel` | Integer | 所属标题层级 (1-6) | HeaderSplitter |
| `headingPath` | List\<String\> | 标题路径（如 ["第一章", "1.1 概述"]） | HeaderSplitter |
| `page` | Integer | 来源页码 | 从 DocumentPosition 继承 |
| `language` | String | 代码语言 | CodeBlockElement |
| `imageRef` | String | 图片引用/路径 | ImageElement |
| `chunkIndex` | Integer | 切分序号 | 所有 Splitter |
| `oversized` | Boolean | 原子元素超出 chunkSize 标记 | 所有 Splitter |

### 4. 原子元素保护

以下元素类型在切分时**保持原子性，不可截断**：

| 元素类型 | 切分行为 | TextChunk 处理 |
|----------|----------|----------------|
| `TABLE` | 整表作为单个 chunk | content = Markdown 表格格式文本，elementType = TABLE |
| `CODE_BLOCK` | 整个代码块作为单个 chunk | content = 代码文本，elementType = CODE_BLOCK，metadata 含 language |
| `IMAGE` | 图片引用作为单个 chunk | content = ""，elementType = IMAGE，metadata 含 imageRef |
| `TEXT` | 可切分 | 正常切分逻辑 |
| `HEADING` | 作为切分边界或合并到内容 | 取决于切分策略 |
| `PAGE_BREAK` | 作为切分边界 | 不产生 chunk |

**原子元素超出 chunkSize**：不拆分，保持完整，metadata 标记 `oversized: true`。

**TableElement 序列化格式**（Markdown 表格）：
```
| 姓名 | 年龄 | 城市 |
| --- | --- | --- |
| 张三 | 28 | 北京 |
| 李四 | 35 | 上海 |
```

### 5. 图片元素处理（预留 Vision LLM 扩展）

图片元素在切分阶段以"占位 chunk"形式保留：
- `elementType = IMAGE`
- `content = ""`（暂无文本内容）
- `metadata` 中携带 `imageRef`（图片来源路径/引用）
- 入库后可在闲时由 Vision LLM 读取图片、生成文本描述，回填 content 和 embedding

下游 KnowledgeStore 在处理时：
- 遇到 IMAGE 类型 chunk：跳过 embedding，仅存储元数据
- 后续 Vision LLM 处理流程可按 `elementType = IMAGE` 查询待处理 chunk

### 6. 切分策略

#### 6.1 递归字符切分（RecursiveCharacterSplitter）

按分隔符层级递归切分 TEXT 元素内容，遇到原子元素保持完整。

**整体处理流程**：
```
输入: ParsedDocument (elements 列表)
  │
  ├─ 遍历 elements
  │   ├─ TABLE / CODE_BLOCK / IMAGE →
  │   │   ① 先 flush 已积累的文本缓冲区（递归切分）
  │   │   ② 将原子元素作为独立 TextChunk 输出
  │   │
  │   ├─ TEXT / HEADING → 追加到文本缓冲区
  │   │
  │   └─ PAGE_BREAK → flush 文本缓冲区（作为天然切分边界）
  │
  └─ 遍历结束 → flush 剩余文本缓冲区
```

**递归切分算法**（对文本缓冲区内容）：
```
recursiveSplit(text, separators, depth=0):
  if measure(text) <= chunkSize:
    return [text]

  separator = separators[depth]
  segments = text.split(separator)

  chunks = []
  currentChunk = ""

  for segment in segments:
    candidate = currentChunk + separator + segment
    if measure(candidate) <= chunkSize:
      currentChunk = candidate
    else:
      if currentChunk not empty:
        chunks.add(currentChunk)
      if measure(segment) > chunkSize and depth+1 < separators.length:
        // 当前段仍然太大，用下一级分隔符继续递归
        chunks.addAll(recursiveSplit(segment, separators, depth+1))
      else:
        currentChunk = segment

  if currentChunk not empty:
    chunks.add(currentChunk)

  // 应用 overlap：后一个 chunk 开头包含前一个 chunk 末尾的 chunkOverlap 长度内容
  return applyOverlap(chunks)
```

**配置参数**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| chunkSize | int | 1000 | 目标块大小（单位取决于 contentMeasure） |
| chunkOverlap | int | 200 | 块间重叠量（单位同上） |
| separators | List\<String\> | 见下 | 分隔符层级 |
| keepSeparator | boolean | true | 是否保留分隔符 |
| contentMeasure | ContentMeasure | CharacterMeasure | 内容度量方式（字符 or token） |

**默认分隔符（中文优先）**：
```java
List.of("\n\n", "\n", "。", "？", "！", "；", ".", "?", "!", ";", " ", "")
```

#### 6.2 标题/结构层级切分（HeaderDocumentSplitter）

基于 `HeadingElement` 按文档结构层级拆分。

**处理流程**：
```
输入: ParsedDocument, headersToSplitOn = [1, 2, 3]

维护状态:
  - currentSection: 当前 section 的元素列表
  - headingStack: 标题路径栈 (e.g. ["第一章", "1.1 概述"])

遍历 elements:
  ├─ HeadingElement(level) 且 level in headersToSplitOn →
  │   ① flush currentSection → 生成 TextChunk(s)
  │   ② 更新 headingStack（弹出 >= 当前 level 的条目，压入新标题）
  │   ③ 如果 includeHeadingInContent，将 heading 加入新 section
  │
  ├─ HeadingElement(level) 且 level NOT in headersToSplitOn →
  │   归入 currentSection（作为内容的一部分）
  │
  ├─ TABLE / CODE_BLOCK / IMAGE / TEXT →
  │   归入 currentSection
  │
  └─ PAGE_BREAK → 归入 currentSection（不作为切分边界）

遍历结束 → flush 最后一个 section
```

**Section flush 逻辑**（原子元素独立拆出）：
```
flush(currentSection, headingStack):
  chunks = []
  textBuffer = ""

  for element in currentSection:
    if element is TEXT or HEADING:
      textBuffer += element.content + "\n"

    if element is TABLE / CODE_BLOCK / IMAGE:
      ① 若 textBuffer 非空 → 输出 TextChunk(content=textBuffer, type=TEXT, metadata={headingPath...})
      ② 清空 textBuffer
      ③ 输出原子 TextChunk(content=序列化内容, type=TABLE/CODE_BLOCK/IMAGE, metadata={headingPath...})

  若 textBuffer 非空 → 输出最后一个 TextChunk
  return chunks
```

所有输出 chunk（包括原子元素）均携带当前 section 的 `heading` / `headingLevel` / `headingPath` metadata。

**配置参数**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| headersToSplitOn | List\<Integer\> | `[1, 2, 3]` | 按哪些标题层级切分 |
| includeHeadingInContent | boolean | true | chunk 内容是否包含标题文本 |

**特殊情况**：
- 文档开头无标题的内容 → 归入一个没有标题的 section（headingPath 为空列表）

#### 6.3 语义切分（SemanticSplitter）

基于 `EmbeddingModel` 计算相邻文本段的语义相似度，在语义断点处切分。

**处理流程**：
```
第一步：提取文本段 + 保留原子元素
  遍历 elements:
    ├─ TEXT / HEADING → 按句子边界拆分，得到句子列表
    ├─ TABLE / CODE_BLOCK / IMAGE → 标记为原子元素，记录位置
    └─ PAGE_BREAK → 作为天然断点

第二步：计算语义相似度
  对相邻句子组（bufferSize 个句子一组）调用 embeddingModel.embedAll()
  计算每对相邻组的余弦相似度

第三步：确定断点
  相似度 < breakpointThreshold 的位置 → 切分断点

第四步：组装 chunks
  按断点将句子合并为 TextChunk
  原子元素在原位置插入为独立 chunk
```

**句子拆分**：复用与 RecursiveCharacterSplitter 相同的中文/英文句子边界检测（`。？！.?!\n`）。

**配置参数**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| embeddingModel | EmbeddingModel | (必填) | 用于计算语义相似度 |
| bufferSize | int | 1 | 相邻句子分组大小 |
| breakpointThreshold | double | 0.5 | 语义断点阈值（相似度低于此值则切分） |
| contentMeasure | ContentMeasure | CharacterMeasure | 用于检查 chunk 是否超出上限 |
| maxChunkSize | int | 0 (不限) | 语义 chunk 最大尺寸，超出时内部强制拆分 |

#### 6.4 组合切分（CompositeDocumentSplitter）

支持二级切分：先按结构拆分，再对每个 section 细分。

```java
public class CompositeDocumentSplitter implements DocumentSplitter {
    private final DocumentSplitter primary;   // e.g. HeaderDocumentSplitter
    private final DocumentSplitter secondary; // e.g. RecursiveCharacterSplitter
}
```

**处理流程**：
```
1. primary.split(doc) → 得到一级 chunks
2. 遍历一级 chunks:
   ├─ 非 TEXT 类型（原子元素）→ 直接透传（metadata 已含章节信息）
   └─ TEXT 类型 →
       ① 包装为 ParsedDocument（ParsedDocument.fromText(chunk.content())）
       ② secondary.split(subDoc) → 得到子 chunks
       ③ 合并 metadata：父 chunk metadata 为基础，子 chunk metadata 覆盖合并
3. 返回所有 chunks
```

**metadata 合并策略**：
- 父 chunk（primary）的 metadata 作为基础
- 子 chunk（secondary）的 metadata 覆盖合并
- 冲突时子 chunk 优先（更具体的信息覆盖更粗粒度的）

**典型使用场景**：
```java
DocumentSplitter splitter = new CompositeDocumentSplitter(
    new HeaderDocumentSplitter(List.of(1, 2)),           // 先按 H1/H2 拆章节
    RecursiveCharacterSplitter.builder()
        .chunkSize(512)
        .contentMeasure(new TokenMeasure(EncodingType.CL100K_BASE))
        .build()                                          // 再按 512 token 细分
);
```

### 7. 中文支持

- 默认分隔符包含中文标点：`。？！；`
- 句子边界检测支持中文句末标点
- CharacterMeasure 下中文字符算 1；TokenMeasure 下按实际 token 数计（中文通常 1-2 token/字）
- 用户可根据目标 LLM 选择合适的 ContentMeasure

### 8. 边界处理

| 场景 | 行为 |
|------|------|
| 空文档 / 无 elements | 返回空列表 |
| 文本短于 chunkSize | 返回单个 chunk |
| 原子元素超出 chunkSize | 保持完整，metadata 标记 `oversized: true` |
| chunkOverlap >= chunkSize | 构造时抛出 IllegalArgumentException |
| 连续多个原子元素 | 各自独立成 chunk |

### 9. ParsedDocument 改动

新增静态工厂方法：
```java
public static ParsedDocument fromText(String text) {
    DocumentMetadata metadata = DocumentMetadata.builder()
        .fileName("text")
        .format("txt")
        .build();
    return ParsedDocument.builder(metadata)
        .addElement(TextElement.builder(text).build())
        .build();
}
```

- `null` 输入抛 NullPointerException
- 空串抛 IllegalArgumentException（与 TextElement 现有校验一致）

### 10. 删除旧接口

删除 `chain/src/main/java/com/non/chain/knowledge/TextSplitter.java`（未使用）。

## 类图概览

```
                        ┌───────────────────┐
                        │  DocumentSplitter │ (interface)
                        │                   │
                        │  split(ParsedDoc) │
                        │  split(String)    │ default
                        │  → List<TextChunk>│
                        └─────────┬─────────┘
                                  │
            ┌─────────────────────┼─────────────────────┐
            │                     │                     │
  ┌─────────┴──────────┐ ┌───────┴────────┐ ┌──────────┴─────────┐
  │ RecursiveCharacter  │ │ Header         │ │ Semantic           │
  │ Splitter            │ │ Document       │ │ Splitter           │
  │                     │ │ Splitter       │ │                    │
  │ - chunkSize         │ │ - headersTo    │ │ - embeddingModel   │
  │ - chunkOverlap      │ │   SplitOn      │ │ - bufferSize       │
  │ - separators        │ │ - includeHead  │ │ - breakpointThresh │
  │ - contentMeasure    │ └────────────────┘ │ - contentMeasure   │
  └─────────────────────┘                    └────────────────────┘

  ┌─────────────────────────┐
  │ CompositeDocumentSplitter│
  │                          │
  │ - primary: DocSplitter   │ ──▶ 一级切分（结构）
  │ - secondary: DocSplitter │ ──▶ 二级切分（TEXT 细分，原子透传）
  └──────────────────────────┘

  ┌──────────────────┐        ┌──────────────────┐
  │ ContentMeasure   │        │  TextChunk       │
  │ (interface)      │        │                  │
  │ measure(String)  │        │ - content        │
  └────────┬─────────┘        │ - elementType    │
           │                  │ - metadata       │
     ┌─────┴──────┐          │                  │
     │            │          │ text(String) [静态]│
 Character    Token          └──────────────────┘
 Measure      Measure
              (jtokkit)
```

## 包结构

```
com.non.chain.knowledge/           (chain 模块 - 接口 & 模型)
├── DocumentSplitter.java          (新增，替代 TextSplitter)
├── TextChunk.java                 (新增)
├── ContentMeasure.java            (新增)
├── DocumentChunk.java             (已有)
└── KnowledgeStore.java            (已有)

com.non.chain.document/            (chain 模块 - 文档模型)
├── ParsedDocument.java            (已有，新增 fromText 工厂方法)
├── DocumentElement.java           (已有)
├── ElementType.java               (已有)
└── ...

com.non.chain.document.splitter/   (chain-document 模块 - 实现)
├── CharacterMeasure.java
├── TokenMeasure.java              (依赖 jtokkit，optional)
├── RecursiveCharacterSplitter.java
├── HeaderDocumentSplitter.java
├── SemanticSplitter.java
└── CompositeDocumentSplitter.java
```

**需删除**：
- `chain/src/main/java/com/non/chain/knowledge/TextSplitter.java`

## Acceptance Criteria

- [ ] 删除旧 `TextSplitter` 接口
- [ ] `DocumentSplitter` 接口：`split(ParsedDocument)` + `default split(String)`
- [ ] `TextChunk`：content + elementType + metadata，Builder 模式，不可变，`TextChunk.text()` 工厂方法
- [ ] `ContentMeasure` 接口 + `CharacterMeasure` 实现
- [ ] `TokenMeasure` 实现（基于 jtokkit，optional 依赖），支持 EncodingType 和 ModelType 构造
- [ ] `ParsedDocument.fromText(String)` 便捷工厂方法
- [ ] `RecursiveCharacterSplitter`：文本缓冲区 + flush 机制，原子元素独立输出
- [ ] `RecursiveCharacterSplitter`：递归切分算法 + overlap 处理
- [ ] `RecursiveCharacterSplitter`：支持 chunkSize / chunkOverlap / separators / contentMeasure
- [ ] `RecursiveCharacterSplitter`：支持按 token 数切分（配合 TokenMeasure）
- [ ] `HeaderDocumentSplitter`：按标题层级切分，headingStack 维护标题路径
- [ ] `HeaderDocumentSplitter`：section flush 时原子元素独立拆出，均携带 headingPath metadata
- [ ] `SemanticSplitter`：基于 EmbeddingModel 自适应切分，原子元素保持完整
- [ ] `CompositeDocumentSplitter`：原子元素直接透传，TEXT 二次切分，metadata 合并
- [ ] `TableElement` 序列化为 Markdown 表格格式
- [ ] IMAGE 元素以占位 chunk 形式保留，携带 imageRef metadata
- [ ] 空文档返回空列表，短文本返回单个 chunk
- [ ] 原子元素超出 chunkSize 时保持完整，标记 oversized
- [ ] chunkOverlap >= chunkSize 时抛出 IllegalArgumentException
- [ ] 单元测试覆盖各策略的正常和边界情况

## Definition of Done

- Tests added/updated (unit/integration where appropriate)
- Lint / typecheck / CI green
- Docs/notes updated if behavior changes

## Out of Scope

- 代码语言切分（CodeLanguageSplitter）— 未来扩展
- Vision LLM 图片内容提取 — 切分阶段仅保留引用，提取在入库后闲时进行
- 自动选择最佳切分策略
- 切分质量评估指标

## Decision (ADR-lite)

**Context**: 需要为 nonchain 的知识库流水线补齐文档切分环节。原 TextSplitter 接口未使用，可以从头设计。文档模型中表格、代码块、图片需要特殊处理。

**Decision**:
1. 删除旧 TextSplitter，统一为 `DocumentSplitter` 单接口，基于 `ParsedDocument` 输入，含 `default split(String)` 便捷方法
2. 返回 `List<TextChunk>`（content + elementType + metadata），提供 `TextChunk.text()` 快捷工厂
3. 引入 `ContentMeasure` 抽象，内置 `CharacterMeasure` 和 `TokenMeasure`（jtokkit），chunkSize/chunkOverlap 单位由 measure 决定
4. TABLE / CODE_BLOCK / IMAGE 作为原子元素不可截断；TABLE 序列化为 Markdown 表格格式
5. IMAGE 以占位 chunk 保留，预留 Vision LLM 后处理空间
6. 所有 Splitter 中原子元素均独立拆出为 chunk，携带所属上下文 metadata（如 headingPath）
7. CompositeDocumentSplitter 对原子元素直接透传，仅对 TEXT chunk 二次切分
8. 优先实现递归字符、标题层级、语义三种策略 + 组合切分
9. 中文优先：默认分隔符包含中文标点

**Consequences**:
- 统一接口降低认知成本，所有策略自然获得原子元素保护
- 纯文本场景通过 `default split(String)` 零摩擦使用
- 图片占位 chunk 需要下游（KnowledgeStore / embedding 流程）特殊处理
- 语义切分依赖 EmbeddingModel，使用时需要网络调用

## Technical Notes

### 关键文件路径
- 待删除：`chain/src/main/java/com/non/chain/knowledge/TextSplitter.java`
- 文档模型：`chain/src/main/java/com/non/chain/document/ParsedDocument.java`
- 元素类型：`chain/src/main/java/com/non/chain/document/ElementType.java`
- 输出模型：`chain/src/main/java/com/non/chain/knowledge/DocumentChunk.java`
- 现有清洗器模式参考：`chain-document/src/main/java/com/non/chain/document/cleaner/`

### 架构约束
- Java 11+，Maven 构建
- 遵循现有模式：Builder、不可变对象、策略模式
- 接口 + TextChunk + ContentMeasure 放 `chain` 模块，实现放 `chain-document` 模块
- 所有集合使用 `Collections.unmodifiable*` 包装

### 参考来源
- [LangChain Text Splitters](https://docs.langchain.com/oss/python/integrations/splitters)
- [Spring AI ETL Pipeline](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)
- [LlamaIndex Node Parser Modules](https://developers.llamaindex.ai/python/framework/module_guides/loading/node_parsers/modules/)
- [JTokkit - Java Tokenizer for OpenAI Models](https://github.com/knuddelsgmbh/jtokkit)
