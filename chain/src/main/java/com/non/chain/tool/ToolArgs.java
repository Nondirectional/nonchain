package com.non.chain.tool;

import java.util.Map;

/**
 * 工具参数的类型安全访问器
 */
public class ToolArgs {

    private final Map<String, Object> data;

    ToolArgs(Map<String, Object> data) {
        this.data = data;
    }

    public String getString(String name) {
        Object v = data.get(name);
        return v != null ? v.toString() : null;
    }

    public int getInt(String name) {
        Object v = data.get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        return v != null ? Integer.parseInt(v.toString()) : 0;
    }

    public long getLong(String name) {
        Object v = data.get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        return v != null ? Long.parseLong(v.toString()) : 0L;
    }

    public double getDouble(String name) {
        Object v = data.get(name);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return v != null ? Double.parseDouble(v.toString()) : 0.0;
    }

    public boolean getBoolean(String name) {
        Object v = data.get(name);
        if (v instanceof Boolean) return (Boolean) v;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    public boolean has(String name) {
        return data.containsKey(name) && data.get(name) != null;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        return (T) data.get(name);
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
