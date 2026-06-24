package com.non.chain;

import com.non.chain.tool.ToolCall;

import java.util.List;

public class Message {

    private final String role;
    private final String content;
    private final List<ContentPart> contentParts;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;
    /**
     * 是否进入 LLM 上下文。默认 true；应用层消息（如 UI 状态、通知）为 false，
     * 在 LLM 边界被剥离，不进 provider 请求。
     */
    private final boolean llmVisible;
    /**
     * 可选语义标签。null 表示普通 LLM 消息；应用层消息可自由打标签
     * （如 "note"/"status"/"ui"），仅供 UI/应用层区分语义。
     */
    private final String kind;

    public Message(String role, String content) {
        this(role, content, null, null, null, true, null);
    }

    private Message(String role, String content, List<ContentPart> contentParts,
                    String toolCallId, List<ToolCall> toolCalls,
                    boolean llmVisible, String kind) {
        this.role = role;
        this.content = content;
        this.contentParts = contentParts;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls;
        this.llmVisible = llmVisible;
        this.kind = kind;
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

    public boolean llmVisible() {
        return llmVisible;
    }

    public String kind() {
        return kind;
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
        return new Message("user", null, contentParts, null, null, true, null);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    /**
     * 带工具调用的 assistant 消息
     */
    public static Message assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content != null ? content : "", null, null, toolCalls, true, null);
    }

    /**
     * 工具执行结果消息
     */
    public static Message toolResult(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId, null, true, null);
    }

    /**
     * 应用层消息：不进 LLM 上下文，role 固定为 "note"。
     *
     * <p>用于记录 UI-only 状态（"正在思考"、"已读取文件 X"）进对话 transcript，
     * 供 UI 重放，同时在 LLM 边界被剥离。{@code kind} 为应用自定义语义标签，
     * 如 "status"/"ui"/"artifact"，框架只按 {@link #llmVisible()} 过滤，
     * {@code kind} 仅供 UI/应用层区分。</p>
     */
    public static Message note(String kind, String content) {
        return new Message("note", content, null, null, null, false, kind);
    }

    /**
     * 从完整参数构造消息，用于反序列化等场景（5 参向后兼容版本）。
     *
     * <p>产出 LLM 可见的普通消息（llmVisible=true, kind=null）。</p>
     */
    public static Message of(String role, String content, List<ContentPart> contentParts,
                             String toolCallId, List<ToolCall> toolCalls) {
        return new Message(role, content, contentParts, toolCallId, toolCalls, true, null);
    }

    /**
     * 从完整参数构造消息，用于反序列化等场景（含应用层消息标记）。
     */
    public static Message of(String role, String content, List<ContentPart> contentParts,
                             String toolCallId, List<ToolCall> toolCalls,
                             boolean llmVisible, String kind) {
        return new Message(role, content, contentParts, toolCallId, toolCalls, llmVisible, kind);
    }
}
