package com.non.chain.callback.event;

import com.non.chain.Message;
import com.non.chain.tool.Tool;

import java.util.Collections;
import java.util.List;

/**
 * LLM 调用失败时触发的事件
 */
public class LlmErrorEvent {

    private final String traceId;
    private final List<Message> messages;
    private final List<Tool> tools;
    private final Throwable error;
    private final long latencyMs;

    public LlmErrorEvent(String traceId, List<Message> messages, List<Tool> tools, Throwable error, long latencyMs) {
        this.traceId = traceId;
        this.messages = messages != null ? Collections.unmodifiableList(messages) : Collections.emptyList();
        this.tools = tools != null ? Collections.unmodifiableList(tools) : Collections.emptyList();
        this.error = error;
        this.latencyMs = latencyMs;
    }

    public String traceId() {
        return traceId;
    }

    public List<Message> messages() {
        return messages;
    }

    public List<Tool> tools() {
        return tools;
    }

    public Throwable error() {
        return error;
    }

    public long latencyMs() {
        return latencyMs;
    }
}
