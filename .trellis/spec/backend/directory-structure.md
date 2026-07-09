# Directory Structure

> How backend code is organized in this project.

---

## Overview

This is a Java 11+ library project (minimum Java 11, compatible with JDK 11/17/21) for building LLM-powered applications. It provides LLM provider abstraction, tool/function calling, and a graph-based workflow engine. There is no web framework or database layer.

---

## Directory Layout

```
src/main/java/com/non/chain/
├── ChatResult.java           # Core model: LLM chat response (content + thinking + toolCalls)
├── ChatChunk.java            # Streaming model: incremental chunk (deltaContent + deltaThinking + deltaToolCalls)
├── Message.java              # Core model: chat message (system/user/assistant/tool, supports multimodal contentParts)
├── ContentPart.java          # Interface: multimodal content part marker (TextPart, ImageUrlPart)
├── TextPart.java             # Text content part for multimodal messages
├── ImageUrlPart.java         # Image URL content part for multimodal messages
├── provider/
│   ├── LLM.java              # Interface: LLM provider contract
│   └── DashscopeLLM.java     # Implementation: Dashscope (Alibaba Cloud) provider
├── memory/
│   ├── ChatMemory.java       # Interface: conversation memory strategy (add/get/clear)
│   ├── ChatMemoryStore.java  # Interface: conversation storage abstraction
│   ├── Tokenizer.java        # Interface: token counting for memory pruning
│   ├── JtokkitTokenizer.java # Implementation: jtokkit-based token counting
│   ├── InMemoryChatMemoryStore.java # Implementation: in-memory message storage
│   ├── MessageWindowChatMemory.java # Strategy: sliding window message pruning
│   └── TokenWindowChatMemory.java   # Strategy: token-based message pruning
├── tool/
│   ├── Tool.java             # Tool definition (schema for function calling)
│   ├── ToolCall.java         # Represents a tool call returned by LLM
│   ├── ToolArgs.java         # Type-safe argument accessor for tool execution
│   ├── ToolHandler.java      # Functional interface: tool execution handler
│   ├── ToolDef.java          # Annotation: marks a method as an LLM-callable tool
│   ├── ToolParam.java        # Annotation: marks tool method parameters
│   └── ToolRegistry.java     # Central registry: annotation scan + fluent API + SubAgent registration
├── agent/
│   ├── Agent.java            # LLM + tool loop executor (Builder pattern, memory/callback/streaming/interceptors)
│   ├── AgentEvent.java       # Streaming event interface (TextDelta/ThinkingDelta/ToolStart/ToolEnd/...)
│   ├── AgentException.java   # Unchecked exception for agent loop failures
│   ├── BeforeToolCall.java   # Interceptor: before tool execution (can block)
│   ├── AfterToolCall.java    # Interceptor: after tool execution (can rewrite result)
│   ├── BeforeResult.java     # before interceptor return type (pass/block)
│   ├── AfterResult.java      # after interceptor return type (keep/rewrite)
│   ├── ToolCallContext.java  # Immutable interceptor input context
│   ├── SubAgentExposureMode.java  # Enum: DIRECT (default, one tool per sub-agent) / DELEGATE (single delegate tool)
│   ├── SubAgentDefinition.java    # Immutable value object: sub-agent config (name/desc/systemPrompt/llm/tools/...)
│   └── ContextSelector.java       # Functional interface: parent-context pruning strategy for sub-agents
├── flow/
│   ├── Node.java             # Workflow node: wraps a Function<State, State>
│   ├── Edge.java             # Workflow edge: unconditional or conditional routing
│   ├── State.java            # Shared mutable state passed between nodes
│   ├── Graph.java            # DAG execution engine: runs nodes following edges
│   └── GraphResult.java      # Execution result: final state + history + trace
├── trace/                    # Execution telemetry: OTel-style span tree (orthogonal to user-facing callback)
│   ├── Span.java             # Strongly-typed skeleton + schemaless attributes payload
│   ├── Trace.java            # One runtime execution: flat span list + runtimeId/conversationId + JSON
│   ├── SpanContext.java      # Immutable span context (runtimeId/spanId/parentSpanId) — propagation truth source
│   ├── SpanAttributes.java   # Attribute key constants + span type constants (guard typos)
│   ├── Tracer.java           # Span builder + current-span ThreadLocal stack + ScopedSpan scope
│   ├── TraceStore.java       # SPI: record(span) / getTrace(id)
│   ├── InMemoryTraceStore.java  # Bounded LRU in-memory default impl (concurrent-safe)
│   ├── TraceSerializer.java  # Trace ↔ JSON (stable schema for future external stores)
│   ├── RecordingCallback.java   # Callback → span payload bridge (fills attributes only)
│   ├── TraceMarker.java      # Suppressed-exception marker carrying runtimeId (failure path)
│   └── TraceRuntimeIds.java  # Extract runtimeId from exception chain (failure path)
└── example/
    ├── FunctionCallExample.java          # Demo: annotation-based tool definition
    ├── FunctionCallMultiParamExample.java # Demo: multi-param tools (annotation vs fluent)
    ├── FunctionCallRawExample.java        # Demo: fluent API tool definition
    └── EasyWorkflowExample.java           # Demo: graph workflow with conditional routing
```

---

## Module Organization

### Adding a new LLM provider
1. Create class in `provider/` implementing the `LLM` interface
2. Implement all four `chat()` overloads (sync) and all four `streamChat()` overloads (streaming via `Consumer<ChatChunk>`)
3. Handle provider-specific features (e.g., thinking mode) via additional builder methods

### Adding a new tool capability
Two supported approaches:
1. **Annotation style** — Create a service class with `@ToolDef` methods and `@ToolParam` parameters, register via `ToolRegistry.scan(object)`
2. **Fluent API style** — Call `registry.register(name, desc).param(...).handle(...)` directly

