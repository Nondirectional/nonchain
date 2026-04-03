# 文档清洗管道

nonchain 的文档清洗管道提供了一套可组合的清洗器体系，用于在文档解析后、切分前对文档内容进行预处理。通过 `CleanerPipeline` 将多个清洗器按顺序串联执行，每个清洗器专注于单一职责。

## 架构概览

```
ParsedDocument
     |
     v
ControlCharacterRemover  -- 移除控制字符
     |
     v
UnicodeNormalizer        -- Unicode 规范化 + 全角转半角
     |
     v
WhitespaceNormalizer     -- 空白字符合并
     |
     v
BoilerplateRemover       -- 移除样板内容
     |
     v
DuplicateRemover         -- 重复段落去重
     |
     v
ShortFragmentMerger      -- 短片段合并
     |
     v
ImageStrategyCleaner     -- 图片策略处理
     |
     v
TableSerializer          -- 表格序列化
     |
     v
Cleaned ParsedDocument
```

## DocumentCleaner 接口

所有清洗器的标准接口。

```java
public interface DocumentCleaner {
    ParsedDocument clean(ParsedDocument document);
}
```

每个清洗器接收一个 `ParsedDocument`，返回一个新的清洗后的 `ParsedDocument`。

## CleanerPipeline 管道

`CleanerPipeline` 将多个清洗器按顺序串联执行，前一个清洗器的输出作为后一个清洗器的输入。

```java
// 使用可变参数创建
CleanerPipeline pipeline = CleanerPipeline.of(
        new ControlCharacterRemover(),
        new UnicodeNormalizer(),
        new WhitespaceNormalizer()
);

// 使用列表创建
List<DocumentCleaner> cleaners = Arrays.asList(
        new ControlCharacterRemover(),
        new UnicodeNormalizer()
);
CleanerPipeline pipeline = CleanerPipeline.of(cleaners);

// 执行清洗
ParsedDocument cleaned = pipeline.clean(rawDocument);
```

| 工厂方法 | 说明 |
|---------|------|
| `CleanerPipeline.of(DocumentCleaner...)` | 通过可变参数创建管道 |
| `CleanerPipeline.of(List<DocumentCleaner>)` | 通过列表创建管道 |

## 清洗器详解

### TextContentCleaner 基类

`TextContentCleaner` 是一个抽象基类，简化了文本变换类清洗器的实现。它自动处理 `TextElement` 和 `HeadingElement` 的内容变换，其他类型元素原样传递，变换后内容为空的元素会被自动过滤。

子类只需实现 `transformText(String)` 方法：

```java
public class MyCleaner extends TextContentCleaner {
    @Override
    protected String transformText(String text) {
        return text.toUpperCase();
    }
}
```

### WhitespaceNormalizer

空白字符规范化清洗器。将多个连续空白字符（空格、制表符、换行等）合并为单个空格，并去除文本首尾的空白。

**适用场景：** 清理文档解析时产生的不规则空白。

```java
// "Hello   \n\n  World" -> "Hello World"
WhitespaceNormalizer cleaner = new WhitespaceNormalizer();
```

**实现逻辑：**

```java
protected String transformText(String text) {
    return text.trim().replaceAll("\\s+", " ");
}
```

### ControlCharacterRemover

控制字符移除清洗器。移除 Unicode 控制字符、格式字符、私用区字符和代理区字符，但保留常见的空白控制字符。

**适用场景：** 清理 PDF 提取时混入的不可见控制字符。

**保留的字符：** `\n`（换行）、`\r`（回车）、`\t`（制表符）

**移除的 Unicode 类别：**

| 类别 | 说明 |
|------|------|
| Cc (Control) | 控制字符，如 null、bell 等 |
| Cf (Format) | 格式字符，如零宽空格、零宽连接符等 |
| Co (Private Use) | 私用区字符 |
| Cs (Surrogate) | 代理区字符 |

```java
ControlCharacterRemover cleaner = new ControlCharacterRemover();
```

### UnicodeNormalizer

Unicode 规范化清洗器。执行两项操作：

1. **NFC 规范化：** 将组合字符序列转换为预组合字符（如 `e + combining accent` -> `e accent`）
2. **全角转半角：** 将全角 ASCII 字符转换为对应的半角字符

**全角转半角映射：**

| 全角 | 半角 | 全角 | 半角 |
|------|------|------|------|
| Ａ | A | ０ | 0 |
| ａ | a | （空格） | (空格) |
| ！ | ! | ｚ | z |

