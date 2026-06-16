package com.non.chain.tool;


import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.callback.ChainContext;
import com.non.chain.callback.ChainTrace;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.callback.event.ToolErrorEvent;
import com.non.chain.callback.event.ToolStartEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心：支持注解扫描和 fluent API 两种注册方式，统一执行工具调用
 */
public class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                Type[] genericTypes = entry.method.getGenericParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    Parameter param = params[i];
                    ToolParam tp = param.getAnnotation(ToolParam.class);
                    String paramName = tp != null ? tp.name() : param.getName();
                    String paramDesc = tp != null ? tp.description() : "";
                    boolean required = tp == null || tp.required();
                    String jsonType = javaTypeToJsonType(param.getType());
                    String itemsType = inferItemsType(genericTypes[i], param.getType());
                    if (itemsType != null) {
                        builder.addProperty(paramName, jsonType, paramDesc, required, itemsType);
                    } else {
                        builder.addProperty(paramName, jsonType, paramDesc, required);
                    }
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
        Map<String, Object> parsedArgs = parseArguments(arguments);

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
        if (type.isArray() || List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type)) {
            return "array";
        }
        if (Map.class.isAssignableFrom(type)) {
            return "object";
        }
        return "string";
    }

    /**
     * 推断数组/集合参数的元素 JSON 类型（用于生成 schema 的 items）。
     * 返回 null 表示该参数非数组/集合（无需 items）。
     */
    private String inferItemsType(Type genericType, Class<?> rawType) {
        // Java 数组：元素类型 = getComponentType()
        if (rawType.isArray()) {
            return javaTypeToJsonType(rawType.getComponentType());
        }
        // 仅 List/Set 生成 items；Map 作为 object 不需要 items
        if (List.class.isAssignableFrom(rawType) || Set.class.isAssignableFrom(rawType)) {
            if (genericType instanceof ParameterizedType) {
                Type[] args = ((ParameterizedType) genericType).getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class) {
                    return javaTypeToJsonType((Class<?>) args[0]);
                }
            }
            // raw List/Set 无泛型 → 兜底 string
            return "string";
        }
        return null;
    }

    private Object convertType(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType == String.class) return value.toString();
        if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(value.toString());
        if (targetType == long.class || targetType == Long.class) return Long.parseLong(value.toString());
        if (targetType == double.class || targetType == Double.class) return Double.parseDouble(value.toString());
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(value.toString());
        if (targetType.isArray()) {
            List<?> list = coerceToList(value);
            Object array = Array.newInstance(targetType.getComponentType(), list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, convertType(list.get(i), targetType.getComponentType()));
            }
            return array;
        }
        if (List.class.isAssignableFrom(targetType)) return coerceToList(value);
        if (Set.class.isAssignableFrom(targetType)) return new LinkedHashSet<>(coerceToList(value));
        if (Map.class.isAssignableFrom(targetType) && value instanceof Map) return value;
        return value;
    }

    /**
     * 将值强制转为 List：已是 List 直接返回；Java 数组逐元素拷贝；否则 fail-fast。
     */
    private List<?> coerceToList(Object value) {
        if (value instanceof List) {
            return (List<?>) value;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                list.add(Array.get(value, i));
            }
            return list;
        }
        throw new IllegalArgumentException("无法转换为 List: " + value.getClass());
    }

    /**
     * 解析工具参数 JSON（支持标量、字符串、布尔、null、数组、对象及嵌套），
     * 返回 key-value 的 Map。解析失败 fail-fast 抛出 IllegalArgumentException。
     */
    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Object parsed = MAPPER.readValue(json.trim(), Object.class);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            }
            throw new IllegalArgumentException("工具参数必须是 JSON 对象: " + json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具参数 JSON 解析失败: " + json, e);
        }
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
