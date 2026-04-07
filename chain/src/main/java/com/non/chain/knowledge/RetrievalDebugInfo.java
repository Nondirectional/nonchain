package com.non.chain.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RetrievalDebugInfo {

    private final RetrievalMode mode;
    private final FusionStrategy fusionStrategy;
    private final String analyzer;
    private final int size;
    private final Integer rankWindowSize;
    private final Integer numCandidates;
    private final List<String> filtersApplied;
    private final boolean profileIncluded;
    private final Long tookMs;
    private final List<String> matchedRetrievers;

    private RetrievalDebugInfo(
            RetrievalMode mode,
            FusionStrategy fusionStrategy,
            String analyzer,
            int size,
            Integer rankWindowSize,
            Integer numCandidates,
            List<String> filtersApplied,
            boolean profileIncluded,
            Long tookMs,
            List<String> matchedRetrievers
    ) {
        this.mode = mode;
        this.fusionStrategy = fusionStrategy;
        this.analyzer = analyzer;
        this.size = size;
        this.rankWindowSize = rankWindowSize;
        this.numCandidates = numCandidates;
        this.filtersApplied = filtersApplied;
        this.profileIncluded = profileIncluded;
        this.tookMs = tookMs;
        this.matchedRetrievers = matchedRetrievers;
    }

    public RetrievalMode mode() {
        return mode;
    }

    public FusionStrategy fusionStrategy() {
        return fusionStrategy;
    }

    public String analyzer() {
        return analyzer;
    }

    public int size() {
        return size;
    }

    public Integer rankWindowSize() {
        return rankWindowSize;
    }

    public Integer numCandidates() {
        return numCandidates;
    }

    public List<String> filtersApplied() {
        return filtersApplied;
    }

    public boolean profileIncluded() {
        return profileIncluded;
    }

    public Long tookMs() {
        return tookMs;
    }

    public List<String> matchedRetrievers() {
        return matchedRetrievers;
    }

    public static Builder builder(RetrievalMode mode, int size) {
        return new Builder(mode, size);
    }

    public static class Builder {
        private final RetrievalMode mode;
        private final int size;
        private FusionStrategy fusionStrategy;
        private String analyzer;
        private Integer rankWindowSize;
        private Integer numCandidates;
        private final List<String> filtersApplied = new ArrayList<>();
        private boolean profileIncluded;
        private Long tookMs;
        private final List<String> matchedRetrievers = new ArrayList<>();

        private Builder(RetrievalMode mode, int size) {
            this.mode = mode;
            this.size = size;
        }

        public Builder fusionStrategy(FusionStrategy fusionStrategy) {
            this.fusionStrategy = fusionStrategy;
            return this;
        }

        public Builder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public Builder rankWindowSize(Integer rankWindowSize) {
            this.rankWindowSize = rankWindowSize;
            return this;
        }

        public Builder numCandidates(Integer numCandidates) {
            this.numCandidates = numCandidates;
            return this;
        }

        public Builder filtersApplied(List<String> filtersApplied) {
            this.filtersApplied.clear();
            if (filtersApplied != null) {
                this.filtersApplied.addAll(filtersApplied);
            }
            return this;
        }

        public Builder addFilterApplied(String filterName) {
            if (filterName == null || filterName.isBlank()) {
                throw new IllegalArgumentException("filterName 不能为空");
            }
            this.filtersApplied.add(filterName);
            return this;
        }

        public Builder profileIncluded(boolean profileIncluded) {
            this.profileIncluded = profileIncluded;
            return this;
        }

        public Builder tookMs(Long tookMs) {
            this.tookMs = tookMs;
            return this;
        }

        public Builder matchedRetrievers(List<String> matchedRetrievers) {
            this.matchedRetrievers.clear();
            if (matchedRetrievers != null) {
                this.matchedRetrievers.addAll(matchedRetrievers);
            }
            return this;
        }

        public Builder addMatchedRetriever(String matchedRetriever) {
            if (matchedRetriever == null || matchedRetriever.isBlank()) {
                throw new IllegalArgumentException("matchedRetriever 不能为空");
            }
            this.matchedRetrievers.add(matchedRetriever);
            return this;
        }

        public RetrievalDebugInfo build() {
            if (mode == null) {
                throw new IllegalArgumentException("mode 不能为空");
            }
            if (size <= 0) {
                throw new IllegalArgumentException("size 必须大于 0");
            }
            if (rankWindowSize != null && rankWindowSize <= 0) {
                throw new IllegalArgumentException("rankWindowSize 必须大于 0");
            }
            if (numCandidates != null && numCandidates <= 0) {
                throw new IllegalArgumentException("numCandidates 必须大于 0");
            }
            if (tookMs != null && tookMs < 0) {
                throw new IllegalArgumentException("tookMs 不能小于 0");
            }
            return new RetrievalDebugInfo(
                    mode,
                    fusionStrategy,
                    analyzer,
                    size,
                    rankWindowSize,
                    numCandidates,
                    Collections.unmodifiableList(new ArrayList<>(filtersApplied)),
                    profileIncluded,
                    tookMs,
                    Collections.unmodifiableList(new ArrayList<>(matchedRetrievers))
            );
        }
    }
}
