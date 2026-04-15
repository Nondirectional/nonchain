package com.non.chain.memory;

import com.non.chain.Message;

import java.util.List;

/**
 * Token 计数接口。
 *
 * <p>用于 Token 裁剪策略中计算消息的 token 数量。</p>
 */
public interface Tokenizer {

    /**
     * 估算文本的 token 数量
     */
    int estimateTokenCount(String text);

    /**
     * 估算单条消息的 token 数量
     */
    int estimateTokenCount(Message message);

    /**
     * 估算消息列表的总 token 数量
     */
    int estimateTokenCount(List<Message> messages);
}
