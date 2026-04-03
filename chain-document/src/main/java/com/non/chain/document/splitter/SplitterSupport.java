package com.non.chain.document.splitter;

import com.non.chain.document.CodeBlockElement;
import com.non.chain.document.DocumentElement;
import com.non.chain.document.ElementType;
import com.non.chain.document.ImageElement;
import com.non.chain.document.TableElement;
import com.non.chain.document.cleaner.TableSerializer;
import com.non.chain.knowledge.ContentMeasure;
import com.non.chain.knowledge.TextChunk;

import java.util.Map;
import java.util.Objects;

final class SplitterSupport {

    private SplitterSupport() {
    }

    static TextChunk buildAtomicChunk(DocumentElement element, int chunkIndex,
                                      Map<String, Object> extraMetadata,
                                      Integer maxChunkSize,
                                      ContentMeasure contentMeasure) {
        Objects.requireNonNull(element, "element 不能为 null");

        ElementType type = element.elementType();
        TextChunk.Builder builder;
        String measuredContent = "";

        switch (type) {
            case TABLE:
                measuredContent = TableSerializer.serialize((TableElement) element);
                if (measuredContent.isEmpty()) {
                    return null;
                }
                builder = TextChunk.builder(measuredContent, ElementType.TABLE);
                break;

            case CODE_BLOCK:
                CodeBlockElement code = (CodeBlockElement) element;
                measuredContent = code.content();
                builder = TextChunk.builder(measuredContent, ElementType.CODE_BLOCK);
                break;

            case IMAGE:
                builder = TextChunk.builder("", ElementType.IMAGE);
                break;

            default:
                throw new IllegalArgumentException("不支持的原子元素类型: " + type);
        }

        builder.metadata(element.metadata());
        switch (type) {
            case CODE_BLOCK:
                CodeBlockElement code = (CodeBlockElement) element;
                if (code.language() != null) {
                    builder.putMetadata("language", code.language());
                }
                break;

            case IMAGE:
                ImageElement image = (ImageElement) element;
                if (image.fileName() != null) {
                    builder.putMetadata("imageRef", image.fileName());
                }
                break;

            default:
                break;
        }

        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            for (Map.Entry<String, Object> entry : extraMetadata.entrySet()) {
                builder.putMetadata(entry.getKey(), entry.getValue());
            }
        }
        builder.putMetadata("chunkIndex", chunkIndex);

        if (element.position() != null && element.position().pageNumber() != null) {
            builder.putMetadata("page", element.position().pageNumber());
        }

        if (type != ElementType.IMAGE
                && maxChunkSize != null
                && maxChunkSize > 0
                && contentMeasure != null
                && contentMeasure.measure(measuredContent) > maxChunkSize) {
            builder.putMetadata("oversized", true);
        }

        return builder.build();
    }
}
