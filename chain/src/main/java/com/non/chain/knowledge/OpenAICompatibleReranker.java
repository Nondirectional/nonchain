package com.non.chain.knowledge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 OpenAI 兼容 /v1/rerank 端点的 Reranker 实现。
 * 适用于 vLLM、Jina、Cohere 等提供 rerank API 的服务。
 *
 * <p>使用示例：
 * <pre>{@code
 * Reranker reranker = new OpenAICompatibleReranker(
 *     "http://10.100.10.21:40000/v1",
 *     "bge-reranker-large"
 * );
 * List<SearchResult> reranked = reranker.rerank(query, candidates, 10);
 * }</pre>
 */
public class OpenAICompatibleReranker implements Reranker {

    private final String baseUrl;
    private final String model;
    private final String apiKey;

    /**
     * 无 API Key 构造（适用于内网无认证部署）
     *
     * @param baseUrl 服务端点地址，如 "http://10.100.10.21:40000/v1"
     * @param model   模型名称，如 "bge-reranker-large"
     */
    public OpenAICompatibleReranker(String baseUrl, String model) {
        this(baseUrl, null, model);
    }

    /**
     * 完整参数构造
     *
     * @param baseUrl 服务端点地址
     * @param apiKey  API Key（可选，无认证时传 null）
     * @param model   模型名称
     */
    public OpenAICompatibleReranker(String baseUrl, String apiKey, String model) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> results, int topN) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (topN <= 0) {
            topN = results.size();
        }

        String requestBody = buildRequestJson(query, results, topN);
        String responseBody = sendRequest(requestBody);
        return parseResponse(responseBody, results);
    }

    private String sendRequest(String requestBody) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/rerank").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body;
            if (stream == null) {
                throw new RuntimeException("Rerank API 调用失败: HTTP " + code + " (无响应体)");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }

            if (code >= 400) {
                throw new RuntimeException("Rerank API 调用失败: HTTP " + code + " - " + body);
            }

            return body;
        } catch (IOException e) {
            throw new RuntimeException("Rerank API 调用失败", e);
        }
    }

    String buildRequestJson(String query, List<SearchResult> results, int topN) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":");
        sb.append(escapeJson(model));
        sb.append(",\"query\":");
        sb.append(escapeJson(query));
        sb.append(",\"documents\":[");

        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeJson(results.get(i).content()));
        }

        sb.append(']');

        if (topN < results.size()) {
            sb.append(",\"top_n\":");
            sb.append(topN);
        }

        sb.append('}');
        return sb.toString();
    }

    List<SearchResult> parseResponse(String responseBody, List<SearchResult> originalResults) {
        List<SearchResult> reranked = new ArrayList<>();

        String resultsKey = "\"results\":";
        int resultsIdx = responseBody.indexOf(resultsKey);
        if (resultsIdx < 0) {
            throw new RuntimeException("Rerank 响应格式错误: 缺少 results 字段");
        }

        int arrayStart = responseBody.indexOf('[', resultsIdx);
        int arrayEnd = responseBody.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd < 0 || arrayEnd <= arrayStart) {
            throw new RuntimeException("Rerank 响应格式错误: results 数组解析失败");
        }

        String arrayContent = responseBody.substring(arrayStart + 1, arrayEnd);
        String[] items = splitJsonObjects(arrayContent);

        for (String item : items) {
            int index = extractIntValue(item, "\"index\"");
            double score = extractDoubleValue(item, "\"relevance_score\"");

            if (index < 0 || index >= originalResults.size()) {
                continue;
            }

            SearchResult original = originalResults.get(index);
            SearchResult rescored = SearchResult.builder(
                    original.knowledgeBaseId(),
                    original.documentId(),
                    original.chunkId(),
                    original.content(),
                    score
            ).metadata(original.metadata()).chunkIndex(original.chunkIndex()).build();

            reranked.add(rescored);
        }

        return reranked;
    }

    private String[] splitJsonObjects(String arrayContent) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    items.add(arrayContent.substring(start, i + 1));
                    start = -1;
                }
            }
        }

        return items.toArray(new String[0]);
    }

    private int extractIntValue(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return -1;
        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return -1;

        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) <= ' ') start++;

        int end = start;
        while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;

        return Integer.parseInt(json.substring(start, end));
    }

    private double extractDoubleValue(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0.0;
        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return 0.0;

        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) <= ' ') start++;

        int end = start;
        while (end < json.length() && isNumberChar(json.charAt(end))) end++;

        return Double.parseDouble(json.substring(start, end));
    }

    private boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == '-' || c == 'e' || c == 'E' || c == '+';
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
