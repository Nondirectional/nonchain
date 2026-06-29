package com.non.chain.trace;

import java.util.Optional;

/**
 * 失败路径 runtimeId 提取辅助 API（Decision 9）。
 *
 * <p>失败路径下 {@code Agent.run} / {@code Graph.run} 直接抛异常，调用方拿不到返回值里的
 * {@code runtimeId}。本类从「异常链 + 被抑制异常」里提取 trace marker 携带的 runtimeId，
 * 让调用方仍能拉回已录制的失败 trace。</p>
 *
 * <p><b>不改变既有异常类型/主异常语义</b>：marker 仅作为 {@code suppressed} 附加，主异常对象/类型不变。</p>
 *
 * <pre>{@code
 * try {
 *     agent.run("...");
 * } catch (RuntimeException e) {
 *     Optional<String> rid = TraceRuntimeIds.find(e);
 *     rid.ifPresent(id -> traceStore.getTrace(id)); // 回捞失败 trace
 *     throw e; // 原异常语义不变
 * }
 * }</pre>
 */
public final class TraceRuntimeIds {

    private TraceRuntimeIds() {}

    /**
     * 从异常链（cause）+ 被抑制异常（suppressed）里提取 trace marker 携带的 runtimeId。
     *
     * @param throwable 业务异常（通常是被 catch 到的顶层异常）
     * @return 找到则返回 runtimeId，否则 {@link Optional#empty()}
     */
    public static Optional<String> find(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            String found = scanOne(t);
            if (found != null) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /** 扫描单个 throwable 自身及其 suppressed。 */
    private static String scanOne(Throwable t) {
        if (isMarker(t)) {
            return ((TraceMarker) t).runtimeId();
        }
        Throwable[] suppressed = t.getSuppressed();
        if (suppressed != null) {
            for (Throwable s : suppressed) {
                if (isMarker(s)) {
                    return ((TraceMarker) s).runtimeId();
                }
                // suppressed 也可能有自己的 suppressed（递归一层）
                String nested = scanOne(s);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static boolean isMarker(Throwable t) {
        return t instanceof TraceMarker;
    }

    /**
     * 把 marker 作为 suppressed 附加到异常上（不改变原异常类型/消息）。
     * 若异常已有同 runtimeId 的 marker 则不重复附加。
     */
    public static void attach(Throwable throwable, String runtimeId) {
        if (throwable == null || runtimeId == null) {
            return;
        }
        // 避免重复附加
        if (find(throwable).filter(runtimeId::equals).isPresent()) {
            return;
        }
        throwable.addSuppressed(new TraceMarker(runtimeId));
    }
}
