package com.non.chain.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public class RetrievalModeTest {

    @Test
    public void values_containsBm25KnnHybrid() {
        RetrievalMode[] values = RetrievalMode.values();
        assertEquals(3, values.length);
        assertArrayEquals(new RetrievalMode[]{RetrievalMode.BM25, RetrievalMode.KNN, RetrievalMode.HYBRID}, values);
    }

    @Test
    public void valueOf_bm25() {
        assertEquals(RetrievalMode.BM25, RetrievalMode.valueOf("BM25"));
    }

    @Test
    public void valueOf_knn() {
        assertEquals(RetrievalMode.KNN, RetrievalMode.valueOf("KNN"));
    }

    @Test
    public void valueOf_hybrid() {
        assertEquals(RetrievalMode.HYBRID, RetrievalMode.valueOf("HYBRID"));
    }
}
