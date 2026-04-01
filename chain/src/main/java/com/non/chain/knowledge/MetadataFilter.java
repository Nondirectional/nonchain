package com.non.chain.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MetadataFilter {

    public enum Type {
        CONDITION,
        AND,
        OR,
        NOT
    }

    public enum Operator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        IN,
        EXISTS
    }

    private final Type type;
    private final String key;
    private final Operator operator;
    private final Object value;
    private final List<MetadataFilter> children;

    private MetadataFilter(Type type, String key, Operator operator, Object value, List<MetadataFilter> children) {
        this.type = type;
        this.key = key;
        this.operator = operator;
        this.value = value;
        this.children = children;
    }

    public Type type() {
        return type;
    }

    public String key() {
        return key;
    }

    public Operator operator() {
        return operator;
    }

    public Object value() {
        return value;
    }

    public List<MetadataFilter> children() {
        return children;
    }

    public static MetadataFilter condition(String key, Operator operator, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("过滤 key 不能为空");
        }
        if (operator == null) {
            throw new IllegalArgumentException("过滤 operator 不能为空");
        }
        if (operator != Operator.EXISTS && value == null) {
            throw new IllegalArgumentException("过滤 value 不能为空");
        }
        return new MetadataFilter(Type.CONDITION, key, operator, value, List.of());
    }

    public static MetadataFilter and(List<MetadataFilter> filters) {
        return logical(Type.AND, filters);
    }

    public static MetadataFilter or(List<MetadataFilter> filters) {
        return logical(Type.OR, filters);
    }

    public static MetadataFilter not(MetadataFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("NOT 条件不能为空");
        }
        return new MetadataFilter(Type.NOT, null, null, null, List.of(filter));
    }

    private static MetadataFilter logical(Type type, List<MetadataFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            throw new IllegalArgumentException("组合过滤条件不能为空");
        }
        List<MetadataFilter> copied = new ArrayList<>();
        for (MetadataFilter filter : filters) {
            copied.add(Objects.requireNonNull(filter, "组合过滤条件中不能包含 null"));
        }
        return new MetadataFilter(type, null, null, null, Collections.unmodifiableList(copied));
    }
}
