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
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;

public class PdfDocumentReader implements DocumentReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    /**
     * 默认扫描件检测阈值：每页平均文本字符数低于此值视为扫描件
     */
    private static final int DEFAULT_SCAN_THRESHOLD = 50;
    /**
     * 默认 OCR 渲染 DPI：扫描件每页渲染为图片的分辨率
     */
    private static final int DEFAULT_RENDER_DPI = 300;
    /**
     * 默认单页大图覆盖率阈值：该页任一图片渲染面积占页面面积比例达到此值视为"图片重页面"
     */
    private static final double DEFAULT_LARGE_IMAGE_PAGE_THRESHOLD = 0.3;
    /**
     * 默认文档级图片覆盖率阈值：图片重页面占总页数比例达到此值视为扫描件
     */
    private static final double DEFAULT_IMAGE_COVERAGE_THRESHOLD = 0.5;

    private final OcrEngine ocrEngine;
    private final int scanThreshold;
    private final int renderDpi;
    private final double largeImagePageThreshold;
    private final double imageCoverageThreshold;

    public PdfDocumentReader() {
        this(null);
    }

    public PdfDocumentReader(OcrEngine ocrEngine) {
        this(ocrEngine, DEFAULT_SCAN_THRESHOLD);
    }

    public PdfDocumentReader(OcrEngine ocrEngine, int scanThreshold) {
        this(ocrEngine, scanThreshold, DEFAULT_RENDER_DPI);
    }

    /**
     * @param renderDpi 扫描件 OCR 时每页渲染为图片的 DPI，必须为正数
     */
    public PdfDocumentReader(OcrEngine ocrEngine, int scanThreshold, int renderDpi) {
        this(ocrEngine, scanThreshold, renderDpi,
                DEFAULT_LARGE_IMAGE_PAGE_THRESHOLD, DEFAULT_IMAGE_COVERAGE_THRESHOLD);
    }

    /**
     * @param largeImagePageThreshold  单页大图覆盖率阈值（0-1）：该页任一图片渲染面积占页面面积比例达到此值视为"图片重页面"
     * @param imageCoverageThreshold   文档级图片覆盖率阈值（0-1）：图片重页面占总页数比例达到此值视为扫描件
     */
    public PdfDocumentReader(OcrEngine ocrEngine, int scanThreshold,
                             double largeImagePageThreshold, double imageCoverageThreshold) {
        this(ocrEngine, scanThreshold, DEFAULT_RENDER_DPI, largeImagePageThreshold, imageCoverageThreshold);
    }

    /**
     * @param renderDpi                扫描件 OCR 时每页渲染为图片的 DPI，必须为正数
     * @param largeImagePageThreshold  单页大图覆盖率阈值（0-1）：该页任一图片渲染面积占页面面积比例达到此值视为"图片重页面"
     * @param imageCoverageThreshold   文档级图片覆盖率阈值（0-1）：图片重页面占总页数比例达到此值视为扫描件
     */
    public PdfDocumentReader(OcrEngine ocrEngine, int scanThreshold, int renderDpi,
                             double largeImagePageThreshold, double imageCoverageThreshold) {
        if (renderDpi <= 0) {
            throw new IllegalArgumentException("渲染 DPI 必须为正数: " + renderDpi);
        }
        if (largeImagePageThreshold < 0 || largeImagePageThreshold > 1) {
            throw new IllegalArgumentException("单页大图覆盖率阈值必须在 0-1 之间: " + largeImagePageThreshold);
        }
        if (imageCoverageThreshold < 0 || imageCoverageThreshold > 1) {
            throw new IllegalArgumentException("文档级图片覆盖率阈值必须在 0-1 之间: " + imageCoverageThreshold);
        }
        this.ocrEngine = ocrEngine;
        this.scanThreshold = scanThreshold;
        this.renderDpi = renderDpi;
        this.largeImagePageThreshold = largeImagePageThreshold;
        this.imageCoverageThreshold = imageCoverageThreshold;
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
            int imageHeavyPages = 0;

            // 第一遍：正常提取文本和图片
            for (int i = 0; i < pageCount; i++) {
                int pageNumber = i + 1;
                PDPage page = document.getPage(i);

                // Extract text from this page
                extractTextElements(document, i + 1, elements);

                // Extract images from this page，并统计该页是否为图片重页面
                if (extractImageElements(page, pageNumber, elements)) {
                    imageHeavyPages++;
                }
            }

            // 检测是否为扫描件，如果是且有 OCR 引擎则重新提取
            boolean scanned = isScanned(elements, pageCount, imageHeavyPages);
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
     * <p>
     * 两个维度（OR 关系）：
     * <ol>
     *   <li>文字密度低：TEXT/HEADING 元素总字符数 / 页数 &lt; scanThreshold</li>
     *   <li>图片覆盖率达标：图片重页面数 / 总页数 &gt;= imageCoverageThreshold（应对带 OCR 文字层的扫描件）</li>
     * </ol>
     */
    private boolean isScanned(List<DocumentElement> elements, int pageCount, int imageHeavyPages) {
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
        // 维度1：文字密度低 → 直接判扫描件
        if (totalChars / pageCount < scanThreshold) {
            return true;
        }
        // 维度2：图片覆盖率达标 → 应对带 OCR 文字层的扫描件
        double imagePageRatio = (double) imageHeavyPages / pageCount;
        return imagePageRatio >= imageCoverageThreshold;
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
            BufferedImage pageImage = renderer.renderImageWithDPI(i, renderDpi);
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

    private boolean extractImageElements(PDPage page, int pageNumber, List<DocumentElement> elements) {
        try {
            ImageExtractor extractor = new ImageExtractor(pageNumber, elements, page, largeImagePageThreshold);
            extractor.processPage(page);
            return extractor.isImageHeavyPage();
        } catch (IOException e) {
            // Skip image extraction on error, continue with other elements
            return false;
        }
    }

    /**
     * PDFStreamEngine that extracts PDImageXObject instances from a page.
     */
    private static class ImageExtractor extends PDFStreamEngine {

        private final int pageNumber;
        private final List<DocumentElement> elements;
        private final double largeImagePageThreshold;
        private final double pageArea;
        private int imageIndex = 0;
        private boolean largeImage = false;

        ImageExtractor(int pageNumber, List<DocumentElement> elements, PDPage page,
                       double largeImagePageThreshold) {
            this.pageNumber = pageNumber;
            this.elements = elements;
            this.largeImagePageThreshold = largeImagePageThreshold;
            PDRectangle pageRect = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            this.pageArea = pageRect != null ? pageRect.getWidth() * pageRect.getHeight() : 0.0;

            // Register required operators for rendering
            addOperator(new DrawObject());
            addOperator(new Concatenate());
            addOperator(new Save());
            addOperator(new Restore());
            addOperator(new SetMatrix());
        }

        /**
         * 该页是否存在渲染面积占比超阈值的图片（图片重页面）。
         */
        boolean isImageHeavyPage() {
            return largeImage;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String operation = operator.getName();

            if (OperatorName.DRAW_OBJECT.equals(operation)) {
                COSName objectName = (COSName) operands.get(0);
                PDXObject xobject = getResources().getXObject(objectName);

                if (xobject instanceof PDImageXObject) {
                    PDImageXObject image = (PDImageXObject) xobject;
                    markLargeImageIfNeeded();
                    addImageElement(image);
                }
            } else {
                super.processOperator(operator, operands);
            }
        }

        /**
         * 用当前图形状态的 CTM 计算图片渲染面积占页面面积的比例，
         * 超过阈值则标记该页为图片重页面。面积使用 CTM 行列式绝对值，正确处理旋转/剪切。
         */
        private void markLargeImageIfNeeded() {
            if (largeImage || pageArea <= 0.0) {
                return;
            }
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            double imageArea = Math.abs(
                    ctm.getScaleX() * ctm.getScaleY() - ctm.getShearX() * ctm.getShearY());
            double coverageRatio = imageArea / pageArea;
            if (coverageRatio >= largeImagePageThreshold) {
                largeImage = true;
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
