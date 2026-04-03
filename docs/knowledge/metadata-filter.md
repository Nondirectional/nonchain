# MetadataFilter 元数据过滤

## 概述

`MetadataFilter`（`com.non.chain.knowledge.MetadataFilter`）提供了一种可组合的元数据过滤机制。通过条件过滤和逻辑组合（AND、OR、NOT），可以构建复杂的元数据查询条件，与 `SearchRequest` 配合使用实现对检索结果的精确过滤。

核心设计理念：

- **可组合性**：支持任意深度的逻辑嵌套，灵活构建复杂查询
- **类型安全**：通过枚举定义过滤类型和操作符，避免字符串拼写错误
- **不可变**：构建完成后 filter 不可修改，保证线程安全

## 枚举定义

### Type（过滤类型）

| 枚举值 | 说明 |
|--------|------|
| `CONDITION` | 条件过滤，对单个字段进行比较 |
| `AND` | 逻辑与，所有子条件必须同时满足 |
| `OR` | 逻辑或，至少一个子条件满足 |
| `NOT` | 逻辑非，取反子条件 |

### Operator（操作符）

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `EQ` | 等于 | `category EQ "tech"` |
| `NE` | 不等于 | `status NE "archived"` |
| `GT` | 大于 | `version GT 2` |
| `GTE` | 大于等于 | `priority GTE 3` |
| `LT` | 小于 | `score LT 0.5` |
| `LTE` | 小于等于 | `year LTE 2025` |
| `IN` | 包含于列表 | `tag IN ["java", "ai"]` |
| `EXISTS` | 字段存在 | `author EXISTS`（不需要 value） |

## API 参考

### 静态工厂方法

| 方法 | 说明 |
|------|------|
| `condition(String key, Operator operator, Object value)` | 创建条件过滤 |
| `and(List<MetadataFilter> filters)` | 逻辑与，所有子条件必须同时满足 |
| `or(List<MetadataFilter> filters)` | 逻辑或，至少一个子条件满足 |
| `not(MetadataFilter filter)` | 逻辑非，取反子条件 |

### 访问器方法

| 方法 | 说明 |
|------|------|
| `type()` | 获取过滤类型 |
| `key()` | 获取字段名（仅 CONDITION 类型有值） |
| `operator()` | 获取操作符（仅 CONDITION 类型有值） |
| `value()` | 获取比较值（仅 CONDITION 类型有值） |
| `children()` | 获取子过滤条件列表（仅 AND、OR、NOT 类型有值） |

## 使用示例

### 条件过滤

最基础的过滤方式，对单个元数据字段进行比较。

```java
import com.non.chain.knowledge.MetadataFilter;
import static com.non.chain.knowledge.MetadataFilter.Operator;

// 等于过滤：source 字段等于 "docs"
MetadataFilter filter = MetadataFilter.condition("source", Operator.EQ, "docs");

// 不等于过滤：status 不等于 "archived"
MetadataFilter filter2 = MetadataFilter.condition("status", Operator.NE, "archived");

// 大于过滤：version 大于 2
MetadataFilter filter3 = MetadataFilter.condition("version", Operator.GT, 2);

// IN 过滤：tag 包含在列表中
MetadataFilter filter4 = MetadataFilter.condition("tag", Operator.IN,
        List.of("java", "ai", "llm"));

// EXISTS 过滤：author 字段存在（不需要 value）
MetadataFilter filter5 = MetadataFilter.condition("author", Operator.EXISTS, null);
```

### 逻辑与（AND）

所有子条件必须同时满足。

```java
import com.non.chain.knowledge.MetadataFilter;
import static com.non.chain.knowledge.MetadataFilter.Operator;

// 同时满足：source 等于 "docs" 且 year 大于等于 2024
MetadataFilter andFilter = MetadataFilter.and(List.of(
        MetadataFilter.condition("source", Operator.EQ, "docs"),
        MetadataFilter.condition("year", Operator.GTE, 2024)
));

// 多条件组合：知识库为 "kb-tech" 且 tag 包含 "java" 且 status 不等于 "archived"
MetadataFilter complexAnd = MetadataFilter.and(List.of(
        MetadataFilter.condition("knowledgeBaseId", Operator.EQ, "kb-tech"),
        MetadataFilter.condition("tag", Operator.IN, List.of("java")),
        MetadataFilter.condition("status", Operator.NE, "archived")
));
```

### 逻辑或（OR）

至少一个子条件满足即可。

```java
import com.non.chain.knowledge.MetadataFilter;
import static com.non.chain.knowledge.MetadataFilter.Operator;

// source 等于 "docs" 或 source 等于 "wiki"
MetadataFilter orFilter = MetadataFilter.or(List.of(
        MetadataFilter.condition("source", Operator.EQ, "docs"),
        MetadataFilter.condition("source", Operator.EQ, "wiki")
));

// tag 为 "java" 或 tag 为 "python"
MetadataFilter tagFilter = MetadataFilter.or(List.of(
        MetadataFilter.condition("tag", Operator.EQ, "java"),
        MetadataFilter.condition("tag", Operator.EQ, "python")
));
```

### 逻辑非（NOT）

取反子条件的结果。

