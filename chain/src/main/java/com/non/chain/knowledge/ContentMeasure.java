package com.non.chain.knowledge;

/**
 * 内容度量接口，抽象长度计算方式。
 * <p>
 * 使 chunkSize / chunkOverlap 的单位可以是字符或 token。
 */
public interface ContentMeasure {

    /**
     * 度量文本长度。
     *
     * @param text 文本，null 返回 0
     * @return 度量值
     */
    int measure(String text);
}
