package com.non.chain.document;

public class DocumentPosition {

    private final Integer pageNumber;
    private final Integer lineNumber;
    private final Integer charOffset;

    private DocumentPosition(Integer pageNumber, Integer lineNumber, Integer charOffset) {
        this.pageNumber = pageNumber;
        this.lineNumber = lineNumber;
        this.charOffset = charOffset;
    }

    public Integer pageNumber() {
        return pageNumber;
    }

    public Integer lineNumber() {
        return lineNumber;
    }

    public Integer charOffset() {
        return charOffset;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Integer pageNumber;
        private Integer lineNumber;
        private Integer charOffset;

        private Builder() {
        }

        public Builder pageNumber(Integer pageNumber) {
            this.pageNumber = pageNumber;
            return this;
        }

        public Builder lineNumber(Integer lineNumber) {
            this.lineNumber = lineNumber;
            return this;
        }

        public Builder charOffset(Integer charOffset) {
            this.charOffset = charOffset;
            return this;
        }

        public DocumentPosition build() {
            if (pageNumber != null && pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber 不能小于 1");
            }
            if (lineNumber != null && lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber 不能小于 1");
            }
            if (charOffset != null && charOffset < 0) {
                throw new IllegalArgumentException("charOffset 不能小于 0");
            }
            return new DocumentPosition(pageNumber, lineNumber, charOffset);
        }
    }
}
