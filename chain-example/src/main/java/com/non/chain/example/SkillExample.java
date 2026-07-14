package com.non.chain.example;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.ToolRegistry;

/**
 * Skill Demo — 展示过程性知识注入机制
 *
 * <p>场景：一个 Agent 配备「代码审查流程」skill。当用户请求审查代码时，LLM 自主点选该 skill，
 * skill 的过程性知识作为 system 消息注入对话，指导 Agent 按结构化流程审查。</p>
 *
 * <p>skill 本身不含可执行工具——它是知识/指令层的东西，改变 Agent 的行为方式。
 * LLM 通过 tool-calling 点选（skill 在 function 列表里以无参数 function 出现，description
 * 带 {@code [Skill]} 前缀），点中后内容作为 system 消息注入（PERSISTENT 常驻）。</p>
 *
 * <p>运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class SkillExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus").maxCompletionTokens(1024);

        // 注册 skill：过程性知识文本
        SkillRegistry skillRegistry = new SkillRegistry();
        skillRegistry.register("code-review", "当用户请求审查代码、review 代码或评估代码质量时使用")
                .content("# 代码审查流程\n"
                        + "按以下步骤审查代码：\n"
                        + "1. **整体结构**：模块划分是否清晰，职责是否单一\n"
                        + "2. **命名规范**：变量/方法/类命名是否达意、一致\n"
                        + "3. **错误处理**：异常是否被妥善处理，边界条件是否覆盖\n"
                        + "4. **安全性**：是否有注入、越权等安全隐患\n"
                        + "5. **性能**：是否有明显的性能问题（N+1 查询、不必要的循环等）\n"
                        + "输出格式：按严重程度分级（🔴 严重 / 🟡 建议 / 🟢 良好）")
                .build();

        skillRegistry.register("commit-helper", "当用户请求生成 commit message 或提交信息时使用")
                .content("# Commit Message 规范\n"
                        + "使用约定式提交格式：\n"
                        + "- `feat: 新功能`\n"
                        + "- `fix: 修复 bug`\n"
                        + "- `refactor: 重构`\n"
                        + "- `docs: 文档`\n"
                        + "首行不超过 50 字符，空行后写详细说明（每行不超过 72 字符）。")
                .build();

        Agent agent = Agent.builder(llm, new ToolRegistry())
                .skillRegistry(skillRegistry)
                .systemPrompt("你是一个开发助手。根据需要主动使用可用的 skill 来指导你的回答。")
                .maxIterations(5)
                .build();

        // 带 event 消费者运行，可观察 skill 激活事件
        agent.run("帮我审查这段代码：\n```java\npublic User getUser(String id) {\n    return users.get(id);\n}\n```",
                event -> {
                    if (event instanceof AgentEvent.SkillActivated) {
                        AgentEvent.SkillActivated sa = (AgentEvent.SkillActivated) event;
                        System.out.println("▶ Skill 已激活: " + sa.skillName()
                                + " (注入 " + sa.contentLength() + " 字符的指导)");
                    } else if (event instanceof AgentEvent.Complete) {
                        System.out.println("\n=== 完成 ===");
                    }
                });
    }
}
