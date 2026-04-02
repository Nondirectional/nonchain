package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class ShortFragmentMergerTest {

    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.pdf")
            .format("pdf")
            .build();

    @Test
    public void shortFragmentMergedIntoPrevious() {
        ShortFragmentMerger merger = new ShortFragmentMerger(10);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("这是一段较长的正文内容，长度超过十个字符的阈值限制").build())
                .addElement(TextElement.builder("残段").build())
                .addElement(TextElement.builder("这也是一段超过十个字符的正常内容段落").build())
                .build();

        ParsedDocument result = merger.clean(doc);

        assertEquals(2, result.elements().size());
        assertTrue(textAt(result, 0).contains("残段"));
        assertEquals("这也是一段超过十个字符的正常内容段落", textAt(result, 1));
    }

    @Test
    public void firstElementShort_notMerged() {
        ShortFragmentMerger merger = new ShortFragmentMerger(10);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("短句").build())
                .addElement(TextElement.builder("这是正常长度的段落内容，足够长不会被合并到前面").build())
                .build();

        ParsedDocument result = merger.clean(doc);

        // 第一个元素前面没有东西，无法合并，保留
        assertEquals(2, result.elements().size());
    }

    @Test
    public void previousIsHeading_shortTextNotMerged() {
        ShortFragmentMerger merger = new ShortFragmentMerger(20);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(HeadingElement.builder(1, "标题").build())
                .addElement(TextElement.builder("短文字").build())
                .build();

        ParsedDocument result = merger.clean(doc);

        assertEquals(2, result.elements().size());
        assertEquals(ElementType.HEADING, result.elements().get(0).elementType());
        assertEquals(ElementType.TEXT, result.elements().get(1).elementType());
    }

    @Test
    public void nonTextElements_unaffected() {
        ShortFragmentMerger merger = new ShortFragmentMerger(20);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("正常段落内容，足够长。").build())
                .addElement(ImageElement.builder(new byte[]{1}, "image/png").build())
                .addElement(TextElement.builder("短句").build())
                .build();

        ParsedDocument result = merger.clean(doc);

        assertEquals(3, result.elements().size());
        // 短句前面是 ImageElement，不能合并，保留
        assertEquals("短句", textAt(result, 2));
    }

    @Test
    public void multipleShortFragments_mergedSequentially() {
        ShortFragmentMerger merger = new ShortFragmentMerger(50);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("第一段，这是一段足够长的文字内容，不会触发合并。").build())
                .addElement(TextElement.builder("短一").build())
                .addElement(TextElement.builder("短二").build())
                .addElement(TextElement.builder("短三").build())
                .build();

        ParsedDocument result = merger.clean(doc);

        // 短一合并到第一段，短二合并到更新后的第一段，短三合并到更新后的第一段
        assertEquals(1, result.elements().size());
        assertTrue(textAt(result, 0).contains("短一"));
        assertTrue(textAt(result, 0).contains("短二"));
        assertTrue(textAt(result, 0).contains("短三"));
    }

    @Test
    public void customThreshold() {
        ShortFragmentMerger merger = new ShortFragmentMerger(5);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("正常段落内容足够长").build())
                .addElement(TextElement.builder("短").build())
                .build();

        ParsedDocument result = merger.clean(doc);

        assertEquals(1, result.elements().size());
        assertEquals("正常段落内容足够长 短", textAt(result, 0));
    }

    private String textAt(ParsedDocument doc, int index) {
        return ((TextElement) doc.elements().get(index)).content();
    }
}
