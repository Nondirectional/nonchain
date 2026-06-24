package com.non.chain.example;

import com.non.chain.Message;
import com.non.chain.memory.InMemoryChatMemoryStore;
import com.non.chain.memory.MessageSerializer;
import com.non.chain.memory.MessageWindowChatMemory;
import com.non.chain.memory.ChatMemory;

import java.util.Arrays;
import java.util.List;

/**
 * 应用层消息与 LLM 消息分层 Demo（任务 06-24-message-layering）
 *
 * <p>演示三层语义：</p>
 * <ol>
 *   <li><b>产生</b>：用 {@link Message#note(String, String)} 产生应用层消息（UI 状态/通知），
 *       标记为 llmVisible=false</li>
 *   <li><b>持久化</b>：note 进 memory 并经 {@link MessageSerializer} 往返，
 *       UI 可从 transcript 重建含 note 的完整历史</li>
 *   <li><b>过滤</b>：note 不进 LLM 上下文（边界过滤），且不占窗口裁剪预算、原位保留</li>
 * </ol>
 *
 * <p>本示例不需要 LLM API Key，纯本地演示标记机制 + 持久化往返 + 裁剪交互。
 * 如需演示 Agent 实际调用 LLM 时 note 被剥离，参考 {@link ToolInterceptorExample}，
 * 在拦截器中注入 {@code Message.note(...)} 到 transcript。</p>
 */
public class MessageLayeringExample {

    public static void main(String[] args) {
        System.out.println("=== 应用层消息与 LLM 消息分层 Demo ===");
        System.out.println();

        // 1. 产生应用层消息（UI 状态/通知）
        Message note1 = Message.note("status", "正在读取文件 X");
        Message note2 = Message.note("ui", "工具审核中");
        System.out.println("[1] 产生应用层消息:");
        System.out.println("    note1 = role=" + note1.role()
                + ", llmVisible=" + note1.llmVisible()
                + ", kind=" + note1.kind()
                + ", content=" + note1.content());
        System.out.println("    note2 = role=" + note2.role()
                + ", llmVisible=" + note2.llmVisible()
                + ", kind=" + note2.kind()
                + ", content=" + note2.content());
        System.out.println();

        // 2. 持久化往返：note 经 MessageSerializer 序列化/反序列化后标记完整保留
        System.out.println("[2] 持久化往返（MessageSerializer）:");
        String json = MessageSerializer.serialize(note1);
        System.out.println("    序列化 JSON: " + json);
        Message restored = MessageSerializer.deserialize(json);
        System.out.println("    反序列化: role=" + restored.role()
                + ", llmVisible=" + restored.llmVisible()
                + ", kind=" + restored.kind()
                + ", content=" + restored.content());
        System.out.println("    ✅ note 标记（llmVisible/kind）往返后完整保留");
        System.out.println();

        // 3. 裁剪交互：note 不占窗口预算、原位保留
        System.out.println("[3] 裁剪交互（MessageWindowChatMemory）:");
        ChatMemory memory = MessageWindowChatMemory.builder()
                .store(new InMemoryChatMemoryStore())
                .maxMessages(2)
                .conversationId("demo-conv")
                .build();

        memory.add(Message.system("你是一个助手"));
        memory.add(Message.user("问题1"));
        memory.add(Message.note("status", "正在思考"));   // 不占预算
        memory.add(Message.assistant("回答1"));
        memory.add(Message.user("问题2"));                // 触发裁剪

        List<Message> transcript = memory.messages();
        System.out.println("    maxMessages=2，加入 1 system + 2 user + 1 assistant + 1 note 后:");
        long llmVisibleCount = transcript.stream().filter(Message::llmVisible).count();
        long noteCount = transcript.stream().filter(m -> "note".equals(m.role())).count();
        System.out.println("    transcript 共 " + transcript.size() + " 条："
                + llmVisibleCount + " LLM 可见 + " + noteCount + " note");
        System.out.println("    ✅ note 不占预算、原位保留；LLM 可见消息裁剪到 maxMessages");
        System.out.println();

        // 4. UI 重放视角：transcript 完整还原应用与 LLM 双层历史
        System.out.println("[4] UI 重放视角（完整 transcript）:");
        for (int i = 0; i < transcript.size(); i++) {
            Message m = transcript.get(i);
            String layer = m.llmVisible() ? "LLM" : "应用层";
            System.out.println("    [" + i + "] (" + layer + ")"
                    + " role=" + m.role()
                    + (m.kind() != null ? " kind=" + m.kind() : "")
                    + " : " + m.content());
        }
        System.out.println();
        System.out.println("✅ 分层成立：应用层消息进 transcript（UI 可重放），不进 LLM 上下文，不占裁剪预算。");

        // 5. 对照：边界过滤视角（直接展示过滤语义）
        System.out.println();
        System.out.println("[5] LLM 边界过滤视角:");
        List<Message> allForLlm = Arrays.asList(
                Message.system("系统提示"),
                Message.user("问题"),
                Message.note("status", "正在思考"),
                Message.assistant("回答")
        );
        System.out.println("    输入 messages（含 note）共 " + allForLlm.size() + " 条");
        System.out.println("    边界过滤后（进 provider 请求的只有 LLM 可见消息）:");
        allForLlm.stream()
                .filter(Message::llmVisible)
                .forEach(m -> System.out.println("      - role=" + m.role() + " : " + m.content()));
        System.out.println("    ✅ note 在 LLM 边界被剥离，不污染 LLM 上下文。");
    }
}
