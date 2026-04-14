package com.non.chain.callback.event;

import com.non.chain.tool.ToolCall;

/**
 * 工具执行前触发的事件
 */
public class ToolStartEvent {

    private final String traceId;
    private final ToolCall toolCall;

    public ToolStartEvent(String traceId, ToolCall toolCall) {
        this.traceId = traceId;
        this.toolCall = toolCall;
    }

    public String traceId() {
        return traceId;
    }

    public ToolCall toolCall() {
        return toolCall;
    }
}
