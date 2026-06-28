package com.non.chain.agent;

/**
 * SubAgent 暴露模式：决定父 Agent 在 LLM 工具列表中如何看到已注册的子代理。
 *
 * <p>构建期固定，不支持同一个 {@link Agent} 在不同 {@code run(...)} 间切换。</p>
 *
 * <ul>
 *   <li>{@link #DIRECT} — 默认：每个子代理暴露为一个独立 tool（schema 仅含 {@code task} 参数）。</li>
 *   <li>{@link #DELEGATE} — 显式开启：只暴露一个通用 {@code delegate_to_subagent(agentName, task)} tool。</li>
 * </ul>
 */
public enum SubAgentExposureMode {
    DIRECT,
    DELEGATE
}
