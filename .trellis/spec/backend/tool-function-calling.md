# Tool Function-Calling Contracts

> Executable contracts for the tool/function-calling subsystem: schema declaration ↔ parameter parsing ↔ type conversion must stay consistent across layers.

---

## Overview

The tool subsystem spans three layers that must agree on types:

1. **Schema declaration** — `Tool.toFunctionDefinition()` builds the JSON Schema sent to the LLM.
2. **Parameter parsing** — `ToolRegistry.parseArguments()` turns the LLM's JSON `arguments` string into a `Map<String, Object>`.
3. **Type conversion** — `ToolRegistry.convertType()` adapts parsed values to the Java target parameter types (annotation style); fluent style reads via `ToolArgs`.

A `ClassCastException` at `(List) args.get(...)` is almost always a **contract break** between layers 1 and 2: the schema declares a type the parser cannot produce (or vice versa).

---

## Scenario: Schema Type ↔ Parse Result ↔ Java Type

### 1. Scope / Trigger

- **Trigger**: any change to `Tool` schema generation, `parseArguments`, `convertType`, `javaTypeToJsonType`, or `@ToolParam`-based type inference. This is a cross-layer contract; code-spec depth is mandatory.

### 2. Signatures

```java
// Layer 1 — schema declaration (Tool.java)
FunctionDefinition toFunctionDefinition();
Tool.Builder addProperty(String name, String type, String description, boolean isRequired);            // scalar
Tool.Builder addProperty(String name, String type, String description, boolean isRequired, String itemsType); // array w/ items

// Layer 2 — parameter parsing (ToolRegistry.java, private)
Map<String, Object> parseArguments(String json);

// Layer 3 — type conversion (ToolRegistry.java, private)
Object convertType(Object value, Class<?> targetType);
String javaTypeToJsonType(Class<?> type);
String inferItemsType(Type genericType, Class<?> rawType);

// Fluent consumer (ToolArgs.java)
<T> T get(String name);  // unchecked cast — caller MUST know the JSON type
```

### 3. Contracts

The three layers are bound by this type mapping. **Never declare a schema type in layer 1 that layer 2 cannot parse**, or the unchecked cast in layer 3/fluent consumer will throw `ClassCastException`.

| JSON (LLM returns) | Schema `type` (layer 1) | Jackson parse result (layer 2) | Java target type (layer 3) |
|--------------------|------------------------|--------------------------------|----------------------------|
| `12` / `1.5`       | `number`               | `Integer` / `Double`           | `int`/`Integer`/`long`/`Long`/`double`/`Double` |
| `"abc"`            | `string`               | `String`                       | `String` |
| `true`             | `boolean`              | `Boolean`                      | `boolean`/`Boolean` |
| `null`             | (any)                  | `null`                         | nullable |
| `[12, 34]`         | `array` + `items`      | `ArrayList<Integer>`           | `List`/`Set`/Java array |
| `{"k":"v"}`        | `object`               | `LinkedHashMap`                | `Map` |

**Annotation-style array inference** (`inferItemsType`, zero `@ToolParam` change):
- `List<Integer>` / `Integer[]` → `items: {type: number}` (via `Method.getGenericParameterTypes()` → `ParameterizedType`)
- `String[]` / `List<String>` → `items: {type: string}`
- raw `List` (no generic) → `items: {type: string}` (fallback, never throws)
- `Map<K,V>` → `type: object`, **no `items`** (do not mis-infer items for Map)

### 4. Validation & Error Matrix

| Condition | Result |
|-----------|--------|
| `arguments` is `null` / blank | return empty `Map` (no throw) |
| `arguments` is syntactically invalid JSON | `IllegalArgumentException` "工具参数 JSON 解析失败: ..." (cause preserved) |
| `arguments` is valid JSON but not an object (e.g. `[1,2]`) | `IllegalArgumentException` "工具参数必须是 JSON 对象: ..." |
| `convertType` value cannot be coerced to target `List` | `IllegalArgumentException` "无法转换为 List: ..." |
| Unregistered tool name | `IllegalArgumentException` "未注册的工具: ..." |
| Reflective method.invoke failure | `RuntimeException` "工具执行失败: ..." (cause unwrapped) |

### 5. Good / Base / Bad Cases

