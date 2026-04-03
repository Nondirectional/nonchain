package com.non.chain.document.splitter;

import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.ElementType;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TableElement;
import com.non.chain.document.TextElement;
import com.non.chain.knowledge.TextChunk;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class HeaderDocumentSplitterTest {

    @Test
    public void split_attachesHeadingMetadataToTextAndAtomicChunks() {
        ParsedDocument document = ParsedDocument.builder(DocumentMetadata.builder()
                        .fileName("guide.md")
                        .format("md")
                        .build())
                .addElement(HeadingElement.builder(1, "第一章").build())
                .addElement(TextElement.builder("章节介绍").build())
                .addElement(HeadingElement.builder(2, "概述").build())
                .addElement(TableElement.builder()
                        .position(DocumentPosition.builder().pageNumber(5).build())
                        .addHeader("键")
                        .addHeader("值")
                        .addRow(Arrays.asList("A", "1"))
                        .build())
                .addElement(TextElement.builder("详细说明").build())
                .build();

        HeaderDocumentSplitter splitter = new HeaderDocumentSplitter(List.of(1, 2), false);
        List<TextChunk> chunks = splitter.split(document);

        assertEquals(3, chunks.size());

        assertEquals(ElementType.TEXT, chunks.get(0).elementType());
        assertEquals("第一章", chunks.get(0).metadata().get("heading"));
        assertEquals(1, chunks.get(0).metadata().get("headingLevel"));
        assertEquals(List.of("第一章"), chunks.get(0).metadata().get("headingPath"));

        assertEquals(ElementType.TABLE, chunks.get(1).elementType());
        assertEquals("概述", chunks.get(1).metadata().get("heading"));
        assertEquals(2, chunks.get(1).metadata().get("headingLevel"));
        assertEquals(List.of("第一章", "概述"), chunks.get(1).metadata().get("headingPath"));
        assertEquals(Integer.valueOf(5), chunks.get(1).metadata().get("page"));

        assertEquals(ElementType.TEXT, chunks.get(2).elementType());
        assertEquals("概述", chunks.get(2).metadata().get("heading"));
        assertEquals(List.of("第一章", "概述"), chunks.get(2).metadata().get("headingPath"));
    }
}
