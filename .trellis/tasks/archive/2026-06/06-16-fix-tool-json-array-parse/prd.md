# 修复工具参数 JSON 解析不支持数组/对象导致 ClassCastException

## Goal

修复 `ToolRegistry.parseSimpleJson` 无法解析 JSON 数组 `[...]` 和对象 `{...}` 嵌套结构，导致 LLM 返回数组/对象类型参数时，下游 `(List<Object>) args.get(...)` / `(T) data.get(...)` 强转抛 `ClassCastException`，工具调用直接崩溃的问题。

让「上游 `Tool.toFunctionDefinition()` 声明的 schema 类型」与「下游参数解析」契约一致：声明 `array`/`object` 就能被正确解析为 `List`/`Map`。

## Confirmed Facts

- **根因**：`parseSimpleJson`（ToolRegistry.java:183-222）的值解析分支只有 `"字符串"` / `true` / `false` / `null` / 裸 token 兜底，**没有任何对 `[` / `{` 嵌套结构的处理**。对 `{"points": [12, 34]}`，剥掉外层 `{}` 后，值分支从 `[` 读到第一个 `,` → 得到 `"[12"` 存进 map。
- **契约断裂点**：`Tool.toFunctionDefinition()`（Tool.java:40-69）把 property 的 `type` 原样塞进 schema，`addProperty(name, "array", ...)` 完全合法 → LLM 必然返回数组；但解析端不认数组 → 上下游契约不一致。
- **依赖已就位**：`chain` 模块 pom 已依赖 `jackson-databind 2.17.1`，`MessageSerializer` 已在用 `ObjectMapper`，替换手写解析器无需新增依赖，风格统一。
- **手写 parser 其它隐性缺陷**：除数组/对象外，还不支持字符串内 `\"` 转义、字符串值内含逗号（`"a,b"` 会被错误截断）。打补丁是无限续命，应整体替换。
- **下游消费现状**：
  - fluent 方式：`ToolArgs.get(name)`（ToolArgs.java:49-52）无检查 `(T) data.get(...)`；`getInt/getLong/getDouble` 先判 `instanceof Number`；`getString` 走 `v.toString()`。
  - 注解方式：`doExecute`（ToolRegistry.java:135-143）→ `convertType`（170-178）对未知目标类型原样返回 value。

## Requirements

- R1：工具参数 JSON 解析必须正确支持标量、字符串、布尔、null、**数组**、**对象**（含嵌套），数组解析为 `java.util.List`，对象解析为 `java.util.Map`。
- R2：字符串值必须正确处理 `\"` 等转义与值内逗号。
- R3：替换手写解析器，使用项目已有的 Jackson `ObjectMapper`。
- R4：**向后兼容** —— 现有标量场景行为不变：
  - 字符串值仍可经 `ToolArgs.getString` 正常读取。
  - 数字值：原手写解析存 String（如 `"12"`），新实现存 Number（`Integer 12`）。须验证 `ToolArgs.getInt/getLong/getDouble`、注解方式 `convertType`、`getString` 三条消费链路在 Number 类型下结果一致。
- R5：解析失败须按项目异常规范抛出（参考 error-handling.md：标准 Java 异常、fail-fast、中文描述性消息），不得静默吞错。
- R6：新增 `ToolRegistry` 单元测试覆盖上述场景（当前 `tool` 包零测试）。
- R7：增强注解方式（`@ToolDef`/`@ToolParam`）的 schema 生成：`javaTypeToJsonType` 对 `List`/`Set`/Java 数组返回 `"array"`，对 `Map` 返回 `"object"`，并生成 JSON Schema 的 `items` 字段描述数组元素类型。fluent 方式的 array 声明同样应能生成 `items`。
- R8：`convertType` 适配容器/数组目标类型，使注解方式工具的方法参数（`List`/`Set`/Java 数组/`Map`）能正确接收 parser 解析出的 `List`/`Map`。

## Acceptance Criteria

- [ ] `{"points": [12, 34]}` 经解析后 `args.get("points")` 可成功强转为 `List<Integer>`，值为 `[12, 34]`。
- [ ] `{"config": {"k": "v"}}` 经解析后可强转为 `Map`，值为 `{k=v}`。
- [ ] `{"name": "a,b", "note": "say \"hi\""}` 字符串值正确解析，不被逗号/引号截断。
- [ ] 标量场景（int/long/double/boolean/string/null）回归通过：fluent `ToolArgs` 与注解方式 `convertType` 两条链路行为与修复前一致。
- [ ] 非法 JSON 入参抛出含中文描述的 `IllegalArgumentException`（或既有异常规范约定的类型），不抛 ClassCastException、不静默返回空 map。
- [ ] 注解方式 `@ToolParam List<Integer> points` 能生成含 `items` 的 array schema（如 `{"type":"array","items":{"type":"number"}}`），LLM 据此返回数组后可被正确接收。
- [ ] 注解方式工具的方法参数（`List`/数组等容器类型）能正确接收解析结果，不抛类型异常。
- [ ] `chain` 模块 `mvn test` 全绿。

## Out of Scope

- 不破坏 `Tool` / `Property` 现有结构：可为 array `items` 做**增量扩展**（如 `Property` 增加 `items` 字段、`Tool.Builder` 增加重载），但不得改动现有标量 `addProperty(name, type, ...)` 的行为与 schema 输出。
- 不重构 `ToolArgs` API（保持现有 getter 语义）。
- 不涉及 LLM provider 层、Agent 层逻辑。
- 不做深度嵌套对象 schema：数组元素为自定义 POJO/复杂嵌套时，MVP 仅声明 `items:{type:object}`，不反射 POJO 属性生成 `properties`。

## Resolved Decisions

- **D1（范围决策）**：✅ **纳入**（用户选 B）。任务范围从「仅修 parser」扩展为「修 parser + 增强注解方式 schema 生成 + convertType 适配」，使注解方式注册的工具也能向 LLM 声明 array/object 参数并正确接收。
- **D2（注解数组 API 形态）**：✅ **自动从方法签名泛型推断，零注解变更**（用户选 A）。框架用 `Method.getGenericParameterTypes()` 拿到 `ParameterizedType`（保留 `List<Integer>` 的 `Integer`），自动算出数组元素类型 → 生成 `items`。`@ToolParam` API 保持不变。raw type `List`（无泛型）兜底为 `items:{type:"string"}`。支持的目标参数类型：`List`/`Set`/Java 数组（`int[]`/`Integer[]`/`String[]`）/`Map`。

## Open Questions

- （暂无待决问题，可进入 design/implement 编写）
