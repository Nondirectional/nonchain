package com.non.chain.callback.event;

/**
 * LLM 调用的 token 用量
 */
public class TokenUsage {

    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }

    @Override
    public String toString() {
        return "TokenUsage{prompt=" + promptTokens + ", completion=" + completionTokens + ", total=" + totalTokens + "}";
    }
}
