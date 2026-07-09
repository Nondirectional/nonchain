# Design — 工具参数 JSON 解析数组/对象支持

> 关联 prd.md 的 D1（范围=纳入注解 array）、D2（自动泛型推断，零注解变更）。

## 1. 改动边界

三处源文件，全部在 `chain/src/main/java/com/non/chain/tool/`，外加新增测试：

| 文件 | 改动 | 性质 |
|---|---|---|
| `ToolRegistry.java` | 替换 `parseSimpleJson`；增强 `javaTypeToJsonType`；增强 `convertType`；新增私有 helper（泛型/数组元素类型推断、`items` schema 构造） | 核心改动 |
| `Tool.java` | `Property` 增加 `items` 字段；`Tool.Builder` 新增带 `items` 的 `addProperty` 重载；`toFunctionDefinition()` 在 type=array 时输出 `items` | 增量扩展（不改现有标量行为） |
| `ToolRegistryTest.java`（新建） | parser + schema 生成 + convertType 全链路测试 | 新增 |

不改动：`ToolArgs.java`（API 不变）、`ToolDef.java`、`ToolParam.java`、provider 层、Agent 层。

## 2. 模块 1：Parser 替换

### 2.1 替换前（根因）

`parseSimpleJson`（ToolRegistry.java:183-222）是手写状态机，值分支只有：`"字符串"`（不含转义/内嵌逗号处理）/ `true`/`false`/`null`/裸 token 兜底。**无 `[`/`{` 嵌套处理**。对 `{"points":[12,34]}` → 剥 `{}` → 值分支从 `[` 读到首个 `,` → 存 `"[12"`。

### 2.2 替换后

用 Jackson `ObjectMapper`（`MessageSerializer` 已是同款用法，依赖已就位）：

```java
private static final ObjectMapper MAPPER = new ObjectMapper();

@SuppressWarnings("unchecked")
private Map<String, Object> parseArguments(String json) {
    if (json == null || json.isBlank()) return new HashMap<>();
    try {
        Object parsed = MAPPER.readValue(json.trim(), Object.class);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new IllegalArgumentException("工具参数必须是 JSON 对象: " + json);
    } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("工具参数 JSON 解析失败: " + json, e);
    }
}
```

**为什么 `readValue(json, Object.class)` 而非 `Map.class`**：后者对合法的 JSON 数组顶层入参会抛错（信息差），而先读 `Object` 再判 `Map` 能区分「语法错」vs「结构错」，给出更精准的中文消息。

### 2.3 Jackson 默认类型映射（与下游消费链路兼容性核对）

| JSON 类型 | Jackson 解析结果类型 | 下游是否兼容 |
|---|---|---|
| `"abc"` | `String` | ✅ `getString` 走 `toString()` |
| `12` / `1.5` | `Integer` / `Double` | ✅ `getInt/getLong/getDouble` 先判 `instanceof Number`；`convertType` 走 `parseInt(value.toString())` |
| `true` | `Boolean` | ✅ `getBoolean` 先判 `instanceof Boolean` |
| `null` | `null` | ✅ `getString` 返 null；getter 返默认值 |
| `[1,2,3]` | `ArrayList<Integer>` | ✅ 新增能力，可强转 `List` |
| `{"k":"v"}` | `LinkedHashMap` | ✅ 新增能力，可强转 `Map` |

**R4 向后兼容关键点**：数字从 String→Number 是**唯一的行为差异**，三条消费链路（`ToolArgs.getInt/getLong/getDouble`、注解 `convertType`、`getString`）都通过「先 instanceof Number / 再 toString() / 走 parseInt」兜底，Number 类型下结果与修复前一致。`getString`：`Number.toString()` 返回 `"12"`，与旧 String `"12"` 文本一致。测试须显式覆盖此回归。

### 2.4 方法可见性

旧 `parseSimpleJson` 是 `private`。新方法 `parseArguments` 保持 `private`，仅 `doExecute`（ToolRegistry.java:129）一处调用点改名。**不暴露为 public**（parser 不是公开 API，避免契约固化）。

### 2.5 异常规范

遵循 `.trellis/spec/backend/error-handling.md`：标准 Java 异常（`IllegalArgumentException`）+ fail-fast + 中文描述性消息 + 包装时保留 cause。**不静默吞错**（旧实现对非法 JSON 是把残片当字符串塞进 map，属隐性行为，新实现显式 fail）。

## 3. 模块 2：Schema 生成增强（注解 + fluent）

