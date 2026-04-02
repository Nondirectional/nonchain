package com.non.chain.example;

import com.non.chain.document.*;
import com.non.chain.document.cleaner.*;
import com.non.chain.document.pdf.PdfDocumentReader;

import java.io.InputStream;

/**
 * 示例：文档清洗管道。
 * <p>
 * 使用 PdfDocumentReader 提取文档后，通过 CleanerPipeline 串联多个清洗器：
 * <ol>
 *   <li>ControlCharacterRemover - 移除控制字符</li>
 *   <li>UnicodeNormalizer - Unicode 规范化</li>
 *   <li>WhitespaceNormalizer - 空白字符合并</li>
 *   <li>BoilerplateRemover - 移除样板内容（页码、版权声明等）</li>
 *   <li>DuplicateRemover - 重复段落去重</li>
 *   <li>ShortFragmentMerger - 短片段合并</li>
 *   <li>ImageStrategyCleaner - 图片策略处理</li>
 * </ol>
 */
public class DocumentCleanerExample {

    public static void main(String[] args) throws Exception {
        // 1. 提取文档
        OcrEngine ocrEngine = new RapidOCREngine();
        DocumentReaders readers = new DocumentReaders()
                .register(new PdfDocumentReader(ocrEngine));

        InputStream is = DocumentCleanerExample.class.getResourceAsStream("/document/scanned.pdf");
        DocumentSource source = DocumentSource.of(is, "sample.pdf");
        ParsedDocument rawDoc = readers.read(source);

        System.out.println("=== 清洗前 ===");
        printStats(rawDoc);

        // 2. 构建清洗管道
        CleanerPipeline pipeline = CleanerPipeline.of(
                new ControlCharacterRemover(),
                new UnicodeNormalizer(),
                new WhitespaceNormalizer(),
                new BoilerplateRemover(),
                new DuplicateRemover(),
                new ShortFragmentMerger(),
                new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.REMOVE)
        );

        // 3. 执行清洗
        ParsedDocument cleanedDoc = pipeline.clean(rawDoc);

        System.out.println();
        System.out.println("=== 清洗后 ===");
        printStats(cleanedDoc);

        // 4. 显示差异
        System.out.println();
        System.out.println("=== 清洗效果 ===");
        int removed = rawDoc.elements().size() - cleanedDoc.elements().size();
        System.out.println("移除元素: " + removed + " 个");
        System.out.println("保留元素: " + cleanedDoc.elements().size() + " 个");

        // 5. 打印清洗后的前 5 个文本元素
        System.out.println();
        System.out.println("=== 前 5 个文本元素 ===");
        int count = 0;
        for (DocumentElement element : cleanedDoc.elements()) {
            if (element instanceof TextElement) {
                TextElement text = (TextElement) element;
                System.out.println("[TEXT] page=" + text.position().pageNumber());
                System.out.println("  " + text.content().substring(0, Math.min(120, text.content().length())) + (text.content().length() > 120 ? "..." : ""));
                if (++count >= 5) break;
            }
        }
    }

    private static void printStats(ParsedDocument doc) {
        int textCount = 0, imageCount = 0, otherCount = 0;
        for (DocumentElement element : doc.elements()) {
            if (element instanceof TextElement) textCount++;
            else if (element instanceof ImageElement) imageCount++;
            else otherCount++;
        }
        System.out.println("总元素: " + doc.elements().size() + " (文本=" + textCount + ", 图片=" + imageCount + ", 其他=" + otherCount + ")");
    }
}
