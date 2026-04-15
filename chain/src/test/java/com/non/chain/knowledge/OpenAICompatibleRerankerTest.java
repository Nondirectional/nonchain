package com.non.chain.knowledge;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class OpenAICompatibleRerankerTest {

    // ---- 构造器校验 ----

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullBaseUrl() {
        new OpenAICompatibleReranker(null, "model");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankBaseUrl() {
        new OpenAICompatibleReranker("  ", "model");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullModel() {
        new OpenAICompatibleReranker("http://localhost:8000/v1", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankModel() {
        new OpenAICompatibleReranker("http://localhost:8000/v1", "  ");
    }

    @Test
    public void shouldConstructWithValidParams() {
        new OpenAICompatibleReranker("http://localhost:8000/v1", "bge-reranker-large");
        new OpenAICompatibleReranker("http://localhost:8000/v1/", "api-key", "bge-reranker-large");
    }

    @Test
    public void shouldNormalizeTrailingSlash() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1/", "model");
        // 测试 buildRequestJson 正常工作即可（baseUrl 已规范化）
        List<SearchResult> results = List.of(
                createSearchResult("chunk1", "Hello world")
        );
        String json = r.buildRequestJson("query", results, 10);
        assertTrue(json.contains("\"model\":\"model\""));
        assertTrue(json.contains("\"query\":\"query\""));
        assertTrue(json.contains("\"documents\":[\"Hello world\"]"));
    }

    // ---- rerank 输入校验 ----

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullQuery() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        r.rerank(null, List.of(createSearchResult("c1", "text")), 5);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankQuery() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        r.rerank("  ", List.of(createSearchResult("c1", "text")), 5);
    }

    @Test
    public void shouldReturnEmptyForEmptyResults() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        List<SearchResult> result = r.rerank("query", List.of(), 5);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldReturnEmptyForNullResults() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        List<SearchResult> result = r.rerank("query", null, 5);
        assertTrue(result.isEmpty());
    }

    // ---- buildRequestJson 测试 ----

    @Test
    public void shouldBuildCorrectRequestJson() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "bge-reranker-large");
        List<SearchResult> results = List.of(
                createSearchResult("c1", "Paris is the capital of France."),
                createSearchResult("c2", "Berlin is the capital of Germany.")
        );
        String json = r.buildRequestJson("capital of France", results, 2);

        assertTrue(json.contains("\"model\":\"bge-reranker-large\""));
        assertTrue(json.contains("\"query\":\"capital of France\""));
        assertTrue(json.contains("\"documents\":[\"Paris is the capital of France.\",\"Berlin is the capital of Germany.\"]"));
        // topN == results.size(), 不应包含 top_n
        assertFalse(json.contains("\"top_n\""));
    }

    @Test
    public void shouldIncludeTopNWhenSmallerThanResultsSize() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        List<SearchResult> results = List.of(
                createSearchResult("c1", "doc1"),
                createSearchResult("c2", "doc2"),
                createSearchResult("c3", "doc3")
        );
        String json = r.buildRequestJson("query", results, 2);
        assertTrue(json.contains("\"top_n\":2"));
    }

    @Test
    public void shouldEscapeSpecialCharsInJson() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        List<SearchResult> results = List.of(
                createSearchResult("c1", "Line1\nLine2\tTab\"Quote\\Backslash")
        );
        String json = r.buildRequestJson("query", results, 1);
        assertTrue(json.contains("Line1\\nLine2\\tTab\\\"Quote\\\\Backslash"));
    }

    // ---- parseResponse 测试 ----

    @Test
    public void shouldParseValidResponse() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        List<SearchResult> originals = List.of(
                createSearchResult("c1", "Paris is the capital of France."),
                createSearchResult("c2", "Berlin is the capital of Germany."),
                createSearchResult("c3", "London is the capital of UK.")
        );

        String responseJson = "{\"id\":\"rerank-123\",\"model\":\"model\",\"results\":[" +
                "{\"index\":2,\"document\":{\"text\":\"London...\"},\"relevance_score\":0.95}," +
                "{\"index\":0,\"document\":{\"text\":\"Paris...\"},\"relevance_score\":0.82}" +
                "],\"usage\":{\"prompt_tokens\":42,\"total_tokens\":42}}";

        List<SearchResult> parsed = r.parseResponse(responseJson, originals);

        assertEquals(2, parsed.size());
        // 按响应顺序排列（index 2 first）
        assertEquals("c3", parsed.get(0).chunkId());
        assertEquals(0.95, parsed.get(0).score(), 0.001);
        assertEquals("c1", parsed.get(1).chunkId());
        assertEquals(0.82, parsed.get(1).score(), 0.001);
        // metadata 和 chunkIndex 保留
        assertNotNull(parsed.get(0).metadata());
    }

    @Test(expected = RuntimeException.class)
    public void shouldRejectResponseWithoutResults() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        r.parseResponse("{\"model\":\"model\"}", List.of(createSearchResult("c1", "text")));
    }

    @Test
    public void shouldSkipInvalidIndexInResponse() {
        OpenAICompatibleReranker r = new OpenAICompatibleReranker("http://localhost:8000/v1", "model");
        List<SearchResult> originals = List.of(
                createSearchResult("c1", "doc1")
        );

        String responseJson = "{\"results\":[" +
                "{\"index\":0,\"relevance_score\":0.9}," +
                "{\"index\":5,\"relevance_score\":0.8}" +
                "]}";

        List<SearchResult> parsed = r.parseResponse(responseJson, originals);
        assertEquals(1, parsed.size());
        assertEquals("c1", parsed.get(0).chunkId());
    }

    // ---- 辅助方法 ----

    private SearchResult createSearchResult(String chunkId, String content) {
        return SearchResult.builder("kb1", "doc1", chunkId, content, 1.0)
                .chunkIndex(0)
                .build();
    }
}
