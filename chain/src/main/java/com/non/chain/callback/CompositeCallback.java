package com.non.chain.callback;

import com.non.chain.callback.event.*;
import com.non.chain.flow.GraphEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 多订阅者组合回调。将多个 ChainCallback 组合为一个，每个回调独立执行，
 * 单个回调的异常不会影响其他回调和主流程。
 *
 * <pre>{@code
 * ChainCallback multi = CompositeCallback.of(
 *     new LoggingCallback(),
 *     new MetricsCallback()
 * );
 * }</pre>
 */
public class CompositeCallback implements ChainCallback {

    private final List<ChainCallback> callbacks;

    private CompositeCallback(List<ChainCallback> callbacks) {
        this.callbacks = Collections.unmodifiableList(new ArrayList<>(callbacks));
    }

    /**
     * 组合多个回调
     */
    public static CompositeCallback of(ChainCallback... callbacks) {
        return new CompositeCallback(Arrays.asList(callbacks));
    }

    /**
     * 组合回调列表
     */
    public static CompositeCallback of(List<ChainCallback> callbacks) {
        return new CompositeCallback(callbacks);
    }

    @Override
    public void onLlmStart(LlmStartEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onLlmStart(event), "onLlmStart");
        }
    }

    @Override
    public void onLlmComplete(LlmCompleteEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onLlmComplete(event), "onLlmComplete");
        }
    }

    @Override
    public void onLlmError(LlmErrorEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onLlmError(event), "onLlmError");
        }
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onToolStart(event), "onToolStart");
        }
    }

    @Override
    public void onToolComplete(ToolCompleteEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onToolComplete(event), "onToolComplete");
        }
    }

    @Override
    public void onToolError(ToolErrorEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onToolError(event), "onToolError");
        }
    }

    @Override
    public void onRetrievalStart(RetrievalStartEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onRetrievalStart(event), "onRetrievalStart");
        }
    }

    @Override
    public void onRetrievalComplete(RetrievalCompleteEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onRetrievalComplete(event), "onRetrievalComplete");
        }
    }

    @Override
    public void onRetrievalError(RetrievalErrorEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onRetrievalError(event), "onRetrievalError");
        }
    }

    @Override
    public void onGraphEvent(GraphEvent event) {
        for (ChainCallback cb : callbacks) {
            safeInvoke(() -> cb.onGraphEvent(event), "onGraphEvent");
        }
    }

    private void safeInvoke(Runnable action, String methodName) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 回调异常不应中断主流程，静默吞掉
        }
    }
}
