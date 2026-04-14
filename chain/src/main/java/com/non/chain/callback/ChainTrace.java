package com.non.chain.callback;

import java.util.UUID;

/**
 * 基于 ThreadLocal 的 traceId 管理，用于关联同一次 Agent 迭代中的 LLM + Tool 调用。
 *
 * <p>Agent 在 run() 时自动设置 traceId，LLM 和 Tool 的事件自动携带。</p>
 */
public final class ChainTrace {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private ChainTrace() {}

    /**
     * 设置当前 traceId
     */
    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    /**
     * 获取当前 traceId
     */
    public static String get() {
        return TRACE_ID.get();
    }

    /**
     * 清除当前 traceId
     */
    public static void clear() {
        TRACE_ID.remove();
    }

    /**
     * 生成新的 traceId
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
