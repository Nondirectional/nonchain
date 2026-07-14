package com.non.chain.example;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.agent.SkillInjectionMode;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.ToolRegistry;

/**
 * Skill Demo — 过程性知识注入(顶层 Agent)
 *
 * <p><b>场景</b>：一个开发助手 Agent 配备两个 skill——「PRD 审查清单」和「Git 分支命名规范」。
 * 用户分别提出两个不同意图的问题，LLM 自主判断该用哪个 skill，点选后 skill 内容按配置注入，
 * 指导 Agent 按专业知识回答。</p>
 *
 * <p><b>展示要点</b>：</p>
 * <ul>
 *   <li>skill 在 LLM function 列表里以<b>无参数 function</b> 出现（description 带 {@code [Skill]} 前缀）</li>
 *   <li>LLM 根据 user 意图<b>自主点选</b>——不点、点一个、点多个都由 LLM 决定</li>
 *   <li>点选后产出两条消息：tool result（协议确认）+ <b>知识注入</b>（知识常驻）</li>
 *   <li>{@code SkillActivated} 事件携带 skill 名和注入内容长度</li>
 * </ul>
 *
 * <p>运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class SkillExample {

    public static void main(String[] args) {
        LLM llm = new VLLM("http://10.100.10.21:40002/v1","qwen3-14b").maxCompletionTokens(2048);

        SkillRegistry skills = new SkillRegistry();

        // skill 1：PRD 审查清单——告诉 Agent「怎么审查 PRD」的过程性知识
        skills.register("prd-review-checklist", "当用户请求审查 PRD、产品需求文档或评估需求质量时使用")
                .content("# PRD 审查清单\n"
                        + "逐项检查以下维度并给出评分（1-5）：\n"
                        + "1. **目标清晰度**：是否有可衡量的成功指标\n"
                        + "2. **用户价值**：是否说明了用户痛点和收益\n"
                        + "3. **边界定义**：MVP 范围是否明确，做了什么、不做什么\n"
                        + "4. **风险识别**：是否列出技术/依赖/时间风险\n"
                        + "5. **验收标准**：是否有可测试的完成定义\n"
                        + "输出格式：每个维度打分 + 一句理由，最后给总体建议。")
                .build();

        // skill 2：Git 分支命名规范——告诉 Agent「分支该怎么命名」的过程性知识
        skills.register("git-branch-naming", "当用户询问 Git 分支命名、提交分支策略或创建分支时使用")
                .content("# Git 分支命名规范\n"
                        + "本项目采用以下分支命名约定：\n"
                        + "- 功能分支：`feat/<简述>`，如 `feat/user-login`\n"
                        + "- 修复分支：`fix/<简述>`，如 `fix/null-pointer`\n"
                        + "- 重构分支：`refactor/<简述>`\n"
                        + "- 文档分支：`docs/<简述>`\n"
                        + "- 发布分支：`release/<版本号>`\n"
                        + "分支名全小写，单词用连字符分隔，不超过 40 字符。")
                .build();

        Agent agent = Agent.builder(llm, new ToolRegistry())
                .skillRegistry(skills)
                .skillInjectionMode(SkillInjectionMode.USER) // 此 vLLM 部署不支持多条 system 消息
                .systemPrompt("你是一个开发流程助手。根据用户问题，主动使用可用的 skill 来提供专业回答。")
                .maxIterations(5)
                .build();

        System.out.println("========== 第一个问题：审查 PRD（应触发 prd-review-checklist）==========\n");
        agent.run("帮我审查这个 PRD：做一个用户反馈收集功能，目标是提升产品体验。",
                SkillExample::printEvent);

        System.out.println("\n========== 第二个问题：分支命名（应触发 git-branch-naming）==========\n");
        agent.run("我要开发一个支付功能的新分支，该怎么命名？",
                SkillExample::printEvent);
    }

    private static void printEvent(AgentEvent event) {
        if (event instanceof AgentEvent.SkillActivated) {
            AgentEvent.SkillActivated sa = (AgentEvent.SkillActivated) event;
            System.out.println("  ▶ Skill 已激活: " + sa.skillName()
                    + "（注入 " + sa.contentLength() + " 字符的过程性知识）");
        } else if (event instanceof AgentEvent.TextDelta) {
            System.out.print(((AgentEvent.TextDelta) event).delta());
        } else if (event instanceof AgentEvent.Complete) {
            System.out.println("\n  ── 回答完成 ──");
        }
    }
}
