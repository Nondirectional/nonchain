package com.non.chain.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SearchRequest {

    private final float[] queryEmbedding;
    private final int topK;
    private final Double minScore;
    private final List<String> knowledgeBaseIds;
    private final List<String> documentIds;
    private final List<String> chunkIds;
    private final MetadataFilter metadataFilter;

    private SearchRequest(
            float[] queryEmbedding,
            int topK,
            Double minScore,
            List<String> knowledgeBaseIds,
            List<String> documentIds,
            List<String> chunkIds,
            MetadataFilter metadataFilter
    ) {
        this.queryEmbedding = queryEmbedding;
        this.topK = topK;
        this.minScore = minScore;
        this.knowledgeBaseIds = knowledgeBaseIds;
        this.documentIds = documentIds;
        this.chunkIds = chunkIds;
        this.metadataFilter = metadataFilter;
    }

    public float[] queryEmbedding() {
        return Arrays.copyOf(queryEmbedding, queryEmbedding.length);
    }

    public int topK() {
        return topK;
    }

    public Double minScore() {
        return minScore;
    }

    public List<String> knowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public List<String> documentIds() {
        return documentIds;
    }

    public List<String> chunkIds() {
        return chunkIds;
    }

    public MetadataFilter metadataFilter() {
        return metadataFilter;
    }

    public static Builder builder(float[] queryEmbedding) {
        return new Builder(queryEmbedding);
    }

    public static class Builder {
        private final float[] queryEmbedding;
        private int topK = 5;
        private Double minScore;
        private final List<String> knowledgeBaseIds = new ArrayList<>();
        private final List<String> documentIds = new ArrayList<>();
        private final List<String> chunkIds = new ArrayList<>();
        private MetadataFilter metadataFilter;

        private Builder(float[] queryEmbedding) {
            this.queryEmbedding = queryEmbedding == null ? null : Arrays.copyOf(queryEmbedding, queryEmbedding.length);
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        public Builder knowledgeBaseIds(List<String> knowledgeBaseIds) {
            replaceIds(this.knowledgeBaseIds, knowledgeBaseIds, "knowledgeBaseId");
            return this;
        }

        public Builder addKnowledgeBaseId(String knowledgeBaseId) {
            addId(this.knowledgeBaseIds, knowledgeBaseId, "knowledgeBaseId");
            return this;
        }

        public Builder documentIds(List<String> documentIds) {
            replaceIds(this.documentIds, documentIds, "documentId");
            return this;
        }

        public Builder addDocumentId(String documentId) {
            addId(this.documentIds, documentId, "documentId");
            return this;
        }

        public Builder chunkIds(List<String> chunkIds) {
            replaceIds(this.chunkIds, chunkIds, "chunkId");
            return this;
        }

        public Builder addChunkId(String chunkId) {
            addId(this.chunkIds, chunkId, "chunkId");
            return this;
        }

        public Builder metadataFilter(MetadataFilter metadataFilter) {
            this.metadataFilter = metadataFilter;
            return this;
        }

        public SearchRequest build() {
            if (queryEmbedding == null || queryEmbedding.length == 0) {
                throw new IllegalArgumentException("查询向量不能为空");
            }
            if (topK <= 0) {
                throw new IllegalArgumentException("topK 必须大于 0");
            }
            return new SearchRequest(
                    Arrays.copyOf(queryEmbedding, queryEmbedding.length),
                    topK,
                    minScore,
                    Collections.unmodifiableList(new ArrayList<>(knowledgeBaseIds)),
                    Collections.unmodifiableList(new ArrayList<>(documentIds)),
                    Collections.unmodifiableList(new ArrayList<>(chunkIds)),
                    metadataFilter
            );
        }

        private void replaceIds(List<String> target, List<String> values, String fieldName) {
            target.clear();
            if (values == null) {
                return;
            }
            for (String value : values) {
                addId(target, value, fieldName);
            }
        }

        private void addId(List<String> target, String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " 不能为空");
            }
            target.add(value);
        }
    }
}
