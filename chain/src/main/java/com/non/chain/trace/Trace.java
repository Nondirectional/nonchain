package com.non.chain.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次顶层执行的完整 span 树（扁平列表）。
 *
 * <p>{@code runtimeId} = 根 span 的 spanId，同棵树共享。{@code spans} 是扁平列表（按 startTime 排序），
 * 取回方/可视化端自行按 {@code parentSpanId} 重建树。{@code conversationId} 为次级聚合层，可空。</p>
 *
 * <p>序列化：{@link #toJson()} / {@link #fromJson(String)} 委托 {@link TraceSerializer}，
 * 产出稳定 JSON 结构（便于将来外部 store 建 {@code spans} 表）。</p>
 */
public final class Trace {

    private final String runtimeId;
    private final String conversationId;
    private final List<Span> spans;

    public Trace(String runtimeId, String conversationId, List<Span> spans) {
        this.runtimeId = runtimeId;
        this.conversationId = conversationId;
        this.spans = spans != null ? Collections.unmodifiableList(new ArrayList<>(spans)) : Collections.emptyList();
    }

    public String runtimeId() {
        return runtimeId;
    }

    public String conversationId() {
        return conversationId;
    }

    public List<Span> spans() {
        return spans;
    }

    /** 序列化为 JSON（委托 {@link TraceSerializer}）。 */
    public String toJson() {
        return TraceSerializer.serialize(this);
    }

    /** 从 JSON 反序列化（委托 {@link TraceSerializer}）。 */
    public static Trace fromJson(String json) {
        return TraceSerializer.deserialize(json);
    }

    @Override
    public String toString() {
        return "Trace{runtimeId=" + runtimeId + ", conversationId=" + conversationId
                + ", spans=" + spans.size() + "}";
    }
}
