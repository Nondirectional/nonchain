package com.non.chain.callback;

/**
 * ChainCallback 工具类
 */
public final class ChainCallbackUtil {

    private static final ChainCallback NOOP = new ChainCallback() {};

    private ChainCallbackUtil() {}

    /**
     * 获取空实现的回调（所有方法为 no-op）
     */
    public static ChainCallback noop() {
        return NOOP;
    }
}
