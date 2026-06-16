package com.non.chain.tool;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 工具参数 JSON 解析（数组/对象/转义）+ 注解 schema 生成 + convertType 全链路测试。
 * parser/convertType 为 private，统一通过公开 API {@code execute(name, json)} 触发；
 * schema 断言通过 {@code getTools()} -> {@code toFunctionDefinition()} 读 parameters 附加属性。
 */
public class ToolRegistryTest {

    // ====================== Parser / ToolArgs 消费（fluent） ======================

    @Test
    public void fluentScalarRegression() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("scalar", "标量回归")
                .param("city", "string", "城市", true)
                .param("n", "number", "整数", true)
                .param("d", "number", "浮点", true)
                .param("b", "boolean", "布尔", true)
                .param("x", "string", "可空", false)
                .handle(args -> args.getString("city") + "|"
                        + args.getInt("n") + "|"
                        + args.getLong("n") + "|"
                        + args.getDouble("d") + "|"
                        + args.getBoolean("b") + "|"
                        + args.has("x"));

        // Jackson 数字存为 Number（Integer/Double），三条链路结果须与旧 String 路径一致
        String result = registry.execute("scalar",
                "{\"city\":\"北京\",\"n\":12,\"d\":1.5,\"b\":true,\"x\":null}");
        assertEquals("北京|12|12|1.5|true|false", result);
    }

    @Test
    public void parseArrayValue() {
        List<Object> captured = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register("arr", "数组")
                .param("points", "array", "点", true)
                .handle(args -> {
                    captured.add(args.get("points"));
                    return "ok";
                });
        registry.execute("arr", "{\"points\":[12,34]}");

        Object points = captured.get(0);
        assertTrue("应为 List", points instanceof List);
        assertEquals(Arrays.asList(12, 34), points);
    }

    @Test
    public void parseNestedObjectValue() {
        List<Object> captured = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register("obj", "对象")
                .param("config", "object", "配置", true)
                .handle(args -> {
                    captured.add(args.get("config"));
                    return "ok";
                });
        registry.execute("obj", "{\"config\":{\"k\":\"v\"}}");

        Object config = captured.get(0);
        assertTrue("应为 Map", config instanceof Map);
        assertEquals(Collections.singletonMap("k", "v"), config);
    }

    @Test
    public void parseStringWithEscapeAndComma() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("str", "字符串")
                .param("name", "string", "名", true)
                .param("note", "string", "备注", true)
                .handle(args -> args.getString("name") + "|" + args.getString("note"));

        // name 含内嵌逗号，note 含转义引号 —— 旧手写 parser 会被逗号/引号截断
        String json = "{\"name\":\"a,b\",\"note\":\"say \\\"hi\\\"\"}";
        String result = registry.execute("str", json);
        assertEquals("a,b|say \"hi\"", result);
    }

    @Test
    public void parseNullValue() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("nul", "空值")
                .param("x", "string", "可空", true)
                .handle(args -> String.valueOf(args.getString("x")) + "|" + args.has("x"));
        String result = registry.execute("nul", "{\"x\":null}");
        assertEquals("null|false", result);
    }

    @Test
    public void parseEmptyInput() {
        int[] count = {0};
        ToolRegistry registry = new ToolRegistry();
        registry.register("empty", "空入参")
                .param("x", "string", "可选", false)
                .handle(args -> {
                    count[0]++;
                    return "ok";
                });

        registry.execute("empty", null);
        registry.execute("empty", "");
        registry.execute("empty", "   ");
        assertEquals(3, count[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseInvalidJsonThrows() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("bad", "非法")
                .param("x", "string", "", true)
                .handle(args -> "ok");
        registry.execute("bad", "{\"bad");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseTopLevelArrayThrows() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("top", "顶层非对象")
                .param("x", "string", "", true)
                .handle(args -> "ok");
        // 合法 JSON 数组，但工具参数必须是对象 -> 结构错误
        registry.execute("top", "[1,2]");
    }

    // ====================== Schema 生成（注解方式） ======================

    /** 注解 schema 生成用的工具集合。 */
    public static class AnnoTools {
        @ToolDef(name = "list_tool", description = "list")
        public String listTool(@ToolParam(name = "points") List<Integer> points) {
            return String.valueOf(points);
        }

        @ToolDef(name = "array_tool", description = "array")
        public String arrayTool(@ToolParam(name = "tags") String[] tags) {
            return Arrays.toString(tags);
        }

        @ToolDef(name = "map_tool", description = "map")
        public String mapTool(@ToolParam(name = "data") Map<String, Object> data) {
            return String.valueOf(data);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> schemasOf(Tool tool) {
        FunctionDefinition fd = tool.toFunctionDefinition();
        assertTrue("schema 必须存在", fd.parameters().isPresent());
        FunctionParameters params = fd.parameters().get();
        JsonValue propsValue = params._additionalProperties().get("properties");
        assertNotNull("properties 必须存在", propsValue);
        return propsValue.convert(Map.class);
    }

    private Tool findTool(ToolRegistry registry, String name) {
        return registry.getTools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到工具: " + name));
    }

    @Test
    public void annotationListGeneratesArrayItemsNumber() {
        ToolRegistry registry = new ToolRegistry();
        registry.scan(new AnnoTools());
        Map<String, Map<String, Object>> schemas = schemasOf(findTool(registry, "list_tool"));
        Map<String, Object> points = schemas.get("points");
        assertEquals("array", points.get("type"));
        assertNotNull("items 必须存在", points.get("items"));
        assertEquals("number", ((Map<String, Object>) points.get("items")).get("type"));
    }

    @Test
    public void annotationArrayGeneratesArrayItemsString() {
        ToolRegistry registry = new ToolRegistry();
        registry.scan(new AnnoTools());
        Map<String, Map<String, Object>> schemas = schemasOf(findTool(registry, "array_tool"));
        Map<String, Object> tags = schemas.get("tags");
        assertEquals("array", tags.get("type"));
        assertEquals("string", ((Map<String, Object>) tags.get("items")).get("type"));
    }

    @Test
    public void annotationMapGeneratesObjectType() {
        ToolRegistry registry = new ToolRegistry();
        registry.scan(new AnnoTools());
        Map<String, Map<String, Object>> schemas = schemasOf(findTool(registry, "map_tool"));
        Map<String, Object> data = schemas.get("data");
        assertEquals("object", data.get("type"));
        assertNull("object 不应有 items", data.get("items"));
    }

    // ====================== 注解方式端到端（容器/数组参数接收） ======================

    /** 注解端到端：方法参数为容器/数组类型，验证 convertType 适配后能正确接收。 */
    public static class AnnoExecutor {
        Object received;

        @ToolDef(name = "nums_tool", description = "nums")
        public String numsTool(@ToolParam(name = "nums") int[] nums) {
            received = nums;
            return Arrays.toString(nums);
        }

        @ToolDef(name = "list_tool2", description = "list2")
        public String listTool2(@ToolParam(name = "items") List<String> items) {
            received = items;
            return String.valueOf(items);
        }

        @ToolDef(name = "set_tool", description = "set")
        public String setTool(@ToolParam(name = "tags") Set<String> tags) {
            received = tags;
            return String.valueOf(tags);
        }
    }

    @Test
    public void annotationEndToEndIntArray() {
        AnnoExecutor ex = new AnnoExecutor();
        ToolRegistry registry = new ToolRegistry();
        registry.scan(ex);
        String result = registry.execute("nums_tool", "{\"nums\":[1,2]}");
        assertEquals("[1, 2]", result);
        assertTrue("应为 int[]", ex.received instanceof int[]);
        assertArrayEquals(new int[]{1, 2}, (int[]) ex.received);
    }

    @Test
    public void annotationEndToEndList() {
        AnnoExecutor ex = new AnnoExecutor();
        ToolRegistry registry = new ToolRegistry();
        registry.scan(ex);
        String result = registry.execute("list_tool2", "{\"items\":[\"a\",\"b\"]}");
        assertEquals("[a, b]", result);
        assertTrue("应为 List", ex.received instanceof List);
        assertEquals(Arrays.asList("a", "b"), ex.received);
    }

    @Test
    public void annotationEndToEndSetDedup() {
        AnnoExecutor ex = new AnnoExecutor();
        ToolRegistry registry = new ToolRegistry();
        registry.scan(ex);
        registry.execute("set_tool", "{\"tags\":[\"x\",\"x\",\"y\"]}");
        assertTrue("应为 Set", ex.received instanceof Set);
        Set<?> set = (Set<?>) ex.received;
        assertEquals("Set 须去重", 2, set.size());
        assertTrue(set.contains("x"));
        assertTrue(set.contains("y"));
    }
}
