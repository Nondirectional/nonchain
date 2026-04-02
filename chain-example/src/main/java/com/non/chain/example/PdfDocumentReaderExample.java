package com.non.chain.example;

import com.non.chain.document.*;
import com.non.chain.document.pdf.PdfDocumentReader;

import java.io.File;
import java.io.InputStream;

/**
 * 示例：使用 PdfDocumentReader 解析 PDF 文件，提取文本和嵌入图片。
 *
 * 前置条件：准备一个 PDF 文件，修改下方 filePath 路径。
 */
public class PdfDocumentReaderExample {

    public static void main(String[] args) throws Exception {
        OcrEngine ocrEngine = new RapidOCREngine();
        // 1. 注册 Reader
        DocumentReaders readers = new DocumentReaders()
                .register(new PdfDocumentReader(ocrEngine));

        // 2. 读取 PDF 文件

        // 扫描件
        InputStream is = PdfDocumentReaderExample.class.getResourceAsStream("/document/scanned.pdf");
        DocumentSource source = DocumentSource.of(is, "scanned.pdf");

        // 正常PDF
//        InputStream is = PdfDocumentReaderExample.class.getResourceAsStream("/document/sample.pdf");
//        DocumentSource source = DocumentSource.of(is, "sample.pdf");
        // 3. 解析文档
        ParsedDocument doc = readers.read(source);

        // 4. 打印结果
        System.out.println("=== PDF 文档解析结果 ===");
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("格式: " + doc.metadata().format());
        System.out.println("页数: " + doc.metadata().pageCount());
        System.out.println("元素数量: " + doc.elements().size());

        // 扫描件检测
        Object scanned = doc.metadata().attributes().get("scanned");
        if (Boolean.TRUE.equals(scanned)) {
            System.out.println("** 检测到扫描件 PDF **");
            System.out.println("  提示: 可通过 PdfDocumentReader(OcrEngine) 注入 OCR 引擎提取文字");
        }
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