**适用场景：** 统一文档中的字符编码，消除全角/半角混用的问题。

```java
UnicodeNormalizer cleaner = new UnicodeNormalizer();
```

### DuplicateRemover

重复段落去重清洗器。检测并移除重复出现的 `TEXT` 和 `HEADING` 元素，保留首次出现的元素。

**适用场景：** 移除 PDF 中反复出现的页眉、页脚、水印文字。

**去重逻辑：**
- 基于标准化后的文本内容比较（去除多余空白后转小写）
- 仅作用于 `TextElement` 和 `HeadingElement`
- 首次出现保留，后续重复项移除
- 其他类型元素（TABLE、IMAGE、CODE_BLOCK）原样保留

```java
DuplicateRemover cleaner = new DuplicateRemover();
```

### ShortFragmentMerger

短文本片段合并清洗器。将长度低于阈值的 `TextElement` 合并到前一个相邻的 `TextElement` 中。

**适用场景：** 修复 PDF 提取时产生的不自然断行和碎片段落。

**合并规则：**
- 默认阈值为 50 个字符
- 短片段与前一个 `TextElement` 以空格连接合并
- 如果前一个元素不是 `TextElement`，则无法合并，保留原样
- 非 `TextElement` 类型不受影响

```java
// 默认阈值 50 字符
ShortFragmentMerger cleaner = new ShortFragmentMerger();

// 自定义阈值
ShortFragmentMerger cleaner = new ShortFragmentMerger(100);
```

| 构造函数 | 说明 |
|---------|------|
| `ShortFragmentMerger()` | 默认阈值 50 字符 |
| `ShortFragmentMerger(int threshold)` | 自定义阈值 |

### BoilerplateRemover

样板内容移除清洗器。通过正则表达式匹配并移除常见的样板文本。

**适用场景：** 清除文档中的页码、版权声明、机密标记等无意义内容。

**内置匹配模式：**

| 类别 | 匹配模式 |
|------|---------|
| 页码 | `第 N 页`, `Page N`, `N / N`, `N of N`, `- N -` |
| 版权声明 | `Copyright 2024`, `2024`, `All rights reserved` |
| 机密标记 | `Confidential`, `内部资料`, `严禁传播` |
| 其他样板 | `Draft`, `Sample` |

```java
// 使用默认模式
BoilerplateRemover cleaner = new BoilerplateRemover();

// 自定义正则模式
Pattern[] customPatterns = {
    Pattern.compile("^机密$", Pattern.CASE_INSENSITIVE),
    Pattern.compile("^v\\d+\\.\\d+$"),  // 版本号
    Pattern.compile("^第\\s*\\d+\\s*章$")  // 章节标记
};
BoilerplateRemover cleaner = new BoilerplateRemover(customPatterns);
```

| 构造函数 | 说明 |
|---------|------|
| `BoilerplateRemover()` | 使用内置默认模式 |
| `BoilerplateRemover(Pattern[])` | 使用自定义正则模式 |

### ImageStrategyCleaner

图片策略清洗器。根据配置决定如何处理文档中的 `ImageElement`。

```java
// 保留图片（默认）
ImageStrategyCleaner cleaner = new ImageStrategyCleaner();
ImageStrategyCleaner cleaner = new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.KEEP);

// 移除所有图片
ImageStrategyCleaner cleaner = new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.REMOVE);
```

| 策略 | 说明 |
|------|------|
| `ImageStrategy.KEEP` | 保留所有图片元素（默认） |
| `ImageStrategy.REMOVE` | 移除所有图片元素 |

### TableSerializer

表格序列化清洗器。将 `TableElement` 转换为 Markdown 表格语法的 `TextElement`，使表格内容可被下游 `DocumentSplitter` 处理。

**适用场景：** 需要将表格内容纳入向量检索时，在清洗阶段将表格序列化为可切分的文本。

**序列化格式：**

```markdown
| 名称 | 类型 |
| --- | --- |
| 值1 | 值2 |
| 值3 | 值4 |
```

**规则：**
- 有表头时，使用表头行生成分隔线
- 无表头时，使用第一行作为表头
- 保留原始元素的 `position` 和 `metadata`
- 非 `TABLE` 类型元素原样传递

```java
TableSerializer cleaner = new TableSerializer();

// 也可以直接调用静态方法序列化单个表格
String markdown = TableSerializer.serialize(tableElement);
```

## 完整清洗管道示例

