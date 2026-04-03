package com.non.chain.document.splitter;

import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.ElementType;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;
import com.non.chain.knowledge.DocumentSplitter;
import com.non.chain.knowledge.TextChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 组合切分器，支持二级切分。
 * <p>
 * 先用 primary 按结构拆分，再对每个 TEXT 类型 chunk 用 secondary 细分。
 * 原子元素直接透传，metadata 合并（子覆盖父）。
 */
public class CompositeDocumentSplitter implements DocumentSplitter {

    private final DocumentSplitter primary;
    private final DocumentSplitter secondary;

    public CompositeDocumentSplitter(DocumentSplitter primary, DocumentSplitter secondary) {
        Objects.requireNonNull(primary, "primary splitter 不能为 null");
        Objects.requireNonNull(secondary, "secondary splitter 不能为 null");
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public List<TextChunk> split(ParsedDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");

        List<TextChunk> primaryChunks = primary.split(document);
        if (primaryChunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextChunk> result = new ArrayList<>();
        int chunkIndex = 0;

        for (TextChunk chunk : primaryChunks) {
            if (chunk.elementType() != ElementType.TEXT) {
                // 非 TEXT 类型（原子元素）直接透传，更新 chunkIndex
                TextChunk.Builder builder = TextChunk.builder(chunk.content(), chunk.elementType())
                        .metadata(chunk.metadata())
                        .putMetadata("chunkIndex", chunkIndex++);
                result.add(builder.build());
                continue;
            }

            // TEXT 类型 → 二次切分
            ParsedDocument subDoc = ParsedDocument.fromText(chunk.content());
            List<TextChunk> subChunks = secondary.split(subDoc);

            Map<String, Object> parentMetadata = chunk.metadata();

            for (TextChunk subChunk : subChunks) {
                // 合并 metadata：父为基础，子覆盖
                Map<String, Object> merged = new LinkedHashMap<>(parentMetadata);
                merged.putAll(subChunk.metadata());
                merged.put("chunkIndex", chunkIndex++);

                result.add(TextChunk.builder(subChunk.content(), subChunk.elementType())
                        .metadata(merged)
                        .build());
            }
        }

        return Collections.unmodifiableList(result);
    }
}
