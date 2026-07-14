package com.non.chain.agent;

/**
 * Skill 被点选后注入过程性知识的消息角色。
 *
 * <p>{@link #SYSTEM} 为默认值，保持既有 system 指令语义。某些模型的 Chat Template
 * 不支持中途追加多条 system 消息时，可显式使用 {@link #USER}。</p>
 */
public enum SkillInjectionMode {

    /** 以 system 消息注入 Skill 内容（默认）。 */
    SYSTEM,

    /** 以带 {@code [Skill: name]} 边界的 user 消息注入 Skill 内容。 */
    USER
}
