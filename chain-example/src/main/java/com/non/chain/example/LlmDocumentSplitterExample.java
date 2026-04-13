package com.non.chain.example;

import com.non.chain.document.*;
import com.non.chain.document.splitter.*;
import com.non.chain.knowledge.TextChunk;
import com.non.chain.provider.DashscopeLLM;

import java.util.Arrays;
import java.util.List;

/**
 * 示例：基于 LLM 的文档切分器。
 * <p>
 * 演示 {@link LlmDocumentSplitter} 的用法：结合规则清洗和 LLM 语义切分，
 * 生成语义完整的 chunk 用于 RAG 检索。
 */
public class LlmDocumentSplitterExample {

    public static void main(String[] args) {
        System.out.println("===== 1. 纯文本 LLM 切分 =====");
        basicLlmSplit();

        System.out.println();
        System.out.println("===== 2. 含原子元素的 LLM 切分 =====");
        llmSplitWithAtomicElements();

        System.out.println();
        System.out.println("===== 3. 自定义 prompt 模板 =====");
        customPromptSplit();
    }

    private static void basicLlmSplit() {
        DashscopeLLM llm = new DashscopeLLM("qwen-plus");

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(llm)
                .targetChunkSize(500)
                .segmentSize(5000)
                .build();

        String text = "非链库是一个轻量级Java LLM工具库。它提供了文档处理、向量化存储和检索等功能。" +
                "框架设计简洁，易于扩展。支持多种文档格式，包括PDF、Word、Markdown等。" +
                "核心特性包括：工具调用、流式对话、图工作流引擎等。";

        List<TextChunk> chunks = splitter.split(text);

        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d ---%n", i);
            System.out.println("  title: " + chunk.metadata().get("title"));
            System.out.println("  content: " + chunk.content());
        }
    }

    private static void llmSplitWithAtomicElements() {
        DashscopeLLM llm = new DashscopeLLM("qwen-plus");

        ParsedDocument document = ParsedDocument.builder(
                DocumentMetadata.builder().fileName("tech.md").format("md").build())
                .addElement(TextElement.builder("深度学习在图像识别领域取得了突破性进展。卷积神经网络是最常用的架构。").build())
                .addElement(TableElement.builder()
                        .addHeader("模型")
                        .addHeader("准确率")
                        .addRow(Arrays.asList("ResNet", "96.4%"))
                        .addRow(Arrays.asList("VGG", "92.3%"))
                        .build())
                .addElement(TextElement.builder("自然语言处理也有显著进步。Transformer架构彻底改变了这一领域。").build())
                .build();

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(llm)
                .targetChunkSize(500)
                .build();

        List<TextChunk> chunks = splitter.split(document);

        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d [type=%s] ---%n", i, chunk.elementType());
            String preview = chunk.content().substring(0, Math.min(60, chunk.content().length()));
            System.out.println("  " + preview + (chunk.content().length() > 60 ? "..." : ""));
        }
    }

    private static void customPromptSplit() {
        DashscopeLLM llm = new DashscopeLLM("qwen-plus");

        String customPrompt =
                "你是一个技术文档切分专家。请将以下技术文档切分为语义完整的片段，用于RAG检索。\n" +
                "每个chunk应包含一个完整的技术概念。\n" +
                "目标每个chunk大约 TARGET_SIZE 个token。\n" +
                "输出JSON数组：[{\"content\": \"...\", \"title\": \"...\"}]";

        LlmDocumentSplitter splitter = LlmDocumentSplitter.builder(llm)
                .targetChunkSize(300)
                .segmentSize(3000)
                .promptTemplate(customPrompt)
                .build();

        String text = "Java 11引入了var关键字用于局部变量类型推断。" +
                "Stream API得到了增强，新增了takeWhile和dropWhile方法。" +
                "HttpClient被标准化，替代了传统的HttpURLConnection。";

        List<TextChunk> chunks = splitter.split(text);

        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d ---%n", i);
            System.out.println("  title: " + chunk.metadata().get("title"));
            System.out.println("  content: " + chunk.content());
        }
    }
}
