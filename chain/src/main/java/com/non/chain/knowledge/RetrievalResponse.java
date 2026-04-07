package com.non.chain.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RetrievalResponse {

    private final List<SearchResult> results;
    private final RetrievalDebugInfo debugInfo;

    private RetrievalResponse(List<SearchResult> results, RetrievalDebugInfo debugInfo) {
        this.results = results;
        this.debugInfo = debugInfo;
    }

    public List<SearchResult> results() {
        return results;
    }

    public RetrievalDebugInfo debugInfo() {
        return debugInfo;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<SearchResult> results = new ArrayList<>();
        private RetrievalDebugInfo debugInfo;

        private Builder() {
        }

        public Builder results(List<SearchResult> results) {
            this.results.clear();
            if (results != null) {
                this.results.addAll(results);
            }
            return this;
        }

        public Builder addResult(SearchResult result) {
            if (result == null) {
                throw new IllegalArgumentException("result 不能为空");
            }
            this.results.add(result);
            return this;
        }

        public Builder debugInfo(RetrievalDebugInfo debugInfo) {
            this.debugInfo = debugInfo;
            return this;
        }

        public RetrievalResponse build() {
            return new RetrievalResponse(
                    Collections.unmodifiableList(new ArrayList<>(results)),
                    debugInfo
            );
        }
    }
}
