package com.non.chain.document.splitter;

import com.non.chain.document.ParsedDocument;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.TextChunk;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SemanticSplitterTest {

    @Test
    public void split_breaksAtSemanticBoundary() {
        SemanticSplitter splitter = SemanticSplitter.builder(new StubEmbeddingModel())
                .bufferSize(1)
                .breakpointThreshold(0.5)
                .build();

        List<TextChunk> chunks = splitter.split(ParsedDocument.fromText("甲。乙。丙。丁。"));

        assertEquals(2, chunks.size());
        assertEquals("甲。乙。", chunks.get(0).content());
        assertEquals("丙。丁。", chunks.get(1).content());
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_negativeMaxChunkSize_throwsException() {
        SemanticSplitter.builder(new StubEmbeddingModel())
                .maxChunkSize(-1)
                .build();
    }

    private static class StubEmbeddingModel implements EmbeddingModel {
        @Override
        public List<float[]> embedAll(List<String> texts) {
            List<float[]> result = new ArrayList<>();
            for (String text : texts) {
                if (text.contains("丙") || text.contains("丁")) {
                    result.add(new float[]{0F, 1F});
                } else {
                    result.add(new float[]{1F, 0F});
                }
            }
            return result;
        }
    }
}
