# 图工作流引擎

## 概述

nonchain 的图工作流引擎（`com.non.chain.flow`）提供了一个轻量级的**有向图执行引擎**，用于编排多步骤的 AI 处理流水线。通过将复杂的工作流抽象为节点（Node）和边（Edge），开发者可以灵活地构建顺序执行、条件分支、多路汇聚等各种处理流程。

核心设计理念：

- **声明式构建**：通过 Builder 模式定义图结构，与执行逻辑解耦
- **不可变状态**：每个节点处理状态后返回新状态，保证执行的可追溯性
- **条件路由**：支持基于运行时状态的条件分支，实现动态流程控制
- **完整追踪**：记录全部状态历史和节点执行路径，便于调试与审计

## 核心概念

### Graph（图）

图是整个工作流引擎的入口。它由一组节点和边组成，定义了数据处理的整体拓扑结构。图构建完成后是不可变的，可以被多次执行。

```java
Graph graph = Graph.builder("my-workflow")
        .start("nodeA")
        .addNode(nodeA)
        .addNode(nodeB)
        .addEdge(Edge.of("nodeA", "nodeB"))
        .build();
```

### Node（节点）

节点是图中的处理单元，每个节点包含一个名称和一个处理函数 `Function<State, State>`。节点接收当前状态，执行业务逻辑后返回处理后的状态。

### Edge（边）

边定义了节点之间的流转关系。支持两种类型：
- **无条件边**：始终从源节点流转到目标节点
- **条件边**：根据当前状态动态决定下一个节点

### State（状态）

状态是节点间传递数据的载体。它包含一个键值对数据存储（`Map<String, Object>`）和消息历史列表（`List<Message>`）。状态在节点之间流转，每个节点可以读取和修改状态。

### GraphResult（执行结果）

图的执行结果，包含最终状态、完整的状态历史和已执行的节点列表。

## API 参考

### Graph

| 方法 | 说明 |
|------|------|
| `builder(String name)` | 创建 Graph 构建器，指定图名称 |
| `addNode(Node node)` | 添加处理节点 |
| `addEdge(Edge edge)` | 添加边 |
| `start(String nodeName)` | 设置起始节点 |
| `build()` | 构建图（必须至少包含一个节点且指定起始节点） |
| `run(State initialState)` | 执行图，返回 GraphResult |
| `name()` | 获取图名称 |
| `END = "__END__"` | 终止常量，条件路由返回此值时终止执行 |

**Builder 方法链**：`builder`、`addNode`、`addEdge`、`start` 均返回 Builder 本身，支持链式调用。

### Node

| 方法 | 说明 |
|------|------|
| `new Node(String name, Function<State, State> processor)` | 构造节点 |
| `name()` | 获取节点名称 |
| `apply(State state)` | 执行处理器，返回处理后的状态 |

### Edge

| 方法 | 说明 |
|------|------|
| `of(String from, String to)` | 无条件边，始终从 `from` 流转到 `to` |
| `conditional(String from, Function<State, String> router)` | 条件路由边，`router` 函数根据状态返回下一个节点名称；返回 `Graph.END` 表示终止 |
| `from()` | 获取源节点名称 |
| `route(State state)` | 根据当前状态计算下一个节点 |

### State

| 方法 | 说明 |
|------|------|
| `new State()` | 构造空状态 |
| `new State(State other)` | 拷贝构造 |
| `put(String key, Object value)` | 存入数据，返回 this |
| `get(String key)` | 获取数据，返回 `Optional<T>` |
| `getOrDefault(String key, T defaultValue)` | 带默认值获取 |
| `has(String key)` | 判断 key 是否存在 |
| `addMessage(Message message)` | 添加消息到历史记录 |
| `history()` | 获取消息历史（不可变列表） |
| `lastAssistantMessage()` | 获取最后一条助手消息，返回 `Optional<String>` |

### GraphResult

