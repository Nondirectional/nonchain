package com.non.chain.document.cleaner;

import com.non.chain.document.ParsedDocument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 清洗管道组合器，将多个 {@link DocumentCleaner} 按顺序串联执行。
 */
public class CleanerPipeline implements DocumentCleaner {

    private final List<DocumentCleaner> cleaners;

    private CleanerPipeline(List<DocumentCleaner> cleaners) {
        this.cleaners = Collections.unmodifiableList(new ArrayList<>(cleaners));
    }

    /**
     * 创建包含指定清洗器的管道。
     */
    public static CleanerPipeline of(DocumentCleaner... cleaners) {
        Objects.requireNonNull(cleaners, "cleaners 不能为 null");
        return new CleanerPipeline(Arrays.asList(cleaners));
    }

    /**
     * 创建包含指定清洗器列表的管道。
     */
    public static CleanerPipeline of(List<DocumentCleaner> cleaners) {
        Objects.requireNonNull(cleaners, "cleaners 不能为 null");
        return new CleanerPipeline(cleaners);
    }

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        Objects.requireNonNull(document, "document 不能为 null");
        ParsedDocument result = document;
        for (DocumentCleaner cleaner : cleaners) {
            result = cleaner.clean(result);
        }
        return result;
    }

    /**
     * 返回管道中的清洗器列表（不可变）。
     */
    public List<DocumentCleaner> cleaners() {
        return cleaners;
    }
}
