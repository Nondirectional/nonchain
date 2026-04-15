package com.non.chain.memory;

import com.non.chain.Message;

import java.util.List;

/**
 * 对话记忆存储接口。
 *
 * <p>抽象消息的读写和删除，与裁剪策略分离。
 * 内置 {@link InMemoryChatMemoryStore}，可扩展为 MySQL、Redis 等实现。</p>
 */
public interface ChatMemoryStore {

    /**
     * 获取指定对话的所有消息
     */
    List<Message> getMessages(String conversationId);

    /**
     * 更新指定对话的消息（全量替换）
     */
    void updateMessages(String conversationId, List<Message> messages);

    /**
     * 删除指定对话的所有消息
     */
    void deleteMessages(String conversationId);
}
