package com.non.chain.document.splitter;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.document.CodeBlockElement;
import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.ElementType;
import com.non.chain.document.ImageElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TableElement;
import com.non.chain.document.TextElement;
import com.non.chain.knowledge.TextChunk;
import com.non.chain.provider.LLM;
import com.non.chain.tool.Tool;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * LlmDocumentSplitter 单元测试。
 * <p>
 * 使用 Mock LLM 验证切分逻辑，不依赖真实 LLM 服务。
 */
public class LlmDocumentSplitterTest {

    private final DocumentMetadata metadata = DocumentMetadata.builder()
            .fileName("test.md")
            .format("md")
            .build();

    // ---- 正常切分 ----

    @Test
    public void split_normalText_returnsLlmChunks() {
        String llmResponse = "[{\"content\": \"第一段内容。\", \"title\": \"第一段\"}, " +
                "{\"content\": \"第二段内容。\", \"title\": \"第二段\"}]";
        LLM mockLlm = new FixedResponseLLM(llmResponse);

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(mockLlm)
                .targetChunkSize(500)
                .segmentSize(5000)
                .build();

        List<TextChunk> chunks = splitter.split("这是一段测试文本。这是另一段测试文本。");

        assertEquals(2, chunks.size());
        assertEquals("第一段内容。", chunks.get(0).content());
        assertEquals("第一段", chunks.get(0).metadata().get("title"));
        assertEquals(Integer.valueOf(0), chunks.get(0).metadata().get("chunkIndex"));

        assertEquals("第二段内容。", chunks.get(1).content());
        assertEquals("第二段", chunks.get(1).metadata().get("title"));
        assertEquals(Integer.valueOf(1), chunks.get(1).metadata().get("chunkIndex"));
    }

    @Test
    public void split_singleChunk_returnsOneChunk() {
        String llmResponse = "[{\"content\": \"完整内容。\", \"title\": \"标题\"}]";
        LLM mockLlm = new FixedResponseLLM(llmResponse);

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(mockLlm).build();

        List<TextChunk> chunks = splitter.split("简短文本。");

        assertEquals(1, chunks.size());
        assertEquals("完整内容。", chunks.get(0).content());
    }

    // ---- 原子元素透传 ----

    @Test
    public void split_atomicElements_passedThroughDirectly() {
        String llmResponse = "[{\"content\": \"前文。\", \"title\": \"前\"}]";
        LLM mockLlm = new FixedResponseLLM(llmResponse);

        ParsedDocument document = ParsedDocument.builder(metadata)
                .addElement(TextElement.builder("前文").build())
                .addElement(TableElement.builder()
                        .addHeader("姓名")
                        .addHeader("城市")
                        .addRow(Arrays.asList("张三", "北京"))
                        .build())
                .addElement(CodeBlockElement.builder("System.out.println(\"ok\");")
                        .language("java")
                        .build())
                .addElement(ImageElement.builder(new byte[]{1}, "image/png")
                        .fileName("img-1.png")
                        .position(DocumentPosition.builder().pageNumber(3).build())
                        .build())
                .build();

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(mockLlm)
                .targetChunkSize(500)
                .build();

        List<TextChunk> chunks = splitter.split(document);

        assertEquals(4, chunks.size());

        // 第一个是 LLM 切分的文本
        assertEquals(ElementType.TEXT, chunks.get(0).elementType());
        assertEquals(Integer.valueOf(0), chunks.get(0).metadata().get("chunkIndex"));
        assertEquals("前文。", chunks.get(0).content());

        // 第二个是 TABLE 原子元素
        assertEquals(ElementType.TABLE, chunks.get(1).elementType());
        assertTrue(chunks.get(1).content().contains("| 姓名 | 城市 |"));
        assertEquals(Integer.valueOf(1), chunks.get(1).metadata().get("chunkIndex"));

        // 第三个是 CODE_BLOCK 原子元素
        assertEquals(ElementType.CODE_BLOCK, chunks.get(2).elementType());
        assertEquals("java", chunks.get(2).metadata().get("language"));
        assertEquals(Integer.valueOf(2), chunks.get(2).metadata().get("chunkIndex"));

        // 第四个是 IMAGE 原子元素
        assertEquals(ElementType.IMAGE, chunks.get(3).elementType());
        assertEquals("img-1.png", chunks.get(3).metadata().get("imageRef"));
        assertEquals(Integer.valueOf(3), chunks.get(3).metadata().get("chunkIndex"));
    }

    // ---- JSON 解析错误 + 重试 ----

