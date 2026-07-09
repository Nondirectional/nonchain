package com.non.chain.agent;

/**
 * 子代理执行状态(D9 graceful max turns)。
 *
 * <p>子代理(前台/后台)运行结束后由 {@code runInternal} 产出,标识最终完成状态:</p>
 * <ul>
 *   <li>{@link #RUNNING} — 仍在运行(仅后台子代理中间态)</li>
 *   <li>{@link #COMPLETED} — 正常完成(无 toolCalls 退出,round &lt; maxIterations)</li>
 *   <li>{@link #STEERED} — grace 内收尾成功(到达 maxIterations 后被收尾 steer,grace turns 内完成)</li>
 *   <li>{@link #ABORTED} — 硬中断(maxIterations + grace turns 全部耗尽,返回部分结果)</li>
 *   <li>{@link #FAILED} — 异常退出</li>
 * </ul>
 */
public enum SubAgentStatus {
    RUNNING,
    COMPLETED,
    STEERED,
    ABORTED,
    FAILED
}
