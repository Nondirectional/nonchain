package com.non.chain.document.pdf;

import com.non.chain.document.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.Test;

import java.awt.image.BufferedImage;
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
