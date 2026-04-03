package com.non.chain.document.splitter;

import com.non.chain.document.*;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.ContentMeasure;
import com.non.chain.knowledge.DocumentSplitter;
import com.non.chain.knowledge.TextChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 语义切分器。
 * <p>
 * 基于 {@link EmbeddingModel} 计算相邻文本段的语义相似度，在语义断点处切分。
 */
public class SemanticSplitter implements DocumentSplitter {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。？！.?!\\n])");

    private final EmbeddingModel embeddingModel;
    private final int bufferSize;
    private final double breakpointThreshold;
    private final ContentMeasure contentMeasure;
    private final int maxChunkSize;

    private SemanticSplitter(EmbeddingModel embeddingModel, int bufferSize,
                             double breakpointThreshold, ContentMeasure contentMeasure,
                             int maxChunkSize) {
        this.embeddingModel = embeddingModel;
        this.bufferSize = bufferSize;
        this.breakpointThreshold = breakpointThreshold;
        this.contentMeasure = contentMeasure;
        this.maxChunkSize = maxChunkSize;
    }

    @Override
    public List<TextChunk> split(ParsedDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");
        List<DocumentElement> elements = document.elements();
        if (elements.isEmpty()) {
            return Collections.emptyList();
        }

        // 第一步：提取文本句子 + 标记原子元素位置
        List<SegmentEntry> entries = new ArrayList<>();
        for (DocumentElement element : elements) {
            ElementType type = element.elementType();
            switch (type) {
                case TEXT:
                    splitToSentences(((TextElement) element).content(), entries);
                    break;
                case HEADING:
                    splitToSentences(((HeadingElement) element).content(), entries);
                    break;
                case TABLE:
                case CODE_BLOCK:
                case IMAGE:
                    entries.add(new SegmentEntry(element, true));
                    break;
                case PAGE_BREAK:
                    entries.add(new SegmentEntry(null, false)); // 天然断点标记
                    break;
                default:
                    break;
            }
        }

        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        // 收集纯文本句子（非原子、非断点）
        List<Integer> sentenceIndices = new ArrayList<>();
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            SegmentEntry entry = entries.get(i);
            if (!entry.isAtomic && entry.element != null) {
                // 文本句子（存为临时 TextElement）
                sentenceIndices.add(i);
                sentences.add(entry.text);
            }
        }

        // 第二步：计算语义相似度并确定断点
        boolean[] isBreakpoint = new boolean[entries.size()];

        // 标记 PAGE_BREAK 和原子元素为断点
        for (int i = 0; i < entries.size(); i++) {
            SegmentEntry entry = entries.get(i);
            if (entry.isAtomic || entry.element == null) {
                isBreakpoint[i] = true;
            }
        }

        // 对句子序列计算语义断点
        if (sentences.size() > 1) {
            List<String> groups = buildGroups(sentences);
            if (groups.size() > 1) {
                List<float[]> embeddings = embeddingModel.embedAll(groups);
                for (int i = 0; i < embeddings.size() - 1; i++) {
                    double similarity = cosineSimilarity(embeddings.get(i), embeddings.get(i + 1));
                    if (similarity < breakpointThreshold) {
                        // 在第 i+1 个 group 对应的句子位置设断点
                        int sentIdx = Math.min((i + 1) * bufferSize, sentenceIndices.size() - 1);
                        isBreakpoint[sentenceIndices.get(sentIdx)] = true;
                    }
                }
            }
        }

        // 第三步：按断点组装 chunks
        List<TextChunk> result = new ArrayList<>();
        int chunkIndex = 0;
        StringBuilder currentText = new StringBuilder();

        for (int i = 0; i < entries.size(); i++) {
            SegmentEntry entry = entries.get(i);

            if (entry.isAtomic) {
                // flush 文本
                chunkIndex = flushText(currentText, result, chunkIndex);
                // 输出原子元素
                chunkIndex = emitAtomicElement(entry.element, result, chunkIndex);
                continue;
            }

            if (entry.element == null) {
                // PAGE_BREAK — flush
                chunkIndex = flushText(currentText, result, chunkIndex);
                continue;
            }

            // 文本句子
            if (isBreakpoint[i] && currentText.length() > 0) {
                chunkIndex = flushText(currentText, result, chunkIndex);
            }
            currentText.append(entry.text);
        }

        flushText(currentText, result, chunkIndex);

        return Collections.unmodifiableList(result);
    }

    private void splitToSentences(String text, List<SegmentEntry> entries) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] parts = SENTENCE_BOUNDARY.split(text);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                entries.add(SegmentEntry.sentence(trimmed));
            }
        }
    }

    private List<String> buildGroups(List<String> sentences) {
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i += bufferSize) {
            StringBuilder group = new StringBuilder();
            for (int j = i; j < Math.min(i + bufferSize, sentences.size()); j++) {
                if (group.length() > 0) {
                    group.append(" ");
                }
                group.append(sentences.get(j));
            }
            groups.add(group.toString());
        }
        return groups;
    }

    private int flushText(StringBuilder buffer, List<TextChunk> result, int chunkIndex) {
        if (buffer.length() == 0) {
            return chunkIndex;
        }

        String text = buffer.toString().trim();
        buffer.setLength(0);

        if (text.isEmpty()) {
            return chunkIndex;
        }

        // 如果设置了 maxChunkSize 且文本超出，则强制拆分
        if (maxChunkSize > 0 && contentMeasure.measure(text) > maxChunkSize) {
            RecursiveCharacterSplitter fallback = RecursiveCharacterSplitter.builder()
                    .chunkSize(maxChunkSize)
                    .chunkOverlap(0)
                    .contentMeasure(contentMeasure)
                    .build();
            ParsedDocument subDoc = ParsedDocument.fromText(text);
            for (TextChunk chunk : fallback.split(subDoc)) {
                result.add(TextChunk.builder(chunk.content(), ElementType.TEXT)
                        .putMetadata("chunkIndex", chunkIndex++)
                        .build());
            }
            return chunkIndex;
        }

        result.add(TextChunk.builder(text, ElementType.TEXT)
                .putMetadata("chunkIndex", chunkIndex)
                .build());
        return chunkIndex + 1;
    }

    private int emitAtomicElement(DocumentElement element, List<TextChunk> result, int chunkIndex) {
        TextChunk chunk = SplitterSupport.buildAtomicChunk(
                element,
                chunkIndex,
                null,
                maxChunkSize > 0 ? maxChunkSize : null,
                contentMeasure
        );
        if (chunk != null) {
            result.add(chunk);
            return chunkIndex + 1;
        }
        return chunkIndex;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    private static class SegmentEntry {
        final DocumentElement element;
        final boolean isAtomic;
        final String text; // 仅 sentence 有值

        SegmentEntry(DocumentElement element, boolean isAtomic) {
            this.element = element;
            this.isAtomic = isAtomic;
            this.text = null;
        }

        private SegmentEntry(String text) {
            this.element = TextElement.builder(text).build();
            this.isAtomic = false;
            this.text = text;
        }

        static SegmentEntry sentence(String text) {
            return new SegmentEntry(text);
        }
    }

    public static Builder builder(EmbeddingModel embeddingModel) {
        return new Builder(embeddingModel);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private int bufferSize = 1;
        private double breakpointThreshold = 0.5;
        private ContentMeasure contentMeasure;
        private int maxChunkSize = 0;

        private Builder(EmbeddingModel embeddingModel) {
            Objects.requireNonNull(embeddingModel, "embeddingModel 不能为 null");
            this.embeddingModel = embeddingModel;
        }

        public Builder bufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public Builder breakpointThreshold(double breakpointThreshold) {
            this.breakpointThreshold = breakpointThreshold;
            return this;
        }

        public Builder contentMeasure(ContentMeasure contentMeasure) {
            this.contentMeasure = contentMeasure;
            return this;
        }

        public Builder maxChunkSize(int maxChunkSize) {
            this.maxChunkSize = maxChunkSize;
            return this;
        }

        public SemanticSplitter build() {
            if (bufferSize < 1) {
                throw new IllegalArgumentException("bufferSize 必须大于 0");
            }
            if (breakpointThreshold < 0 || breakpointThreshold > 1) {
                throw new IllegalArgumentException("breakpointThreshold 必须在 0-1 之间");
            }
            if (maxChunkSize < 0) {
                throw new IllegalArgumentException("maxChunkSize 不能小于 0");
            }
            if (contentMeasure == null) {
                contentMeasure = new CharacterMeasure();
            }
            return new SemanticSplitter(embeddingModel, bufferSize, breakpointThreshold,
                    contentMeasure, maxChunkSize);
        }
    }
}
