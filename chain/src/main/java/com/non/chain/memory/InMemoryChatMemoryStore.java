package com.non.chain.memory;

import com.non.chain.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存存储实现。
 *
 * <p>消息存储在内存中，进程重启后丢失。适用于单次运行场景或测试。</p>
 */
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final ConcurrentHashMap<String, List<Message>> store = new ConcurrentHashMap<>();

    @Override
    public List<Message> getMessages(String conversationId) {
        List<Message> messages = store.get(conversationId);
        return messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    @Override
    public void updateMessages(String conversationId, List<Message> messages) {
        store.put(conversationId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(String conversationId) {
        store.remove(conversationId);
    }
}
