# Skill 系统:过程性知识注入机制

## Goal

为 nonchain 引入 **skill** 概念:过程性知识/指令文本,LLM 通过 tool-calling 点选,内容作为
system 消息注入 Agent 上下文。skill 本身不含可执行工具——它是知识/指令层的东西,改变 Agent 的
行为方式,而非执行有副作用的动作。

**填的坑:** SubAgent 重做(0.10.0)的裁剪清单 D13 明确写了 `skill 预加载 | nonchain 无技能系统`。
当时刻意不做,留了这个坑。本任务(树)填它。

**推进策略:** 分两批——顶层 Agent skill(MVP)→ SubAgent skill 预加载(v2),由两个子任务承接。

---

## 已定决策(Grill 记录,2026-07-14)

### D1. skill 本质 = 过程性知识/指令文本

- **决策:** skill 是"怎么做某事"的过程性知识,在相关时注入上下文,本身不含可执行工具。
- **理由:** 填补 D13 的坑;ZCode/Claude 的 skill 机制都验证了这条路。
- **状态:** ✅ 已确认

### D2. 触发机制 = 模型自选(LLM-as-router)

- **决策:** skill 的 `description` 暴露给 LLM,LLM 像选 tool 一样点选 skill,点中后内容注入。
- **理由:** 复用 nonchain 已成熟的 tool-calling 循环;召回质量靠用户写好 description,框架不兜底。
- **代价:** skill 在 LLM 眼里会长得像一个 function(寻址通道只有 tool-calling),但它"调用"后
  只产生知识注入,无副作用。
- **状态:** ✅ 已确认

### D3. 执行路径 = 独立 SkillRegistry

- **决策:** skill 不进 ToolRegistry,有自己的 `SkillRegistry`;`dispatchExecute` 增加一条
  case 识别 skill 调用。
- **理由:** 语义干净——tool 是"做事的",skill 是"供知识的",两者在框架里泾渭分明。接受给
  `dispatchExecute`(已是五路分叉)加第六路的代价。
- **状态:** ✅ 已确认

### D4. 注入位置 = system 消息

- **决策:** skill 内容作为 `Message.system()` 追加到对话。LLM 当作持续生效的行为指令。
- **理由:** 语义最正确;`Message.system()` 现成;与 `Message.note()` 的分层哲学一致。
- **⚠️ 待验证(provider 兼容性):** 对话中途插入 system 消息,DashScope / OpenAI 兼容(vLLM)
  行为需实测。若某 provider 忽略或报错,fallback 注入为 user 消息(带 `[Skill: xxx]` 前缀)。
  **此验证结果决定 D4 终值是 system 还是 user,必须在实现前先跑。**
- **状态:** ✅ 已确认(system 为默认,user 为 fallback)

### D5. 生命周期 = MVP 只做 PERSISTENT(常驻)

- **决策:** MVP 只实现 PERSISTENT——skill 注入后 system 消息常驻整轮对话。
- **砍掉项:** EPHEMERAL(本轮有效下轮清空)留 v2。它需引入"消息移除"这个 nonchain 头一次的
  非追加操作(识别 / 时机 / 与 ChatMemory 边界三层难点),复杂度约占整个 MVP 的 30-40%。
  v2 做 EPHEMERAL 时应与 SubAgent 的 `ContextSelector` 统一设计。
- **状态:** ✅ 已确认

### D6. content 形态 = 静态文本 + 纯代码定义

- **决策:** `content` 是静态 String,加载时定死;MVP 纯代码定义
  (`SkillDefinition.builder().name(...).content("...").build()`)。
- **砍掉项:** 动态生成(`Supplier<String>`)、外部文件加载(`SkillLoader` 扫 `*.md`)均留 v2。
- **状态:** ✅ 已确认

### D7. 作用域 = MVP 只做顶层 Agent,设计预留 SubAgent

- **决策:** MVP 只通过 `Agent.Builder.skillRegistry(...)` 挂顶层;SubAgent 不碰,
  `SubAgentDefinition` 不动。但 `SkillRegistry` 必须是独立可传递对象(像 ToolRegistry),v2
  接入 SubAgent 时是"传递"而非"重构"。
- **理由:** SubAgent 重做刚落地,不宜立刻再动;先在顶层验证设计正确性。
- **状态:** ✅ 已确认

### D8. schema 拼接 = SkillRegistry.getSkillTools() + Agent 循环拼接

- **决策:** `SkillRegistry.getSkillTools()` 把每个 skill 转成**无参数** function(只有
  name + description);Agent 循环拉取后与 `ToolRegistry.getTools()` 拼接喂 LLM。
  `dispatchExecute` 加 `skillRegistry.contains(name)` 判断,走 skill 注入路径。
- **理由:** 与 SubAgent "独立存储 + 转 Tool + Agent 路由"模式一致;每个 skill 是具名 function,
  LLM 一跳点选,召回质量最高。
- **状态:** ✅ 已确认

### D9. 横切机制 = callback + trace,不走 interceptor

- **决策:**
  - skill 激活发 `AgentEvent.SkillActivated`(进 AgentEvent,不进 ChainCallback 的新分组)
  - 记 trace span(让 trace 树能看到 skill 激活节点)
  - **不走** tool interceptor(`BeforeToolCall`/`AfterToolCall`)——skill 无副作用,不该进控制层
- **状态:** ✅ 已确认

### D10. 包结构 = com.non.chain.skill 独立顶层包

- **决策:** skill 代码住 `com.non.chain.skill`,与 tool/agent/memory 平起平坐。
- **状态:** ✅ 已确认

### D11. Builder API = .skillRegistry(SkillRegistry),与 toolRegistry 对称

- **决策:** `Agent.builder(llm, toolRegistry).skillRegistry(skillRegistry)`。单一入口,
  不提供 `skills(...)` 便捷方法(避免两条路径)。
- **状态:** ✅ 已确认

### D12. 命名防护 = Agent.build() 统一校验,fail-fast

- **决策:** `Agent.build()` 时检查 skill 名 vs tool 名 + sub-agent 名 + 框架保留名
  (`delegate_to_subagent`/`get_subagent_result`/`steer_subagent`)冲突,冲突抛异常。
- **状态:** ✅ 已确认

---

## 子任务分解

| 子任务 | Slug | 范围 | 优先级 |
|---|---|---|---|
| 1 | `07-14-skill-agent` | 顶层 Agent skill 机制 MVP(D1-D12 主体) | P2 |
| 2 | `07-14-skill-subagent` | SubAgent skill 预加载 + 延后项(v2) | P3 |

子任务 2 依赖子任务 1 落地。

---

## 实现时最需警惕的三点(父任务级,适用于整个 skill 体系)

1. **dispatchExecute 加分支后的顺序**:已是五路分叉(普通 tool / DIRECT sub-agent / DELEGATE /
   get_result / steer),skill 是第六路。skill 名与 tool 名已在 build 时校验互斥,skill 判断可前置。
2. **命名校验完整覆盖**:build() 校验要覆盖用户 tool 名 + sub-agent 名 + 框架保留名 + skill 名两两互斥。
3. **skill 注入消息与 ChatMemory 的边界**:注入的 system 消息属"框架运行时构造",非"用户对话历史"。
   若 Agent 配了 ChatMemory,注入消息在持久化时要被剥离。MVP PERSISTENT 也要注意,v2 EPHEMERAL
   会正面碰上。

## Notes

- 本父任务承载 grill 总览共识,不直接进入实现;实施细节落两个子任务的 PRD/design/implement。
- 子任务 1 的 PRD 含 D4 待验证项(provider 兼容性),实现前必须先跑验证。
