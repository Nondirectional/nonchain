package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.callback.ChainContext;
import com.non.chain.callback.ChainTrace;
import com.non.chain.callback.CompositeCallback;
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
import com.non.chain.trace.RecordingCallback;
import com.non.chain.trace.SpanAttributes;
import com.non.chain.trace.SpanContext;
import com.non.chain.trace.TraceRuntimeIds;
import com.non.chain.trace.Tracer;
import com.non.chain.trace.TraceStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final SubAgentExposureMode subAgentExposureMode;

    // ---- 执行链路遥测（trace 录制，可空；null = 未启用 = 零开销零行为变化） ----
    private final Tracer tracer;
    private final RecordingCallback recordingCallback;
    /** SubAgent 全树下钻时注入的父 span context（边界1）；顶层 Agent 为 null。 */
    private final SpanContext parentSpanContext;

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
        this.subAgentExposureMode = builder.subAgentExposureMode;
        this.tracer = builder.tracer;
        this.recordingCallback = builder.recordingCallback;
        this.parentSpanContext = builder.parentSpanContext;
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
        if (tracer == null) {
            // 未启用录制：保持现状（零开销零行为变化）
            try {
                return doRunWithLoop(messages, eventConsumer);
            } finally {
                ChainTrace.clear();
            }
        }
        // 启用录制：用 agent_run 根 span 包住整个循环。
        // parentSpanContext 非空（SubAgent 下钻）→ startChild（不切新根，挂到父委派 tool span 下）；
        // 为空（顶层执行）→ startSpan 新根。
        Tracer.ScopedSpan rootSpan = parentSpanContext != null
                ? tracer.startChild(parentSpanContext, SpanAttributes.SpanType.AGENT_RUN, "agent")
                : tracer.startSpan(SpanAttributes.SpanType.AGENT_RUN, "agent");
        try {
            rootSpan.putAttribute(SpanAttributes.SYSTEM_PROMPT, systemPrompt);
            rootSpan.putAttribute(SpanAttributes.MAX_ITERATIONS, maxIterations);
            try {
                ChatResult result = doRunWithLoop(messages, eventConsumer);
                // 成功路径：回填 runtimeId（失败路径在 catch 里 attach marker）
                return result.withRuntimeId(rootSpan.runtimeId());
            } catch (RuntimeException | Error e) {
                // 失败路径：保留原异常类型/栈/语义不变，仅附加 suppressed trace marker
                TraceRuntimeIds.attach(e, rootSpan.runtimeId());
                throw e;
            } catch (Exception e) {
                TraceRuntimeIds.attach(e, rootSpan.runtimeId());
                throw e;
            }
        } finally {
            rootSpan.close();
            ChainTrace.clear();
        }
    }

    private ChatResult doRunWithLoop(List<Message> messages, Consumer<AgentEvent> eventConsumer) {
        List<Tool> tools = resolveToolsForCurrentExposureMode();
        String traceId = ChainTrace.get();

        for (int round = 0; round < maxIterations; round++) {
            if (eventConsumer != null) {
                eventConsumer.accept(new AgentEvent.RoundStart(round + 1));
            }

            // trace 启用时：每轮开一个 llm span，覆盖「LLM 调用 + 本轮所有工具执行」。
            // 这样 tool span（串行靠 current 栈、并行靠捕获的 llm ctx）自然挂到对应 llm 下。
            final Tracer.ScopedSpan llmSpan = tracer != null
                    ? tracer.startSpan(SpanAttributes.SpanType.LLM, "llm") : null;
            try {
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
                    if (llmSpan != null) {
                        llmSpan.markError(e);
                    }
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
                    // 并行执行多个工具调用，按源顺序组装结果。
                    // 父上下文快照在子代理执行时只读；本轮历史消息不会再原地修改，复制一份避免并发读写歧义。
                    final List<Message> parentSnapshot = List.copyOf(messages);
                    // 边界2：worker 线程 ThreadLocal 是空的，捕获当前 llm span 的 context，
                    // worker 里用 startChild 建 tool span，parent 指向本轮 llm span。
                    final SpanContext llmCtx = tracer != null ? Tracer.current() : null;
                    @SuppressWarnings("unchecked")
                    CompletableFuture<String>[] futures = new CompletableFuture[toolCalls.size()];
                    for (int i = 0; i < toolCalls.size(); i++) {
                        ToolCall tc = toolCalls.get(i);
                        if (eventConsumer != null) {
                            eventConsumer.accept(new AgentEvent.ToolStart(tc.name(), tc.arguments()));
                        }
                        final int idx = i;
                        final ToolCall finalTc = tc;
                        futures[i] = CompletableFuture.supplyAsync(
                                () -> executeWithToolSpan(finalTc, llmCtx, assistantMessage, parentSnapshot, traceId),
                                executor)
                                .whenComplete((output, err) -> {
                                    if (eventConsumer != null) {
                                        eventConsumer.accept(new AgentEvent.ToolEnd(finalTc.name(),
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
                    final List<Message> parentSnapshot = List.copyOf(messages);
                    for (ToolCall tc : toolCalls) {
                        if (eventConsumer != null) {
                            eventConsumer.accept(new AgentEvent.ToolStart(tc.name(), tc.arguments()));
                        }
                        String output = executeWithToolSpan(tc, null, assistantMessage, parentSnapshot, traceId);
                        if (eventConsumer != null) {
                            eventConsumer.accept(new AgentEvent.ToolEnd(tc.name(), output));
                        }
                        messages.add(Message.toolResult(tc.id(), output));
                    }
                }
            } finally {
                if (llmSpan != null) {
                    llmSpan.close();
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
     * 在 tool span 作用域内执行一次工具调用。
     *
     * <p>trace 启用时的 span 策略：</p>
     * <ul>
     *   <li><b>串行路径</b>（{@code parentCtx == null}）：current 栈正常，startSpan("tool")
     *       自动以本轮 llm span 为 parent。</li>
     *   <li><b>并行路径</b>（{@code parentCtx != null}）：worker 线程 ThreadLocal 空，
     *       用 startChild(parentCtx, "tool") 显式恢复 parent（边界2）。</li>
     * </ul>
     * trace 未启用时（tracer 为 null）直接执行，无任何 span 开销。
     */
    private String executeWithToolSpan(ToolCall tc, SpanContext parentCtx,
                                       Message assistantMessage, List<Message> parentMessages, String traceId) {
        if (tracer == null) {
            return safeExecute(tc, assistantMessage, parentMessages, traceId);
        }
        Tracer.ScopedSpan toolSpan = parentCtx != null
                ? tracer.startChild(parentCtx, SpanAttributes.SpanType.TOOL, tc.name())
                : tracer.startSpan(SpanAttributes.SpanType.TOOL, tc.name());
        // 注意：safeExecute 对工具/子代理执行错误采用软失败（捕获后回灌 LLM，不抛出），
        // 错误状态由 RecordingCallback.onToolError 标记到 span；这里只兜底真正向上传播的异常
        // （如拦截器抛出的 AgentException）。
        try {
            return safeExecute(tc, assistantMessage, parentMessages, traceId);
        } catch (RuntimeException e) {
            toolSpan.markError(e);
            throw e;
        } finally {
            toolSpan.close();
        }
    }

    /**
     * 安全执行工具调用：callback → before 拦截 → 执行 → after 拦截。
     *
     * <p>callback（onToolStart/onToolComplete/onToolError）仅在此触发一次。ToolRegistry.execute
     * 已不触发 callback（见 ToolRegistry）。before 返回 block 时跳过执行；after 可改写 content/isError。
     * 工具执行异常软失败回灌 LLM（保持现状语义）；拦截器异常包装为 AgentException 抛出（不静默吞）。</p>
     *
     * <p>{@code parentMessages} 为父 Agent 当前轮消息链快照，仅在执行子代理工具时用于裁剪父上下文。
     * 普通工具路径不读取它，行为与改动前一致。</p>
     */
    private String safeExecute(ToolCall tc, Message assistantMessage, List<Message> parentMessages, String traceId) {
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

            // 3. 实际执行（按工具类型三路分流）
            String result;
            boolean isError = false;
            try {
                result = dispatchExecute(tc, assistantMessage, parentMessages, traceId);
            } catch (Exception execEx) {
                // 工具/子代理执行错误：软失败（现状语义），仍允许 after 拦截器处理错误结果
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

    /**
     * 按工具类型分流执行：普通工具 / 独立子代理 tool / 通用 delegate tool。
     * 普通工具走 {@link ToolRegistry#execute}；子代理工具走 {@link #executeSubAgentTool}。
     */
    private String dispatchExecute(ToolCall tc, Message assistantMessage, List<Message> parentMessages, String traceId) {
        // 独立子代理 tool：tool 名即子代理名
        if (toolRegistry.hasSubAgent(tc.name())) {
            SubAgentDefinition def = toolRegistry.getSubAgent(tc.name());
            String task = parseTaskArg(tc.arguments(), def.name());
            return executeSubAgentTool(def, task, assistantMessage, parentMessages, traceId);
        }
        // 通用 delegate tool：先解析 agentName 再定位子代理
        if (ToolRegistry.DELEGATE_TOOL_NAME.equals(tc.name())) {
            String agentName = parseAgentNameArg(tc.arguments());
            SubAgentDefinition def = toolRegistry.getSubAgent(agentName);
            String task = parseTaskArg(tc.arguments(), agentName);
            return executeSubAgentTool(def, task, assistantMessage, parentMessages, traceId);
        }
        // 普通工具：维持现状
        return toolRegistry.execute(tc.name(), tc.arguments());
    }

    /**
     * 执行子代理：构造裁剪上下文 → 动态构建子代理 Agent → 运行 → 返回最终文本。
     *
     * <p>父/子 callback 与 trace 隔离：子代理使用独立 noop callback 与独立 trace，
     * 父侧仅在外层把它视为一次普通工具调用（onToolStart/onToolComplete/onToolError 由 safeExecute 触发）。
     * 子代理整体抛出的异常向上传播，由 safeExecute 捕获后软失败回灌 LLM。</p>
     */
    private String executeSubAgentTool(SubAgentDefinition def, String task,
                                       Message assistantMessage, List<Message> parentMessages, String traceId) {
        // 1. 裁剪父上下文（不含父 systemPrompt；排除 llmVisible=false）
        ContextSelector selector = def.contextSelector() != null
                ? def.contextSelector() : DEFAULT_CONTEXT_SELECTOR;
        List<Message> parentSlice = selector.select(parentMessages, assistantMessage, task);

        // 2. 组装子代理消息：子代理 systemPrompt + 父上下文切片 + 本次 task
        List<Message> childMessages = new ArrayList<>();
        childMessages.add(Message.system(def.systemPrompt()));
        childMessages.addAll(parentSlice);
        childMessages.add(Message.user(task));

        // 3. 动态构造子代理：默认继承父 LLM，独立工具集/拦截器/maxIterations，隔离 callback 与 trace
        LLM childLlm = def.llmOverride() != null ? def.llmOverride() : this.llm;
        ToolRegistry childRegistry = def.toolRegistry() != null ? def.toolRegistry() : new ToolRegistry();
        Agent.Builder childBuilder = Agent.builder(childLlm, childRegistry)
                .systemPrompt(def.systemPrompt())
                .callback(ChainCallbackUtil.noop()); // 父/子【用户面】callback 隔离（既有承诺不变）
        if (def.maxIterations() != null) {
            childBuilder.maxIterations(def.maxIterations());
        }
        for (BeforeToolCall b : def.beforeInterceptors()) {
            childBuilder.addBeforeToolCall(b);
        }
        for (AfterToolCall a : def.afterInterceptors()) {
            childBuilder.addAfterToolCall(a);
        }
        // 边界1（SubAgent 全树下钻）：录制层正交于用户面 callback——
        // 子代理用户 callback 仍隔离（noop），但【录制 callback】不隔离：
        // 注入父的 tracer + 当前 current SpanContext（即父委派 tool span 的 ctx），
        // 子代理用自己的 RecordingCallback 实例，内部 LLM/Tool span 挂到父委派 tool span 下，进同一棵树。
        SpanContext subParentCtx = null;
        if (tracer != null) {
            subParentCtx = Tracer.current();
            if (subParentCtx != null) {
                childBuilder.trace(tracer.store())
                        .parentSpanContext(subParentCtx);
            }
        }
        Agent child = childBuilder.build();

        // 4. 子代理用自己的 trace 运行（run(List<Message>) 内部会生成独立 traceId 并在 finally 清理）
        ChatResult childResult = child.run(childMessages);
        return childResult.content() != null ? childResult.content() : "";
    }

    /** 子代理/delegate 工具共享的 JSON 参数解析器（与 ToolRegistry.parseArguments 同语义）。 */
    private static final ObjectMapper SUBAGENT_ARG_MAPPER = new ObjectMapper();

    /** 从独立子代理 tool 的 arguments 中解析 {@code task}。 */
    private String parseTaskArg(String arguments, String agentName) {
        Map<String, Object> map = parseArgsMap(arguments, "子代理调用");
        Object task = map.get("task");
        if (task == null || task.toString().isBlank()) {
            throw new IllegalArgumentException("子代理调用缺少 task 参数: " + agentName);
        }
        return task.toString();
    }

    /** 从通用 delegate tool 的 arguments 中解析 {@code agentName}。 */
    private String parseAgentNameArg(String arguments) {
        Map<String, Object> map = parseArgsMap(arguments, "delegate 调用");
        Object name = map.get("agentName");
        if (name == null || name.toString().isBlank()) {
            throw new IllegalArgumentException("delegate 调用缺少 agentName 参数");
        }
        return name.toString();
    }

    /**
     * 把子代理/delegate tool 的 JSON arguments 解析为 Map（与 {@code ToolRegistry.parseArguments}
     * 同语义，错误文案带前缀 {@code prefix} 以区分调用来源）。
     */
    private Map<String, Object> parseArgsMap(String arguments, String prefix) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException(prefix + "参数为空");
        }
        try {
            Object parsed = SUBAGENT_ARG_MAPPER.readValue(arguments.trim(), Object.class);
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException(prefix + "参数必须是 JSON 对象: " + arguments);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(prefix + "参数 JSON 解析失败: " + arguments, e);
        }
    }

    /**
     * 按当前暴露模式解析传给 LLM 的工具列表。
     * <ul>
     *   <li>DIRECT（默认）：普通工具 + 每个子代理一个独立 tool。</li>
     *   <li>DELEGATE：普通工具 + 单个 delegate_to_subagent tool（无子代理时仅普通工具）。</li>
     * </ul>
     */
    private List<Tool> resolveToolsForCurrentExposureMode() {
        List<Tool> tools = new ArrayList<>(toolRegistry.getRegularTools());
        if (subAgentExposureMode == SubAgentExposureMode.DELEGATE) {
            Optional<Tool> delegate = toolRegistry.getDelegateSubAgentTool();
            delegate.ifPresent(tools::add);
        } else {
            tools.addAll(toolRegistry.getDirectSubAgentTools());
        }
        return tools;
    }

    /** 框架默认上下文裁剪：排除 llmVisible=false（含 note），保留其余父消息。 */
    private static final ContextSelector DEFAULT_CONTEXT_SELECTOR = (parentMessages, assistantMessage, task) -> {
        List<Message> visible = new ArrayList<>(parentMessages.size());
        for (Message m : parentMessages) {
            if (m.llmVisible()) {
                visible.add(m);
            }
        }
        return visible;
    };

    public static class Builder {

        private final LLM llm;
        private final ToolRegistry toolRegistry;
        private String systemPrompt;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private ChainCallback callback;
        private ChatMemory memory;
        private Executor executor = ForkJoinPool.commonPool();
        private SubAgentExposureMode subAgentExposureMode = SubAgentExposureMode.DIRECT;
        private final List<BeforeToolCall> beforeInterceptors = new ArrayList<>();
        private final List<AfterToolCall> afterInterceptors = new ArrayList<>();

        // ---- trace（构建期决定，运行期生效） ----
        private TraceStore traceStore;
        /** Builder 复用的 tracer/recordingCallback（同一 Agent 实例共享一份）。 */
        private Tracer tracer;
        private RecordingCallback recordingCallback;
        /** SubAgent 注入的父 span context（边界1）。 */
        private SpanContext parentSpanContext;

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
         * 启用执行链路遥测录制（opt-in）。
         *
         * <p>传入非空 {@link TraceStore} 即开启录制：Agent 每次执行会生成一棵 OTel 风格 span 树
         * （agent_run 根 + 内嵌 LLM/Tool/SubAgent 子 span），存入 store，调用方可凭
         * {@link ChatResult#runtimeId()} 拉回完整 trace。失败路径下 runtimeId 通过
         * {@code TraceRuntimeIds.find(throwable)} 从异常里提取。</p>
         *
         * <p><b>默认全关</b>：不调用本方法 = 不录制 = 零开销、零行为变化。
         * 不引入全局 static 开关、不加 ServiceLoader 自动发现。</p>
         *
         * <pre>{@code
         * InMemoryTraceStore store = new InMemoryTraceStore();
         * Agent agent = Agent.builder(llm, registry)
         *     .systemPrompt("...")
         *     .trace(store)        // ← 启用录制
         *     .build();
         * ChatResult r = agent.run("你好");
         * Trace trace = store.getTrace(r.runtimeId()).orElseThrow();
         * }</pre>
         */
        public Builder trace(TraceStore traceStore) {
            this.traceStore = traceStore;
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
         * 设置子代理暴露模式（构建期固定，运行时不可切换）。
         *
         * <ul>
         *   <li>{@link SubAgentExposureMode#DIRECT}（默认）：每个子代理暴露为独立 tool。</li>
         *   <li>{@link SubAgentExposureMode#DELEGATE}：只暴露单个 {@code delegate_to_subagent} tool。</li>
         * </ul>
         * 传 {@code null} 回退默认值 {@code DIRECT}。
         */
        public Builder subAgentExposureMode(SubAgentExposureMode mode) {
            this.subAgentExposureMode = mode == null ? SubAgentExposureMode.DIRECT : mode;
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
            // 启用 trace 录制：构造 Tracer + RecordingCallback，并把录制 callback 与用户 callback 组合。
            // 用户面 callback 与录制 callback 是两个独立实例（design §4.2 边界1）：
            // SubAgent 用户隔离靠传 noop()（只隔离用户 callback），recordingCallback 单独注入下钻。
            if (traceStore != null) {
                this.tracer = new Tracer(traceStore);
                this.recordingCallback = new RecordingCallback();
                // CompositeCallback 独立执行每个 callback 且异常隔离（safeInvoke 静默吞）；
                // RecordingCallback 内部也做了静默隔离，录制失败不污染主流程。
                // 注意：RecordingCallback 只填充载荷，span 由 Agent/Graph 显式建/关。
                this.callback = CompositeCallback.of(callback, recordingCallback);
            }
            return new Agent(this);
        }

        /**
         * 注入父 span context（SubAgent 全树下钻用，边界1）。包级入口，框架内部使用。
         */
        Builder parentSpanContext(SpanContext parentSpanContext) {
            this.parentSpanContext = parentSpanContext;
            return this;
        }

        /** 复用已构造的 tracer（SubAgent 注入用）。包级入口，框架内部使用。 */
        Tracer tracer() {
            return tracer;
        }

        /** 复用已构造的 recordingCallback（SubAgent 注入用）。包级入口，框架内部使用。 */
        RecordingCallback recordingCallback() {
            return recordingCallback;
        }
    }
}
