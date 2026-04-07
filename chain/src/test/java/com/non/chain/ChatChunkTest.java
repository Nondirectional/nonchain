package com.non.chain;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ChatChunkTest {

    @Test
    public void testDeltaContentChunk() {
        ChatChunk chunk = new ChatChunk("Hello", null, null, null);

        assertTrue(chunk.hasContent());
        assertEquals("Hello", chunk.deltaContent());
        assertFalse(chunk.hasThinking());
        assertFalse(chunk.hasToolCalls());
        assertFalse(chunk.isFinished());
        assertNull(chunk.finishReason());
    }

    @Test
    public void testDeltaThinkingChunk() {
        ChatChunk chunk = new ChatChunk(null, "Let me think...", null, null);

        assertFalse(chunk.hasContent());
        assertTrue(chunk.hasThinking());
        assertEquals("Let me think...", chunk.deltaThinking());
    }

    @Test
    public void testDeltaToolCallChunk() {
        ChatChunk.DeltaToolCall dtc = new ChatChunk.DeltaToolCall(0, "call_123", "get_weather", null);
        ChatChunk chunk = new ChatChunk(null, null, Collections.singletonList(dtc), null);

        assertFalse(chunk.hasContent());
        assertTrue(chunk.hasToolCalls());
        assertEquals(1, chunk.deltaToolCalls().size());
        assertEquals(0, chunk.deltaToolCalls().get(0).index());
        assertEquals("call_123", chunk.deltaToolCalls().get(0).id());
        assertEquals("get_weather", chunk.deltaToolCalls().get(0).name());
    }

    @Test
    public void testFinishChunk() {
        ChatChunk chunk = new ChatChunk(null, null, null, "stop");

        assertTrue(chunk.isFinished());
        assertEquals("stop", chunk.finishReason());
    }

    @Test
    public void testToolCallArgumentsDelta() {
        ChatChunk.DeltaToolCall dtc1 = new ChatChunk.DeltaToolCall(0, "call_1", "search", "{\"qu");
        ChatChunk.DeltaToolCall dtc2 = new ChatChunk.DeltaToolCall(0, null, null, "ery\":");
        ChatChunk.DeltaToolCall dtc3 = new ChatChunk.DeltaToolCall(0, null, null, "\"hello\"}");

        assertEquals("{\"qu", dtc1.argumentsDelta());
        assertNull(dtc2.id());
        assertNull(dtc2.name());
    }

    @Test
    public void testMultipleToolCallsInOneChunk() {
        ChatChunk.DeltaToolCall dtc1 = new ChatChunk.DeltaToolCall(0, "call_1", "search", null);
        ChatChunk.DeltaToolCall dtc2 = new ChatChunk.DeltaToolCall(1, "call_2", "translate", null);
        List<ChatChunk.DeltaToolCall> deltaToolCalls = Arrays.asList(dtc1, dtc2);

        ChatChunk chunk = new ChatChunk(null, null, deltaToolCalls, null);

        assertEquals(2, chunk.deltaToolCalls().size());
        assertEquals(0, chunk.deltaToolCalls().get(0).index());
        assertEquals(1, chunk.deltaToolCalls().get(1).index());
    }

    @Test
    public void testEmptyChunk() {
        ChatChunk chunk = new ChatChunk(null, null, null, null);

        assertFalse(chunk.hasContent());
        assertFalse(chunk.hasThinking());
        assertFalse(chunk.hasToolCalls());
        assertFalse(chunk.isFinished());
    }

    @Test
    public void testNullToolCallsList() {
        ChatChunk chunk = new ChatChunk("text", null, null, null);
        assertNotNull(chunk.deltaToolCalls());
        assertTrue(chunk.deltaToolCalls().isEmpty());
    }

    @Test
    public void testContentAndThinkingTogether() {
        ChatChunk chunk = new ChatChunk("some text", "thinking...", null, null);
        assertTrue(chunk.hasContent());
        assertTrue(chunk.hasThinking());
    }
}
