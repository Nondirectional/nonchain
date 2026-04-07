package com.non.chain.agent;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.provider.LLM;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    private final Consumer<String> logger;

    private Agent(Builder builder) {
        this.llm = builder.llm;
        this.toolRegistry = builder.toolRegistry;
        this.systemPrompt = builder.systemPrompt;
        this.maxIterations = builder.maxIterations;
        this.logger = builder.logger;
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
        List<Tool> tools = toolRegistry.getTools();

        for (int i = 0; i < maxIterations; i++) {
            log("[迭代 %d] 调用 LLM...", i + 1);
            ChatResult result = llm.chat(messages, tools);
            messages.add(result.toMessage());

            if (!result.hasToolCalls()) {
                log("[迭代 %d] LLM 返回最终回复", i + 1);
                return result;
            }

            for (ToolCall tc : result.toolCalls()) {
                log("[迭代 %d] 调用工具: %s(%s)", i + 1, tc.name(), tc.arguments());
                String output = safeExecute(tc);
                log("[迭代 %d] 工具结果: %s", i + 1, output);
                messages.add(Message.toolResult(tc.id(), output));
            }
        }

        throw new AgentException("超出最大迭代次数: " + maxIterations);
    }

    private void log(String format, Object... args) {
        if (logger != null) {
            logger.accept(String.format(format, args));
        }
    }

    /**
     * 安全执行工具调用，捕获异常后将错误信息传回 LLM
     */
    private String safeExecute(ToolCall tc) {
        try {
            return toolRegistry.execute(tc.name(), tc.arguments());
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    public static class Builder {

        private final LLM llm;
        private final ToolRegistry toolRegistry;
        private String systemPrompt;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private Consumer<String> logger;

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
         * 设置日志回调，用于观察 agent loop 的执行过程
         *
         * <pre>{@code
         * .logger(System.out::println)
         * }</pre>
         */
        public Builder logger(Consumer<String> logger) {
            this.logger = logger;
            return this;
        }

        public Agent build() {
            return new Agent(this);
        }
    }
}
