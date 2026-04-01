package com.non.chain.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ParsedDocument {

    private final DocumentMetadata metadata;
    private final List<DocumentElement> elements;

    private ParsedDocument(DocumentMetadata metadata, List<DocumentElement> elements) {
        this.metadata = metadata;
        this.elements = elements;
    }

    public DocumentMetadata metadata() {
        return metadata;
    }

    public List<DocumentElement> elements() {
        return elements;
    }

    public static Builder builder(DocumentMetadata metadata) {
        return new Builder(metadata);
    }

    public static class Builder {
        private final DocumentMetadata metadata;
        private final List<DocumentElement> elements = new ArrayList<>();

        private Builder(DocumentMetadata metadata) {
            this.metadata = metadata;
        }

        public Builder elements(List<DocumentElement> elements) {
            this.elements.clear();
            if (elements != null) {
                this.elements.addAll(elements);
            }
            return this;
        }

        public Builder addElement(DocumentElement element) {
            Objects.requireNonNull(element, "element 不能为 null");
            this.elements.add(element);
            return this;
        }

        public ParsedDocument build() {
            Objects.requireNonNull(metadata, "metadata 不能为 null");
            return new ParsedDocument(
                    metadata,
                    Collections.unmodifiableList(new ArrayList<>(elements))
            );
        }
    }
}
