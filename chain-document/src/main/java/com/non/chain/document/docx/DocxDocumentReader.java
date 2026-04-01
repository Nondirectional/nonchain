package com.non.chain.document.docx;

import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.DocumentReader;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ImageElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TableElement;
import com.non.chain.document.TextElement;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DocxDocumentReader implements DocumentReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("docx");

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public ParsedDocument read(DocumentSource source) throws IOException {
        List<DocumentElement> elements = new ArrayList<>();

        XWPFDocument document = new XWPFDocument(source.inputStream());
        try {
            List<IBodyElement> bodyElements = document.getBodyElements();
            int elementIndex = 0;

            for (IBodyElement bodyElement : bodyElements) {
                if (bodyElement instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) bodyElement;
                    DocumentElement element = parseParagraph(paragraph, elementIndex);
                    if (element != null) {
                        elements.add(element);
                    }
                    elementIndex++;
                } else if (bodyElement instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) bodyElement;
                    TableElement element = parseTable(table, elementIndex);
                    if (element != null) {
                        elements.add(element);
                    }
                    elementIndex++;
                }
            }

            // 提取文档中的嵌入图片
            List<XWPFPictureData> pictures = document.getAllPictures();
            for (int i = 0; i < pictures.size(); i++) {
                XWPFPictureData picture = pictures.get(i);
                ImageElement imageElement = parsePicture(picture, elementIndex + i);
                if (imageElement != null) {
                    elements.add(imageElement);
                }
            }
        } finally {
            document.close();
        }

        DocumentMetadata metadata = DocumentMetadata.builder()
                .fileName(source.fileName())
                .format("docx")
                .build();

        return ParsedDocument.builder(metadata)
                .elements(elements)
                .build();
    }

    private DocumentElement parseParagraph(XWPFParagraph paragraph, int elementIndex) {
        String text = paragraph.getText();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        DocumentPosition position = DocumentPosition.builder()
                .lineNumber(elementIndex + 1)
                .build();

        int headingLevel = detectHeadingLevel(paragraph);
        if (headingLevel > 0) {
            return HeadingElement.builder(headingLevel, text.trim())
                    .position(position)
                    .build();
        }

        return TextElement.builder(text.trim())
                .position(position)
                .build();
    }

    private int detectHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return 0;
        }

        String normalizedStyle = style.trim();

        // 匹配 "Heading1" ~ "Heading9"（含大小写变体，如 "heading 1"）
        if (normalizedStyle.toLowerCase().startsWith("heading")) {
            String suffix = normalizedStyle.substring("heading".length()).trim();
            return parseLevel(suffix);
        }

        // 某些文档直接使用 "1" ~ "9" 作为样式名
        return parseLevel(normalizedStyle);
    }

    private int parseLevel(String suffix) {
        try {
            int level = Integer.parseInt(suffix);
            if (level >= 1 && level <= 6) {
                return level;
            }
        } catch (NumberFormatException e) {
            // 不是数字，忽略
        }
        return 0;
    }

    private TableElement parseTable(XWPFTable table, int elementIndex) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return null;
        }

        DocumentPosition position = DocumentPosition.builder()
                .lineNumber(elementIndex + 1)
                .build();

        TableElement.Builder builder = TableElement.builder()
                .position(position);

        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<String> cellTexts = extractRowTexts(row);

            if (i == 0) {
                // 第一行作为表头
                for (String header : cellTexts) {
                    builder.addHeader(header);
                }
            } else {
                builder.addRow(cellTexts);
            }
        }

        return builder.build();
    }

    private List<String> extractRowTexts(XWPFTableRow row) {
        List<String> texts = new ArrayList<>();
        for (XWPFTableCell cell : row.getTableCells()) {
            String cellText = cell.getText();
            texts.add(cellText != null ? cellText.trim() : "");
        }
        return texts;
    }

    private ImageElement parsePicture(XWPFPictureData picture, int elementIndex) {
        byte[] data = picture.getData();
        if (data == null || data.length == 0) {
            return null;
        }

        String extension = picture.suggestFileExtension();
        String mimeType = mapExtensionToMimeType(extension);

        String pictureFileName = picture.getFileName();

        DocumentPosition position = DocumentPosition.builder()
                .lineNumber(elementIndex + 1)
                .build();

        return ImageElement.builder(data, mimeType)
                .fileName(pictureFileName)
                .position(position)
                .putMetadata("elementIndex", elementIndex)
                .build();
    }

    private String mapExtensionToMimeType(String extension) {
        if (extension == null) {
            return "application/octet-stream";
        }
        switch (extension.toLowerCase()) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "tiff":
            case "tif":
                return "image/tiff";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            default:
                return "application/octet-stream";
        }
    }
}
