# Implement — Skill 机制(顶层 Agent):MVP

> 依赖 `prd.md`(D1-D12)与 `design.md`。本文件描述实现顺序、验证命令、风险点与 review gate。
> 核心原则:**零回归**——每一步都要保证不引入 skill 能力的 Agent 与 0.10.0 行为完全一致。

## 0. 实现策略

按"前置验证 → 数据结构 → 注册接入 → 循环接入(schema + dispatch)→ 观测 → 校验 → 测试文档"
分层推进。每一步独立可编译/可测试,降低 Agent.java 改动的风险。

无 skill 的 Agent 作为回归基准贯穿始终(skillRegistry 默认 null,所有 skill 路径跳过)。

---

## Step 0:前置验证 V1(system 注入 provider 兼容性)

**必须在写任何代码前完成。** 结果决定 design.md §4.4 的注入位置终值。

- [ ] 写一个临时最小测试(可放 `chain/src/test/.../V1SkillInjectionCompatTest.java` 或直接手写
      `main` 方法),用 DashScopeLLM 或 mock LLM 构造如下对话序列:
      1. `Message.system("你是助手")`
      2. `Message.user("开始")`
      3. `Message.assistant("好的")`
      4. `Message.user("继续")`
      5. **`Message.system("# 某技能指令\n你必须用 JSON 格式回答")`** ← 中途插入的 system
      6. 调用 LLM,观察返回是否报错、LLM 是否遵循"JSON 格式"指令
- [ ] 在 DashScope(qwen)上跑通
- [ ] 在 vLLM / OpenAI 兼容上跑通
- [ ] **判定:**
  - 两家都正常 → 注入用 `Message.system(content)`,design §4 不变
  - 任一异常 → 注入改 `Message.user("[Skill: {name}]\n" + content)`,记录到 design §4.4
- [ ] 删除临时测试文件,结论写入 design.md §4.4 的"验证结论"小节

验证命令(临时测试):

```bash
rtk mvn -pl chain test -Dtest=V1SkillInjectionCompatTest -q
# 或直接跑 main 方法
```

---

## Step 1:核心数据结构(com.non.chain.skill 包)

新增基础类型,无逻辑依赖,仅编译验证。

- [ ] 新增 `SkillDefinition` 不可变值对象 + Builder
  - `chain/src/main/java/com/non/chain/skill/SkillDefinition.java`
  - 字段:`name` / `description` / `content`(均 String,final)
  - 私有全参构造 + `Builder` 内部类
  - `name()` / `description()` / `content()` accessor
  - Builder.build() 校验三项非空非 blank,空值抛 `IllegalArgumentException`
- [ ] 新增 `SkillRegistry` 注册中心
  - `chain/src/main/java/com/non/chain/skill/SkillRegistry.java`
  - `Map<String, SkillDefinition> skills`(LinkedHashMap + synchronized,保序 + 线程安全)
  - `register(String name, String description)` → 返回 `SkillRegistration`(fluent 入口)
  - `register(SkillDefinition)` → 值对象直传入口
  - `contains(name)` / `get(name)` / `skillNames()` 查询方法
  - `getSkillTools()` → 每个 skill 转无参数 `Tool`(description 加 `[Skill]` 前缀)
- [ ] 新增 `SkillRegistration` fluent Builder(作为 SkillRegistry 内部类,对称
      `ToolRegistry.Registration`)
  - `content(String)` 方法
  - `build()` 校验 content 非空 → 构造 SkillDefinition → 写入 registry

验证:

```bash
rtk mvn -pl chain compile -q
```

新增测试 `SkillDefinitionTest`:
- Builder 正常构造
- name/description/content 空值抛异常

新增测试 `SkillRegistryTest`:
- fluent register + build
- 值对象 register
- getSkillTools() 产出无参数 Tool(name/description 对,properties 空)
- getSkillTools() description 带 `[Skill]` 前缀
- contains / get / skillNames

```bash
rtk mvn -pl chain test -Dtest=SkillDefinitionTest,SkillRegistryTest -q
```

---

## Step 2:Agent.Builder 接入 skillRegistry

仅 Builder 字段 + 构造传递,不改循环逻辑。无 skill 时行为不变。

- [ ] `Agent` 新增字段 `private final SkillRegistry skillRegistry`(默认 null)
- [ ] `Agent` 构造函数新增 `this.skillRegistry = builder.skillRegistry;`
- [ ] `Agent.Builder` 新增字段 `private SkillRegistry skillRegistry;`
- [ ] `Agent.Builder` 新增方法 `public Builder skillRegistry(SkillRegistry sr)`
- [ ] **回归验证:** 不调 `.skillRegistry()` 的 Agent,skillRegistry 字段为 null

