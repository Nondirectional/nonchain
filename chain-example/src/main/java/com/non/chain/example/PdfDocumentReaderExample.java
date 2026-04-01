package com.non.chain.example;

import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentReaders;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.ImageElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;
import com.non.chain.document.pdf.PdfDocumentReader;

import java.io.File;

/**
 * 示例：使用 PdfDocumentReader 解析 PDF 文件，提取文本和嵌入图片。
 *
 * 前置条件：准备一个 PDF 文件，修改下方 filePath 路径。
 */
public class PdfDocumentReaderExample {

    public static void main(String[] args) throws Exception {
        // 1. 注册 Reader
        DocumentReaders readers = new DocumentReaders()
                .register(new PdfDocumentReader());

        // 2. 读取 PDF 文件（请替换为实际文件路径）
        String filePath = "example.pdf";
        DocumentSource source = DocumentSource.fromFile(new File(filePath));

        // 3. 解析文档
        ParsedDocument doc = readers.read(source);

        // 4. 打印结果
        System.out.println("=== PDF 文档解析结果 ===");
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("格式: " + doc.metadata().format());
        System.out.println("页数: " + doc.metadata().pageCount());
        System.out.println("元素数量: " + doc.elements().size());
        System.out.println();

        int textCount = 0;
        int imageCount = 0;

        for (DocumentElement element : doc.elements()) {
            if (element instanceof TextElement) {
                textCount++;
                TextElement text = (TextElement) element;
                System.out.println("[TEXT] page=" + text.position().pageNumber());
                System.out.println("  " + text.content().replace("\n", " "));
            } else if (element instanceof ImageElement) {
                imageCount++;
                ImageElement image = (ImageElement) element;
                System.out.println("[IMAGE] page=" + image.position().pageNumber()
                        + ", mimeType=" + image.mimeType()
                        + ", size=" + image.data().length + " bytes"
                        + (image.width() != null ? ", " + image.width() + "x" + image.height() : ""));
            }
        }

        System.out.println();
        System.out.println("统计: 文本元素=" + textCount + ", 图片元素=" + imageCount);
    }
}
