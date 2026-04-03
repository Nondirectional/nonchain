package com.non.chain.example;

import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

import java.util.Arrays;

/**
 * 图片输入示例 — 使用多模态模型识别图片内容
 *
 * 使用 Message.user(List<ContentPart>) 构建包含图片的用户消息，
 * 配合 qwen-vl-plus 等视觉模型进行图片理解。
 */
public class ImageInputExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-vl-plus");

        Message userMessage = Message.user(Arrays.asList(
                ImageUrlPart.of("https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"),
                TextPart.of("女孩的衬衫是什么颜色的？")
        ));

        ChatResult result = llm.chat(Arrays.asList(userMessage));
        System.out.println(result.content());
    }
}
