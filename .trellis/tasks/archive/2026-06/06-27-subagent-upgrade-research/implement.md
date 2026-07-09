# Implement — nonchain SubAgent MVP

> 依赖 `prd.md` 与 `design.md`。本文件只描述实现顺序、验证方式、风险点和 review gate，不等于开始实现。

## 1. 实现目标

交付一个可在 `Agent` 自动循环中使用的委派型 SubAgent MVP，满足：

- `ToolRegistry` 可注册子代理
- `Agent.Builder` 可选择 SubAgent 暴露模式
- `DIRECT` / `DELEGATE` 两种模式可工作
- 父 Agent 可委派给子代理并拿回最终文本
- 父/子拦截器、错误语义、并行语义符合 `prd.md`

## 2. 建议实现顺序

### Step 1：补齐最小类型与枚举

- [ ] 新增 `SubAgentExposureMode`
  - 位置建议：`chain/src/main/java/com/non/chain/agent/SubAgentExposureMode.java`
  - 值：`DIRECT`、`DELEGATE`
- [ ] 新增 `SubAgentDefinition`
  - 不可变值对象，放 `tool/` 包
- [ ] 新增 `SubAgentRegistration`
  - `ToolRegistry.registerSubAgent(...)` 返回的 Builder
- [ ] 新增 `ContextSelector`
  - 函数式接口，首批只支持注册时注入

验证：

```bash
rtk mvn -pl chain compile -q
```

### Step 2：扩展 `ToolRegistry`

- [ ] 新增 `registerSubAgent(String name, String description)`
- [ ] 新增子代理定义存储结构
- [ ] 新增查询方法：
  - `hasSubAgent(name)`
  - `getSubAgent(name)`
  - `getDirectSubAgentTools()`
  - `getDelegateSubAgentTool()`
  - `getRegularTools()` 或等价私有拆分
- [ ] 保持现有 `register/scan/execute/getTools()` 不破坏
- [ ] 让 `delegate` tool 的 `agentName` schema 使用 enum

重点约束：
- `getTools()` 的默认行为要保持兼容
- 不把“暴露模式状态”塞成 `ToolRegistry` 全局可变开关

验证：

```bash
rtk mvn -pl chain test -Dtest=ToolRegistryTest -q
```

必要时新增：
- `SubAgentToolRegistryTest`

### Step 3：扩展 `Agent.Builder`

- [ ] Builder 新增 `SubAgentExposureMode subAgentExposureMode` 字段
- [ ] 新增 `subAgentExposureMode(SubAgentExposureMode mode)` 方法
- [ ] 默认值设为 `DIRECT`
- [ ] `Agent` 构造时保存该字段

验证：

```bash
rtk mvn -pl chain compile -q
```

### Step 4：改造 `Agent` 的工具暴露逻辑

- [ ] 把 `doRunWithLoop(...)` 中的 `toolRegistry.getTools()` 改成按暴露模式解析
- [ ] `DIRECT` 模式：普通工具 + 独立子代理 tool
- [ ] `DELEGATE` 模式：普通工具 + 一个通用 delegate tool

验证：

- 新增测试断言父 Agent 看到的 tool 名单

### Step 5：改造 `safeExecute(...)` 的上下文签名

当前 `safeExecute(...)` 只拿：
- `ToolCall`
- `assistantMessage`
- `traceId`

MVP 需要父消息快照，因此改成：

```java
safeExecute(tc, assistantMessage, parentMessages, traceId)
```

- [ ] 串行路径传当前 `messages`
- [ ] 并行路径传 `List.copyOf(messages)` 或等价只读快照
- [ ] 确保普通工具路径行为不变

验证：

```bash
rtk mvn -pl chain test -Dtest=AgentTest -q
```

### Step 6：实现子代理执行分流

- [ ] 在 `safeExecute(...)` 或其下游新增：
  - 普通工具执行路径
  - 独立子代理路径
  - 通用 delegate 路径
- [ ] `delegate_to_subagent` 解析 `agentName` + `task`
- [ ] 统一落到 `executeSubAgentTool(...)`

关键点：
- 非 `Agent` 自动循环直接 `execute` 子代理时要 fail-fast
- 错误信息必须是清晰中文

### Step 7：实现上下文裁剪与子代理运行

- [ ] 提供默认 `ContextSelector`
- [ ] 支持子代理注册时注入覆盖策略
- [ ] 构造子代理消息：
  - 子代理 `systemPrompt`
  - 裁剪父上下文
  - `Message.user(task)`
