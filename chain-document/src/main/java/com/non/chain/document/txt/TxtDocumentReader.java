package com.non.chain.document.txt;

import com.non.chain.document.DocumentMetadata;
import com.non.chain.document.DocumentPosition;
import com.non.chain.document.DocumentReader;
import com.non.chain.document.DocumentSource;
import com.non.chain.document.ParsedDocument;
import com.non.chain.document.TextElement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TxtDocumentReader implements DocumentReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "text");

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public ParsedDocument read(DocumentSource source) throws IOException {
        StringBuilder fullText = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(source.inputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fullText.append(line).append("\n");
            }
        }

        String text = fullText.toString();
        List<TextElement> elements = parseElements(text);

        DocumentMetadata metadata = DocumentMetadata.builder()
                .fileName(source.fileName())
                .format("txt")
                .build();

        return ParsedDocument.builder(metadata)
                .elements(new ArrayList<>(elements))
                .build();
    }

    private List<TextElement> parseElements(String text) {
        List<TextElement> elements = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");

        int lineNumber = 1;
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            elements.add(TextElement.builder(trimmed)
                    .position(DocumentPosition.builder()
                            .lineNumber(lineNumber)
                            .build())
                    .build());
            lineNumber += paragraph.split("\\n").length + 2;
        }

        return elements;
    }
}
