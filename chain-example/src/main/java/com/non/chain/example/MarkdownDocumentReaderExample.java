package com.non.chain.example;

import com.non.chain.document.CodeBlockElement;
import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentReaders;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;
import com.non.chain.document.markdown.MarkdownDocumentReader;

import java.io.InputStream;

/**
 * 示例：使用 MarkdownDocumentReader 解析 Markdown 文件。
 */
public class MarkdownDocumentReaderExample {

    public static void main(String[] args) throws Exception {
        // 1. 注册 Reader
        DocumentReaders readers = new DocumentReaders()
                .register(new MarkdownDocumentReader());

        // 2. 读取资源文件
        InputStream is = MarkdownDocumentReaderExample.class.getResourceAsStream("/document/sample.md");
        DocumentSource source = DocumentSource.of(is, "sample.md");

        // 3. 解析文档
        ParsedDocument doc = readers.read(source);

        // 4. 打印结果
        System.out.println("=== Markdown 文档解析结果 ===");
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("格式: " + doc.metadata().format());
        System.out.println("元素数量: " + doc.elements().size());
        System.out.println();

        for (DocumentElement element : doc.elements()) {
            if (element instanceof HeadingElement) {
                HeadingElement heading = (HeadingElement) element;
                String prefix = "#".repeat(heading.level());
                System.out.println("[HEADING " + heading.level() + "] line=" + heading.position().lineNumber());
                System.out.println("  " + prefix + " " + heading.content());
            } else if (element instanceof TextElement) {
                TextElement text = (TextElement) element;
                System.out.println("[TEXT] line=" + text.position().lineNumber());
                System.out.println("  " + text.content());
            } else if (element instanceof CodeBlockElement) {
                CodeBlockElement code = (CodeBlockElement) element;
                System.out.println("[CODE" + (code.language() != null ? " lang=" + code.language() : "") + "]");
                System.out.println("  " + code.content().replace("\n", "\n  "));
            }
            System.out.println();
        }
    }
}
