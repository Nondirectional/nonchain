package com.non.chain.flow;

import com.non.chain.flow.Edge;
import com.non.chain.flow.GraphResult;
import com.non.chain.flow.Node;
import com.non.chain.flow.State;

import java.util.*;

public class Graph {

    public static final String END = "__END__";

    private final String name;
    private final String startNode;
    private final Map<String, Node> nodes;
    private final Map<String, Edge> edges;

    private Graph(String name, String startNode, Map<String, Node> nodes, Map<String, Edge> edges) {
        this.name = name;
        this.startNode = startNode;
        this.nodes = nodes;
        this.edges = edges;
    }

    public String name() {
        return name;
    }

    public GraphResult run(State initialState) {
        List<State> history = new ArrayList<>();
        List<String> executedNodes = new ArrayList<>();
        State current = initialState;

        history.add(new State(current));

        String nextNode = startNode;

        while (nextNode != null && !END.equals(nextNode)) {
            Node node = nodes.get(nextNode);
            if (node == null) {
                throw new IllegalStateException("未找到节点: " + nextNode);
            }

            current = node.apply(current);
            executedNodes.add(node.name());
            history.add(new State(current));

            Edge edge = edges.get(nextNode);
            if (edge != null) {
                nextNode = edge.route(current);
            } else {
                nextNode = null;
            }
        }

        return new GraphResult(current, Collections.unmodifiableList(history), Collections.unmodifiableList(executedNodes));
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String startNode;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Edge> edges = new HashMap<>();

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

        public Graph build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("Graph 必须包含至少一个 Node");
            }
            if (startNode == null) {
                throw new IllegalStateException("必须指定起始节点");
            }
            return new Graph(name, startNode, nodes, edges);
        }
    }
}
