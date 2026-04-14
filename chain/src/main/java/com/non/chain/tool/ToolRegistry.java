package com.non.chain.tool;


import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.callback.ChainContext;
import com.non.chain.callback.ChainTrace;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.callback.event.ToolErrorEvent;
import com.non.chain.callback.event.ToolStartEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心：支持注解扫描和 fluent API 两种注册方式，统一执行工具调用
 */
public class ToolRegistry {

    private final Map<String, ToolEntry> entries = new ConcurrentHashMap<>();
    private final ChainCallback callback;

    public ToolRegistry() {
        this.callback = ChainCallbackUtil.noop();
    }

    public ToolRegistry(ChainCallback callback) {
        this.callback = callback != null ? callback : ChainCallbackUtil.noop();
    }

    public ToolRegistry(ChainContext chainContext) {
        this.callback = chainContext != null ? chainContext.callback() : ChainCallbackUtil.noop();
    }

    /**
     * Fluent API 入口：手动注册工具
     *
     * <pre>{@code
     * registry.register("get_weather", "查询天气")
     *     .param("city", "string", "城市名", true)
     *     .param("unit", "string", "温度单位", false)
     *     .handle(args -> args.getString("city") + "今天晴天");
     * }</pre>
     */
    public Registration register(String name, String description) {
        return new Registration(name, description);
    }

