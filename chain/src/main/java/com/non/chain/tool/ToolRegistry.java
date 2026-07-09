package com.non.chain.tool;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.non.chain.agent.SubAgentDefinition;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心：支持注解扫描和 fluent API 两种注册方式，统一执行工具调用。
 *
 * <p>除普通工具外，还支持注册 {@linkplain SubAgentDefinition 委派型子代理}。
 * 子代理的暴露由 {@code Agent} 层按 {@code SubAgentExposureMode} 决定，
 * {@link ToolRegistry} 只负责存储与查询，不持有模式状态（见 spec：
 * tool-function-calling.md「ToolRegistry 是纯执行器，不触发 callback」）。</p>
 */
public class ToolRegistry {

    /** 通用 delegate tool 的固定名称（仅 DELEGATE 模式暴露时使用）。 */
    public static final String DELEGATE_TOOL_NAME = "delegate_to_subagent";
    /** D3 get_subagent_result 工具名 */
    public static final String GET_RESULT_TOOL_NAME = "get_subagent_result";
    /** D6 steer_subagent 工具名 */
    public static final String STEER_TOOL_NAME = "steer_subagent";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, ToolEntry> entries = new ConcurrentHashMap<>();
    // 子代理按注册顺序保留（DIRECT 工具列表与 DELEGATE 的 agentName enum 都依赖稳定顺序）
    private final Map<String, SubAgentDefinition> subAgents = Collections.synchronizedMap(new LinkedHashMap<>());

