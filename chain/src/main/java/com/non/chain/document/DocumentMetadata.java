package com.non.chain.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DocumentMetadata {

    private final String fileName;
    private final String format;
    private final Integer pageCount;
    private final Map<String, Object> attributes;

    private DocumentMetadata(String fileName, String format, Integer pageCount, Map<String, Object> attributes) {
        this.fileName = fileName;
        this.format = format;
        this.pageCount = pageCount;
        this.attributes = attributes;
    }

    public String fileName() {
        return fileName;
    }

    public String format() {
        return format;
    }

    public Integer pageCount() {
        return pageCount;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String fileName;
        private String format;
        private Integer pageCount;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public Builder pageCount(Integer pageCount) {
            this.pageCount = pageCount;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes.clear();
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        public Builder putAttribute(String key, Object value) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("attribute key 不能为空");
            }
            this.attributes.put(key, value);
            return this;
        }

        public DocumentMetadata build() {
            if (pageCount != null && pageCount < 1) {
                throw new IllegalArgumentException("pageCount 不能小于 1");
            }
            return new DocumentMetadata(
                    fileName,
                    format,
                    pageCount,
                    Collections.unmodifiableMap(new LinkedHashMap<>(attributes))
            );
        }
    }
}
