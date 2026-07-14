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
import com.non.chain.skill.SkillDefinition;
import com.non.chain.skill.SkillRegistry;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
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
    /** D9 graceful grace turns 默认值;Builder.graceTurns(0) 可禁用(回退 0.9.0 硬截断抛异常) */
    private static final int DEFAULT_GRACE_TURNS = 3;

    private final LLM llm;
    private final ToolRegistry toolRegistry;
    private final String systemPrompt;
    private final int maxIterations;
    private final int graceTurns;
    private final ChainCallback callback;
    private final ChatMemory memory;
    private final Executor executor;
    private final List<BeforeToolCall> beforeInterceptors;
    private final List<AfterToolCall> afterInterceptors;
    private final SubAgentExposureMode subAgentExposureMode;

    /** skill 注册中心;null = 无 skill(行为与 0.10.0 一致)。 */
    private final SkillRegistry skillRegistry;
    /** skill 被点选后注入知识的消息角色。 */
    private final SkillInjectionMode skillInjectionMode;

    // ---- 后台子代理配置(D4)----
    private final ExecutorService backgroundExecutor;
    private final int maxBackgroundRunning;
    private final int spawnCeiling;
    private final long awaitTimeoutMs;

    // ---- steer(D6):null = 未启用(顶层/前台);非 null = 子代理实例,支持运行中注入 ----
    private final BlockingQueue<String> pendingSteers;

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
        this.graceTurns = builder.graceTurns;
        this.callback = builder.callback;
        this.memory = builder.memory;
        this.executor = builder.executor;
        this.beforeInterceptors = Collections.unmodifiableList(new ArrayList<>(builder.beforeInterceptors));
        this.afterInterceptors = Collections.unmodifiableList(new ArrayList<>(builder.afterInterceptors));
        this.subAgentExposureMode = builder.subAgentExposureMode;
        this.skillRegistry = builder.skillRegistry;
        this.skillInjectionMode = builder.skillInjectionMode;
        this.backgroundExecutor = builder.backgroundExecutor;
        this.maxBackgroundRunning = builder.maxBackgroundRunning;
        this.spawnCeiling = builder.spawnCeiling != null ? builder.spawnCeiling
                : maxIterations * maxBackgroundRunning * 2;
        this.awaitTimeoutMs = builder.awaitTimeoutMs;
        this.pendingSteers = builder.pendingSteers;
        this.tracer = builder.tracer;
        this.recordingCallback = builder.recordingCallback;
        this.parentSpanContext = builder.parentSpanContext;
    }

    public static Builder builder(LLM llm, ToolRegistry toolRegistry) {
        return new Builder(llm, toolRegistry);
    }

    /**
     * 运行中注入消息(D6 steer)。仅后台子代理支持(pendingSteers 非空);
     * 顶层/前台 Agent 调用抛 UnsupportedOperationException。
     *
     * <p>注入的消息在子代理下一轮 LLM 调用前作为 user message 加入对话(非即时中断)。</p>
     *
     * @param message 转向消息
     */
    public void steer(String message) {
        if (pendingSteers == null) {
            throw new UnsupportedOperationException("steer 仅支持后台子代理");
        }
        if (message != null && !message.isBlank()) {
            pendingSteers.add(message);
        }
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

        // D9 graceful:循环上界 = maxIterations + graceTurns(顶层和子代理统一,关键点1)
        // graceTurns=0 时回退 0.9.0 硬截断(抛异常)
        int hardLimit = maxIterations;
        int totalLimit = maxIterations + graceTurns;
        SubAgentStatus finalStatus = SubAgentStatus.COMPLETED;
        ChatResult lastResult = null;

        // D2:后台管理器绑定本次 run()。无子代理时 join/awaitAll 都是空操作,零开销。
        BackgroundSubAgentManager bgManager = new BackgroundSubAgentManager(
                this, maxBackgroundRunning, spawnCeiling, backgroundExecutor, eventConsumer, awaitTimeoutMs);
        String parentRunId = tracer != null ? traceId : traceId;  // run() 标识

        try {
            for (int round = 0; round < totalLimit; round++) {
                // D9:到达 hardLimit 时自动注入"收尾"消息(grace 阶段开始)
                if (round == hardLimit && graceTurns > 0) {
                    String wrapUp = "已达轮数上限,请立即收尾输出最终结果。";
                    if (pendingSteers != null) {
                        pendingSteers.add(wrapUp);
                    } else {
                        messages.add(Message.user(wrapUp));
                    }
                    finalStatus = SubAgentStatus.STEERED;
                }

                // D6 steer 检查点:每轮 LLM 调用前 drain pendingSteers(仅子代理实例)
                if (pendingSteers != null) {
                    String steer;
                    while ((steer = pendingSteers.poll()) != null) {
                        messages.add(Message.user(steer));
                    }
                }

                if (eventConsumer != null) {
                    eventConsumer.accept(new AgentEvent.RoundStart(round + 1));
                }

                // trace 启用时：每轮开一个 llm span，覆盖「LLM 调用 + 本轮所有工具执行」。
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
                    lastResult = result;

                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.RoundEnd(round + 1));
                    }

                    if (!result.hasToolCalls()) {
                        // 准备 Complete:D3 强制等待所有后台完成或超时
                        if (bgManager.hasRunning()) {
                            JoinResult jr = bgManager.awaitAll(awaitTimeoutMs);
                            if (jr.hasUnconsumed()) {
                                messages.add(jr.mergedMessage());  // 注入后台结果,让 LLM 再看一轮
                                continue;
                            }
                        }
                        // 真正 Complete:无遗留后台
                        if (eventConsumer != null) {
                            eventConsumer.accept(new AgentEvent.Complete(result));
                        }
                        return result;
                    }

                    List<ToolCall> toolCalls = result.toolCalls();
                    boolean parallel = toolCalls.size() > 1 && executor != null;
                    final Message assistantMessage = result.toMessage();
                    final String runId = parentRunId;

                    if (parallel) {
                        // 并行执行多个工具调用，按源顺序组装结果。
                        final List<Message> parentSnapshot = List.copyOf(messages);
                        final SpanContext llmCtx = tracer != null ? Tracer.current() : null;

                        // skill 在并行路径前置分流:skill 走独立路径(不进 executeWithToolSpan),
                        // 其余 toolCall 进并行池。skill 与 tool 名在 build() 时已校验互斥。
                        List<ToolCall> realToolCalls = new ArrayList<>();
                        for (ToolCall tc : toolCalls) {
                            if (skillRegistry != null && skillRegistry.contains(tc.name())) {
                                DispatchResult dr = executeSkill(tc, eventConsumer);
                                messages.add(Message.toolResult(tc.id(), dr.toolResultText));
                                messages.addAll(dr.extraMessages);
                            } else {
                                realToolCalls.add(tc);
                            }
                        }

                        if (!realToolCalls.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            CompletableFuture<String>[] futures = new CompletableFuture[realToolCalls.size()];
                            for (int i = 0; i < realToolCalls.size(); i++) {
                                ToolCall tc = realToolCalls.get(i);
                                if (eventConsumer != null) {
                                    eventConsumer.accept(new AgentEvent.ToolStart(tc.name(), tc.arguments()));
                                }
                                final ToolCall finalTc = tc;
                                futures[i] = CompletableFuture.supplyAsync(
                                        () -> executeWithToolSpan(finalTc, llmCtx, assistantMessage, parentSnapshot, traceId, runId, bgManager),
                                        executor)
                                        .whenComplete((output, err) -> {
                                            if (eventConsumer != null) {
                                                eventConsumer.accept(new AgentEvent.ToolEnd(finalTc.name(),
                                                        err != null ? "工具执行失败: " + err.getMessage() : output));
                                            }
                                        });
                            }
                            CompletableFuture.allOf(futures).join();
                            for (int i = 0; i < realToolCalls.size(); i++) {
                                try {
                                    messages.add(Message.toolResult(realToolCalls.get(i).id(), futures[i].get()));
                                } catch (Exception e) {
                                    messages.add(Message.toolResult(realToolCalls.get(i).id(), "工具执行失败: " + e.getMessage()));
                                }
                            }
                        }
                    } else {
                        // 单个工具调用或未配置 executor，串行执行
                        final List<Message> parentSnapshot = List.copyOf(messages);
                        for (ToolCall tc : toolCalls) {
                            if (skillRegistry != null && skillRegistry.contains(tc.name())) {
                                DispatchResult dr = executeSkill(tc, eventConsumer);
                                messages.add(Message.toolResult(tc.id(), dr.toolResultText));
                                messages.addAll(dr.extraMessages);
                                continue;
                            }
                            if (eventConsumer != null) {
                                eventConsumer.accept(new AgentEvent.ToolStart(tc.name(), tc.arguments()));
                            }
                            String output = executeWithToolSpan(tc, null, assistantMessage, parentSnapshot, traceId, runId, bgManager);
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

                // D3 轮末 join:把已完成的后台结果注入(死循环防护:只注入已完成,不 spawn)
                JoinResult jr = bgManager.joinCompleted();
                if (!jr.isEmpty()) {
                    messages.add(jr.mergedMessage());
                }
            }

            // 循环耗尽(hardLimit + graceTurns 用尽)
            if (graceTurns == 0) {
                // 0.9.0 硬截断语义:抛异常
                AgentException ex = new AgentException("超出最大迭代次数: " + maxIterations);
                if (eventConsumer != null) {
                    eventConsumer.accept(new AgentEvent.AgentError(ex));
                }
                throw ex;
            }
            // graceful 硬中断:返回部分结果(不抛异常,D9)
            // 背景任务如有遗留,先 awaitAll
            if (bgManager.hasRunning()) {
                JoinResult jr = bgManager.awaitAll(awaitTimeoutMs);
                if (jr.hasUnconsumed() && lastResult != null) {
                    // 已耗尽循环,无法再 continue;合并结果追加到最终文本
                }
            }
            finalStatus = SubAgentStatus.ABORTED;
            if (lastResult != null) {
                if (eventConsumer != null) {
                    eventConsumer.accept(new AgentEvent.Complete(lastResult));
                }
                return lastResult;
            }
            // 兜底
            return new ChatResult("(已达最大轮数,无输出)", null, null, null, null);
        } finally {
            // D2:run() 结束清理后台任务
            bgManager.close();
        }
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
                                       Message assistantMessage, List<Message> parentMessages, String traceId,
                                       String runId, BackgroundSubAgentManager bgManager) {
        if (tracer == null) {
            return safeExecute(tc, assistantMessage, parentMessages, traceId, runId, bgManager);
        }
        Tracer.ScopedSpan toolSpan = parentCtx != null
                ? tracer.startChild(parentCtx, SpanAttributes.SpanType.TOOL, tc.name())
                : tracer.startSpan(SpanAttributes.SpanType.TOOL, tc.name());
        // 注意：safeExecute 对工具/子代理执行错误采用软失败（捕获后回灌 LLM，不抛出），
        // 错误状态由 RecordingCallback.onToolError 标记到 span；这里只兜底真正向上传播的异常
        // （如拦截器抛出的 AgentException）。
        try {
            return safeExecute(tc, assistantMessage, parentMessages, traceId, runId, bgManager);
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
    private String safeExecute(ToolCall tc, Message assistantMessage, List<Message> parentMessages,
                               String traceId, String runId, BackgroundSubAgentManager bgManager) {
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

            // 3. 实际执行（按工具类型五路分流,瑕疵B）
            String result;
            boolean isError = false;
            try {
                result = dispatchExecute(tc, assistantMessage, parentMessages, traceId, runId, bgManager);
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
     * Skill 注入执行:取出 skill content → 触发 SkillActivated 事件 → 产出 tool result 确认 +
     * 按配置注入消息。skill 走独立路径,不经过 executeWithToolSpan/safeExecute(不碰 interceptor /
     * Tool callback —— D9 锁定 skill 用自己的 SkillActivated 事件)。
     *
     * @param tc             LLM 的 skill 点选调用(无参数)
     * @param eventConsumer  AgentEvent 消费者(可能 null)
     * @return tool result 文本 + 额外注入消息
     */
    private DispatchResult executeSkill(ToolCall tc, java.util.function.Consumer<AgentEvent> eventConsumer) {
        SkillDefinition def = skillRegistry.get(tc.name());
        String content = def.content();

        // D9: skill 激活事件(独立于 Tool callback)
        if (eventConsumer != null) {
            eventConsumer.accept(new AgentEvent.SkillActivated(def.name(), content.length()));
        }

        // D9: trace span(skill 激活节点;复用 TOOL 类型 + span name 区分,design §5.3)
        Tracer.ScopedSpan skillSpan = tracer != null
                ? tracer.startSpan(SpanAttributes.SpanType.TOOL, "skill:" + def.name()) : null;
        try {
            // 双消息注入:tool result(满足 tool-calling 协议)+ 按配置注入的知识。
            String toolResultText = "(skill " + def.name() + " 已加载,详见 Skill 注入内容)";
            Message injection = skillInjectionMode == SkillInjectionMode.USER
                    ? Message.user("[Skill: " + def.name() + "]\n" + content)
                    : Message.system(content);
            return new DispatchResult(toolResultText, java.util.Collections.singletonList(injection));
        } finally {
            if (skillSpan != null) {
                skillSpan.close();
            }
        }
    }

    /**
     * 按工具类型分流执行(五路分流,瑕疵B):
     * <ol>
     *   <li>独立子代理 tool:解析 run_in_background → 前台同步 / 后台 spawn</li>
     *   <li>delegate tool:解析 agentName + run_in_background → 同上</li>
     *   <li>get_subagent_result tool → bgManager.getResult(D3)</li>
     *   <li>steer_subagent tool → bgManager.steer(D6)</li>
     *   <li>普通工具 → ToolRegistry.execute(0.9.0 不变)</li>
     * </ol>
     */
    private String dispatchExecute(ToolCall tc, Message assistantMessage, List<Message> parentMessages,
                                   String traceId, String runId, BackgroundSubAgentManager bgManager) {
        // 1. 独立子代理 tool：tool 名即子代理名
        if (toolRegistry.hasSubAgent(tc.name())) {
            SubAgentDefinition def = toolRegistry.getSubAgent(tc.name());
            String task = parseTaskArg(tc.arguments(), def.name());
            boolean background = parseBackgroundArg(tc.arguments());
            if (background) {
                return bgManager.spawn(def, task, assistantMessage, parentMessages, runId);
            }
            return executeSubAgentTool(def, task, assistantMessage, parentMessages, traceId, runId, false);
        }
        // 2. 通用 delegate tool：先解析 agentName 再定位子代理
        if (ToolRegistry.DELEGATE_TOOL_NAME.equals(tc.name())) {
            String agentName = parseAgentNameArg(tc.arguments());
            SubAgentDefinition def = toolRegistry.getSubAgent(agentName);
            String task = parseTaskArg(tc.arguments(), agentName);
            boolean background = parseBackgroundArg(tc.arguments());
            if (background) {
                return bgManager.spawn(def, task, assistantMessage, parentMessages, runId);
            }
            return executeSubAgentTool(def, task, assistantMessage, parentMessages, traceId, runId, false);
        }
        // 3. get_subagent_result tool(D3)
        if (ToolRegistry.GET_RESULT_TOOL_NAME.equals(tc.name())) {
            String subAgentId = parseStringArg(tc.arguments(), "subagent_id", "get_subagent_result");
            boolean wait = parseBooleanArg(tc.arguments(), "wait", false);
            return bgManager.getResult(subAgentId, wait);
        }
        // 4. steer_subagent tool(D6)
        if (ToolRegistry.STEER_TOOL_NAME.equals(tc.name())) {
            String subAgentId = parseStringArg(tc.arguments(), "subagent_id", "steer_subagent");
            String message = parseStringArg(tc.arguments(), "message", "steer_subagent");
            return bgManager.steer(subAgentId, message);
        }
        // 5. 普通工具：维持现状
        return toolRegistry.execute(tc.name(), tc.arguments());
    }

    /**
     * 执行前台子代理：构造裁剪上下文(或 resume)→ 动态构建子代理 Agent → 运行 → 返回最终文本。
     *
     * <p>D7 resume:配置了 chatMemoryStore 且有历史 → 走 resume(不注入父上下文);
     * 否则首次委派注入父上下文切片。</p>
     * <p>D12 后台/前台 context 截断:前台全量,后台截断(此处 background=false 用全量)。</p>
     * <p>D6 steer:前台不启用 enableSteer(无触发源)。</p>
     *
     * @param background 是否后台模式(影响 context 截断 + conversationId + steer 启用)
     * @param recordId   后台子代理的 recordId(后台 conversationId 隔离用,瑕疵C);前台传 null
     */
    private String executeSubAgentTool(SubAgentDefinition def, String task,
                                       Message assistantMessage, List<Message> parentMessages,
                                       String traceId, String runId, boolean background) {
        SubAgentResult sr = runSubAgentInternal(def, task, assistantMessage, parentMessages,
                runId, background, null);
        return sr.displayText();
    }

    /**
     * 后台子代理执行入口(供 BackgroundSubAgentManager 调用,D6 启用 steer)。
     *
     * @param record 后台子代理记录(含 steer 队列 + recordId)
     */
    SubAgentResult executeBackgroundSubAgent(SubAgentRecord record, SubAgentDefinition def, String task,
                                             Message assistantMessage, List<Message> parentMessages,
                                             String runId) {
        return runSubAgentInternal(def, task, assistantMessage, parentMessages, runId, true, record);
    }

    /**
     * 子代理执行核心(D5 隔离 + D6 steer + D7 resume + D9 graceful + D12 截断 + 瑕疵C conversationId)。
     */
    private SubAgentResult runSubAgentInternal(SubAgentDefinition def, String task,
                                               Message assistantMessage, List<Message> parentMessages,
                                               String runId, boolean background, SubAgentRecord record) {
        // D7 resume + 瑕疵C conversationId
        com.non.chain.memory.ChatMemoryStore store = def.chatMemoryStore();
        String conversationId = background
                ? runId + ":" + def.name() + ":" + (record != null ? record.id() : "noid")
                : runId + ":" + def.name();
        boolean isResume = false;
        List<Message> history = null;
        if (store != null) {
            try {
                history = store.getMessages(conversationId);
                isResume = history != null && !history.isEmpty();
            } catch (Exception ignored) {
                isResume = false;
            }
        }

        // 1. 组装子代理消息
        List<Message> childMessages = new ArrayList<>();
        childMessages.add(Message.system(def.systemPrompt()));
        if (isResume) {
            // resume:不注入父上下文(D12),用自己的历史
            childMessages.addAll(history);
        } else {
            // 首次委派:注入父上下文切片
            ContextSelector selector = def.contextSelector() != null
                    ? def.contextSelector()
                    : (background ? BACKGROUND_CONTEXT_SELECTOR : DEFAULT_CONTEXT_SELECTOR);
            List<Message> parentSlice = selector.select(parentMessages, assistantMessage, task);
            childMessages.addAll(parentSlice);
        }
        childMessages.add(Message.user(task));

        // 2. 动态构造子代理
        LLM childLlm = def.llmOverride() != null ? def.llmOverride() : this.llm;
        ToolRegistry childRegistry = def.toolRegistry() != null ? def.toolRegistry() : new ToolRegistry();
        Agent.Builder childBuilder = Agent.builder(childLlm, childRegistry)
                .systemPrompt(def.systemPrompt())
                .graceTurns(DEFAULT_GRACE_TURNS)   // D9 子代理统一 graceful
                .skillInjectionMode(skillInjectionMode)
                .callback(ChainCallbackUtil.noop()); // 父/子【用户面】callback 隔离(既有承诺不变)
        if (background) {
            childBuilder.enableSteer();  // D6 后台子代理启用 steer
        }
        if (def.maxIterations() != null) {
            childBuilder.maxIterations(def.maxIterations());
        }
        for (BeforeToolCall b : def.beforeInterceptors()) {
            childBuilder.addBeforeToolCall(b);
        }
        for (AfterToolCall a : def.afterInterceptors()) {
            childBuilder.addAfterToolCall(a);
        }
        // D13 子代理 skill 预加载:把 def 的 skillRegistry 传给子代理
        if (def.skillRegistry() != null) {
            childBuilder.skillRegistry(def.skillRegistry());
        }
        // 边界1(SubAgent 全树下钻):录制层不隔离,注入父 tracer + current SpanContext
        if (tracer != null) {
            SpanContext subParentCtx = Tracer.current();
            if (subParentCtx != null) {
                childBuilder.trace(tracer.store()).parentSpanContext(subParentCtx);
            }
        }
        Agent child = childBuilder.build();

        // D6 后台模式:把 child 注册到 record(steer 桥接)+ drain 已累积的 steer
        if (background && record != null) {
            record.childAgent(child);
            // drain spawn 前已注入的 steer 到 child 内部队列
            String s;
            while ((s = record.pendingSteers().poll()) != null) {
                child.steer(s);
            }
            // 运行中后续的 steer 通过 bgManager.steer → child.steer 直接桥接(见 record.childAgent)
        }

        // 3. 运行子代理
        ChatResult childResult = child.run(childMessages);
        String content = childResult.content() != null ? childResult.content() : "";

        // 4. D7 resume:存回历史(去掉 systemPrompt)
        if (store != null) {
            try {
                List<Message> toStore = new ArrayList<>();
                for (Message m : childMessages) {
                    if (!"system".equals(m.role())) {
                        toStore.add(m);
                    }
                }
                store.updateMessages(conversationId, toStore);
            } catch (Exception ignored) {
                // 存储失败不影响主流程
            }
        }

        // 5. 推断 status(瑕疵A:子代理内部 graceful 已处理,这里从结果推断)
        // 子代理 run 不抛异常(graceful),content 即最终文本。status 默认 COMPLETED。
        return new SubAgentResult(content, SubAgentStatus.COMPLETED);
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
        // D3/D6:有子代理时额外暴露控制工具
        tools.addAll(toolRegistry.getSubAgentControlTools());
        // skill:无参数 function 拼在末尾(skillRegistry==null 时跳过,零影响)
        if (skillRegistry != null) {
            tools.addAll(skillRegistry.getSkillTools());
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

    /** D12 后台默认截断:只取最近 4 条可见消息(避免并行后台 token 爆炸) */
    private static final int BACKGROUND_CONTEXT_WINDOW = 4;
    private static final ContextSelector BACKGROUND_CONTEXT_SELECTOR = (parentMessages, assistantMessage, task) -> {
        List<Message> visible = new ArrayList<>();
        for (Message m : parentMessages) {
            if (m.llmVisible()) {
                visible.add(m);
            }
        }
        int n = Math.min(visible.size(), BACKGROUND_CONTEXT_WINDOW);
        return new ArrayList<>(visible.subList(visible.size() - n, visible.size()));
    };

    /** D11 从 tool arguments 解析 run_in_background(默认 false) */
    private boolean parseBackgroundArg(String arguments) {
        try {
            Map<String, Object> map = parseArgsMap(arguments, "子代理调用");
            Object bg = map.get("run_in_background");
            return bg != null && Boolean.parseBoolean(bg.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 String 参数 */
    private String parseStringArg(String arguments, String key, String toolName) {
        Map<String, Object> map = parseArgsMap(arguments, toolName);
        Object val = map.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new IllegalArgumentException(toolName + " 缺少 " + key + " 参数");
        }
        return val.toString();
    }

    /** 解析 boolean 参数(带默认值) */
    private boolean parseBooleanArg(String arguments, String key, boolean defaultValue) {
        try {
            Map<String, Object> map = parseArgsMap(arguments, key);
            Object val = map.get(key);
            return val != null ? Boolean.parseBoolean(val.toString()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static class Builder {

        private final LLM llm;
        private final ToolRegistry toolRegistry;
        private String systemPrompt;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private int graceTurns = DEFAULT_GRACE_TURNS;   // D9:默认 3;0 = 禁用 graceful(回退0.9.0硬截断抛异常)
        private ChainCallback callback;
        private ChatMemory memory;
        private Executor executor = ForkJoinPool.commonPool();
        private SubAgentExposureMode subAgentExposureMode = SubAgentExposureMode.DIRECT;
        private final List<BeforeToolCall> beforeInterceptors = new ArrayList<>();
        private final List<AfterToolCall> afterInterceptors = new ArrayList<>();

        /** skill 注册中心;null = 无 skill。 */
        private SkillRegistry skillRegistry;
        /** skill 注入模式;默认 SYSTEM，保持既有消息序列。 */
        private SkillInjectionMode skillInjectionMode = SkillInjectionMode.SYSTEM;

        // ---- 后台子代理配置(D4)----
        private ExecutorService backgroundExecutor;       // 默认 null → run() 时按 maxBackgroundRunning 创建
        private int maxBackgroundRunning = 4;             // D4 运行上限
        private Integer spawnCeiling;                     // D4 熔断:null = 自适应(maxIterations × maxRunning × 2)
        private long awaitTimeoutMs = 60_000;             // D3 Complete 前等待超时

        // ---- steer(D6):null = 未启用(顶层/前台 Agent),子代理构造时 enableSteer ----
        private BlockingQueue<String> pendingSteers;

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
         * 注册 skill 中心。skill 是过程性知识/指令文本,LLM 通过 tool-calling 点选后按
         * {@link #skillInjectionMode(SkillInjectionMode)} 注入消息。不调用则该 Agent 无 skill 能力,
         * 行为与 0.10.0 一致。
         *
         * <p>命名冲突在 {@link #build()} 时校验:skill 名不能与 tool 名 / sub-agent 名 /
         * 框架保留名(delegate_to_subagent 等)重复,否则 fail-fast。</p>
         */
        public Builder skillRegistry(SkillRegistry skillRegistry) {
            this.skillRegistry = skillRegistry;
            return this;
        }

        /**
         * 设置 Skill 被点选后的知识注入角色。
         *
         * <p>默认 {@link SkillInjectionMode#SYSTEM}，保持既有行为。对于不支持在对话中
         * 追加多条 system 消息的模型 Chat Template，可显式设为
         * {@link SkillInjectionMode#USER}；此时内容会带 {@code [Skill: name]} 边界。
         * 传 {@code null} 回退默认值 {@code SYSTEM}。</p>
         */
        public Builder skillInjectionMode(SkillInjectionMode skillInjectionMode) {
            this.skillInjectionMode = skillInjectionMode == null
                    ? SkillInjectionMode.SYSTEM : skillInjectionMode;
            return this;
        }

        /**
         * 设置 graceful max turns 的 grace 阶段轮数(D9)。
         *
         * <ul>
         *   <li>默认 3:超 maxIterations 后注入"收尾"消息,给最多 graceTurns 轮收尾</li>
         *   <li>0:禁用 graceful,回退 0.9.0 硬截断(超 maxIterations 抛 AgentException)</li>
         * </ul>
         * <p><b>破坏性变更(§9.2)</b>:本次顶层和子代理统一走 graceful。设为 0 可恢复 0.9.0 抛异常语义。</p>
         */
        public Builder graceTurns(int graceTurns) {
            this.graceTurns = Math.max(0, graceTurns);
            return this;
        }

        /**
         * 后台子代理线程池(D4)。默认 null → run() 时按 {@link #maxBackgroundRunning} 创建固定池。
         */
        public Builder backgroundExecutor(ExecutorService exec) {
            this.backgroundExecutor = exec;
            return this;
        }

        /**
         * 后台子代理运行上限(D4),默认 4。超出的 spawn 进 FIFO 队列。
         */
        public Builder maxBackgroundRunning(int n) {
            this.maxBackgroundRunning = Math.max(1, n);
            return this;
        }

        /**
         * 后台子代理总派发熔断(D4)。默认 null = 自适应(maxIterations × maxRunning × 2)。
         */
        public Builder spawnCeiling(int ceiling) {
            this.spawnCeiling = Math.max(1, ceiling);
            return this;
        }

        /**
         * Complete 前等待后台完成的全局超时(D3),默认 60 秒。
         */
        public Builder awaitTimeoutMs(long ms) {
            this.awaitTimeoutMs = Math.max(1000, ms);
            return this;
        }

        /**
         * 启用 steer 队列(D6)。仅子代理构造时框架内部调用,顶层/前台 Agent 不启用。
         */
        Builder enableSteer() {
            this.pendingSteers = new java.util.concurrent.LinkedBlockingQueue<>();
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
            // D12: skill 命名冲突校验(skill 名不能与 tool / sub-agent / 框架保留名重复)
            if (skillRegistry != null) {
                validateSkillNaming(skillRegistry, toolRegistry);
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
         * D12: skill 命名冲突校验。skill 名不能与已注册的 tool 名 / sub-agent 名 /
         * 框架保留名(delegate_to_subagent / get_subagent_result / steer_subagent)重复,
         * 否则 fail-fast 抛 IllegalStateException。
         */
        private static void validateSkillNaming(SkillRegistry sr, ToolRegistry tr) {
            java.util.Set<String> reserved = new java.util.HashSet<>();
            for (Tool t : tr.getRegularTools()) {
                reserved.add(t.name());
            }
            reserved.addAll(tr.subAgentNames());
            reserved.add(ToolRegistry.DELEGATE_TOOL_NAME);
            reserved.add(ToolRegistry.GET_RESULT_TOOL_NAME);
            reserved.add(ToolRegistry.STEER_TOOL_NAME);

            for (String skillName : sr.skillNames()) {
                if (reserved.contains(skillName)) {
                    throw new IllegalStateException(
                            "skill 名 '" + skillName + "' 与已注册的工具/子代理/保留名冲突");
                }
            }
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

    /**
     * Skill 执行产物:tool result 文本 + 按配置生成的注入消息。
     *
     * <p>仅 skill 路径使用(普通 tool / sub-agent 走 {@code dispatchExecute} 返回 String,不经此类型)。
     * skill 被点选后,产出 tool result(满足 tool-calling 协议)+ 知识注入两条消息。</p>
     */
    private static final class DispatchResult {
        final String toolResultText;
        final List<Message> extraMessages;

        private DispatchResult(String toolResultText, List<Message> extraMessages) {
            this.toolResultText = toolResultText;
            this.extraMessages = extraMessages;
        }

        static DispatchResult of(String text, List<Message> extra) {
            return new DispatchResult(text, extra);
        }
    }
}
