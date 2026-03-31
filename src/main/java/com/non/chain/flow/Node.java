package com.non.chain.flow;

import com.non.chain.flow.State;

import java.util.function.Function;

public class Node {

    private final String name;
    private final Function<State, State> processor;

    public Node(String name, Function<State, State> processor) {
        this.name = name;
        this.processor = processor;
    }

    public String name() {
        return name;
    }

    public State apply(State state) {
        return processor.apply(state);
    }
}