### Registering a delegated sub-agent (SubAgent)
Delegated sub-agents are registered in `ToolRegistry`, exposed by `Agent.Builder`:
1. Call `registry.registerSubAgent(name, description)` → declarative `SubAgentRegistration` Builder (`description` for LLM schema, `systemPrompt` for sub-agent role — both required, defined separately)
2. Configure optional fields: `.toolRegistry(childTools)` (nullable = no-tool sub-agent), `.llm(override)` (default = inherit parent LLM), `.maxIterations(n)`, `.contextSelector(strategy)`, `.addBeforeToolCall/.addAfterToolCall` (scoped to sub-agent internals)
3. `.build()` writes the `SubAgentDefinition` into `ToolRegistry`
4. Parent `Agent` picks exposure mode via `.subAgentExposureMode(DIRECT|DELEGATE)` (build-time fixed, default `DIRECT`)
5. Only runs inside the `Agent` auto-loop; hand-written `registry.execute("subAgent", ...)` fails fast

### Adding a new workflow pattern
1. Define `Node` instances with `Function<State, State>` processors
2. Define `Edge` instances for routing (use `Edge.of()` for unconditional, `Edge.conditional()` for branching)
3. Assemble via `Graph.builder(name).start(...).addNode(...).addEdge(...).build()`

### Enabling execution telemetry (trace recording)
Execution telemetry is **orthogonal to the user-facing `ChainCallback`** and **opt-in (default off = zero overhead)**:
1. Build an `InMemoryTraceStore` (or a custom `TraceStore` impl)
2. `Agent.builder(llm, registry).trace(store)` and/or `Graph.builder(name).traceStore(store)`
3. Run as usual; read `ChatResult.runtimeId()` / `GraphResult.runtimeId()` (nullable — null when recording is off)
4. Pull the full span tree back with `store.getTrace(runtimeId)` and serialize via `Trace.toJson()`

**Contracts**:
- `trace/` is a new package parallel to `callback/` — `callback/` is user-facing observation, `trace/` is recording-side observation (orthogonal responsibilities).
- The recording layer has its OWN span propagation path: `SpanContext` (truth source) + `Tracer` current-span ThreadLocal stack + explicit propagation at three hard boundaries (SubAgent build point, parallel-tool worker, Flow node). It does **not** parasitize `ChainCallback`, so it bypasses SubAgent's `noop()` isolation and achieves full-tree drill-down.
- Failure path keeps the original exception type/semantics unchanged; `runtimeId` is exposed via a **suppressed `TraceMarker`** + `TraceRuntimeIds.find(throwable)` — never a wrapping exception.
- `ChatResult`/`GraphResult` gained a nullable `runtimeId()` — purely additive, no breaking change.
- `State` gained a `data()` accessor (read-only) for `state_in`/`state_out` capture — purely additive.
- **Persistent stores**: `chain-mysql` / `chain-postgres` provide `MysqlTraceStore` / `PostgresTraceStore` (`TraceStore` impls) that persist each span to a `trace_span` table (schema in each module's `trace_span.sql`). `record(span)` does a portable idempotent DELETE+INSERT inside one transaction (H2/MySQL/PostgreSQL universal); `attributes` is serialized as JSON via `TraceSerializer.serializeAttributes`. `Span.restored(...)` rebuilds a finalized span from a persisted row.

---

## Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Package names | Lowercase, no underscores | `provider`, `tool`, `flow` |
| Class names | PascalCase | `ChatResult`, `ToolRegistry` |
| Method names | camelCase | `toMessage()`, `hasToolCalls()` |
| Constants | UPPER_SNAKE_CASE | `Graph.END` |
| Builder methods | camelCase, return `this` | `.description()`, `.param()` |
| Accessor methods | No `get` prefix, field name as method | `content()`, `role()`, `name()` |
| Factory methods | camelCase, return new instance | `Message.user()`, `Edge.of()` |
| Boolean check methods | `has` prefix | `hasThinking()`, `hasToolCalls()` |

---

## Examples

**Well-organized module — `tool/` package**:
- Clear separation: definition (`Tool`), invocation (`ToolCall`), execution (`ToolHandler`, `ToolArgs`), registration (`ToolRegistry`)
- Two registration patterns coexist in `ToolRegistry` via internal `ToolEntry` abstraction
- `src/main/java/com/non/chain/tool/`

**Builder pattern — `Tool` class**:
- Private constructor, static `builder()` factory, inner `Builder` class
- `src/main/java/com/non/chain/tool/Tool.java`

**Immutable value object — `Message` class**:
- Private constructor, static factory methods (`system()`, `user()`, `user(List<ContentPart>)`, `assistant()`, `toolResult()`)
- Supports multimodal content via `contentParts` field (text + image)
- `src/main/java/com/non/chain/Message.java`

**Interface + implementations — `ContentPart` hierarchy**:
- Marker interface `ContentPart` with two implementations: `TextPart`, `ImageUrlPart`
- Used with `Message.user(List<ContentPart>)` for multimodal user messages
- Follows immutable value object pattern (private final fields, static `of()` factory)
- `src/main/java/com/non/chain/ContentPart.java`, `TextPart.java`, `ImageUrlPart.java`

**Streaming model — `ChatChunk` class**:
- Immutable value object with `deltaContent`, `deltaThinking`, `List<DeltaToolCall>`, `finishReason`
- Inner class `DeltaToolCall` for incremental tool call fragments (index + id + name + argumentsDelta)
- Boolean check methods: `hasContent()`, `hasThinking()`, `hasToolCalls()`, `isFinished()`
- `src/main/java/com/non/chain/ChatChunk.java`
