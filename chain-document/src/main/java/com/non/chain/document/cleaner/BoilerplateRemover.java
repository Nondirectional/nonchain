package com.non.chain.document.cleaner;

import com.non.chain.document.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 样板内容移除清洗器。
 * <p>
 * 通过正则匹配移除常见的样板文本，包括：
 * <ul>
 *   <li>页码（如 "第 1 页", "Page 1", "1 / 10"）</li>
 *   <li>版权声明（如 "Copyright", "©", "All rights reserved"）</li>
 *   <li>机密标记（如 "Confidential", "内部资料"）</li>
 * </ul>
 * <p>
 * 支持通过构造函数传入自定义正则模式。
 */
public class BoilerplateRemover implements DocumentCleaner {

    private static final Pattern[] DEFAULT_PATTERNS = {
            // 页码模式
            Pattern.compile("^第\\s*\\d+\\s*页$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^page\\s*\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\d+\\s*/\\s*\\d+$"),
            Pattern.compile("^\\d+\\s* of \\s*\\d+$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^-\\s*\\d+\\s*-$"),
            // 版权声明
            Pattern.compile("copyright\\s+\\d{4}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("©\\s*\\d{4}"),
            Pattern.compile("all\\s+rights\\s+reserved", Pattern.CASE_INSENSITIVE),
            // 机密标记
            Pattern.compile("confidential", Pattern.CASE_INSENSITIVE),
            Pattern.compile("内部资料"),
            Pattern.compile("严禁传播"),
            // 常见样板
            Pattern.compile("^draft$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^sample$", Pattern.CASE_INSENSITIVE),
    };

    private final Pattern[] patterns;

    public BoilerplateRemover() {
        this(DEFAULT_PATTERNS);
    }

    public BoilerplateRemover(Pattern[] patterns) {
        this.patterns = patterns;
    }

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        List<DocumentElement> cleaned = new ArrayList<>();

        for (DocumentElement element : document.elements()) {
            if (!isBoilerplate(element)) {
                cleaned.add(element);
            }
        }

        return ParsedDocument.builder(document.metadata())
                .elements(cleaned)
                .build();
    }

    private boolean isBoilerplate(DocumentElement element) {
        String content = extractTextContent(element);
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        for (Pattern pattern : patterns) {
            if (pattern.matcher(trimmed).find()) {
                return true;
            }
        }
        return false;
    }

    private String extractTextContent(DocumentElement element) {
        if (element instanceof TextElement) {
            return ((TextElement) element).content();
        }
        if (element instanceof HeadingElement) {
            return ((HeadingElement) element).content();
        }
        return null;
    }
}
