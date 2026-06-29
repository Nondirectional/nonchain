package com.non.chain.flow;

import com.non.chain.Message;

import java.util.*;

public class State {

    private final Map<String, Object> data;
    private final List<Message> history;

    public State() {
        this.data = new HashMap<>();
        this.history = new ArrayList<>();
    }

    public State(State other) {
        this.data = new HashMap<>(other.data);
        this.history = new ArrayList<>(other.history);
    }

    public State put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key) {
        return Optional.ofNullable((T) data.get(key));
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) data.getOrDefault(key, defaultValue);
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    /**
     * state 数据视图（只读快照）。用于 trace 录制采集 state_in/state_out 载荷，
     * 以及任何需要读取当前 state 全量键值的场景。
     */
    public Map<String, Object> data() {
        return Collections.unmodifiableMap(new HashMap<>(data));
    }

    public State addMessage(Message message) {
        history.add(message);
        return this;
    }

    public List<Message> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public Optional<String> lastAssistantMessage() {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if ("assistant".equals(msg.role())) {
                return Optional.of(msg.content());
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("State{dataKeys=").append(data.keySet());
        sb.append(", history=[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(", ");
            Message msg = history.get(i);
            sb.append(msg.role()).append(": ").append(msg.content());
        }
        sb.append("]}");
        return sb.toString();
    }
}
