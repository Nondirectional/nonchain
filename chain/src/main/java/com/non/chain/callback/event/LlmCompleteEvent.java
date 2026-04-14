package com.non.chain.callback.event;

import com.non.chain.ChatResult;

/**
 * LLM 调用成功后触发的事件（含 token 用量、延迟）
 */
public class LlmCompleteEvent {

    private final String traceId;
    private final ChatResult result;
    private final TokenUsage tokenUsage;
    private final long latencyMs;

    public LlmCompleteEvent(String traceId, ChatResult result, TokenUsage tokenUsage, long latencyMs) {
        this.traceId = traceId;
        this.result = result;
        this.tokenUsage = tokenUsage;
        this.latencyMs = latencyMs;
    }

    public String traceId() {
        return traceId;
    }

    public ChatResult result() {
        return result;
    }

    public TokenUsage tokenUsage() {
        return tokenUsage;
    }

    public long latencyMs() {
        return latencyMs;
    }
}
