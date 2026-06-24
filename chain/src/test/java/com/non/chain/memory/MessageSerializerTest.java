package com.non.chain.memory;

import com.non.chain.ContentPart;
import com.non.chain.ImageUrlPart;
import com.non.chain.Message;
import com.non.chain.TextPart;
import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class MessageSerializerTest {

    @Test
    public void testSerializeUserMessage() {
        Message msg = Message.user("你好");
        String json = MessageSerializer.serialize(msg);
        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"content\":\"你好\""));
    }

    @Test
    public void testSerializeAssistantMessage() {
        Message msg = Message.assistant("你好！");
        String json = MessageSerializer.serialize(msg);
        assertTrue(json.contains("\"role\":\"assistant\""));
    }

    @Test
    public void testSerializeSystemMessage() {
        Message msg = Message.system("你是助手");
        String json = MessageSerializer.serialize(msg);
        assertTrue(json.contains("\"role\":\"system\""));
    }

    @Test
    public void testSerializeToolResultMessage() {
        Message msg = Message.toolResult("call-1", "结果");
        String json = MessageSerializer.serialize(msg);
        assertTrue(json.contains("\"role\":\"tool\""));
        assertTrue(json.contains("\"toolCallId\":\"call-1\""));
    }

    @Test
    public void testSerializeAssistantWithToolCalls() {
        ToolCall tc = new ToolCall("call-1", "get_weather", "{\"city\":\"北京\"}");
        Message msg = Message.assistantWithToolCalls("查询中", Arrays.asList(tc));
        String json = MessageSerializer.serialize(msg);
        assertTrue(json.contains("\"toolCalls\""));
        assertTrue(json.contains("\"name\":\"get_weather\""));
    }

    @Test
    public void testSerializeMultimodalMessage() {
        List<ContentPart> parts = Arrays.asList(
                new TextPart("描述图片"),
                new ImageUrlPart("https://example.com/img.png")
        );
        Message msg = Message.user(parts);
        String json = MessageSerializer.serialize(msg);
        assertTrue(json.contains("\"contentParts\""));
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"type\":\"imageUrl\""));
    }

    @Test
    public void testDeserializeUserMessage() {
        String json = "{\"role\":\"user\",\"content\":\"你好\"}";
        Message msg = MessageSerializer.deserialize(json);
        assertEquals("user", msg.role());
        assertEquals("你好", msg.content());
    }

    @Test
    public void testDeserializeAssistantWithToolCalls() {
        String json = "{\"role\":\"assistant\",\"content\":\"查询中\"," +
                "\"toolCalls\":[{\"id\":\"call-1\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}]}";
        Message msg = MessageSerializer.deserialize(json);
        assertEquals("assistant", msg.role());
        assertEquals("查询中", msg.content());
        assertNotNull(msg.toolCalls());
        assertEquals(1, msg.toolCalls().size());
        assertEquals("get_weather", msg.toolCalls().get(0).name());
    }

    @Test
    public void testDeserializeToolResult() {
        String json = "{\"role\":\"tool\",\"content\":\"晴天\",\"toolCallId\":\"call-1\"}";
        Message msg = MessageSerializer.deserialize(json);
        assertEquals("tool", msg.role());
        assertEquals("晴天", msg.content());
        assertEquals("call-1", msg.toolCallId());
    }

    @Test
    public void testRoundTripUserMessage() {
        Message original = Message.user("这是一条测试消息");
        String json = MessageSerializer.serialize(original);
        Message restored = MessageSerializer.deserialize(json);
        assertEquals(original.role(), restored.role());
        assertEquals(original.content(), restored.content());
    }

    @Test
    public void testRoundTripToolCallsMessage() {
        ToolCall tc = new ToolCall("call-1", "search", "{\"q\":\"test\"}");
        Message original = Message.assistantWithToolCalls(null, Arrays.asList(tc));
        String json = MessageSerializer.serialize(original);
        Message restored = MessageSerializer.deserialize(json);
        assertEquals(original.role(), restored.role());
        assertNotNull(restored.toolCalls());
        assertEquals(1, restored.toolCalls().size());
        assertEquals("call-1", restored.toolCalls().get(0).id());
        assertEquals("search", restored.toolCalls().get(0).name());
    }

    @Test
    public void testRoundTripMultimodal() {
        List<ContentPart> parts = Arrays.asList(
                new TextPart("看这个"),
                new ImageUrlPart("https://example.com/a.png")
        );
        Message original = Message.user(parts);
        String json = MessageSerializer.serialize(original);
        Message restored = MessageSerializer.deserialize(json);
        assertNotNull(restored.contentParts());
        assertEquals(2, restored.contentParts().size());
    }

    // ---- 应用层消息分层（R3 持久化往返）----

    @Test
    public void testSerializeNoteIncludesLlmVisibleAndKind() {
        Message msg = Message.note("status", "已读取文件 X");
        String json = MessageSerializer.serialize(msg);
        assertTrue(json.contains("\"role\":\"note\""));
        assertTrue(json.contains("\"llmVisible\":false"));
        assertTrue(json.contains("\"kind\":\"status\""));
    }

    @Test
    public void testSerializeLlmVisibleMessageHasTrueFlag() {
        // 普通 LLM 可见消息序列化时总写 llmVisible=true
        String json = MessageSerializer.serialize(Message.user("你好"));
        assertTrue(json.contains("\"llmVisible\":true"));
        assertFalse("kind=null 不应写入 kind 字段", json.contains("\"kind\""));
    }

    @Test
    public void testRoundTripNotePreservesAllFields() {
        Message original = Message.note("ui", "工具审核中");
        String json = MessageSerializer.serialize(original);
        Message restored = MessageSerializer.deserialize(json);
        assertEquals("note", restored.role());
        assertEquals("工具审核中", restored.content());
        assertFalse("llmVisible 必须保留为 false", restored.llmVisible());
        assertEquals("ui", restored.kind());
    }

    @Test
    public void testDeserializeLegacyJsonDefaultsToLlmVisible() {
        // 旧数据不含 llmVisible/kind 字段，反序列化默认 llmVisible=true, kind=null
        String legacyJson = "{\"role\":\"user\",\"content\":\"旧消息\"}";
        Message restored = MessageSerializer.deserialize(legacyJson);
        assertTrue("旧数据应默认 llmVisible=true", restored.llmVisible());
        assertNull("旧数据 kind 应为 null", restored.kind());
    }

    @Test
    public void testRoundTripRegularMessagePreservesLlmVisibleTrue() {
        Message original = Message.assistant("普通回复");
        String json = MessageSerializer.serialize(original);
        Message restored = MessageSerializer.deserialize(json);
        assertTrue(restored.llmVisible());
        assertNull(restored.kind());
    }
}
