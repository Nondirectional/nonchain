package com.non.chain.document.cleaner;

import com.non.chain.document.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重复段落去重清洗器。
 * <p>
 * 检测并移除重复出现的 TEXT 和 HEADING 元素（常见于 PDF 的页眉、页脚、水印文字）。
 * 去重基于标准化后的文本内容（去除多余空白后比较），首次出现保留，后续重复项移除。
 */
public class DuplicateRemover implements DocumentCleaner {

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        Set<String> seen = new HashSet<>();
        List<DocumentElement> deduplicated = new ArrayList<>();

        for (DocumentElement element : document.elements()) {
            String key = contentKey(element);
            if (key != null) {
                if (seen.add(key)) {
                    deduplicated.add(element);
                }
                // 重复项直接跳过
            } else {
                // 非 TEXT/HEADING 类型原样保留
                deduplicated.add(element);
            }
        }

        return ParsedDocument.builder(document.metadata())
                .elements(deduplicated)
                .build();
    }

    private String contentKey(DocumentElement element) {
        if (element instanceof TextElement) {
            return normalize(((TextElement) element).content());
        }
        if (element instanceof HeadingElement) {
            return normalize(((HeadingElement) element).content());
        }
        return null;
    }

    private String normalize(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
