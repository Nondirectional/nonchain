package com.non.chain.agent;

import com.non.chain.Message;

import java.util.List;

/**
 * 子代理上下文裁剪策略：从父 Agent 的消息链中选出应当注入子代理的消息切片。
 *
 * <p>首批仅支持在子代理注册时注入；未提供时使用框架默认裁剪策略
 * （过滤 {@code llmVisible=false}、保留相关 user/assistant/tool、不含父 systemPrompt）。</p>
 *
 * <pre>{@code
 * registry.registerSubAgent("research", "调研")
 *         .contextSelector((parent, assistant, task) -> {
 *             // 只保留最近 4 条 LLM 可见消息
 *             return parent.stream().filter(Message::llmVisible).limit(4).collect(toList());
 *         })
 *         ...
 * }</pre>
 */
@FunctionalInterface
public interface ContextSelector {

    /**
     * @param parentMessages    父 Agent 当前轮的完整消息链快照（只读）
     * @param assistantMessage  触发本次委派的父 assistant 消息（含 toolCalls）
     * @param task              本次委派给子代理的任务文本
     * @return 注入子代理的父上下文消息切片（不含子代理 systemPrompt 与 task 本身）
     */
    List<Message> select(List<Message> parentMessages, Message assistantMessage, String task);
}