- **Good** — annotation tool `@ToolParam List<Integer> points`, LLM returns `{"points":[12,34]}`: schema has `items`, parser yields `ArrayList`, `convertType` returns the list, `method.invoke` receives `List<Integer>`. No exception.
- **Base** — scalar `@ToolParam int n`, LLM returns `{"n":12}`: Jackson stores `Integer(12)`; `ToolArgs.getInt` hits the `instanceof Number` branch; `convertType` does `Integer.parseInt(value.toString())`. Result identical to the legacy String path.
- **Bad** — schema declares `array` but parser is hand-written and does not recurse into `[`: value becomes `"[12"` (a truncated String), downstream `(List) args.get(...)` throws `ClassCastException`. **This is the bug this spec exists to prevent.**

### 6. Tests Required

`chain/src/test/java/com/non/chain/tool/ToolRegistryTest.java` must cover (assertion points):
- Array parse → `List<Integer>` equals `[12,34]`.
- Nested object parse → `Map` equals `{k=v}`.
- String with embedded comma / `\"` escape untruncated.
- Scalar regression across all three consumers (`ToolArgs.getInt/getLong/getDouble/getString`, annotation `convertType`) with **Number**-typed storage.
- Empty / null input returns empty args without throwing.
- Invalid JSON and top-level non-object both throw `IllegalArgumentException` with a Chinese message.
- Annotation schema generation: `List<Integer>`→`items:number`, `String[]`→`items:string`, `Map`→`object` no items.
- Annotation end-to-end: `int[]` / `List` / `Set` (dedup) parameters received correctly by the invoked method.

### 7. Wrong vs Correct

#### Wrong — hand-written JSON parser

```java
// Hand-written state machine; value branch has no '[' / '{' recursion.
// For {"points":[12,34]} it slices "[12" into the map → ClassCastException downstream.
if (json.charAt(i) == '"') { /* read string */ }
else { /* read until ',' or '}', store as string */ }
```

**Why it's bad**: cannot handle nested arrays/objects, string escapes, or values containing commas. Patching one branch at a time is unbounded; the parser is the wrong place to encode JSON grammar.

#### Correct — use the project's Jackson ObjectMapper

```java
private static final ObjectMapper MAPPER = new ObjectMapper();

private Map<String, Object> parseArguments(String json) {
    if (json == null || json.isBlank()) return new HashMap<>();
    try {
        Object parsed = MAPPER.readValue(json.trim(), Object.class);
        if (parsed instanceof Map) return (Map<String, Object>) parsed;
        throw new IllegalArgumentException("工具参数必须是 JSON 对象: " + json);
    } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("工具参数 JSON 解析失败: " + json, e);
    }
}
```

**Why it works**: Jackson already parses every JSON type to the Java types the downstream consumers expect (`ArrayList`, `LinkedHashMap`, `Integer`, `Double`). No grammar to maintain; contract with layer 1 holds by construction.

---

## Convention: Numeric Storage is Number, not String

**What**: `parseArguments` stores JSON numbers as `Integer`/`Double` (Jackson default), not as `String` like the legacy hand-written parser did.

**Why**: the three consumers all tolerate Number via existing fallbacks, so behavior is unchanged while array/object support is gained. Documenting this prevents a future "optimization" that reintroduces String storage and silently breaks the `instanceof Number` fast paths.

**Consumers that must keep their Number fallbacks**:
- `ToolArgs.getInt/getLong/getDouble` — `if (v instanceof Number) return ((Number) v).intValue();`
- `ToolArgs.getString` — `v.toString()` (works for Number)
- `convertType` scalar branches — `Integer.parseInt(value.toString())`

---

## Common Mistake: Declaring a Schema Type Without Parser Support

**Symptom**: `ClassCastException` in a tool handler when the LLM returns an array/object.

**Cause**: layer 1 (schema) advertises `array`/`object`, but the parser/convertor was not updated to produce or accept the corresponding Java container type. The unchecked `(T) data.get(...)` cast then fails at runtime.

**Fix**: keep the mapping table above in sync across all three layers when adding a new JSON type.

**Prevention**: when adding any new schema type, trace it through `toFunctionDefinition → parseArguments → convertType/ToolArgs` and add a `ToolRegistryTest` case for the full path.

---

## Convention: Tool Interceptors vs Callback (control vs observation)

**What**: `ToolRegistry.execute` does **not** trigger `ChainCallback`. Tool lifecycle callbacks (`onToolStart`/`onToolComplete`/`onToolError`) are fired exclusively by the `Agent` orchestration layer (once per tool call). `ToolRegistry` is a pure executor.

