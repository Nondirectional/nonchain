package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class DuplicateRemoverTest {

    private final DuplicateRemover remover = new DuplicateRemover();
    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.pdf")
            .format("pdf")
            .build();

    @Test
    public void exactDuplicates_removed() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("页眉文字").build())
                .addElement(TextElement.builder("正文第一段").build())
                .addElement(TextElement.builder("页眉文字").build())
                .addElement(TextElement.builder("正文第二段").build())
                .addElement(TextElement.builder("页眉文字").build())
                .build();

        ParsedDocument result = remover.clean(doc);

        assertEquals(3, result.elements().size());
        assertEquals("页眉文字", textAt(result, 0));
        assertEquals("正文第一段", textAt(result, 1));
        assertEquals("正文第二段", textAt(result, 2));
    }

    @Test
    public void caseInsensitiveDuplicates_removed() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("Hello World").build())
                .addElement(TextElement.builder("hello world").build())
                .build();

        ParsedDocument result = remover.clean(doc);

        assertEquals(1, result.elements().size());
    }

    @Test
    public void whitespaceDifference_treatedAsDuplicate() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("hello  world").build())
                .addElement(TextElement.builder("hello world").build())
                .build();

        ParsedDocument result = remover.clean(doc);

        assertEquals(1, result.elements().size());
    }

    @Test
    public void noDuplicates_allKept() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("段落一").build())
                .addElement(TextElement.builder("段落二").build())
                .addElement(TextElement.builder("段落三").build())
                .build();

        ParsedDocument result = remover.clean(doc);

        assertEquals(3, result.elements().size());
    }

    @Test
    public void duplicateHeading_removed() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(HeadingElement.builder(1, "Chapter 1").build())
                .addElement(TextElement.builder("内容").build())
                .addElement(HeadingElement.builder(1, "Chapter 1").build())
                .build();

        ParsedDocument result = remover.clean(doc);

        assertEquals(2, result.elements().size());
        assertEquals(ElementType.HEADING, result.elements().get(0).elementType());
        assertEquals(ElementType.TEXT, result.elements().get(1).elementType());
    }

    @Test
    public void nonTextElements_preserved() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("hello").build())
                .addElement(ImageElement.builder(new byte[]{1}, "image/png").build())
                .addElement(TextElement.builder("hello").build())
                .addElement(ImageElement.builder(new byte[]{2}, "image/png").build())
                .build();

        ParsedDocument result = remover.clean(doc);

        assertEquals(3, result.elements().size());
        // 第一个 TextElement 保留
        assertEquals(ElementType.TEXT, result.elements().get(0).elementType());
        // 两个 ImageElement 都保留（DuplicateRemover 不处理非文本元素）
        assertEquals(ElementType.IMAGE, result.elements().get(1).elementType());
        assertEquals(ElementType.IMAGE, result.elements().get(2).elementType());
    }

    private String textAt(ParsedDocument doc, int index) {
        return ((TextElement) doc.elements().get(index)).content();
    }
}