```java
import com.non.chain.document.*;
import com.non.chain.document.cleaner.*;
import com.non.chain.document.pdf.PdfDocumentReader;
import java.io.InputStream;
import java.util.regex.Pattern;

public class CleanerPipelineExample {
    public static void main(String[] args) throws Exception {
        // 1. 提取文档
        OcrEngine ocrEngine = new RapidOCREngine();
        DocumentReaders readers = new DocumentReaders()
                .register(new PdfDocumentReader(ocrEngine));

        InputStream is = getClass().getResourceAsStream("/document/report.pdf");
        DocumentSource source = DocumentSource.of(is, "report.pdf");
        ParsedDocument rawDoc = readers.read(source);

        System.out.println("清洗前元素数: " + rawDoc.elements().size());

        // 2. 构建清洗管道
        CleanerPipeline pipeline = CleanerPipeline.of(
                // 第一步：移除控制字符（PDF 提取常产生不可见字符）
                new ControlCharacterRemover(),
                // 第二步：Unicode 规范化 + 全角转半角
                new UnicodeNormalizer(),
                // 第三步：空白字符合并
                new WhitespaceNormalizer(),
                // 第四步：移除样板内容（页码、版权等）
                new BoilerplateRemover(),
                // 第五步：重复段落去重（页眉页脚）
                new DuplicateRemover(),
                // 第六步：短片段合并（修复不自然断行）
                new ShortFragmentMerger(50),
                // 第七步：移除图片（如不需要图片检索）
                new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.REMOVE),
                // 第八步：表格序列化为 Markdown 格式
                new TableSerializer()
        );

        // 3. 执行清洗
        ParsedDocument cleanedDoc = pipeline.clean(rawDoc);

        // 4. 查看效果
        int removed = rawDoc.elements().size() - cleanedDoc.elements().size();
        System.out.println("清洗后元素数: " + cleanedDoc.elements().size());
        System.out.println("移除元素数: " + removed);

        // 5. 遍历清洗后的元素
        for (DocumentElement element : cleanedDoc.elements()) {
            if (element instanceof TextElement) {
                TextElement text = (TextElement) element;
                System.out.println("[TEXT] " + text.content());
            } else if (element instanceof HeadingElement) {
                HeadingElement heading = (HeadingElement) element;
                System.out.println("[H" + heading.level() + "] " + heading.content());
            } else if (element instanceof CodeBlockElement) {
                System.out.println("[CODE] " + ((CodeBlockElement) element).content());
            }
        }
    }
}
```

## 自定义清洗器

通过继承 `TextContentCleaner` 可以快速实现自定义的文本变换清洗器：

```java
import com.non.chain.document.cleaner.TextContentCleaner;

public class ChinesePunctuationNormalizer extends TextContentCleaner {
    @Override
    protected String transformText(String text) {
        // 统一中文标点
        return text
                .replaceAll("[,，]", "，")
                .replaceAll("[.。]", "。")
                .replaceAll("[!！]", "！")
                .replaceAll("[?？]", "？")
                .replaceAll("[;；]", "；")
                .replaceAll("[:：]", "：");
    }
}
```

通过实现 `DocumentCleaner` 接口可以实现更复杂的清洗逻辑：

```java
public class MaxLengthFilter implements DocumentCleaner {
    private final int maxLength;

    public MaxLengthFilter(int maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        List<DocumentElement> filtered = document.elements().stream()
                .filter(e -> {
                    if (e instanceof TextElement) {
                        return ((TextElement) e).content().length() <= maxLength;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        return ParsedDocument.builder(document.metadata())
                .elements(filtered)
                .build();
    }
}
```

## 推荐清洗管道

针对不同文档类型的推荐清洗器组合：

**PDF 文档：**
```java
CleanerPipeline.of(
    new ControlCharacterRemover(),
    new UnicodeNormalizer(),
    new WhitespaceNormalizer(),
    new BoilerplateRemover(),
    new DuplicateRemover(),
    new ShortFragmentMerger(50),
    new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.REMOVE),
    new TableSerializer()
);
```

**Markdown 文档：**
```java
CleanerPipeline.of(
    new UnicodeNormalizer(),
    new WhitespaceNormalizer(),
    new DuplicateRemover(),
    new TableSerializer()
);
```

**Word 文档：**
```java
CleanerPipeline.of(
    new ControlCharacterRemover(),
    new UnicodeNormalizer(),
    new WhitespaceNormalizer(),
    new DuplicateRemover(),
    new ShortFragmentMerger(30),
    new TableSerializer()
);
```
