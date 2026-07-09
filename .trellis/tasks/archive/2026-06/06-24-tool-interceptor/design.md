# Design — Agent 工具拦截器（beforeToolCall/afterToolCall）

> 关联 prd.md 的 D1（双函数式接口）、D2（静态工厂返回类型）、D3（直接重构 ToolRegistry.execute 移除 callback + 删死构造器）、D4（不含 terminate，留给 P1）。

## 1. 改动边界

新增文件 + 修改现有文件。全部在 `chain/src/main/java/com/non/chain/`，外加测试。

| 文件 | 改动 | 性质 |
|---|---|---|
| `agent/BeforeToolCall.java`（新增） | `@FunctionalInterface`，`BeforeResult before(ToolCallContext)` | 新增 |
| `agent/AfterToolCall.java`（新增） | `@FunctionalInterface`，`AfterResult after(ToolCallContext)` | 新增 |
| `agent/ToolCallContext.java`（新增） | 拦截器入参上下文，不可变值对象 | 新增 |
| `agent/BeforeResult.java`（新增） | before 返回类型，静态工厂 `pass()`/`block(reason)` | 新增 |
| `agent/AfterResult.java`（新增） | after 返回类型，静态工厂 + builder | 新增 |
| `agent/Agent.java` | Builder 加 `addBeforeToolCall`/`addAfterToolCall`；重构 `safeExecute` 插入拦截点 + 统一 callback 触发 | 核心改动 |
| `tool/ToolRegistry.java` | 重构 `execute` 移除 callback 触发；删除 2 个零调用构造器 + callback 字段 | 核心改动（删死代码） |
| `AgentTest.java` | 新增拦截器场景测试 + callback 单次触发断言 | 增量 |
| `chain-example/...` | 新增拦截器示例 | 增量 |

不改动：`ChainCallback`/`CompositeCallback`（观察者，正交）、`Message`、provider 层、memory 层、`ToolRegistry` 的解析/转换逻辑。

## 2. 新增类型契约

### 2.1 ToolCallContext（拦截器入参）

不可变值对象（quality-guidelines §1：private final + no-prefix accessor）。

```java
package com.non.chain.agent;

public final class ToolCallContext {
    private final String toolCallId;
    private final String toolName;
    private final String arguments;          // 原始 JSON 字符串
    private final Message assistantMessage;  // 触发该工具调用的 assistant 消息（含完整 content）

    public ToolCallContext(String toolCallId, String toolName, String arguments, Message assistantMessage) { ... }

    public String toolCallId() { ... }
    public String toolName() { ... }
    public String arguments() { ... }
    public Message assistantMessage() { ... }
}
```

**为什么带 assistantMessage**：pi 的 beforeToolCall 入参含 `assistantMessage`（agent-loop.ts:586）。拦截器可能需要看 assistant 的完整输出（思考链、其他 toolCall）来做决策（如"assistant 同时调了 dangerous_tool 和 safe_tool，只 block dangerous"）。获取成本低（doRunWithLoop 里 `result` 就是），收益是决策信息完整。

> ⚠️ 内存可见性：并行模式下 `assistantMessage` 在多个 worker 线程间共享只读，`Message` 本身不可变（final 字段），无竞态。

### 2.2 BeforeResult

静态工厂（quality-guidelines §3）。不可变。

```java
public final class BeforeResult {
    private final boolean blocked;
    private final String reason;   // blocked=true 时作为 error tool result 文本

    private BeforeResult(boolean blocked, String reason) { ... }

    public static BeforeResult pass() { return new BeforeResult(false, null); }
    public static BeforeResult block(String reason) {
        return new BeforeResult(true, reason == null ? "工具执行被拦截" : reason);
    }

    public boolean blocked() { ... }
    public String reason() { ... }
}
```

- `pass()` / `block(reason)` 是仅有的两个构造路径——无法构造出"blocked=true 但 reason=null"的歧义态（block 兜底默认文案）。
- 不用 null 表示 pass：null 在链式调用和多拦截器场景下是 bug 之源。

### 2.3 AfterResult

静态工厂 + builder。不可变。**本任务不含 terminate（D4）**。

