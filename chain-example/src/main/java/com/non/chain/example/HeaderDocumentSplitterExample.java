package com.non.chain.example;

import com.non.chain.document.*;
import com.non.chain.document.splitter.HeaderDocumentSplitter;
import com.non.chain.knowledge.TextChunk;

import java.util.Arrays;
import java.util.List;

/**
 * 示例：标题/结构层级切分器。
 * <p>
 * 演示 {@link HeaderDocumentSplitter} 的两种用法：
 * <ol>
 *   <li>基础用法 — 按 H1/H2 标题层级切分，自动维护标题路径</li>
 *   <li>原子元素 — 表格等在标题切分下独立输出并携带章节信息</li>
 * </ol>
 */
public class HeaderDocumentSplitterExample {

    public static void main(String[] args) {
        System.out.println("===== 1. 基础用法：按标题层级切分 =====");
        basicSplit();

        System.out.println();
        System.out.println("===== 2. 原子元素携带章节信息 =====");
        atomicElementSplit();
    }

    private static void basicSplit() {
        ParsedDocument document = ParsedDocument.builder(
                DocumentMetadata.builder().fileName("guide.md").format("md").build())
                // 前言（无标题）
                .addElement(TextElement.builder("这是一篇关于非链库的介绍文档。").build())
                // 第一章
                .addElement(HeadingElement.builder(1, "快速开始").build())
                .addElement(TextElement.builder("首先安装依赖，然后创建一个简单的示例程序。").build())
                .addElement(TextElement.builder("框架支持多种文档格式，配置灵活。").build())
                // 1.1
                .addElement(HeadingElement.builder(2, "环境要求").build())
                .addElement(TextElement.builder("Java 11 及以上版本，Maven 3.6+。").build())
                // 第二章
                .addElement(HeadingElement.builder(1, "核心概念").build())
                .addElement(TextElement.builder("非链库的核心包括文档处理、向量化、检索三大模块。").build())
                .build();

        HeaderDocumentSplitter splitter = new HeaderDocumentSplitter(List.of(1, 2), true);
        List<TextChunk> chunks = splitter.split(document);

        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        System.out.println();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d ---%n", i);
            System.out.println("  heading: " + chunk.metadata().get("heading"));
            System.out.println("  headingLevel: " + chunk.metadata().get("headingLevel"));
            System.out.println("  headingPath: " + chunk.metadata().get("headingPath"));
            System.out.println("  content: " + chunk.content());
        }
    }

    private static void atomicElementSplit() {
        ParsedDocument document = ParsedDocument.builder(
                DocumentMetadata.builder().fileName("report.md").format("md").build())
                .addElement(HeadingElement.builder(1, "年度报告").build())
                .addElement(TextElement.builder("以下是本年度的关键数据。").build())
                .addElement(HeadingElement.builder(2, "销售数据").build())
                .addElement(TableElement.builder()
                        .addHeader("季度")
                        .addHeader("销售额")
                        .addRow(Arrays.asList("Q1", "120万"))
                        .addRow(Arrays.asList("Q2", "150万"))
                        .build())
                .addElement(HeadingElement.builder(2, "技术指标").build())
                .addElement(CodeBlockElement.builder("response_time = 42ms\nthroughput = 1000qps")
                        .language("yaml")
                        .build())
                .addElement(TextElement.builder("系统整体表现良好。").build())
                .build();

        HeaderDocumentSplitter splitter = new HeaderDocumentSplitter(List.of(1, 2), true);
        List<TextChunk> chunks = splitter.split(document);

        System.out.println("切分结果: " + chunks.size() + " 个 chunk");
        System.out.println();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d [type=%s] ---%n", i, chunk.elementType());
            System.out.println("  headingPath: " + chunk.metadata().get("headingPath"));
            String preview = chunk.content().substring(0, Math.min(60, chunk.content().length()));
            System.out.println("  content: " + preview + (chunk.content().length() > 60 ? "..." : ""));
        }
    }
}
