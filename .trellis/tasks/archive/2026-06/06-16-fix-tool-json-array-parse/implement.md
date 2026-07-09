# Implement — 执行计划

> 关联 prd.md（D1=纳入、D2=自动泛型推断）、design.md（三模块）。
> 平台 Pi：主会话派发 `trellis-implement` / `trellis-check` 子代理，不内联。本文件为人类可读执行计划。

## 验证命令

```bash
# 编译 + 全量测试（chain 模块）
cd /Users/non/Projects/nonchain && mvn -q -pl chain test

# 仅跑本次新增测试
mvn -q -pl chain test -Dtest=ToolRegistryTest

# 单测失败时看完整堆栈
mvn -pl chain test -Dtest=ToolRegistryTest -e
```

> 注意：chain 模块无 surefire 显式配置，junit 4.13.2 scope=test。`-Dtest=` 需 surefire 默认绑定，已验证可用。

## 执行顺序

### 阶段 A：Parser 替换（止血，最高优先）

1. **`ToolRegistry.java`**：
   - 新增 `import com.fasterxml.jackson.databind.ObjectMapper;`、`import com.fasterxml.jackson.core.JsonProcessingException;`
   - 新增静态字段 `private static final ObjectMapper MAPPER = new ObjectMapper();`
   - 用 design §2.2 的 `parseArguments` **替换** `parseSimpleJson`（183-222 行整段）。
   - `doExecute`（129 行）调用点改名 `parseArguments`。
   - ⚠️ 删除旧方法体，不保留死代码。

2. **编译确认**：`mvn -q -pl chain compile`。

3. **回归校验（先于写新测试）**：手动核对 R4 三条链路——
   - `ToolArgs.getInt/getLong/getDouble`：`instanceof Number` 分支命中（Jackson 存 Integer/Double），返回与旧 String 路径一致。
   - `convertType`：标量分支 `parseInt(value.toString())`，Integer.toString() = "12"。
   - `getString`：`Number.toString()` = "12"。

### 阶段 B：Schema 生成增强（注解 array/items）

4. **`Tool.java`**（增量，不改标量行为）：
   - `Property` 增加 `final Property items;` 字段；新增 4 参构造；旧 3 参构造改为 `this(..., null)` 委托。
   - `toFunctionDefinition`：在 enum 判断后追加 `if (items != null)` 输出 `items` schema（design §3.2）。
   - `Builder` 新增重载 `addProperty(name, type, description, isRequired, itemsType)`。
   - **不动**现有 `addProperty(name, type, description, isRequired)` 和 enum 重载。

5. **`ToolRegistry.java`**：
   - 增强 `javaTypeToJsonType`（design §3.3）：标量判断后、`return "string"` 前，插入 array（`isArray`/`List`/`Set`）与 object（`Map`）判断。新增 `import java.util.List;`、`java.util.Set;`、`java.util.LinkedHashSet;`、`java.lang.reflect.*;`。
   - 新增 `inferItemsType(Type genericType, Class<?> rawType)` 私有方法。
   - `getTools()` 注解分支（84-91 行）：取 `entry.method.getGenericParameterTypes()`，循环内按 design §3.3 计算 `itemsType` 并选调 5 参或 4 参 `addProperty`。

### 阶段 C：convertType 适配容器/数组

6. **`ToolRegistry.java`**：
   - `convertType`（170-178 行）：标量分支保留，末尾 `return value` 前插入 array/List/Set/Map 分支（design §4.2）。
   - 新增私有 helper `coerceToList(Object)`。
   - 新增 `import java.lang.reflect.Array;`。
   - ⚠️ 数组分支内递归 `convertType(elem, componentType)`，处理 `Integer`→`int` 装箱。

### 阶段 D：测试

7. **新建 `chain/src/test/java/com/non/chain/tool/ToolRegistryTest.java`**（JUnit 4，对齐 quality-guidelines「测公开 API」+ 现有 AgentTest 风格）。

   覆盖矩阵：

   | 测试 | 入参 | 断言 |
   |---|---|---|
   | 标量 fluent 回归 | `{"city":"北京","n":12,"d":1.5,"b":true}` | getString/getInt/getLong/getDouble/getBoolean 返回正确；**getInt=12**（Number 路径） |
   | 数组解析 | `{"points":[12,34]}` | `args.<List<Integer>>get("points")` = [12,34] |
   | 嵌套对象 | `{"config":{"k":"v"}}` | `args.<Map>get("config")` = {k=v} |
   | 字符串转义/内嵌逗号 | `{"name":"a,b","note":"say \"hi\""}` | getString("name")="a,b", getString("note")=`say "hi"` |
   | null 值 | `{"x":null}` | getString("x")=null, has("x")=false |
   | 空入参 | `null` / `""` / `"   "` | 不抛错，返回空（handler 收到空 args） |
   | 非法 JSON | `{"bad` / `[1,2]`（顶层非对象） | 抛 `IllegalArgumentException`，消息含中文 |
   | 注解 schema：List<Integer> | 注册 `@ToolParam List<Integer> points` 工具 → getTools | schema 含 `items:{type:number}` |
   | 注解 schema：String[] | `@ToolParam String[] tags` | `items:{type:string}` |
   | 注解 schema：Map | `@ToolParam Map<String,Object> data` | `type:object`，无 items |
   | 注解端到端：int[] | LLM 返回 `{"nums":[1,2]}` → execute | method 收到 `int[]{1,2}`，不抛异常 |
   | 注解端到端：List | `{"items":["a","b"]}` → execute | method 收到 List=["a","b"] |
   | 注解端到端：Set | `{"tags":["x","x","y"]}` → execute | method 收到 Set，去重后 size=2 |

   - parser 与 convertType 是 private：通过 `execute(name, json)` 走公开 API 触发；schema 断言通过 `getTools()` 拿 `Tool` 再 `toFunctionDefinition()` 读 `parameters()` 的 additionalProperties（或新建测试用注解工具类直接验证 invoke 结果）。
   - schema 字段断言：`FunctionDefinition.parameters().additionalProperties()` 是 `Map<String,JsonValue>`，取 `"properties"` → 解析为 Map 再断言 type/items。

8. **全量测试**：`mvn -q -pl chain test`，全绿。

## 风险文件 & 回滚点

| 文件 | 风险 | 回滚 |
|---|---|---|
| `ToolRegistry.java` | parser 替换改变数字存储类型；泛型反射边界（raw type） | parser 单独可回退（丢 array 能力） |
| `Tool.java` | Property 加字段 | private 类，无外部消费者；增量，可保留 |
| `ToolRegistryTest.java`（新） | 测试断言 schema 结构依赖 SDK 内部 Map 形态 | 仅测试，删除即回滚 |

**回滚策略**：三模块逻辑独立。若集成出问题，优先 `git diff` 隔离；极端整体 `git revert`（单提交）。

## Pre-start Checklist（1.4 review gate）

- [x] prd.md 存在且含可测验收标准
- [x] design.md 存在（complex 任务）
- [x] implement.md 存在（complex 任务）
- [ ] 用户 review 三件套后确认进入实现
- [ ] `task.py start` 后 status → in_progress

## 范围纪律（防 scope creep）

- **不做**：fluent 方式 `items`（design §3.2 决策点，best-effort 不阻塞）。
- **不做**：POJO/深嵌套 schema（items 到 object 为止）。
- **不做**：`@ToolParam` API 变更（D2 零注解变更）。
- **不做**：ToolArgs API 变更。
