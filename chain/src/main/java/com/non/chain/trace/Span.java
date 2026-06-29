package com.non.chain.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTel 风格的 span：强类型骨架 + schemaless attributes 载荷。
 *
 * <p><b>生命周期</b>：span 是「构建期可变、record 后定稿」的对象，由 {@link Tracer.ScopedSpan}
 * 管理——创建时只有骨架字段，运行期通过 {@code putAttribute} 补全载荷，{@code ScopedSpan.close()}
 * 时写 {@code endTime/status/error} 并 {@link TraceStore#record}。</p>
 *
 * <p><b>强类型骨架</b>：spanId / parentSpanId / runtimeId / type / name / startTimeMs / endTimeMs /
 * status / error。这些是每棵 span 树通用的、需要建表/排序的稳定列。</p>
 *
 * <p><b>attributes 载荷</b>：按 type 约定 key（见 {@link SpanAttributes}），值类型约定：
 * messages → {@code List<Message>}（序列化时转结构化 JSON）、result/arguments → String、
 * tokens/latency → Long、state → {@code Map}。内部用 {@code Map<String,Object>}，
 * JSON 序列化靠既有 Jackson。</p>
 */
public final class Span {

    // ---- 强类型骨架（创建时定） ----
    private final String spanId;
    private final String parentSpanId;
    private final String runtimeId;
    private final String type;
    private final String name;
    private final long startTimeMs;

    // ---- 定稿字段（close 时写） ----
    private long endTimeMs;
    private String status;     // "ok" / "error"；未 close 时为 null
    private String error;      // status=error 时的消息，可空

    // ---- 载荷（schemaless，按 type 约定 key） ----
    private final Map<String, Object> attributes;

    public Span(String spanId, String parentSpanId, String runtimeId,
                String type, String name, long startTimeMs) {
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.runtimeId = runtimeId;
        this.type = type;
        this.name = name;
        this.startTimeMs = startTimeMs;
        this.attributes = new LinkedHashMap<>();
        this.status = null;
        this.error = null;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public String runtimeId() {
        return runtimeId;
    }

    public String type() {
        return type;
    }

    public String name() {
        return name;
    }

    public long startTimeMs() {
        return startTimeMs;
    }

    public long endTimeMs() {
        return endTimeMs;
    }

    public String status() {
        return status;
    }

    public String error() {
        return error;
    }

    /** 载荷视图，只读。 */
    public Map<String, Object> attributes() {
        return java.util.Collections.unmodifiableMap(attributes);
    }

    /** 写一个载荷字段（构建期）。 */
    public void putAttribute(String key, Object value) {
        if (key != null) {
            attributes.put(key, value);
        }
    }

    /** 批量写载荷字段（构建期）。 */
    public void putAllAttributes(Map<String, Object> map) {
        if (map != null) {
            attributes.putAll(map);
        }
    }

    /** close 时由 Tracer 调用：写 endTime + ok 状态。 */
    void end(long endTimeMs) {
        this.endTimeMs = endTimeMs;
        if (this.status == null) {
            this.status = "ok";
        }
    }

    /** close 时由 Tracer 调用：写 endTime + error 状态 + 错误消息。 */
    void endWithError(long endTimeMs, String errorMessage) {
        this.endTimeMs = endTimeMs;
        this.status = "error";
        this.error = errorMessage;
    }

    /**
     * 从持久化存储重建一个已定稿的 Span（供 {@code TraceStore} 实现的 getTrace 用）。
     *
     * <p>与构建期 span 不同：重建的 span 直接带上定稿的 endTime/status/error/attributes，
     * 不再经过 {@link #end}/{@link #endWithError} 流程。仅用于读回已落库数据，不应再用于录制。</p>
     *
     * @param spanId        span id
     * @param parentSpanId  父 span id（根 span 为 null）
     * @param runtimeId     runtime id
     * @param type          span 类型
     * @param name          span 名称
     * @param startTimeMs   起始时间
     * @param endTimeMs     结束时间
     * @param status        状态（"ok"/"error"）
     * @param error         错误消息（status=error 时，可空）
     * @param attributes    载荷（可空）
     */
    public static Span restored(String spanId, String parentSpanId, String runtimeId,
                                String type, String name,
                                long startTimeMs, long endTimeMs,
                                String status, String error,
                                java.util.Map<String, Object> attributes) {
        Span span = new Span(spanId, parentSpanId, runtimeId, type, name, startTimeMs);
        span.endTimeMs = endTimeMs;
        span.status = status != null ? status : "ok";
        span.error = error;
        if (attributes != null) {
            span.attributes.putAll(attributes);
        }
        return span;
    }

    @Override
    public String toString() {
        return "Span{spanId=" + spanId + ", parent=" + parentSpanId + ", runtime=" + runtimeId
                + ", type=" + type + ", name=" + name + ", status=" + status + "}";
    }
}
