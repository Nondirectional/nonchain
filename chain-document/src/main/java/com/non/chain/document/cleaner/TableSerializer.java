package com.non.chain.document.cleaner;

import com.non.chain.document.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表格序列化清洗器。
 * <p>
 * 将 {@link TableElement} 转换为可索引的纯文本格式（Markdown 表格语法），
 * 替换原始的 TableElement，使表格内容可被下游 DocumentSplitter 处理。
 * <p>
 * 非 TABLE 类型的元素原样传递。
 */
public class TableSerializer implements DocumentCleaner {

    @Override
    public ParsedDocument clean(ParsedDocument document) {
        List<DocumentElement> result = new ArrayList<>();

        for (DocumentElement element : document.elements()) {
            if (element instanceof TableElement) {
                String serialized = serialize((TableElement) element);
                if (!serialized.isBlank()) {
                    result.add(TextElement.builder(serialized)
                            .position(element.position())
                            .metadata(element.metadata())
                            .build());
                }
            } else {
                result.add(element);
            }
        }

        return ParsedDocument.builder(document.metadata())
                .elements(result)
                .build();
    }

    public static String serialize(TableElement table) {
        List<String> headers = table.headers();
        List<List<String>> rows = table.rows();

        if (headers.isEmpty() && rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        if (!headers.isEmpty()) {
            // 有表头的情况
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("| ").append(headers.stream()
                    .map(h -> "---")
                    .collect(Collectors.joining(" | "))).append(" |\n");
            for (List<String> row : rows) {
                sb.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
        } else {
            // 无表头，用第一行作为表头
            List<String> firstRow = rows.get(0);
            int colCount = firstRow.size();
            sb.append("| ").append(String.join(" | ", firstRow)).append(" |\n");
            sb.append("| ");
            for (int i = 0; i < colCount; i++) {
                if (i > 0) sb.append(" | ");
                sb.append("---");
            }
            sb.append(" |\n");
            for (int i = 1; i < rows.size(); i++) {
                sb.append("| ").append(String.join(" | ", rows.get(i))).append(" |\n");
            }
        }

        return sb.toString().trim();
    }
}
