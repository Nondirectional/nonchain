package com.non.chain.knowledge;

import com.non.chain.document.ParsedDocument;

import java.util.List;
import java.util.Objects;

/**
 * 文档切分器接口，将文档内容切分为适合向量检索和 LLM 处理的文本块。
 */
public interface DocumentSplitter {

    /**
     * 对文档执行切分。
     *
     * @param document 文档，不为 null
     * @return 切分后的文本块列表
     */
    List<TextChunk> split(ParsedDocument document);

    /**
     * 纯文本便捷方法。
     *
     * @param text 纯文本，不为 null
     * @return 切分后的文本块列表
     */
    default List<TextChunk> split(String text) {
        Objects.requireNonNull(text, "text 不能为 null");
        return split(ParsedDocument.fromText(text));
    }
}
