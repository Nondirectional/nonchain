package com.non.chain.example;

import com.non.chain.document.*;
import com.non.chain.document.splitter.*;
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.knowledge.TextChunk;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 示例：语义切分器。
 * <p>
 * 演示 {@link SemanticSplitter} 的用法：基于 EmbeddingModel 计算相邻文本段的语义相似度，在语义断点处切分。
 * <p>
 * 注意：生产环境需要传入真实的 EmbeddingModel（如 {@link com.non.chain.embedding.DashScopeEmbeddingModel}）。
 * 本示例使用 Stub 模拟，无需外部依赖即可运行。
 */
public class SemanticSplitterExample {

    public static void main(String[] args) {
        System.out.println("===== 1. 纯文本语义切分 =====");
        basicSemanticSplit();

        System.out.println();
        System.out.println("===== 2. 含原子元素的语义切分 =====");
        semanticSplitWithAtomicElements();
    }

    private static void basicSemanticSplit() {
        SemanticSplitter splitter = SemanticSplitter.builder(new DashScopeEmbeddingModel("text-embedding-v4"))
                .bufferSize(1)
                .breakpointThreshold(0.5)
                .build();

        // 前两句讨论 AI，后两句讨论体育 — 期望在话题切换处切分
        String text = "人工智能正在改变软件开发的方式。机器学习模型可以自动完成代码审查。" +
                "足球世界杯每四年举办一次。今年的比赛将在多个城市同时进行。";

        List<TextChunk> chunks = splitter.split(text);

        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d ---%n", i);
            System.out.println("  " + chunk.content());
        }
    }

    private static void semanticSplitWithAtomicElements() {
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

        SemanticSplitter splitter = SemanticSplitter.builder(new DashScopeEmbeddingModel("text-embedding-v4"))
                .bufferSize(1)
                .breakpointThreshold(0.5)
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
}
