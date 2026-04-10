package com.non.chain.knowledge;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ContextExpansionResponseTest {

    private static SearchResult makeChunk(String chunkId, int chunkIndex) {
        return SearchResult.builder("kb-1", "doc-1", chunkId, "content-" + chunkIndex, 1.0)
                .chunkIndex(chunkIndex)
                .build();
    }

    @Test
    public void build_keepsChunksAndBoundaryFlags() {
        ContextExpansionResponse response = ContextExpansionResponse.builder()
                .chunks(Arrays.asList(makeChunk("chunk-1", 1), makeChunk("chunk-2", 2)))
                .hasPrevious(true)
                .hasNext(false)
                .startChunkIndex(1)
                .endChunkIndex(2)
                .build();

        assertEquals(2, response.chunks().size());
        assertTrue(response.hasPrevious());
        assertFalse(response.hasNext());
        assertEquals(Integer.valueOf(1), response.startChunkIndex());
        assertEquals(Integer.valueOf(2), response.endChunkIndex());
    }

    @Test
    public void build_chunksListIsUnmodifiable() {
        ContextExpansionResponse response = ContextExpansionResponse.builder()
                .addChunk(makeChunk("chunk-1", 1))
                .build();

        try {
            response.chunks().add(makeChunk("chunk-2", 2));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void build_emptyResponse_hasEmptyChunks() {
        ContextExpansionResponse response = ContextExpansionResponse.builder().build();

        assertNotNull(response.chunks());
        assertTrue(response.chunks().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addChunkNull_throwsException() {
        ContextExpansionResponse.builder().addChunk(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_negativeStartChunkIndex_throwsException() {
        ContextExpansionResponse.builder()
                .startChunkIndex(-1)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_negativeEndChunkIndex_throwsException() {
        ContextExpansionResponse.builder()
                .endChunkIndex(-1)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_startGreaterThanEnd_throwsException() {
        ContextExpansionResponse.builder()
                .startChunkIndex(3)
                .endChunkIndex(2)
                .build();
    }
}
