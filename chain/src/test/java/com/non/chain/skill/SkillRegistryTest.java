package com.non.chain.skill;

import com.non.chain.tool.Tool;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SkillRegistry 与 SkillDefinition 注册/查询/schema 转换测试。
 *
 * <p>覆盖 design §3:双入口注册、重名 fail-fast、getSkillTools 产出无参数 Tool(带 [Skill] 前缀)、
 * 查询方法。Agent 层 skill 注入的端到端测试见 {@code agent.AgentSkillTest}。</p>
 */
public class SkillRegistryTest {

    @Test
    public void fluentRegister_build() {
        SkillRegistry registry = new SkillRegistry();
        registry.register("code-review", "审查代码时使用")
                .content("# 审查流程\n1. 看结构")
                .build();

        assertTrue(registry.contains("code-review"));
        SkillDefinition def = registry.get("code-review");
        assertEquals("code-review", def.name());
        assertEquals("审查代码时使用", def.description());
        assertEquals("# 审查流程\n1. 看结构", def.content());
    }

    @Test
    public void valueObjectRegister() {
        SkillRegistry registry = new SkillRegistry();
        SkillDefinition def = SkillDefinition.builder()
                .name("commit")
                .description("生成 commit message")
                .content("按约定式提交格式")
                .build();
        registry.register(def);

        assertTrue(registry.contains("commit"));
        assertSame(def, registry.get("commit"));
    }

    @Test
    public void fluentRegister_duplicateName_throws() {
        SkillRegistry registry = new SkillRegistry();
        registry.register("dup", "d1").content("c1").build();
        try {
            registry.register("dup", "d2").content("c2").build();
            fail("应抛异常:重名 skill");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("dup"));
        }
    }

    @Test
    public void valueObjectRegister_duplicateName_throws() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(SkillDefinition.builder().name("x").description("d").content("c").build());
        try {
            registry.register(SkillDefinition.builder().name("x").description("d").content("c").build());
            fail("应抛异常:重名 skill");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("x"));
        }
    }

    @Test
    public void register_blankName_throws() {
        SkillRegistry registry = new SkillRegistry();
        try {
            registry.register("", "d");
            fail("应抛异常:空 name");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void register_blankDescription_throws() {
        SkillRegistry registry = new SkillRegistry();
        try {
            registry.register("ok", "  ");
            fail("应抛异常:空 description");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void skillBuild_blankContent_throws() {
        SkillRegistry registry = new SkillRegistry();
        try {
            registry.register("ok", "d").build();  // content 未设置
            fail("应抛异常:空 content");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void get_unknownSkill_throws() {
        SkillRegistry registry = new SkillRegistry();
        try {
            registry.get("nope");
            fail("应抛异常:未注册 skill");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void contains_null_returnsFalse() {
        assertFalse(new SkillRegistry().contains(null));
    }

    @Test
    public void getSkillTools_emptyRegistry_returnsEmpty() {
        assertTrue(new SkillRegistry().getSkillTools().isEmpty());
    }

    @Test
    public void getSkillTools_producesParamlessFunctionsWithPrefix() {
        SkillRegistry registry = new SkillRegistry();
        registry.register("code-review", "审查代码时使用")
                .content("# 流程\n1. ...")
                .build();
        registry.register("commit", "生成 commit message")
                .content("约定式提交")
                .build();

        List<Tool> tools = registry.getSkillTools();
        assertEquals(2, tools.size());

        // 按注册顺序
        Tool first = tools.get(0);
        assertEquals("code-review", first.name());
        assertEquals("[Skill] 审查代码时使用", first.description());

        Tool second = tools.get(1);
        assertEquals("commit", second.name());
        assertEquals("[Skill] 生成 commit message", second.description());

        // 确认无参数(schema 里 required 为空 —— 通过 toFunctionDefinition 不报错验证)
        // toFunctionDefinition 产出 properties={} required=[] 的合法 schema
        first.toFunctionDefinition();
        second.toFunctionDefinition();
    }

    @Test
    public void skillNames_preservesRegistrationOrder() {
        SkillRegistry registry = new SkillRegistry();
        registry.register("zeta", "d").content("c").build();
        registry.register("alpha", "d").content("c").build();
        registry.register("mid", "d").content("c").build();

        List<String> names = registry.skillNames();
        assertEquals(3, names.size());
        assertEquals("zeta", names.get(0));
        assertEquals("alpha", names.get(1));
        assertEquals("mid", names.get(2));
    }

    @Test
    public void skillDefinitionBuilder_blankFields_throws() {
        try {
            SkillDefinition.builder().name("").description("d").content("c").build();
            fail("应抛异常:空 name");
        } catch (IllegalArgumentException expected) {
        }
        try {
            SkillDefinition.builder().name("ok").description("").content("c").build();
            fail("应抛异常:空 description");
        } catch (IllegalArgumentException expected) {
        }
        try {
            SkillDefinition.builder().name("ok").description("d").content("  ").build();
            fail("应抛异常:空 content");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void assertSame(Object expected, Object actual) {
        // JUnit 4 的 assertSame 在 org.junit.Assert,这里简化用 ==
        assertTrue("expected same instance", expected == actual);
    }
}
