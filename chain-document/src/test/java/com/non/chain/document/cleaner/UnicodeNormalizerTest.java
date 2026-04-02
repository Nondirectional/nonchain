package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class UnicodeNormalizerTest {

    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.txt")
            .format("txt")
            .build();

    @Test
    public void fullwidthAscii_convertedToHalfwidth() {
        ParsedDocument doc = docWithText("Ｈｅｌｌｏ　Ｗｏｒｌｄ");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("Hello World", textContent(result));
    }

    @Test
    public void fullwidthDigits_converted() {
        ParsedDocument doc = docWithText("２０２４年");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("2024年", textContent(result));
    }

    @Test
    public void fullwidthSymbols_converted() {
        // ＝ → =, ！ → !, （ → ( but these are in FF01-FF5E range
        ParsedDocument doc = docWithText("test＝value");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("test=value", textContent(result));
    }

    @Test
    public void fullwidthSpace_convertedToHalfwidth() {
        ParsedDocument doc = docWithText("hello\u3000world"); // 全角空格
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("hello world", textContent(result));
    }

    @Test
    public void nfcNormalization_applied() {
        // é 可以用 U+00E9 (预组合) 或 U+0065 + U+0301 (组合) 表示
        String decomposed = "e\u0301"; // e + 组合重音
        ParsedDocument doc = docWithText(decomposed);
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("é", textContent(result));
        assertEquals(1, textContent(result).length()); // NFC 后为单字符
    }

    @Test
    public void normalAscii_unchanged() {
        ParsedDocument doc = docWithText("hello world 123");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("hello world 123", textContent(result));
    }

    @Test
    public void headingContent_normalized() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(HeadingElement.builder(1, "Ｔｉｔｌｅ").build())
                .build();
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("Title", headingContent(result));
    }

    @Test
    public void chineseText_unchanged() {
        ParsedDocument doc = docWithText("你好世界");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("你好世界", textContent(result));
    }

    private ParsedDocument docWithText(String text) {
        return ParsedDocument.builder(metadata)
                .addElement(TextElement.builder(text).build())
                .build();
    }

    private String textContent(ParsedDocument doc) {
        return ((TextElement) doc.elements().get(0)).content();
    }

    private String headingContent(ParsedDocument doc) {
        return ((HeadingElement) doc.elements().get(0)).content();
    }
}
