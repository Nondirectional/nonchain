package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CleanerPipelineTest {

    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.txt")
            .format("txt")
            .build();

    @Test
    public void emptyPipeline_returnsOriginalDocument() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("hello").build())
                .build();

        CleanerPipeline pipeline = CleanerPipeline.of();
        ParsedDocument result = pipeline.clean(doc);

        assertEquals(1, result.elements().size());
        assertEquals("hello", ((TextElement) result.elements().get(0)).content());
    }

    @Test
    public void singleCleaner_appliesOnce() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("  hello   world  ").build())
                .build();

        CleanerPipeline pipeline = CleanerPipeline.of(new WhitespaceNormalizer());
        ParsedDocument result = pipeline.clean(doc);

        assertEquals(1, result.elements().size());
        assertEquals("hello world", ((TextElement) result.elements().get(0)).content());
    }

    @Test
    public void multipleCleaners_appliedInOrder() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("  ＡＢＣ  ").build())
                .build();

        // 先 Unicode 规范化（全角→半角），再空白规范化
        CleanerPipeline pipeline = CleanerPipeline.of(
                new UnicodeNormalizer(),
                new WhitespaceNormalizer()
        );
        ParsedDocument result = pipeline.clean(doc);

        assertEquals(1, result.elements().size());
        assertEquals("ABC", ((TextElement) result.elements().get(0)).content());
    }

    @Test
    public void metadata_preserved() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("hello").build())
                .build();

        CleanerPipeline pipeline = CleanerPipeline.of(new WhitespaceNormalizer());
        ParsedDocument result = pipeline.clean(doc);

        assertEquals("test.txt", result.metadata().fileName());
        assertEquals("txt", result.metadata().format());
    }

    @Test(expected = NullPointerException.class)
    public void nullDocument_throwsException() {
        CleanerPipeline pipeline = CleanerPipeline.of(new WhitespaceNormalizer());
        pipeline.clean(null);
    }

    @Test
    public void cleaners_returnsCleanerList() {
        WhitespaceNormalizer wn = new WhitespaceNormalizer();
        UnicodeNormalizer un = new UnicodeNormalizer();
        CleanerPipeline pipeline = CleanerPipeline.of(wn, un);

        assertEquals(2, pipeline.cleaners().size());
        assertSame(wn, pipeline.cleaners().get(0));
        assertSame(un, pipeline.cleaners().get(1));
    }
}