    /**
     * 注解扫描：扫描对象中所有带 @ToolDef 注解的方法并注册
     */
    public ToolRegistry scan(Object target) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            ToolDef toolDef = method.getAnnotation(ToolDef.class);
            if (toolDef != null) {
                method.setAccessible(true);
                entries.put(toolDef.name(), new ToolEntry(toolDef, method, target, null, Collections.emptyList()));
            }
        }
        return this;
    }

    /**
     * 获取所有已注册的工具定义（用于传给 LLM）
     */
    public List<Tool> getTools() {
        List<Tool> tools = new ArrayList<>();
        for (ToolEntry entry : entries.values()) {
            if (entry.handler != null) {
                // fluent 方式注册
                Tool.Builder builder = Tool.builder(entry.name).description(entry.description);
                for (ParamDef pd : entry.paramDefs) {
                    builder.addProperty(pd.name, pd.type, pd.description, pd.required);
                }
                tools.add(builder.build());
            } else {
                // 注解方式注册
                ToolDef def = entry.toolDef;
                Tool.Builder builder = Tool.builder(def.name())
                        .description(def.description());

                Parameter[] params = entry.method.getParameters();
                for (Parameter param : params) {
                    ToolParam tp = param.getAnnotation(ToolParam.class);
                    String paramName = tp != null ? tp.name() : param.getName();
                    String paramDesc = tp != null ? tp.description() : "";
                    boolean required = tp == null || tp.required();
                    builder.addProperty(paramName, javaTypeToJsonType(param.getType()), paramDesc, required);
                }
                tools.add(builder.build());
            }
        }
        return tools;
    }

    /**
     * 执行工具调用
     *
     * @param name      工具名称
     * @param arguments JSON 格式的参数字符串
     * @return 工具执行结果
     */
    public String execute(String name, String arguments) {
        ToolEntry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }

        String traceId = ChainTrace.get();
        ToolCall toolCall = new ToolCall(null, name, arguments);
        callback.onToolStart(new ToolStartEvent(traceId, toolCall));

        long start = System.currentTimeMillis();
        try {
            String result = doExecute(entry, arguments);
            long latencyMs = System.currentTimeMillis() - start;
            callback.onToolComplete(new ToolCompleteEvent(traceId, null, name, result, latencyMs));
            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            callback.onToolError(new ToolErrorEvent(traceId, null, name, arguments, e, latencyMs));
            throw e;
        }
    }

    private String doExecute(ToolEntry entry, String arguments) {
        Map<String, Object> parsedArgs = parseSimpleJson(arguments);

        if (entry.handler != null) {
            return entry.handler.execute(new ToolArgs(parsedArgs));
        }

        Parameter[] params = entry.method.getParameters();
        Object[] callArgs = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            ToolParam tp = params[i].getAnnotation(ToolParam.class);
            String paramName = tp != null ? tp.name() : params[i].getName();
            Object value = parsedArgs.get(paramName);
            callArgs[i] = convertType(value, params[i].getType());
        }

        try {
            Object result = entry.method.invoke(entry.target, callArgs);
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            throw new RuntimeException("工具执行失败: " + entry.name, e.getCause() != null ? e.getCause() : e);
        }
    }

    public boolean hasTool(String name) {
        return entries.containsKey(name);
    }

    private String javaTypeToJsonType(Class<?> type) {
        if (type == int.class || type == Integer.class ||
                type == long.class || type == Long.class ||
                type == double.class || type == Double.class ||
                type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        return "string";
    }

    private Object convertType(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType == String.class) return value.toString();
        if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(value.toString());
        if (targetType == long.class || targetType == Long.class) return Long.parseLong(value.toString());
        if (targetType == double.class || targetType == Double.class) return Double.parseDouble(value.toString());
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(value.toString());
        return value;
    }

    /**
     * 简易 JSON 解析，提取 key-value 为 Map
     */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> map = new HashMap<>();
        if (json == null || json.isBlank()) return map;

        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        int i = 0;
        while (i < json.length()) {
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ',' || json.charAt(i) == '\n')) i++;
            if (i >= json.length()) break;

            if (json.charAt(i) != '"') { i++; continue; }
            int keyStart = ++i;
            while (i < json.length() && json.charAt(i) != '"') i++;
            String key = json.substring(keyStart, i);
            i++;

            while (i < json.length() && json.charAt(i) != ':') i++;
            i++;
            while (i < json.length() && json.charAt(i) == ' ') i++;

            if (i < json.length() && json.charAt(i) == '"') {
                int valStart = ++i;
                while (i < json.length() && json.charAt(i) != '"') i++;
                map.put(key, json.substring(valStart, i));
                i++;
            } else {
                int valStart = i;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
                String val = json.substring(valStart, i).trim();
                if (val.equals("true")) map.put(key, true);
                else if (val.equals("false")) map.put(key, false);
                else if (val.equals("null")) map.put(key, null);
                else map.put(key, val);
            }
        }
        return map;
    }

    // ---- 内部结构 ----

    private static class ParamDef {
        final String name;
        final String type;
        final String description;
        final boolean required;

        ParamDef(String name, String type, String description, boolean required) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
        }
    }

    private static class ToolEntry {
        final String name;
        final String description;
        final ToolDef toolDef;
        final Method method;
        final Object target;
        final ToolHandler handler;
        final List<ParamDef> paramDefs;

        // 注解方式
        ToolEntry(ToolDef toolDef, Method method, Object target, ToolHandler handler, List<ParamDef> paramDefs) {
            this.name = toolDef.name();
            this.description = toolDef.description();
            this.toolDef = toolDef;
            this.method = method;
            this.target = target;
            this.handler = handler;
            this.paramDefs = paramDefs;
        }

        // Fluent 方式
        ToolEntry(String name, String description, ToolHandler handler, List<ParamDef> paramDefs) {
            this.name = name;
            this.description = description;
            this.toolDef = null;
            this.method = null;
            this.target = null;
            this.handler = handler;
            this.paramDefs = paramDefs;
        }
    }

    /**
     * Fluent 注册 Builder
     */
    public class Registration {

        private final String name;
        private final String description;
        private final List<ParamDef> params = new ArrayList<>();

        private Registration(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Registration param(String name, String type, String description, boolean required) {
            params.add(new ParamDef(name, type, description, required));
            return this;
        }

        public ToolRegistry handle(ToolHandler handler) {
            entries.put(name, new ToolEntry(name, description, handler, params));
            return ToolRegistry.this;
        }
    }
}
