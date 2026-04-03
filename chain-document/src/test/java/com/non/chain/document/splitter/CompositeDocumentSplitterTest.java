package com.non.chain.document.splitter;

import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;
import com.non.chain.knowledge.TextChunk;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class CompositeDocumentSplitterTest {

    @Test
    public void split_mergesParentMetadataIntoSecondaryChunks() {
        ParsedDocument document = ParsedDocument.builder(DocumentMetadata.builder()
                        .fileName("guide.md")
                        .format("md")
                        .build())
                .addElement(HeadingElement.builder(1, "章节").build())
                .addElement(TextElement.builder("A。B。").build())
                .build();

        CompositeDocumentSplitter splitter = new CompositeDocumentSplitter(
                new HeaderDocumentSplitter(List.of(1), false),
                RecursiveCharacterSplitter.builder()
                        .chunkSize(2)
                        .chunkOverlap(0)
                        .separators(List.of("。", ""))
                        .build()
        );

        List<TextChunk> chunks = splitter.split(document);

        assertEquals(2, chunks.size());
        assertEquals("章节", chunks.get(0).metadata().get("heading"));
        assertEquals(List.of("章节"), chunks.get(0).metadata().get("headingPath"));
        assertEquals(Integer.valueOf(0), chunks.get(0).metadata().get("chunkIndex"));

        assertEquals("章节", chunks.get(1).metadata().get("heading"));
        assertEquals(List.of("章节"), chunks.get(1).metadata().get("headingPath"));
        assertEquals(Integer.valueOf(1), chunks.get(1).metadata().get("chunkIndex"));
    }
}
