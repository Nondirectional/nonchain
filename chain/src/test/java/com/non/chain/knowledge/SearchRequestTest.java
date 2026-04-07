package com.non.chain.knowledge;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SearchRequestTest {

    // --- Mode detection (auto-degradation) ---

    @Test
    public void build_withQueryTextOnly_defaultsToBm25() {
        SearchRequest request = SearchRequest.builder()
                .queryText("Elasticsearch")
                .build();

        assertEquals(RetrievalMode.BM25, request.mode());
    }

    @Test
    public void build_withEmbeddingOnly_defaultsToKnn() {
        SearchRequest request = SearchRequest.builder(new float[]{0.1f, 0.2f})
                .build();

        assertEquals(RetrievalMode.KNN, request.mode());
    }

    @Test
    public void build_withTextAndEmbedding_defaultsToHybrid() {
        SearchRequest request = SearchRequest.builder()
                .queryText("检索词")
                .queryEmbedding(new float[]{0.1f, 0.2f})
                .build();

        assertEquals(RetrievalMode.HYBRID, request.mode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_withoutQueryTextAndEmbedding_throwsException() {
        SearchRequest.builder().build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_withBlankQueryTextAndNoEmbedding_throwsException() {
        SearchRequest.builder()
                .queryText("   ")
                .build();
    }

    @Test
    public void build_withEmptyEmbeddingAndNoText_throwsException() {
        try {
            SearchRequest.builder(new float[]{}).build();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    // --- Default values ---

    @Test
    public void build_defaultSizeIs10() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .build();

        assertEquals(10, request.size());
    }

    @Test
    public void build_defaultRankWindowSizeIsMax50SizeTimes5() {
        // size=10 -> max(50, 10*5) = 50
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .build();
        assertEquals(50, request.rankWindowSize());
    }

    @Test
    public void build_defaultRankWindowSizeIsMax50SizeTimes5_withLargerSize() {
        // size=20 -> max(50, 20*5) = 100
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .size(20)
                .build();
        assertEquals(100, request.rankWindowSize());
    }

    @Test
    public void build_defaultNumCandidatesIsMax100RankWindowSizeTimes2() {
        // rankWindowSize=50 -> max(100, 50*2) = 100
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .build();
        assertEquals(100, request.numCandidates());
    }

    @Test
    public void build_defaultNumCandidatesIsMax100RankWindowSizeTimes2_withLargerSize() {
        // size=20 -> rankWindowSize=100 -> numCandidates=max(100, 100*2) = 200
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .size(20)
                .build();
        assertEquals(200, request.numCandidates());
    }

    @Test
    public void build_defaultFusionStrategyIsRrf() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .build();
        assertEquals(FusionStrategy.RRF, request.fusionStrategy());
    }

    @Test
    public void build_defaultVectorWeightIs1() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .build();
        assertEquals(1.0, request.vectorWeight(), 0.001);
    }

    @Test
    public void build_defaultKeywordWeightIs1() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .build();
        assertEquals(1.0, request.keywordWeight(), 0.001);
    }

    // --- Validation ---

    @Test(expected = IllegalArgumentException.class)
    public void build_sizeZero_throwsException() {
        SearchRequest.builder().queryText("test").size(0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_sizeNegative_throwsException() {
        SearchRequest.builder().queryText("test").size(-1).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_rankWindowSizeSmallerThanSize_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .size(20)
                .rankWindowSize(10)
                .build();
    }

    @Test
    public void build_rankWindowSizeEqualToSize_isValid() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .size(10)
                .rankWindowSize(10)
                .build();
        assertEquals(10, request.rankWindowSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_numCandidatesZero_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .numCandidates(0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_numCandidatesNegative_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .numCandidates(-5)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_vectorWeightZero_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .vectorWeight(0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_vectorWeightNegative_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .vectorWeight(-0.5)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_keywordWeightZero_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .keywordWeight(0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_keywordWeightNegative_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .keywordWeight(-0.5)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_fusionStrategyNull_throwsException() {
        SearchRequest.builder()
                .queryText("test")
                .fusionStrategy(null)
                .build();
    }

    // --- debug/trace behavior ---

    @Test
    public void build_defaultsToDebugFalse() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .build();
        assertFalse(request.debug());
        assertFalse(request.trace());
    }

    @Test
    public void build_traceImpliesDebug() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .trace(true)
                .build();
        assertTrue(request.debug());
        assertTrue(request.trace());
    }

    @Test
    public void build_debugExplicitlySet() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .debug(true)
                .build();
        assertTrue(request.debug());
        assertFalse(request.trace());
    }

    @Test
    public void build_withTextAndEmbedding_traceSetsDebugAndTrace() {
        SearchRequest request = SearchRequest.builder()
                .queryText("检索词")
                .queryEmbedding(new float[]{0.1f, 0.2f})
                .trace(true)
                .build();

        assertTrue(request.debug());
        assertTrue(request.trace());
    }

    // --- Immutability ---

    @Test
    public void build_queryEmbeddingReturnsDefensiveCopy() {
        float[] original = new float[]{0.1f, 0.2f, 0.3f};
        SearchRequest request = SearchRequest.builder()
                .queryEmbedding(original)
                .build();

        float[] copy = request.queryEmbedding();
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, copy, 0.001f);

        // Mutating the copy should not affect the request
        copy[0] = 999f;
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, request.queryEmbedding(), 0.001f);
    }

    @Test
    public void build_queryEmbeddingInBuilder_defensiveCopyOnInput() {
        float[] original = new float[]{0.1f, 0.2f};
        SearchRequest.Builder builder = SearchRequest.builder()
                .queryEmbedding(original);

        // Mutating the original after setting should not affect the builder
        original[0] = 999f;
        SearchRequest request = builder.build();
        assertArrayEquals(new float[]{0.1f, 0.2f}, request.queryEmbedding(), 0.001f);
    }

    @Test
    public void build_knowledgeBaseIdsIsUnmodifiable() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .addKnowledgeBaseId("kb1")
                .build();

        try {
            request.knowledgeBaseIds().add("kb2");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void build_documentIdsIsUnmodifiable() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .addDocumentId("doc1")
                .build();

        try {
            request.documentIds().add("doc2");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void build_chunkIdsIsUnmodifiable() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .addChunkId("chunk1")
                .build();

        try {
            request.chunkIds().add("chunk2");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    // --- ID list operations ---

    @Test(expected = IllegalArgumentException.class)
    public void build_addKnowledgeBaseIdNull_throwsException() {
        SearchRequest.builder().queryText("test").addKnowledgeBaseId(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addKnowledgeBaseIdBlank_throwsException() {
        SearchRequest.builder().queryText("test").addKnowledgeBaseId("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addDocumentIdNull_throwsException() {
        SearchRequest.builder().queryText("test").addDocumentId(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addDocumentIdBlank_throwsException() {
        SearchRequest.builder().queryText("test").addDocumentId("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addChunkIdNull_throwsException() {
        SearchRequest.builder().queryText("test").addChunkId(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_addChunkIdBlank_throwsException() {
        SearchRequest.builder().queryText("test").addChunkId("  ");
    }

    // --- ID list replacement ---

    @Test
    public void build_knowledgeBaseIdsReplacesOnSet() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .addKnowledgeBaseId("kb1")
                .knowledgeBaseIds(Arrays.asList("kb2", "kb3"))
                .build();

        assertEquals(Arrays.asList("kb2", "kb3"), request.knowledgeBaseIds());
    }

    @Test
    public void build_documentIdsReplacesOnSet() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .addDocumentId("doc1")
                .documentIds(Arrays.asList("doc2", "doc3"))
                .build();

        assertEquals(Arrays.asList("doc2", "doc3"), request.documentIds());
    }

    @Test
    public void build_chunkIdsReplacesOnSet() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .addChunkId("chunk1")
                .chunkIds(Arrays.asList("chunk2", "chunk3"))
                .build();

        assertEquals(Arrays.asList("chunk2", "chunk3"), request.chunkIds());
    }

    @Test
    public void build_knowledgeBaseIdsNullClearsList() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .addKnowledgeBaseId("kb1")
                .knowledgeBaseIds(null)
                .build();

        assertTrue(request.knowledgeBaseIds().isEmpty());
    }

    // --- topK alias ---

    @Test
    public void build_topKAliasEqualsSize() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .topK(5)
                .build();

        assertEquals(5, request.size());
        assertEquals(5, request.topK());
    }

    // --- hasQueryText / hasQueryEmbedding ---

    @Test
    public void build_hasQueryTextTrue() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .build();
        assertTrue(request.hasQueryText());
        assertFalse(request.hasQueryEmbedding());
    }

    @Test
    public void build_hasQueryEmbeddingTrue() {
        SearchRequest request = SearchRequest.builder(new float[]{0.1f})
                .build();
        assertFalse(request.hasQueryText());
        assertTrue(request.hasQueryEmbedding());
    }

    @Test
    public void build_hasBothQueryTextAndEmbedding() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .build();
        assertTrue(request.hasQueryText());
        assertTrue(request.hasQueryEmbedding());
    }

    // --- Custom parameter values ---

    @Test
    public void build_customRankWindowSize() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .rankWindowSize(200)
                .build();
        assertEquals(200, request.rankWindowSize());
    }

    @Test
    public void build_customNumCandidates() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .numCandidates(500)
                .build();
        assertEquals(500, request.numCandidates());
    }

    @Test
    public void build_linearFusionStrategy() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .queryEmbedding(new float[]{0.1f})
                .fusionStrategy(FusionStrategy.LINEAR)
                .vectorWeight(0.7)
                .keywordWeight(0.3)
                .build();

        assertEquals(FusionStrategy.LINEAR, request.fusionStrategy());
        assertEquals(0.7, request.vectorWeight(), 0.001);
        assertEquals(0.3, request.keywordWeight(), 0.001);
    }

    // --- queryText normalization ---

    @Test
    public void build_queryTextIsTrimmed() {
        SearchRequest request = SearchRequest.builder()
                .queryText("  test  ")
                .build();

        assertEquals("test", request.queryText());
    }

    @Test
    public void build_metadataFilterPreserved() {
        MetadataFilter filter = MetadataFilter.condition("tag", MetadataFilter.Operator.EQ, "java");
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .metadataFilter(filter)
                .build();

        assertNotNull(request.metadataFilter());
        assertEquals(MetadataFilter.Type.CONDITION, request.metadataFilter().type());
        assertEquals("tag", request.metadataFilter().key());
    }

    @Test
    public void build_noMetadataFilter_byDefault() {
        SearchRequest request = SearchRequest.builder()
                .queryText("test")
                .build();

        assertNull(request.metadataFilter());
    }

    // --- builder(float[]) static factory ---

    @Test
    public void build_staticFactoryWithEmbedding() {
        SearchRequest request = SearchRequest.builder(new float[]{0.5f, 0.6f})
                .build();

        assertEquals(RetrievalMode.KNN, request.mode());
        assertArrayEquals(new float[]{0.5f, 0.6f}, request.queryEmbedding(), 0.001f);
    }

    @Test
    public void build_staticFactoryWithNullEmbedding_stillAllowsBuildWithText() {
        SearchRequest request = SearchRequest.builder((float[]) null)
                .queryText("test")
                .build();

        assertEquals(RetrievalMode.BM25, request.mode());
    }
}
