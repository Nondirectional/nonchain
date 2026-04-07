package com.non.chain.knowledge;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class RetrievalDebugInfoTest {

    // --- Builder requires mode and size ---

    @Test
    public void build_basicBuilder_succeeds() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .build();

        assertEquals(RetrievalMode.BM25, info.mode());
        assertEquals(10, info.size());
        assertNull(info.fusionStrategy());
        assertNull(info.analyzer());
        assertNull(info.rankWindowSize());
        assertNull(info.numCandidates());
        assertTrue(info.filtersApplied().isEmpty());
        assertFalse(info.profileIncluded());
        assertNull(info.tookMs());
        assertTrue(info.matchedRetrievers().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_sizeZero_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_sizeNegative_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, -1).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_rankWindowSizeZero_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .rankWindowSize(0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_rankWindowSizeNegative_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .rankWindowSize(-5)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_numCandidatesZero_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .numCandidates(0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_numCandidatesNegative_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .numCandidates(-10)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_tookMsNegative_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .tookMs(-1L)
                .build();
    }

    // --- All fields populated ---

    @Test
    public void build_allFieldsPopulated() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.HYBRID, 10)
                .fusionStrategy(FusionStrategy.RRF)
                .analyzer("ik_smart")
                .rankWindowSize(50)
                .numCandidates(100)
                .filtersApplied(Arrays.asList("knowledgeBaseIds", "metadataFilter"))
                .profileIncluded(true)
                .tookMs(42L)
                .matchedRetrievers(Arrays.asList("standard", "knn", "rrf"))
                .build();

        assertEquals(RetrievalMode.HYBRID, info.mode());
        assertEquals(FusionStrategy.RRF, info.fusionStrategy());
        assertEquals("ik_smart", info.analyzer());
        assertEquals(10, info.size());
        assertEquals(Integer.valueOf(50), info.rankWindowSize());
        assertEquals(Integer.valueOf(100), info.numCandidates());
        assertEquals(2, info.filtersApplied().size());
        assertTrue(info.profileIncluded());
        assertEquals(Long.valueOf(42), info.tookMs());
        assertEquals(3, info.matchedRetrievers().size());
    }

    // --- List field immutability ---

    @Test
    public void build_filtersAppliedIsUnmodifiable() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addFilterApplied("knowledgeBaseIds")
                .build();

        try {
            info.filtersApplied().add("other");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void build_matchedRetrieversIsUnmodifiable() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addMatchedRetriever("standard")
                .build();

        try {
            info.matchedRetrievers().add("other");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    // --- addFilterApplied / addMatchedRetriever validation ---

    @Test(expected = IllegalArgumentException.class)
    public void build_addFilterAppliedNull_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addFilterApplied(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addFilterAppliedBlank_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addFilterApplied("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addMatchedRetrieverNull_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addMatchedRetriever(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addMatchedRetrieverBlank_throwsException() {
        RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addMatchedRetriever("  ");
    }

    // --- List setter replaces previous ---

    @Test
    public void build_filtersAppliedSetterReplacesPrevious() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addFilterApplied("first")
                .filtersApplied(Arrays.asList("second"))
                .build();

        assertEquals(1, info.filtersApplied().size());
        assertEquals("second", info.filtersApplied().get(0));
    }

    @Test
    public void build_matchedRetrieversSetterReplacesPrevious() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addMatchedRetriever("first")
                .matchedRetrievers(Arrays.asList("second"))
                .build();

        assertEquals(1, info.matchedRetrievers().size());
        assertEquals("second", info.matchedRetrievers().get(0));
    }

    @Test
    public void build_filtersAppliedNull_clearsList() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addFilterApplied("first")
                .filtersApplied(null)
                .build();

        assertTrue(info.filtersApplied().isEmpty());
    }

    @Test
    public void build_matchedRetrieversNull_clearsList() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .addMatchedRetriever("first")
                .matchedRetrievers(null)
                .build();

        assertTrue(info.matchedRetrievers().isEmpty());
    }

    // --- tookMs zero is valid ---

    @Test
    public void build_tookMsZero_isValid() {
        RetrievalDebugInfo info = RetrievalDebugInfo.builder(RetrievalMode.BM25, 10)
                .tookMs(0L)
                .build();
        assertEquals(Long.valueOf(0), info.tookMs());
    }
}
