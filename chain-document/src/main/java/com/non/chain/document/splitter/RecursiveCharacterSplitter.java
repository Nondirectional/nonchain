package com.non.chain.document.splitter;

import com.non.chain.document.*;
import com.non.chain.knowledge.ContentMeasure;
import com.non.chain.knowledge.DocumentSplitter;
import com.non.chain.knowledge.TextChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 递归字符切分器。
 * <p>
 * 按分隔符层级递归切分 TEXT 元素内容，遇到原子元素（TABLE / CODE_BLOCK / IMAGE）保持完整。
 */
public class RecursiveCharacterSplitter implements DocumentSplitter {

    private static final List<String> DEFAULT_SEPARATORS = List.of(
            "\n\n", "\n", "。", "？", "！", "；", ".", "?", "!", ";", " ", ""
    );

    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> separators;
    private final boolean keepSeparator;
    private final ContentMeasure contentMeasure;

    private RecursiveCharacterSplitter(int chunkSize, int chunkOverlap, List<String> separators,
                                       boolean keepSeparator, ContentMeasure contentMeasure) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = separators;
        this.keepSeparator = keepSeparator;
        this.contentMeasure = contentMeasure;
    }

    @Override
    public List<TextChunk> split(ParsedDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");
        List<DocumentElement> elements = document.elements();
        if (elements.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextChunk> result = new ArrayList<>();
        StringBuilder textBuffer = new StringBuilder();
        int chunkIndex = 0;

        for (DocumentElement element : elements) {
            ElementType type = element.elementType();

            switch (type) {
                case TABLE:
                case CODE_BLOCK:
                case IMAGE:
                    // flush 文本缓冲区
                    chunkIndex = flushTextBuffer(textBuffer, result, chunkIndex);
                    // 原子元素独立输出
                    chunkIndex = emitAtomicElement(element, result, chunkIndex);
                    break;

                case TEXT:
                    appendToBuffer(textBuffer, ((TextElement) element).content());
                    break;

                case HEADING:
                    appendToBuffer(textBuffer, ((HeadingElement) element).content());
                    break;

                case PAGE_BREAK:
                    // 天然切分边界
                    chunkIndex = flushTextBuffer(textBuffer, result, chunkIndex);
                    break;

                default:
                    break;
            }
        }

        // flush 剩余文本
        chunkIndex = flushTextBuffer(textBuffer, result, chunkIndex);

        return Collections.unmodifiableList(result);
    }

    private int flushTextBuffer(StringBuilder buffer, List<TextChunk> result, int chunkIndex) {
        if (buffer.length() == 0) {
            return chunkIndex;
        }

        String text = buffer.toString().trim();
        buffer.setLength(0);

        if (text.isEmpty()) {
            return chunkIndex;
        }

        List<String> chunks = recursiveSplit(text, 0);
        chunks = applyOverlap(chunks);

        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                result.add(TextChunk.builder(trimmed, ElementType.TEXT)
                        .putMetadata("chunkIndex", chunkIndex++)
                        .build());
            }
        }

        return chunkIndex;
    }

    private int emitAtomicElement(DocumentElement element, List<TextChunk> result, int chunkIndex) {
        TextChunk chunk = SplitterSupport.buildAtomicChunk(
                element,
                chunkIndex,
                null,
                chunkSize,
                contentMeasure
        );
        if (chunk != null) {
            result.add(chunk);
            return chunkIndex + 1;
        }
        return chunkIndex;
    }

    private List<String> recursiveSplit(String text, int depth) {
        if (contentMeasure.measure(text) <= chunkSize) {
            return new ArrayList<>(List.of(text));
        }

        if (depth >= separators.size()) {
            // 无更细分隔符，直接返回
            return new ArrayList<>(List.of(text));
        }

        String separator = separators.get(depth);
        List<String> segments = splitBySeparator(text, separator);

        if (segments.size() <= 1) {
            // 当前分隔符无法切分，尝试下一级
            return recursiveSplit(text, depth + 1);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }

            String candidate;
            if (currentChunk.length() == 0) {
                candidate = segment;
            } else {
                candidate = currentChunk.toString() + segment;
            }

            if (contentMeasure.measure(candidate) <= chunkSize) {
                if (currentChunk.length() == 0) {
                    currentChunk.append(segment);
                } else {
                    currentChunk.append(segment);
                }
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }

                if (contentMeasure.measure(segment) > chunkSize && depth + 1 < separators.size()) {
                    chunks.addAll(recursiveSplit(segment, depth + 1));
                } else {
                    currentChunk.append(segment);
                }
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    private List<String> splitBySeparator(String text, String separator) {
        if (separator.isEmpty()) {
            // 逐字符切分
            List<String> chars = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                chars.add(String.valueOf(text.charAt(i)));
            }
            return chars;
        }

        if (!keepSeparator) {
            return new ArrayList<>(Arrays.asList(text.split(java.util.regex.Pattern.quote(separator), -1)));
        }

        // 保留分隔符：分隔符附加到前一段末尾
        List<String> parts = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf(separator, start)) != -1) {
            parts.add(text.substring(start, idx + separator.length()));
            start = idx + separator.length();
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    private List<String> applyOverlap(List<String> chunks) {
        if (chunkOverlap <= 0 || chunks.size() <= 1) {
            return chunks;
        }

        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prevChunk = chunks.get(i - 1);
            String currentChunk = chunks.get(i);

            // 从前一个 chunk 末尾取 overlap 量的内容
            String overlapText = extractOverlap(prevChunk);
            if (!overlapText.isEmpty()) {
                String merged = overlapText + currentChunk;
                // 如果合并后超出 chunkSize，则不加 overlap
                if (contentMeasure.measure(merged) <= chunkSize) {
                    result.add(merged);
                } else {
                    result.add(currentChunk);
                }
            } else {
                result.add(currentChunk);
            }
        }

        return result;
    }

    private String extractOverlap(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int totalMeasure = contentMeasure.measure(text);
        if (totalMeasure <= chunkOverlap) {
            return text;
        }

        // 从末尾逐步截取，直到度量值 <= chunkOverlap
        for (int i = text.length() - 1; i >= 0; i--) {
            String suffix = text.substring(i);
            if (contentMeasure.measure(suffix) >= chunkOverlap) {
                return suffix;
            }
        }
        return text;
    }

    private void appendToBuffer(StringBuilder buffer, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) != '\n') {
            buffer.append('\n');
        }
        buffer.append(content);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int chunkSize = 1000;
        private int chunkOverlap = 200;
        private List<String> separators;
        private boolean keepSeparator = true;
        private ContentMeasure contentMeasure;

        private Builder() {
        }

        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder chunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
            return this;
        }

        public Builder separators(List<String> separators) {
            this.separators = separators;
            return this;
        }

        public Builder keepSeparator(boolean keepSeparator) {
            this.keepSeparator = keepSeparator;
            return this;
        }

        public Builder contentMeasure(ContentMeasure contentMeasure) {
            this.contentMeasure = contentMeasure;
            return this;
        }

        public RecursiveCharacterSplitter build() {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("chunkSize 必须大于 0");
            }
            if (chunkOverlap < 0) {
                throw new IllegalArgumentException("chunkOverlap 不能小于 0");
            }
            if (chunkOverlap >= chunkSize) {
                throw new IllegalArgumentException("chunkOverlap 必须小于 chunkSize");
            }
            if (separators == null) {
                separators = DEFAULT_SEPARATORS;
            }
            if (contentMeasure == null) {
                contentMeasure = new CharacterMeasure();
            }
            return new RecursiveCharacterSplitter(
                    chunkSize, chunkOverlap,
                    Collections.unmodifiableList(new ArrayList<>(separators)),
                    keepSeparator, contentMeasure
            );
        }
    }
}