### 3.1 现状

- `javaTypeToJsonType`（ToolRegistry.java:157-168）对 `List`/`Set`/数组 → `"string"`（**bug 级功能缺失**）。
- `Tool.Builder.addProperty(name, type, desc, required)` 仅存 type；`Property` 无 `items` 字段；`toFunctionDefinition` 不输出 `items`。

### 3.2 设计

**`Property` 增量扩展**（不改现有构造与标量输出）：

```java
private static class Property {
    final String type;
    final String description;
    final List<String> enumValues;
    final Property items;   // 新增，仅 type=array 时非 null

    // 保留旧构造（enum 重载）→ items=null，行为不变
    Property(String type, String description, List<String> enumValues) {
        this(type, description, enumValues, null);
    }
    Property(String type, String description, List<String> enumValues, Property items) {
        this.type = type; this.description = description;
        this.enumValues = enumValues; this.items = items;
    }
}
```

**`toFunctionDefinition` 输出 items**（仅当 `items != null`）：

```java
if (entry.getValue().items != null) {
    Map<String, Object> itemsSchema = new LinkedHashMap<>();
    itemsSchema.put("type", entry.getValue().items.type);
    prop.put("items", itemsSchema);  // MVP: items 仅含 type，无嵌套 properties
}
```

**`Tool.Builder` 新增重载**（不改现有 `addProperty`）：

```java
public Builder addProperty(String name, String type, String description,
                           boolean isRequired, String itemsType) {
    Property items = itemsType != null ? new Property(itemsType, null, null) : null;
    this.properties.put(name, new Property(type, description, null, items));
    if (isRequired) this.required.add(name);
    return this;
}
```

fluent 方式：`getTools()` 内 fluent 分支调旧 `addProperty`（无 items）。要给 fluent array 也加 items，需让 `ParamDef` 携带元素类型——但 fluent 用户已手动传 `"array"` 字符串，且 `Registration.param(...)` API 现状只接 4 参。**fluent items 留作未来增强**，本任务 fluent 方式仍按现状（type=array 但无 items，LLM 多半仍能返回数组，parser 端已能正确解析）。注解方式是本次重点，必须完整支持 items。

> ⚠️ 决策点（design review 可调）：fluent 方式的 `items` 是否本任务必须？倾向**不做**——fluent 用户传 `"array"` 是显式声明，LLM 即便缺 `items` 通常也能返回数组，parser 端已能消费。若 review 认为必须对称，再给 `Registration.param` 加重载。prd R7 措辞「fluent 方式的 array 声明同样应能生成 items」偏理想态，design 层面保守定为「注解方式必做，fluent 方式 best-effort 不阻塞」。

### 3.3 注解方式元素类型推断（D2 自动推断）

`getTools()` 注解分支改用 `getGenericParameterTypes()` 拿 `Type`，推断逻辑：

```java
// 注解分支（替换现 javaTypeToJsonType 单参调用）
for (int i = 0; i < params.length; i++) {
    ToolParam tp = params[i].getAnnotation(ToolParam.class);
    String paramName = tp != null ? tp.name() : params[i].getName();
    String paramDesc = tp != null ? tp.description() : "";
    boolean required = tp == null || tp.required();

    Type genericType = genericParamTypes[i];
    String jsonType = javaTypeToJsonType(params[i].getType());
    String itemsType = inferItemsType(genericType, params[i].getType()); // null 表示非数组
    if (itemsType != null) {
        builder.addProperty(paramName, jsonType, paramDesc, required, itemsType);
    } else {
        builder.addProperty(paramName, jsonType, paramDesc, required);
    }
}
```

**`javaTypeToJsonType` 增强**（新增容器判断，放在标量判断之前）：

```java
private String javaTypeToJsonType(Class<?> type) {
    if (type == int.class || type == Integer.class ||
        type == long.class || type == Long.class ||
        type == double.class || type == Double.class ||
        type == float.class || type == Float.class) {
        return "number";
    }
    if (type == boolean.class || type == Boolean.class) return "boolean";
    if (type.isArray() || List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type))
        return "array";
    if (Map.class.isAssignableFrom(type)) return "object";
    return "string";
}
```

**`inferItemsType`（新增私有方法）**：

