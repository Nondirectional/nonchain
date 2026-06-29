package com.non.chain.trace;

/**
 * 执行链路遥测的「失败路径 marker」：作为 suppressed exception 附加到业务异常上，
 * 携带 {@code runtimeId}，供 {@link TraceRuntimeIds#find(Throwable)} 提取。
 *
 * <p><b>设计动机（Decision 9）</b>：失败路径也要能把 runtimeId 暴露给调用方，但
 * <b>不能改变既有异常类型/主异常语义</b>。所以不用包装异常（会改变 {@code catch} 语义），
 * 而是用 {@code Throwable.addSuppressed(marker)} 把 runtimeId 挂到原异常对象上。</p>
 *
 * <p>本类刻意继承 {@link RuntimeException} 但<b>不应被抛出</b>——它只作为 suppressed 标记载体存在。
 * 它只覆盖 {@link #fillInStackTrace()} 返回空栈以避免无谓开销。</p>
 */
public final class TraceMarker extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** marker 在异常链里的识别标记前缀（{@link TraceRuntimeIds#find} 用它定位）。 */
    static final String MARKER_PREFIX = "[nonchain-trace] runtimeId=";

    private final String runtimeId;

    public TraceMarker(String runtimeId) {
        super(MARKER_PREFIX + runtimeId);
        this.runtimeId = runtimeId;
    }

    /** 该 marker 携带的 runtime id。 */
    public String runtimeId() {
        return runtimeId;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // 不需要栈轨迹（仅作标记），避免无谓开销
        return this;
    }
}