| 方法 | 说明 |
|------|------|
| `finalState()` | 获取最终状态 |
| `history()` | 获取所有状态历史（包含初始状态和每个节点处理后的状态） |
| `executedNodes()` | 获取已执行节点名称列表 |
| `printTrace()` | 打印执行轨迹，展示每个节点的状态变化 |

## 使用示例

### 条件路由工作流

以下示例实现了一个根据问题类型进行分类路由的工作流：技术问题走技术专家分支，通用问题走通用助手分支，最后汇聚到总结节点。

```java
import com.non.chain.*;
import com.non.chain.flow.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

public class ConditionalWorkflowExample {

    public static void main(String[] args) {
        // 1. 初始化 LLM
        LLM llm = new DashscopeLLM("qwen3.5-35b-a3b", 512)
                .enableThinking(true)
                .thinkingBudget(512);

        // 2. 构建图
        Graph graph = Graph.builder("conditional-pipeline")
                .start("classify")

                // ---- 节点定义 ----

                // 分类节点：判断问题类型
                .addNode(new Node("classify", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String userInput = state.getOrDefault("userInput", "");

                    ChatResult result = chat.chat(
                            "你是一个分类器。只回答 'technical' 或 'general'。"
                                    + "技术类问题（编程、数学、科学）回答 technical，其他回答 general。",
                            userInput
                    );

                    state.put("category", result.content().trim().toLowerCase());
                    state.addMessage(Message.user(userInput));
                    System.out.println("[classify] 分类结果: " + state.getOrDefault("category", ""));
                    return state;
                }))

                // 技术分支节点
                .addNode(new Node("technical", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String userInput = state.getOrDefault("userInput", "");

                    ChatResult result = chat.chat(
                            "你是一个技术专家。用专业但易懂的方式回答以下技术问题，包含具体细节。",
                            userInput
                    );

                    state.put("draftAnswer", result.content());
                    System.out.println("[technical] 技术解答完成");
                    return state;
                }))

                // 通用分支节点
                .addNode(new Node("general", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String userInput = state.getOrDefault("userInput", "");

                    ChatResult result = chat.chat(
                            "你是一个友好的助手。用通俗简洁的方式回答以下问题。",
                            userInput
                    );

                    state.put("draftAnswer", result.content());
                    System.out.println("[general] 通用解答完成");
                    return state;
                }))

                // 汇聚总结节点
                .addNode(new Node("summarize", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String draft = state.getOrDefault("draftAnswer", "");

                    ChatResult result = chat.chat(
                            "将以下内容总结为简洁清晰的最终回答。",
                            draft
                    );

                    state.put("finalAnswer", result.content());
                    state.addMessage(Message.assistant(result.content()));
                    System.out.println("[summarize] 总结完成");
                    return state;
                }))

                // ---- 边定义 ----

                // classify 根据分类结果条件路由
                .addEdge(Edge.conditional("classify", state -> {
                    String category = state.getOrDefault("category", "general");
                    if (category.contains("technical")) {
                        return "technical";
                    }
                    return "general";
                }))

                // 两个分支都汇聚到 summarize
                .addEdge(Edge.of("technical", "summarize"))
                .addEdge(Edge.of("general", "summarize"))

                // summarize 执行后结束
                .addEdge(Edge.of("summarize", Graph.END))

                .build();

        // 3. 准备初始状态并执行
        State initialState = new State()
                .put("llm", llm)
                .put("userInput", "量子计算是什么？它有哪些潜在应用？");

        System.out.println("=== 开始执行 Pipeline: " + graph.name() + " ===\n");
        GraphResult result = graph.run(initialState);

        // 4. 查看结果
        System.out.println("\n=== 执行路径 ===");
        System.out.println(String.join(" -> ", result.executedNodes()) + " -> END");

        System.out.println("\n=== 最终回答 ===");
        System.out.println(result.finalState().getOrDefault("finalAnswer", ""));

        System.out.println("\n=== 历史状态追踪 ===");
        result.printTrace();
    }
}
```

