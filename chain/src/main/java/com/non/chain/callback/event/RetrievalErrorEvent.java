package com.non.chain.callback.event;

import com.non.chain.knowledge.SearchRequest;

/**
 * 检索失败时触发的事件
 */
public class RetrievalErrorEvent {

    private final String traceId;
    private final SearchRequest request;
    private final Throwable error;
    private final long latencyMs;

    public RetrievalErrorEvent(String traceId, SearchRequest request, Throwable error, long latencyMs) {
        this.traceId = traceId;
        this.request = request;
        this.error = error;
        this.latencyMs = latencyMs;
    }

    public String traceId() {
        return traceId;
    }

    public SearchRequest request() {
        return request;
    }

    public Throwable error() {
        return error;
    }

    public long latencyMs() {
        return latencyMs;
    }
}
