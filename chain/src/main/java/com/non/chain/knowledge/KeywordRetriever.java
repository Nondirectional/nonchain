package com.non.chain.knowledge;

import java.util.List;

public interface KeywordRetriever {

    List<SearchResult> search(String queryText, int topK);
}
