package com.non.chain.knowledge;

import java.util.List;

public interface KnowledgeStore {

    String add(DocumentChunk chunk);

    List<String> addAll(List<DocumentChunk> chunks);

    List<SearchResult> search(SearchRequest request);

    void delete(String chunkId);

    void deleteAll(List<String> chunkIds);

    void deleteByDocumentId(String documentId);
}
