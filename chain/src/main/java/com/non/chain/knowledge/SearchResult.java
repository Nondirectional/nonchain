package com.non.chain.knowledge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SearchResult {

    private final String knowledgeBaseId;
    private final String documentId;
    private final String chunkId;
    private final String content;
    private final Map<String, Object> metadata;
    private final double score;
    private final Integer chunkIndex;

    private SearchResult(
            String knowledgeBaseId,
            String documentId,
            String chunkId,
            String content,
            Map<String, Object> metadata,
            double score,
            Integer chunkIndex
    ) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.content = content;
        this.metadata = metadata;
        this.score = score;
        this.chunkIndex = chunkIndex;
    }

    public String knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String documentId() {
        return documentId;
    }

    public String chunkId() {
        return chunkId;
    }

    public String content() {
        return content;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public double score() {
        return score;
    }

    public Integer chunkIndex() {
        return chunkIndex;
    }

    public static Builder builder(String knowledgeBaseId, String documentId, String chunkId, String content, double score) {
        return new Builder(knowledgeBaseId, documentId, chunkId, content, score);
    }

    public static class Builder {
        private final String knowledgeBaseId;
        private final String documentId;
        private final String chunkId;
        private final String content;
        private final double score;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private Integer chunkIndex;

        private Builder(String knowledgeBaseId, String documentId, String chunkId, String content, double score) {
            this.knowledgeBaseId = knowledgeBaseId;
            this.documentId = documentId;
            this.chunkId = chunkId;
            this.content = content;
            this.score = score;
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

        public Builder chunkIndex(Integer chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        public SearchResult build() {
            if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
                throw new IllegalArgumentException("knowledgeBaseId 不能为空");
            }
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("documentId 不能为空");
            }
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException("chunkId 不能为空");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("结果内容不能为空");
            }
            if (chunkIndex != null && chunkIndex < 0) {
                throw new IllegalArgumentException("chunkIndex 不能小于 0");
            }
            Map<String, Object> immutableMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            return new SearchResult(
                    knowledgeBaseId,
                    documentId,
                    chunkId,
                    content,
                    immutableMetadata,
                    score,
                    chunkIndex
            );
        }
    }
}
