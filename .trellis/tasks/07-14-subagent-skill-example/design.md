# SubAgent 执行事件与 Skill 注入可观测性：设计

## 1. 边界

本次只扩展 `AgentEvent` 实时观察路径。`ChainCallback` 父子隔离保持不变，Trace 继续记录完整 span 树，SkillRegistry、注入模式、tool-calling 协议与 SubAgent 生命周期状态机不变。

## 2. 公共事件契约

在 `AgentEvent` 中新增不可变包装事件：

```java
class SubAgentProgress implements AgentEvent {
    String subAgentId();
    String name();
    String task();
    String parentToolCallId();
    boolean background();
    AgentEvent event();
}
```

- `event()` 保留原始子事件类型和载荷，不复制或扁平化字段。
- `subAgentId` 标识一次调用：前台生成 UUID，后台复用 `SubAgentRecord.id`。
- `parentToolCallId` 来自触发委派的 `ToolCall.id()`，允许为 null（兼容异常 provider 输出）。
- `task` 是解析后的委派任务，不要求 UI 再解析 tool arguments。
- 不支持嵌套 SubAgent，因此不会产生递归 `SubAgentProgress`；现有构建校验继续禁止子代理 ToolRegistry 再注册 SubAgent。

## 3. 事件数据流

```text
parent Agent eventConsumer
  ├─ parent AgentEvent（现状）
  ├─ outer ToolStart / ToolEnd（现状）
  └─ SubAgentProgress
       └─ child AgentEvent
            RoundStart / ThinkingDelta / TextDelta / ToolCallDelta
            ToolStart / ToolEnd / SkillActivated / RoundEnd
            Complete / AgentError
```

父级提供 `eventConsumer` 时，`runSubAgentInternal` 使用 `child.run(childMessages, childEventConsumer)`；没有父级 consumer 时仍调用 `child.run(childMessages)`，保持同步非流式路径和零包装开销。

## 4. 前台调用

`dispatchExecute` 将 `ToolCall.id()` 和父级 event consumer 传入前台 SubAgent 路径。每次调用生成 UUID 作为 `subAgentId`，再构造包装 consumer。前台不新增 `SubAgentSpawned/Started/Completed/Failed`，避免与内部 `Complete/AgentError`、外层 `ToolEnd` 重复。

## 5. 后台调用

`BackgroundSubAgentManager.spawn` 接收并保存 `parentToolCallId` 到 `SubAgentRecord`。后台执行复用 record ID，并通过 manager 的安全事件出口向父级投递包装事件。

- 单个 `subAgentId` 内事件按子 Agent 产生顺序投递。
- 多个后台/并行调用可在不同线程交错投递。
- `eventConsumer` 必须线程安全。
- 原有 `SubAgentSpawned/Started/Completed/Failed/Steered/Aborted` 保持不变。

## 6. 异常语义

`SubAgentProgress` 的父级消费异常被隔离；观察失败不改变子代理执行、状态和 tool result。子代理自身 `AgentError` 仍作为被包装事件投递，并遵循原有业务错误路径。

## 7. Example

`SubAgentSkillExample` 处理 `SubAgentProgress`，使用 `[SubAgent:name/id]` 前缀展示 Round、Tool、Skill、Text、Error 与 Complete。默认不打印 `ThinkingDelta`，但 API 完整保留。示例不修改 VLLM 配置。

## 8. 兼容与回滚

- 未提供 event consumer：消息序列、LLM 调用方式、性能与现状一致。
- 已提供 event consumer：新增事件类型和调用次数；旧 `instanceof` 分支可自然忽略。
- `SubAgentRecord` 与 `BackgroundSubAgentManager.spawn` 保留旧签名重载，未关联父 tool-call 时 `parentToolCallId=null`。
- 后台记录在 `markCompleted/markFailed` 时完成 future，确保 `awaitAll/getResult` 能观察到已结束状态。
- 父循环在无运行后台任务时也执行 `joinCompleted()`，避免子代理在 `hasRunning()` 检查前完成导致父 Agent 提前返回中间文本。
- 删除包装 consumer 的传递即可回滚，不涉及存储或协议迁移。

## 9. 验证

- 前台 Skill 子代理：schema、Skill tool call、tool result、注入消息、`SkillActivated` 包装事件与调用上下文字段。
- 前台事件类型：Round、Text、Complete 等保留原始子事件。
- 后台事件：progress ID 与 `SubAgentSpawned.subAgentId` 一致，`background=true`，parent tool-call ID 正确。
- 并发/异常：消费者抛异常不影响最终结果；事件集合使用线程安全容器。
- 完整 `chain` 与 `chain-example` Maven 回归。
