# 文档模型

nonchain 的文档模型提供了一套完整的文档抽象体系，涵盖文档读取、结构化表示和元数据管理。所有文档解析器都基于统一的模型接口，输出标准化的 `ParsedDocument`，下游的清洗管道和切分策略均作用于该模型。

## 整体架构

```
DocumentSource  -->  DocumentReader  -->  ParsedDocument
                                          |
                                     DocumentMetadata
                                     List<DocumentElement>
                                          |
                        +-----------------+------------------+
                        |        |        |         |        |
                     TextElement  HeadingElement  ...  ImageElement
```

## 核心接口与类

### DocumentReader 接口

文档解析器标准接口，所有格式的解析器都需实现此接口。

```java
public interface DocumentReader {
    boolean supports(String extension);
    ParsedDocument read(DocumentSource source) throws IOException;
}
```

| 方法 | 说明 |
|------|------|
| `supports(String extension)` | 判断是否支持指定文件扩展名 |
| `read(DocumentSource source)` | 读取文档源并返回结构化的 ParsedDocument |

### DocumentReaders 注册中心

`DocumentReaders` 是一个解析器注册中心，通过扩展名自动分派到对应的 `DocumentReader`。

```java
// 创建注册中心并注册解析器
DocumentReaders readers = new DocumentReaders()
        .register(new TxtDocumentReader())
        .register(new MarkdownDocumentReader())
        .register(new HtmlDocumentReader())
        .register(new DocxDocumentReader())
        .register(new PdfDocumentReader());

// 通过 DocumentSource 读取
ParsedDocument doc = readers.read(source);

// 通过 File 直接读取
ParsedDocument doc = readers.read(new File("example.pdf"));
```

| 方法 | 说明 |
|------|------|
| `register(DocumentReader reader)` | 注册解析器，支持链式调用 |
| `read(DocumentSource source)` | 根据扩展名自动分派到对应解析器 |
| `read(File file)` | 便捷方法，自动创建 DocumentSource |

### DocumentSource 文档源

`DocumentSource` 封装文档的输入流、文件名、内容类型和自定义元数据。

#### 静态工厂方法

```java
// 从 InputStream 和文件名创建
DocumentSource source = DocumentSource.of(inputStream, "report.pdf");

// 从 InputStream 创建（无文件名）
DocumentSource source = DocumentSource.of(inputStream);

// 从字节数组和文件名创建
DocumentSource source = DocumentSource.of(bytes, "data.docx");

// 从 File 对象创建
DocumentSource source = DocumentSource.fromFile(new File("example.md"));
```

#### Builder 模式

```java
DocumentSource source = DocumentSource.builder(inputStream)
        .fileName("report.pdf")
        .contentType("application/pdf")
        .putMetadata("author", "non")
        .putMetadata("department", "engineering")
        .build();
```

| 属性 | 说明 |
|------|------|
| `inputStream` | 输入流（必填） |
| `fileName` | 文件名，用于推断扩展名 |
| `contentType` | MIME 类型 |
| `metadata` | 自定义元数据键值对 |

`extension()` 方法会从 `fileName` 中提取文件扩展名（小写），用于解析器分派。

### ParsedDocument 解析后的文档

解析器的统一输出类型，包含元数据和元素列表。

```java
// 使用 Builder 构建
ParsedDocument doc = ParsedDocument.builder(metadata)
        .addElement(textElement)
        .addElement(headingElement)
        .addElement(tableElement)
        .build();

// 从纯文本快速创建
ParsedDocument doc = ParsedDocument.fromText("这是一段纯文本内容");
```

| 属性 | 说明 |
|------|------|
| `metadata()` | 文档元数据 |
| `elements()` | 不可变的文档元素列表 |

### DocumentMetadata 文档元数据

```java
DocumentMetadata metadata = DocumentMetadata.builder()
        .fileName("report.pdf")
        .format("pdf")
        .pageCount(42)
        .putAttribute("scanned", false)
        .putAttribute("author", "non")
        .build();
```

| 属性 | 说明 |
|------|------|
| `fileName` | 文件名 |
| `format` | 文件格式 |
| `pageCount` | 页数（可选） |
| `attributes` | 自定义属性键值对 |

## 文档元素体系

### ElementType 枚举

```java
public enum ElementType {
    TEXT,        // 普通文本
    HEADING,     // 标题
    IMAGE,       // 图片
    TABLE,       // 表格
    CODE_BLOCK,  // 代码块
    PAGE_BREAK   // 分页符
}
```

### DocumentElement 基类

所有文档元素的抽象基类，提供公共属性。

| 属性 | 说明 |
|------|------|
| `elementType()` | 元素类型 |
| `position()` | 文档位置信息（可为 null） |
| `metadata()` | 元素级元数据 |

### DocumentPosition 文档位置

记录元素在文档中的位置信息。

```java
DocumentPosition pos = DocumentPosition.builder()
        .pageNumber(3)
        .lineNumber(15)
        .charOffset(120)
        .build();
```

| 属性 | 说明 |
|------|------|
| `pageNumber` | 页码（从 1 开始） |
| `lineNumber` | 行号（从 1 开始） |
| `charOffset` | 字符偏移量（从 0 开始） |

### TextElement 文本元素