验证:

```bash
rtk mvn -pl chain compile -q
rtk mvn -pl chain test -Dtest=AgentTest -q
```

---

## Step 3:LLM schema 拼接(design §6)

让 LLM 能"看到" skill function。这一步后,LLM 会尝试点选 skill,但 dispatch 还没接,会报错——
所以这步和 Step 4 必须连续完成,中间不单独验证功能(只验证编译 + 现有回归)。

- [ ] 修改 `Agent.resolveToolsForCurrentExposureMode()`(`Agent.java:797-808`)
  - 末尾追加:`if (skillRegistry != null) { tools.addAll(skillRegistry.getSkillTools()); }`
- [ ] **回归验证:** skillRegistry == null 时,tools 列表与 0.10.0 完全一致

验证:

```bash
rtk mvn -pl chain compile -q
rtk mvn -pl chain test -Dtest=AgentTest -q
```

---

## Step 4:dispatchExecute skill 分支(design §4-§5,本任务核心)

引入 `DispatchResult` + 第六路 skill 分支 + 双消息注入。

### 4a. DispatchResult 内部类

- [ ] `Agent` 新增私有静态内部类 `DispatchResult`
  - `final String toolResultText`
  - `final List<Message> extraMessages`(skill 注入的额外消息;空 list = 无)
  - 工厂:`static of(String text)` / `static of(String text, List<Message> extra)`

### 4b. dispatchExecute 签名改造

- [ ] `dispatchExecute` 返回类型从 `String` 改为 `DispatchResult`
- [ ] 现有五路的 `return` 全部包成 `DispatchResult.of(...)`:
  - 第 1 路(子代理 spawn/前台):`return DispatchResult.of(bgManager.spawn(...))` /
    `DispatchResult.of(executeSubAgentTool(...))`
  - 第 2 路(delegate):同上
  - 第 3 路(get_result):`return DispatchResult.of(bgManager.getResult(...))`
  - 第 4 路(steer):`return DispatchResult.of(bgManager.steer(...))`
  - 第 5 路(普通工具):`return DispatchResult.of(toolRegistry.execute(...))`

### 4c. skill 分支前置(design §5.1)

- [ ] dispatchExecute 开头(skillRegistry 判断前置):
  ```java
  if (skillRegistry != null && skillRegistry.contains(tc.name())) {
      return executeSkill(tc);
  }
  ```

### 4d. executeSkill 实现(design §5.2)

- [ ] 新增 `private DispatchResult executeSkill(ToolCall tc)`:
  - 取 `SkillDefinition` content
  - 触发 `AgentEvent.SkillActivated`(若 eventConsumer != null)
  - 构造 toolResultText:`"(skill " + name + " 已加载,详见系统指令)"`
  - 构造注入消息:`Message.system(content)`(若 V1 fallback:`Message.user(...)`)
  - 返回 `DispatchResult.of(toolResultText, List.of(injection))`

### 4e. 主循环消费 DispatchResult(design §4.2)

修改 `doRunWithLoop` 的两个执行路径(`Agent.java:366-409`):

- [ ] **并行路径(line 379):** `executeWithToolSpan` 内部调 `dispatchExecute` 改为返回
      `DispatchResult`;主循环 `allOf().join()` 后:
  - `messages.add(Message.toolResult(tc.id(), futures[i].get().toolResultText))`
  - `messages.addAll(futures[i].get().extraMessages)`
  - ⚠️ 注意:`executeWithToolSpan` 签名也要从 `String` 改 `DispatchResult`,它内部调
        `dispatchExecute`。并行路径的 `whenComplete` 回调里 output 类型也跟着变。
- [ ] **串行路径(line 399-408):**
  - `DispatchResult dr = executeWithToolSpan(tc, ...)`
  - `messages.add(Message.toolResult(tc.id(), dr.toolResultText))`
  - `messages.addAll(dr.extraMessages)`

验证:

```bash
rtk mvn -pl chain compile -q
rtk mvn -pl chain test -Dtest=AgentTest,SubAgentTest -q
```

⚠️ 这步改动最大,AgentTest + SubAgentTest 必须全绿(零回归)。如有失败,大概率是
DispatchResult 包装遗漏某一路。

---

## Step 5:AgentEvent.SkillActivated 事件(design §3.4)

- [ ] `AgentEvent.java` 新增内部 class `SkillActivated implements AgentEvent`
  - 字段:`skillName`(String)/ `contentLength`(int)
  - accessor + 构造函数
- [ ] Step 4d 的 `executeSkill` 里已触发该事件,这步只补 class 定义

