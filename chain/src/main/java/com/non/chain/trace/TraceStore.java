package com.non.chain.trace;

import java.util.Optional;

/**
 * 可插拔 trace 存储 SPI（design.md §5）。
 *
 * <p>第一版只锁定两个方法：</p>
 * <ul>
 *   <li>{@link #record(Span)}：每完成一个 span（{@code ScopedSpan.close()} 时）调用一次。</li>
 *   <li>{@link #getTrace(String)}：按 runtimeId 拉回整棵 span 树。</li>
 * </ul>
 *
 * <p>{@code search(...)} 查询 API 明确后置（Out Of Scope）。持久化实现（MySQL/Postgres）
 * 作为独立可选模块，类比 {@code chain-mysql}。</p>
 *
 * <p>实现需线程安全：并行工具场景下多个 worker 线程会并发 {@code record} 同一 runtimeId。</p>
 */
public interface TraceStore {

    /**
     * 记录一个已定稿的 span。在 {@code ScopedSpan.close()} 时调用。
     */
    void record(Span span);

    /**
     * 按 runtimeId 拉回完整 span 树（按 startTime 排序）。不存在时返回 {@link Optional#empty()}。
     */
    Optional<Trace> getTrace(String runtimeId);
}
