package com.non.chain;

import com.non.chain.callback.event.TokenUsage;
import com.non.chain.tool.ToolCall;

import java.util.Collections;
import java.util.List;

public class ChatResult {

    private final String content;
    private final String thinkingContent;
    private final List<ToolCall> toolCalls;
    private final TokenUsage tokenUsage;
    /**
     * 本次执行链路遥测的 runtime id（可空）。仅在启用 trace 录制时填充，
     * 用于从 {@code TraceStore} 拉回完整 span 树。未启用录制时为 null。
     */
    private final String runtimeId;
    /**
     * 本次执行所用 LLM 的上下文窗口大小（tokens）。仅作元数据透出给应用层，
     * 默认 -1 表示未知（LLM 未声明）。由 Agent 在 run 结束时从 llm 实例回填。
     */
    private final long contextWindow;

    public ChatResult(String content, String thinkingContent) {
        this(content, thinkingContent, Collections.emptyList(), null);
    }

    public ChatResult(String content, String thinkingContent, List<ToolCall> toolCalls) {
        this(content, thinkingContent, toolCalls, null);
    }

    public ChatResult(String content, String thinkingContent, List<ToolCall> toolCalls, TokenUsage tokenUsage) {
        this(content, thinkingContent, toolCalls, tokenUsage, null);
    }

    /**
     * 带执行链路 runtime id 的构造器（trace 录制启用时由 Agent 内部使用，纯新增）。
     */
    public ChatResult(String content, String thinkingContent, List<ToolCall> toolCalls,
                      TokenUsage tokenUsage, String runtimeId) {
        this(content, thinkingContent, toolCalls, tokenUsage, runtimeId, -1L);
    }

    /**
     * 全参构造器：同时带 runtimeId 与 contextWindow。Agent 收尾时用此构造器
     * 把 LLM 实例上声明的 contextWindow 透出到 ChatResult。
     */
    public ChatResult(String content, String thinkingContent, List<ToolCall> toolCalls,
                      TokenUsage tokenUsage, String runtimeId, long contextWindow) {
        this.content = content;
        this.thinkingContent = thinkingContent;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.tokenUsage = tokenUsage;
        this.runtimeId = runtimeId;
        this.contextWindow = contextWindow;
    }

    public String content() {
        return content;
    }

    public String thinkingContent() {
        return thinkingContent;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public TokenUsage tokenUsage() {
        return tokenUsage;
    }

    /**
     * 本次执行的 trace runtime id（可空）。仅在启用 trace 录制时填充；
     * 未启用录制时为 null。配合 {@code TraceStore.getTrace(runtimeId)} 拉回整棵 span 树。
     */
    public String runtimeId() {
        return runtimeId;
    }

    /**
     * 本次执行所用 LLM 的上下文窗口大小（tokens）。-1 表示未声明。
     * 应用层可结合 {@link #tokenUsage()} 的 promptTokens 计算「已用/上限」占比。
     */
    public long contextWindow() {
        return contextWindow;
    }

    /**
     * 返回带指定 runtime id 的副本（内部用，保留 contextWindow 等其它字段）。
     */
    public ChatResult withRuntimeId(String runtimeId) {
        return new ChatResult(content, thinkingContent, toolCalls, tokenUsage, runtimeId, contextWindow);
    }

    /**
     * 返回带指定 contextWindow 的副本（内部用，不改变其它字段）。
     */
    public ChatResult withContextWindow(long contextWindow) {
        return new ChatResult(content, thinkingContent, toolCalls, tokenUsage, runtimeId, contextWindow);
    }

    public boolean hasThinking() {
        return thinkingContent != null && !thinkingContent.isBlank();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 自动转换为 Message：有工具调用则生成带 toolCalls 的 assistant 消息，否则生成普通 assistant 消息
     */
    public Message toMessage() {
        if (hasToolCalls()) {
            return Message.assistantWithToolCalls(content, toolCalls);
        }
        return Message.assistant(content);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (hasThinking()) {
            sb.append("[思考]\n").append(thinkingContent).append("\n\n");
        }
        if (hasToolCalls()) {
            sb.append("[工具调用]\n");
            for (ToolCall tc : toolCalls) {
                sb.append("  ").append(tc).append("\n");
            }
        }
        sb.append("[回复]\n").append(content);
        return sb.toString();
    }
}