```java
TextElement text = TextElement.builder("这是一段普通文本内容。")
        .position(DocumentPosition.builder().pageNumber(1).lineNumber(1).build())
        .putMetadata("bold", true)
        .build();
```

| 属性 | 说明 |
|------|------|
| `content` | 文本内容（不可为空或空白） |

### HeadingElement 标题元素

```java
HeadingElement heading = HeadingElement.builder(2, "安装指南")
        .position(DocumentPosition.builder().pageNumber(1).lineNumber(5).build())
        .build();
```

| 属性 | 说明 |
|------|------|
| `level` | 标题级别（1-6） |
| `content` | 标题内容 |

### ImageElement 图片元素

```java
ImageElement image = ImageElement.builder(imageBytes, "image/png")
        .fileName("figure-1.png")
        .width(800)
        .height(600)
        .position(DocumentPosition.builder().pageNumber(2).build())
        .build();
```

| 属性 | 说明 |
|------|------|
| `data` | 图片字节数组（不可为空） |
| `mimeType` | MIME 类型（不可为空） |
| `fileName` | 图片文件名（可选） |
| `width` | 宽度（可选） |
| `height` | 高度（可选） |

### TableElement 表格元素

```java
TableElement table = TableElement.builder()
        .addHeader("名称")
        .addHeader("类型")
        .addRow(Arrays.asList("RecursiveCharacterSplitter", "递归字符切分"))
        .addRow(Arrays.asList("HeaderDocumentSplitter", "标题层级切分"))
        .position(DocumentPosition.builder().pageNumber(3).lineNumber(10).build())
        .build();
```

| 属性 | 说明 |
|------|------|
| `headers` | 表头列表 |
| `rows` | 数据行列表（每行为 `List<String>`） |

### CodeBlockElement 代码块元素

```java
CodeBlockElement code = CodeBlockElement.builder("public static void main(String[] args) {\n    System.out.println(\"Hello\");\n}")
        .language("java")
        .position(DocumentPosition.builder().pageNumber(4).lineNumber(1).build())
        .build();
```

| 属性 | 说明 |
|------|------|
| `language` | 编程语言标识（可选） |
| `content` | 代码内容 |

## OCR 引擎

### OcrEngine 接口

```java
public interface OcrEngine {
    String recognize(BufferedImage image);
}
```

将图片识别为文本。主要用于 PDF 扫描件的文字提取。

### RapidOCREngine

基于 Python RapidOCR 的实现，通过内联 Python 脚本调用（使用 `uv run python`）。需通过 `uv add rapidocr` 安装依赖。

```java
OcrEngine ocr = new RapidOCREngine()
        .setTextScore(0.5);  // 文本置信度阈值
```

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `setTextScore(double)` | 设置文本置信度阈值 | 0.5 |

### TesseractOCREngine

基于 Tesseract CLI 的实现，通过 `ProcessBuilder` 调用本地 `tesseract` 命令。需安装 Tesseract 并确保在系统 PATH 中。

```java
OcrEngine ocr = new TesseractOCREngine();
OcrEngine ocr = new TesseractOCREngine("/usr/share/tessdata", "chi_sim");

// 链式配置
OcrEngine ocr = new TesseractOCREngine()
        .setDataPath("/usr/share/tessdata")
        .setLanguage("chi_sim")
        .setPageSegMode(6);
```

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `setDataPath(String)` | tessdata 数据目录 | null |
| `setLanguage(String)` | OCR 语言 | chi_sim |
| `setPageSegMode(int)` | 页面分割模式（PSM） | -1（自动检测） |

## 完整使用示例

```java
import com.non.chain.document.*;
import com.non.chain.document.pdf.PdfDocumentReader;

public class DocumentModelExample {
    public static void main(String[] args) throws Exception {
        // 1. 创建解析器注册中心
        DocumentReaders readers = new DocumentReaders()
                .register(new TxtDocumentReader())
                .register(new MarkdownDocumentReader())
                .register(new HtmlDocumentReader())
                .register(new DocxDocumentReader())
                .register(new PdfDocumentReader(new RapidOCREngine()));

        // 2. 从文件读取文档
        ParsedDocument doc = readers.read(new File("example.pdf"));

        // 3. 查看文档元数据
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("格式: " + doc.metadata().format());
        System.out.println("页数: " + doc.metadata().pageCount());

        // 4. 遍历文档元素
        for (DocumentElement element : doc.elements()) {
            switch (element.elementType()) {
                case TEXT:
                    TextElement text = (TextElement) element;
                    System.out.println("[文本] " + text.content());
                    break;
                case HEADING:
                    HeadingElement heading = (HeadingElement) element;
                    System.out.println("[标题 H" + heading.level() + "] " + heading.content());
                    break;
                case IMAGE:
                    ImageElement image = (ImageElement) element;
                    System.out.println("[图片] " + image.fileName()
                            + " (" + image.data().length + " bytes)");
                    break;
                case TABLE:
                    TableElement table = (TableElement) element;
                    System.out.println("[表格] " + table.headers().size() + " 列, "
                            + table.rows().size() + " 行");
                    break;
                case CODE_BLOCK:
                    CodeBlockElement code = (CodeBlockElement) element;
                    System.out.println("[代码块/" + code.language() + "] "
                            + code.content().substring(0, 50) + "...");
                    break;
                default:
                    break;
            }
        }
    }
}
```
