package com.non.chain.document.html;

import com.non.chain.document.CodeBlockElement;
import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.DocumentReader;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TableElement;
import com.non.chain.document.TextElement;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class HtmlDocumentReader implements DocumentReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("html", "htm");

    private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public ParsedDocument read(DocumentSource source) throws IOException {
        Document doc;
        try (InputStream is = source.inputStream()) {
            doc = Jsoup.parse(is, "UTF-8", "");
        }

        List<DocumentElement> elements = new ArrayList<>();
        Element body = doc.body();
        if (body != null) {
            processNode(body, elements, 1);
        }

        DocumentMetadata metadata = DocumentMetadata.builder()
                .fileName(source.fileName())
                .format("html")
                .build();

        return ParsedDocument.builder(metadata)
                .elements(elements)
                .build();
    }

    private void processNode(Element element, List<DocumentElement> elements, int lineNumber) {
        for (Node child : element.childNodes()) {
            if (!(child instanceof Element)) {
                if (child instanceof TextNode) {
                    String text = ((TextNode) child).text().trim();
                    if (!text.isEmpty()) {
                        elements.add(TextElement.builder(text)
                                .position(DocumentPosition.builder()
                                        .lineNumber(lineNumber)
                                        .build())
                                .build());
                    }
                }
                continue;
            }

            Element el = (Element) child;
            String tagName = el.tagName().toLowerCase();

            if (HEADING_TAGS.contains(tagName)) {
                String text = el.text().trim();
                if (!text.isEmpty()) {
                    int level = Integer.parseInt(tagName.substring(1));
                    elements.add(HeadingElement.builder(level, text)
                            .position(DocumentPosition.builder()
                                    .lineNumber(lineNumber)
                                    .build())
                            .build());
                }
            } else if ("table".equals(tagName)) {
                TableElement table = parseTable(el, lineNumber);
                if (table != null) {
                    elements.add(table);
                }
            } else if ("pre".equals(tagName)) {
                Element codeEl = el.selectFirst("code");
                String content = codeEl != null ? codeEl.text() : el.text();
                if (content != null && !content.trim().isEmpty()) {
                    String language = null;
                    if (codeEl != null) {
                        for (String cls : codeEl.classNames()) {
                            if (cls.startsWith("language-")) {
                                language = cls.substring(9);
                                break;
                            }
                        }
                    }
                    elements.add(CodeBlockElement.builder(content.trim())
                            .language(language)
                            .position(DocumentPosition.builder()
                                    .lineNumber(lineNumber)
                                    .build())
                            .build());
                }
            } else if ("p".equals(tagName) || "div".equals(tagName) || "span".equals(tagName)
                    || "li".equals(tagName) || "td".equals(tagName) || "th".equals(tagName)) {
                // Skip, handled by parent context
            } else {
                // Recurse into other elements
                processNode(el, elements, lineNumber);
            }
        }
    }

    private TableElement parseTable(Element tableEl, int lineNumber) {
        Elements rows = tableEl.select("tr");
        if (rows.isEmpty()) {
            return null;
        }

        TableElement.Builder builder = TableElement.builder()
                .position(DocumentPosition.builder()
                        .lineNumber(lineNumber)
                        .build());

        boolean firstRow = true;
        for (Element row : rows) {
            Elements cells = row.select("th, td");
            List<String> cellTexts = new ArrayList<>();
            for (Element cell : cells) {
                cellTexts.add(cell.text().trim());
            }

            if (firstRow && !row.select("th").isEmpty()) {
                builder.headers(cellTexts);
                firstRow = false;
            } else {
                if (!cellTexts.isEmpty()) {
                    builder.addRow(cellTexts);
                }
                firstRow = false;
            }
        }

        return builder.build();
    }
}
