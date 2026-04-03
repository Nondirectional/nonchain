package com.non.chain.knowledge;

import com.non.chain.document.ElementType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 文本块，文档切分的输出单元。
 * <p>
 * 不可变对象，使用 Builder 模式构造。
 */
public class TextChunk {

    private final String content;
    private final ElementType elementType;
    private final Map<String, Object> metadata;

    private TextChunk(String content, ElementType elementType, Map<String, Object> metadata) {
        this.content = content;
        this.elementType = elementType;
        this.metadata = metadata;
    }

    public String content() {
        return content;
    }

    public ElementType elementType() {
        return elementType;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    /**
     * 快捷构造 TEXT 类型 chunk。
     */
    public static TextChunk text(String content) {
        return builder(content, ElementType.TEXT).build();
    }

    public static Builder builder(String content, ElementType elementType) {
        return new Builder(content, elementType);
    }

    public static class Builder {
        private final String content;
        private final ElementType elementType;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String content, ElementType elementType) {
            this.content = content;
            this.elementType = elementType;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.clear();
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public Builder putMetadata(String key, Object value) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("metadata key 不能为空");
            }
            this.metadata.put(key, value);
            return this;
        }

        public TextChunk build() {
            Objects.requireNonNull(elementType, "elementType 不能为 null");
            if (elementType == ElementType.IMAGE) {
                // IMAGE 类型允许空内容
                String c = content == null ? "" : content;
                return new TextChunk(
                        c,
                        elementType,
                        Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                );
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("非 IMAGE 类型的 content 不能为空");
            }
            return new TextChunk(
                    content,
                    elementType,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
            );
        }
    }
}
