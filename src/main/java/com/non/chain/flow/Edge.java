package com.non.chain.flow;

import com.non.chain.flow.State;

import java.util.function.Function;

public class Edge {

    private final String from;
    private final Function<State, String> router;

    private Edge(String from, Function<State, String> router) {
        this.from = from;
        this.router = router;
    }

    public String from() {
        return from;
    }

    public String route(State state) {
        return router.apply(state);
    }

    /**
     * 无条件边：from 始终流转到 to
     */
    public static Edge of(String from, String to) {
        return new Edge(from, state -> to);
    }

    /**
     * 条件边：from 根据 router 函数的结果决定流转到哪个节点
     * router 返回 Graph.END 表示结束
     */
    public static Edge conditional(String from, Function<State, String> router) {
        return new Edge(from, router);
    }
}
