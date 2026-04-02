package com.non.chain.document.cleaner;

import java.text.Normalizer;

/**
 * Unicode 规范化清洗器。
 * <p>
 * 执行 NFC 规范化，并将全角 ASCII 字符转换为对应的半角字符。
 * <ul>
 *   <li>NFC 规范化：统一 Unicode 编码形式（组合字符 → 预组合字符）</li>
 *   <li>全角→半角：Ａ→A, ０→0, ａ→a 等</li>
 * </ul>
 */
public class UnicodeNormalizer extends TextContentCleaner {

    @Override
    protected String transformText(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        return convertFullwidthToHalfwidth(normalized);
    }

    private String convertFullwidthToHalfwidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 全角 ASCII 范围：FF01-FF5E 对应半角 !-~
            // 全角空格：3000 → 0020
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
