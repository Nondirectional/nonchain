package com.non.chain.memory;

import com.non.chain.Message;

import java.util.List;

/**
 * 对话记忆策略接口。
 *
 * <p>负责管理对话历史的裁剪策略（如滑动窗口、Token 限制等），
 * 内部组合 {@link ChatMemoryStore} 实现持久化。</p>
 *
 * <pre>{@code
 * ChatMemory memory = MessageWindowChatMemory.builder()
 *     .store(new InMemoryChatMemoryStore())
 *     .maxMessages(20)
 *     .conversationId("user-1")
 *     .build();
 *
 * memory.add(Message.user("你好"));
 * List<Message> history = memory.messages();
 * memory.clear();
 * }</pre>
 */
public interface ChatMemory {

    /**
     * 对话标识，用于区分不同会话
     */
    String conversationId();

    /**
     * 添加单条消息到记忆中
     */
    void add(Message message);

    /**
     * 批量添加消息到记忆中
     */
    void addAll(List<Message> messages);

    /**
     * 获取当前所有消息（经过裁剪策略处理后的）
     */
    List<Message> messages();

    /**
     * 清除所有消息
     */
    void clear();
}
