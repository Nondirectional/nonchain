# Agent 控制流可扩展性（借鉴 pi-agent-core）

## Goal

为 nonchain Agent 运行时补齐**控制流的可扩展性**，使其从"能跑的 agent"升级为"能被定制/治理的 agent"。

现状：nonchain 的 `ChainCallback` 是纯只读观察者（`CompositeCallback.safeInvoke` 甚至静默吞异常），`Message` 是单一统一模型（无应用层/LLM 层区分），工具执行没有拦截点，消息历史只有裁剪没有分层。要改工具行为或管理 UI-only 状态，用户只能继承 `Agent`/`AbstractOpenAILLM`/`ToolRegistry` 覆写方法，破坏封装。

借鉴 pi-agent-core（`@earendil-works/pi-agent-core`）的设计，补齐两个 P0 能力（见子任务）。

## 来源

对 pi-agent-core 源码的分析（已 clone 到 `/tmp/pi-sparse/packages/agent`，约 1 万行 TS）。可借鉴要点已在会话中评估并按性价比排序。本父任务聚焦其中两个 P0 项。

## 任务地图（子任务）

| 子任务 | slug | 可独立验收 | 依赖 |
|---|---|---|---|
| Agent 工具拦截器（beforeToolCall/afterToolCall） | `06-24-tool-interceptor` | ✅ | 无 |
| 应用层消息与 LLM 消息分层 | `06-24-message-layering` | ✅ | 无 |

两个子任务**逻辑独立**（不同抽象：控制点 vs 数据模型），但**物理可能都动 `Agent.java`**：
- 拦截器动 `Agent.doRunWithLoop` 的工具执行段
- 消息分层动 `Message` 模型 + `AbstractOpenAILLM.buildMessageListParams` 的过滤

若两者并发实现，`Agent.java` 的合并冲突由本父任务的**集成评审**兜底。建议执行顺序：先拦截器（控制流纯增量，不动数据模型），再消息分层（数据模型改动，需连带审视所有消费 Message 的位置）。

## 跨子任务集成验收

父任务保留对最终集成态的验收，子任务只对自己的单元能力负责：

- [ ] 两个子任务各自的核心能力验收通过（见各自 prd）
- [ ] 拦截器与消息分层**正交**：拦截器拿到的 `ToolCall`/结果仍是 `Message` 模型，消息分层不改变拦截器的输入输出契约
- [ ] `Agent.java` 合并后无语义冲突（两个改动在同一方法的不同位置，预期不冲突）
- [ ] 现有测试全绿（`AgentTest`、`AgentMemoryTest`、`ToolRegistryTest`）
- [ ] `chain-example` 中至少有一个示例演示新能力（拦截器示例 + 非进 LLM 消息示例）
- [ ] spec 更新：若引入新的扩展点范式（interceptor / llmVisible），写入 `.trellis/spec/backend/`

## 共享设计原则（两个子任务都遵守）

1. **增量而非重写**：不破坏现有 `ChainCallback`、`Message`、`Agent.Builder` 的公开 API。新能力以新增接口/字段形式加入，默认行为与现状一致。
2. **回调与拦截器职责正交**：`ChainCallback` 仍只观察行为，新拦截器控制行为。两者不互相替代，可在同一调用链共存。
3. **错误用异常，不改 Result 范式**：nonchain 是 Java 生态，保持异常主流范式。拦截器抛异常按现有 `AgentException` 规范处理。
4. **不照搬 TS 结构**：借鉴 pi-agent-core 的*思想*（拦截点、消息分层、convertToLlm 边界），不照搬其 `EventStream`/`async generator`/declaration merging 等 TS 特有结构。用 Java 惯用方式表达。
5. **最小化改动面**：每个子任务的 design.md 必须列出精确的改动文件清单，预期不动 provider 之外的无关模块。

## Out of Scope

以下能力在会话分析中被识别但**本父任务不包含**（属 P1+，后续单独建任务）：

- 工具并行执行的 preflight 串行重构（P1）
- `terminate` hint + `shouldStopAfterTurn` 优雅停止（P1）
- Compaction（基于摘要的上下文压缩）（P2）
- Provider 请求拦截 before_provider_request/payload（P2）
- 会话树 / fork / 分支摘要（P3，与"轻量框架"定位冲突，暂不考虑）

## Open Questions

- 无阻塞性问题。两个子任务的具体 API 形态（接口名、字段名）留到各自 design.md 决策。
