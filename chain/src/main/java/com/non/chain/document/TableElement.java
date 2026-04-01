package com.non.chain.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TableElement extends DocumentElement {

    private final List<String> headers;
    private final List<List<String>> rows;

    private TableElement(List<String> headers, List<List<String>> rows, DocumentPosition position, Map<String, Object> metadata) {
        super(ElementType.TABLE, position, metadata);
        this.headers = headers;
        this.rows = rows;
    }

    public List<String> headers() {
        return headers;
    }

    public List<List<String>> rows() {
        return rows;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<String> headers = new ArrayList<>();
        private final List<List<String>> rows = new ArrayList<>();
        private DocumentPosition position;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder headers(List<String> headers) {
            this.headers.clear();
            if (headers != null) {
                this.headers.addAll(headers);
            }
            return this;
        }

        public Builder addHeader(String header) {
            this.headers.add(header);
            return this;
        }

        public Builder rows(List<List<String>> rows) {
            this.rows.clear();
            if (rows != null) {
                for (List<String> row : rows) {
                    this.rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
                }
            }
            return this;
        }

        public Builder addRow(List<String> row) {
            Objects.requireNonNull(row, "行数据不能为 null");
            this.rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
            return this;
        }

        public Builder position(DocumentPosition position) {
            this.position = position;
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

        public TableElement build() {
            return new TableElement(
                    Collections.unmodifiableList(new ArrayList<>(headers)),
                    Collections.unmodifiableList(new ArrayList<>(rows)),
                    position,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
            );
        }
    }
}
