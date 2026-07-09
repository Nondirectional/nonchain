package com.non.chain.agent;

/**
 * 子代理运行结果(D9):最终文本 + 完成状态。
 *
 * <p>由 {@code Agent.runInternal} 产出,供 {@code executeSubAgentTool} / {@code BackgroundSubAgentManager}
 * 读取状态(瑕疵A 分层通道)。{@link #displayText()} 在非正常完成时追加 status note,
 * 让父代理判断产出完整性。</p>
 */
public final class SubAgentResult {

    private final String content;
    private final SubAgentStatus status;

    public SubAgentResult(String content, SubAgentStatus status) {
        this.content = content;
        this.status = status;
    }

    public String content() {
        return content;
    }

    public SubAgentStatus status() {
        return status;
    }

    /**
     * 展示文本:非正常完成时追加警告(D9 status note),防止父代理把部分输出误当已完成结果。
     *
     * @return content + 状态警告(若有)
     */
    public String displayText() {
        if (content == null || content.isEmpty()) {
            return note();
        }
        String n = note();
        return n.isEmpty() ? content : content + "\n\n" + n;
    }

    private String note() {
        if (status == null) {
            return "";
        }
        switch (status) {
            case STEERED:
                return "(限时收尾,输出可能不完整)";
            case ABORTED:
                return "(超 maxIterations 硬中断,输出可能不完整)";
            case FAILED:
                return "(执行失败)";
            default:
                return "";
        }
    }
}
