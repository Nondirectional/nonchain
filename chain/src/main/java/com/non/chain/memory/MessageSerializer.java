package com.non.chain.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.non.chain.ContentPart;
import com.non.chain.ImageDataPart;
import com.non.chain.ImageUrlPart;
import com.non.chain.Message;
import com.non.chain.TextPart;
import com.non.chain.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * Message 与 JSON 之间的序列化/反序列化工具。
 */
public class MessageSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageSerializer() {
    }

    public static String serialize(Message message) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.toolCallId() != null) {
                node.put("toolCallId", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode tcArray = node.putArray("toolCalls");
                for (ToolCall tc : message.toolCalls()) {
                    ObjectNode tcNode = tcArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("name", tc.name());
                    tcNode.put("arguments", tc.arguments());
                }
            }
            if (message.contentParts() != null && !message.contentParts().isEmpty()) {
                ArrayNode partsArray = node.putArray("contentParts");
                for (ContentPart part : message.contentParts()) {
                    ObjectNode partNode = partsArray.addObject();
                    if (part instanceof TextPart) {
                        partNode.put("type", "text");
                        partNode.put("text", ((TextPart) part).text());
                    } else if (part instanceof ImageUrlPart) {
                        partNode.put("type", "imageUrl");
                        partNode.put("imageUrl", ((ImageUrlPart) part).url());
                    } else if (part instanceof ImageDataPart) {
                        partNode.put("type", "imageData");
                        partNode.put("base64Data", ((ImageDataPart) part).base64Data());
                        partNode.put("mimeType", ((ImageDataPart) part).mimeType());
                    }
                }
            }
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("消息序列化失败", e);
        }
    }

    public static Message deserialize(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String role = node.get("role").asText();
            String content = node.has("content") ? node.get("content").asText() : null;
            String toolCallId = node.has("toolCallId") ? node.get("toolCallId").asText() : null;

            List<ToolCall> toolCalls = null;
            if (node.has("toolCalls")) {
                toolCalls = new ArrayList<>();
                for (JsonNode tcNode : node.get("toolCalls")) {
                    toolCalls.add(new ToolCall(
                            tcNode.get("id").asText(),
                            tcNode.get("name").asText(),
                            tcNode.get("arguments").asText()
                    ));
                }
            }

            List<ContentPart> contentParts = null;
            if (node.has("contentParts")) {
                contentParts = new ArrayList<>();
                for (JsonNode partNode : node.get("contentParts")) {
                    String type = partNode.get("type").asText();
                    if ("text".equals(type)) {
                        contentParts.add(new TextPart(partNode.get("text").asText()));
                    } else if ("imageUrl".equals(type)) {
                        contentParts.add(new ImageUrlPart(partNode.get("imageUrl").asText()));
                    } else if ("imageData".equals(type)) {
                        contentParts.add(new ImageDataPart(
                                partNode.get("base64Data").asText(),
                                partNode.get("mimeType").asText()
                        ));
                    }
                }
            }

            return Message.of(role, content, contentParts, toolCallId, toolCalls);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("消息反序列化失败", e);
        }
    }
}
