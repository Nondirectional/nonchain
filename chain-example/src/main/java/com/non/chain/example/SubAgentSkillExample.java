package com.non.chain.example;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.ToolRegistry;

/**
 * SubAgent + Skill Demo — 展示子代理预加载 skill(D13)
 *
 * <p>场景：主 Agent 有一个专职「代码审查」子代理，该子代理预加载了「审查清单」skill。
 * 当用户请求审查代码时，主 Agent 委派给审查子代理，子代理自主点选 skill 获取审查流程知识，
 * 按结构化流程完成审查。</p>
 *
 * <p>这填补了 SubAgent 重做裁剪清单 D13（skill 预加载 | nonchain 无技能系统）的坑。
 * 运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class SubAgentSkillExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus").maxCompletionTokens(1024);

        // 子代理专属的 skill:代码审查流程清单
        SkillRegistry reviewSkills = new SkillRegistry();
        reviewSkills.register("security-checklist", "审查代码安全性时使用")
                .content("# 安全审查清单\n"
                        + "检查以下安全风险：\n"
                        + "1. SQL 注入（是否使用参数化查询）\n"
                        + "2. XSS（是否对输出做转义）\n"
                        + "3. 权限校验（是否检查了访问权限）\n"
                        + "4. 敏感信息泄露（日志/异常是否暴露密钥）")
                .build();

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("security-reviewer", "负责代码安全审查")
                .systemPrompt("你是安全审查专家。根据需要使用可用的 skill 来指导审查。")
                .skillRegistry(reviewSkills)    // D13: 子代理预加载 skill
                .maxIterations(5)
                .build();

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是主助手。涉及代码安全审查时委派给 security-reviewer。")
                .maxIterations(5)
                .build();

        agent.run("帮我审查这段代码的安全性：\n"
                + "```java\n"
                + "public User login(String name, String pass) {\n"
                + "    String sql = \"SELECT * FROM users WHERE name='\" + name + \"' AND pass='\" + pass + \"'\";\n"
                + "    return jdbc.queryForObject(sql, User.class);\n"
                + "}\n"
                + "```",
                event -> {
                    if (event instanceof AgentEvent.SubAgentSpawned) {
                        AgentEvent.SubAgentSpawned sa = (AgentEvent.SubAgentSpawned) event;
                        System.out.println("▶ 委派子代理: " + sa.name() + " — " + sa.task());
                    } else if (event instanceof AgentEvent.SkillActivated) {
                        AgentEvent.SkillActivated sa = (AgentEvent.SkillActivated) event;
                        System.out.println("  └ 子代理激活 skill: " + sa.skillName()
                                + " (注入 " + sa.contentLength() + " 字符)");
                    } else if (event instanceof AgentEvent.Complete) {
                        System.out.println("\n=== 完成 ===");
                    }
                });
    }
}
