package com.non.chain.document.splitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.non.chain.document.DocumentElement;
import com.non.chain.document.ElementType;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;
import com.non.chain.document.HeadingElement;
import com.non.chain.knowledge.ContentMeasure;
import com.non.chain.knowledge.DocumentSplitter;
import com.non.chain.knowledge.TextChunk;
import com.non.chain.provider.LLM;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 基于 LLM 的文档切分器。
 * <p>
 * 混合架构：先按结构将文档元素分成若干片段（segment），对文本类片段调用 LLM 进行
 * 语义清洗和切分，对原子元素（TABLE、CODE_BLOCK、IMAGE）直接透传。
 * <p>
 * LLM 输出格式为 JSON 数组：{@code [{"content": "...", "title": "..."}]}。
 */
public class LlmDocumentSplitter implements DocumentSplitter {

    private static final int DEFAULT_SEGMENT_SIZE = 5000;
    private static final int DEFAULT_TARGET_CHUNK_SIZE = 500;
    private static final int MAX_RETRIES = 2;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个专业的文档切分助手。你的任务是将给定的文本切分为语义完整的片段，用于后续的向量检索（RAG）。\n" +
            "要求：\n" +
            "1. 每个 chunk 必须语义完整，不能截断句子或段落。\n" +
            "2. 每个 chunk 的内容应围绕一个主题，不要混合多个不相关的话题。\n" +
            "3. 清洗文本：去除无意义的空白、重复内容、格式噪音。\n" +
            "4. 为每个 chunk 提供一个简短的标题（title），概括该 chunk 的主要内容。\n" +
            "5. 目标每个 chunk 大约 TARGET_SIZE 个 token，但语义完整性优先于长度限制。\n" +
            "6. 严格按 JSON 数组格式输出，不要包含任何其他文字说明。\n" +
            "输出格式：\n" +
            "[{\"content\": \"chunk 内容\", \"title\": \"chunk 标题\"}]";

    private final LLM llm;
    private final ContentMeasure contentMeasure;
    private final int targetChunkSize;
    private final int segmentSize;
    private final String promptTemplate;
    private final ObjectMapper objectMapper;

