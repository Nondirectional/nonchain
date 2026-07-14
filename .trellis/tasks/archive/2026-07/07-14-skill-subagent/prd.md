# Skill 机制(SubAgent 预加载 + 延后项):v2

> 父任务: `07-14-skill-system`(grill 总览共识见父 PRD)
> 前置依赖: `07-14-skill-agent`(MVP 必须先落地,本任务在其基础上扩展)

## 进度

- [x] **第一块 SubAgent skill 预加载(D13)— 已完成(2026-07-14)**
  - SubAgentDefinition 加 skillRegistry 字段 + accessor
  - SubAgentRegistration 加 `.skillRegistry()` Builder 方法
  - runSubAgentInternal 委派构造时传递 skillRegistry
  - 子 agent 命名校验自动复用顶层 validateSkillNaming
  - 测试:SubAgentSkillTest 3/3 全绿(点选注入 + 命名冲突 + 零回归)
- [ ] 第二块延后项(EPHEMERAL / 动态 content / SkillLoader)— 未开始,仍 deferred

## Goal

分两块扩展 skill 系统:

1. **填 D13 的坑:** 让 SubAgent 也能挂 skill——`SubAgentDefinition` 加 `skillRegistry` 字段,
   委派构造子 agent 时传入。这正是 SubAgent 重做 PRD 裁剪清单 D13 (`skill 预加载 | nonchain 无技能系统`)
   当初留下的坑。**(✅ 已完成)**
2. **落地 MVP 砍掉的延后项:** EPHEMERAL 生命周期、动态 content、外部文件加载。**(⏳ deferred)**

## Requirements

### 第一块:SubAgent skill 预加载(D13)

#### R1. SubAgentDefinition 加 skillRegistry 字段

- `SubAgentRegistration.skillRegistry(SkillRegistry)` Builder 方法
- 委派构造子 agent 时,把 skillRegistry 传入子 Agent.Builder
- 子 agent 的 LLM schema 拼接、dispatchExecute skill 分支复用 skill-agent MVP 的实现

#### R2. 委派时的 skill 注入语义

- 子 agent 启动时,skill 通过 tool-calling 点选(与顶层 Agent 一致),不预加载到 systemPrompt
  (除非应用层显式把 skill content 拼进 systemPrompt——那是应用选择,非框架行为)
- "预加载"在本框架语义下 = "子 agent 配备了 skillRegistry,LLM 可按需点选",而非"启动即注入全部"

#### R3. 命名冲突校验扩展

- D10 fail-fast 已禁止子 agent 嵌套委派(子 agent 的 toolRegistry 不能含 subAgent)
- skill 不在此限——子 agent 的 skillRegistry 与子 agent 的 toolRegistry 各自独立,但
  build() / 委派构造时需校验子 agent 范围内 skill 名 vs tool 名 vs 保留名互斥

---

### 第二块:MVP 砍掉的延后项

#### R4. EPHEMERAL 生命周期(D5 砍掉项)

- `SkillDefinition.lifecycle(PERSISTENT | EPHEMERAL)`,默认 PERSISTENT
- EPHEMERAL 语义:skill 注入只在"点选→下一轮 LLM 调用"这一次生效,之后从对话移除
- **三层难点(必须在 design.md 论证解决方案):**
  1. **识别:** 怎么标记"上一轮注入的 skill system 消息"。`Message.kind` 已被应用层消息占用,
     需新的识别机制(如注入时记录消息 list index,或引入新的 kind 约定 `"skill-ephemeral:{name}"`)
  2. **时机:** Agent 循环结构从纯 append 变为"remove ephemeral skills → append user →
     call LLM → ...",新增清理步骤
  3. **ChatMemory 边界:** 注入的 system 消息绝不能进 ChatMemory 持久化。需在"进 LLM 上下文"
     和"进 ChatMemory 持久化"两个通道间做分离——nonchain 目前没这个分离,是本块最大的新机制
- **建议:** 与 SubAgent 的 `ContextSelector`(上下文裁剪)统一设计,而非 skill 系统自己扛

#### R5. 动态 content(D6 砍掉项)

- `SkillDefinition.content(Supplier<String>)` 或 `Function<SkillContext, String>`
- 每次激活时调用 Supplier 生成 content
- 风险:可变内容 → 不可缓存、可能失败、可能有副作用,与"纯知识注入"有张力
- 需在 design.md 论证:什么场景真的需要动态(否则静态 + tool 配合已够)

#### R6. 外部文件加载 SkillLoader(D6 砍掉项)

- `SkillLoader` 从 classpath / 文件系统扫描 `*.md` 文件,每个文件解析为一个 SkillDefinition
- 文件格式约定:frontmatter(name + description)+ 正文(content)
- 加载与定义解耦:`SkillLoader` 产出 `List<SkillDefinition>`,喂给 `SkillRegistry`

---

## Acceptance Criteria

### 第一块(SubAgent)
- [ ] `SubAgentRegistration.skillRegistry(sr)` 可用,委派构造的子 agent 能点选 skill
- [ ] D13 坑填上:SubAgent 重做的"skill 预加载"不再是空缺
- [ ] 子 agent 范围内命名冲突校验通过

### 第二块(延后项)
- [ ] EPHEMERAL 生命周期可用,多轮对话下不堆积 system 消息
- [ ] EPHEMERAL 注入消息不污染 ChatMemory
- [ ] 动态 content 可用(若 design 论证有真实需求)
- [ ] SkillLoader 能从目录扫描 `*.md` 加载 skill

## Out of Scope

- 嵌套 skill 委派(子 agent 的 skill 再委派给孙 agent)——D10 已禁止嵌套委派,skill 同理
- skill 版本管理 / 热更新——非 embedded SDK 关注点

## Notes

- 本任务依赖 skill-agent MVP 落地后才能开始,PRD/design/implement 应在 MVP 完成后基于实际
  实现刷新(避免空中设计)。
- EPHEMERAL(R4)是本任务最复杂的部分,design.md 必须先论证三层难点的方案再实现。
