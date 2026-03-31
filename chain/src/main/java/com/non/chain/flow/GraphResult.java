package com.non.chain.flow;

import java.util.List;

public class GraphResult {

    private final State finalState;
    private final List<State> history;
    private final List<String> executedNodes;

    public GraphResult(State finalState, List<State> history, List<String> executedNodes) {
        this.finalState = finalState;
        this.history = history;
        this.executedNodes = executedNodes;
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
