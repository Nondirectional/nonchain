package com.non.chain.document;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParsedDocumentTest {

    @Test
    public void fromText_blankText_returnsEmptyDocument() {
        ParsedDocument document = ParsedDocument.fromText("   \n  ");

        assertEquals("text", document.metadata().fileName());
        assertEquals("txt", document.metadata().format());
        assertTrue(document.elements().isEmpty());
    }

    @Test
    public void fromText_nonBlankText_createsSingleTextElement() {
        ParsedDocument document = ParsedDocument.fromText("hello");

        assertEquals(1, document.elements().size());
        assertEquals(ElementType.TEXT, document.elements().get(0).elementType());
        assertEquals("hello", ((TextElement) document.elements().get(0)).content());
    }

    @Test(expected = NullPointerException.class)
    public void fromText_null_throwsException() {
        ParsedDocument.fromText(null);
    }
}
