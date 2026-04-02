package com.non.chain.document.cleaner;

/**
 * 控制字符移除清洗器。
 * <p>
 * 移除 Unicode 控制字符（类别 Cc）和格式字符（类别 Cf，包括零宽字符），
 * 但保留常见的空白控制字符（换行 \\n、制表 \\t、回车 \\r），
 * 这些由 {@link WhitespaceNormalizer} 统一处理。
 */
public class ControlCharacterRemover extends TextContentCleaner {

    @Override
    protected String transformText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAllowedControlChar(c) || !isControlOrFormat(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isAllowedControlChar(char c) {
        return c == '\n' || c == '\r' || c == '\t';
    }

    private boolean isControlOrFormat(char c) {
        int type = Character.getType(c);
        return type == Character.CONTROL        // Cc
                || type == Character.FORMAT      // Cf (zero-width, etc.)
                || type == Character.PRIVATE_USE // Co
                || type == Character.SURROGATE;  // Cs
    }
}
