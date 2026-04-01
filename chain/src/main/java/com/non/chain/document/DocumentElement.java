package com.non.chain.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class DocumentElement {

    private final ElementType elementType;
    private final DocumentPosition position;
    private final Map<String, Object> metadata;

    protected DocumentElement(ElementType elementType, DocumentPosition position, Map<String, Object> metadata) {
        this.elementType = elementType;
        this.position = position;
        this.metadata = metadata;
    }

    public ElementType elementType() {
        return elementType;
    }

    public DocumentPosition position() {
        return position;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    protected static Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
