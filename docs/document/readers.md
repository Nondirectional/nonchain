# 文档解析器

nonchain 提供 5 种文档解析器，支持常见文档格式的结构化解析。所有解析器实现统一的 `DocumentReader` 接口，通过 `DocumentReaders` 注册中心进行管理和分派。

## 解析器概览

| 解析器 | 支持扩展名 | 核心依赖 | 输出元素类型 |
|--------|-----------|---------|------------|
| TxtDocumentReader | txt, text | 无 | TextElement |
| MarkdownDocumentReader | md, markdown | commonmark | TextElement, HeadingElement, CodeBlockElement |
| HtmlDocumentReader | html, htm | Jsoup | TextElement, HeadingElement, TableElement, CodeBlockElement |
| DocxDocumentReader | docx | Apache POI | TextElement, HeadingElement, TableElement, ImageElement |
| PdfDocumentReader | pdf | PDFBox | TextElement, HeadingElement, ImageElement |

## TxtDocumentReader

纯文本解析器，最基础的文档读取实现。

**支持扩展名：** `txt`, `text`

**解析逻辑：** 按双换行符（`\n\n`）分割文本为段落，每个段落生成一个 `TextElement`。单换行符不会触发段落分割。

```java
import com.non.chain.document.txt.TxtDocumentReader;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.DocumentReaders;
import com.non.chain.document.ParsedDocument;
import java.io.InputStream;

// 直接使用
TxtDocumentReader reader = new TxtDocumentReader();
InputStream is = getClass().getResourceAsStream("/document/sample.txt");
ParsedDocument doc = reader.read(DocumentSource.of(is, "sample.txt"));

// 通过注册中心
DocumentReaders readers = new DocumentReaders()
        .register(new TxtDocumentReader());
ParsedDocument doc = readers.read(new File("notes.txt"));
```

## MarkdownDocumentReader

Markdown 文档解析器，基于 commonmark 库解析 AST。

**支持扩展名：** `md`, `markdown`

**解析逻辑：** 使用 commonmark 的 `Parser` 解析文档为 AST 节点，遍历节点树并转换为对应的文档元素。

**输出元素类型：**
- `Paragraph` 节点 -> `TextElement`
- `Heading` 节点 -> `HeadingElement`（保留标题级别 1-6）
- `FencedCodeBlock` 节点 -> `CodeBlockElement`（保留语言标识）

```java
import com.non.chain.document.markdown.MarkdownDocumentReader;

MarkdownDocumentReader reader = new MarkdownDocumentReader();
InputStream is = getClass().getResourceAsStream("/document/guide.md");
ParsedDocument doc = reader.read(DocumentSource.of(is, "guide.md"));

// 遍历输出
for (DocumentElement element : doc.elements()) {
    if (element instanceof HeadingElement) {
        HeadingElement h = (HeadingElement) element;
        System.out.println("H" + h.level() + ": " + h.content());
    } else if (element instanceof CodeBlockElement) {
        CodeBlockElement c = (CodeBlockElement) element;
        System.out.println("Code [" + c.language() + "]: " + c.content());
    } else if (element instanceof TextElement) {
        System.out.println("Text: " + ((TextElement) element).content());
    }
}
```

## HtmlDocumentReader

HTML 文档解析器，基于 Jsoup 解析 DOM。

**支持扩展名：** `html`, `htm`

**解析逻辑：** 使用 Jsoup 解析 HTML，遍历 `body` 下的 DOM 节点，识别标题、表格、代码块和文本段落。

**输出元素类型：**
- `<h1>` ~ `<h6>` 标签 -> `HeadingElement`（提取标题级别）
- `<table>` 标签 -> `TableElement`（第一行含 `<th>` 作为表头，其余作为数据行）
- `<pre><code>` 标签 -> `CodeBlockElement`（从 `language-*` CSS 类名提取语言标识）
- 文本节点 -> `TextElement`

```java
import com.non.chain.document.html.HtmlDocumentReader;

HtmlDocumentReader reader = new HtmlDocumentReader();
InputStream is = getClass().getResourceAsStream("/document/page.html");
ParsedDocument doc = reader.read(DocumentSource.of(is, "page.html"));
```

