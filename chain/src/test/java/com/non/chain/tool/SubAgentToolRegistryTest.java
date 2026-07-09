package com.non.chain.tool;

import com.non.chain.agent.SubAgentDefinition;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 子代理注册层契约：registerSubAgent、查询方法、schema 生成（DIRECT/DELEGATE）、
 * execute 子代理 fail-fast。Agent 端到端委派见 {@code agent.SubAgentTest}。
 */
public class SubAgentToolRegistryTest {

    // ---- 注册与查询 ----

    @Test
    public void registerSubAgentBasicQuery() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "负责调研与归纳")
                .systemPrompt("你是调研代理。")
                .build();

        assertTrue(registry.hasSubAgent("research"));
        assertFalse(registry.hasSubAgent("absent"));

        SubAgentDefinition def = registry.getSubAgent("research");
        assertEquals("research", def.name());
        assertEquals("负责调研与归纳", def.description());
        assertEquals("你是调研代理。", def.systemPrompt());
        assertEquals(List.of("research"), registry.subAgentNames());
    }

    @Test
    public void getSubAgentAbsentThrows() {
        ToolRegistry registry = new ToolRegistry();
        try {
            registry.getSubAgent("absent");
            fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("未注册的子代理"));
        }
    }

    @Test
    public void registerRequiresDescription() {
        ToolRegistry registry = new ToolRegistry();
        try {
            registry.registerSubAgent("x", " ");
            fail("description 为空应抛异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("description"));
        }
    }

    @Test
    public void buildRequiresSystemPrompt() {
        ToolRegistry registry = new ToolRegistry();
        try {
            registry.registerSubAgent("x", "描述").build();
            fail("未设置 systemPrompt 应抛异常");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("systemPrompt"));
        }
    }

    @Test
    public void duplicateSubAgentNameRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("dup", "1").systemPrompt("s").build();
        try {
            registry.registerSubAgent("dup", "2");
            fail("重名应抛异常");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("同名"));
        }
    }

    @Test
    public void subAgentNameClashWithRegularToolRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("shared", "普通工具").handle(args -> "ok");
        try {
            registry.registerSubAgent("shared", "子代理");
            fail("与普通工具重名应抛异常");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("同名"));
        }
    }

    // ---- schema: DIRECT ----

    @Test
    public void directSubAgentToolSchemaOnlyTask() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "负责调研与归纳")
                .systemPrompt("你是调研代理。")
                .build();

        List<Tool> directTools = registry.getDirectSubAgentTools();
        assertEquals(1, directTools.size());

        Tool tool = directTools.get(0);
        assertEquals("research", tool.name());
        assertEquals("负责调研与归纳", tool.description());

        // schema 含 task(必填) + run_in_background(可选,D11)
        Map<String, Map<String, Object>> schemas = schemasOf(tool);
        assertTrue(schemas.containsKey("task"));
        assertTrue(schemas.containsKey("run_in_background"));
        assertEquals(2, schemas.size());

        List<String> required = requiredOf(tool);
        assertEquals(List.of("task"), required);
    }

    // ---- schema: DELEGATE ----

    @Test
    public void delegateToolEnumFromRegisteredSubAgents() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研").systemPrompt("s").build();
        registry.registerSubAgent("review", "审核").systemPrompt("s").build();

        Optional<Tool> delegate = registry.getDelegateSubAgentTool();
        assertTrue(delegate.isPresent());
        Tool tool = delegate.get();
        assertEquals(ToolRegistry.DELEGATE_TOOL_NAME, tool.name());

        Map<String, Map<String, Object>> schemas = schemasOf(tool);
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) schemas.get("agentName").get("enum");
        // agentName enum 与已注册子代理名一致（按注册顺序）
        assertEquals(List.of("research", "review"), enumValues);
    }

    @Test
    public void delegateToolEmptyWhenNoSubAgents() {
        ToolRegistry registry = new ToolRegistry();
        assertFalse(registry.getDelegateSubAgentTool().isPresent());
    }

    // ---- getTools 兼容性 ----

    @Test
    public void getToolsExcludesSubAgents() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("normal", "普通工具").handle(args -> "ok");
        registry.registerSubAgent("sub", "子代理").systemPrompt("s").build();

        List<Tool> tools = registry.getTools();
        assertEquals(1, tools.size());
        assertEquals("normal", tools.get(0).name());
    }

    // ---- execute fail-fast ----

    @Test
    public void executeSubAgentFailsFast() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理").systemPrompt("s").build();

        try {
            registry.execute("sub", "{\"task\":\"x\"}");
            fail("直接 execute 子代理应 fail-fast");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("仅支持在 Agent 自动循环中执行"));
        }
    }

    @Test
    public void executeDelegateToolFailsFast() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理").systemPrompt("s").build();

        try {
            registry.execute(ToolRegistry.DELEGATE_TOOL_NAME, "{}");
            fail("直接 execute delegate tool 应 fail-fast");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("仅支持在 Agent 自动循环中执行"));
        }
    }

    // ---- 辅助：读取 schema（复用 ToolRegistryTest 的解析方式） ----

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> schemasOf(Tool tool) {
        FunctionDefinition fd = tool.toFunctionDefinition();
        assertTrue("schema 必须存在", fd.parameters().isPresent());
        FunctionParameters params = fd.parameters().get();
        JsonValue propsValue = params._additionalProperties().get("properties");
        assertNotNull("properties 必须存在", propsValue);
        return propsValue.convert(Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> requiredOf(Tool tool) {
        FunctionDefinition fd = tool.toFunctionDefinition();
        FunctionParameters params = fd.parameters().get();
        JsonValue reqValue = params._additionalProperties().get("required");
        assertNotNull("required 必须存在", reqValue);
        return reqValue.convert(List.class);
    }
}
