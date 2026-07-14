package com.non.chain.agent;

import com.non.chain.Message;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.memory.ChatMemoryStore;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 后台子代理管理器(D1/D2/D3/D4/D6 核心编排器)。
 *
 * <p>管理一次父 agent run() 内的后台子代理派发、并发队列、完成结果收集与轮末 join。
 * 生命周期绑定在单次 run() 内(D2):{@link #close()} 取消未完成任务并关闭线程池。</p>
 *
 * <p>由父 Agent 的 doRunWithLoop 入口创建,通过 {@link #spawn} 派发后台子代理,
 * {@link #joinCompleted()} 轮末消费已完成结果,{@link #awaitAll(long)} Complete 前强制等待,
 * {@link #getResult} / {@link #steer} 支持 get_subagent_result / steer_subagent 工具。</p>
 */
public class BackgroundSubAgentManager implements AutoCloseable {

    /** 后台子代理立即返回的 tool result 文本模板 */
    private static final String SPAWN_RESULT_TEMPLATE =
            "后台子代理已派发,id=%s,名称=\"%s\"。用 get_subagent_result 查询结果或等待自动 join。";

    private final Agent parentAgent;
    private final ExecutorService bgExecutor;
    private final int maxRunning;
    private final int spawnCeiling;
    private final Consumer<AgentEvent> eventSink;
    private final long awaitTimeoutMs;

    private final Map<String, SubAgentRecord> running = new ConcurrentHashMap<>();
    private final Deque<QueuedSpawn> pending = new ConcurrentLinkedDeque<>();
    private final List<SubAgentRecord> completedUnconsumed = new CopyOnWriteArrayList<>();
    private final Map<String, SubAgentRecord> allRecords = new ConcurrentHashMap<>();
    private int totalSpawned = 0;
    private volatile boolean closed = false;

    public BackgroundSubAgentManager(Agent parentAgent, int maxRunning, int spawnCeiling,
                                     ExecutorService bgExecutor, Consumer<AgentEvent> eventSink,
                                     long awaitTimeoutMs) {
        this.parentAgent = parentAgent;
        this.maxRunning = maxRunning;
        this.spawnCeiling = spawnCeiling;
        this.bgExecutor = bgExecutor != null ? bgExecutor
                : Executors.newFixedThreadPool(maxRunning);
        this.eventSink = eventSink;
        this.awaitTimeoutMs = awaitTimeoutMs;
    }

    /**
     * spawn 一个后台子代理(D4 含熔断 + 队列)。立即返回 tool result,不阻塞。
     *
     * @param parentToolCallId 触发本次委派的父 tool-call id
     * @param def            子代理定义
     * @param task           委派任务
     * @param assistantMsg   触发本次委派的父 assistant 消息
     * @param parentSnapshot 父消息链快照(只读)
     * @param parentRunId    父 run() 标识(用于 conversationId)
     * @return 给父 LLM 的即时 tool result
     */
    /** 向后兼容入口：未关联父 tool-call。 */
    public synchronized String spawn(SubAgentDefinition def, String task, Message assistantMsg,
                                     List<Message> parentSnapshot, String parentRunId) {
        return spawn(null, def, task, assistantMsg, parentSnapshot, parentRunId);
    }

    public synchronized String spawn(String parentToolCallId, SubAgentDefinition def, String task, Message assistantMsg,
                                     List<Message> parentSnapshot, String parentRunId) {
        if (closed) {
            return "后台管理器已关闭,拒绝新任务";
        }
        // D4 熔断
        if (totalSpawned >= spawnCeiling) {
            return "已达后台派发上限(" + spawnCeiling + "),拒绝新任务。请用 get_subagent_result 等待现有任务。";
        }
        totalSpawned++;

        CompletableFuture<SubAgentResult> future = new CompletableFuture<>();
        SubAgentRecord record = new SubAgentRecord(def.name(), task, parentToolCallId, future);
        allRecords.put(record.id(), record);
        emit(new AgentEvent.SubAgentSpawned(record.id(), def.name(), task, true));

        // D4 运行上限:超了进队列,否则立即提交
        if (running.size() < maxRunning) {
            submitToExecutor(record, def, task, assistantMsg, parentSnapshot, parentRunId);
        } else {
            pending.addLast(new QueuedSpawn(record, def, task, assistantMsg, parentSnapshot, parentRunId));
        }
        return String.format(SPAWN_RESULT_TEMPLATE, record.id(), def.name());
    }

    /** 提交后台任务到 executor */
    private void submitToExecutor(SubAgentRecord record, SubAgentDefinition def, String task,
                                  Message assistantMsg, List<Message> parentSnapshot, String parentRunId) {
        running.put(record.id(), record);
        emit(new AgentEvent.SubAgentStarted(record.id(), def.name()));
        bgExecutor.submit(() -> {
            try {
                SubAgentResult sr = parentAgent.executeBackgroundSubAgent(
                        record, def, task, assistantMsg, parentSnapshot, parentRunId, this::emit);
                record.markCompleted(sr);
                onTaskDone(record);
                emit(new AgentEvent.SubAgentCompleted(record.id(), def.name(),
                        sr.status(), truncate(sr.content(), 200)));
            } catch (Exception e) {
                record.markFailed(e);
                onTaskDone(record);
                emit(new AgentEvent.SubAgentFailed(record.id(), def.name(), e));
            }
        });
    }

    /** 任务完成回调:从 running 移除,加入 completedUnconsumed,drain 队列 */
    private void onTaskDone(SubAgentRecord record) {
        if (!record.resultConsumed()) {
            completedUnconsumed.add(record);
        }
        // 先登记 completed，再移除 running，避免父循环在两步之间观察到“无运行且无完成”。
        running.remove(record.id());
        drainPending();
    }

    /** drain 等待队列(有 slot 时提交下一个) */
    private void drainPending() {
        while (running.size() < maxRunning && !pending.isEmpty()) {
            QueuedSpawn q = pending.pollFirst();
            if (q != null) {
                submitToExecutor(q.record, q.def, q.task, q.assistantMsg, q.parentSnapshot, q.parentRunId);
            }
        }
    }

    /**
     * 轮末 join(D3):收集已完成未消费的结果,合并成 JoinResult。
     * 不阻塞,不 spawn 新任务(死循环防护)。
     */
    public JoinResult joinCompleted() {
        if (completedUnconsumed.isEmpty()) {
            return JoinResult.empty();
        }
        List<SubAgentRecord> batch = new ArrayList<>();
        for (SubAgentRecord r : completedUnconsumed) {
            if (!r.resultConsumed()) {
                batch.add(r);
                r.markConsumed();
            }
        }
        completedUnconsumed.clear();
        return new JoinResult(batch, false);
    }

    /**
     * Complete 前强制等待(D3):阻塞直到所有后台完成或超时。
     * 超时后强制取消未完成的,允许 Complete。
     */
    public JoinResult awaitAll(long timeoutMs) {
        // 等待所有 running 完成
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (SubAgentRecord r : new ArrayList<>(running.values())) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            try {
                r.future().get(remain, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // 超时或中断:继续
            }
        }
        boolean timeout = !running.isEmpty();
        if (timeout) {
            // 取消未完成的
            for (SubAgentRecord r : new ArrayList<>(running.values())) {
                r.future().cancel(true);
                running.remove(r.id());
            }
        }
        return joinCompleted();
    }

    /**
     * get_subagent_result 后端(D3)。
     *
     * @param id   子代理 id
     * @param wait 是否阻塞等待完成
     * @return 结果文本(含状态信息)
     */
    public String getResult(String id, boolean wait) {
        SubAgentRecord r = allRecords.get(id);
        if (r == null) {
            return "未找到后台子代理: " + id;
        }
        if (wait && !r.isDone()) {
            // 瑕疵E:wait 有超时保护
            try {
                r.future().get(awaitTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return "等待后台子代理 \"" + r.name() + "\" 超时或中断。当前状态: " + r.status();
            }
        }
        r.markConsumed();
        if (!r.isDone()) {
            return "子代理 \"" + r.name() + "\" 仍在运行(id=" + id + ")。请稍后再查。";
        }
        String base = r.result() != null ? r.result() : "(无输出)";
        return "子代理 \"" + r.name() + "\" [" + r.status() + "] id=" + id + ":\n" + base;
    }

    /** steer_subagent 后端(D6):注入转向消息到子代理 steer 队列 */
    public String steer(String id, String message) {
        SubAgentRecord r = allRecords.get(id);
        if (r == null) {
            return "未找到后台子代理: " + id;
        }
        if (r.isDone()) {
            return "子代理 \"" + r.name() + "\" 已完成,无法 steer";
        }
        // 优先桥接到运行中的 child Agent(直接进 child 内部队列)
        Agent child = r.childAgent();
        if (child != null) {
            try {
                child.steer(message);
                emit(new AgentEvent.SubAgentSteered(id, message));
                return "已向后台子代理 \"" + r.name() + "\" 注入转向消息";
            } catch (Exception ignored) {
                // child 不支持 steer 或已结束,回退到 record 队列
            }
        }
        r.pendingSteers().add(message);
        emit(new AgentEvent.SubAgentSteered(id, message));
        return "已向后台子代理 \"" + r.name() + "\" 注入转向消息";
    }

    /** 是否有未完成的后台任务 */
    public boolean hasRunning() {
        return !running.isEmpty() || !pending.isEmpty();
    }

    /** run() 结束清理(D2):取消未完成 + 关闭线程池 */
    @Override
    public void close() {
        closed = true;
        // 取消所有 running
        for (SubAgentRecord r : new ArrayList<>(running.values())) {
            r.future().cancel(true);
        }
        running.clear();
        pending.clear();
        bgExecutor.shutdown();
        try {
            bgExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void emit(AgentEvent event) {
        if (eventSink != null) {
            try {
                eventSink.accept(event);
            } catch (Exception ignored) {
                // 事件发射异常不影响主流程
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 队列中的待执行 spawn */
    private static class QueuedSpawn {
        final SubAgentRecord record;
        final SubAgentDefinition def;
        final String task;
        final Message assistantMsg;
        final List<Message> parentSnapshot;
        final String parentRunId;

        QueuedSpawn(SubAgentRecord record, SubAgentDefinition def, String task,
                    Message assistantMsg, List<Message> parentSnapshot, String parentRunId) {
            this.record = record;
            this.def = def;
            this.task = task;
            this.assistantMsg = assistantMsg;
            this.parentSnapshot = parentSnapshot;
            this.parentRunId = parentRunId;
        }
    }
}
