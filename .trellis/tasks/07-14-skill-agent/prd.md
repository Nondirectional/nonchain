# Skill 机制(顶层 Agent):MVP

> 父任务: `07-14-skill-system`(grill 总览共识见父 PRD)
> 这是 skill 系统的 MVP 主体,承接父任务决策 D1-D12 的全部实现。

## Goal

在 nonchain 顶层 Agent 上落地 skill 系统:LLM 通过 tool-calling 点选 skill,skill 内容作为
system 消息注入对话上下文(PERSISTENT 常驻)。skill 走独立 `SkillRegistry`,与 ToolRegistry
在执行路径上隔离,但在 LLM schema 上拼接。

## 前置验证(必须实现前先跑)

### V1. system 消息中途插入的 provider 兼容性

- **验证对象:** DashScope、OpenAI 兼容(vLLM)
- **验证内容:** 对话进行到第 N 轮时,追加一条 `role=system` 消息,该消息是否被 provider
  正确识别为行为指令(LLM 在后续轮次表现出遵循该指令的行为)?
- **可能的坑:** 部分 provider / 老模型对"非首位 system 消息"处理不一致(可能忽略、可能报错)。
- **判定与 fallback:**
  - 两家都正常 → D4 终值 = system 消息注入
  - 任一家异常 → D4 终值 = user 消息注入(带 `[Skill: {name}]\n` 前缀),并记录 provider 差异
- **产出:** 验证结论写入 design.md 的"注入位置"章节,作为最终实现依据。

---

## Requirements

### R1. 新增 com.non.chain.skill 包

独立顶层包,与 tool/agent/memory 平起平坐(D10)。

### R2. SkillDefinition 值对象

- 字段:`name`、`description`、`content`(静态 String)
- Builder 模式:`SkillDefinition.builder().name(...).description(...).content(...).build()`
- `name` / `description` / `content` 均必填,空值 fail-fast
- 不可变值对象(与现有 Message / Tool 风格一致)

### R3. SkillRegistry 注册中心

- 类比 ToolRegistry,但只存 skill,不执行
- `register(SkillDefinition)` 和 fluent `register(name, description).content(...).build()` 双入口
  (与 ToolRegistry 的 register + scan 双入口对称)
- `getSkillTools()`:把每个 skill 转成**无参数** `Tool`(只有 name + description,无 properties)
- `contains(name)` / `get(name)` / `skillNames()` 查询方法
- 注册顺序保留(LinkedHashMap,与 ToolRegistry 的 subAgents 一致)

### R4. Agent.Builder.skillRegistry(SkillRegistry)

- 与 `Agent.builder(llm, toolRegistry)` 对称的第二注册中心入口
- 默认 null(无 skill 时 Agent 行为与现状完全一致)

### R5. Agent 循环:LLM schema 拼接

- 每轮 LLM 调用前,把 `SkillRegistry.getSkillTools()` 与 `ToolRegistry` 产出的 tools 拼接
  (含 regular tools + sub-agent exposure tools + control tools),一起喂给 LLM
- skill 无参数,LLM 点选时 toolCall 无 arguments

### R6. Agent 循环:dispatchExecute 加 skill 分支

- 在现有五路分叉前增加 skill 判断(`skillRegistry != null && skillRegistry.contains(name)`)
- skill 分支逻辑:取出 `SkillDefinition.content()` → 构造 `Message.system(content)` →
  追加到对话 messages → 进入下一轮 LLM 推理(不产生 tool result 消息,因为走 system 注入而非
  tool result 路径——**此点需在 design.md 明确消息序列**)
- skill 激活后不进入 `executor` 并行执行(skill 注入是纯内存操作,无并发需求)

### R7. 注入消息序列(D4 实现依据,取决于 V1 验证)

**主方案(system 注入,V1 通过时):**
- skill 被 LLM 点选 → 追加 `assistantWithToolCalls`(记录这次 toolCall,保持 tool-calling 协议
  完整性)→ 追加 `Message.system(content)` → 进入下一轮 LLM 推理
- ⚠️ 注意:tool-calling 协议通常要求 assistant 的 toolCall 后跟一条 tool role 的 result。
  system 注入与 tool-calling 协议的衔接方式需在 design.md 明确——可能需要一条空的 tool
  acknowledge + 一条 system 注入,或研究 provider 对"toolCall 后跟非 tool result"的容忍度。

**fallback 方案(user 注入,V1 不通过时):**
- 同上,但注入为 `Message.user("[Skill: {name}]\n" + content)`

### R8. AgentEvent.SkillActivated 事件

- 新增 `SkillActivated` 事件类型进 AgentEvent(参考 SubAgent 的 Spawned/Started 等)
- 事件携带:skill name、注入的 content(或其摘要/长度)、轮次信息
- 在 skill 分支执行时触发

### R9. trace span

- skill 激活记一个 span(参考现有 tool span 的结构)
- span 含:skill name、content 长度、注入位置(system/user)

### R10. Agent.build() 命名冲突校验(D12)

- build() 时遍历:tool names + sub-agent names + 框架保留名
  (`delegate_to_subagent` / `get_subagent_result` / `steer_subagent`) + skill names
- 任一两两冲突 → fail-fast 抛 IllegalStateException,提示具体冲突的名字对
- 仅在 `skillRegistry != null` 时执行此校验

---

## Acceptance Criteria

- [ ] V1 验证结论产出,D4 注入位置终值确定并写入 design.md
- [ ] `com.non.chain.skill` 包建立,含 SkillDefinition + SkillRegistry
- [ ] `Agent.builder(llm, toolRegistry).skillRegistry(sr).build()` 可用
- [ ] 无 skillRegistry 时,Agent 行为与 0.10.0 完全一致(回归零破坏)
- [ ] LLM 能在 function 列表里看到 skill(无参数 function),点选后内容注入对话
- [ ] 注入消息序列经 V1 验证的 provider 测试通过
- [ ] SkillActivated 事件能被 callback 捕获
- [ ] trace 树能看到 skill 激活 span
- [ ] skill 名与 tool/sub-agent/保留名冲突时,build() fail-fast
- [ ] chain-example 模块新增 skill 用例 demo
- [ ] 现有测试全部通过,新增 skill 相关单元测试

## Out of Scope(留 v2 / skill-subagent 子任务)

- SubAgent skill 预加载(D13)→ skill-subagent 子任务
- EPHEMERAL 生命周期 → skill-subagent 子任务
- 动态 content(`Supplier<String>`)→ skill-subagent 子任务
- 外部文件加载(`SkillLoader` 扫 `*.md`)→ skill-subagent 子任务

## Notes

- 本任务为复杂任务,需补 design.md(含 V1 验证结论 + R7 消息序列设计)和 implement.md 后再 start。
- R7 的消息序列是本任务最微妙的设计点——skill 走 system 注入但寻址走 tool-calling,两者协议
  衔接需在 design.md 仔细论证。
