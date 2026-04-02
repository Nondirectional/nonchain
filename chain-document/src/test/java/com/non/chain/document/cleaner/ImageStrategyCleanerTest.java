package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class ImageStrategyCleanerTest {

    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.pdf")
            .format("pdf")
            .build();

    @Test
    public void keepStrategy_imagesPreserved() {
        ImageStrategyCleaner cleaner = new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.KEEP);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("文字内容").build())
                .addElement(ImageElement.builder(new byte[]{1, 2}, "image/png").fileName("img.png").build())
                .addElement(TextElement.builder("更多文字").build())
                .build();

        ParsedDocument result = cleaner.clean(doc);

        assertEquals(3, result.elements().size());
        assertEquals(ElementType.IMAGE, result.elements().get(1).elementType());
    }

    @Test
    public void keepStrategyDefault_imagesPreserved() {
        ImageStrategyCleaner cleaner = new ImageStrategyCleaner();

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(ImageElement.builder(new byte[]{1}, "image/png").build())
                .build();

        ParsedDocument result = cleaner.clean(doc);

        assertEquals(1, result.elements().size());
    }

    @Test
    public void removeStrategy_imagesRemoved() {
        ImageStrategyCleaner cleaner = new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.REMOVE);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("文字内容").build())
                .addElement(ImageElement.builder(new byte[]{1, 2}, "image/png").fileName("img.png").build())
                .addElement(ImageElement.builder(new byte[]{3, 4}, "image/jpeg").fileName("img2.jpg").build())
                .addElement(TextElement.builder("更多文字").build())
                .build();

        ParsedDocument result = cleaner.clean(doc);

        assertEquals(2, result.elements().size());
        assertEquals(ElementType.TEXT, result.elements().get(0).elementType());
        assertEquals(ElementType.TEXT, result.elements().get(1).elementType());
    }

    @Test
    public void removeStrategy_noImages_unchanged() {
        ImageStrategyCleaner cleaner = new ImageStrategyCleaner(ImageStrategyCleaner.ImageStrategy.REMOVE);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("文字A").build())
                .addElement(HeadingElement.builder(1, "标题").build())
                .addElement(TextElement.builder("文字B").build())
                .build();

        ParsedDocument result = cleaner.clean(doc);

        assertEquals(3, result.elements().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullStrategy_throwsException() {
        new ImageStrategyCleaner(null);
    }
}
