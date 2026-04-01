package com.non.chain.document;

import java.io.IOException;

public interface DocumentReader {

    boolean supports(String extension);

    ParsedDocument read(DocumentSource source) throws IOException;
}