执行输出示例：

```
=== 开始执行 Pipeline: conditional-pipeline ===

[classify] 分类结果: technical
[technical] 技术解答完成
[summarize] 总结完成

=== 执行路径 ===
classify -> technical -> summarize -> END

=== 最终回答 ===
量子计算是一种利用量子力学原理进行计算的新型计算范式...

=== 历史状态追踪 ===
=== 初始状态 ===
State{dataKeys=[llm, userInput], history=[]}

=== [classify] ===
State{dataKeys=[llm, userInput, category], history=[user: 量子计算是什么？...]}
...
```

### RAG 管道工作流

以下示例展示了如何将 KnowledgeStore 集成到 Graph 工作流中，实现一个典型的 RAG（检索增强生成）管道。

```java
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.flow.Edge;
import com.non.chain.flow.Graph;
import com.non.chain.flow.GraphResult;
import com.non.chain.flow.Node;
import com.non.chain.flow.State;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.pgvector.PgvectorKnowledgeStore;

import java.util.List;

public class RagWorkflowExample {

    public static void main(String[] args) {
        EmbeddingModel embeddingModel = ...;  // 你的 EmbeddingModel 实例
        PgvectorKnowledgeStore store = ...;   // 你的 KnowledgeStore 实例

        // Node 1: 将用户 query 转为向量
        Node embedQuery = new Node("embedQuery", state -> {
            String query = state.<String>get("query").orElseThrow();
            float[] vec = embeddingModel.embed(query);
            return state.put("queryEmbedding", vec);
        });

        // Node 2: 向量检索
        Node retrieve = new Node("retrieve", state -> {
            float[] vec = state.<float[]>get("queryEmbedding").orElseThrow();
            SearchRequest req = SearchRequest.builder(vec)
                    .topK(3)
                    .addKnowledgeBaseId("kb-demo")
                    .build();
            List<SearchResult> results = store.search(req);
            return state.put("retrievedChunks", results);
        });

        // Node 3: 基于检索结果生成回答
        Node generate = new Node("generate", state -> {
            @SuppressWarnings("unchecked")
            List<SearchResult> chunks = state.<List<SearchResult>>get("retrievedChunks")
                    .orElseThrow();
            StringBuilder context = new StringBuilder();
            for (SearchResult r : chunks) {
                context.append(r.content()).append("\n");
            }
            // 将 context + query 传给 LLM 生成答案
            return state.put("context", context.toString());
        });

        // 构建并执行 RAG 图
        Graph graph = Graph.builder("rag-demo")
                .addNode(embedQuery)
                .addNode(retrieve)
                .addNode(generate)
                .start("embedQuery")
                .addEdge(Edge.of("embedQuery", "retrieve"))
                .addEdge(Edge.of("retrieve", "generate"))
                .addEdge(Edge.of("generate", Graph.END))
                .build();

        State initial = new State().put("query", "什么是向量数据库？");
        GraphResult result = graph.run(initial);
        System.out.println("执行节点: " + result.executedNodes());
    }
}
```

## 执行机制

1. 图从 `start` 指定的起始节点开始执行
2. 获取当前节点并调用其 `processor` 处理状态
3. 处理完成后，查找该节点的出边（Edge），通过 `route` 方法确定下一个节点
4. 如果 `route` 返回 `Graph.END`（`"__END__"`）或没有出边，则执行结束
5. 如果指定的下一个节点不存在，抛出 `IllegalStateException`

## 注意事项

- 节点的 `processor` 函数直接修改传入的 State 对象（非纯函数），如果在分支场景下需要隔离状态，应在节点内自行拷贝
- `GraphResult.history()` 返回的列表包含初始状态（索引 0）和每个节点处理后的状态
- `State` 的 `get` 方法返回 `Optional`，`getOrDefault` 方法直接返回值，使用时注意类型安全
- 图构建后不可修改，但可以被多次执行，每次执行传入不同的初始状态即可
