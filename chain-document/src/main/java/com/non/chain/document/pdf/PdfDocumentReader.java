package com.non.chain.document.pdf;

import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.DocumentReader;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.ImageElement;
import com.non.chain.document.OcrEngine;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;
import com.non.chain.document.HeadingElement;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.contentstream.operator.state.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;

public class PdfDocumentReader implements DocumentReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    /**
     * 默认扫描件检测阈值：每页平均文本字符数低于此值视为扫描件
     */
    private static final int DEFAULT_SCAN_THRESHOLD = 50;

    private final OcrEngine ocrEngine;
    private final int scanThreshold;

    public PdfDocumentReader() {
        this(null);
    }

    public PdfDocumentReader(OcrEngine ocrEngine) {
        this(ocrEngine, DEFAULT_SCAN_THRESHOLD);
    }

    public PdfDocumentReader(OcrEngine ocrEngine, int scanThreshold) {
        this.ocrEngine = ocrEngine;
        this.scanThreshold = scanThreshold;
    }

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public ParsedDocument read(DocumentSource source) throws IOException {
        PDDocument document = PDDocument.load(source.inputStream());
        try {
            int pageCount = document.getNumberOfPages();
            List<DocumentElement> elements = new ArrayList<>();

            // 第一遍：正常提取文本和图片
            for (int i = 0; i < pageCount; i++) {
                int pageNumber = i + 1;
                PDPage page = document.getPage(i);

                // Extract text from this page
                extractTextElements(document, i + 1, elements);

                // Extract images from this page
                extractImageElements(page, pageNumber, elements);
            }

            // 检测是否为扫描件，如果是且有 OCR 引擎则重新提取
            boolean scanned = isScanned(elements, pageCount);
            if (scanned && ocrEngine != null) {
                elements = extractWithOcr(document, pageCount);
            }

            DocumentMetadata metadata = DocumentMetadata.builder()
                    .fileName(source.fileName())
                    .format("pdf")
                    .pageCount(pageCount)
                    .putAttribute("scanned", scanned)
                    .build();

            return ParsedDocument.builder(metadata)
                    .elements(elements)
                    .build();
        } finally {
            document.close();
        }
    }

    /**
     * 检测文档是否为扫描件。
     * 计算所有 TEXT 元素的总字符数，除以页数，低于阈值则为扫描件。
     */
    private boolean isScanned(List<DocumentElement> elements, int pageCount) {
        if (pageCount == 0) {
            return false;
        }
        long totalChars = elements.stream()
                .filter(e -> e instanceof TextElement || e instanceof HeadingElement)
                .mapToLong(e -> {
                    if (e instanceof TextElement) {
                        return ((TextElement) e).content().length();
                    }
                    return ((HeadingElement) e).content().length();
                })
                .sum();
        return totalChars / pageCount < scanThreshold;
    }

    /**
     * 使用 OCR 对每页进行文字提取。
     * 将每页渲染为 BufferedImage 后调用 OcrEngine 识别。
     */
    private List<DocumentElement> extractWithOcr(PDDocument document, int pageCount) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        List<DocumentElement> elements = new ArrayList<>();

        for (int i = 0; i < pageCount; i++) {
            int pageNumber = i + 1;
            BufferedImage pageImage = renderer.renderImageWithDPI(i, 300);
            String ocrText = ocrEngine.recognize(pageImage);

            if (ocrText != null && !ocrText.isBlank()) {
                String[] paragraphs = ocrText.split("\\n\\s*\\n");
                int lineNumber = 1;
                for (String paragraph : paragraphs) {
                    String trimmed = paragraph.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    DocumentPosition position = DocumentPosition.builder()
                            .pageNumber(pageNumber)
                            .lineNumber(lineNumber)
                            .build();
                    elements.add(TextElement.builder(trimmed)
                            .position(position)
                            .build());
                    lineNumber += paragraph.split("\\n").length + 1;
                }
            }
        }

        return elements;
    }

    private void extractTextElements(PDDocument document, int pageIndex, List<DocumentElement> elements)
            throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex);
        stripper.setEndPage(pageIndex);
        String pageText = stripper.getText(document);

        if (pageText == null || pageText.isBlank()) {
            return;
        }

        // Split by double newline to identify paragraphs
        String[] paragraphs = pageText.split("\\n\\s*\\n");
        int lineNumber = 1;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            DocumentPosition position = DocumentPosition.builder()
                    .pageNumber(pageIndex)
                    .lineNumber(lineNumber)
                    .build();

            elements.add(TextElement.builder(trimmed)
                    .position(position)
                    .build());

            lineNumber += paragraph.split("\\n").length + 1;
        }
    }

    private void extractImageElements(PDPage page, int pageNumber, List<DocumentElement> elements) {
        try {
            ImageExtractor extractor = new ImageExtractor(pageNumber, elements);
            extractor.processPage(page);
        } catch (IOException e) {
            // Skip image extraction on error, continue with other elements
        }
    }

    /**
     * PDFStreamEngine that extracts PDImageXObject instances from a page.
     */
    private static class ImageExtractor extends PDFStreamEngine {

        private final int pageNumber;
        private final List<DocumentElement> elements;
        private int imageIndex = 0;

        ImageExtractor(int pageNumber, List<DocumentElement> elements) {
            this.pageNumber = pageNumber;
            this.elements = elements;

            // Register required operators for rendering
            addOperator(new DrawObject());
            addOperator(new Concatenate());
            addOperator(new Save());
            addOperator(new Restore());
            addOperator(new SetMatrix());
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String operation = operator.getName();

            if (OperatorName.DRAW_OBJECT.equals(operation)) {
                COSName objectName = (COSName) operands.get(0);
                PDXObject xobject = getResources().getXObject(objectName);

                if (xobject instanceof PDImageXObject) {
                    PDImageXObject image = (PDImageXObject) xobject;
                    addImageElement(image);
                }
            } else {
                super.processOperator(operator, operands);
            }
        }

        private void addImageElement(PDImageXObject image) throws IOException {
            BufferedImage bufferedImage = image.getImage();
            byte[] pngData = toPngBytes(bufferedImage);
            if (pngData == null || pngData.length == 0) {
                return;
            }

            imageIndex++;

            DocumentPosition position = DocumentPosition.builder()
                    .pageNumber(pageNumber)
                    .build();

            Integer width = image.getWidth();
            Integer height = image.getHeight();

            String imageFileName = "page" + pageNumber + "_image" + imageIndex + ".png";

            elements.add(ImageElement.builder(pngData, "image/png")
                    .fileName(imageFileName)
                    .width(width)
                    .height(height)
                    .position(position)
                    .build());
        }

        private byte[] toPngBytes(BufferedImage image) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean written = ImageIO.write(image, "png", baos);
            if (!written) {
                return null;
            }
            return baos.toByteArray();
        }
    }
}
