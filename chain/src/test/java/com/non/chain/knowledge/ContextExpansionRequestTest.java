package com.non.chain.knowledge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ContextExpansionRequestTest {

    @Test
    public void build_defaultsToIncludeCenterTrue() {
        ContextExpansionRequest request = ContextExpansionRequest.builder("doc-1", 3).build();

        assertEquals("doc-1", request.documentId());
        assertEquals(3, request.centerChunkIndex());
        assertEquals(0, request.before());
        assertEquals(0, request.after());
        assertTrue(request.includeCenter());
        assertNull(request.knowledgeBaseId());
    }

    @Test
    public void build_allowsExplicitWindowAndKnowledgeBase() {
        ContextExpansionRequest request = ContextExpansionRequest.builder("doc-1", 5)
                .before(2)
                .after(1)
                .includeCenter(false)
                .knowledgeBaseId("kb-1")
                .build();

        assertEquals(2, request.before());
        assertEquals(1, request.after());
        assertFalse(request.includeCenter());
        assertEquals("kb-1", request.knowledgeBaseId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_blankDocumentId_throwsException() {
        ContextExpansionRequest.builder("   ", 1).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_negativeCenterChunkIndex_throwsException() {
        ContextExpansionRequest.builder("doc-1", -1).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_negativeBefore_throwsException() {
        ContextExpansionRequest.builder("doc-1", 1)
                .before(-1)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_negativeAfter_throwsException() {
        ContextExpansionRequest.builder("doc-1", 1)
                .after(-1)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_emptyWindowWithoutCenter_throwsException() {
        ContextExpansionRequest.builder("doc-1", 1)
                .includeCenter(false)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_blankKnowledgeBaseId_throwsException() {
        ContextExpansionRequest.builder("doc-1", 1)
                .knowledgeBaseId(" ")
                .build();
    }
}
