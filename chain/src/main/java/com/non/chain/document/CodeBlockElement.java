package com.non.chain.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class CodeBlockElement extends DocumentElement {

    private final String language;
    private final String content;

    private CodeBlockElement(String language, String content, DocumentPosition position, Map<String, Object> metadata) {
        super(ElementType.CODE_BLOCK, position, metadata);
        this.language = language;
        this.content = content;
    }

    public String language() {
        return language;
    }

    public String content() {
        return content;
    }

    public static Builder builder(String content) {
        return new Builder(content);
    }

    public static class Builder {
        private final String content;
        private String language;
        private DocumentPosition position;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String content) {
            this.content = content;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
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

        public CodeBlockElement build() {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("代码块内容不能为空");
            }
            return new CodeBlockElement(
                    language,
                    content,
                    position,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
            );
        }
    }
}
