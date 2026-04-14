package com.non.chain.callback.event;

import com.non.chain.knowledge.RetrievalResponse;

/**
 * 检索完成后触发的事件（含命中数、耗时）
 */
public class RetrievalCompleteEvent {

    private final String traceId;
    private final RetrievalResponse response;
    private final int hitCount;
    private final long latencyMs;

    public RetrievalCompleteEvent(String traceId, RetrievalResponse response, int hitCount, long latencyMs) {
        this.traceId = traceId;
        this.response = response;
        this.hitCount = hitCount;
        this.latencyMs = latencyMs;
    }

    public String traceId() {
        return traceId;
    }

    public RetrievalResponse response() {
        return response;
    }

    public int hitCount() {
        return hitCount;
    }

    public long latencyMs() {
        return latencyMs;
    }
}
