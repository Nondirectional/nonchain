package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class BoilerplateRemoverTest {

    private final BoilerplateRemover remover = new BoilerplateRemover();
    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.pdf")
            .format("pdf")
            .build();

    @Test
    public void pageNumberChinese_removed() {
        ParsedDocument doc = docWithText("第 1 页");
        ParsedDocument result = remover.clean(doc);

        assertTrue(result.elements().isEmpty());
    }

    @Test
    public void pageNumberEnglish_removed() {
        ParsedDocument doc = docWithText("Page 5 of 20");
        ParsedDocument result = remover.clean(doc);

        assertTrue(result.elements().isEmpty());
    }

    @Test
    public void pageSlashFormat_removed() {
        ParsedDocument doc = docWithText("3 / 10");
        ParsedDocument result = remover.clean(doc);

        assertTrue(result.elements().isEmpty());
    }

    @Test
    public void copyright_removed() {
        ParsedDocument doc = docWithText("Copyright 2024 ACME Corp. All rights reserved.");
        ParsedDocument result = remover.clean(doc);

        assertTrue(result.elements().isEmpty());
    }

    @Test
    public void copyrightSymbol_removed() {
        ParsedDocument doc = docWithText("© 2024 ACME Inc.");
        ParsedDocument result = remover.clean(doc);

        assertTrue(result.elements().isEmpty());
    }

    @Test
    public void confidential_removed() {
        ParsedDocument doc = docWithText("This document is Confidential");
        ParsedDocument result = remover.clean(doc);

        assertTrue(result.elements().isEmpty());
    }

    @Test
    public void normalText_kept() {
        ParsedDocument doc = docWithText("这是一段正常的正文内容");
        ParsedDocument result = remover.clean(doc);

        assertEquals(1, result.elements().size());
        assertEquals("这是一段正常的正文内容", textAt(result, 0));
    }

    @Test
    public void headingBoilerplate_removed() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(HeadingElement.builder(1, "Draft").build())
                .addElement(TextElement.builder("正常内容").build())
                .build();
        ParsedDocument result = remover.clean(doc);

        assertEquals(1, result.elements().size());
        assertEquals(ElementType.TEXT, result.elements().get(0).elementType());
    }

    @Test
    public void customPattern_removed() {
        Pattern[] custom = {Pattern.compile("^TODO:")};
        BoilerplateRemover customRemover = new BoilerplateRemover(custom);

        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("TODO: implement this later").build())
                .addElement(TextElement.builder("正常内容").build())
                .build();
        ParsedDocument result = customRemover.clean(doc);

        assertEquals(1, result.elements().size());
        assertEquals("正常内容", textAt(result, 0));
    }

    @Test
    public void nonTextElements_preserved() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("第 1 页").build())
                .addElement(ImageElement.builder(new byte[]{1}, "image/png").build())
                .addElement(TextElement.builder("正常内容").build())
                .build();
        ParsedDocument result = remover.clean(doc);

        assertEquals(2, result.elements().size());
        assertEquals(ElementType.IMAGE, result.elements().get(0).elementType());
        assertEquals(ElementType.TEXT, result.elements().get(1).elementType());
    }

    private ParsedDocument docWithText(String text) {
        return ParsedDocument.builder(metadata)
                .addElement(TextElement.builder(text).build())
                .build();
    }

    private String textAt(ParsedDocument doc, int index) {
        return ((TextElement) doc.elements().get(index)).content();
    }
}
