package com.non.chain.callback.event;

import com.non.chain.Message;
import com.non.chain.tool.Tool;

import java.util.Collections;
import java.util.List;

/**
 * LLM 调用前触发的事件
 */
public class LlmStartEvent {

    private final String traceId;
    private final List<Message> messages;
    private final List<Tool> tools;

    public LlmStartEvent(String traceId, List<Message> messages, List<Tool> tools) {
        this.traceId = traceId;
        this.messages = messages != null ? Collections.unmodifiableList(messages) : Collections.emptyList();
        this.tools = tools != null ? Collections.unmodifiableList(tools) : Collections.emptyList();
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
}
