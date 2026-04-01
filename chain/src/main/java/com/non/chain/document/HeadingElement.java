package com.non.chain.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class HeadingElement extends DocumentElement {

    private final int level;
    private final String content;

    private HeadingElement(int level, String content, DocumentPosition position, Map<String, Object> metadata) {
        super(ElementType.HEADING, position, metadata);
        this.level = level;
        this.content = content;
    }

    public int level() {
        return level;
    }

    public String content() {
        return content;
    }

    public static Builder builder(int level, String content) {
        return new Builder(level, content);
    }

    public static class Builder {
        private final int level;
        private final String content;
        private DocumentPosition position;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(int level, String content) {
            this.level = level;
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

        public HeadingElement build() {
            if (level < 1 || level > 6) {
                throw new IllegalArgumentException("heading level 必须在 1-6 之间");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("heading 内容不能为空");
            }
            return new HeadingElement(
                    level,
                    content,
                    position,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
            );
        }
    }
}
