package com.non.chain.provider;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.tool.Tool;

import java.util.List;
import java.util.function.Consumer;

public interface LLM {

    // ---- 同步调用 ----

    /**
     * 发送消息并获取回复
     */
    default ChatResult chat(String systemMessage, String userMessage) {
        return chat(systemMessage, userMessage, OutputFormat.TEXT);
    }

    /**
     * 发送多轮对话消息并获取回复
     */
    default ChatResult chat(List<Message> messages) {
        return chat(messages, OutputFormat.TEXT);
    }

    /**
     * 发送消息并获取回复（带工具定义）
     */
    default ChatResult chat(String systemMessage, String userMessage, List<Tool> tools) {
        return chat(systemMessage, userMessage, tools, OutputFormat.TEXT);
    }

    /**
     * 发送多轮对话消息并获取回复（带工具定义）
     */
    default ChatResult chat(List<Message> messages, List<Tool> tools) {
        return chat(messages, tools, OutputFormat.TEXT);
    }

    ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat);

    ChatResult chat(List<Message> messages, OutputFormat outputFormat);

    ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat);

    ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat);

    // ---- 流式调用 ----

    /**
     * 流式发送消息并获取回复
     */
    default ChatResult streamChat(String systemMessage, String userMessage, Consumer<ChatChunk> callback) {
        return streamChat(systemMessage, userMessage, OutputFormat.TEXT, callback);
    }

    /**
     * 流式发送多轮对话消息并获取回复
     */
    default ChatResult streamChat(List<Message> messages, Consumer<ChatChunk> callback) {
        return streamChat(messages, OutputFormat.TEXT, callback);
    }

    /**
     * 流式发送消息并获取回复（带工具定义）
     */
    default ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, Consumer<ChatChunk> callback) {
        return streamChat(systemMessage, userMessage, tools, OutputFormat.TEXT, callback);
    }

    /**
     * 流式发送多轮对话消息并获取回复（带工具定义）
     */
    default ChatResult streamChat(List<Message> messages, List<Tool> tools, Consumer<ChatChunk> callback) {
        return streamChat(messages, tools, OutputFormat.TEXT, callback);
    }

    ChatResult streamChat(String systemMessage, String userMessage, OutputFormat outputFormat, Consumer<ChatChunk> callback);

    ChatResult streamChat(List<Message> messages, OutputFormat outputFormat, Consumer<ChatChunk> callback);

    ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback);

    ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback);
}