    @Test
    public void split_malformedJson_retriesAndSucceeds() {
        AtomicInteger callCount = new AtomicInteger(0);
        LLM retryLlm = new LLM() {
            @Override
            public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
                int count = callCount.incrementAndGet();
                if (count == 1) {
                    return new ChatResult("这不是JSON", null);
                }
                return new ChatResult(
                        "[{\"content\": \"重试成功。\", \"title\": \"重试\"}]",
                        null
                );
            }

            @Override
            public ChatResult chat(List<Message> messages, OutputFormat outputFormat) {
                return chat("", "", outputFormat);
            }

            @Override
            public ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat) {
                return chat(systemMessage, userMessage, outputFormat);
            }

            @Override
            public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
                return chat(messages, outputFormat);
            }

            @Override
            public ChatResult streamChat(String systemMessage, String userMessage, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(systemMessage, userMessage, outputFormat);
            }

            @Override
            public ChatResult streamChat(List<Message> messages, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(messages, outputFormat);
            }

            @Override
            public ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(systemMessage, userMessage, tools, outputFormat);
            }

            @Override
            public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(messages, tools, outputFormat);
            }
        };

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(retryLlm).build();

        List<TextChunk> chunks = splitter.split("测试重试。");

        assertEquals(1, chunks.size());
        assertEquals("重试成功。", chunks.get(0).content());
        assertTrue(callCount.get() >= 2);
    }

    @Test
    public void split_allRetriesFail_usesFallbackSplit() {
        LLM alwaysFailLlm = new FixedResponseLLM("always invalid response");

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(alwaysFailLlm)
                .targetChunkSize(20)
                .contentMeasure(new CharacterMeasure())
                .build();

        String longText = "这是一段比较长的文本用于测试fallback切分功能是否正常工作。";
        List<TextChunk> chunks = splitter.split(longText);

        // fallback 使用 RecursiveCharacterSplitter，应该能切分出多个 chunk
        assertFalse(chunks.isEmpty());
        for (TextChunk chunk : chunks) {
            assertNotNull(chunk.content());
            assertFalse(chunk.content().isEmpty());
            // fallback 的 chunk 不应该有 title
            assertNull(chunk.metadata().get("title"));
        }
    }

    // ---- JSON 提取（带额外文字） ----

    @Test
    public void split_jsonWithExtraText_extractsArrayCorrectly() {
        String llmResponse = "好的，以下是切分结果：\n" +
                "[{\"content\": \"内容一。\", \"title\": \"标题一\"}, " +
                "{\"content\": \"内容二。\", \"title\": \"标题二\"}]\n" +
                "以上是切分结果。";
        LLM mockLlm = new FixedResponseLLM(llmResponse);

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(mockLlm).build();

        List<TextChunk> chunks = splitter.split("测试文本。");

        assertEquals(2, chunks.size());
        assertEquals("内容一。", chunks.get(0).content());
        assertEquals("内容二。", chunks.get(1).content());
    }

    // ---- 多段文本（超过 segmentSize 会分成多个 segment） ----

    @Test
    public void split_longText_splitsIntoMultipleSegments() {
        AtomicInteger callCount = new AtomicInteger(0);
        LLM countingLlm = new UserMessageCaptureLLM(callCount);

        // 小 segmentSize 强制多段
        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(countingLlm)
                .targetChunkSize(30)
                .segmentSize(30)
                .contentMeasure(new CharacterMeasure())
                .build();

        String longText = "第一段文本内容，比较长一些。第二段文本内容，也比较长一些。第三段文本内容。";
        List<TextChunk> chunks = splitter.split(longText);

        // 应该被分成多个 segment，每个 segment 产生一个 LLM 调用
        assertTrue("expected multiple LLM calls, got " + callCount.get(), callCount.get() > 1);
        assertFalse(chunks.isEmpty());
    }

    // ---- 空文档 ----

    @Test
    public void split_emptyDocument_returnsEmptyList() {
        LLM mockLlm = new FixedResponseLLM("[]");

        ParsedDocument emptyDoc = ParsedDocument.builder(metadata).build();
        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(mockLlm).build();

        List<TextChunk> chunks = splitter.split(emptyDoc);

        assertTrue(chunks.isEmpty());
    }

    // ---- Builder 校验 ----

    @Test(expected = IllegalArgumentException.class)
    public void build_zeroTargetChunkSize_throwsException() {
        LlmDocumentSplitter.builder(new FixedResponseLLM("[]"))
                .targetChunkSize(0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_segmentSizeSmallerThanTargetChunkSize_throwsException() {
        LlmDocumentSplitter.builder(new FixedResponseLLM("[]"))
                .targetChunkSize(1000)
                .segmentSize(500)
                .build();
    }

    @Test(expected = NullPointerException.class)
    public void build_nullLlm_throwsException() {
        LlmDocumentSplitter.builder(null);
    }

    // ---- oversized 原子元素标记 ----

    @Test
    public void split_oversizedAtomicElement_marksOversized() {
        LLM mockLlm = new FixedResponseLLM("[{\"content\": \"x\", \"title\": \"t\"}]");

        ParsedDocument document = ParsedDocument.builder(metadata)
                .addElement(CodeBlockElement.builder(
                        "01234567890123456789012345678901234567890123456789")
                        .build())
                .build();

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(mockLlm)
                .targetChunkSize(10)
                .contentMeasure(new CharacterMeasure())
                .build();

        List<TextChunk> chunks = splitter.split(document);

        assertEquals(1, chunks.size());
        assertEquals(Boolean.TRUE, chunks.get(0).metadata().get("oversized"));
    }

    // ---- 自定义 promptTemplate ----

    @Test
    public void split_customPromptTemplate_usedInLlmCall() {
        final String[] capturedSystemPrompt = {null};
        LLM capturingLlm = new LLM() {
            @Override
            public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
                capturedSystemPrompt[0] = systemMessage;
                return new ChatResult("[{\"content\": \"结果\", \"title\": \"t\"}]", null);
            }

            @Override
            public ChatResult chat(List<Message> messages, OutputFormat outputFormat) {
                return chat("", "", outputFormat);
            }

            @Override
            public ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat) {
                return chat(systemMessage, userMessage, outputFormat);
            }

            @Override
            public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
                return chat(messages, outputFormat);
            }

            @Override
            public ChatResult streamChat(String systemMessage, String userMessage, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(systemMessage, userMessage, outputFormat);
            }

            @Override
            public ChatResult streamChat(List<Message> messages, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(messages, outputFormat);
            }

            @Override
            public ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(systemMessage, userMessage, tools, outputFormat);
            }

            @Override
            public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
                return chat(messages, tools, outputFormat);
            }
        };

        String customPrompt = "自定义提示 TARGET_SIZE 测试";
        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(capturingLlm)
                .promptTemplate(customPrompt)
                .targetChunkSize(300)
                .build();

        splitter.split("测试");

        assertNotNull(capturedSystemPrompt[0]);
        assertEquals("自定义提示 300 测试", capturedSystemPrompt[0]);
    }

    // ---- Mock LLM 实现 ----

    /**
     * 固定返回预设响应的 Mock LLM。
     */
    private static class FixedResponseLLM implements LLM {
        private final String response;

        FixedResponseLLM(String response) {
            this.response = response;
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
            return new ChatResult(response, null);
        }

        @Override
        public ChatResult chat(List<Message> messages, OutputFormat outputFormat) {
            return new ChatResult(response, null);
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat) {
            return new ChatResult(response, null);
        }

        @Override
        public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
            return new ChatResult(response, null);
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return new ChatResult(response, null);
        }

        @Override
        public ChatResult streamChat(List<Message> messages, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return new ChatResult(response, null);
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return new ChatResult(response, null);
        }

        @Override
        public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return new ChatResult(response, null);
        }
    }

    /**
     * 记录 userMessage 前缀并计数的 Mock LLM。
     */
    private static class UserMessageCaptureLLM implements LLM {
        private final AtomicInteger callCount;

        UserMessageCaptureLLM(AtomicInteger callCount) {
            this.callCount = callCount;
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
            callCount.incrementAndGet();
            String preview = userMessage.substring(0, Math.min(20, userMessage.length()));
            return new ChatResult(
                    "[{\"content\": \"" + preview + "\", \"title\": \"片段\"}]",
                    null
            );
        }

        @Override
        public ChatResult chat(List<Message> messages, OutputFormat outputFormat) {
            return chat("", "", outputFormat);
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat) {
            return chat(systemMessage, userMessage, outputFormat);
        }

        @Override
        public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
            return chat(messages, outputFormat);
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return chat(systemMessage, userMessage, outputFormat);
        }

        @Override
        public ChatResult streamChat(List<Message> messages, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return chat(messages, outputFormat);
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return chat(systemMessage, userMessage, tools, outputFormat);
        }

        @Override
        public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            return chat(messages, tools, outputFormat);
        }
    }
}
