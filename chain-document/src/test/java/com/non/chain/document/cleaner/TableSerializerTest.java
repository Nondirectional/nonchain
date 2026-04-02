package com.non.chain.document.cleaner;

import com.non.chain.document.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class TableSerializerTest {

    private final TableSerializer serializer = new TableSerializer();
    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.docx")
            .format("docx")
            .build();

    @Test
    public void tableWithHeaders_serializedToMarkdown() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("表格前文字").build())
                .addElement(TableElement.builder()
                        .addHeader("姓名").addHeader("年龄").addHeader("城市")
                        .addRow(java.util.Arrays.asList("张三", "25", "北京"))
                        .addRow(java.util.Arrays.asList("李四", "30", "上海"))
                        .build())
                .addElement(TextElement.builder("表格后文字").build())
                .build();

        ParsedDocument result = serializer.clean(doc);

        assertEquals(3, result.elements().size());
        assertEquals(ElementType.TEXT, result.elements().get(0).elementType());
        assertEquals(ElementType.TEXT, result.elements().get(1).elementType()); // TableElement 变为 TextElement
        assertEquals(ElementType.TEXT, result.elements().get(2).elementType());

        String tableText = ((TextElement) result.elements().get(1)).content();
        assertTrue(tableText.contains("| 姓名 | 年龄 | 城市 |"));
        assertTrue(tableText.contains("| --- | --- | --- |"));
        assertTrue(tableText.contains("| 张三 | 25 | 北京 |"));
        assertTrue(tableText.contains("| 李四 | 30 | 上海 |"));
    }

    @Test
    public void tableWithoutHeaders_firstRowAsHeader() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TableElement.builder()
                        .addRow(java.util.Arrays.asList("A", "B", "C"))
                        .addRow(java.util.Arrays.asList("1", "2", "3"))
                        .build())
                .build();

        ParsedDocument result = serializer.clean(doc);

        assertEquals(1, result.elements().size());
        String tableText = ((TextElement) result.elements().get(0)).content();
        assertTrue(tableText.contains("| A | B | C |"));
        assertTrue(tableText.contains("| --- | --- | --- |"));
        assertTrue(tableText.contains("| 1 | 2 | 3 |"));
    }

    @Test
    public void emptyTable_removed() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("前文").build())
                .addElement(TableElement.builder().build())
                .addElement(TextElement.builder("后文").build())
                .build();

        ParsedDocument result = serializer.clean(doc);

        assertEquals(2, result.elements().size());
    }

    @Test
    public void nonTableElements_unchanged() {
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("文本").build())
                .addElement(HeadingElement.builder(1, "标题").build())
                .addElement(ImageElement.builder(new byte[]{1}, "image/png").build())
                .build();

        ParsedDocument result = serializer.clean(doc);

        assertEquals(3, result.elements().size());
        assertEquals(ElementType.TEXT, result.elements().get(0).elementType());
        assertEquals(ElementType.HEADING, result.elements().get(1).elementType());
        assertEquals(ElementType.IMAGE, result.elements().get(2).elementType());
    }

    @Test
    public void positionAndMetadata_preserved() {
        DocumentPosition pos = DocumentPosition.builder().pageNumber(2).build();
        ParsedDocument doc = ParsedDocument.builder(metadata)
                .addElement(TableElement.builder()
                        .position(pos)
                        .addHeader("A").addHeader("B")
                        .addRow(java.util.Arrays.asList("1", "2"))
                        .build())
                .build();

        ParsedDocument result = serializer.clean(doc);

        TextElement serialized = (TextElement) result.elements().get(0);
        assertEquals(Integer.valueOf(2), serialized.position().pageNumber());
    }
}
