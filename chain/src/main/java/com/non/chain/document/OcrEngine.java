package com.non.chain.document;

import java.awt.image.BufferedImage;

/**
 * OCR 引擎标准接口。
 * <p>
 * 将图片识别为文本，可由不同实现提供：
 * <ul>
 *   <li>Tesseract（本地原生 OCR）</li>
 *   <li>多模态大模型（如 GPT-4V、Claude 等）</li>
 *   <li>专用 OCR 识别模型（如 PaddleOCR）</li>
 * </ul>
 * <p>
 * 实现类注入到 {@link DocumentReader}（如 PdfDocumentReader）中，
 * 在 Reader 阶段完成扫描件的文字提取。
 */
public interface OcrEngine {

    /**
     * 对图片执行 OCR 识别，返回提取的文本内容。
     *
     * @param image 待识别的图片，不为 null
     * @return 识别出的文本内容，如果无法识别则返回空字符串
     */
    String recognize(BufferedImage image);
}
