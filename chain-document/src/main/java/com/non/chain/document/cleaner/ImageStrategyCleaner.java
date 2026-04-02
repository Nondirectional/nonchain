package com.non.chain.document.cleaner;

import com.non.chain.document.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片策略清洗器。
 * <p>
 * 根据 {@link ImageStrategy} 配置决定如何处理 {@link ImageElement}：
 * <ul>
 *   <li>{@link ImageStrategy#KEEP} - 原样保留（默认）</li>
 *   <li>{@link ImageStrategy#REMOVE} - 移除所有图片元素</li>
 * </ul>
 * 非 IMAGE 类型的元素始终原样传递。
 */
public class ImageStrategyCleaner implements DocumentCleaner {

    /**
     * 图片处理策略
     */
    public enum ImageStrategy {
        /** 保留图片元素 */
        KEEP,
        /** 移除图片元素 */
        REMOVE
    }

    private final ImageStrategy strategy;

    public ImageStrategyCleaner() {
        this(ImageStrategy.KEEP);
    }

    public ImageStrategyCleaner(ImageStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy 不能为 null");
        }
        this.strategy = strategy;
    }

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        if (strategy == ImageStrategy.KEEP) {
            return document;
        }

        List<DocumentElement> result = new ArrayList<>();
        for (DocumentElement element : document.elements()) {
            if (element.elementType() != ElementType.IMAGE) {
                result.add(element);
            }
        }

        return ParsedDocument.builder(document.metadata())
                .elements(result)
                .build();
    }
}
