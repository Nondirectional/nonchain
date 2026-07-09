# 执行链路遥测 Trace Telemetry

## Goal

为 nonchain 增加"执行链路遥测"能力：开发者拿到一个运行时 id（runtime id），能把 **MVP 范围内的整棵执行链路** ——含 Agent、Flow、SubAgent、LLM 调用、工具调用、Graph 节点——的 prompt/messages/入参出参/状态快照从 store 里拉出来，供后续做执行链路可视化与归档分析。retrieval 作为同一模型下的后续扩展，不进入本次 MVP。

核心约束：复用已有的 `ChainCallback` 采集数据，**不重打点**；录制层**正交于**用户面 callback；默认**零开销**（不配置不录制）。

## Background / Confirmed Facts

- `ChainCallback` 已经覆盖了 LLM / Tool / Graph 等主要生命周期事件，是遥测的天然采集源：
  - `LlmStartEvent.messages()` = 发给 LLM 的实际 prompt/messages（每轮）
  - `LlmCompleteEvent.result()` = LLM 输出（content + toolCalls）
  - `ToolStartEvent.toolCall()` / `ToolCompleteEvent.result()` = 工具入参/输出
  - `GraphEvent`（`type/node/state/executedNodes/error`）= Flow 节点级事件，但**只携带单份 state 快照**，无法单靠事件同时还原 `state_in/state_out`
- `ChainTrace`（`chain/.../callback/ChainTrace.java`）是基于 `ThreadLocal` 的单 traceId，在每次 `Agent.runWithLoop()`（`Agent.java:167-173`）生成、finally 清理。**目前仅用于关联事件，没有任何持久化**。
- **SubAgent 刻意隔离**：`Agent.java:410` 子代理以 `.callback(ChainCallbackUtil.noop())` 构建；README/CHANGELOG 明确"父/子 callback 与 trace 隔离"。任何"只寄生在 `ChainCallback` 上的录制器"对 SubAgent 内部是黑盒。
- **并行工具传播断点**：`Agent.java:246-247` 用 `CompletableFuture.supplyAsync(..., executor)`，`ChainTrace` 是纯 ThreadLocal，**进 worker 线程即丢失**。
- **Flow 无 traceId**：`Graph.run()`（`Graph.java:35-88`）全程无 `ChainTrace`；节点体 `node.apply(state)`（`Graph.java:62`）是 `Function<State,State>`，签名不可改。节点体内部若调用 `agent.run()`，现在无法知道自己在某个 node 之下。
- **Agent 与 Flow 是并列的执行根，且会互相嵌套**：Flow 节点体里可以塞 `agent.run()` / `llm.chat()` / 子图 `graph.run()`，也可以什么都不塞只改 State。真实执行是树状嵌套。
- Retrieval 事件类型已定义在 callback 包中，但当前主执行路径里**尚未发现实际发射点**；第一版若要覆盖 retrieval，要么补发事件，要么明确降级为后续能力。
- nonchain 是**库**（Maven artifact），不是服务。现有存储全部遵循"接口 + 内置实现 + 外部可插拔"模式：`ChatMemory`（`ChatMemoryStore` SPI）、`KnowledgeStore`（接口 + ES 实现）。无全局 static 可变状态、无 web 框架、无数据库依赖。
- 现有可观测性已标记为"done"，但 OpenTelemetry/Micrometer 集成标注为 out-of-scope（`TODO.md:19`）。本任务把"trace 数据落库 + 按 id 拉取"这一块从无到有补齐。

## Requirements

- 引入 OTel 风格的 span 树：`runtimeId / spanId / parentSpanId / type / startTime / endTime / status / error` 为强类型骨架，载荷按 type 存入通用 `attributes` Map。
- runtime id = **一次顶层执行**：根 span 的类型可以是 `agent_run` 或 `graph_run`（谁在最外层谁就是根）。内嵌的 Agent / Flow / SubAgent / LLM / Tool 全部是同一棵树的子 span，**不切新根**。
- SubAgent **全树下钻**：父 Agent 委派给子代理后，子代理内部的 LLM 轮次、工具调用，作为父 tool span 的子 span 录制进同一棵树。
- 录制层**正交于用户面 callback**：录制上下文走自己的 span 传播路径，不寄生 `ChainCallback`，不动"父/子 callback 隔离"这条既有承诺。
- 传播机制为混合方案：`SpanContext` 作真相源 + ThreadLocal current-span 栈做易用镜像 + 三处硬边界（SubAgent 构建点、并行工具、Flow 节点）显式传播。
- 提供可插拔 `TraceStore` SPI；第一版**必需** `record(span)` / `getTrace(id)`，`search(...)` 明确后置。内置 `InMemoryTraceStore` 开箱即用。
- 存留模型为**持久化归档**：trace 当落库数据对待，序列化格式稳定，schema 能被外部 store（MySQL/Postgres）建表存取；第一版默认实现仍为 in-memory，持久化实现作为可选模块（类比 `chain-mysql`/`chain-postgres`）。
- 提供 Java pull API：`TraceStore.getTrace(runtimeId): Trace` + `Trace` 可序列化为 JSON。**库不起 HTTP、不画 UI**；可视化是独立消费端。
- 必须定义**失败路径**如何拿到 `runtimeId`：不能只依赖成功返回值；异常场景也要能把 runtime id 暴露给调用方，且**不能改变既有异常类型/主异常语义**。
- 录制**默认全关、显式 opt-in**：`Agent.builder(...).trace(store)` / `Graph.builder(name).traceStore(store)`。不配置 = 不录制 = 零开销。不引入全局 static 开关，不加 ServiceLoader 自动发现。

