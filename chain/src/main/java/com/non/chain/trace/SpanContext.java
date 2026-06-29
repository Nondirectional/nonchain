package com.non.chain.trace;

import java.util.Objects;

/**
 * 不可变 span 上下文值对象，用于跨线程 / 跨 Agent 传递「当前 span」。
 *
 * <p>它是传播机制的真相源（design.md §4）：ThreadLocal current-span 栈里存的就是本对象，
 * 三处边界（SubAgent 构建点、并行工具 worker、Flow 节点）通过显式捕获 {@code SpanContext}
 * 跨越线程/代理边界。</p>
 *
 * <ul>
 *   <li>{@code runtimeId}：同棵树共享，等于根 span 的 spanId。</li>
 *   <li>{@code spanId}：当前 span 的 id。</li>
 *   <li>{@code parentSpanId}：父 span 的 id，根 span 为 {@code null}。</li>
 * </ul>
 */
public final class SpanContext {

    private final String runtimeId;
    private final String spanId;
    private final String parentSpanId;

    public SpanContext(String runtimeId, String spanId, String parentSpanId) {
        this.runtimeId = runtimeId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
    }

    public String runtimeId() {
        return runtimeId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpanContext)) return false;
        SpanContext that = (SpanContext) o;
        return Objects.equals(runtimeId, that.runtimeId)
                && Objects.equals(spanId, that.spanId)
                && Objects.equals(parentSpanId, that.parentSpanId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runtimeId, spanId, parentSpanId);
    }

    @Override
    public String toString() {
        return "SpanContext{runtimeId=" + runtimeId + ", spanId=" + spanId
                + ", parentSpanId=" + parentSpanId + "}";
    }
}
