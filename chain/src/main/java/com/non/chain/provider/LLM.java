package com.non.chain.provider;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.tool.Tool;

import java.util.List;
import java.util.function.Consumer;

public interface LLM {

    /**
     * 当前模型的 Chat Template 是否支持多条 system 消息。
     *
     * <p>默认 true，避免改变既有自定义 LLM 的行为；不支持的具体模型由调用方在实例上显式声明。</p>
     */
    default boolean supportsMultipleSystemMessages() {
        return true;
    }

    /**
     * 配置当前 LLM 实例的多 system 能力。
     *
     * <p>内置 provider 提供可链式实现；自定义 LLM 若需要运行时切换能力可覆写此方法。
     * 默认实现显式失败，避免静默忽略调用方的兼容性配置。</p>
     */
    default LLM supportsMultipleSystemMessages(boolean supported) {
        throw new UnsupportedOperationException("当前 LLM 不支持配置多 system 消息能力");
    }

    /**
     * 为一次 LLM 请求准备消息副本。
     *
     * <p>返回值可被 provider 修改/转换，调用方传入的 Agent transcript 不会被原地改写。</p>
     */
    default List<Message> prepareMessages(List<Message> messages) {
        return MessageNormalizer.normalizeForRequest(messages, supportsMultipleSystemMessages());
    }

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