```java
public final class AfterResult {
    private final boolean modified;       // 内部标记：是否要改写（keep() 时 false）
    private final String content;         // null=不改
    private final Boolean isError;        // null=不改；true/false=显式设置

    private AfterResult(boolean modified, String content, Boolean isError) { ... }

    public static AfterResult keep() { return new AfterResult(false, null, null); }
    public static AfterResult content(String newContent) {
        return new AfterResult(true, newContent, null);
    }
    public static AfterResult error() {
        return new AfterResult(true, null, true);
    }
    public static Builder builder() { return new Builder(); }

    public boolean modified() { ... }
    public String content() { ... }       // 调用方判 modified() 后用
    public Boolean isError() { ... }

    public static class Builder {
        private String content;
        private Boolean isError;
        public Builder content(String c) { this.content = c; return this; }
        public Builder isError(boolean e) { this.isError = e; return this; }
        public AfterResult build() {
            boolean modified = content != null || isError != null;
            if (!modified) return keep();
            return new AfterResult(true, content, isError);
        }
    }
}
```

- `Boolean isError`（包装类型）三态：null=不改、TRUE=标错、FALSE=撤销错误。content 同理用 null=不改。
- `modified()` 让循环代码一眼判断是否要替换原结果，避免无谓对象操作。
- 未来 P1 加 terminate：给 Builder 加 `.terminate()`、AfterResult 加 `boolean terminate` 字段 + accessor，纯增量，不破坏现有拦截器。

## 3. ToolRegistry 改动（D3 修正）

**直接重构 `execute` 移除 callback 触发**，不新增方法。理由见 prd R5：grep 证实全库 30+ 处 `new ToolRegistry()` 全用无参构造器（callback=noop），`new ToolRegistry(callback)`/`new ToolRegistry(chainContext)` **零调用方**，ToolRegistry 的 callback 触发是死代码 + 职责越界。

```java
// 重构后：execute 只管执行
public String execute(String name, String arguments) {
    ToolEntry entry = entries.get(name);
    if (entry == null) {
        throw new IllegalArgumentException("未注册的工具: " + name);
    }
    return doExecute(entry, arguments);   // 复用现有 doExecute（ToolRegistry.java:144），无 callback
}
```

同时删除：
- `ToolRegistry(ChainCallback callback)` 构造器（ToolRegistry.java:37-39）—— 零调用方
- `ToolRegistry(ChainContext chainContext)` 构造器（ToolRegistry.java:41-43）—— 零调用方
- `private final ChainCallback callback;` 字段（ToolRegistry.java:31）+ 相关 import

**保留**：
- 无参构造器 `ToolRegistry()`（ToolRegistry.java:33-35）—— 30+ 处在用
- `doExecute`（ToolRegistry.java:144）、`parseArguments`、`convertType` 等执行逻辑全部不变

**为什么删构造器而不只是清空 execute**：callback 字段一旦保留，就暗示"ToolRegistry 会触发 callback"，误导未来维护者；既然 execute 不再用 callback，字段和构造器就是死代码，一并删除回归单一职责。error-handling spec 强调 fail-fast 不含糊，死字段是反面。

## 4. Agent 改动（核心）

### 4.1 Builder 新增拦截器字段

```java
public static class Builder {
    // 现有字段...
    private final List<BeforeToolCall> beforeInterceptors = new ArrayList<>();
    private final List<AfterToolCall> afterInterceptors = new ArrayList<>();

    public Builder addBeforeToolCall(BeforeToolCall interceptor) {
        if (interceptor != null) this.beforeInterceptors.add(interceptor);
        return this;
    }

    public Builder addAfterToolCall(AfterToolCall interceptor) {
        if (interceptor != null) this.afterInterceptors.add(interceptor);
        return this;
    }
    // build() 把两个 list 传给 Agent（不可变拷贝：Collections.unmodifiableList）
}
```

Agent 新增两个 final 字段持有不可变 list。

### 4.2 重构 safeExecute —— 拦截点 + 统一 callback

**重构后 safeExecute 的职责**：编排 callback + before 拦截器 + 执行 + after 拦截器，callback 只在此触发（ToolRegistry 已不再触发，见 §3）。

