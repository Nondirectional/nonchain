package com.non.chain.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContextExpansionResponse {

    private final List<SearchResult> chunks;
    private final boolean hasPrevious;
    private final boolean hasNext;
    private final Integer startChunkIndex;
    private final Integer endChunkIndex;

    private ContextExpansionResponse(
            List<SearchResult> chunks,
            boolean hasPrevious,
            boolean hasNext,
            Integer startChunkIndex,
            Integer endChunkIndex
    ) {
        this.chunks = chunks;
        this.hasPrevious = hasPrevious;
        this.hasNext = hasNext;
        this.startChunkIndex = startChunkIndex;
        this.endChunkIndex = endChunkIndex;
    }

    public List<SearchResult> chunks() {
        return chunks;
    }

    public boolean hasPrevious() {
        return hasPrevious;
    }

    public boolean hasNext() {
        return hasNext;
    }

    public Integer startChunkIndex() {
        return startChunkIndex;
    }

    public Integer endChunkIndex() {
        return endChunkIndex;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<SearchResult> chunks = new ArrayList<>();
        private boolean hasPrevious;
        private boolean hasNext;
        private Integer startChunkIndex;
        private Integer endChunkIndex;

        private Builder() {
        }

        public Builder chunks(List<SearchResult> chunks) {
            this.chunks.clear();
            if (chunks != null) {
                this.chunks.addAll(chunks);
            }
            return this;
        }

        public Builder addChunk(SearchResult chunk) {
            if (chunk == null) {
                throw new IllegalArgumentException("chunk 不能为空");
            }
            this.chunks.add(chunk);
            return this;
        }

        public Builder hasPrevious(boolean hasPrevious) {
            this.hasPrevious = hasPrevious;
            return this;
        }

        public Builder hasNext(boolean hasNext) {
            this.hasNext = hasNext;
            return this;
        }

        public Builder startChunkIndex(Integer startChunkIndex) {
            this.startChunkIndex = startChunkIndex;
            return this;
        }

        public Builder endChunkIndex(Integer endChunkIndex) {
            this.endChunkIndex = endChunkIndex;
            return this;
        }

        public ContextExpansionResponse build() {
            if (startChunkIndex != null && startChunkIndex < 0) {
                throw new IllegalArgumentException("startChunkIndex 不能小于 0");
            }
            if (endChunkIndex != null && endChunkIndex < 0) {
                throw new IllegalArgumentException("endChunkIndex 不能小于 0");
            }
            if (startChunkIndex != null && endChunkIndex != null && startChunkIndex > endChunkIndex) {
                throw new IllegalArgumentException("startChunkIndex 不能大于 endChunkIndex");
            }
            return new ContextExpansionResponse(
                    Collections.unmodifiableList(new ArrayList<>(chunks)),
                    hasPrevious,
                    hasNext,
                    startChunkIndex,
                    endChunkIndex
            );
        }
    }
}
