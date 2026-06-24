package com.non.chain.agent;

/**
 * after 拦截器的返回类型，表达「不改」或「改写工具结果」。
 *
 * <p>可改写 content（脱敏/截断）与 isError（标记或撤销错误）。不可变，仅通过静态工厂构造。
 * 本任务不含 terminate（留给后续 P1）；{@link Builder} 设计为可扩展。</p>
 */
public final class AfterResult {

    private final boolean modified;
    private final String content;     // null = 不改
    private final Boolean isError;    // null = 不改；TRUE/FALSE = 显式设置

    private AfterResult(boolean modified, String content, Boolean isError) {
        this.modified = modified;
        this.content = content;
        this.isError = isError;
    }

    /** 不改写工具结果。 */
    public static AfterResult keep() {
        return new AfterResult(false, null, null);
    }

    /** 仅改写 content（脱敏/截断等）。 */
    public static AfterResult content(String newContent) {
        return new AfterResult(true, newContent, null);
    }

    /** 标记工具结果为错误（isError=true）。 */
    public static AfterResult error() {
        return new AfterResult(true, null, true);
    }

    /** 组合多项改写（content + isError）。 */
    public static Builder builder() {
        return new Builder();
    }

    public boolean modified() {
        return modified;
    }

    /** 改写后的 content；调用方应先判 {@link #modified()}。null 表示不改。 */
    public String content() {
        return content;
    }

    /** 改写后的错误标志；null 表示不改，TRUE/FALSE 为显式设置。 */
    public Boolean isError() {
        return isError;
    }

    /** after 改写构造器。未设置任何项时 {@link #build()} 返回 {@link #keep()}。 */
    public static final class Builder {
        private String content;
        private Boolean isError;

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder isError(boolean isError) {
            this.isError = isError;
            return this;
        }

        public AfterResult build() {
            boolean modified = content != null || isError != null;
            if (!modified) {
                return keep();
            }
            return new AfterResult(true, content, isError);
        }
    }
}
