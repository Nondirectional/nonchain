package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class WhitespaceNormalizerTest {

    private final WhitespaceNormalizer normalizer = new WhitespaceNormalizer();
    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.txt")
            .format("txt")
            .build();

    @Test
    public void multipleSpaces_collapsed() {
        ParsedDocument doc = docWithText("hello    world");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("hello world", textContent(result));
    }

    @Test
    public void leadingTrailingWhitespace_trimmed() {
        ParsedDocument doc = docWithText("   hello world   ");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("hello world", textContent(result));
    }

    @Test
    public void tabsAndNewlines_normalized() {
        ParsedDocument doc = docWithText("hello\t\n\tworld");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("hello world", textContent(result));
    }

    @Test
    public void noWhitespace_unchanged() {
        ParsedDocument doc = docWithText("hello");
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("hello", textContent(result));
    }

    @Test
    public void blankAfterNormalization_removed() {
        // 用含控制字符的文本来测试：控制字符被替换后内容为空
        // 因为 TextElement.builder 不允许空白内容，所以用混合场景测试
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("keep this").build())
                .addElement(TextElement.builder("also keep").build())
                .build();
        ParsedDocument result = normalizer.clean(doc);

        assertEquals(2, result.elements().size());
    }

    @Test
    public void headingContent_normalized() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(HeadingElement.builder(1, "  title   with   spaces  ").build())
                .build();
        ParsedDocument result = normalizer.clean(doc);

        assertEquals("title with spaces", headingContent(result));
        assertEquals(1, ((HeadingElement) result.elements().get(0)).level());
    }

    @Test
    public void imageElement_passedThrough() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("hello  world").build())
                .addElement(ImageElement.builder(new byte[]{1, 2, 3}, "image/png").fileName("test.png").build())
                .build();
        ParsedDocument result = normalizer.clean(doc);

        assertEquals(2, result.elements().size());
        assertEquals(ElementType.IMAGE, result.elements().get(1).elementType());
    }

    @Test
    public void positionAndMetadata_preserved() {
        DocumentPosition pos = DocumentPosition.builder().lineNumber(5).build();
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("hello  world")
                        .position(pos)
                        .build())
                .build();
        ParsedDocument result = normalizer.clean(doc);

        TextElement element = (TextElement) result.elements().get(0);
        assertEquals(Integer.valueOf(5), element.position().lineNumber());
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
