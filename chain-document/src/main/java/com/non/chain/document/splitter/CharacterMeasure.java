package com.non.chain.document.splitter;

import com.non.chain.knowledge.ContentMeasure;

/**
 * 字符计数度量，以 {@link String#length()} 为单位。
 */
public class CharacterMeasure implements ContentMeasure {

    @Override
    public int measure(String text) {
        return text == null ? 0 : text.length();
    }
}
