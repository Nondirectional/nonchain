package com.non.chain.flow;

import java.util.List;

/**
 * Graph 执行过程中触发的事件。
 */
public class GraphEvent {

    public enum Type {
        GRAPH_START,
        NODE_START,
        NODE_END,
        NODE_ERROR,
        GRAPH_ERROR,
        GRAPH_END
    }

    private final Type type;
    private final String node;
    private final State state;
    private final List<String> executedNodes;
    private final String error;

    public GraphEvent(Type type, String node, State state, List<String> executedNodes, String error) {
        this.type = type;
        this.node = node;
        this.state = state;
        this.executedNodes = executedNodes;
        this.error = error;
    }

    public Type type() {
        return type;
    }

    public String node() {
        return node;
    }

    public State state() {
        return state;
    }

    public List<String> executedNodes() {
        return executedNodes;
    }

    public String error() {
        return error;
    }

    public static GraphEvent of(Type type, String node, State state) {
        return new GraphEvent(type, node, state, null, null);
    }

    public static GraphEvent graphEnd(State state, List<String> executedNodes) {
        return new GraphEvent(Type.GRAPH_END, null, state, executedNodes, null);
    }

    public static GraphEvent nodeError(String node, State state, String error) {
        return new GraphEvent(Type.NODE_ERROR, node, state, null, error);
    }

    public static GraphEvent graphError(State state, String error) {
        return new GraphEvent(Type.GRAPH_ERROR, null, state, null, error);
    }
}
