package com.non.chain.knowledge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SourceDocument {

    private final String documentId;
    private final String knowledgeBaseId;
    private final String sourceType;
    private final String sourceName;
    private final Map<String, Object> metadata;

    private SourceDocument(
            String documentId,
            String knowledgeBaseId,
            String sourceType,
            String sourceName,
            Map<String, Object> metadata
    ) {
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.sourceType = sourceType;
        this.sourceName = sourceName;
        this.metadata = metadata;
    }

    public String documentId() {
        return documentId;
    }

    public String knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String sourceType() {
        return sourceType;
    }

    public String sourceName() {
        return sourceName;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public static Builder builder(String documentId, String knowledgeBaseId, String sourceType, String sourceName) {
        return new Builder(documentId, knowledgeBaseId, sourceType, sourceName);
    }

    public static class Builder {
        private final String documentId;
        private final String knowledgeBaseId;
        private final String sourceType;
        private final String sourceName;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String documentId, String knowledgeBaseId, String sourceType, String sourceName) {
            this.documentId = documentId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.sourceType = sourceType;
            this.sourceName = sourceName;
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
            metadata.put(key, value);
            return this;
        }

        public SourceDocument build() {
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("documentId 不能为空");
            }
            if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
                throw new IllegalArgumentException("knowledgeBaseId 不能为空");
            }
            if (sourceType == null || sourceType.isBlank()) {
                throw new IllegalArgumentException("sourceType 不能为空");
            }
            if (sourceName == null || sourceName.isBlank()) {
                throw new IllegalArgumentException("sourceName 不能为空");
            }
            return new SourceDocument(
                    documentId,
                    knowledgeBaseId,
                    sourceType,
                    sourceName,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
            );
        }
    }
}
