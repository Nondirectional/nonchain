package com.non.chain.knowledge;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DocumentChunk {

    private final String chunkId;
    private final String documentId;
    private final String knowledgeBaseId;
    private final String content;
    private final Map<String, Object> metadata;
    private final float[] embedding;
    private final Integer chunkIndex;

    private DocumentChunk(
            String chunkId,
            String documentId,
            String knowledgeBaseId,
            String content,
            Map<String, Object> metadata,
            float[] embedding,
            Integer chunkIndex
    ) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.content = content;
        this.metadata = metadata;
        this.embedding = embedding;
        this.chunkIndex = chunkIndex;
    }

    public String chunkId() {
        return chunkId;
    }

    public String documentId() {
        return documentId;
    }

    public String knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String content() {
        return content;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public float[] embedding() {
        return embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
    }

    public Integer chunkIndex() {
        return chunkIndex;
    }

    public static Builder builder(String documentId, String knowledgeBaseId, String content) {
        return new Builder(documentId, knowledgeBaseId, content);
    }

    public static class Builder {
        private String chunkId;
        private final String documentId;
        private final String knowledgeBaseId;
        private final String content;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private float[] embedding;
        private Integer chunkIndex;

        private Builder(String documentId, String knowledgeBaseId, String content) {
            this.documentId = documentId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.content = content;
        }

        public Builder chunkId(String chunkId) {
            this.chunkId = chunkId;
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

        public Builder embedding(float[] embedding) {
            this.embedding = embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
            return this;
        }

        public Builder chunkIndex(Integer chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        public DocumentChunk build() {
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("documentId 不能为空");
            }
            if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
                throw new IllegalArgumentException("knowledgeBaseId 不能为空");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("chunk 内容不能为空");
            }
            if (chunkIndex != null && chunkIndex < 0) {
                throw new IllegalArgumentException("chunkIndex 不能小于 0");
            }
            return new DocumentChunk(
                    chunkId,
                    documentId,
                    knowledgeBaseId,
                    content,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata)),
                    embedding == null ? null : Arrays.copyOf(embedding, embedding.length),
                    chunkIndex
            );
        }
    }
}
