package com.non.chain.callback;

/**
 * 共享上下文，持有 ChainCallback 引用，可注入到各组件中。
 *
 * <pre>{@code
 * ChainContext ctx = ChainContext.builder()
 *     .callback(new LoggingCallback())
 *     .build();
 *
 * Agent agent = Agent.builder(llm, registry)
 *     .chainContext(ctx)
 *     .build();
 * }</pre>
 */
public class ChainContext {

    private final ChainCallback callback;

    private ChainContext(Builder builder) {
        this.callback = builder.callback != null ? builder.callback : ChainCallbackUtil.noop();
    }

    /**
     * 获取回调实例（永不为 null）
     */
    public ChainCallback callback() {
        return callback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChainCallback callback;

        private Builder() {}

        public Builder callback(ChainCallback callback) {
            this.callback = callback;
            return this;
        }

        public ChainContext build() {
            return new ChainContext(this);
        }
    }
}
