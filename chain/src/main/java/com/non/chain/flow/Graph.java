package com.non.chain.flow;

import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.callback.ChainContext;

import java.util.*;
import java.util.function.Consumer;

public class Graph {

    public static final String END = "__END__";

    private final String name;
    private final String startNode;
    private final Map<String, Node> nodes;
    private final Map<String, Edge> edges;
    private final Consumer<GraphEvent> onEvent;
    private final ChainCallback callback;

    private Graph(String name, String startNode, Map<String, Node> nodes, Map<String, Edge> edges,
                  Consumer<GraphEvent> onEvent, ChainCallback callback) {
        this.name = name;
        this.startNode = startNode;
        this.nodes = nodes;
        this.edges = edges;
        this.onEvent = onEvent;
        this.callback = callback;
    }

    public String name() {
        return name;
    }

    public GraphResult run(State initialState) {
        List<State> history = new ArrayList<>();
        List<String> executedNodes = new ArrayList<>();
        State current = initialState;

        history.add(new State(current));

        emit(GraphEvent.of(GraphEvent.Type.GRAPH_START, null, new State(current)));

        String nextNode = startNode;

        while (nextNode != null && !END.equals(nextNode)) {
            Node node = nodes.get(nextNode);
            if (node == null) {
                String errorMsg = "未找到节点: " + nextNode;
                emit(GraphEvent.nodeError(nextNode, new State(current), errorMsg));
                emit(GraphEvent.graphError(new State(current), errorMsg));
                List<String> finalExecutedNodes = Collections.unmodifiableList(executedNodes);
                emit(GraphEvent.graphEnd(current, finalExecutedNodes));
                throw new IllegalStateException(errorMsg);
            }

            String currentNodeName = nextNode;

            try {
                emit(GraphEvent.of(GraphEvent.Type.NODE_START, currentNodeName, new State(current)));

                current = node.apply(current);
                executedNodes.add(node.name());
                history.add(new State(current));

                emit(GraphEvent.of(GraphEvent.Type.NODE_END, node.name(), new State(current)));

                Edge edge = edges.get(currentNodeName);
                if (edge != null) {
                    nextNode = edge.route(current);
                } else {
                    nextNode = null;
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                emit(GraphEvent.nodeError(currentNodeName, new State(current), errorMsg));
                emit(GraphEvent.graphError(new State(current), errorMsg));
                List<String> finalExecutedNodes = Collections.unmodifiableList(executedNodes);
                emit(GraphEvent.graphEnd(current, finalExecutedNodes));
                throw e;
            }
        }

        List<String> finalExecutedNodes = Collections.unmodifiableList(executedNodes);
        emit(GraphEvent.graphEnd(current, finalExecutedNodes));

        return new GraphResult(current, Collections.unmodifiableList(history), finalExecutedNodes);
    }

    private void emit(GraphEvent event) {
        if (onEvent != null) {
            onEvent.accept(event);
        }
        if (callback != null) {
            try {
                callback.onGraphEvent(event);
            } catch (Exception ignored) {
                // 回调异常不应中断主流程
            }
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String startNode;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Edge> edges = new HashMap<>();
        private Consumer<GraphEvent> onEvent;
        private ChainCallback callback;

        private Builder(String name) {
            this.name = name;
        }

        public Builder addNode(Node node) {
            nodes.put(node.name(), node);
            return this;
        }

        public Builder start(String nodeName) {
            this.startNode = nodeName;
            return this;
        }

        public Builder addEdge(Edge edge) {
            edges.put(edge.from(), edge);
            return this;
        }

        public Builder onEvent(Consumer<GraphEvent> onEvent) {
            this.onEvent = onEvent;
            return this;
        }

        /**
         * 设置 ChainCallback，Graph 事件将同时通过此回调发出
         */
        public Builder callback(ChainCallback callback) {
            this.callback = callback;
            return this;
        }

        /**
         * 通过 ChainContext 注入回调
         */
        public Builder chainContext(ChainContext chainContext) {
            if (chainContext != null && this.callback == null) {
                this.callback = chainContext.callback();
            }
            return this;
        }

        public Graph build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("Graph 必须包含至少一个 Node");
            }
            if (startNode == null) {
                throw new IllegalStateException("必须指定起始节点");
            }
            return new Graph(name, startNode, nodes, edges, onEvent,
                    callback != null ? callback : ChainCallbackUtil.noop());
        }
    }
}
