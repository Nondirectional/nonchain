package com.non.chain;

import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;

import java.util.Collections;
import java.util.List;

public class ChatResult {

    private final String content;
    private final String thinkingContent;
    private final List<ToolCall> toolCalls;

    public ChatResult(String content, String thinkingContent) {
        this(content, thinkingContent, Collections.emptyList());
    }

    public ChatResult(String content, String thinkingContent, List<ToolCall> toolCalls) {
        this.content = content;
        this.thinkingContent = thinkingContent;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
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

    public static interface LLM {

        /**
         * 发送消息并获取回复
         *
         * @param systemMessage 系统提示消息，可为 null
         * @param userMessage   用户消息
         * @return 聊天结果（含思考和回复内容）
         */
        ChatResult chat(String systemMessage, String userMessage);

        /**
         * 发送多轮对话消息并获取回复
         *
         * @param messages 消息列表
         * @return 聊天结果（含思考和回复内容）
         */
        ChatResult chat(List<Message> messages);

        /**
         * 发送消息并获取回复（带工具定义）
         *
         * @param systemMessage 系统提示消息，可为 null
         * @param userMessage   用户消息
         * @param tools         可用工具列表
         * @return 聊天结果（可能包含工具调用）
         */
        ChatResult chat(String systemMessage, String userMessage, List<Tool> tools);

        /**
         * 发送多轮对话消息并获取回复（带工具定义）
         *
         * @param messages 消息列表
         * @param tools    可用工具列表
         * @return 聊天结果（可能包含工具调用）
         */
        ChatResult chat(List<Message> messages, List<Tool> tools);
    }
}
