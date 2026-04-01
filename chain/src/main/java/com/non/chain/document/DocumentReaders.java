package com.non.chain.document;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DocumentReaders {

    private final List<DocumentReader> readers;

    public DocumentReaders() {
        this.readers = new ArrayList<>();
    }

    public DocumentReaders register(DocumentReader reader) {
        Objects.requireNonNull(reader, "reader 不能为 null");
        readers.add(reader);
        return this;
    }

    public ParsedDocument read(DocumentSource source) throws IOException {
        Objects.requireNonNull(source, "source 不能为 null");
        String extension = source.extension();

        DocumentReader reader = findReader(extension, source.contentType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的文件格式: " + (extension != null ? extension : "未知")));

        return reader.read(source);
    }

    public ParsedDocument read(File file) throws IOException {
        return read(DocumentSource.fromFile(file));
    }

    private Optional<DocumentReader> findReader(String extension, String contentType) {
        if (extension != null) {
            for (DocumentReader reader : readers) {
                if (reader.supports(extension)) {
                    return Optional.of(reader);
                }
            }
        }
        return Optional.empty();
    }
}