## DocxDocumentReader

Word 文档解析器，基于 Apache POI 的 XWPFDocument API。

**支持扩展名：** `docx`

**解析逻辑：** 遍历文档的 `IBodyElement` 列表，识别段落和表格。通过段落样式名自动检测标题级别，并提取文档中的所有嵌入图片。

**特性：**
- 标题检测：识别 `Heading1` ~ `Heading9` 样式名（含大小写变体）
- 段落提取：非标题段落作为 `TextElement` 输出
- 表格提取：第一行作为表头，其余行作为数据
- 嵌入图片提取：提取所有 `XWPFPictureData`，自动映射扩展名到 MIME 类型

```java
import com.non.chain.document.docx.DocxDocumentReader;

DocxDocumentReader reader = new DocxDocumentReader();
InputStream is = getClass().getResourceAsStream("/document/report.docx");
ParsedDocument doc = reader.read(DocumentSource.of(is, "report.docx"));

// 统计各类元素
for (DocumentElement element : doc.elements()) {
    if (element instanceof HeadingElement) {
        System.out.println("标题: " + ((HeadingElement) element).content());
    } else if (element instanceof TableElement) {
        TableElement t = (TableElement) element;
        System.out.println("表格: " + t.headers().size() + " 列");
    } else if (element instanceof ImageElement) {
        ImageElement img = (ImageElement) element;
        System.out.println("图片: " + img.fileName() + " (" + img.data().length + " bytes)");
    } else if (element instanceof TextElement) {
        System.out.println("文本: " + ((TextElement) element).content());
    }
}
```

## PdfDocumentReader

PDF 文档解析器，基于 Apache PDFBox。功能最丰富的解析器，支持文本提取、图片提取、扫描件检测和 OCR 回退。

**支持扩展名：** `pdf`

**特性：**
- **文本提取：** 使用 `PDFTextStripper` 逐页提取文本，按双换行分段
- **图片提取：** 通过 `PDFStreamEngine` 遍历页面内容流，提取 `PDImageXObject` 并转为 PNG 格式
- **扫描件检测：** 计算每页平均文本字符数，低于阈值判定为扫描件
- **OCR 回退：** 检测到扫描件时，将每页渲染为 300 DPI 图片后调用 OCR 引擎识别

### 构造函数

```java
// 无 OCR 引擎（仅提取文本和图片）
PdfDocumentReader reader = new PdfDocumentReader();

// 带默认 OCR 引擎（使用 RapidOCR，扫描件检测阈值 50）
PdfDocumentReader reader = new PdfDocumentReader(ocrEngine);

// 自定义 OCR 引擎和扫描件检测阈值
PdfDocumentReader reader = new PdfDocumentReader(ocrEngine, 100);
```

| 构造函数 | 说明 |
|---------|------|
| `PdfDocumentReader()` | 无 OCR，仅提取文本和嵌入图片 |
| `PdfDocumentReader(OcrEngine)` | 带默认扫描件阈值（50） |
| `PdfDocumentReader(OcrEngine, int)` | 自定义扫描件阈值 |

### 扫描件检测

默认扫描件检测阈值为 50 个字符/页。计算方式：

```
isScanned = (总文本字符数 / 页数) < scanThreshold
```

当检测到扫描件且提供了 OCR 引擎时，会丢弃原始文本提取结果，改用 OCR 逐页识别。

### 元数据

PDF 解析后会在 `DocumentMetadata.attributes` 中添加 `scanned` 属性，标识文档是否为扫描件。

```java
Object scanned = doc.metadata().attributes().get("scanned");
if (Boolean.TRUE.equals(scanned)) {
    System.out.println("检测到扫描件 PDF");
}
```

### 完整示例