- [ ] 动态构造子代理 `Agent`
  - `LLM`：默认继承父级，可覆盖
  - `toolRegistry`：默认独立，可为空
  - `maxIterations`：独立配置，默认回退框架默认值
  - `before/after`：只装子代理自己的拦截器

验证：

- 子代理拿到父上下文但不拿父 `systemPrompt`
- `llmVisible=false` 消息不进入子代理上下文

### Step 8：处理错误、拦截器与 callback 语义

- [ ] 父拦截器继续包裹外层子代理 tool
- [ ] 子拦截器仅包裹子代理内部工具
- [ ] 父/子 `callback/trace` 默认隔离
- [ ] 子代理整体失败：
  - 外层发 `ToolErrorEvent`
  - 仍返回错误文本给父 Agent

重点回归：
- 不能破坏现有普通工具 `ToolStart/ToolComplete/ToolError` 触发次数
- 子代理不应把内部事件透出到父 `AgentEvent`

### Step 9：并行语义回归

- [ ] 同一轮多个子代理可并行
- [ ] 并行结果按原始 tool call 顺序回灌
- [ ] 子代理内部如再有工具调用，不影响父层组装顺序

验证：

- 新增并行子代理测试

### Step 10：示例与文档

- [ ] 新增 `chain-example` 示例：
  - `DIRECT` 模式
  - `DELEGATE` 模式
- [ ] 更新 `README.md` 或相关 docs：
  - SubAgent 能力介绍
  - 仅支持 `Agent` 自动循环
  - 首批只支持 fluent API 注册

## 3. 测试清单

建议新增或扩展以下测试：

- [ ] `ToolRegistryTest`
  - `registerSubAgent(...)` 基本注册
  - 独立子代理 tool schema 只含 `task`
  - delegate tool schema 含 `agentName enum + task`
- [ ] `AgentTest`
  - `DIRECT` 模式能调用独立子代理
  - `DELEGATE` 模式只暴露 delegate tool
  - 子代理默认继承父 `LLM`
  - 子代理覆盖 `LLM` 生效
  - 子代理失败外层记 `ToolErrorEvent` 但父循环继续
  - 多个子代理并行执行
- [ ] 新增 `SubAgentTest` 或 `SubAgentAgentTest`
  - 上下文裁剪默认行为
  - 不传父 `systemPrompt`
  - 默认排除 `llmVisible=false`
  - fail-fast：非 Agent 自动循环误用
- [ ] 若需要，扩展 `ChainCallbackTest`
  - 验证父/子 callback 隔离

推荐执行：

```bash
rtk mvn -pl chain test -q
rtk mvn -pl chain-example compile -q
```

## 4. 风险点

### 高风险

- `Agent.safeExecute(...)`
  - 这是现有工具执行的核心路径
  - 改签名和分流时最容易回归

- `ToolRegistry.getTools()` 附近逻辑
  - 现有文档/示例/测试大量依赖它
  - 不能因为引入 SubAgent 而破坏普通工具列表行为

### 中风险

- 子代理上下文裁剪
  - 若默认策略过激，子代理拿不到关键信息
  - 若默认策略过宽，又会损失 token 边界

- delegate 模式 schema
  - `agentName` enum 生成需与已注册子代理保持同步

### 低风险

- 新增值对象/枚举/Builder
  - 主要是 API 设计与测试覆盖问题

## 5. 回滚点

- 若 `Agent` 执行层回归：
  - 回滚 `Agent.java`
  - 保留 `ToolRegistry` 注册层类型也无害

- 若 `ToolRegistry` 注册层设计不理想：
  - 回滚 `SubAgentRegistration` 与相关存储结构
  - 普通工具语义应不受影响

- 若 `DELEGATE` 模式问题多：
  - 可先局部关闭 delegate tool 暴露
  - 保留 `DIRECT` 模式作为主路径

## 6. Review Gate

进入实现前应确认：

- [ ] `prd.md` 中的 MVP 边界与默认值已被接受
- [ ] `design.md` 中的双层职责划分被接受：
  - 注册在 `ToolRegistry`
  - 模式选择在 `Agent.Builder`
- [ ] 可以接受首批只支持 `Agent` 自动循环
- [ ] 可以接受首批不支持递归委派、不展开子代理内部事件、不支持运行时逐次覆盖上下文

只有以上 review 通过，才执行：

```bash
rtk python3 ./.trellis/scripts/task.py start .trellis/tasks/06-27-subagent-upgrade-research
```
