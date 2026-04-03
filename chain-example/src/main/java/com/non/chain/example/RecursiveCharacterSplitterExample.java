package com.non.chain.example;

import com.non.chain.document.*;
import com.non.chain.document.splitter.*;
import com.non.chain.knowledge.ContentMeasure;
import com.non.chain.knowledge.TextChunk;

import java.util.Arrays;
import java.util.List;

/**
 * 示例：递归字符切分器。
 * <p>
 * 演示 {@link RecursiveCharacterSplitter} 的三种用法：
 * <ol>
 *   <li>基础用法 — 按字符数切分纯文本</li>
 *   <li>原子元素保护 — 表格、代码块、图片保持完整</li>
 *   <li>Token 切分 — 配合 {@link TokenMeasure} 按_token_数切分</li>
 * </ol>
 */
public class RecursiveCharacterSplitterExample {

    public static void main(String[] args) {
        System.out.println("===== 1. 基础用法：按字符数切分 =====");
        basicSplit();

        System.out.println();
        System.out.println("===== 2. 原子元素保护 =====");
        atomicElementSplit();

        System.out.println();
        System.out.println("===== 3. 按 Token 数切分 =====");
        tokenSplit();
    }

    private static void basicSplit() {
        RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
                .chunkSize(50)
                .chunkOverlap(10)
                .build();

        String text = "非链库是一个轻量级Java LLM工具库。它提供了文档处理、向量化存储和检索等功能。" +
                "框架设计简洁，易于扩展。支持多种文档格式，包括PDF、Word、Markdown等。";

        List<TextChunk> chunks = splitter.split(text);

        System.out.println("原文长度: " + text.length() + " 字符");
        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        System.out.println();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.println("--- Chunk " + i + " (length=" + chunk.content().length() + ") ---");
            System.out.println(chunk.content());
        }
    }

    private static void atomicElementSplit() {
        ParsedDocument document = ParsedDocument.builder(
                DocumentMetadata.builder().fileName("demo.md").format("md").build())
                .addElement(TextElement.builder("这是一段介绍文字，用来演示递归字符切分器的基本功能。").build())
                .addElement(TableElement.builder()
                        .addHeader("功能")
                        .addHeader("状态")
                        .addRow(Arrays.asList("文档读取", "已完成"))
                        .addRow(Arrays.asList("文档清洗", "已完成"))
                        .addRow(Arrays.asList("文档切分", "开发中"))
                        .build())
                .addElement(CodeBlockElement.builder("System.out.println(\"Hello\");")
                        .language("java")
                        .build())
                .addElement(TextElement.builder("上面展示了表格和代码块作为原子元素不会被截断。").build())
                .build();

        RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
                .chunkSize(30)
                .chunkOverlap(0)
                .build();

        List<TextChunk> chunks = splitter.split(document);

        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        System.out.println();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d [type=%s] ---%n", i, chunk.elementType());
            if (chunk.elementType() == ElementType.TABLE || chunk.elementType() == ElementType.CODE_BLOCK) {
                System.out.println(chunk.content().substring(0, Math.min(80, chunk.content().length()))
                        + (chunk.content().length() > 80 ? "..." : ""));
            } else {
                System.out.println(chunk.content());
            }
            System.out.println("metadata: " + chunk.metadata());
        }
    }

    private static void tokenSplit() {
        ContentMeasure tokenMeasure = new TokenMeasure(
                com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);

        RecursiveCharacterSplitter splitter = RecursiveCharacterSplitter.builder()
                .chunkSize(50)
                .chunkOverlap(10)
                .contentMeasure(tokenMeasure)
                .build();

        String text = "非链库是一个轻量级Java LLM工具库。它提供了文档处理、向量化存储和检索等功能。" +
                "框架设计简洁，易于扩展。支持多种文档格式，包括PDF、Word、Markdown等。";

        List<TextChunk> chunks = splitter.split(text);

        System.out.println("按 50 token 切分，结果: " + chunks.size() + " 个 chunk");
        System.out.println();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            int tokens = tokenMeasure.measure(chunk.content());
            System.out.printf("--- Chunk %d (%d tokens, %d chars) ---%n",
                    i, tokens, chunk.content().length());
            System.out.println(chunk.content());
        }
    }
}
