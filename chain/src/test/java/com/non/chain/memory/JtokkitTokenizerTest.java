package com.non.chain.memory;

import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import com.non.chain.Message;
import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class JtokkitTokenizerTest {

    @Test
    public void testEstimateTokenCountText() {
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        int count = tokenizer.estimateTokenCount("Hello, world!");
        assertTrue(count > 0);
    }

    @Test
    public void testEstimateTokenCountEmpty() {
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        assertEquals(0, tokenizer.estimateTokenCount(""));
        assertEquals(0, tokenizer.estimateTokenCount((String) null));
    }

    @Test
    public void testEstimateTokenCountMessage() {
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        Message msg = Message.user("你好，请问今天天气怎么样？");
        int count = tokenizer.estimateTokenCount(msg);
        assertTrue(count > 0);
    }

    @Test
    public void testEstimateTokenCountMessageWithToolCalls() {
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"北京\"}");
        Message msg = Message.assistantWithToolCalls("正在查询天气", Arrays.asList(toolCall));
        int count = tokenizer.estimateTokenCount(msg);
        assertTrue(count > 0);
    }

    @Test
    public void testEstimateTokenCountMessageList() {
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        List<Message> messages = Arrays.asList(
                Message.user("你好"),
                Message.assistant("你好！")
        );
        int total = tokenizer.estimateTokenCount(messages);
        assertTrue(total > 0);

        // 总数应该大于单条消息的 token 数
        int single = tokenizer.estimateTokenCount(messages.get(0));
        assertTrue(total > single);
    }

    @Test
    public void testEstimateTokenCountEmptyList() {
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        assertEquals(0, tokenizer.estimateTokenCount((List<Message>) null));
    }

    @Test
    public void testOfEncoding() {
        Tokenizer tokenizer = JtokkitTokenizer.ofEncoding(EncodingType.O200K_BASE);
        int count = tokenizer.estimateTokenCount("Hello");
        assertTrue(count > 0);
    }

    @Test
    public void testOfModel() {
        Tokenizer tokenizer = JtokkitTokenizer.ofModel(ModelType.GPT_4);
        int count = tokenizer.estimateTokenCount("Hello");
        assertTrue(count > 0);
    }

    @Test
    public void testNullMessage() {
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        assertEquals(0, tokenizer.estimateTokenCount((Message) null));
    }
}
