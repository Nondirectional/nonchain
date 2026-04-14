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

    public ChatResult(String content, String thinkingContent) {
        this(content, thinkingContent, Collections.emptyList(), null);
    }

    public ChatResult(String content, String thinkingContent, List<ToolCall> toolCalls) {
        this(content, thinkingContent, toolCalls, null);
    }

    public ChatResult(String content, String thinkingContent, List<ToolCall> toolCalls, TokenUsage tokenUsage) {
        this.content = content;
        this.thinkingContent = thinkingContent;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.tokenUsage = tokenUsage;
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
