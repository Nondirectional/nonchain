package com.non.chain.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注工具方法的参数
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /**
     * 参数名称（Java 编译时不保留参数名，需显式指定）
     */
    String name();

    /**
     * 参数描述
     */
    String description() default "";

    /**
     * 是否必填，默认 true
     */
    boolean required() default true;
}
