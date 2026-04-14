package com.non.chain.callback.event;

/**
 * 工具执行成功后触发的事件（含耗时）
 */
public class ToolCompleteEvent {

    private final String traceId;
    private final String toolCallId;
    private final String toolName;
    private final String result;
    private final long latencyMs;

    public ToolCompleteEvent(String traceId, String toolCallId, String toolName, String result, long latencyMs) {
        this.traceId = traceId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.result = result;
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

    public String result() {
        return result;
    }

    public long latencyMs() {
        return latencyMs;
    }
}
