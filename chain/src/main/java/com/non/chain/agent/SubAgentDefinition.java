package com.non.chain.agent;

import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;

import java.util.Collections;
import java.util.List;

/**
 * 子代理定义：不可变值对象，描述一个委派型子代理的全部配置。
 *
 * <p>由 {@link SubAgentRegistration#build()} 产出，存储在 {@link ToolRegistry} 中，
 * 在父 Agent 执行委派时被读取以动态构造子代理 {@link Agent} 实例。</p>
 *
 * <p>注册时不预构建 {@code Agent}，因为子代理默认继承父 LLM、依赖运行时上下文、
 * 且父/子 trace 与 callback 需要隔离——这些都只能运行时确定。</p>
 */
public final class SubAgentDefinition {

    private final String name;
    private final String description;
    private final String systemPrompt;
    private final ToolRegistry toolRegistry;          // nullable：为空表示无工具子代理
    private final LLM llmOverride;                    // nullable：为空时继承父 LLM
    private final Integer maxIterations;              // nullable：为空时回退框架默认值
    private final ContextSelector contextSelector;    // nullable：为空时用框架默认裁剪
    private final List<BeforeToolCall> beforeInterceptors;
    private final List<AfterToolCall> afterInterceptors;

    public SubAgentDefinition(String name, String description, String systemPrompt,
                              ToolRegistry toolRegistry, LLM llmOverride, Integer maxIterations,
                              ContextSelector contextSelector,
                              List<BeforeToolCall> beforeInterceptors,
                              List<AfterToolCall> afterInterceptors) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.llmOverride = llmOverride;
        this.maxIterations = maxIterations;
        this.contextSelector = contextSelector;
        this.beforeInterceptors = beforeInterceptors == null
                ? Collections.emptyList() : Collections.unmodifiableList(beforeInterceptors);
        this.afterInterceptors = afterInterceptors == null
                ? Collections.emptyList() : Collections.unmodifiableList(afterInterceptors);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public LLM llmOverride() {
        return llmOverride;
    }

    public Integer maxIterations() {
        return maxIterations;
    }

    public ContextSelector contextSelector() {
        return contextSelector;
    }

    public List<BeforeToolCall> beforeInterceptors() {
        return beforeInterceptors;
    }

    public List<AfterToolCall> afterInterceptors() {
        return afterInterceptors;
    }
}
