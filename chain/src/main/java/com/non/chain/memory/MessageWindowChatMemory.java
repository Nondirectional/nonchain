package com.non.chain.memory;

import com.non.chain.Message;

import java.util.List;

/**
 * 滑动窗口记忆策略。
 *
 * <p>保留最近 N 条消息。裁剪时保护 SystemMessage（永不删除）和
 * 工具消息配对（assistant + tool result 成对保留/删除）。</p>
 *
 * <pre>{@code
 * ChatMemory memory = MessageWindowChatMemory.builder()
 *     .store(new InMemoryChatMemoryStore())
 *     .maxMessages(20)
 *     .conversationId("user-1")
 *     .build();
 *
 * memory.add(Message.user("你好"));
 * memory.add(Message.assistant("你好！有什么可以帮你？"));
 * List<Message> history = memory.messages(); // 2 条消息
 * }</pre>
 */
public class MessageWindowChatMemory implements ChatMemory {

    private final ChatMemoryStore store;
    private final int maxMessages;
    private final String conversationId;

    private MessageWindowChatMemory(Builder builder) {
        this.store = builder.store;
        this.maxMessages = builder.maxMessages;
        this.conversationId = builder.conversationId;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String conversationId() {
        return conversationId;
    }

    @Override
    public void add(Message message) {
        List<Message> messages = store.getMessages(conversationId);
        messages.add(message);
        trim(messages);
        store.updateMessages(conversationId, messages);
    }

    @Override
    public void addAll(List<Message> newMessages) {
        List<Message> messages = store.getMessages(conversationId);
        messages.addAll(newMessages);
        trim(messages);
        store.updateMessages(conversationId, messages);
    }

    @Override
    public List<Message> messages() {
        return store.getMessages(conversationId);
    }

    @Override
    public void clear() {
        store.deleteMessages(conversationId);
    }

    /**
     * 裁剪消息列表到 maxMessages 以内。
     *
     * <p>规则：</p>
     * <ol>
     *   <li>SystemMessage（索引 0 且 role="system"）永不删除</li>
     *   <li>assistant 消息带 toolCalls 时，相关 tool 消息应尽量成组保留/删除，
     *       包括异常顺序下的防御式配对</li>
     *   <li>从最老的非 system 消息开始删除</li>
     * </ol>
     */
    void trim(List<Message> messages) {
        while (messages.size() > maxMessages) {
            int deleteIndex = findFirstDeletableIndex(messages);
            if (deleteIndex < 0) {
                break;
            }
            // 检查是否是带工具调用的 assistant 消息，需要连同后续 tool 消息一起删除
            int deleteCount = countMessageGroup(messages, deleteIndex);
            for (int i = 0; i < deleteCount && deleteIndex < messages.size(); i++) {
                messages.remove(deleteIndex);
            }
        }
    }

    /**
     * 找到第一个可删除的消息索引（跳过索引 0 的 system 消息）
     */
    private int findFirstDeletableIndex(List<Message> messages) {
        return ChatMemoryTrimSupport.findFirstDeletableIndex(messages);
    }

    /**
     * 计算从 index 开始的消息组大小。
     *
     * <p>正常顺序下保护 assistant(toolCalls) + tool，异常顺序下也尝试将
     * 连续的 tool 与其后紧邻的 assistant(toolCalls) 作为一组删除。</p>
     */
    private int countMessageGroup(List<Message> messages, int index) {
        return ChatMemoryTrimSupport.countMessageGroup(messages, index);
    }

    public static class Builder {
        private ChatMemoryStore store;
        private int maxMessages = 20;
        private String conversationId;

        public Builder store(ChatMemoryStore store) {
            this.store = store;
            return this;
        }

        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public MessageWindowChatMemory build() {
            if (store == null) {
                store = new InMemoryChatMemoryStore();
            }
            if (conversationId == null || conversationId.isEmpty()) {
                conversationId = "default";
            }
            return new MessageWindowChatMemory(this);
        }
    }
}