```java
private String inferItemsType(Type genericType, Class<?> rawType) {
    // Java 数组：元素类型 = getComponentType()
    if (rawType.isArray()) return javaTypeToJsonType(rawType.getComponentType());
    // List/Set 带泛型：ParameterizedType 保留元素类型
    if (genericType instanceof ParameterizedType) {
        Type[] args = ((ParameterizedType) genericType).getActualTypeArguments();
        if (args.length > 0 && args[0] instanceof Class) {
            // List<String[]> 等嵌套不做（MVP），只取 Class 元素
            return javaTypeToJsonType((Class<?>) args[0]);
        }
    }
    // raw List/Set 无泛型 → 兜底 string
    if (List.class.isAssignableFrom(rawType) || Set.class.isAssignableFrom(rawType))
        return "string";
    return null; // 非数组
}
```

**示例输出**：
- `@ToolParam(name="points") List<Integer> points` → `{"type":"array","items":{"type":"number"}}`
- `@ToolParam(name="tags") String[] tags` → `{"type":"array","items":{"type":"string"}}`
- `@ToolParam(name="data") Map<String,Object> data` → `{"type":"object"}`（无 items）
- `@ToolParam(name="raw") List raw` → `{"type":"array","items":{"type":"string"}}`（兜底）

## 4. 模块 3：convertType 适配容器/数组

### 4.1 现状

`convertType`（ToolRegistry.java:170-178）只处理标量，未知类型原样返回 value（对 List/数组目标会失败，因为 Jackson 解析出的 `ArrayList`/`LinkedHashMap` 无法强转成 `String[]` / `HashSet`）。

### 4.2 增强（在标量分支后追加容器分支）

```java
private Object convertType(Object value, Class<?> targetType) {
    if (value == null) return null;
    // 标量分支保持不变 ...
    // 新增容器/数组分支
    if (targetType.isArray()) {
        List<?> list = coerceToList(value);
        Object array = Array.newInstance(targetType.getComponentType(), list.size());
        for (int i = 0; i < list.size(); i++) {
            Array.set(array, i, convertType(list.get(i), targetType.getComponentType()));
        }
        return array;
    }
    if (List.class.isAssignableFrom(targetType)) return coerceToList(value);
    if (Set.class.isAssignableFrom(targetType)) return new LinkedHashSet<>(coerceToList(value));
    if (Map.class.isAssignableFrom(targetType) && value instanceof Map) return value;
    return value; // 兜底
}

private List<?> coerceToList(Object value) {
    if (value instanceof List) return (List<?>) value;
    if (value.getClass().isArray()) {
        int len = Array.getLength(value);
        List<Object> list = new ArrayList<>(len);
        for (int i = 0; i < len; i++) list.add(Array.get(value, i));
        return list;
    }
    throw new IllegalArgumentException("无法转换为 List: " + value.getClass());
}
```

**递归元素转换**：`int[]` 目标时，Jackson 给的 `ArrayList<Integer>` 元素是 `Integer`，需 `convertType(elem, int.class)` 转成 `int` 装箱。数组分支内递归调用 `convertType` 处理。

## 5. 数据流（端到端）

```
LLM 返回 arguments='{"points":[12,34]}'
  → ToolRegistry.execute(name, arguments)
  → doExecute → parseArguments  [Jackson → {points: ArrayList[12,34]}]
  → 注解方式: 循环 param → convertType(ArrayList, targetType)
            - targetType=List → coerceToList 直接返回
            - targetType=int[] → 逐元素 convertType(elem, int)
  → method.invoke(target, callArgs)   [无 ClassCastException]
```

## 6. 兼容性 & 风险

| 风险 | 缓解 |
|---|---|
| 数字 String→Number 改变类型 | 三条消费链路均有 instanceof/toString 兜底，测试显式覆盖 |
| `Property` 加字段破坏序列化 | `Property` 是 private 不可变值对象，无外部消费者 |
| 泛型推断拿不到（raw type） | 兜底 `items:{type:string}`，不抛错 |
| Jackson 解析大数字精度 | 工具参数场景无超大数；`Integer`/`Double` 默认足够 |
| 现有 `@ToolParam` 用法回归 | API 零变更，标量注解工具 schema 输出不变 |

**回滚**：三模块独立，parser 可单独回滚（但会丢 array 能力）；schema/convertType 增强是纯增量，可保留。极端情况整体 `git revert` 单次提交。

## 7. Out of Scope（重申）

- 不做 POJO/深嵌套对象 schema（`items:{type:object}` 到顶）。
- 不改 `ToolArgs` API。
- fluent 方式 `items` 为 best-effort，不阻塞验收。
- 不涉及 provider/Agent 层。
