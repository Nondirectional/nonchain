package com.non.chain.example;

import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentReaders;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;
import com.non.chain.document.txt.TxtDocumentReader;

import java.io.InputStream;

/**
 * 示例：使用 TxtDocumentReader 解析纯文本文件。
 */
public class TxtDocumentReaderExample {

    public static void main(String[] args) throws Exception {
        // 1. 注册 Reader
        DocumentReaders readers = new DocumentReaders()
                .register(new TxtDocumentReader());

        // 2. 读取资源文件
        InputStream is = TxtDocumentReaderExample.class.getResourceAsStream("/document/sample.txt");
        DocumentSource source = DocumentSource.of(is, "sample.txt");

        // 3. 解析文档
        ParsedDocument doc = readers.read(source);

        // 4. 打印结果
        System.out.println("=== TXT 文档解析结果 ===");
        System.out.println("文件名: " + doc.metadata().fileName());
        System.out.println("格式: " + doc.metadata().format());
        System.out.println("元素数量: " + doc.elements().size());
        System.out.println();

        for (DocumentElement element : doc.elements()) {
            if (element instanceof TextElement) {
                TextElement text = (TextElement) element;
                System.out.println("[TEXT] line=" + text.position().lineNumber());
                System.out.println("  " + text.content());
                System.out.println();
            }
        }
    }
}
