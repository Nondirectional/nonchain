package com.non.chain.memory;

import com.non.chain.Message;

import java.util.ArrayList;
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
     *   <li>assistant 消息带 toolCalls 时，紧随其后的 tool 消息与之配对，
     *       删除时必须一起删除</li>
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
        int start = isSystemMessage(messages, 0) ? 1 : 0;
        if (start >= messages.size()) {
            return -1;
        }
        return start;
    }

    /**
     * 计算从 index 开始的消息组大小。
     *
     * <p>如果该消息是带 toolCalls 的 assistant 消息，
     * 则包含紧随其后的所有 tool 消息（保持配对完整性）。</p>
     */
    private int countMessageGroup(List<Message> messages, int index) {
        Message msg = messages.get(index);
        // 如果是带工具调用的 assistant 消息
        if ("assistant".equals(msg.role()) && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            int count = 1;
            // 统计紧随其后的 tool 消息数量
            for (int i = index + 1; i < messages.size(); i++) {
                if ("tool".equals(messages.get(i).role())) {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        }
        // 如果是 tool 消息（无对应 assistant），也单独处理
        return 1;
    }

    private boolean isSystemMessage(List<Message> messages, int index) {
        return index == 0 && !messages.isEmpty() && "system".equals(messages.get(0).role());
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