```java
/**
 * 执行单个工具调用：callback → before 拦截 → 执行 → after 拦截。
 * callback 仅在此触发一次（R5）。before block 时跳过执行。
 */
private String safeExecute(ToolCall tc, Message assistantMessage, String traceId) {
    ToolCallContext ctx = new ToolCallContext(tc.id(), tc.name(), tc.arguments(), assistantMessage);

    // 1. callback: onToolStart（唯一触发点）
    callback.onToolStart(new ToolStartEvent(traceId, tc));
    long start = System.currentTimeMillis();

    try {
        // 2. before 拦截器链（任一 block 即短路）
        for (BeforeToolCall before : beforeInterceptors) {
            BeforeResult br = before.before(ctx);
            if (br.blocked()) {
                long latencyMs = System.currentTimeMillis() - start;
                // block 视为错误：触发 onToolError（reason 文本），无 onToolComplete
                callback.onToolError(new ToolErrorEvent(traceId, tc.id(), tc.name(), tc.arguments(),
                        new RuntimeException(br.reason()), latencyMs));
                return br.reason();   // reason 作为 tool result 回灌 LLM
            }
        }

        // 3. 实际执行（ToolRegistry.execute 已不再触发 callback，见 §3）
        String result = toolRegistry.execute(tc.name(), tc.arguments());

        // 4. after 拦截器链（链式：前一个输出作后一个输入）
        boolean isError = false;
        for (AfterToolCall after : afterInterceptors) {
            AfterResult ar = after.after(new ToolCallContext(tc.id(), tc.name(), tc.arguments(),
                    assistantMessage, result, isError));  // after 上下文含 result/isError
            if (ar.modified()) {
                if (ar.content() != null) result = ar.content();
                if (ar.isError() != null) isError = ar.isError();
            }
        }

        // 5. callback: onToolComplete 或 onToolError（唯一触发点）
        long latencyMs = System.currentTimeMillis() - start;
        if (isError) {
            callback.onToolError(new ToolErrorEvent(traceId, tc.id(), tc.name(), tc.arguments(),
                    new RuntimeException(result), latencyMs));
        } else {
            callback.onToolComplete(new ToolCompleteEvent(traceId, tc.id(), tc.name(), result, latencyMs));
        }
        return result;

    } catch (Exception e) {
        // 拦截器或执行抛异常 → 包装抛出（R3：不静默吞）
        long latencyMs = System.currentTimeMillis() - start;
        callback.onToolError(new ToolErrorEvent(traceId, tc.id(), tc.name(), tc.arguments(), e, latencyMs));
        throw new AgentException("工具拦截器或执行失败: " + tc.name(), e);
    }
}
```

> ⚠️ **ToolCallContext 对 after 的扩展**：after 需要额外的 result/isError 字段。两种做法：
> - **方案 a（推荐）**：ToolCallContext 加可选的 result/isError 字段（before 时为 null），一个类两种用法。
> - 方案 b：before/after 各一个 context 类。
>
> 选 a：减少类数量，result/isError 在 before 时自然为 null（before 时还没执行），语义清晰。

修正后的 ToolCallContext：
```java
public final class ToolCallContext {
    private final String toolCallId;
    private final String toolName;
    private final String arguments;
    private final Message assistantMessage;
    private final String result;      // after 时非 null；before 时 null
    private final boolean isError;    // after 时有意义；before 时 false

    // before 用：构造时不传 result/isError（默认 null/false）
    public ToolCallContext(String toolCallId, String toolName, String arguments, Message assistantMessage) {
        this(toolCallId, toolName, arguments, assistantMessage, null, false);
    }
    // after 用
    public ToolCallContext(String toolCallId, String toolName, String arguments,
                           Message assistantMessage, String result, boolean isError) { ... }

    public String result() { return result; }       // before 调用时返回 null
    public boolean isError() { return isError; }
}
```

### 4.3 异常语义变更（需注意）

**现状**：`safeExecute` catch 所有异常 → 返回软失败字符串 `"工具执行失败: " + msg` 回灌 LLM，**不抛**（Agent.java:281-285）。这是"工具错误软处理"。

**重构后**：
- **工具执行本身的异常**（`execute` 抛，现 ToolRegistry 不再吞）：保持现状的软失败语义——catch 后返回错误字符串回灌 LLM（不破坏现有 `testToolExecutionErrorPassedToLLM` 回归）。即下面的内层 catch 对执行异常走软失败路径。
- **拦截器异常**（before/after 抛）：按 R3 包装为 `AgentException` 抛出（让 run 失败可见，不静默吞）。

为区分两者，调整结构：
```java
try {
    // before 拦截器（异常 = 拦截器故障，抛 AgentException）
    for (BeforeToolCall before : beforeInterceptors) { ... }
    // 执行（异常 = 工具故障，软失败回灌）
    String result;
    boolean isError = false;
    try {
        result = toolRegistry.execute(tc.name(), tc.arguments());
    } catch (Exception execEx) {
        // 工具执行错误：软失败（现状语义），但仍允许 after 拦截器处理错误结果
        result = "工具执行失败: " + execEx.getMessage();
        isError = true;
    }
    // after 拦截器（异常 = 拦截器故障，抛 AgentException）
    for (AfterToolCall after : afterInterceptors) { ... }
    ...
} catch (AgentException ae) {
    throw ae;  // 拦截器异常上抛
} catch (Exception e) {
    // 兜底
}
```

