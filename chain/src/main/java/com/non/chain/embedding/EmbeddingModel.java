package com.non.chain.embedding;

import java.util.List;

public interface EmbeddingModel {

    /**
     * 批量生成向量（核心方法）。
     *
     * @param texts 文本列表
     * @return 与输入顺序一致的向量列表
     */
    List<float[]> embedAll(List<String> texts);

    /**
     * 单条文本生成向量，默认委托批量方法。
     */
    default float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        return embedAll(List.of(text)).get(0);
    }

    /**
     * 向量维度，默认通过单条 embedding 推断。
     */
    default int dimension() {
        return embed("dimension probe").length;
    }
}
