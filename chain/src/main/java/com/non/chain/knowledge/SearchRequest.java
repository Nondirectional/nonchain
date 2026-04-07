package com.non.chain.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SearchRequest {

    private final String queryText;
    private final float[] queryEmbedding;
    private final int size;
    private final int rankWindowSize;
    private final int numCandidates;
    private final List<String> knowledgeBaseIds;
    private final List<String> documentIds;
    private final List<String> chunkIds;
    private final MetadataFilter metadataFilter;
    private final boolean debug;
    private final boolean trace;
    private final FusionStrategy fusionStrategy;
    private final double vectorWeight;
    private final double keywordWeight;

    private SearchRequest(
            String queryText,
            float[] queryEmbedding,
            int size,
            int rankWindowSize,
            int numCandidates,
            List<String> knowledgeBaseIds,
            List<String> documentIds,
            List<String> chunkIds,
            MetadataFilter metadataFilter,
            boolean debug,
            boolean trace,
            FusionStrategy fusionStrategy,
            double vectorWeight,
            double keywordWeight
    ) {
        this.queryText = queryText;
        this.queryEmbedding = queryEmbedding;
        this.size = size;
        this.rankWindowSize = rankWindowSize;
        this.numCandidates = numCandidates;
        this.knowledgeBaseIds = knowledgeBaseIds;
        this.documentIds = documentIds;
        this.chunkIds = chunkIds;
        this.metadataFilter = metadataFilter;
        this.debug = debug;
        this.trace = trace;
        this.fusionStrategy = fusionStrategy;
        this.vectorWeight = vectorWeight;
        this.keywordWeight = keywordWeight;
    }

    public String queryText() {
        return queryText;
    }

    public float[] queryEmbedding() {
        return queryEmbedding == null ? null : Arrays.copyOf(queryEmbedding, queryEmbedding.length);
    }

    public int size() {
        return size;
    }

    public int topK() {
        return size;
    }

    public int rankWindowSize() {
        return rankWindowSize;
    }

    public int numCandidates() {
        return numCandidates;
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

    public boolean debug() {
        return debug;
    }

    public boolean trace() {
        return trace;
    }

    public FusionStrategy fusionStrategy() {
        return fusionStrategy;
    }

    public double vectorWeight() {
        return vectorWeight;
    }

    public double keywordWeight() {
        return keywordWeight;
    }

    public boolean hasQueryText() {
        return queryText != null && !queryText.isBlank();
    }

    public boolean hasQueryEmbedding() {
        return queryEmbedding != null && queryEmbedding.length > 0;
    }

    public RetrievalMode mode() {
        if (hasQueryText() && hasQueryEmbedding()) {
            return RetrievalMode.HYBRID;
        }
        if (hasQueryText()) {
            return RetrievalMode.BM25;
        }
        if (hasQueryEmbedding()) {
            return RetrievalMode.KNN;
        }
        throw new IllegalStateException("检索请求缺少 queryText 或 queryEmbedding");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(float[] queryEmbedding) {
        return new Builder().queryEmbedding(queryEmbedding);
    }

    public static class Builder {
        private String queryText;
        private float[] queryEmbedding;
        private int size = 10;
        private Integer rankWindowSize;
        private Integer numCandidates;
        private final List<String> knowledgeBaseIds = new ArrayList<>();
        private final List<String> documentIds = new ArrayList<>();
        private final List<String> chunkIds = new ArrayList<>();
        private MetadataFilter metadataFilter;
        private boolean debug;
        private boolean trace;
        private FusionStrategy fusionStrategy = FusionStrategy.RRF;
        private double vectorWeight = 1.0;
        private double keywordWeight = 1.0;

        private Builder() {
        }

        public Builder queryText(String queryText) {
            this.queryText = normalizeText(queryText);
            return this;
        }

        public Builder queryEmbedding(float[] queryEmbedding) {
            this.queryEmbedding = queryEmbedding == null ? null : Arrays.copyOf(queryEmbedding, queryEmbedding.length);
            return this;
        }

        public Builder size(int size) {
            this.size = size;
            return this;
        }

        public Builder topK(int topK) {
            return size(topK);
        }

        public Builder rankWindowSize(int rankWindowSize) {
            this.rankWindowSize = rankWindowSize;
            return this;
        }

        public Builder numCandidates(int numCandidates) {
            this.numCandidates = numCandidates;
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

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder trace(boolean trace) {
            this.trace = trace;
            return this;
        }

        public Builder fusionStrategy(FusionStrategy fusionStrategy) {
            if (fusionStrategy == null) {
                throw new IllegalArgumentException("fusionStrategy 不能为空");
            }
            this.fusionStrategy = fusionStrategy;
            return this;
        }

        public Builder vectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
            return this;
        }

        public Builder keywordWeight(double keywordWeight) {
            this.keywordWeight = keywordWeight;
            return this;
        }

        public SearchRequest build() {
            boolean hasText = queryText != null && !queryText.isBlank();
            boolean hasEmbedding = queryEmbedding != null && queryEmbedding.length > 0;
            if (!hasText && !hasEmbedding) {
                throw new IllegalArgumentException("queryText 和 queryEmbedding 不能同时为空");
            }
            if (size <= 0) {
                throw new IllegalArgumentException("size 必须大于 0");
            }
            int finalRankWindowSize = rankWindowSize != null ? rankWindowSize : Math.max(50, size * 5);
            if (finalRankWindowSize < size) {
                throw new IllegalArgumentException("rankWindowSize 不能小于 size");
            }
            int finalNumCandidates = numCandidates != null ? numCandidates : Math.max(100, finalRankWindowSize * 2);
            if (finalNumCandidates <= 0) {
                throw new IllegalArgumentException("numCandidates 必须大于 0");
            }
            if (vectorWeight <= 0) {
                throw new IllegalArgumentException("vectorWeight 必须大于 0");
            }
            if (keywordWeight <= 0) {
                throw new IllegalArgumentException("keywordWeight 必须大于 0");
            }

            return new SearchRequest(
                    queryText,
                    hasEmbedding ? Arrays.copyOf(queryEmbedding, queryEmbedding.length) : null,
                    size,
                    finalRankWindowSize,
                    finalNumCandidates,
                    Collections.unmodifiableList(new ArrayList<>(knowledgeBaseIds)),
                    Collections.unmodifiableList(new ArrayList<>(documentIds)),
                    Collections.unmodifiableList(new ArrayList<>(chunkIds)),
                    metadataFilter,
                    debug || trace,
                    trace,
                    fusionStrategy,
                    vectorWeight,
                    keywordWeight
            );
        }

        private String normalizeText(String text) {
            if (text == null) {
                return null;
            }
            String normalized = text.trim();
            return normalized.isEmpty() ? null : normalized;
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