> 这个区分是 design review 的重点：after 拦截器能否处理"执行失败的 result"？推荐**能**——after 看到 isError=true + 错误字符串，可改写为更友好的错误或脱敏。这比"执行失败就跳过 after"更有用。

### 4.4 调用点改造（串行 + 并行）

`doRunWithLoop` 的两处调用点（Agent.java:231 并行、Agent.java:254 串行）改传 `assistantMessage`：

- 串行（Agent.java:250-259）：`safeExecute(tc, result, traceId)`（`result` 是当前轮的 ChatResult/Message）
- 并行（Agent.java:225-238）：`CompletableFuture.supplyAsync(() -> safeExecute(tc, result, traceId), executor)`

> **并行模式的线程安全**：before/after 拦截器在 worker 线程执行。要求拦截器实现自身线程安全（无共享可变状态，或用同步）。文档明确标注此约束。`beforeInterceptors`/`afterInterceptors` list 本身不可变，多线程只读遍历安全。

### 4.5 block 场景的 tool result

before block 时返回 `br.reason()`，调用方（doRunWithLoop）把它当作正常 tool result 追加到 messages（`Message.toolResult(tc.id(), reason)`）。LLM 看到 reason 文本，自然知道该工具被拦截/失败。与现有"工具错误回灌"路径一致（testToolExecutionErrorPassedToLLM 验证的模式）。

## 5. 数据流（端到端）

```
LLM 返回 assistant(toolCalls=[tc1, tc2])
  → doRunWithLoop 遍历 toolCalls
  → safeExecute(tc, assistantMsg, traceId)
      1. callback.onToolStart          [唯一触发]
      2. for before in beforeInterceptors:
           BeforeResult r = before.before(ctx)
           if r.blocked() → callback.onToolError(reason); return reason
      3. result = toolRegistry.execute(name, args)  [ToolRegistry 不再触发 callback，见 §3]
         (执行异常 → result="工具执行失败:...", isError=true)
      4. for after in afterInterceptors:
           AfterResult r = after.after(ctx with result,isError)
           if r.modified() → 应用 content/isError 改写
      5. isError? callback.onToolError : callback.onToolComplete    [唯一触发]
      6. return result
  → messages.add(Message.toolResult(tc.id(), result))
  → 下一轮 LLM
```

## 6. 兼容性 & 风险

| 风险 | 缓解 |
|---|---|
| 不配拦截器时行为变化 | beforeInterceptors/afterInterceptors 为空 → safeExecute 等价于"callback + execute"，与现状语义一致（ToolRegistry 的 callback 本就打到 noop）。回归测试守护。 |
| ToolRegistry.execute 不再触发 callback 破坏外部使用者 | grep 证实全库 30+ 处 `new ToolRegistry()` 全用无参构造器（callback=noop），`new ToolRegistry(callback)`/`new ToolRegistry(chainContext)` **零调用方**——没有使用者依赖 ToolRegistry 的 callback 触发。`ChainCallbackTest` 的 tool 事件断言是通过 Agent.callback 注入 collector 跑的（Agent.java 触发），不依赖 ToolRegistry 触发。 |
| ToolRegistry 删除两个构造器破坏调用方 | 同上，零调用方。若担心未知的私有 fork，可先标 `@Deprecated` 而非删除——但本项目惯例是直接删死代码（error-handling spec 的 fail-fast 精神）。 |
| 工具执行异常语义从"软失败"变？ | 不变：执行异常仍软失败回灌 LLM。仅拦截器异常才抛。 |
| 并行模式拦截器线程安全 | 文档标注：拦截器在 worker 线程执行，需线程安全。 |
| ToolCallContext 跨 before/after 复用 | result/isError 在 before 时为 null/false，语义清晰，无歧义。 |

**回滚**：改动集中在 Agent + 5 个新类 + ToolRegistry。新类删除即回滚拦截器；Agent.safeExecute 可 git revert；ToolRegistry 的 execute 重构 + 构造器删除可 git revert。三处独立。

## 7. Out of Scope（重申）

- terminate / shouldStopAfterTurn（D4，留给 P1）。
- preflight 串行 + execute 并发两阶段重构（P1）。
- 具体 脱敏/审核 规则实现（只提供机制 + 示例）。
- provider / memory / Message 改动。
