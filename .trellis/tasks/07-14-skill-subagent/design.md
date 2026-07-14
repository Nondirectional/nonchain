# Design — Skill 机制(SubAgent 预加载 + 延后项):v2

> ⚠️ **状态:占位 / 设计方向预登记。** 本任务依赖 `07-14-skill-agent`(MVP)落地后才能进入
> 严肃设计。当前文件只登记已知的设计方向和待解难点,**不展开实现级论证**(避免空中设计)。
> MVP 完成后,应基于实际实现刷新本文件。

## 依赖前置

- `07-14-skill-agent` 必须 completed。以下设计方向基于 MVP 的 `SkillRegistry` /
  `SkillDefinition` / `dispatchExecute` skill 分支已就位。

## 第一块:SubAgent skill 预加载(D13)—— 设计方向

### 已知:接入点机械、低风险

MVP 把 `SkillRegistry` 设计为独立可传递对象(D7),SubAgent 接入是"传递"而非"重构":

- `SubAgentDefinition` 加 `skillRegistry` 字段(nullable)
- `SubAgentRegistration.skillRegistry(SkillRegistry)` Builder 方法
- 委派构造子 agent 时(`Agent.runSubAgentInternal`),把 skillRegistry 传入 childBuilder
- 子 agent 的 schema 拼接、dispatchExecute skill 分支**零改动**——复用 MVP 实现

### 待 MVP 落地后确认

- 委派时子 agent 的命名冲突校验范围(子 agent 的 skill 名 vs 子 agent 的 tool 名)是否需要
  在父 Agent.build() 校验,还是延后到委派构造时
- D10 fail-fast(禁止子 agent 嵌套委派)与 skill 的交互:skill 不在嵌套禁止范围内,但需确认
  校验逻辑不误伤

## 第二块:EPHEMERAL 生命周期(D5 砍掉项)—— 待解难点

**这是 v2 最复杂的部分,三层难点必须在正式 design 里论证:**

### 难点 1:识别注入消息

`Message.kind` 已被应用层消息(`note`)占用。EPHEMERAL 注入的 system 消息需要新的识别机制:
- 候选 A:复用 `kind` 字段,约定 `"skill-ephemeral:{name}"` 前缀(但 kind 注释说"应用层消息"
  用,框架占用需改注释)
- 候选 B:注入时记录消息在 messages list 的 index,下一轮按 index 移除(index 在 append 后稳定)
- 候选 C:给 Message 新增一个框架内部标记字段(侵入值对象)

MVP 落地后,看 Message 在实际循环中的使用模式再定。

### 难点 2:循环时机

主循环结构从纯 append 变为"remove ephemeral → append → call LLM → ..."。需确认:
- 清理点在每轮 LLM 调用前(line 287-293 steer 检查点附近)
- 与 graceful / steer 检查点的顺序

### 难点 3:ChatMemory 边界(最硬的)

注入的 system 消息**绝不能进 ChatMemory 持久化**。但 nonchain 目前"进 LLM 上下文"和
"进 ChatMemory"是同一批 messages。需要分离两个通道:
- 候选:Agent 维护两个 list——`llmMessages`(含临时注入)+ `persistentMessages`(不含)
- 或:ChatMemoryStore 写入前过滤掉框架标记的临时消息

**建议:** 这块应与 SubAgent 的 `ContextSelector`(上下文裁剪)统一设计——两者本质都是
"哪些消息进 LLM 上下文 / 哪些持久化"的过滤问题。强行让 skill 系统自己扛会重复造轮子。

## 第三块:动态 content / SkillLoader —— 低风险延后项

- **动态 content**(`Supplier<String>`):`SkillDefinition` 加一个重载,content 接受 Supplier。
  executeSkill 调用时 `content.get()`。风险:失败处理、trace 记录可变内容。
- **SkillLoader**(扫 `*.md`):独立类,frontmatter 解析(name + description)+ 正文(content)。
  产出 `List<SkillDefinition>` 喂 registry。与框架核心解耦,可放最后做。

这两块在 MVP 落地后,设计成本很低,不必现在展开。

## 何时刷新本文件

当 `07-14-skill-agent` completed 后:
1. 基于实际 `SkillDefinition` / `SkillRegistry` / `dispatchExecute` 结构刷新第一块设计
2. 基于 MVP 的 Message 使用模式,展开第二块(EPHEMERAL)三层难点的正式方案
3. 第三块按需展开