```java
import com.non.chain.knowledge.MetadataFilter;
import static com.non.chain.knowledge.MetadataFilter.Operator;

// source 不等于 "docs"
MetadataFilter notFilter = MetadataFilter.not(
        MetadataFilter.condition("source", Operator.EQ, "docs")
);

// status 不为 "archived" 且 status 不为 "deleted"
MetadataFilter notMultiple = MetadataFilter.and(List.of(
        MetadataFilter.not(MetadataFilter.condition("status", Operator.EQ, "archived")),
        MetadataFilter.not(MetadataFilter.condition("status", Operator.EQ, "deleted"))
));
```

### 嵌套组合

支持任意深度的逻辑嵌套，构建复杂查询条件。

```java
import com.non.chain.knowledge.MetadataFilter;
import static com.non.chain.knowledge.MetadataFilter.Operator;

// 复杂条件：(source 等于 "docs" 且 year >= 2024) 或 (tag 包含 "java")
MetadataFilter nestedFilter = MetadataFilter.or(List.of(
        MetadataFilter.and(List.of(
                MetadataFilter.condition("source", Operator.EQ, "docs"),
                MetadataFilter.condition("year", Operator.GTE, 2024)
        )),
        MetadataFilter.condition("tag", Operator.IN, List.of("java"))
));

// 更复杂的嵌套：NOT (status 等于 "archived" OR status 等于 "deleted")
//                     AND source 等于 "docs"
MetadataFilter complexNested = MetadataFilter.and(List.of(
        MetadataFilter.not(MetadataFilter.or(List.of(
                MetadataFilter.condition("status", Operator.EQ, "archived"),
                MetadataFilter.condition("status", Operator.EQ, "deleted")
        ))),
        MetadataFilter.condition("source", Operator.EQ, "docs")
));
```

### 与 SearchRequest 配合使用

MetadataFilter 通过 `SearchRequest.Builder` 的 `metadataFilter` 方法传入检索请求。

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.*;
import com.non.chain.knowledge.pgvector.PgvectorKnowledgeStore;

import java.util.List;

public class MetadataFilterSearchExample {
    public static void main(String[] args) {
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");
        PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(
                        "jdbc:postgresql://localhost:5432/nonchain", 1024)
                .username("postgres")
                .password("postgres")
                .build();

        // 构建过滤条件：source 为 "docs" 且 year >= 2024
        MetadataFilter filter = MetadataFilter.and(List.of(
                MetadataFilter.condition("source", MetadataFilter.Operator.EQ, "docs"),
                MetadataFilter.condition("year", MetadataFilter.Operator.GTE, 2024)
        ));

        // 构建检索请求
        float[] queryVec = embeddingModel.embed("向量数据库");
        SearchRequest request = SearchRequest.builder(queryVec)
                .topK(10)
                .minScore(0.5)
                .addKnowledgeBaseId("kb-tech")
                .metadataFilter(filter)  // 附加元数据过滤
                .build();

        // 执行检索
        List<SearchResult> results = store.search(request);
        for (SearchResult result : results) {
            System.out.printf("[%.4f] %s | metadata: %s%n",
                    result.score(),
                    result.content(),
                    result.metadata());
        }
    }
}
```

### 按 tag 过滤文档

```java
// 查询 tag 为 "tutorial" 的文档分块
MetadataFilter tagFilter = MetadataFilter.condition("tag", MetadataFilter.Operator.EQ, "tutorial");

float[] queryVec = embeddingModel.embed("如何使用 RAG");
SearchRequest request = SearchRequest.builder(queryVec)
        .topK(5)
        .metadataFilter(tagFilter)
        .build();

List<SearchResult> results = store.search(request);
```

### 排除特定来源

```java
// 排除 source 为 "legacy" 的分块
MetadataFilter excludeLegacy = MetadataFilter.not(
        MetadataFilter.condition("source", MetadataFilter.Operator.EQ, "legacy")
);

float[] queryVec = embeddingModel.embed("最新技术文档");
SearchRequest request = SearchRequest.builder(queryVec)
        .topK(10)
        .metadataFilter(excludeLegacy)
        .build();

List<SearchResult> results = store.search(request);
```

## 异常说明

| 异常场景 | 抛出异常 |
|----------|----------|
| `condition` 的 key 为空或空白 | `IllegalArgumentException` |
| `condition` 的 operator 为 null | `IllegalArgumentException` |
| `condition` 的 operator 非 EXISTS 时 value 为 null | `IllegalArgumentException` |
| `and` / `or` 的过滤列表为 null 或空 | `IllegalArgumentException` |
| `and` / `or` 的过滤列表中包含 null | `NullPointerException` |
| `not` 的过滤条件为 null | `IllegalArgumentException` |

## 注意事项

- `EXISTS` 操作符的 `value` 参数应传入 `null`，仅检查字段是否存在
- `IN` 操作符的 `value` 应传入 `List` 类型
- 数值比较操作符（`GT`、`GTE`、`LT`、`LTE`）要求元数据中对应字段的值类型为数值类型
- MetadataFilter 构建后是不可变的，可以安全地在多线程环境中共享
- MetadataFilter 的实际过滤行为由 KnowledgeStore 实现决定，不同存储后端可能对嵌套深度和操作符支持程度不同