**Why**: Two distinct extension axes were confused before this task:
- **`ChainCallback`** — *observation*. Read-only lifecycle hooks (`onLlmStart`, `onToolComplete`, ...). Cannot block or modify execution. `CompositeCallback.safeInvoke` silently swallows handler exceptions so a broken observer never breaks the run.
- **Tool interceptors** (`BeforeToolCall`/`AfterToolCall`, `com.non.chain.agent`) — *control*. Can block execution (`BeforeResult.block(reason)`), rewrite the result (`AfterResult.content(...)`/`error()`), and chain across multiple interceptors. Interceptor exceptions are wrapped in `AgentException` and **do** propagate (they fail the run visibly — never silently swallowed).

**Historical note**: `ToolRegistry` previously fired its own `onToolStart/onToolComplete/onToolError` inside `execute`, but all 30+ call sites used `new ToolRegistry()` (callback=noop), so that firing was dead code that only appeared to duplicate the Agent's firing when both shared a callback. The two `ToolRegistry(ChainCallback)` / `ToolRegistry(ChainContext)` constructors were removed as zero-call dead code.

**Contracts to preserve**:
- `ToolRegistry` MUST stay callback-free — adding tool observation belongs in `Agent`, not the executor.
- Interceptor exceptions propagate (control failures must be visible); callback exceptions are isolated (observation failures must not break the run).
- Multiple `BeforeToolCall` short-circuit on first `block`; multiple `AfterToolCall` chain (each receives the previous one's output).

---

## Convention: SubAgent Registration vs Exposure (two-layer split)

**What**: 委派型子代理（SubAgent）的能力拆成两层：**注册在 `ToolRegistry`，暴露模式选择在 `Agent.Builder`**。`ToolRegistry` 只存不决，不持有暴露模式状态。

**Why**: `ToolRegistry` 的职责是「可暴露的工具能力」，`Agent` 的职责是「LLM + tool 的循环执行」。把「父 Agent 最终看到哪套子代理工具」这个 *执行期* 决策塞进 `ToolRegistry`，会让 registry 带全局可变模式开关，破坏其「纯注册/查询/执行器」定位（也与上一节「ToolRegistry 是纯执行器」契约冲突）。

**Layer split**:
- **注册层 `ToolRegistry`**：`registerSubAgent(name, description)` → 声明式 `SubAgentRegistration` Builder（`systemPrompt` 必填，`description` 必填且与 `systemPrompt` 分开——description 进 LLM schema，systemPrompt 是子代理角色）。存储用 `LinkedHashMap`（注册顺序稳定，DIRECT 工具列表与 DELEGATE 的 `agentName` enum 都依赖）。查询方法 `hasSubAgent` / `getSubAgent` / `subAgentNames` / `getRegularTools` / `getDirectSubAgentTools` / `getDelegateSubAgentTool`。`getTools()` 语义不变（仅普通工具），子代理不混入。
- **暴露层 `Agent.Builder`**：`subAgentExposureMode(SubAgentExposureMode)` 构建期固定，默认 `DIRECT`。`Agent.resolveToolsForCurrentExposureMode()` 组合：DIRECT = 普通工具 + 每个子代理一个独立 tool；DELEGATE = 普通工具 + 单个 `delegate_to_subagent`。

**Contracts to preserve**:
- `registerSubAgent` 重名（与普通工具或已注册子代理同名）→ `IllegalStateException`「已存在同名工具或子代理」；`build()` 未设 `systemPrompt` → `IllegalStateException`。
- 独立子代理 tool schema 含必填 `task` + 可选 `run_in_background`（D11 调用级前后台）；delegate tool 的 `agentName` 是已注册子代理名 enum，也含可选 `run_in_background`（用 `Tool.Builder.addProperty(..., enumValues)`，复用 layer-1 schema 契约，无 parser 改动）。
- **D10 嵌套 fail-fast**：子代理的 `toolRegistry` 注册了 subAgent → `build()` 抛 `IllegalStateException`「子代理不支持嵌套委派」。仅一层委派。
- **执行分流在 `Agent.safeExecute` 下游**：`dispatchExecute` **五路**——① 独立子代理 tool（`hasSubAgent(name)`，解析 `run_in_background`：true→后台 spawn，false→前台同步）② delegate tool（同上分流）③ `get_subagent_result` tool → `bgManager.getResult` ④ `steer_subagent` tool → `bgManager.steer` ⑤ 普通工具走 `ToolRegistry.execute`。
- **D3/D6 控制工具暴露**：有已注册子代理时，`getSubAgentControlTools()` 额外暴露 `get_subagent_result` + `steer_subagent`；无子代理时不暴露。两者 fail-fast 同子代理名（`ToolRegistry.execute` 命中 → `IllegalStateException`「仅支持在 Agent 自动循环中执行」）。
- **`execute` fail-fast**：`ToolRegistry.execute` 命中子代理名、delegate tool、`get_subagent_result` 或 `steer_subagent` → `IllegalStateException`「仅支持在 Agent 自动循环中执行」。手写循环不提供父上下文/cb 隔离，禁止降级执行。
- **`safeExecute` 签名带父消息快照 + runId + bgManager**：`safeExecute(tc, assistantMessage, parentMessages, traceId, runId, bgManager)`，串/并行路径都传 `List.copyOf(messages)`（只读快照）。普通工具路径不读 `parentMessages`，行为不变。
- 子代理/delegate 的 `task`/`agentName`/`run_in_background` JSON 解析在 `Agent` 内用共享 `parseArgsMap`（与 `ToolRegistry.parseArguments` 同语义），不重复造 parser。

## Convention: SubAgent Runtime Isolation (context / callback / error)

**What**: 子代理运行时默认**无状态隔离**——独立 systemPrompt、独立工具集（可空）、独立 before/after 拦截器、独立 maxIterations、默认继承父 LLM、父/子 callback 与 trace 隔离。

**Why**: 父 Agent 把子代理视为一次普通工具调用（`ToolStart/ToolComplete/ToolError` 各一次），子代理内部事件若透出会污染父层可观测语义并破坏事件计数；上下文不裁剪则 token 失控且会泄漏父 systemPrompt。

**Context pruning contracts**:
- 子代理消息 = 子代理 `systemPrompt` + 裁剪后父上下文切片 + 本次 `task` 作为新 user 消息。
- **不含父 `systemPrompt`**；**排除 `llmVisible=false`** 应用层消息（note）。
- 默认 `ContextSelector` = 过滤 `llmVisible=false` 后保留其余父消息；可在注册时用 `contextSelector(...)` 覆盖（首批不支持运行时逐次覆盖）。
- 子代理在注册时**不预构建 `Agent`**：默认继承父 LLM、上下文运行时才知、父/子 trace+callback 需隔离，都只能运行时确定。`executeSubAgentTool` 内动态 `Agent.builder(...).callback(noop()).build()`。

**Callback / trace isolation contracts**:
- 子代理用 `ChainCallbackUtil.noop()` 与独立 trace（`child.run(list)` 内部生成自己的 traceId 并在 finally 清理），**不继承父 callback**。
- 子代理内部 LLM/Tool 事件**不透出**到父 callback（验证：父 callback 只看到外层 sub 委派的 Tool 事件，看不到子代理内部 kid_tool 事件）。
- 父 `before/after` 拦截器作用于外层子代理 tool 调用（可阻止/改写整次委派）；子代理注册时配的拦截器仅作用于子代理内部工具，**不继承父拦截器**。

**Error contracts**:
- 子代理整体抛异常 → 由 `safeExecute` 捕获 → 软失败（与普通工具一致）：外层记 `ToolErrorEvent`，错误文本回灌父 Agent，父循环继续。
- 子代理内部工具失败 → 子代理自身按现有 `Agent` 语义软失败处理，产出文本结果。
- ⚠️ **maxIterations graceful（破坏性变更）**：顶层 Agent 和子代理统一走 graceful——超 `maxIterations` 后注入「立即收尾」消息，给 `graceTurns`（默认 3）轮收尾；grace 内完成标 `STEERED`，grace 也超则硬中断返回部分结果标 `ABORTED`，**不抛异常**。回退 0.9.x 硬截断：`.graceTurns(0)` 恢复抛 `AgentException` 语义。

**Scope contracts**:
- 仅一层委派，子代理不能再委派（D10 fail-fast 见上一节）。
- 仅支持 `Agent` 自动循环；手写循环误用 → fail-fast（见上一节）。
- 同轮多个子代理可并行（沿用现有多工具并行），结果按原始 tool call 顺序回灌。

## Convention: SubAgent Background Execution & Orchestration (前台/后台并行)

**What**: 引入后台子代理——`run_in_background=true` 时 spawn 后立即返回，父代理不阻塞、继续推理；后台子代理由 `BackgroundSubAgentManager` 编排（独立线程池 + 运行上限 + 熔断 + 完成队列）。生命周期绑定单次父 `run()` 内（D2：`run()` 结束 `close()` 取消未完成任务）。

**Why**: 0.9.x 的并行是「同轮多 toolCall 不同线程跑但 `allOf().join()` 同步阻塞等齐」；后台模式让父代理能「派出去自己继续推理」，配合自动 join 消费结果。

**Background manager contracts**:
- **独立线程池**：`Executors.newFixedThreadPool(maxBackgroundRunning)`，不复用父 `executor`（ForkJoinPool 不适合阻塞 IO）。Builder 可 `.backgroundExecutor(...)` 覆盖。
- **运行上限（默认 4）+ FIFO 队列**：`running.size() < maxRunning` 立即提交，否则入队；任务完成 drain。
- **总派发熔断（自适应）**：`spawnCeiling = maxIterations × maxBackgroundRunning × 2`（防 LLM 失控狂 spawn），可 `.spawnCeiling(...)` 显式覆盖；超限拒绝 spawn 返回错误文本。
- **死循环防护三重保证**：① `joinCompleted()` 只注入已完成结果，不 spawn 新任务、不强制 `continue`；② spawn 受熔断；③ `awaitAll(timeout)` 有全局超时（默认 60s），超时取消未完成的。

**Result consumption contracts（D3 自动 join + 主动拉取）**:
- **轮末自动 join**：每轮 tool 执行后、下一轮 LLM 推理前，`bgManager.joinCompleted()` 把已完成未消费结果**合并成一条 user 消息**注入（D13，降低膨胀）。
- **Complete 前强制等待**：`!result.hasToolCalls()` 分支有运行任务时调 `bgManager.awaitAll(timeout)`；无运行任务时仍执行 `joinCompleted()`，避免任务在状态检查竞态窗口完成后丢失结果；有未消费则注入 + `continue`（让 LLM 再看一轮），无则真正 Complete。
- **`get_subagent_result` 工具**：父 LLM 主动查询/等待（`wait:true` 阻塞，但有 `awaitTimeoutMs` 超时保护——瑕疵E）。返回完整结果 + 状态。

**Steer contracts（D6 仅后台）**:
- `steer(message)` 仅后台子代理实例支持（`pendingSteers` 非 null）；顶层/前台 Agent 调用抛 `UnsupportedOperationException`。
- steer 消息在子代理**下一轮 LLM 调用前**作为 user message 加入（非即时中断）。
- `steer_subagent` 工具 → `bgManager.steer` → 通过 `record.childAgent().steer()` 桥接到运行中子代理的内部队列。

**Resume contracts（D7 opt-in 有状态）**:
- `SubAgentDefinition.chatMemoryStore` 默认 null = 无状态（0.9.x 语义）；配置后子代理有状态，复用现有 `ChatMemoryStore` SPI。
- 首次委派：注入父上下文切片；resume（历史非空）：**不注入父上下文**（D12），用 `[systemPrompt] + history + [user(task)]`。
- **conversationId 区分并发（瑕疵C）**：前台 = `<runId>:<subAgentName>`（连续 resume）；后台 = `<runId>:<subAgentName>:<recordId>`（并发隔离）。

**Context pruning（D12 前后台差异）**:
- 前台默认 = `DEFAULT_CONTEXT_SELECTOR`（全量可见，0.9.x 不变）。
- 后台默认 = `BACKGROUND_CONTEXT_SELECTOR`（最近 4 条可见消息，避免并行 token 爆炸）。
- 子代理注册的 `contextSelector` 优先级最高（覆盖两者）。

**Lifecycle events（D5，正交于内部事件隔离）**:
- 新增 `AgentEvent`：`SubAgentSpawned/Started/Completed/Failed/Steered/Aborted`，由 `BackgroundSubAgentManager` 在状态转换时发射。
- 子代理**内部事件**（`TextDelta`/`ToolStart` 等）仍隔离（`noop()` 不变）；**trace 不隔离**（子代理 span 挂父委派 tool span 下，0.9.x 边界1 不变）。

## Related

---

## Related

- [Quality Guidelines](./quality-guidelines.md) — immutable value objects, Builder pattern, test public API only
- [Error Handling](./error-handling.md) — standard Java exceptions, fail-fast, Chinese messages
- [Directory Structure](./directory-structure.md) — `tool/` package layout
