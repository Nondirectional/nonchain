package com.non.chain.callback.event;

import com.non.chain.knowledge.SearchRequest;

/**
 * 检索开始前触发的事件
 */
public class RetrievalStartEvent {

    private final String traceId;
    private final SearchRequest request;

    public RetrievalStartEvent(String traceId, SearchRequest request) {
        this.traceId = traceId;
        this.request = request;
    }

    public String traceId() {
        return traceId;
    }

    public SearchRequest request() {
        return request;
    }
}
