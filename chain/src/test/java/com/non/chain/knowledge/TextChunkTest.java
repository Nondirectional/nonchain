package com.non.chain.knowledge;

import com.non.chain.document.ElementType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TextChunkTest {

    @Test
    public void imageChunk_allowsEmptyContent() {
        TextChunk chunk = TextChunk.builder(null, ElementType.IMAGE).build();

        assertEquals("", chunk.content());
        assertEquals(ElementType.IMAGE, chunk.elementType());
        assertTrue(chunk.metadata().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void textChunk_blankContent_throwsException() {
        TextChunk.builder("   ", ElementType.TEXT).build();
    }
}
