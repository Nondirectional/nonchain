package com.non.chain.example;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.ToolRegistry;

/**
 * SubAgent + Skill Demo — 子代理预加载过程性知识(D13)
 *
 * <p><b>场景</b>：一个主 Agent 配备专职「安全审查」子代理。该子代理预加载了
 * 「OWASP 漏洞检查清单」skill。当用户请求代码安全审查时：</p>
 * <ol>
 *   <li>主 Agent 判断这是安全审查任务 → 委派给 security-reviewer 子代理</li>
 *   <li>子代理收到任务 → 自主点选 OWASP 清单 skill → skill 内容按 Agent 配置注入消息</li>
 *   <li>子代理按清单逐项检查 → 返回结构化审查报告</li>
 *   <li>主 Agent 拿到子代理结果 → 转述给用户</li>
 * </ol>
 *
 * <p><b>展示要点</b>：</p>
 * <ul>
 *   <li>skill 挂在<b>子代理</b>上（不是主 Agent），主 Agent 看不到这个 skill</li>
 *   <li>委派执行时子 agent 像顶层 Agent 一样按需点选 skill</li>
 *   <li>事件链：ToolStart → SubAgentProgress（Round / Skill / Tool / Text / Complete）→ ToolEnd</li>
 *   <li>填补 SubAgent 重做 D13 裁剪清单的坑（skill 预加载）</li>
 * </ul>
 *
 * <p>运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class SubAgentSkillExample {

    public static void main(String[] args) {
        LLM llm = new VLLM("http://10.100.10.21:40002/v1","qwen3-14b").maxCompletionTokens(2048);

        // 子代理专属 skill：OWASP 漏洞检查清单——只有 security-reviewer 能看到
        SkillRegistry securitySkills = new SkillRegistry();
        securitySkills.register("owasp-checklist", "审查代码安全漏洞时使用")
                .content("# OWASP Top 10 检查清单\n"
                        + "逐项检查代码是否存在以下漏洞：\n"
                        + "1. **注入攻击**（SQL/Command）：是否使用参数化查询/预编译\n"
                        + "2. **失效的认证**：密码是否明文存储、会话是否可预测\n"
                        + "3. **敏感数据泄露**：是否加密传输/存储、日志是否暴露凭据\n"
                        + "4. **XXE / SSRF**：XML 解析是否禁用外部实体、URL 是否校验\n"
                        + "5. **失效的访问控制**：是否校验权限、是否有越权风险\n"
                        + "输出格式：每个漏洞给出 [有风险/无风险/无法判断] + 证据 + 修复建议。")
                .build();

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("security-reviewer", "负责代码安全审查，能识别 OWASP 常见漏洞")
                .systemPrompt("你是安全审查专家。\n\n"
                        + "【重要】收到代码后，你的第一个动作必须是调用 owasp-checklist 工具获取检查清单。"
                        + "在调用该工具之前，不要输出任何审查结论。拿到清单后，严格按照清单逐项检查，"
                        + "每一项给出 [有风险/无风险/无法判断] + 证据 + 修复建议。")
                .skillRegistry(securitySkills)    // D13: 子代理预加载 skill
                .maxIterations(5)
                .build();

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是主助手。涉及代码安全审查时，委派给 security-reviewer 子代理一次即可，"
                        + "拿到结果后直接转述给用户，不要重复委派。其他问题自己回答。")
                .maxIterations(5)
                .build();

        // 故意提供一段有 SQL 注入漏洞的代码
        String vulnerableCode =
                "帮我审查这段 Java 代码的安全性：\n"
                + "```java\n"
                + "public User login(String username, String password) {\n"
                + "    String sql = \"SELECT * FROM users WHERE name='\"\n"
                + "            + username + \"' AND pass='\" + password + \"'\";\n"
                + "    Statement stmt = conn.createStatement();\n"
                + "    ResultSet rs = stmt.executeQuery(sql);\n"
                + "    if (rs.next()) {\n"
                + "        return new User(rs.getString(\"name\"));\n"
                + "    }\n"
                + "    return null;\n"
                + "}\n"
                + "```";

        System.out.println("========== 用户请求安全审查 ==========\n");
        agent.run(vulnerableCode, SubAgentSkillExample::printEvent);
    }

    private static void printEvent(AgentEvent event) {
        if (event instanceof AgentEvent.ToolStart) {
            AgentEvent.ToolStart toolStart = (AgentEvent.ToolStart) event;
            System.out.println("  ▶ 主 Agent 调用: " + toolStart.toolName());
        } else if (event instanceof AgentEvent.SubAgentProgress) {
            printSubAgentProgress((AgentEvent.SubAgentProgress) event);
        } else if (event instanceof AgentEvent.ToolEnd) {
            AgentEvent.ToolEnd toolEnd = (AgentEvent.ToolEnd) event;
            System.out.println("\n  ◀ 主 Agent 工具完成: " + toolEnd.toolName());
        } else if (event instanceof AgentEvent.SubAgentSpawned) {
            AgentEvent.SubAgentSpawned sa = (AgentEvent.SubAgentSpawned) event;
            System.out.println("  ▶ 主 Agent 委派子代理: " + sa.name());
            System.out.println("    任务: " + sa.task());
        } else if (event instanceof AgentEvent.SubAgentCompleted) {
            AgentEvent.SubAgentCompleted sa = (AgentEvent.SubAgentCompleted) event;
            System.out.println("  ◀ 子代理完成: " + sa.name() + " [" + sa.status() + "]");
        } else if (event instanceof AgentEvent.TextDelta) {
            System.out.print(((AgentEvent.TextDelta) event).delta());
        } else if (event instanceof AgentEvent.Complete) {
            System.out.println("\n  ── 主 Agent 回答完成 ──");
        }
    }

    private static void printSubAgentProgress(AgentEvent.SubAgentProgress progress) {
        String prefix = "    [SubAgent " + progress.name() + "/" + progress.subAgentId() + "] ";
        AgentEvent event = progress.event();
        if (event instanceof AgentEvent.RoundStart) {
            System.out.println(prefix + "开始第 " + ((AgentEvent.RoundStart) event).round() + " 轮");
        } else if (event instanceof AgentEvent.ToolStart) {
            AgentEvent.ToolStart toolStart = (AgentEvent.ToolStart) event;
            System.out.println(prefix + "调用工具: " + toolStart.toolName());
        } else if (event instanceof AgentEvent.ToolEnd) {
            AgentEvent.ToolEnd toolEnd = (AgentEvent.ToolEnd) event;
            System.out.println(prefix + "工具完成: " + toolEnd.toolName());
        } else if (event instanceof AgentEvent.SkillActivated) {
            AgentEvent.SkillActivated skill = (AgentEvent.SkillActivated) event;
            System.out.println(prefix + "Skill 已激活: " + skill.skillName()
                    + "（注入 " + skill.contentLength() + " 字符）");
        } else if (event instanceof AgentEvent.TextDelta) {
            System.out.print(((AgentEvent.TextDelta) event).delta());
        } else if (event instanceof AgentEvent.AgentError) {
            System.out.println(prefix + "执行错误: "
                    + ((AgentEvent.AgentError) event).error().getMessage());
        } else if (event instanceof AgentEvent.Complete) {
            System.out.println("\n" + prefix + "子代理完成");
        }
    }
}
