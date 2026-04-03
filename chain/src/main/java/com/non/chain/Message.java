package com.non.chain;

import com.non.chain.tool.ToolCall;

import java.util.List;

public class Message {

    private final String role;
    private final String content;
    private final List<ContentPart> contentParts;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    public Message(String role, String content) {
        this(role, content, null, null, null);
    }

    private Message(String role, String content, List<ContentPart> contentParts,
                    String toolCallId, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.contentParts = contentParts;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls;
    }

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }

    public List<ContentPart> contentParts() {
        return contentParts;
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

    /**
     * 多模态用户消息（文本 + 图片等）
     */
    public static Message user(List<ContentPart> contentParts) {
        return new Message("user", null, contentParts, null, null);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    /**
     * 带工具调用的 assistant 消息
     */
    public static Message assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content != null ? content : "", null, null, toolCalls);
    }

    /**
     * 工具执行结果消息
     */
    public static Message toolResult(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId, null);
    }
}
