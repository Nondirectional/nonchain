package com.non.chain.flow;

import java.util.List;

public class GraphResult {

    private final State finalState;
    private final List<State> history;
    private final List<String> executedNodes;
    /**
     * 本次执行链路遥测的 runtime id（可空）。仅在启用 trace 录制时填充。
     */
    private final String runtimeId;

    public GraphResult(State finalState, List<State> history, List<String> executedNodes) {
        this(finalState, history, executedNodes, null);
    }

    public GraphResult(State finalState, List<State> history, List<String> executedNodes, String runtimeId) {
        this.finalState = finalState;
        this.history = history;
        this.executedNodes = executedNodes;
        this.runtimeId = runtimeId;
    }

    public State finalState() {
        return finalState;
    }

    public List<State> history() {
        return history;
    }

    public List<String> executedNodes() {
        return executedNodes;
    }

    /**
     * 本次执行的 trace runtime id（可空）。仅在启用 trace 录制时填充；
     * 未启用录制时为 null。配合 {@code TraceStore.getTrace(runtimeId)} 拉回完整 span 树。
     */
    public String runtimeId() {
        return runtimeId;
    }

    /** 返回带指定 runtime id 的副本（内部用）。 */
    public GraphResult withRuntimeId(String runtimeId) {
        return new GraphResult(finalState, history, executedNodes, runtimeId);
    }

    public void printTrace() {
        for (int i = 0; i < history.size(); i++) {
            if (i == 0) {
                System.out.println("=== 初始状态 ===");
            } else {
                System.out.println("=== [" + executedNodes.get(i - 1) + "] ===");
            }
            System.out.println(history.get(i));
            System.out.println();
        }
    }
}
