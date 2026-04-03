package com.non.chain.document.splitter;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import com.non.chain.knowledge.ContentMeasure;

/**
 * 基于 tokenizer 的 token 计数度量。
 * <p>
 * 依赖 jtokkit（optional），支持按编码类型或模型名构造。
 */
public class TokenMeasure implements ContentMeasure {

    private final Encoding encoding;

    /**
     * 按编码类型构造（cl100k_base, o200k_base 等）。
     */
    public TokenMeasure(EncodingType encodingType) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(encodingType);
    }

    /**
     * 按模型名构造（自动选择对应编码）。
     */
    public TokenMeasure(ModelType modelType) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncodingForModel(modelType);
    }

    @Override
    public int measure(String text) {
        return text == null ? 0 : encoding.countTokens(text);
    }
}
