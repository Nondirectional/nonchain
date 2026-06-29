package com.non.chain.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.non.chain.Message;
import com.non.chain.memory.MessageSerializer;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@link Trace} / {@link Span} 的 JSON 序列化器（稳定 schema，便于外部 store 建表）。
 *
 * <p>独立工具类（而非放进核心模型）：让核心模型 {@link Trace}/{@link Span} 保持对外部库零依赖，
 * 符合 quality-guidelines「core models 不引外部库」。messages 载荷复用 {@link MessageSerializer}。</p>
 *
 * <p>Trace JSON 结构：</p>
 * <pre>{@code
 * {
 *   "runtimeId": "...",
 *   "conversationId": "..." | null,
 *   "spans": [
 *     { "spanId", "parentSpanId":null, "runtimeId", "type", "name",
 *       "startTimeMs", "endTimeMs", "status", "error":null,
 *       "attributes": { ...按 type 约定的载荷... } },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>attributes 序列化按值实际类型分派：
 * {@code Message} → 复用 {@link MessageSerializer} 再 parse 进树；
 * {@code List<Message>} → 数组；
 * {@code Tool} / {@code List<Tool>} → name + description 摘要；
 * {@code ToolCall} / {@code List<ToolCall>} → {id,name,arguments}；
 * {@code Map} → 原样；{@code Number/Boolean/String} → 原样；其余 → toString。</p>
 */
public final class TraceSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TraceSerializer() {}

    /**
     * 把单个 span 的 attributes 序列化为 JSON 字符串（供持久化 store 建列存储）。
     *
     * <p>值类型分派见类注释（Message→结构化对象、List→数组、Tool/ToolCall→摘要、Map/Number/Boolean/String→原样）。
     * 与 {@link #serialize(Trace)} 单 span 段的 attributes 编码完全一致，保证持久化 ↔ JSON 互操作。</p>
     */
    public static String serializeAttributes(java.util.Map<String, Object> attributes) {
        ObjectNode attrs = MAPPER.createObjectNode();
        if (attributes != null) {
            for (java.util.Map.Entry<String, Object> e : attributes.entrySet()) {
                attrs.set(e.getKey(), toJsonValue(e.getValue()));
            }
        }
        try {
            return MAPPER.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Span attributes 序列化失败", ex);
        }
    }

    /**
     * 从 JSON 字符串反序列化 attributes（{@link #serializeAttributes} 的逆操作）。
     */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> deserializeAttributes(String json) {
        if (json == null || json.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(e -> result.put(e.getKey(), jsonNodeToValue(e.getValue())));
            return result;
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Span attributes 反序列化失败", ex);
        }
    }

    static String serialize(Trace trace) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("runtimeId", trace.runtimeId());
            if (trace.conversationId() != null) {
                root.put("conversationId", trace.conversationId());
            } else {
                root.putNull("conversationId");
            }
            ArrayNode spans = root.putArray("spans");
            // spans 按 startTime 排序输出，保证稳定
            List<Span> sorted = new ArrayList<>(trace.spans());
            sorted.sort(Comparator.comparingLong(Span::startTimeMs));
            for (Span span : sorted) {
                spans.add(serializeSpan(span));
            }
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Trace 序列化失败", e);
        }
    }

    private static ObjectNode serializeSpan(Span span) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("spanId", span.spanId());
        if (span.parentSpanId() != null) {
            node.put("parentSpanId", span.parentSpanId());
        } else {
            node.putNull("parentSpanId");
        }
        node.put("runtimeId", span.runtimeId());
        node.put("type", span.type());
        node.put("name", span.name() != null ? span.name() : "");
        node.put("startTimeMs", span.startTimeMs());
        node.put("endTimeMs", span.endTimeMs());
        node.put("status", span.status() != null ? span.status() : "");
        if (span.error() != null) {
            node.put("error", span.error());
        } else {
            node.putNull("error");
        }
        ObjectNode attrs = node.putObject("attributes");
        for (Map.Entry<String, Object> e : span.attributes().entrySet()) {
            attrs.set(e.getKey(), toJsonValue(e.getValue()));
        }
        return node;
    }

    /** 把 attributes 的一个值转成 JsonNode（按实际类型分派）。 */
    @SuppressWarnings("unchecked")
    private static JsonNode toJsonValue(Object value) {
        if (value == null) {
            return MAPPER.getNodeFactory().nullNode();
        }
        if (value instanceof Message) {
            return parseMessage((Message) value);
        }
        if (value instanceof List) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (Object item : (List<?>) value) {
                arr.add(toJsonValue(item));
            }
            return arr;
        }
        if (value instanceof Tool) {
            return toolSummary((Tool) value);
        }
        if (value instanceof ToolCall) {
            return toolCallNode((ToolCall) value);
        }
        if (value instanceof Map) {
            try {
                return MAPPER.valueToTree(value);
            } catch (IllegalArgumentException ex) {
                return MAPPER.getNodeFactory().textNode(value.toString());
            }
        }
        if (value instanceof Number) {
            return MAPPER.valueToTree(value);
        }
        if (value instanceof Boolean) {
            return MAPPER.getNodeFactory().booleanNode((Boolean) value);
        }
        // String / 其它 → 字符串
        return MAPPER.getNodeFactory().textNode(value.toString());
    }

    private static JsonNode parseMessage(Message msg) {
        try {
            return MAPPER.readTree(MessageSerializer.serialize(msg));
        } catch (JsonProcessingException e) {
            return MAPPER.getNodeFactory().textNode(msg.toString());
        }
    }

    private static ObjectNode toolSummary(Tool tool) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", tool.name());
        if (tool.description() != null) {
            node.put("description", tool.description());
        }
        return node;
    }

    private static ObjectNode toolCallNode(ToolCall tc) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", tc.id());
        node.put("name", tc.name());
        node.put("arguments", tc.arguments());
        return node;
    }

    static Trace deserialize(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String runtimeId = root.get("runtimeId").asText();
            String conversationId = root.has("conversationId") && !root.get("conversationId").isNull()
                    ? root.get("conversationId").asText() : null;
            List<Span> spans = new ArrayList<>();
            JsonNode spansNode = root.get("spans");
            if (spansNode != null) {
                for (JsonNode sNode : spansNode) {
                    spans.add(deserializeSpan(sNode));
                }
            }
            return new Trace(runtimeId, conversationId, spans);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Trace 反序列化失败", e);
        }
    }

    private static Span deserializeSpan(JsonNode node) {
        String spanId = node.get("spanId").asText();
        String parent = node.has("parentSpanId") && !node.get("parentSpanId").isNull()
                ? node.get("parentSpanId").asText() : null;
        String runtimeId = node.get("runtimeId").asText();
        String type = node.get("type").asText();
        String name = node.has("name") ? node.get("name").asText() : "";
        long start = node.get("startTimeMs").asLong();
        long end = node.get("endTimeMs").asLong();
        String status = node.has("status") ? node.get("status").asText() : "ok";
        String error = node.has("error") && !node.get("error").isNull() ? node.get("error").asText() : null;

        Span span = new Span(spanId, parent, runtimeId, type, name, start);
        span.end(end);
        if ("error".equals(status)) {
            span.endWithError(end, error);
        }
        JsonNode attrs = node.get("attributes");
        if (attrs != null) {
            attrs.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                span.putAttribute(e.getKey(), jsonNodeToValue(v));
            });
        }
        return span;
    }

    /** attributes 反序列化：原样还原成 Java 类型（Map/List/String/Number/Boolean）。 */
    private static Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return MAPPER.convertValue(node, Map.class);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(jsonNodeToValue(item));
            }
            return list;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }
}
