package com.non.chain.provider;

import com.non.chain.ChatResult;
import com.non.chain.Message;
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
