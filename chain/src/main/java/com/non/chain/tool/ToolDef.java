package com.non.chain.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个方法为 LLM 可调用的工具
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolDef {

    /**
     * 工具名称
     */
    String name();

    /**
     * 工具描述
     */
    String description();
}
