package com.non.chain.callback.event;

/**
 * 工具执行失败时触发的事件
 */
public class ToolErrorEvent {

    private final String traceId;
    private final String toolCallId;
    private final String toolName;
    private final String arguments;
    private final Throwable error;
    private final long latencyMs;

    public ToolErrorEvent(String traceId, String toolCallId, String toolName, String arguments, Throwable error, long latencyMs) {
        this.traceId = traceId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.error = error;
        this.latencyMs = latencyMs;
    }

    public String traceId() {
        return traceId;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String toolName() {
        return toolName;
    }

    public String arguments() {
        return arguments;
    }

    public Throwable error() {
        return error;
    }

    public long latencyMs() {
        return latencyMs;
    }
}
