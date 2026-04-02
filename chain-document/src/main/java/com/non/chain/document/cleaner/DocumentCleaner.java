package com.non.chain.document.cleaner;

import com.non.chain.document.ParsedDocument;

/**
 * 文档清洗器接口，将一个 ParsedDocument 变换为更干净的 ParsedDocument。
 * <p>
 * 多个 DocumentCleaner 可通过 {@link CleanerPipeline} 组合成管道依次执行。
 */
public interface DocumentCleaner {

    /**
     * 对文档执行清洗，返回一个新的 ParsedDocument。
     *
     * @param document 原始文档，不为 null
     * @return 清洗后的文档，不为 null
     */
    ParsedDocument clean(ParsedDocument document);
}
