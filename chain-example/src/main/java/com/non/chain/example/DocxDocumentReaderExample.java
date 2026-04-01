package com.non.chain.example;

import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentReaders;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ImageElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TableElement;
import com.non.chain.document.TextElement;
import com.non.chain.document.docx.DocxDocumentReader;

import java.io.File;

/**
 * 示例：使用 DocxDocumentReader 解析 DOCX 文件，提取文本、表格和嵌入图片。
 *
 * 前置条件：准备一个 DOCX 文件（包含标题、正文、表格、图片），修改下方 filePath 路径。
 */
public class DocxDocumentReaderExample {

    public static void main(String[] args) throws Exception {
        // 1. 注册 Reader
        DocumentReaders readers = new DocumentReaders()
                .register(new DocxDocumentReader());

        // 2. 读取 DOCX 文件（请替换为实际文件路径）
        String filePath = "example.docx";
        DocumentSource source = DocumentSource.fromFile(new File(filePath));

        // 3. 解析文档
        ParsedDocument doc = readers.read(source);

        // 4. 打印结果
        System.out.println("=== DOCX 文档解析结果 ===");
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("格式: " + doc.metadata().format());
        System.out.println("元素数量: " + doc.elements().size());
        System.out.println();

        int headingCount = 0;
        int textCount = 0;
        int tableCount = 0;
        int imageCount = 0;

        for (DocumentElement element : doc.elements()) {
            if (element instanceof HeadingElement) {
                headingCount++;
                HeadingElement heading = (HeadingElement) element;
                String prefix = "#".repeat(heading.level());
                System.out.println("[HEADING " + heading.level() + "] " + prefix + " " + heading.content());
            } else if (element instanceof TextElement) {
                textCount++;
                TextElement text = (TextElement) element;
                System.out.println("[TEXT] " + text.content().replace("\n", " "));
            } else if (element instanceof TableElement) {
                tableCount++;
                TableElement table = (TableElement) element;
                System.out.println("[TABLE] headers=" + table.headers() + ", rows=" + table.rows().size());
                for (int i = 0; i < table.rows().size(); i++) {
                    System.out.println("  row" + i + ": " + table.rows().get(i));
                }
            } else if (element instanceof ImageElement) {
                imageCount++;
                ImageElement image = (ImageElement) element;
                System.out.println("[IMAGE] mimeType=" + image.mimeType()
                        + ", size=" + image.data().length + " bytes"
                        + (image.fileName() != null ? ", name=" + image.fileName() : ""));
            }
        }

        System.out.println();
        System.out.println("统计: 标题=" + headingCount
                + ", 文本=" + textCount
                + ", 表格=" + tableCount
                + ", 图片=" + imageCount);
    }
}
