package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class ControlCharacterRemoverTest {

    private final ControlCharacterRemover remover = new ControlCharacterRemover();
    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.txt")
            .format("txt")
            .build();

    @Test
    public void zeroWidthCharacters_removed() {
        // 零宽空格 U+200B, 零宽非断空格 U+FEFF (BOM)
        ParsedDocument doc = docWithText("hel\u200Blo\uFEFF world");
        ParsedDocument result = remover.clean(doc);

        assertEquals("hello world", textContent(result));
    }

    @Test
    public void controlCharacters_removed() {
        // BEL (0x07), BS (0x08), DEL (0x7F)
        ParsedDocument doc = docWithText("hello\u0007\u0008world\u007F");
        ParsedDocument result = remover.clean(doc);

        assertEquals("helloworld", textContent(result));
    }

    @Test
    public void newlinesAndTabs_preserved() {
        ParsedDocument doc = docWithText("hello\n\tworld");
        ParsedDocument result = remover.clean(doc);

        assertEquals("hello\n\tworld", textContent(result));
    }

    @Test
    public void normalText_unchanged() {
        ParsedDocument doc = docWithText("hello world");
        ParsedDocument result = remover.clean(doc);

        assertEquals("hello world", textContent(result));
    }

    @Test
    public void elementWithOnlyControlChars_removed() {
        // TextElement builder 不允许空白内容，所以用混合场景测试
        // 混合文本中的控制字符被移除，保留正常文字
        ParsedDocument doc = docWithText("a\u200B\uFEFF\u0007b");
        ParsedDocument result = remover.clean(doc);

        assertEquals("ab", textContent(result));
    }

    @Test
    public void headingContent_cleaned() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(HeadingElement.builder(2, "title\u200Bwith\uFEFFzero").build())
                .build();
        ParsedDocument result = remover.clean(doc);

        assertEquals("titlewithzero", headingContent(result));
    }

    @Test
    public void carriageReturn_preserved() {
        ParsedDocument doc = docWithText("hello\r\nworld");
        ParsedDocument result = remover.clean(doc);

        assertEquals("hello\r\nworld", textContent(result));
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
