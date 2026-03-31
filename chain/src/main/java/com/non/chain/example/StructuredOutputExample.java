package com.non.chain.example;

import com.non.chain.OutputFormat;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

public class StructuredOutputExample {
    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus");

        String system = "你是一个只输出JSON对象的助手。";
        String user = "请生成用户画像，字段包含name、age、tags(数组)。只返回JSON对象，不要markdown。";
        String result = llm.chat(system, user, OutputFormat.JSON_OBJECT).content();

        System.out.println("模型输出:");
        System.out.println(result);

        if (!result.trim().startsWith("{") || !result.trim().endsWith("}")) {
            System.out.println("警告：输出不是标准 JSON 对象");
        }
    }
}
