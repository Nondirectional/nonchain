package com.non.chain.agent;

import com.non.chain.Message;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 后台子代理运行时状态记录(D2/D3/D6)。
 *
 * <p>每个后台 spawn 创建一个 record,由 {@link BackgroundSubAgentManager} 管理。
 * 生命周期绑定在单次父 agent run() 内(D2)。承载:</p>
 * <ul>
 *   <li>异步执行句柄({@link #future})</li>
 *   <li>运行状态({@link #status},关联 D9 graceful)</li>
 *   <li>最终结果文本({@link #result})</li>
 *   <li>steer 注入队列({@link #pendingSteers},D6)</li>
 *   <li>消费标记({@link #resultConsumed},防重复 join)</li>
 * </ul>
 */
public final class SubAgentRecord {

    private final String id;                    // UUID,conversationId 后台隔离用(瑕疵C)
    private final String name;                  // 子代理名
    private final String task;                  // 本次委派任务
    private final String parentToolCallId;      // 触发本次委派的父 tool-call id
    private final Instant spawnedAt;

    private final CompletableFuture<SubAgentResult> future;
    private volatile Instant completedAt;
    private volatile SubAgentStatus status = SubAgentStatus.RUNNING;
    private volatile String result;             // 子代理最终文本
    private volatile boolean resultConsumed;     // 是否已被 join/get_subagent_result 消费
    private final BlockingQueue<String> pendingSteers = new LinkedBlockingQueue<>();  // D6
    private volatile Agent childAgent;           // D6 steer 桥接:运行中的子代理实例

    /** 向后兼容构造：未关联父 tool-call。 */
    public SubAgentRecord(String name, String task, CompletableFuture<SubAgentResult> future) {
        this(name, task, null, future);
    }

    public SubAgentRecord(String name, String task, String parentToolCallId,
                          CompletableFuture<SubAgentResult> future) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.task = task;
        this.parentToolCallId = parentToolCallId;
        this.future = future;
        this.spawnedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String task() {
        return task;
    }

    public String parentToolCallId() {
        return parentToolCallId;
    }

    public Instant spawnedAt() {
        return spawnedAt;
    }

    public CompletableFuture<SubAgentResult> future() {
        return future;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public SubAgentStatus status() {
        return status;
    }

    public String result() {
        return result;
    }

    public boolean resultConsumed() {
        return resultConsumed;
    }

    public BlockingQueue<String> pendingSteers() {
        return pendingSteers;
    }

    /** D6:运行中的子代理实例(steer 桥接用) */
    public Agent childAgent() {
        return childAgent;
    }

    /** 设置运行中的子代理实例(steer 桥接用) */
    public void childAgent(Agent child) {
        this.childAgent = child;
    }

    /** 标记完成(正常/grace 收尾/硬中断),填充结果 */
    public void markCompleted(SubAgentResult sr) {
        this.status = sr.status();
        this.result = sr.content() != null ? sr.content() : "";
        this.completedAt = Instant.now();
        this.future.complete(sr);
    }

    /** 标记失败 */
    public void markFailed(Throwable error) {
        this.status = SubAgentStatus.FAILED;
        this.result = "后台子代理执行失败: " + (error != null ? error.getMessage() : "未知错误");
        this.completedAt = Instant.now();
        this.future.completeExceptionally(error != null ? error : new RuntimeException(this.result));
    }

    /** 标记结果已被消费(join/get_subagent_result) */
    public void markConsumed() {
        this.resultConsumed = true;
    }

    /** 是否已完成(含失败/中断) */
    public boolean isDone() {
        return status != SubAgentStatus.RUNNING;
    }
}
