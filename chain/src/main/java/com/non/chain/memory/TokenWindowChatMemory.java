package com.non.chain.memory;

import com.non.chain.Message;

import java.util.List;

/**
 * 基于 Token 数量的裁剪策略。
 *
 * <p>当消息总 token 数超过限制时，从最老的消息开始裁剪。
 * SystemMessage 永不裁剪，工具消息配对（assistant + tool result）保持完整。</p>
 *
 * <pre>{@code
 * ChatMemory memory = TokenWindowChatMemory.builder()
 *     .store(new InMemoryChatMemoryStore())
 *     .tokenizer(JtokkitTokenizer.defaults())
 *     .maxTokens(4096)
 *     .conversationId("user-1")
 *     .build();
 *
 * memory.add(Message.user("你好"));
 * List<Message> history = memory.messages(); // token 数 <= maxTokens
 * }</pre>
 */
public class TokenWindowChatMemory implements ChatMemory {

    private final ChatMemoryStore store;
    private final Tokenizer tokenizer;
    private final int maxTokens;
    private final String conversationId;

    private TokenWindowChatMemory(Builder builder) {
        this.store = builder.store;
        this.tokenizer = builder.tokenizer;
        this.maxTokens = builder.maxTokens;
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
     * 裁剪消息列表直到总 token 数 <= maxTokens。
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
        while (tokenizer.estimateTokenCount(messages) > maxTokens) {
            int deleteIndex = findFirstDeletableIndex(messages);
            if (deleteIndex < 0) {
                break;
            }
            int deleteCount = countMessageGroup(messages, deleteIndex);
            for (int i = 0; i < deleteCount && !messages.isEmpty() && deleteIndex < messages.size(); i++) {
                messages.remove(deleteIndex);
            }
            // 如果删除后只剩 system 消息，停止
            if (messages.size() <= 1 && !messages.isEmpty() && "system".equals(messages.get(0).role())) {
                break;
            }
        }
    }

    private int findFirstDeletableIndex(List<Message> messages) {
        return ChatMemoryTrimSupport.findFirstDeletableIndex(messages);
    }

    private int countMessageGroup(List<Message> messages, int index) {
        return ChatMemoryTrimSupport.countMessageGroup(messages, index);
    }

    public static class Builder {
        private ChatMemoryStore store;
        private Tokenizer tokenizer;
        private int maxTokens = 4096;
        private String conversationId;

        public Builder store(ChatMemoryStore store) {
            this.store = store;
            return this;
        }

        public Builder tokenizer(Tokenizer tokenizer) {
            this.tokenizer = tokenizer;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public TokenWindowChatMemory build() {
            if (store == null) {
                store = new InMemoryChatMemoryStore();
            }
            if (tokenizer == null) {
                tokenizer = JtokkitTokenizer.defaults();
            }
            if (conversationId == null || conversationId.isEmpty()) {
                conversationId = "default";
            }
            return new TokenWindowChatMemory(this);
        }
    }
}
