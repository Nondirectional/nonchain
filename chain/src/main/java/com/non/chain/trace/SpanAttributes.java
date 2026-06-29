package com.non.chain.trace;

/**
 * Span 载荷 attributes 的 key 常量，避免裸字符串拼写错误（OTel 风格）。
 *
 * <p>具体每个 {@link SpanType} 用到哪些 key 见下表；录制器（RecordingCallback / Graph.run / Tracer）
 * 读写 attributes 时一律使用本类常量。</p>
 *
 * <table>
 * <tr><th>type</th><th>key</th><th>来源</th></tr>
 * <tr><td>agent_run（根）</td><td>{@link #SYSTEM_PROMPT} / {@link #MAX_ITERATIONS} / {@link #QUERY} / {@link #CONVERSATION_ID}</td><td>Agent 配置 + run 入参</td></tr>
 * <tr><td>graph_run（根）</td><td>{@link #GRAPH_NAME} / {@link #START_NODE}</td><td>Graph.run 入参</td></tr>
 * <tr><td>llm</td><td>{@link #MESSAGES} / {@link #TOOLS} / {@link #RESULT_CONTENT} / {@link #RESULT_THINKING} / {@link #RESULT_TOOL_CALLS} / {@link #PROMPT_TOKENS} / {@link #COMPLETION_TOKENS} / {@link #TOTAL_TOKENS} / {@link #LATENCY_MS}</td><td>LlmStartEvent / LlmCompleteEvent</td></tr>
 * <tr><td>tool</td><td>{@link #TOOL_CALL_ID} / {@link #TOOL_NAME} / {@link #ARGUMENTS} / {@link #RESULT} / {@link #IS_ERROR} / {@link #LATENCY_MS}</td><td>ToolStartEvent / ToolCompleteEvent / ToolErrorEvent</td></tr>
 * <tr><td>graph_node</td><td>{@link #NODE_NAME} / {@link #STATE_IN} / {@link #STATE_OUT} / {@link #ERROR}</td><td>Graph.run 本地采集</td></tr>
 * </table>
 *
 * <p>{@code retrieval} type 不进入 MVP 必做范围。</p>
 */
public final class SpanAttributes {

    private SpanAttributes() {}

    // ---- agent_run / graph_run 根 ----
    public static final String SYSTEM_PROMPT = "system_prompt";
    public static final String MAX_ITERATIONS = "max_iterations";
    public static final String QUERY = "query";
    public static final String CONVERSATION_ID = "conversation_id";
    public static final String GRAPH_NAME = "graph_name";
    public static final String START_NODE = "start_node";

    // ---- llm ----
    public static final String MESSAGES = "messages";
    public static final String TOOLS = "tools";
    public static final String RESULT_CONTENT = "result_content";
    public static final String RESULT_THINKING = "result_thinking";
    public static final String RESULT_TOOL_CALLS = "result_tool_calls";
    public static final String PROMPT_TOKENS = "prompt_tokens";
    public static final String COMPLETION_TOKENS = "completion_tokens";
    public static final String TOTAL_TOKENS = "total_tokens";

    // ---- tool ----
    public static final String TOOL_CALL_ID = "tool_call_id";
    public static final String TOOL_NAME = "tool_name";
    public static final String ARGUMENTS = "arguments";

    // ---- graph_node ----
    public static final String NODE_NAME = "node_name";
    public static final String STATE_IN = "state_in";
    public static final String STATE_OUT = "state_out";

    // ---- 通用 ----
    public static final String RESULT = "result";
    public static final String IS_ERROR = "is_error";
    public static final String ERROR = "error";
    public static final String LATENCY_MS = "latency_ms";

    /**
     * Span 类型常量字符串。Span.type() 用这些值。
     */
    public static final class SpanType {
        private SpanType() {}
        public static final String AGENT_RUN = "agent_run";
        public static final String GRAPH_RUN = "graph_run";
        public static final String LLM = "llm";
        public static final String TOOL = "tool";
        public static final String GRAPH_NODE = "graph_node";
        public static final String RETRIEVAL = "retrieval";
    }
}
