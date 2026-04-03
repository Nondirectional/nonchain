package com.non.chain;

/**
 * 文本内容部件。
 */
public class TextPart implements ContentPart {

    private final String text;

    public TextPart(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public static TextPart of(String text) {
        return new TextPart(text);
    }
}
