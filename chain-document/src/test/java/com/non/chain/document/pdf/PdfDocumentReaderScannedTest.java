package com.non.chain.document.pdf;

import com.non.chain.document.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

public class PdfDocumentReaderScannedTest {

    private final DocumentMetadata dummyMetadata = DocumentMetadata.builder()
            .fileName("test.pdf")
            .format("pdf")
            .build();

    // ==================== 扫描件检测测试 ====================

    @Test
    public void normalPdf_notScanned() throws IOException {
        byte[] pdfBytes = createPdfWithText("This is a normal paragraph with enough text content to exceed the scan threshold.");
        PdfDocumentReader reader = new PdfDocumentReader();
        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        // 正常 PDF 不应被标记为扫描件
        assertFalse((Boolean) result.metadata().attributes().get("scanned"));
        assertFalse(result.elements().isEmpty());
    }

    @Test
    public void emptyPdf_detectedAsScanned() throws IOException {
        byte[] pdfBytes = createEmptyPdf(3);
        PdfDocumentReader reader = new PdfDocumentReader();
        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        // 无文本的 PDF 应被标记为扫描件
        assertTrue((Boolean) result.metadata().attributes().get("scanned"));
    }

    @Test
    public void scannedPdf_withoutOcrEngine_returnsSparseText() throws IOException {
        byte[] pdfBytes = createEmptyPdf(2);
        PdfDocumentReader reader = new PdfDocumentReader(); // 无 OCR 引擎
        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        // 检测到扫描件但没有 OCR 引擎，仍然返回原始（稀疏）内容
        assertTrue((Boolean) result.metadata().attributes().get("scanned"));
    }

    // ==================== OCR 集成测试 ====================

    @Test
    public void scannedPdf_withOcrEngine_usesOcr() throws IOException {
        byte[] pdfBytes = createEmptyPdf(2);
        StubOcrEngine ocrEngine = new StubOcrEngine("OCR extracted text for this page.\n\nSecond paragraph.");
        PdfDocumentReader reader = new PdfDocumentReader(ocrEngine);

        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        // OCR 应该被调用
        assertTrue(ocrEngine.callCount > 0);
        // OCR 提取的文本应该在结果中
        assertFalse(result.elements().isEmpty());
        boolean hasOcrText = result.elements().stream()
                .filter(e -> e instanceof TextElement)
                .map(e -> ((TextElement) e).content())
                .anyMatch(c -> c.contains("OCR extracted"));
        assertTrue(hasOcrText);
    }

    @Test
    public void normalPdf_withOcrEngine_doesNotUseOcr() throws IOException {
        byte[] pdfBytes = createPdfWithText("This is a normal paragraph with enough text content to exceed the scan threshold.");
        StubOcrEngine ocrEngine = new StubOcrEngine("Should not be used");
        PdfDocumentReader reader = new PdfDocumentReader(ocrEngine);

        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        // 正常 PDF 不应触发 OCR
        assertEquals(0, ocrEngine.callCount);
    }

    @Test
    public void customThreshold_controlsDetection() throws IOException {
        // 创建一个文本量较少但非空的 PDF
        byte[] pdfBytes = createPdfWithText("Hi");
        // 阈值设为 1，"Hi" = 2 chars / 1 page = 2 > 1，不算扫描件
        PdfDocumentReader reader1 = new PdfDocumentReader(null, 1);
        ParsedDocument result1 = reader1.read(DocumentSource.of(pdfBytes, "test.pdf"));
        assertFalse((Boolean) result1.metadata().attributes().get("scanned"));

        // 阈值设为 100，"Hi" = 2 chars / 1 page = 2 < 100，算扫描件
        PdfDocumentReader reader2 = new PdfDocumentReader(null, 100);
        ParsedDocument result2 = reader2.read(DocumentSource.of(pdfBytes, "test.pdf"));
        assertTrue((Boolean) result2.metadata().attributes().get("scanned"));
    }

    // ==================== 图片覆盖率检测测试 ====================

    @Test
    public void scannedPdf_imageCovered_detectedByCoverage() throws IOException {
        // 文字充足（80字符/页 > 50 阈值，文字密度维度不触发），但单页大面积图片覆盖 ~0.6
        // → 应由图片覆盖率维度判为扫描件
        byte[] pdfBytes = createPdfWithTextAndImage(
                "This is a normal paragraph with enough text content to exceed the scan threshold.", 0.6);
        PdfDocumentReader reader = new PdfDocumentReader();
        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        assertTrue((Boolean) result.metadata().attributes().get("scanned"));
    }

    @Test
    public void normalPdf_withSmallImage_notScanned() throws IOException {
        // 文字充足 + 小插图（覆盖 ~0.05 < 0.3 单页阈值）→ 不应判扫描件
        byte[] pdfBytes = createPdfWithTextAndImage(
                "This is a normal paragraph with enough text content to exceed the scan threshold.", 0.05);
        PdfDocumentReader reader = new PdfDocumentReader();
        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        assertFalse((Boolean) result.metadata().attributes().get("scanned"));
    }

    @Test
    public void highLargeImageThreshold_suppressesImageDetection() throws IOException {
        // 大图(覆盖0.6)但单页大图阈值设为0.7 → 该页不算图片重页面 → 不判扫描件（证明阈值可配）
        byte[] pdfBytes = createPdfWithTextAndImage(
                "This is a normal paragraph with enough text content to exceed the scan threshold.", 0.6);
        PdfDocumentReader reader = new PdfDocumentReader(null, 50, 300, 0.7, 0.5);
        ParsedDocument result = reader.read(DocumentSource.of(pdfBytes, "test.pdf"));

        assertFalse((Boolean) result.metadata().attributes().get("scanned"));
    }

    // ==================== 辅助方法 ====================

    private byte[] createPdfWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createEmptyPdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createPdfWithTextAndImage(String text, double coverage) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            // 生成纯色小图片，渲染尺寸由 coverage 控制
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 8, 8);
            g.dispose();
            ByteArrayOutputStream imgBytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", imgBytes);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, imgBytes.toByteArray(), "img");

            float pageW = page.getMediaBox().getWidth();
            float pageH = page.getMediaBox().getHeight();
            float drawW = (float) (pageW * Math.sqrt(coverage));
            float drawH = (float) (pageH * Math.sqrt(coverage));
            float x = (pageW - drawW) / 2;
            float y = (pageH - drawH) / 2;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, x, y, drawW, drawH);
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 50);
                cs.showText(text);
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * 测试用 OCR 引擎桩实现
     */
    private static class StubOcrEngine implements OcrEngine {
        private final String fixedResult;
        int callCount = 0;

        StubOcrEngine(String fixedResult) {
            this.fixedResult = fixedResult;
        }

        @Override
        public String recognize(BufferedImage image) {
            callCount++;
            return fixedResult;
        }
    }
}
