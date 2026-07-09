package com.non.chain.agent;

import com.non.chain.ChatResult;

/**
 * Agent 流式事件，表示 Agent 循环过程中的各种增量信息。
 *
 * <p>通过 instanceof 判断具体事件类型：</p>
 *
 * <pre>{@code
 * agent.run(messages, event -> {
 *     if (event instanceof AgentEvent.TextDelta) {
 *         print(((AgentEvent.TextDelta) event).delta());
 *     } else if (event instanceof AgentEvent.ToolStart) {
 *         print("调用: " + ((AgentEvent.ToolStart) event).toolName());
 *     } else if (event instanceof AgentEvent.Complete) {
 *         handle(((AgentEvent.Complete) event).result());
 *     }
 * });
 * }</pre>
 */
public interface AgentEvent {

    /**
     * LLM 文本输出增量
     */
    class TextDelta implements AgentEvent {
        private final String delta;

        public TextDelta(String delta) {
            this.delta = delta;
        }

        public String delta() {
            return delta;
        }
    }

    /**
     * LLM 思考输出增量
     */
    class ThinkingDelta implements AgentEvent {
        private final String delta;

        public ThinkingDelta(String delta) {
            this.delta = delta;
        }

        public String delta() {
            return delta;
        }
    }

    /**
     * LLM 生成工具调用参数的增量
     */
    class ToolCallDelta implements AgentEvent {
        private final int index;
        private final String id;
        private final String name;
        private final String argumentsDelta;

        public ToolCallDelta(int index, String id, String name, String argumentsDelta) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.argumentsDelta = argumentsDelta;
        }

        public int index() {
            return index;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String argumentsDelta() {
            return argumentsDelta;
        }
    }

    /**
     * 工具开始执行
     */
    class ToolStart implements AgentEvent {
        private final String toolName;
        private final String arguments;

        public ToolStart(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        public String toolName() {
            return toolName;
        }

        public String arguments() {
            return arguments;
        }
    }

    /**
     * 工具执行完成
     */
    class ToolEnd implements AgentEvent {
        private final String toolName;
        private final String result;

        public ToolEnd(String toolName, String result) {
            this.toolName = toolName;
            this.result = result;
        }

        public String toolName() {
            return toolName;
        }

        public String result() {
            return result;
        }
    }

    /**
     * 新一轮 LLM 调用开始
     */
    class RoundStart implements AgentEvent {
        private final int round;

        public RoundStart(int round) {
            this.round = round;
        }

        public int round() {
            return round;
        }
    }

    /**
     * 一轮 LLM+Tool 结束
     */
    class RoundEnd implements AgentEvent {
        private final int round;

        public RoundEnd(int round) {
            this.round = round;
        }

        public int round() {
            return round;
        }
    }

    /**
     * Agent 循环中出现错误
     */
    class AgentError implements AgentEvent {
        private final Exception error;

        public AgentError(Exception error) {
            this.error = error;
        }

        public Exception error() {
            return error;
        }
    }

    /**
     * Agent 循环结束，携带最终 ChatResult
     */
    class Complete implements AgentEvent {
        private final ChatResult result;

        public Complete(ChatResult result) {
            this.result = result;
        }

        public ChatResult result() {
            return result;
        }
    }

    // ---- SubAgent 生命周期事件(D5):后台子代理的状态转换,内部事件仍隔离 ----

    /** 后台子代理被派发 */
    class SubAgentSpawned implements AgentEvent {
        private final String subAgentId;
        private final String name;
        private final String task;
        private final boolean background;

        public SubAgentSpawned(String subAgentId, String name, String task, boolean background) {
            this.subAgentId = subAgentId;
            this.name = name;
            this.task = task;
            this.background = background;
        }

        public String subAgentId() { return subAgentId; }
        public String name() { return name; }
        public String task() { return task; }
        public boolean background() { return background; }
    }

    /** 后台子代理从队列被调度执行 */
    class SubAgentStarted implements AgentEvent {
        private final String subAgentId;
        private final String name;

        public SubAgentStarted(String subAgentId, String name) {
            this.subAgentId = subAgentId;
            this.name = name;
        }

        public String subAgentId() { return subAgentId; }
        public String name() { return name; }
    }

    /** 后台子代理正常完成(含 STEERED/ABORTED) */
    class SubAgentCompleted implements AgentEvent {
        private final String subAgentId;
        private final String name;
        private final SubAgentStatus status;
        private final String resultPreview;

        public SubAgentCompleted(String subAgentId, String name, SubAgentStatus status, String resultPreview) {
            this.subAgentId = subAgentId;
            this.name = name;
            this.status = status;
            this.resultPreview = resultPreview;
        }

        public String subAgentId() { return subAgentId; }
        public String name() { return name; }
        public SubAgentStatus status() { return status; }
        public String resultPreview() { return resultPreview; }
    }

    /** 后台子代理执行失败 */
    class SubAgentFailed implements AgentEvent {
        private final String subAgentId;
        private final String name;
        private final Throwable error;

        public SubAgentFailed(String subAgentId, String name, Throwable error) {
            this.subAgentId = subAgentId;
            this.name = name;
            this.error = error;
        }

        public String subAgentId() { return subAgentId; }
        public String name() { return name; }
        public Throwable error() { return error; }
    }

    /** 后台子代理被 steer(D6) */
    class SubAgentSteered implements AgentEvent {
        private final String subAgentId;
        private final String message;

        public SubAgentSteered(String subAgentId, String message) {
            this.subAgentId = subAgentId;
            this.message = message;
        }

        public String subAgentId() { return subAgentId; }
        public String message() { return message; }
    }

    /** 后台子代理被硬中断(D9 aborted) */
    class SubAgentAborted implements AgentEvent {
        private final String subAgentId;
        private final String name;

        public SubAgentAborted(String subAgentId, String name) {
            this.subAgentId = subAgentId;
            this.name = name;
        }

        public String subAgentId() { return subAgentId; }
        public String name() { return name; }
    }
}
