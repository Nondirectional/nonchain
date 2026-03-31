package com.non.chain;

import com.non.chain.tool.ToolCall;

import java.util.List;

public class Message {

    private final String role;
    private final String content;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    public Message(String role, String content) {
        this(role, content, null, null);
    }

    private Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls;
    }

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    /**
     * 带工具调用的 assistant 消息
     */
    public static Message assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content != null ? content : "", null, toolCalls);
    }

    /**
     * 工具执行结果消息
     */
    public static Message toolResult(String toolCallId, String content) {
        return new Message("tool", content, toolCallId, null);
    }
}
