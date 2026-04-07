package com.non.chain.knowledge;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class RetrievalResponseTest {

    private static SearchResult makeResult(String content) {
        return SearchResult.builder("kb1", "doc1", "chunk1", content, 1.0).build();
    }

    // --- Builder without debugInfo ---

    @Test
    public void build_withoutDebugInfo_keepsCompactResponse() {
        RetrievalResponse response = RetrievalResponse.builder()
                .addResult(makeResult("content"))
                .build();

        assertEquals(1, response.results().size());
        assertNull(response.debugInfo());
    }

    // --- Builder with debugInfo ---

    @Test
    public void build_withDebugInfo_keepsMetadataOnTopLevel() {
        RetrievalDebugInfo debugInfo = RetrievalDebugInfo.builder(RetrievalMode.HYBRID, 10)
                .fusionStrategy(FusionStrategy.RRF)
                .analyzer("ik_smart")
                .rankWindowSize(50)
                .numCandidates(100)
                .addMatchedRetriever("rrf")
                .build();

        RetrievalResponse response = RetrievalResponse.builder()
                .debugInfo(debugInfo)
                .build();

        assertEquals(RetrievalMode.HYBRID, response.debugInfo().mode());
        assertEquals(FusionStrategy.RRF, response.debugInfo().fusionStrategy());
        assertEquals("ik_smart", response.debugInfo().analyzer());
    }

    // --- results via list setter ---

    @Test
    public void build_resultsListSetter() {
        RetrievalResponse response = RetrievalResponse.builder()
                .results(Arrays.asList(makeResult("a"), makeResult("b")))
                .build();

        assertEquals(2, response.results().size());
    }

    // --- results list immutability ---

    @Test
    public void build_resultsListIsUnmodifiable() {
        RetrievalResponse response = RetrievalResponse.builder()
                .addResult(makeResult("content"))
                .build();

        try {
            response.results().add(makeResult("other"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    // --- results replaces previous values ---

    @Test
    public void build_resultsSetterReplacesPrevious() {
        RetrievalResponse response = RetrievalResponse.builder()
                .addResult(makeResult("first"))
                .results(Arrays.asList(makeResult("second")))
                .build();

        assertEquals(1, response.results().size());
        assertEquals("second", response.results().get(0).content());
    }

    // --- addResult null rejection ---

    @Test(expected = IllegalArgumentException.class)
    public void build_addResultNull_throwsException() {
        RetrievalResponse.builder().addResult(null);
    }

    // --- empty response ---

    @Test
    public void build_emptyResponse_hasEmptyResults() {
        RetrievalResponse response = RetrievalResponse.builder().build();

        assertNotNull(response.results());
        assertTrue(response.results().isEmpty());
        assertNull(response.debugInfo());
    }

    // --- results setter with null clears list ---

    @Test
    public void build_resultsSetterWithNull_clearsList() {
        RetrievalResponse response = RetrievalResponse.builder()
                .addResult(makeResult("first"))
                .results(null)
                .build();

        assertTrue(response.results().isEmpty());
    }
}
