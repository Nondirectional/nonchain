package com.non.chain.document.cleaner;

import com.non.chain.document.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 短文本片段合并清洗器。
 * <p>
 * 将长度低于阈值的 TextElement 合并到前一个相邻的 TextElement 中。
 * 适用于 PDF 提取时产生的不自然断行、换行导致的碎片段落。
 * <p>
 * 默认阈值为 50 个字符，可通过构造函数自定义。
 */
public class ShortFragmentMerger implements DocumentCleaner {

    private static final int DEFAULT_THRESHOLD = 50;

    private final int threshold;

    public ShortFragmentMerger() {
        this(DEFAULT_THRESHOLD);
    }

    public ShortFragmentMerger(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold 不能小于 1");
        }
        this.threshold = threshold;
    }

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        List<DocumentElement> result = new ArrayList<>();

        for (DocumentElement element : document.elements()) {
            if (isShortText(element) && !result.isEmpty()) {
                mergeIntoLast(result, (TextElement) element);
            } else {
                result.add(element);
            }
        }

        return ParsedDocument.builder(document.metadata())
                .elements(result)
                .build();
    }

    private boolean isShortText(DocumentElement element) {
        return element instanceof TextElement
                && ((TextElement) element).content().length() < threshold;
    }

    private void mergeIntoLast(List<DocumentElement> result, TextElement shortElement) {
        int lastIndex = result.size() - 1;
        DocumentElement last = result.get(lastIndex);

        if (last instanceof TextElement) {
            TextElement lastText = (TextElement) last;
            String merged = lastText.content() + " " + shortElement.content();
            result.set(lastIndex, TextElement.builder(merged)
                    .position(lastText.position())
                    .metadata(lastText.metadata())
                    .build());
        } else {
            // 前一个不是 TextElement，不能合并，保留原样
            result.add(shortElement);
        }
    }
}
