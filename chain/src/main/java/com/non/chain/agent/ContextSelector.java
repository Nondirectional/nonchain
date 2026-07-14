package com.non.chain.agent;

import com.non.chain.Message;

import java.util.List;

/**
 * 子代理上下文裁剪策略：从父 Agent 的消息链中选出应当注入子代理的消息切片。
 *
 * <p>首批仅支持在子代理注册时注入；未提供时使用框架默认裁剪策略
 * （框架最终会过滤 {@code llmVisible=false} 与所有父 system，且只保留完整的 user/assistant/tool
 * 调用组）。</p>
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
     * @return 注入子代理的父上下文消息切片（框架会进一步过滤 system/不可见消息并校验工具配对，
     *         不含子代理 systemPrompt 与 task 本身）
     */
    List<Message> select(List<Message> parentMessages, Message assistantMessage, String task);
}
