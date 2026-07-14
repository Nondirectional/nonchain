package com.non.chain.skill;

import com.non.chain.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 注册中心:存储 {@link SkillDefinition},提供查询与 schema 转换。
 *
 * <p>与 {@code ToolRegistry} 平行但独立——skill 不进 ToolRegistry,执行路径在
 * {@code Agent.dispatchExecute} 中单列。本类只负责存储与查询,不执行(skill 注入是
 * Agent 层的职责)。</p>
 *
 * <p>双入口(对称 ToolRegistry 的 register + scan):</p>
 * <ul>
 *   <li>{@code register(name, description)} → fluent {@link SkillRegistration}</li>
 *   <li>{@code register(SkillDefinition)} → 值对象直传</li>
 * </ul>
 */
public class SkillRegistry {

    /** skill 按注册顺序保留(LinkedHashMap),线程安全(synchronized)。 */
    private final Map<String, SkillDefinition> skills = Collections.synchronizedMap(new LinkedHashMap<>());

    public SkillRegistry() {
    }

    /**
     * Fluent 注册入口。
     *
     * <pre>{@code
     * registry.register("code-review", "当用户要求审查代码时使用")
     *         .content("# 代码审查流程\n1. ...")
     *         .build();
     * }</pre>
     */
    public SkillRegistration register(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill name 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("skill description 不能为空: " + name);
        }
        if (skills.containsKey(name)) {
            throw new IllegalStateException("已存在同名 skill: " + name);
        }
        return new SkillRegistration(this, name, description);
    }

    /** 值对象直传注册(构造好的 SkillDefinition 直接写入)。 */
    public SkillRegistry register(SkillDefinition skill) {
        if (skill == null) {
            throw new IllegalArgumentException("skill 不能为空");
        }
        if (skills.containsKey(skill.name())) {
            throw new IllegalStateException("已存在同名 skill: " + skill.name());
        }
        skills.put(skill.name(), skill);
        return this;
    }

    /** 是否存在指定名称的 skill。 */
    public boolean contains(String name) {
        return name != null && skills.containsKey(name);
    }

    /** 获取 skill 定义;不存在时抛 {@link IllegalArgumentException}。 */
    public SkillDefinition get(String name) {
        SkillDefinition def = name == null ? null : skills.get(name);
        if (def == null) {
            throw new IllegalArgumentException("未注册的 skill: " + name);
        }
        return def;
    }

    /** 全部已注册 skill 名称(按注册顺序)。 */
    public List<String> skillNames() {
        synchronized (skills) {
            return new ArrayList<>(skills.keySet());
        }
    }

    /**
     * 把每个 skill 转成 LLM 可见的无参数 {@link Tool}(只有 name + description,无 properties)。
     *
     * <p>description 加 {@code [Skill]} 前缀,让 LLM 在 function 列表里区分 skill 和真 tool
     * (语义隔离靠标记而非路径——见 design §3.2)。无 skill 时返回空 list。</p>
     */
    public List<Tool> getSkillTools() {
        List<Tool> tools = new ArrayList<>();
        synchronized (skills) {
            for (SkillDefinition def : skills.values()) {
                tools.add(Tool.builder(def.name())
                        .description("[Skill] " + def.description())
                        .build());
            }
        }
        return tools;
    }

    /** Fluent 注册 Builder(由 {@link #register(String, String)} 创建)。 */
    public static class SkillRegistration {

        private final SkillRegistry registry;
        private final String name;
        private final String description;
        private String content;

        SkillRegistration(SkillRegistry registry, String name, String description) {
            this.registry = registry;
            this.name = name;
            this.description = description;
        }

        public SkillRegistration content(String content) {
            this.content = content;
            return this;
        }

        /**
         * 构建并写入 {@link SkillRegistry}(重名校验由 {@code register} 入口已做)。
         */
        public SkillRegistry build() {
            SkillDefinition def = SkillDefinition.builder()
                    .name(name)
                    .description(description)
                    .content(content)
                    .build();
            registry.skills.put(name, def);
            return registry;
        }
    }
}