```java
import com.non.chain.document.*;
import com.non.chain.document.pdf.PdfDocumentReader;
import java.io.InputStream;

public class PdfReaderExample {
    public static void main(String[] args) throws Exception {
        // 1. 配置 OCR 引擎（可选）
        OcrEngine ocrEngine = new RapidOCREngine();
        // OcrEngine ocrEngine = new TesseractOCREngine("/usr/share/tessdata", "chi_sim");

        // 2. 创建 PDF 解析器
        PdfDocumentReader reader = new PdfDocumentReader(ocrEngine);

        // 3. 读取 PDF
        InputStream is = getClass().getResourceAsStream("/document/report.pdf");
        DocumentSource source = DocumentSource.of(is, "report.pdf");
        ParsedDocument doc = reader.read(source);

        // 4. 查看元数据
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("页数: " + doc.metadata().pageCount());
        System.out.println("是否扫描件: " + doc.metadata().attributes().get("scanned"));

        // 5. 遍历元素
        int textCount = 0, imageCount = 0;
        for (DocumentElement element : doc.elements()) {
            if (element instanceof TextElement) {
                textCount++;
                TextElement text = (TextElement) element;
                System.out.println("[TEXT page=" + text.position().pageNumber() + "] "
                        + text.content().substring(0, Math.min(80, text.content().length())));
            } else if (element instanceof ImageElement) {
                imageCount++;
                ImageElement image = (ImageElement) element;
                System.out.println("[IMAGE page=" + image.position().pageNumber()
                        + " size=" + image.data().length + " bytes]");
            }
        }
        System.out.println("文本元素: " + textCount + ", 图片元素: " + imageCount);
    }
}
```

## 使用 DocumentReaders 注册中心

推荐通过 `DocumentReaders` 统一管理所有解析器，实现按文件扩展名自动分派。

```java
import com.non.chain.document.*;
import com.non.chain.document.txt.TxtDocumentReader;
import com.non.chain.document.markdown.MarkdownDocumentReader;
import com.non.chain.document.html.HtmlDocumentReader;
import com.non.chain.document.docx.DocxDocumentReader;
import com.non.chain.document.pdf.PdfDocumentReader;
import java.io.File;

public class ReaderRegistryExample {
    public static void main(String[] args) throws Exception {
        // 注册所有解析器
        DocumentReaders readers = new DocumentReaders()
                .register(new TxtDocumentReader())
                .register(new MarkdownDocumentReader())
                .register(new HtmlDocumentReader())
                .register(new DocxDocumentReader())
                .register(new PdfDocumentReader(new RapidOCREngine()));

        // 通过文件扩展名自动选择解析器
        ParsedDocument pdfDoc = readers.read(new File("report.pdf"));
        ParsedDocument mdDoc = readers.read(new File("guide.md"));
        ParsedDocument htmlDoc = readers.read(new File("page.html"));
        ParsedDocument docxDoc = readers.read(new File("data.docx"));
        ParsedDocument txtDoc = readers.read(new File("notes.txt"));

        System.out.println("PDF 元素数: " + pdfDoc.elements().size());
        System.out.println("Markdown 元素数: " + mdDoc.elements().size());
        System.out.println("HTML 元素数: " + htmlDoc.elements().size());
        System.out.println("DOCX 元素数: " + docxDoc.elements().size());
        System.out.println("TXT 元素数: " + txtDoc.elements().size());
    }
}
```

## 依赖要求

| 解析器 | Maven 依赖 | 版本 | 是否必须 |
|--------|-----------|------|---------|
| TxtDocumentReader | 无 | - | 无额外依赖 |
| MarkdownDocumentReader | `org.commonmark:commonmark` | 0.22.0 | 是 |
| HtmlDocumentReader | `org.jsoup:jsoup` | 1.17.2 | 是 |
| DocxDocumentReader | `org.apache.poi:poi-ooxml` | 5.2.5 | 是 |
| PdfDocumentReader | `org.apache.pdfbox:pdfbox` | 2.0.31 | 是 |
| RapidOCREngine | Python `rapidocr`（通过 `uv`） | - | OCR 时需要 |
| TesseractOCREngine | 系统 `tesseract` | - | OCR 时需要 |

```xml
<!-- chain-document 模块依赖（所有解析器的依赖均为 optional） -->
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain-document</artifactId>
    <version>0.4.0</version>
</dependency>

<!-- 使用时需根据实际使用的解析器添加对应依赖 -->
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
    <version>0.22.0</version>
</dependency>
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.17.2</version>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>2.0.31</version>
</dependency>
```
