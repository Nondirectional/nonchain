package com.non.chain.provider;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.tool.Tool;

import java.util.List;

public interface LLM {

    /**
     * 发送消息并获取回复
     *
     * @param systemMessage 系统提示消息，可为 null
     * @param userMessage   用户消息
     * @return 聊天结果（含思考和回复内容）
     */
    default ChatResult chat(String systemMessage, String userMessage) {
        return chat(systemMessage, userMessage, OutputFormat.TEXT);
    }

    /**
     * 发送多轮对话消息并获取回复
     *
     * @param messages 消息列表
     * @return 聊天结果（含思考和回复内容）
     */
    default ChatResult chat(List<Message> messages) {
        return chat(messages, OutputFormat.TEXT);
    }

    /**
     * 发送消息并获取回复（带工具定义）
     *
     * @param systemMessage 系统提示消息，可为 null
     * @param userMessage   用户消息
     * @param tools         可用工具列表
     * @return 聊天结果（可能包含工具调用）
     */
    default ChatResult chat(String systemMessage, String userMessage, List<Tool> tools) {
        return chat(systemMessage, userMessage, tools, OutputFormat.TEXT);
    }

    /**
     * 发送多轮对话消息并获取回复（带工具定义）
     *
     * @param messages 消息列表
     * @param tools    可用工具列表
     * @return 聊天结果（可能包含工具调用）
     */
    default ChatResult chat(List<Message> messages, List<Tool> tools) {
        return chat(messages, tools, OutputFormat.TEXT);
    }

    /**
     * 发送消息并获取回复（指定输出格式）
     */
    ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat);

    /**
     * 发送多轮对话消息并获取回复（指定输出格式）
     */
    ChatResult chat(List<Message> messages, OutputFormat outputFormat);

    /**
     * 发送消息并获取回复（带工具定义 + 指定输出格式）
     */
    ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat);

    /**
     * 发送多轮对话消息并获取回复（带工具定义 + 指定输出格式）
     */
    ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat);
}
