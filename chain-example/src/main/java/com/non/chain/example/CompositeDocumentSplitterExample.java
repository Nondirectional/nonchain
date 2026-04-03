package com.non.chain.example;

import com.non.chain.document.*;
import com.non.chain.document.splitter.*;
import com.non.chain.knowledge.TextChunk;

import java.util.Arrays;
import java.util.List;

/**
 * 示例：组合切分器。
 * <p>
 * 演示 {@link CompositeDocumentSplitter} 的用法：
 * 先用 {@link HeaderDocumentSplitter} 按标题拆章节，再用 {@link RecursiveCharacterSplitter} 对每个章节细分。
 * <p>
 * 组合切分的好处：
 * <ul>
 *   <li>一级切分保留文档结构（标题路径）</li>
 *   <li>二级切分控制 chunk 粒度（适合 embedding）</li>
 *   <li>原子元素直接透传，不参与二次切分</li>
 * </ul>
 */
public class CompositeDocumentSplitterExample {

    public static void main(String[] args) {
        ParsedDocument document = ParsedDocument.builder(
                DocumentMetadata.builder().fileName("article.md").format("md").build())
                .addElement(HeadingElement.builder(1, "机器学习入门").build())
                .addElement(TextElement.builder("机器学习是人工智能的一个分支，它使计算机能够从数据中学习。" +
                        "常见的算法包括线性回归、决策树、神经网络等。" +
                        "深度学习是机器学习的子集，使用多层神经网络处理复杂模式。").build())
                .addElement(HeadingElement.builder(2, "监督学习").build())
                .addElement(TextElement.builder("监督学习使用标注数据训练模型。" +
                        "分类和回归是两种主要任务。" +
                        "常见的评估指标包括准确率、精确率、召回率等。").build())
                .addElement(TableElement.builder()
                        .addHeader("算法")
                        .addHeader("类型")
                        .addRow(Arrays.asList("SVM", "分类"))
                        .addRow(Arrays.asList("随机森林", "分类/回归"))
                        .build())
                .addElement(HeadingElement.builder(2, "无监督学习").build())
                .addElement(TextElement.builder("无监督学习不需要标注数据，主要任务包括聚类和降维。" +
                        "K-Means 和 DBSCAN 是常用的聚类算法。" +
                        "PCA 和 t-SNE 是常用的降维方法。").build())
                .build();

        // 一级切分：按 H1/H2 拆章节
        HeaderDocumentSplitter primary = new HeaderDocumentSplitter(List.of(1, 2), true);

        // 二级切分：按 30 字符细分
        RecursiveCharacterSplitter secondary = RecursiveCharacterSplitter.builder()
                .chunkSize(30)
                .chunkOverlap(0)
                .separators(List.of("。", "，", "", ""))
                .build();

        CompositeDocumentSplitter splitter = new CompositeDocumentSplitter(primary, secondary);
        List<TextChunk> chunks = splitter.split(document);

        System.out.println("组合切分结果: " + chunks.size() + " 个 chunk");
        System.out.println();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            System.out.printf("--- Chunk %d [type=%s] ---%n", i, chunk.elementType());
            System.out.println("  headingPath: " + chunk.metadata().get("headingPath"));
            String preview = chunk.content().substring(0, Math.min(50, chunk.content().length()));
            System.out.println("  content: " + preview + (chunk.content().length() > 50 ? "..." : ""));
        }

        // 对比：仅用 HeaderDocumentSplitter 的结果
        System.out.println();
        System.out.println("===== 对比：仅一级切分 =====");
        List<TextChunk> headerOnly = primary.split(document);
        System.out.println("仅一级切分: " + headerOnly.size() + " 个 chunk");
        for (int i = 0; i < headerOnly.size(); i++) {
            TextChunk chunk = headerOnly.get(i);
            System.out.printf("--- Chunk %d [type=%s] (length=%d) ---%n",
                    i, chunk.elementType(), chunk.content().length());
        }
    }
}
