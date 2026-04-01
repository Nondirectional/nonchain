package com.non.chain.example;

import com.non.chain.document.CodeBlockElement;
import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentReaders;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TableElement;
import com.non.chain.document.TextElement;
import com.non.chain.document.html.HtmlDocumentReader;

import java.io.InputStream;

/**
 * 示例：使用 HtmlDocumentReader 解析 HTML 文件。
 */
public class HtmlDocumentReaderExample {

    public static void main(String[] args) throws Exception {
        // 1. 注册 Reader
        DocumentReaders readers = new DocumentReaders()
                .register(new HtmlDocumentReader());

        // 2. 读取资源文件
        InputStream is = HtmlDocumentReaderExample.class.getResourceAsStream("/document/sample.html");
        DocumentSource source = DocumentSource.of(is, "sample.html");

        // 3. 解析文档
        ParsedDocument doc = readers.read(source);

        // 4. 打印结果
        System.out.println("=== HTML 文档解析结果 ===");
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("格式: " + doc.metadata().format());
        System.out.println("元素数量: " + doc.elements().size());
        System.out.println();

        for (DocumentElement element : doc.elements()) {
            if (element instanceof HeadingElement) {
                HeadingElement heading = (HeadingElement) element;
                System.out.println("[HEADING " + heading.level() + "] " + heading.content());
            } else if (element instanceof TextElement) {
                TextElement text = (TextElement) element;
                System.out.println("[TEXT] " + text.content());
            } else if (element instanceof TableElement) {
                TableElement table = (TableElement) element;
                System.out.println("[TABLE] headers=" + table.headers() + ", rows=" + table.rows().size());
                for (int i = 0; i < table.rows().size(); i++) {
                    System.out.println("  row" + i + ": " + table.rows().get(i));
                }
            } else if (element instanceof CodeBlockElement) {
                CodeBlockElement code = (CodeBlockElement) element;
                System.out.println("[CODE lang=" + code.language() + "]");
                System.out.println("  " + code.content().replace("\n", "\n  "));
            }
            System.out.println();
        }
    }
}
