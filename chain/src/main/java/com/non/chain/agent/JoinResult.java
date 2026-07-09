package com.non.chain.agent;

import com.non.chain.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮末 join 的产物(D3/D13):把已完成但未被消费的后台子代理结果,合并成一条消息注入父循环。
 *
 * <p>同一轮完成的多个后台结果,按 D13 合并成<b>一条</b> {@code user} 消息注入,降低消息膨胀。
 * {@link #isEmpty()} 表示无已完成未消费结果(空操作)。{@link #hasUnconsumed()} 用于 Complete 前
 * 强制等待判定。</p>
 */
public final class JoinResult {

    private final List<SubAgentRecord> records;
    private final boolean awaitTimeout;   // awaitAll 超时标志(仅 Complete 前等待场景)

    public JoinResult(List<SubAgentRecord> records, boolean awaitTimeout) {
        this.records = records == null ? new ArrayList<>() : new ArrayList<>(records);
        this.awaitTimeout = awaitTimeout;
    }

    /** 空结果(无已完成未消费的后台子代理) */
    public static JoinResult empty() {
        return new JoinResult(new ArrayList<>(), false);
    }

    /** 是否无已完成未消费结果 */
    public boolean isEmpty() {
        return records.isEmpty();
    }

    /** 是否有未消费结果(用于 Complete 前强制等待判定) */
    public boolean hasUnconsumed() {
        return !records.isEmpty();
    }

    /** awaitAll 是否超时(Complete 前等待场景,超时后强制允许 Complete) */
    public boolean awaitTimeout() {
        return awaitTimeout;
    }

    /**
     * 合并成一条 user 消息注入(D13)。格式:
     * <pre>
     * [子代理 "research" 完成]
     * &lt;结果文本&gt;
     *
     * [子代理 "writer" 完成(限时收尾,输出可能不完整)]
     * &lt;结果文本&gt;
     * </pre>
     *
     * @return 合并消息;空结果返回 null
     */
    public Message mergedMessage() {
        if (records.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            SubAgentRecord r = records.get(i);
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("[子代理 \"").append(r.name()).append("\" ")
                    .append(statusLabel(r)).append("]\n")
                    .append(r.result() != null ? r.result() : "(无输出)");
        }
        return Message.user(sb.toString());
    }

    private String statusLabel(SubAgentRecord r) {
        if (r.status() == null) {
            return "完成";
        }
        switch (r.status()) {
            case COMPLETED:
                return "完成";
            case STEERED:
                return "完成(限时收尾,输出可能不完整)";
            case ABORTED:
                return "中断(输出可能不完整)";
            case FAILED:
                return "失败";
            default:
                return "完成";
        }
    }

    /** 参与合并的记录(用于事件发射等) */
    public List<SubAgentRecord> records() {
        return new ArrayList<>(records);
    }
}
