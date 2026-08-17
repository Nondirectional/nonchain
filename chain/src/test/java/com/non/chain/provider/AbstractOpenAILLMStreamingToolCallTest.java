package com.non.chain.provider;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.tool.Tool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class AbstractOpenAILLMStreamingToolCallTest {

    private HttpServer server;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String response = "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},"
                    + "\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{"
                    + "\"index\":0,\"id\":\"call-1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"open_current_draft\",\"arguments\":\"\"}}]},"
                    + "\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{"
                    + "\"index\":0,\"function\":{\"name\":\"\",\"arguments\":\"{}\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void keepsInitialToolNameWhenLaterDeltaContainsBlankName() {
        OpenAICompatibleLLM llm = new OpenAICompatibleLLM(
                "http://localhost:" + server.getAddress().getPort() + "/v1", "test", "model");
        Tool tool = Tool.builder("open_current_draft")
                .description("open current draft")
                .build();

        ChatResult result = llm.streamChat(
                Collections.singletonList(Message.user("open the current draft")),
                Collections.singletonList(tool),
                chunk -> { });

        assertEquals(1, result.toolCalls().size());
        assertEquals("open_current_draft", result.toolCalls().get(0).name());
        assertEquals("{}", result.toolCalls().get(0).arguments());
    }
}
