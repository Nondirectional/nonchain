package com.non.chain.skill;

/**
 * Skill 定义:过程性知识/指令文本,LLM 通过 tool-calling 点选后注入上下文。
 *
 * <p>skill 本身不含可执行工具——它是知识/指令层的东西,改变 Agent 的行为方式,
 * 而非执行有副作用的动作。三个字段均必填。</p>
 *
 * <p>不可变值对象。构造后字段不可变,遵循项目 quality-guidelines
 * (private final + 无前缀 accessor)。</p>
 */
public final class SkillDefinition {

    private final String name;
    private final String description;
    private final String content;

    private SkillDefinition(String name, String description, String content) {
        this.name = name;
        this.description = description;
        this.content = content;
    }

    /** 唯一标识,也是 LLM 点选的 function name。 */
    public String name() {
        return name;
    }

    /** 暴露给 LLM 的"什么时候用我",模型自选的路由依据。 */
    public String description() {
        return description;
    }

    /** skill 正文,按 Agent 配置注入消息的内容(MVP PERSISTENT 常驻)。 */
    public String content() {
        return content;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Skill 定义 Builder。三个字段均必填,空值 fail-fast。
     */
    public static final class Builder {

        private String name;
        private String description;
        private String content;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public SkillDefinition build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("skill name 不能为空");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("skill description 不能为空: " + name);
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("skill content 不能为空: " + name);
            }
            return new SkillDefinition(name, description, content);
        }
    }
}