验证:

```bash
rtk mvn -pl chain compile -q
```

---

## Step 6:trace span(design §5.3)

- [ ] `executeSkill` 内包一层 span(若 tracer != null):
  ```java
  Tracer.ScopedSpan span = tracer != null
      ? tracer.startSpan(SpanAttributes.SpanType.TOOL, "skill:" + def.name()) : null;
  try {
      // ... 注入逻辑 ...
  } finally {
      if (span != null) span.close();
  }
  ```
  - 复用 `SpanType.TOOL`(design §5.3 已决定不新增枚举,span name 区分)
- [ ] span attribute 记录 skill name + content length(复用 SpanAttributes.TOOL_NAME 等)

验证:

```bash
rtk mvn -pl chain compile -q
rtk mvn -pl chain test -Dtest=AgentTraceTest -q
```

---

## Step 7:命名冲突校验(design §7, D12)

- [ ] `Agent.Builder.build()` 末尾(skillRegistry != null 时)调用 `validateSkillNaming`
- [ ] 实现 `validateSkillNaming(SkillRegistry, ToolRegistry)`:
  - 收集:regular tool names + subAgentNames + 三个保留名
  - 遍历 skillNames,任一命中集合 → `IllegalStateException`
- [ ] **回归验证:** 无 skill 时不执行校验,build 行为不变

验证:

```bash
rtk mvn -pl chain compile -q
rtk mvn -pl chain test -Dtest=AgentTest -q
```

新增测试(放 Step 8 一起)。

---

## Step 8:集成测试

- [ ] `AgentSkillTest.java`(`chain/src/test/java/com/non/chain/agent/`)
  - **基础流:** mock LLM 第一轮返回 skill toolCall → 验证对话里出现 system 注入消息 +
    tool result → 第二轮 LLM 收到注入后正常响应
  - **无 skill 回归:** 不挂 skillRegistry 的 Agent,messages 序列与 0.10.0 一致
  - **命名冲突:** skill 名 == tool 名时 build() 抛异常
  - **命名冲突:** skill 名 == sub-agent 名时 build() 抛异常
  - **命名冲突:** skill 名 == 保留名(delegate_to_subagent 等)时 build() 抛异常
  - **SkillActivated 事件:** eventConsumer 捕获到 SkillActivated,skillName/contentLength 正确
  - **多 skill 叠加:** LLM 连续点两个 skill,两条 system 消息共存(PERSISTENT)
  - **无参数 function schema:** getSkillTools 产出的 Tool.toFunctionDefinition() 无 properties

```bash
rtk mvn -pl chain test -Dtest=AgentSkillTest -q
```

---

## Step 9:demo 与文档

- [ ] `chain-example` 新增 skill 用例(类比现有 example 结构)
  - 演示:注册一个 skill(如"代码审查流程")→ Agent 挂载 → 用户提问触发 LLM 点选 → 观察
    system 注入生效
- [ ] README skill 章节(若 README 有按能力分章节的结构,新增 skill 段)
- [ ] `docs/skill.md`(若有 per-area docs,新增)
- [ ] CHANGELOG 记录

```bash
rtk mvn -pl chain-example compile -q
```

---

## 风险点

| 风险 | 缓解 |
|---|---|
| **V1 验证不通过**(provider 不接受中途 system) | design §4.4 fallback 到 user 注入,改动局部化(一行) |
| **DispatchResult 改造遗漏某一路** | Step 4 必须跑 AgentTest + SubAgentTest 全绿;并行/串行两路径都改 |
| **并行路径的 CompletableFuture 泛型变化** | `String` → `DispatchResult`,whenComplete 回调的 output 类型跟着变,注意 |
| **LLM 不点选 skill**(召回问题) | 这是 D2 固有代价,非 bug;demo 里 description 写清楚触发条件即可 |
| **skill 注入消息污染 ChatMemory**(PERSISTENT 累积) | MVP 可接受(design §9);累积严重时应用层控 skill 数量,根治待 v2 EPHEMERAL |

## Review Gate

实现完成后的检查清单(对应 `trellis-check`):

- [ ] Step 0 V1 验证结论已写入 design.md
- [ ] 全量测试通过:`rtk mvn -pl chain test -q`
- [ ] 无 skill 的 Agent 与 0.10.0 行为一致(零回归)
- [ ] AgentSkillTest 覆盖:基础流 / 无 skill 回归 / 三类命名冲突 / 事件 / 多 skill 叠加
- [ ] demo 可运行
- [ ] design.md §4.4 的注入位置终值与代码一致(system 或 user fallback)
- [ ] com.non.chain.skill 包结构与 design §10 一致
