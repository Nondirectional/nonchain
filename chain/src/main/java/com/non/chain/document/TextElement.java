package com.non.chain.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class TextElement extends DocumentElement {

    private final String content;

    private TextElement(DocumentPosition position, Map<String, Object> metadata, String content) {
        super(ElementType.TEXT, position, metadata);
        this.content = content;
    }

    public String content() {
        return content;
    }

    public static Builder builder(String content) {
        return new Builder(content);
    }

    public static class Builder {
        private final String content;
        private DocumentPosition position;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String content) {
            this.content = content;
        }

        public Builder position(DocumentPosition position) {
            this.position = position;
            return this;
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

        public TextElement build() {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("文本内容不能为空");
            }
            return new TextElement(
                    position,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata)),
                    content
            );
        }
    }
}
