package com.non.chain.document;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class DocumentSource {

    private final InputStream inputStream;
    private final String fileName;
    private final String contentType;
    private final Map<String, Object> metadata;

    private DocumentSource(InputStream inputStream, String fileName, String contentType, Map<String, Object> metadata) {
        this.inputStream = inputStream;
        this.fileName = fileName;
        this.contentType = contentType;
        this.metadata = metadata;
    }

    public InputStream inputStream() {
        return inputStream;
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public String extension() {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    public static DocumentSource of(InputStream inputStream, String fileName) {
        Objects.requireNonNull(inputStream, "inputStream 不能为 null");
        return new DocumentSource(inputStream, fileName, null, Collections.emptyMap());
    }

    public static DocumentSource of(InputStream inputStream) {
        Objects.requireNonNull(inputStream, "inputStream 不能为 null");
        return new DocumentSource(inputStream, null, null, Collections.emptyMap());
    }

    public static DocumentSource of(byte[] data, String fileName) {
        Objects.requireNonNull(data, "data 不能为 null");
        return new DocumentSource(new ByteArrayInputStream(data), fileName, null, Collections.emptyMap());
    }

    public static DocumentSource fromFile(File file) throws FileNotFoundException {
        Objects.requireNonNull(file, "file 不能为 null");
        if (!file.exists()) {
            throw new FileNotFoundException("文件不存在: " + file.getAbsolutePath());
        }
        return new DocumentSource(
                new FileInputStream(file),
                file.getName(),
                null,
                Collections.emptyMap()
        );
    }

    public static Builder builder(InputStream inputStream) {
        return new Builder(inputStream);
    }

    public static class Builder {
        private final InputStream inputStream;
        private String fileName;
        private String contentType;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
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

        public DocumentSource build() {
            if (inputStream == null) {
                throw new IllegalArgumentException("inputStream 不能为 null");
            }
            return new DocumentSource(
                    inputStream,
                    fileName,
                    contentType,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
            );
        }
    }
}
