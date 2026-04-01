package com.non.chain.document.markdown;

import com.non.chain.document.CodeBlockElement;
import com.non.chain.document.DocumentElement;
import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.DocumentReader;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.HeadingElement;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

public class MarkdownDocumentReader implements DocumentReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown");

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public ParsedDocument read(DocumentSource source) throws IOException {
        String content = readContent(source);
        Parser parser = Parser.builder().build();
        Node document = parser.parse(content);

        List<DocumentElement> elements = new ArrayList<>();
        int lineNumber = 1;

        Node node = document.getFirstChild();
        while (node != null) {
            DocumentElement element = convertNode(node, lineNumber);
            if (element != null) {
                elements.add(element);
            }
            lineNumber += countLines(node);
            node = node.getNext();
        }

        DocumentMetadata metadata = DocumentMetadata.builder()
                .fileName(source.fileName())
                .format("markdown")
                .build();

        return ParsedDocument.builder(metadata)
                .elements(elements)
                .build();
    }

    private DocumentElement convertNode(Node node, int lineNumber) {
        DocumentPosition position = DocumentPosition.builder()
                .lineNumber(lineNumber)
                .build();

        if (node instanceof Heading) {
            Heading heading = (Heading) node;
            String text = extractText(heading);
            if (text.isEmpty()) {
                return null;
            }
            return HeadingElement.builder(heading.getLevel(), text)
                    .position(position)
                    .build();
        }

        if (node instanceof FencedCodeBlock) {
            FencedCodeBlock codeBlock = (FencedCodeBlock) node;
            String content = codeBlock.getLiteral();
            if (content == null || content.isEmpty()) {
                return null;
            }
            return CodeBlockElement.builder(content.trim())
                    .language(codeBlock.getInfo())
                    .position(position)
                    .build();
        }

        if (node instanceof Paragraph) {
            String text = extractText(node);
            if (text.isEmpty()) {
                return null;
            }
            return TextElement.builder(text)
                    .position(position)
                    .build();
        }

        return null;
    }

    private String extractText(Node node) {
        StringBuilder sb = new StringBuilder();
        extractTextRecursive(node, sb);
        return sb.toString().trim();
    }

    private void extractTextRecursive(Node node, StringBuilder sb) {
        if (node instanceof Text) {
            sb.append(((Text) node).getLiteral());
        } else if (node instanceof Code) {
            sb.append(((Code) node).getLiteral());
        } else {
            Node child = node.getFirstChild();
            while (child != null) {
                extractTextRecursive(child, sb);
                child = child.getNext();
            }
        }
    }

    private int countLines(Node node) {
        if (node instanceof FencedCodeBlock) {
            String literal = ((FencedCodeBlock) node).getLiteral();
            return literal != null ? literal.split("\\n").length + 2 : 1;
        }
        if (node instanceof Heading) {
            return 1;
        }
        // Paragraph and other blocks: estimate by source spans or default
        return 1;
    }

    private String readContent(DocumentSource source) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(source.inputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