    public ToolRegistry() {
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

    // ---- 子代理注册 ----

    /**
     * 注册一个委派型子代理，返回声明式 Builder。{@code description} 必填、用于 LLM schema；
     * 子代理角色 {@code systemPrompt} 在 Builder 上设置。
     *
     * <pre>{@code
     * registry.registerSubAgent("research", "负责调研与归纳")
     *         .systemPrompt("你是调研代理。")
     *         .toolRegistry(researchTools)   // 可选
     *         .maxIterations(3)              // 可选
     *         .build();
     * }</pre>
     */
    public SubAgentRegistration registerSubAgent(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("子代理名称不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("子代理 description 不能为空: " + name);
        }
        if (subAgents.containsKey(name) || entries.containsKey(name)) {
            throw new IllegalStateException("已存在同名工具或子代理: " + name);
        }
        return new SubAgentRegistration(this, name, description);
    }

    /** 是否存在指定名称的子代理定义。 */
    public boolean hasSubAgent(String name) {
        return name != null && subAgents.containsKey(name);
    }

    /** 获取子代理定义；不存在时抛 {@link IllegalArgumentException}。 */
    public SubAgentDefinition getSubAgent(String name) {
        SubAgentDefinition def = name == null ? null : subAgents.get(name);
        if (def == null) {
            throw new IllegalArgumentException("未注册的子代理: " + name);
        }
        return def;
    }

    /** 全部已注册子代理名称（按注册顺序，用于 delegate tool 的 agentName enum）。 */
    public List<String> subAgentNames() {
        synchronized (subAgents) {
            return new ArrayList<>(subAgents.keySet());
        }
    }

    /**
     * 普通工具列表（不含子代理）。供 {@code Agent} 按暴露模式组合使用，
     * 也保证 {@link #getTools()} 的现有语义不被破坏。
     */
    public List<Tool> getRegularTools() {
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
     * DIRECT 模式：每个子代理暴露为一个独立 tool。schema 含必填 {@code task} +
     * 可选 {@code run_in_background}(D11 调用级前后台)。
     * tool 名等于子代理名。
     */
    public List<Tool> getDirectSubAgentTools() {
        List<Tool> tools = new ArrayList<>();
        synchronized (subAgents) {
            for (SubAgentDefinition def : subAgents.values()) {
                tools.add(Tool.builder(def.name())
                        .description(def.description())
                        .addProperty("task", "string", "需要委派给该子代理的任务", true)
                        .addProperty("run_in_background", "boolean", "是否后台执行(默认 false)", false)
                        .build());
            }
        }
        return tools;
    }

    /**
     * DELEGATE 模式：单个通用 {@code delegate_to_subagent} tool，{@code agentName}
     * 为已注册子代理名的枚举。含可选 {@code run_in_background}(D11)。无子代理时返回空 Optional。
     */
    public java.util.Optional<Tool> getDelegateSubAgentTool() {
        if (subAgents.isEmpty()) {
            return java.util.Optional.empty();
        }
        Tool tool = Tool.builder(DELEGATE_TOOL_NAME)
                .description("将任务委派给已注册的子代理")
                .addProperty("agentName", "string", "目标子代理名称", true, subAgentNames())
                .addProperty("task", "string", "委派任务", true)
                .addProperty("run_in_background", "boolean", "是否后台执行(默认 false)", false)
                .build();
        return java.util.Optional.of(tool);
    }

    /**
     * D3/D6:有已注册子代理时,额外暴露 get_subagent_result + steer_subagent 工具。
     * 无子代理时返回空 list(不暴露这两个工具)。
     */
    public List<Tool> getSubAgentControlTools() {
        if (subAgents.isEmpty()) {
            return new ArrayList<>();
        }
        List<Tool> tools = new ArrayList<>();
        tools.add(Tool.builder(GET_RESULT_TOOL_NAME)
                .description("查询/等待后台子代理的结果")
                .addProperty("subagent_id", "string", "后台子代理 ID", true)
                .addProperty("wait", "boolean", "是否阻塞等待完成(默认 false)", false)
                .build());
        tools.add(Tool.builder(STEER_TOOL_NAME)
                .description("向运行中的后台子代理注入转向消息")
                .addProperty("subagent_id", "string", "后台子代理 ID", true)
                .addProperty("message", "string", "转向指令", true)
                .build());
        return tools;
    }

    /**
     * 获取所有已注册的普通工具定义（用于传给 LLM）。不含子代理——子代理的暴露
     * 由 {@code Agent} 层按 {@code SubAgentExposureMode} 通过
     * {@link #getDirectSubAgentTools()} / {@link #getDelegateSubAgentTool()} 组合。
     *
     * <p>保持与引入子代理前完全一致的语义，现有用法不受影响。</p>
     */
    public List<Tool> getTools() {
        return getRegularTools();
    }

    /**
     * 执行工具调用（纯执行，不触发 callback——callback 由 Agent 编排层统一管理）。
     *
     * @param name      工具名称
     * @param arguments JSON 格式的参数字符串
     * @return 工具执行结果
     */
    public String execute(String name, String arguments) {
        // 子代理与 delegate tool 仅支持在 Agent 自动循环中执行（需要父上下文/cb 隔离）。
        // 手写 registry.execute(...) 直接调用时 fail-fast，避免产生语义不完整的降级行为。
        if (subAgents.containsKey(name)) {
            throw new IllegalStateException("SubAgent 仅支持在 Agent 自动循环中执行: " + name);
        }
        if (DELEGATE_TOOL_NAME.equals(name)) {
            throw new IllegalStateException("delegate_to_subagent 仅支持在 Agent 自动循环中执行");
        }
        if (GET_RESULT_TOOL_NAME.equals(name)) {
            throw new IllegalStateException("get_subagent_result 仅支持在 Agent 自动循环中执行");
        }
        if (STEER_TOOL_NAME.equals(name)) {
            throw new IllegalStateException("steer_subagent 仅支持在 Agent 自动循环中执行");
        }
        ToolEntry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return doExecute(entry, arguments);
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

    /**
     * 子代理声明式注册 Builder（由 {@link #registerSubAgent(String, String)} 创建）。
     *
     * <p>{@code systemPrompt} 必填；其余字段均可选，为空时使用框架默认值。</p>
     */
    public static class SubAgentRegistration {

        private final ToolRegistry registry;
        private final String name;
        private final String description;
        private String systemPrompt;
        private com.non.chain.tool.ToolRegistry toolRegistry;
        private com.non.chain.provider.LLM llmOverride;
        private Integer maxIterations;
        private com.non.chain.agent.ContextSelector contextSelector;
        private com.non.chain.memory.ChatMemoryStore chatMemoryStore;
        private final List<com.non.chain.agent.BeforeToolCall> beforeInterceptors = new ArrayList<>();
        private final List<com.non.chain.agent.AfterToolCall> afterInterceptors = new ArrayList<>();

        SubAgentRegistration(ToolRegistry registry, String name, String description) {
            this.registry = registry;
            this.name = name;
            this.description = description;
        }

        public SubAgentRegistration systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 子代理专属工具集；为空（默认）表示无工具子代理。 */
        public SubAgentRegistration toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /** 覆盖父 LLM；默认继承父 Agent 的 LLM。 */
        public SubAgentRegistration llm(com.non.chain.provider.LLM llm) {
            this.llmOverride = llm;
            return this;
        }

        /** 子代理最大迭代次数；默认回退框架默认值。 */
        public SubAgentRegistration maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** 注入上下文裁剪策略；默认使用框架默认裁剪。 */
        public SubAgentRegistration contextSelector(com.non.chain.agent.ContextSelector selector) {
            this.contextSelector = selector;
            return this;
        }

        /**
         * 对话记忆存储(D7 resume)。默认 null = 无状态(0.9.0 语义);
         * 配置后子代理变为有状态,支持 resume。
         */
        public SubAgentRegistration chatMemoryStore(com.non.chain.memory.ChatMemoryStore store) {
            this.chatMemoryStore = store;
            return this;
        }

        public SubAgentRegistration addBeforeToolCall(com.non.chain.agent.BeforeToolCall interceptor) {
            if (interceptor != null) {
                this.beforeInterceptors.add(interceptor);
            }
            return this;
        }

        public SubAgentRegistration addAfterToolCall(com.non.chain.agent.AfterToolCall interceptor) {
            if (interceptor != null) {
                this.afterInterceptors.add(interceptor);
            }
            return this;
        }

        /**
         * 构建并写入 {@link ToolRegistry}（同名注册时 {@code registerSubAgent} 已校验，
         * 此处不再重复检查）。
         *
         * <p>D10 fail-fast:子代理的 toolRegistry 若注册了 subAgent,抛异常(仅一层委派)。</p>
         */
        public ToolRegistry build() {
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalStateException("子代理 systemPrompt 不能为空: " + name);
            }
            // D10:子代理不支持嵌套委派
            if (toolRegistry != null && !toolRegistry.subAgentNames().isEmpty()) {
                throw new IllegalStateException(
                        "子代理不支持嵌套委派: " + name + " 的 toolRegistry 含 subAgent");
            }
            SubAgentDefinition def = new SubAgentDefinition(
                    name, description, systemPrompt, toolRegistry, llmOverride, maxIterations,
                    contextSelector, beforeInterceptors, afterInterceptors, chatMemoryStore);
            registry.subAgents.put(name, def);
            return registry;
        }
    }
}
