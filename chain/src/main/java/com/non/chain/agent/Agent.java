package com.non.chain.agent;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.callback.ChainContext;
import com.non.chain.callback.ChainTrace;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.LlmErrorEvent;
import com.non.chain.callback.event.LlmStartEvent;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.callback.event.ToolErrorEvent;
import com.non.chain.callback.event.ToolStartEvent;
import com.non.chain.provider.LLM;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent：LLM + 工具循环
 *
 * <p>核心循环：发送消息+工具给 LLM → 检查工具调用 → 执行工具 → 追加结果 → 继续，
 * 直到模型不再调用工具或达到最大迭代次数。</p>
 *
 * <pre>{@code
 * Agent agent = Agent.builder(llm, toolRegistry)
 *     .systemPrompt("你是一个助手")
 *     .maxIterations(10)
 *     .callback(new LoggingCallback())
 *     .build();
 *
 * ChatResult result = agent.run("北京天气怎么样？");
 * System.out.println(result.content());
 * }</pre>
 */
public class Agent {

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final LLM llm;
    private final ToolRegistry toolRegistry;
    private final String systemPrompt;
    private final int maxIterations;
    private final ChainCallback callback;

    private Agent(Builder builder) {
        this.llm = builder.llm;
        this.toolRegistry = builder.toolRegistry;
        this.systemPrompt = builder.systemPrompt;
        this.maxIterations = builder.maxIterations;
        this.callback = builder.callback;
    }

    public static Builder builder(LLM llm, ToolRegistry toolRegistry) {
        return new Builder(llm, toolRegistry);
    }

    /**
     * 简单查询入口
     */
    public ChatResult run(String query) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        messages.add(Message.user(query));
        return runWithLoop(messages);
    }

    /**
     * 多轮对话入口
     */
    public ChatResult run(List<Message> messages) {
        return runWithLoop(new ArrayList<>(messages));
    }

    private ChatResult runWithLoop(List<Message> messages) {
        String traceId = ChainTrace.generate();
        ChainTrace.set(traceId);
        try {
            return doRunWithLoop(messages);
        } finally {
            ChainTrace.clear();
        }
    }

    private ChatResult doRunWithLoop(List<Message> messages) {
        List<Tool> tools = toolRegistry.getTools();
        String traceId = ChainTrace.get();

        for (int i = 0; i < maxIterations; i++) {
            callback.onLlmStart(new LlmStartEvent(traceId, messages, tools));
            ChatResult result;
            long start = System.currentTimeMillis();
            try {
                result = llm.chat(messages, tools);
                long latencyMs = System.currentTimeMillis() - start;
                callback.onLlmComplete(new LlmCompleteEvent(traceId, result, null, latencyMs));
            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - start;
                callback.onLlmError(new LlmErrorEvent(traceId, messages, tools, e, latencyMs));
                throw e;
            }
            messages.add(result.toMessage());

            if (!result.hasToolCalls()) {
                return result;
            }

            for (ToolCall tc : result.toolCalls()) {
                String output = safeExecute(tc, traceId);
                messages.add(Message.toolResult(tc.id(), output));
            }
        }

        throw new AgentException("超出最大迭代次数: " + maxIterations);
    }

    /**
     * 安全执行工具调用，捕获异常后将错误信息传回 LLM
     */
    private String safeExecute(ToolCall tc, String traceId) {
        callback.onToolStart(new ToolStartEvent(traceId, tc));
        long start = System.currentTimeMillis();
        try {
            String result = toolRegistry.execute(tc.name(), tc.arguments());
            long latencyMs = System.currentTimeMillis() - start;
            callback.onToolComplete(new ToolCompleteEvent(traceId, tc.id(), tc.name(), result, latencyMs));
            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            callback.onToolError(new ToolErrorEvent(traceId, tc.id(), tc.name(), tc.arguments(), e, latencyMs));
            return "工具执行失败: " + e.getMessage();
        }
    }

    public static class Builder {

        private final LLM llm;
        private final ToolRegistry toolRegistry;
        private String systemPrompt;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private ChainCallback callback;

        private Builder(LLM llm, ToolRegistry toolRegistry) {
            this.llm = llm;
            this.toolRegistry = toolRegistry;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * 设置回调，用于观察 agent 及内部 LLM/Tool 的执行过程
         *
         * <pre>{@code
         * .callback(new ChainCallback() {
         *     @Override
         *     public void onLlmComplete(LlmCompleteEvent event) {
         *         System.out.println("LLM 耗时: " + event.latencyMs() + "ms");
         *     }
         * })
         * }</pre>
         */
        public Builder callback(ChainCallback callback) {
            this.callback = callback;
            return this;
        }

        /**
         * 通过 ChainContext 注入回调
         */
        public Builder chainContext(ChainContext chainContext) {
            if (chainContext != null && this.callback == null) {
                this.callback = chainContext.callback();
            }
            return this;
        }

        public Agent build() {
            if (callback == null) {
                callback = ChainCallbackUtil.noop();
            }
            return new Agent(this);
        }
    }
}
