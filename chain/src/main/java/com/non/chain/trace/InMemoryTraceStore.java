package com.non.chain.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内置 {@link TraceStore} 的内存实现：开箱即用、有界 LRU、线程安全。
 *
 * <p><b>存储模型</b>：{@code LinkedHashMap<runtimeId, SpanEntry>}（access-order=true）。
 * 容量到上限时按访问顺序淘汰最久未访问的整棵树（LRU 容量按「树」计，默认 1000）。</p>
 *
 * <p><b>线程安全</b>：并行工具场景下多个 worker 线程会并发 {@code record} 同一 runtimeId。
 * 这里用「全局结构锁 + 每 runtimeId 的 span 列表独立锁」两级：结构操作（put/get/淘汰）用全局锁，
 * 单棵树的 span 追加用该树自己的锁，避免不同 runtimeId 之间互相阻塞。</p>
 *
 * <p>这是第一版的默认实现（持久化 MySQL/Postgres 实现作为独立可选模块后置）。</p>
 */
public class InMemoryTraceStore implements TraceStore {

    private static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    /** access-order=true 的 LinkedHashMap，维护 LRU 顺序。 */
    private final Map<String, SpanEntry> traces;
    /** 结构锁：保护 traces 的 put/get/淘汰（access-order 操作非线程安全）。 */
    private final Object structLock = new Object();

    /** 每个 runtimeId 的 span 列表 + 追加锁。 */
    private static final class SpanEntry {
        final Object appendLock = new Object();
        final List<Span> spans = new ArrayList<>();
    }

    public InMemoryTraceStore() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryTraceStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正数: " + capacity);
        }
        this.capacity = capacity;
        this.traces = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Override
    public void record(Span span) {
        if (span == null) {
            return;
        }
        String runtimeId = span.runtimeId();
        SpanEntry entry;
        synchronized (structLock) {
            entry = traces.get(runtimeId);
            if (entry == null) {
                entry = new SpanEntry();
                traces.put(runtimeId, entry);
                evictIfNeeded();
            } else {
                // get 已触发 access-order 更新（access-order 的 LinkedHashMap 在 get 时重排）
                traces.get(runtimeId);
            }
        }
        // 追加 span 用 entry 自己的锁，不阻塞其它 runtimeId 的结构操作
        synchronized (entry.appendLock) {
            entry.spans.add(span);
        }
    }

    private void evictIfNeeded() {
        while (traces.size() > capacity) {
            // 移除最久未访问的 key（LinkedHashMap access-order 下即第一个）
            String oldest = traces.keySet().iterator().next();
            traces.remove(oldest);
        }
    }

    @Override
    public Optional<Trace> getTrace(String runtimeId) {
        if (runtimeId == null) {
            return Optional.empty();
        }
        List<Span> snapshot;
        synchronized (structLock) {
            SpanEntry entry = traces.get(runtimeId); // 触发 access-order 更新
            if (entry == null) {
                return Optional.empty();
            }
            synchronized (entry.appendLock) {
                if (entry.spans.isEmpty()) {
                    return Optional.empty();
                }
                snapshot = new ArrayList<>(entry.spans);
            }
        }
        snapshot.sort(Comparator.comparingLong(Span::startTimeMs));
        // conversationId 第一版从 root span 的 conversation_id 载荷取（可空）
        String conversationId = null;
        for (Span s : snapshot) {
            if (s.parentSpanId() == null) {
                Object cid = s.attributes().get(SpanAttributes.CONVERSATION_ID);
                if (cid != null) {
                    conversationId = cid.toString();
                }
                break;
            }
        }
        return Optional.of(new Trace(runtimeId, conversationId, snapshot));
    }

    /** 当前持有的 trace 树数量（测试用）。 */
    public int size() {
        synchronized (structLock) {
            return traces.size();
        }
    }
}
