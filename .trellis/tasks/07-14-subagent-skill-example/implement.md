# SubAgent 执行事件与 Skill 注入可观测性：执行计划

## 1. 事件模型

- [x] 在 `AgentEvent` 新增 `SubAgentProgress` 不可变事件与无前缀 accessors。
- [x] 补充 JavaDoc：字段、前后台 ID、并发顺序、消费者线程安全和异常隔离。

## 2. 前台传播

- [x] 将父级 `eventConsumer` 与 `ToolCall.id()` 显式传过 `executeWithToolSpan`、`safeExecute`、`dispatchExecute`、`executeSubAgentTool`。
- [x] 前台每次调用生成 UUID；在 `runSubAgentInternal` 构造包装 consumer。
- [x] 有 consumer 时调用 child event overload；无 consumer 时保持原 `child.run(childMessages)`。
- [x] 仅隔离 `SubAgentProgress` 消费异常，不改变父 Agent 原有事件异常语义。

## 3. 后台传播

- [x] `BackgroundSubAgentManager.spawn` 接收 `parentToolCallId`。
- [x] `SubAgentRecord` 保存 `parentToolCallId` 并提供 accessor。
- [x] 后台执行复用 record ID，通过 manager 安全事件出口投递包装事件。
- [x] 保留现有后台生命周期事件与线程模型。
- [x] 修复父循环竞态：无 running 任务时仍 join 已完成后台结果，避免提前返回中间文本。

## 4. 测试

- [x] 扩展 `SubAgentSkillTest`：断言 Skill schema、tool result、完整注入消息和包装后的 `SkillActivated`。
- [x] 断言 `subAgentId/name/task/parentToolCallId/background` 字段与同一调用内 ID 稳定。
- [x] 断言 Round/Text/Tool/Skill/Complete 等代表性原始子事件类型未丢失。
- [x] 断言同名子代理重复调用生成不同 `subAgentId`，事件可正确分组。
- [x] 新增后台 progress 测试：ID 与 Spawned 一致，跨线程集合安全。
- [x] 新增消费者异常隔离测试，最终 Agent 结果仍成功。
- [x] 运行 `rtk mvn -pl chain test -Dtest=SubAgentSkillTest,SubAgentAdvancedTest -q`。
- [x] 运行 `rtk mvn -pl chain test -q`。

## 5. Example 与文档

- [x] 更新 `SubAgentSkillExample`，按已确认的代表性事件打印子代理执行过程，保留现有 VLLM 配置。
- [x] 更新 README 的 callback/event/trace 边界与 Example 说明。
- [x] 更新 backend code-spec：`SubAgentProgress` API、事件/并发/异常契约和测试矩阵。
- [x] 运行 `rtk mvn -pl chain-example -am test -q` 与全仓 `rtk mvn test -q`。

## 6. 风险与回滚

- 风险：已有 event consumer 收到更多事件；后台回调可能并发。
- 风险：父级启用 event consumer 时，子 Agent 改走已有 streaming overload；测试覆盖消息与结果一致性。
- 回滚：移除 child event wrapper 与调用链参数；无需数据迁移。