    private LlmDocumentSplitter(LLM llm, ContentMeasure contentMeasure,
                                int targetChunkSize, int segmentSize,
                                String promptTemplate) {
        this.llm = llm;
        this.contentMeasure = contentMeasure;
        this.targetChunkSize = targetChunkSize;
        this.segmentSize = segmentSize;
        this.promptTemplate = promptTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<TextChunk> split(ParsedDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");
        List<DocumentElement> elements = document.elements();
        if (elements.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextChunk> result = new ArrayList<>();
        int chunkIndex = 0;

        // 第一步：将元素按 segmentSize 分组
        List<Segment> segments = buildSegments(elements);

        // 第二步：逐段处理
        for (Segment segment : segments) {
            if (segment.isAtomic()) {
                // 原子元素直接透传
                chunkIndex = emitAtomicElement(segment.singleElement(), result, chunkIndex);
            } else {
                // 文本片段：调用 LLM 切分
                String text = segment.textContent();
                if (text.isBlank()) {
                    continue;
                }
                List<LlmChunkResult> llmChunks = splitTextWithLlm(text);
                for (LlmChunkResult llmChunk : llmChunks) {
                    TextChunk.Builder builder = TextChunk.builder(
                            llmChunk.content().trim(), ElementType.TEXT
                    );
                    builder.putMetadata("chunkIndex", chunkIndex++);
                    if (llmChunk.title() != null && !llmChunk.title().isBlank()) {
                        builder.putMetadata("title", llmChunk.title());
                    }
                    result.add(builder.build());
                }
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 将文档元素按 segmentSize 分组。
     * 原子元素独立成组，文本/标题元素按度量值累积分组。
     */
    private List<Segment> buildSegments(List<DocumentElement> elements) {
        List<Segment> segments = new ArrayList<>();
        List<DocumentElement> currentGroup = new ArrayList<>();
        int currentSize = 0;

        for (DocumentElement element : elements) {
            ElementType type = element.elementType();

            if (isAtomic(type)) {
                // flush 当前文本组
                if (!currentGroup.isEmpty()) {
                    segments.add(new Segment(currentGroup));
                    currentGroup = new ArrayList<>();
                    currentSize = 0;
                }
                // 原子元素独立成组
                segments.add(new Segment(element));
                continue;
            }

            if (type == ElementType.PAGE_BREAK) {
                // PAGE_BREAK 作为天然切分边界
                if (!currentGroup.isEmpty()) {
                    segments.add(new Segment(currentGroup));
                    currentGroup = new ArrayList<>();
                    currentSize = 0;
                }
                continue;
            }

            // TEXT 或 HEADING
            String text = extractText(element);
            int textSize = contentMeasure.measure(text);

            if (currentSize > 0 && currentSize + textSize > segmentSize) {
                // 超出 segmentSize，flush 当前组
                segments.add(new Segment(currentGroup));
                currentGroup = new ArrayList<>();
                currentSize = 0;
            }

            currentGroup.add(element);
            currentSize += textSize;
        }

        // flush 剩余
        if (!currentGroup.isEmpty()) {
            segments.add(new Segment(currentGroup));
        }

        return segments;
    }

    /**
     * 调用 LLM 切分文本，带重试机制。
     */
    private List<LlmChunkResult> splitTextWithLlm(String text) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = "请将以下文本切分为语义完整的片段：\n\n" + text;

        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String response = llm.chat(systemPrompt, userPrompt).content();
                return parseLlmResponse(response);
            } catch (Exception e) {
                lastException = e;
            }
        }

        // 所有重试失败，使用 fallback：按字符切分
        return fallbackSplit(text);
    }

    /**
     * 解析 LLM 返回的 JSON 数组。
     */
    private List<LlmChunkResult> parseLlmResponse(String response) {
        String json = extractJsonArray(response);
        try {
            List<LlmChunkResult> results = objectMapper.readValue(
                    json, new TypeReference<List<LlmChunkResult>>() {}
            );
            if (results.isEmpty()) {
                throw new IllegalArgumentException("LLM 返回空数组");
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM 输出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 数组。
     * 处理 LLM 可能在 JSON 前后添加额外文字的情况。
     */
    private String extractJsonArray(String response) {
        String trimmed = response.trim();
        // 尝试找到 JSON 数组的起止位置
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        // 尝试找到 JSON 对象（可能是单个 chunk 被包在对象里）
        start = trimmed.indexOf('{');
        end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return "[" + trimmed.substring(start, end + 1) + "]";
        }
        throw new IllegalArgumentException("无法从 LLM 响应中提取 JSON");
    }

    /**
     * Fallback：当 LLM 调用失败时，使用简单的字符切分。
     */
    private List<LlmChunkResult> fallbackSplit(String text) {
        List<LlmChunkResult> results = new ArrayList<>();

        if (contentMeasure.measure(text) <= targetChunkSize) {
            results.add(new LlmChunkResult(text, null));
            return results;
        }

        // 使用 RecursiveCharacterSplitter 作为 fallback
        RecursiveCharacterSplitter fallback = RecursiveCharacterSplitter.builder()
                .chunkSize(targetChunkSize)
                .chunkOverlap(Math.min(targetChunkSize / 5, 100))
                .contentMeasure(contentMeasure)
                .build();

        ParsedDocument subDoc = ParsedDocument.fromText(text);
        for (TextChunk chunk : fallback.split(subDoc)) {
            results.add(new LlmChunkResult(chunk.content(), null));
        }

        return results;
    }

    private String buildSystemPrompt() {
        return promptTemplate.replace("TARGET_SIZE", String.valueOf(targetChunkSize));
    }

    private int emitAtomicElement(DocumentElement element, List<TextChunk> result, int chunkIndex) {
        TextChunk chunk = SplitterSupport.buildAtomicChunk(
                element, chunkIndex, null,
                targetChunkSize > 0 ? targetChunkSize : null,
                contentMeasure
        );
        if (chunk != null) {
            result.add(chunk);
            return chunkIndex + 1;
        }
        return chunkIndex;
    }

    private boolean isAtomic(ElementType type) {
        return type == ElementType.TABLE
                || type == ElementType.CODE_BLOCK
                || type == ElementType.IMAGE;
    }

    private String extractText(DocumentElement element) {
        switch (element.elementType()) {
            case TEXT:
                return ((TextElement) element).content();
            case HEADING:
                return ((HeadingElement) element).content();
            default:
                return "";
        }
    }

    /**
     * 分组单元，表示一组文本元素或单个原子元素。
     */
    private static class Segment {
        private final List<DocumentElement> elements;
        private final boolean atomic;

        Segment(List<DocumentElement> elements) {
            this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
            this.atomic = false;
        }

        Segment(DocumentElement singleElement) {
            this.elements = Collections.singletonList(singleElement);
            this.atomic = true;
        }

        boolean isAtomic() {
            return atomic;
        }

        DocumentElement singleElement() {
            return elements.get(0);
        }

        String textContent() {
            StringBuilder sb = new StringBuilder();
            for (DocumentElement element : elements) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                switch (element.elementType()) {
                    case TEXT:
                        sb.append(((TextElement) element).content());
                        break;
                    case HEADING:
                        sb.append(((HeadingElement) element).content());
                        break;
                    default:
                        break;
                }
            }
            return sb.toString();
        }
    }

    public static Builder builder(LLM llm) {
        return new Builder(llm);
    }

    public static class Builder {
        private final LLM llm;
        private ContentMeasure contentMeasure;
        private int targetChunkSize = DEFAULT_TARGET_CHUNK_SIZE;
        private int segmentSize = DEFAULT_SEGMENT_SIZE;
        private String promptTemplate;

        private Builder(LLM llm) {
            Objects.requireNonNull(llm, "llm 不能为 null");
            this.llm = llm;
        }

        public Builder contentMeasure(ContentMeasure contentMeasure) {
            this.contentMeasure = contentMeasure;
            return this;
        }

        public Builder targetChunkSize(int targetChunkSize) {
            this.targetChunkSize = targetChunkSize;
            return this;
        }

        public Builder segmentSize(int segmentSize) {
            this.segmentSize = segmentSize;
            return this;
        }

        public Builder promptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public LlmDocumentSplitter build() {
            if (targetChunkSize <= 0) {
                throw new IllegalArgumentException("targetChunkSize 必须大于 0");
            }
            if (segmentSize <= 0) {
                throw new IllegalArgumentException("segmentSize 必须大于 0");
            }
            if (segmentSize < targetChunkSize) {
                throw new IllegalArgumentException("segmentSize 必须大于等于 targetChunkSize");
            }
            if (contentMeasure == null) {
                contentMeasure = new CharacterMeasure();
            }
            if (promptTemplate == null) {
                promptTemplate = DEFAULT_SYSTEM_PROMPT;
            }
            return new LlmDocumentSplitter(
                    llm, contentMeasure, targetChunkSize, segmentSize, promptTemplate
            );
        }
    }
}
