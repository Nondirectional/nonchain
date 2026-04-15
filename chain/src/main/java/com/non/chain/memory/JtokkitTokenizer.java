package com.non.chain.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import com.non.chain.Message;

import java.util.List;

/**
 * 基于 jtokkit 的 Tokenizer 实现。
 *
 * <pre>{@code
 * Tokenizer tokenizer = JtokkitTokenizer.ofEncoding(EncodingType.CL100K_BASE);
 * Tokenizer tokenizer = JtokkitTokenizer.ofModel(ModelType.GPT_4);
 * }</pre>
 */
public class JtokkitTokenizer implements Tokenizer {

    private final Encoding encoding;

    private JtokkitTokenizer(Encoding encoding) {
        this.encoding = encoding;
    }

    /**
     * 按编码类型构造（cl100k_base, o200k_base 等）
     */
    public static JtokkitTokenizer ofEncoding(EncodingType encodingType) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        return new JtokkitTokenizer(registry.getEncoding(encodingType));
    }

    /**
     * 按模型名构造（自动选择对应编码）
     */
    public static JtokkitTokenizer ofModel(ModelType modelType) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        return new JtokkitTokenizer(registry.getEncodingForModel(modelType));
    }

    /**
     * 使用默认编码 (cl100k_base)
     */
    public static JtokkitTokenizer defaults() {
        return ofEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    @Override
    public int estimateTokenCount(Message message) {
        if (message == null) {
            return 0;
        }
        int count = 0;
        // role 开销（约 4 tokens）
        count += 4;
        if (message.content() != null) {
            count += encoding.countTokens(message.content());
        }
        // toolCalls 的 JSON 大小
        if (message.toolCalls() != null) {
            for (var tc : message.toolCalls()) {
                count += encoding.countTokens(tc.name());
                if (tc.arguments() != null) {
                    count += encoding.countTokens(tc.arguments());
                }
            }
        }
        return count;
    }

    @Override
    public int estimateTokenCount(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += estimateTokenCount(message);
        }
        return total;
    }
}
