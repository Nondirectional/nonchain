package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
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
import com.non.chain.memory.ChatMemory;
import com.non.chain.provider.LLM;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
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
    private final ChatMemory memory;
    private final Executor executor;
    private final List<BeforeToolCall> beforeInterceptors;
    private final List<AfterToolCall> afterInterceptors;

    private Agent(Builder builder) {
        this.llm = builder.llm;
        this.toolRegistry = builder.toolRegistry;
        this.systemPrompt = builder.systemPrompt;
        this.maxIterations = builder.maxIterations;
        this.callback = builder.callback;
        this.memory = builder.memory;
        this.executor = builder.executor;
        this.beforeInterceptors = Collections.unmodifiableList(new ArrayList<>(builder.beforeInterceptors));
        this.afterInterceptors = Collections.unmodifiableList(new ArrayList<>(builder.afterInterceptors));
    }

    public static Builder builder(LLM llm, ToolRegistry toolRegistry) {
        return new Builder(llm, toolRegistry);
    }

    /**
     * 简单查询入口
     *
     * <p>如果配置了 Memory，自动管理多轮对话历史：</p>
     * <ol>
     *   <li>将用户消息加入 Memory</li>
     *   <li>从 Memory 获取历史消息</li>
     *   <li>拼装 systemPrompt + 历史消息，调用 LLM</li>
     *   <li>将新产生的消息（assistant + tool）同步回 Memory</li>
     * </ol>
     */
    public ChatResult run(String query) {
        if (memory != null) {
            memory.add(Message.user(query));
            List<Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Message.system(systemPrompt));
            }
            List<Message> history = memory.messages();
            int historyEnd = messages.size() + history.size();
            messages.addAll(history);
            ChatResult result = runWithLoop(messages);
            // 同步新消息（assistant + tool results）到 memory
            for (int i = historyEnd; i < messages.size(); i++) {
                memory.add(messages.get(i));
            }
            return result;
        }
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

    /**
     * 流式查询入口
     */
    public ChatResult run(String query, Consumer<AgentEvent> eventConsumer) {
        if (memory != null) {
            memory.add(Message.user(query));
            List<Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Message.system(systemPrompt));
            }
            List<Message> history = memory.messages();
            int historyEnd = messages.size() + history.size();
            messages.addAll(history);
            ChatResult result = runWithLoop(messages, eventConsumer);
            for (int i = historyEnd; i < messages.size(); i++) {
                memory.add(messages.get(i));
            }
            return result;
        }
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        messages.add(Message.user(query));
        return runWithLoop(messages, eventConsumer);
    }

    /**
     * 流式多轮对话入口
     */
    public ChatResult run(List<Message> messages, Consumer<AgentEvent> eventConsumer) {
        return runWithLoop(new ArrayList<>(messages), eventConsumer);
    }

    private ChatResult runWithLoop(List<Message> messages) {
        return runWithLoop(messages, null);
    }

    private ChatResult runWithLoop(List<Message> messages, Consumer<AgentEvent> eventConsumer) {
        String traceId = ChainTrace.generate();
        ChainTrace.set(traceId);
        try {
            return doRunWithLoop(messages, eventConsumer);
        } finally {
            ChainTrace.clear();
        }
    }

    private ChatResult doRunWithLoop(List<Message> messages, Consumer<AgentEvent> eventConsumer) {
        List<Tool> tools = toolRegistry.getTools();
        String traceId = ChainTrace.get();

        for (int round = 0; round < maxIterations; round++) {
            if (eventConsumer != null) {
                eventConsumer.accept(new AgentEvent.RoundStart(round + 1));
            }

            callback.onLlmStart(new LlmStartEvent(traceId, messages, tools));
            ChatResult result;
            long start = System.currentTimeMillis();
            try {
                if (eventConsumer != null) {
                    result = llm.streamChat(messages, tools, OutputFormat.TEXT, chunk -> {
                        if (chunk.hasContent()) {
                            eventConsumer.accept(new AgentEvent.TextDelta(chunk.deltaContent()));
                        }
                        if (chunk.hasThinking()) {
                            eventConsumer.accept(new AgentEvent.ThinkingDelta(chunk.deltaThinking()));
                        }
                        if (chunk.hasToolCalls()) {
                            for (ChatChunk.DeltaToolCall dtc : chunk.deltaToolCalls()) {
                                eventConsumer.accept(new AgentEvent.ToolCallDelta(
                                        dtc.index(), dtc.id(), dtc.name(), dtc.argumentsDelta()));
                            }
                        }
                    });
                } else {
                    result = llm.chat(messages, tools);
                }
                long latencyMs = System.currentTimeMillis() - start;
                callback.onLlmComplete(new LlmCompleteEvent(traceId, result, null, latencyMs));
            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - start;
                callback.onLlmError(new LlmErrorEvent(traceId, messages, tools, e, latencyMs));
                if (eventConsumer != null) {
                    eventConsumer.accept(new AgentEvent.AgentError(e));
                }
                throw e;
            }
            messages.add(result.toMessage());

            if (eventConsumer != null) {
                eventConsumer.accept(new AgentEvent.RoundEnd(round + 1));
            }

            if (!result.hasToolCalls()) {
                if (eventConsumer != null) {
                    eventConsumer.accept(new AgentEvent.Complete(result));
                }
                return result;
            }

            List<ToolCall> toolCalls = result.toolCalls();
            boolean parallel = toolCalls.size() > 1 && executor != null;
            final Message assistantMessage = result.toMessage();

            if (parallel) {
                // 并行执行多个工具调用，按源顺序组装结果
                @SuppressWarnings("unchecked")
                CompletableFuture<String>[] futures = new CompletableFuture[toolCalls.size()];
                for (int i = 0; i < toolCalls.size(); i++) {
                    ToolCall tc = toolCalls.get(i);
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.ToolStart(tc.name(), tc.arguments()));
                    }
                    final int idx = i;
                    futures[i] = CompletableFuture.supplyAsync(() -> safeExecute(tc, assistantMessage, traceId), executor)
                            .whenComplete((output, err) -> {
                                if (eventConsumer != null) {
                                    eventConsumer.accept(new AgentEvent.ToolEnd(tc.name(),
                                            err != null ? "工具执行失败: " + err.getMessage() : output));
                                }
                            });
                }
                CompletableFuture.allOf(futures).join();
                // 按原始顺序追加结果到 messages
                for (int i = 0; i < toolCalls.size(); i++) {
                    try {
                        messages.add(Message.toolResult(toolCalls.get(i).id(), futures[i].get()));
                    } catch (Exception e) {
                        messages.add(Message.toolResult(toolCalls.get(i).id(), "工具执行失败: " + e.getMessage()));
                    }
                }
            } else {
                // 单个工具调用或未配置 executor，串行执行
                for (ToolCall tc : toolCalls) {
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.ToolStart(tc.name(), tc.arguments()));
                    }
                    String output = safeExecute(tc, assistantMessage, traceId);
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.ToolEnd(tc.name(), output));
                    }
                    messages.add(Message.toolResult(tc.id(), output));
                }
            }
        }

        AgentException ex = new AgentException("超出最大迭代次数: " + maxIterations);
        if (eventConsumer != null) {
            eventConsumer.accept(new AgentEvent.AgentError(ex));
        }
        throw ex;
    }

    /**
     * 安全执行工具调用：callback → before 拦截 → 执行 → after 拦截。
     *
     * <p>callback（onToolStart/onToolComplete/onToolError）仅在此触发一次。ToolRegistry.execute
     * 已不触发 callback（见 ToolRegistry）。before 返回 block 时跳过执行；after 可改写 content/isError。
     * 工具执行异常软失败回灌 LLM（保持现状语义）；拦截器异常包装为 AgentException 抛出（不静默吞）。</p>
     */
    private String safeExecute(ToolCall tc, Message assistantMessage, String traceId) {
        ToolCallContext ctx = new ToolCallContext(tc.id(), tc.name(), tc.arguments(), assistantMessage);

        // 1. callback: onToolStart（唯一触发点）
        callback.onToolStart(new ToolStartEvent(traceId, tc));
        long start = System.currentTimeMillis();

        try {
            // 2. before 拦截器链（任一 block 即短路）
            for (BeforeToolCall before : beforeInterceptors) {
                BeforeResult br = before.before(ctx);
                if (br.blocked()) {
                    long latencyMs = System.currentTimeMillis() - start;
                    // block 视为错误：触发 onToolError（reason 文本），无 onToolComplete
                    callback.onToolError(new ToolErrorEvent(traceId, tc.id(), tc.name(), tc.arguments(),
                            new RuntimeException(br.reason()), latencyMs));
                    return br.reason();
                }
            }

            // 3. 实际执行（ToolRegistry.execute 已不触发 callback）
            String result;
            boolean isError = false;
            try {
                result = toolRegistry.execute(tc.name(), tc.arguments());
            } catch (Exception execEx) {
                // 工具执行错误：软失败（现状语义），仍允许 after 拦截器处理错误结果
                result = "工具执行失败: " + execEx.getMessage();
                isError = true;
            }

            // 4. after 拦截器链（链式：前一个输出作后一个输入）
            for (AfterToolCall after : afterInterceptors) {
                AfterResult ar = after.after(new ToolCallContext(tc.id(), tc.name(), tc.arguments(),
                        assistantMessage, result, isError));
                if (ar.modified()) {
                    if (ar.content() != null) {
                        result = ar.content();
                    }
                    if (ar.isError() != null) {
                        isError = ar.isError();
                    }
                }
            }

            // 5. callback: onToolComplete 或 onToolError（唯一触发点）
            long latencyMs = System.currentTimeMillis() - start;
            if (isError) {
                callback.onToolError(new ToolErrorEvent(traceId, tc.id(), tc.name(), tc.arguments(),
                        new RuntimeException(result), latencyMs));
            } else {
                callback.onToolComplete(new ToolCompleteEvent(traceId, tc.id(), tc.name(), result, latencyMs));
            }
            return result;

        } catch (AgentException ae) {
            // 拦截器异常：上抛（不静默吞）。callback.onToolError 已在包装前的内层保证触发。
            throw ae;
        } catch (Exception e) {
            // 拦截器异常包装为 AgentException
            long latencyMs = System.currentTimeMillis() - start;
            callback.onToolError(new ToolErrorEvent(traceId, tc.id(), tc.name(), tc.arguments(), e, latencyMs));
            throw new AgentException("工具拦截器执行失败: " + tc.name(), e);
        }
    }

    public static class Builder {

        private final LLM llm;
        private final ToolRegistry toolRegistry;
        private String systemPrompt;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private ChainCallback callback;
        private ChatMemory memory;
        private Executor executor = ForkJoinPool.commonPool();
        private final List<BeforeToolCall> beforeInterceptors = new ArrayList<>();
        private final List<AfterToolCall> afterInterceptors = new ArrayList<>();

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

        /**
         * 设置对话记忆，用于自动管理多轮对话历史。
         *
         * <p>配置后，{@code run(String)} 会自动将消息存入 Memory，
         * 并在下次调用时携带历史上下文。{@code run(List<Message>)} 不受影响。</p>
         *
         * <pre>{@code
         * ChatMemory memory = MessageWindowChatMemory.builder()
         *     .maxMessages(20)
         *     .conversationId("user-1")
         *     .build();
         *
         * Agent agent = Agent.builder(llm, toolRegistry)
         *     .memory(memory)
         *     .build();
         *
         * agent.run("我叫小明");
         * agent.run("我叫什么名字？"); // 能记住 "小明"
         * }</pre>
         */
        public Builder memory(ChatMemory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * 设置用于并行执行多个工具调用的线程池。
         *
         * <p>默认使用 {@link ForkJoinPool#commonPool()}。设置为 {@code null} 时
         * 多个工具调用将串行执行。</p>
         *
         * <pre>{@code
         * Agent agent = Agent.builder(llm, toolRegistry)
         *     .executor(Executors.newFixedThreadPool(4))
         *     .build();
         * }</pre>
         */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * 添加 before 工具拦截器，在工具实际执行前调用，可阻止执行。
         * 多个拦截器按添加顺序串行调用，任一返回 block 即短路。
         *
         * <pre>{@code
         * .addBeforeToolCall(ctx -> {
         *     if (ctx.arguments().contains("rm -rf")) {
         *         return BeforeResult.block("危险命令禁止");
         *     }
         *     return BeforeResult.pass();
         * })
         * }</pre>
         */
        public Builder addBeforeToolCall(BeforeToolCall interceptor) {
            if (interceptor != null) {
                this.beforeInterceptors.add(interceptor);
            }
            return this;
        }

        /**
         * 添加 after 工具拦截器，在工具执行完成后、结果回灌 LLM 前调用，可改写结果。
         * 多个拦截器按添加顺序链式调用（前一个输出作后一个输入）。
         *
         * <pre>{@code
         * .addAfterToolCall(ctx -> AfterResult.content(maskSecrets(ctx.result())))
         * }</pre>
         */
        public Builder addAfterToolCall(AfterToolCall interceptor) {
            if (interceptor != null) {
                this.afterInterceptors.add(interceptor);
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
