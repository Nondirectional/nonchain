package com.non.chain.knowledge;

public class ContextExpansionRequest {

    private final String documentId;
    private final int centerChunkIndex;
    private final int before;
    private final int after;
    private final boolean includeCenter;
    private final String knowledgeBaseId;

    private ContextExpansionRequest(
            String documentId,
            int centerChunkIndex,
            int before,
            int after,
            boolean includeCenter,
            String knowledgeBaseId
    ) {
        this.documentId = documentId;
        this.centerChunkIndex = centerChunkIndex;
        this.before = before;
        this.after = after;
        this.includeCenter = includeCenter;
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String documentId() {
        return documentId;
    }

    public int centerChunkIndex() {
        return centerChunkIndex;
    }

    public int before() {
        return before;
    }

    public int after() {
        return after;
    }

    public boolean includeCenter() {
        return includeCenter;
    }

    public String knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public static Builder builder(String documentId, int centerChunkIndex) {
        return new Builder(documentId, centerChunkIndex);
    }

    public static class Builder {
        private final String documentId;
        private final int centerChunkIndex;
        private int before;
        private int after;
        private boolean includeCenter = true;
        private String knowledgeBaseId;

        private Builder(String documentId, int centerChunkIndex) {
            this.documentId = documentId;
            this.centerChunkIndex = centerChunkIndex;
        }

        public Builder before(int before) {
            this.before = before;
            return this;
        }

        public Builder after(int after) {
            this.after = after;
            return this;
        }

        public Builder includeCenter(boolean includeCenter) {
            this.includeCenter = includeCenter;
            return this;
        }

        public Builder knowledgeBaseId(String knowledgeBaseId) {
            this.knowledgeBaseId = knowledgeBaseId;
            return this;
        }

        public ContextExpansionRequest build() {
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("documentId 不能为空");
            }
            if (centerChunkIndex < 0) {
                throw new IllegalArgumentException("centerChunkIndex 不能小于 0");
            }
            if (before < 0) {
                throw new IllegalArgumentException("before 不能小于 0");
            }
            if (after < 0) {
                throw new IllegalArgumentException("after 不能小于 0");
            }
            if (!includeCenter && before == 0 && after == 0) {
                throw new IllegalArgumentException("至少需要请求一个上下文 chunk");
            }
            if (knowledgeBaseId != null && knowledgeBaseId.isBlank()) {
                throw new IllegalArgumentException("knowledgeBaseId 不能为空白");
            }
            return new ContextExpansionRequest(
                    documentId,
                    centerChunkIndex,
                    before,
                    after,
                    includeCenter,
                    knowledgeBaseId
            );
        }
    }
}