## Acceptance Criteria

- [ ] 引入 `Span` / `Trace` / `SpanContext` 模型：骨架字段强类型 + `attributes` Map 载荷；第一版按 type（`agent_run`/`graph_run`/`llm`/`tool`/`graph_node`）记录载荷；`retrieval` 明确后置，除非实现同时补齐事件发射。
- [ ] runtime id = 一次顶层执行；同一棵树覆盖内嵌 Agent/Flow/SubAgent；任何子执行不新建根。
- [ ] SubAgent 全树下钻：录制器能看到子代理内部 LLM/Tool 调用，作为父委派 tool 的子 span。
- [ ] 并行工具正确父子：同一轮多个并行工具的 span 正确挂在触发它们的 LLM 调用之下，跨 worker 线程不丢 parent。
- [ ] Flow 节点 span：`Graph.run` 为节点建 span 并在 `node.apply` 前 push current，节点体内的 `agent.run()` 自然读到 node span 当 parent；`Function<State,State>` 签名不动。
- [ ] `TraceStore` SPI + `InMemoryTraceStore` 默认实现；`getTrace(id)` 返回完整 span 树；`Trace` 可 JSON 序列化/反序列化。
- [ ] 成功和失败两条路径都能把 `runtimeId` 暴露给调用方；不能出现“trace 已录到 store 但异常调用方拿不到 id”的情况。
- [ ] `Agent.builder().trace(store)` / `Graph.builder(name).traceStore(store)` 启用录制；不配置时行为与现状完全一致、无额外开销。
- [ ] 既有 `ChainTrace` / `ChainCallback` / 事件 `traceId` 字段**原样不动**，继续做用户面关联 id；新 span 系统并行存在。
- [ ] 不破坏现有普通工具、Agent 循环、SubAgent 委派、Graph 执行的任何行为（测试全绿）。
- [ ] 新增示例展示 trace 录制 + 按 id 拉取 + 序列化输出。

## Decisions Locked

1. **采集层**：优先复用 `ChainCallback` 事件携带的数据，不重打点；但录制层**正交于**用户面 callback（不寄生），以绕开 SubAgent 的 `noop()` 隔离。对 Graph 节点前后 state 这类事件无法完整表达的信息，允许在录制层补最小直接采集。
2. **SubAgent 深度**：全树下钻（父委派 tool span 下挂子代理内部所有 span）。这是整套设计的支点——否决了"录制寄生 callback"，逼出正交录制层。
3. **根粒度**：runtime id = 一次顶层执行（根类型 `agent_run` 或 `graph_run`）；内嵌 Agent/Flow/SubAgent 全是同一棵树的子 span，不切新根。会话级聚合（`conversationId`）作为次级聚合层后置，第一版 root span 可带 `conversationId` 字段但可不填。
4. **传播**：`SpanContext`（runtimeId/spanId/parentSpanId/type）真相源 + ThreadLocal current-span 栈镜像（同线程子代码免改签名读 parent）+ 三边界显式传播（SubAgent 构建点带 ctx 进去、并行工具在 supplyAsync 前捕获 ctx、Flow 节点 push current 后调 node.apply）。`Node.apply(State)` 签名不动。
5. **存储**：可插拔 `TraceStore` SPI + 内置 `InMemoryTraceStore`，第一版契约只锁定 `record(span)` / `getTrace(id)`；存留模型 = 持久化归档（序列化格式稳定、可建表）。持久化实现（MySQL/Postgres）作为可选模块后置。
6. **取回边界**：库只到 Java API（`getTrace(id)` + JSON 序列化）；可视化是独立消费端，不进核心库。
7. **载荷建模**：统一 `Span` + 强类型骨架 + `attributes` Map（OTel 风格）；载荷无编译期类型安全，用公开常量替代裸字符串挡拼写错误。载荷 key 约定以常量类公开。
8. **启用模型**：默认全关，`.trace(store)` 显式 opt-in；不引全局 static，不加 ServiceLoader 自动发现。需要"全应用默认录"由应用层封装工厂。
9. **失败路径取回**：`runtimeId` 不能只挂在成功返回值上；MVP 采用"**保留原异常类型 + 附加可提取的 trace 元信息**"方案，不引入包装异常来改变调用方既有 `catch` 语义。建议通过 suppressed marker + 辅助提取 API 暴露 runtimeId。

## Out Of Scope

- 可视化前端（独立项目/模块消费 JSON API）。
- OTel / LangSmith exporter（Q1 已留门，将来写一个 `TraceStore` 实现或 exporter 即可）。
- 持久化 store 实现本身（MySQL/Postgres 模块，类比 `chain-mysql`，作为独立后续任务）。
- `TraceStore.search(...)` 查询 API。
- 采样策略（opt-in 即全录；always-on + 采样留到生产 always-on 场景）。
- 会话级聚合查询 API（第一版只按 runtime id 取单棵树；`conversationId` 聚合后置）。
- Retrieval span，除非本任务同时补齐 retrieval 事件的真实发射路径。
- 响应式多跳异步链的 context 传播（当前框架异步仅止于并行工具单跳；多跳 `.thenComposeAsync` 链留到真出现时再加 context-propagating executor）。

## Notes

- 复杂任务：本文件聚焦需求/约束/验收，技术设计见 `design.md`，执行顺序见 `implement.md`。三者齐备且通过 review gate 后再 `task.py start`。
- 设计暗线：SubAgent 全树下钻（Decision 2）是整条设计链支点——它否决寄生 callback（Decision 1），叠加单一根否决切新根（Decision 3），叠加并行工具否决纯 ThreadLocal（Decision 4）。
