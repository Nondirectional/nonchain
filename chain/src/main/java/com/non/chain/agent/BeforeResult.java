package com.non.chain.agent;

/**
 * before 拦截器的返回类型，表达「放行」或「阻止执行」。
 *
 * <p>阻止时 {@link #reason()} 作为 error tool result 回灌 LLM。不可变，仅通过静态工厂构造。</p>
 */
public final class BeforeResult {

    private final boolean blocked;
    private final String reason;

    private BeforeResult(boolean blocked, String reason) {
        this.blocked = blocked;
        this.reason = reason;
    }

    /** 放行：允许工具执行。 */
    public static BeforeResult pass() {
        return new BeforeResult(false, null);
    }

    /** 阻止执行，reason 作为 error tool result 回灌 LLM。reason 为 null 时用默认文案。 */
    public static BeforeResult block(String reason) {
        return new BeforeResult(true, reason == null || reason.isBlank() ? "工具执行被拦截" : reason);
    }

    public boolean blocked() {
        return blocked;
    }

    /** 仅当 {@link #blocked()} 为 true 时有意义。 */
    public String reason() {
        return reason;
    }
}
