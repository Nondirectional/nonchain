package com.non.chain.document;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ImageElement extends DocumentElement {

    private final byte[] data;
    private final String mimeType;
    private final String fileName;
    private final Integer width;
    private final Integer height;

    private ImageElement(
            byte[] data,
            String mimeType,
            String fileName,
            Integer width,
            Integer height,
            DocumentPosition position,
            Map<String, Object> metadata
    ) {
        super(ElementType.IMAGE, position, metadata);
        this.data = data;
        this.mimeType = mimeType;
        this.fileName = fileName;
        this.width = width;
        this.height = height;
    }

    public byte[] data() {
        return data == null ? null : Arrays.copyOf(data, data.length);
    }

    public String mimeType() {
        return mimeType;
    }

    public String fileName() {
        return fileName;
    }

    public Integer width() {
        return width;
    }

    public Integer height() {
        return height;
    }

    public static Builder builder(byte[] data, String mimeType) {
        return new Builder(data, mimeType);
    }

    public static class Builder {
        private byte[] data;
        private final String mimeType;
        private String fileName;
        private Integer width;
        private Integer height;
        private DocumentPosition position;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(byte[] data, String mimeType) {
            this.data = data == null ? null : Arrays.copyOf(data, data.length);
            this.mimeType = mimeType;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder width(Integer width) {
            this.width = width;
            return this;
        }

        public Builder height(Integer height) {
            this.height = height;
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

        public ImageElement build() {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("图片数据不能为空");
            }
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType 不能为空");
            }
            if (width != null && width < 1) {
                throw new IllegalArgumentException("width 不能小于 1");
            }
            if (height != null && height < 1) {
                throw new IllegalArgumentException("height 不能小于 1");
            }
            return new ImageElement(
                    Arrays.copyOf(data, data.length),
                    mimeType,
                    fileName,
                    width,
                    height,
                    position,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
            );
        }
    }
}
