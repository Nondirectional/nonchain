package com.non.chain.document.cleaner;

import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 对 TEXT 和 HEADING 元素的文本内容做变换的 Cleaner 基类。
 * <p>
 * 子类只需实现 {@link #transformText(String)} 方法。
 * 其他类型元素（IMAGE, TABLE, CODE_BLOCK 等）原样传递。
 * <p>
 * 变换后内容为空的元素会被过滤掉。
 */
abstract class TextContentCleaner implements DocumentCleaner {

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        List<DocumentElement> cleaned = new ArrayList<>();
        for (DocumentElement element : document.elements()) {
            DocumentElement result = processElement(element);
            if (result != null) {
                cleaned.add(result);
            }
        }
        return ParsedDocument.builder(document.metadata())
                .elements(cleaned)
                .build();
    }

    private DocumentElement processElement(DocumentElement element) {
        if (element instanceof TextElement) {
            return transformTextElement((TextElement) element);
        }
        if (element instanceof HeadingElement) {
            return transformHeadingElement((HeadingElement) element);
        }
        return element;
    }

    private DocumentElement transformTextElement(TextElement element) {
        String transformed = transformText(element.content());
        if (transformed == null || transformed.isBlank()) {
            return null;
        }
        if (transformed.equals(element.content())) {
            return element;
        }
        return TextElement.builder(transformed)
                .position(element.position())
                .metadata(element.metadata())
                .build();
    }

    private DocumentElement transformHeadingElement(HeadingElement element) {
        String transformed = transformText(element.content());
        if (transformed == null || transformed.isBlank()) {
            return null;
        }
        if (transformed.equals(element.content())) {
            return element;
        }
        return HeadingElement.builder(element.level(), transformed)
                .position(element.position())
                .metadata(element.metadata())
                .build();
    }

    /**
     * 对文本内容执行变换。
     *
     * @param text 原始文本
     * @return 变换后的文本，返回 null 或空白字符串表示该元素应被移除
     */
    protected abstract String transformText(String text);
}
