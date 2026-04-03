package com.non.chain.document.splitter;

import com.non.chain.document.CodeBlockElement;
import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.ElementType;
import com.non.chain.document.ImageElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TableElement;
import com.non.chain.document.TextElement;
import com.non.chain.knowledge.TextChunk;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecursiveCharacterSplitterTest {

    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.md")
            .format("md")
            .build();

    @Test
    public void split_keepsSeparatorWithoutDuplication() {
        RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
                .chunkSize(4)
                .chunkOverlap(0)
                .separators(List.of("。", ""))
                .keepSeparator(true)
                .build();

        List<TextChunk> chunks = splitter.split("A。B");

        assertEquals(1, chunks.size());
        assertEquals("A。B", chunks.get(0).content());
    }

    @Test
    public void split_preservesAtomicElementsAndMetadata() {
        ParsedDocument document = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("前文").build())
                .addElement(TableElement.builder()
                        .position(DocumentPosition.builder().pageNumber(2).build())
                        .putMetadata("source", "table-1")
                        .addHeader("姓名")
                        .addHeader("城市")
                        .addRow(Arrays.asList("张三", "北京"))
                        .build())
                .addElement(CodeBlockElement.builder("System.out.println(\"ok\");")
                        .language("java")
                        .build())
                .addElement(ImageElement.builder(new byte[]{1}, "image/png")
                        .fileName("img-1.png")
                        .position(DocumentPosition.builder().pageNumber(3).build())
                        .build())
                .addElement(TextElement.builder("后文").build())
                .build();

        RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
                .chunkSize(100)
                .chunkOverlap(0)
                .build();

        List<TextChunk> chunks = splitter.split(document);

        assertEquals(5, chunks.size());

        assertEquals(ElementType.TEXT, chunks.get(0).elementType());
        assertEquals(Integer.valueOf(0), chunks.get(0).metadata().get("chunkIndex"));

        assertEquals(ElementType.TABLE, chunks.get(1).elementType());
        assertTrue(chunks.get(1).content().contains("| 姓名 | 城市 |"));
        assertEquals("table-1", chunks.get(1).metadata().get("source"));
        assertEquals(Integer.valueOf(2), chunks.get(1).metadata().get("page"));
        assertEquals(Integer.valueOf(1), chunks.get(1).metadata().get("chunkIndex"));

        assertEquals(ElementType.CODE_BLOCK, chunks.get(2).elementType());
        assertEquals("java", chunks.get(2).metadata().get("language"));
        assertEquals(Integer.valueOf(2), chunks.get(2).metadata().get("chunkIndex"));

        assertEquals(ElementType.IMAGE, chunks.get(3).elementType());
        assertEquals("", chunks.get(3).content());
        assertEquals("img-1.png", chunks.get(3).metadata().get("imageRef"));
        assertEquals(Integer.valueOf(3), chunks.get(3).metadata().get("page"));
        assertEquals(Integer.valueOf(3), chunks.get(3).metadata().get("chunkIndex"));

        assertEquals(ElementType.TEXT, chunks.get(4).elementType());
        assertEquals("后文", chunks.get(4).content());
        assertEquals(Integer.valueOf(4), chunks.get(4).metadata().get("chunkIndex"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_overlapNotSmallerThanChunkSize_throwsException() {
        RecursiveCharacterSplitter.builder()
                .chunkSize(10)
                .chunkOverlap(10)
                .build();
    }

    @Test
    public void split_oversizedAtomicElement_marksOversized() {
        ParsedDocument document = ParsedDocument.builder(metadata)
                .addElement(CodeBlockElement.builder("01234567890123456789").build())
                .build();

        RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
                .chunkSize(5)
                .chunkOverlap(0)
                .build();

        List<TextChunk> chunks = splitter.split(document);

        assertEquals(1, chunks.size());
        assertEquals(Boolean.TRUE, chunks.get(0).metadata().get("oversized"));
        assertFalse(chunks.get(0).content().isEmpty());
    }
}
