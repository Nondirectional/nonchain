package com.non.chain.document.cleaner;

/**
 * 空白字符规范化清洗器。
 * <p>
 * 将多个连续空白字符（空格、制表符、换行等）合并为单个空格，
 * 并去除文本首尾的空白。
 */
public class WhitespaceNormalizer extends TextContentCleaner {

    @Override
    protected String transformText(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
