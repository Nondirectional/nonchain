package com.non.chain.document.splitter;

import com.non.chain.document.*;
import com.non.chain.knowledge.DocumentSplitter;
import com.non.chain.knowledge.TextChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * 标题/结构层级切分器。
 * <p>
 * 基于 {@link HeadingElement} 按文档结构层级拆分，维护标题路径栈。
 */
public class HeaderDocumentSplitter implements DocumentSplitter {

    private final List<Integer> headersToSplitOn;
    private final boolean includeHeadingInContent;

    public HeaderDocumentSplitter(List<Integer> headersToSplitOn) {
        this(headersToSplitOn, true);
    }

    public HeaderDocumentSplitter(List<Integer> headersToSplitOn, boolean includeHeadingInContent) {
        Objects.requireNonNull(headersToSplitOn, "headersToSplitOn 不能为 null");
        if (headersToSplitOn.isEmpty()) {
            throw new IllegalArgumentException("headersToSplitOn 不能为空");
        }
        this.headersToSplitOn = Collections.unmodifiableList(new ArrayList<>(headersToSplitOn));
        this.includeHeadingInContent = includeHeadingInContent;
    }

    @Override
    public List<TextChunk> split(ParsedDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");
        List<DocumentElement> elements = document.elements();
        if (elements.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextChunk> result = new ArrayList<>();
        List<DocumentElement> currentSection = new ArrayList<>();
        LinkedList<HeadingEntry> headingStack = new LinkedList<>();
        int chunkIndex = 0;

        for (DocumentElement element : elements) {
            if (element.elementType() == ElementType.HEADING) {
                HeadingElement heading = (HeadingElement) element;
                int level = heading.level();

                if (headersToSplitOn.contains(level)) {
                    // flush 当前 section
                    chunkIndex = flushSection(currentSection, headingStack, result, chunkIndex);
                    currentSection.clear();

                    // 更新 headingStack：弹出 >= 当前 level 的条目
                    while (!headingStack.isEmpty() && headingStack.peek().level >= level) {
                        headingStack.pop();
                    }
                    headingStack.push(new HeadingEntry(level, heading.content()));

                    // 如果 includeHeadingInContent，将 heading 加入新 section
                    if (includeHeadingInContent) {
                        currentSection.add(element);
                    }
                } else {
                    // 非切分标题，归入 currentSection
                    currentSection.add(element);
                }
            } else {
                // 非 HEADING 元素均归入 currentSection（包括 PAGE_BREAK）
                currentSection.add(element);
            }
        }

        // flush 最后一个 section
        chunkIndex = flushSection(currentSection, headingStack, result, chunkIndex);

        return Collections.unmodifiableList(result);
    }

    private int flushSection(List<DocumentElement> section, LinkedList<HeadingEntry> headingStack,
                             List<TextChunk> result, int chunkIndex) {
        if (section.isEmpty()) {
            return chunkIndex;
        }

        // 构建当前 headingPath（从栈底到栈顶，即从高层到低层）
        List<String> orderedPath = new ArrayList<>();
        for (int i = headingStack.size() - 1; i >= 0; i--) {
            orderedPath.add(headingStack.get(i).content);
        }

        // 当前 section 的标题信息
        String heading = headingStack.isEmpty() ? null : headingStack.peek().content;
        Integer headingLevel = headingStack.isEmpty() ? null : headingStack.peek().level;

        StringBuilder textBuffer = new StringBuilder();

        for (DocumentElement element : section) {
            ElementType type = element.elementType();

            switch (type) {
                case TEXT:
                    textBuffer.append(((TextElement) element).content()).append("\n");
                    break;

                case HEADING:
                    textBuffer.append(((HeadingElement) element).content()).append("\n");
                    break;

                case TABLE:
                case CODE_BLOCK:
                case IMAGE:
                    // flush 文本缓冲区
                    chunkIndex = flushTextBuffer(textBuffer, heading, headingLevel, orderedPath, result, chunkIndex);
                    // 原子元素独立输出，携带 heading metadata
                    chunkIndex = emitAtomicElement(element, heading, headingLevel, orderedPath, result, chunkIndex);
                    break;

                case PAGE_BREAK:
                    // PAGE_BREAK 在 HeaderSplitter 中不作为切分边界
                    break;

                default:
                    break;
            }
        }

        // flush 剩余文本
        chunkIndex = flushTextBuffer(textBuffer, heading, headingLevel, orderedPath, result, chunkIndex);

        return chunkIndex;
    }

    private int flushTextBuffer(StringBuilder buffer, String heading, Integer headingLevel,
                                List<String> headingPath, List<TextChunk> result, int chunkIndex) {
        if (buffer.length() == 0) {
            return chunkIndex;
        }

        String text = buffer.toString().trim();
        buffer.setLength(0);

        if (text.isEmpty()) {
            return chunkIndex;
        }

        TextChunk.Builder builder = TextChunk.builder(text, ElementType.TEXT)
                .putMetadata("chunkIndex", chunkIndex);

        applyHeadingMetadata(builder, heading, headingLevel, headingPath);

        result.add(builder.build());
        return chunkIndex + 1;
    }

    private int emitAtomicElement(DocumentElement element, String heading, Integer headingLevel,
                                  List<String> headingPath, List<TextChunk> result, int chunkIndex) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putHeadingMetadata(metadata, heading, headingLevel, headingPath);
        TextChunk chunk = SplitterSupport.buildAtomicChunk(element, chunkIndex, metadata, null, null);
        if (chunk != null) {
            result.add(chunk);
            return chunkIndex + 1;
        }
        return chunkIndex;
    }

    private void applyHeadingMetadata(TextChunk.Builder builder, String heading,
                                      Integer headingLevel, List<String> headingPath) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putHeadingMetadata(metadata, heading, headingLevel, headingPath);
        for (java.util.Map.Entry<String, Object> entry : metadata.entrySet()) {
            builder.putMetadata(entry.getKey(), entry.getValue());
        }
    }

    private void putHeadingMetadata(LinkedHashMap<String, Object> metadata, String heading,
                                    Integer headingLevel, List<String> headingPath) {
        if (heading != null) {
            metadata.put("heading", heading);
        }
        if (headingLevel != null) {
            metadata.put("headingLevel", headingLevel);
        }
        metadata.put("headingPath", Collections.unmodifiableList(new ArrayList<>(headingPath)));
    }

    private static class HeadingEntry {
        final int level;
        final String content;

        HeadingEntry(int level, String content) {
            this.level = level;
            this.content = content;
        }
    }
}
