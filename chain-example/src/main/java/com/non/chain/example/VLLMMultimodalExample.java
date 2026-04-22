package com.non.chain.example;

import com.non.chain.*;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;

import java.util.Arrays;

/**
 * vLLM 多模态示例 — 演示通过 vLLM 部署的视觉模型进行图片理解
 *
 * <p>展示三种图片输入方式：URL 图片、本地文件图片、base64 数据图片。</p>
 */
public class VLLMMultimodalExample {

    // ===== 按实际部署修改 =====
    private static final String BASE_URL = "http://10.100.10.21:40000/v1";
    private static final String MODEL = "qwen3-14b";

    public static void main(String[] args) {
        LLM llm = new VLLM(BASE_URL, MODEL);

        // ---- 1. URL 图片 ----
        System.out.println("========== 1. URL 图片 ==========");
        Message urlMessage = Message.user(Arrays.asList(
                ImageUrlPart.of("https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"),
                TextPart.of("描述这张图片的内容")
        ));
        ChatResult result1 = llm.chat(Arrays.asList(urlMessage));
        System.out.println(result1.content());

        // ---- 2. 本地文件图片 ----
        System.out.println("\n========== 2. 本地文件图片 ==========");
        // 将路径替换为实际的本地图片路径
        Message fileMessage = Message.user(Arrays.asList(
                ImageDataPart.fromFile("/Users/non/Downloads/img.jpg"),
                TextPart.of("这张图片里有什么？")
        ));
        ChatResult result2 = llm.chat(Arrays.asList(fileMessage));
        System.out.println(result2.content());

        // ---- 3. base64 数据图片 ----
        System.out.println("\n========== 3. base64 数据图片 ==========");
        // 使用已有的 base64 编码图片数据
        String base64Data = "..."; // 替换为实际的 base64 编码图片数据
        Message dataMessage = Message.user(Arrays.asList(
                ImageDataPart.of(base64Data, "image/jpeg"),
                TextPart.of("请描述这张图片")
        ));
        ChatResult result3 = llm.chat(Arrays.asList(dataMessage));
        System.out.println(result3.content());
    }
}
