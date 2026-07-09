# 新增 OpenAI 兼容 Provider 支持 vllm 本地模型

## Goal

支持生产环境通过 vllm-openai 部署的本地模型（LLM 和 Embedding），使 nonchain 框架能对接任何 OpenAI 兼容的推理服务端点。

## Requirements

* 重构现有 DashscopeLLM，提取通用 OpenAI 兼容逻辑到抽象基类 `AbstractOpenAILLM`
* 新增 `OpenAICompatibleLLM`（继承基类），base URL 和 API Key 完全可配置
* 重构 `DashScopeEmbeddingModel`，提取通用逻辑到 `AbstractOpenAIEmbeddingModel`
* 新增 `OpenAICompatibleEmbeddingModel`（继承基类）
* API Key 可选（本地部署无认证时使用占位符或跳过验证）
* 支持 thinking 模式（`enable_thinking` + `reasoning_content`）——vllm 的 qwen3 系列也支持
* 保持 DashScope provider 完全向后兼容

## Acceptance Criteria

* [ ] `OpenAICompatibleLLM` 可通过构造参数指定 base URL（如 `http://10.100.10.21:40000/v1`）和 model
* [ ] 无 API Key 时可正常工作（本地部署无认证场景）
* [ ] 支持 chat 和 streamChat 所有 8 个方法
* [ ] 支持 tool calling（function calling）
* [ ] 支持 thinking 模式（enableThinking + thinkingBudget）
* [ ] 支持 temperature / topP 参数
* [ ] `OpenAICompatibleEmbeddingModel` 可连接本地 embedding 端点
* [ ] `DashscopeLLM` 重构后所有现有功能不变（向后兼容）
* [ ] `DashScopeEmbeddingModel` 重构后所有现有功能不变
* [ ] 单元测试覆盖新增和重构代码

## Definition of Done

* Tests added/updated（单元测试覆盖核心路径）
* 编译通过，无 lint 错误
* Docs 更新（architecture.md, 新增 provider 文档）
* 向后兼容（DashScope 所有现有用法不受影响）

## Decision (ADR-lite)

**Context**: 生产环境使用 vllm-openai 部署本地模型（实际 qwen3.5-35b-a3b，model-name qwen3-14b），需要框架支持。当前只有 DashScope 一个 provider，底层用 openai-java SDK 走 OpenAI 协议。

**Decision**: 采用方案 A —— 重构 + 新增。提取通用 `AbstractOpenAILLM` 基类，新增 `OpenAICompatibleLLM`，`DashscopeLLM` 退化为子类。Embedding 同理。

**Consequences**:
- 消除代码重复，后续新增 Ollama/LiteLLM 等 provider 只需设置不同 base URL
- 重构 DashScope 代码有轻微风险，需确保所有现有测试通过
- thinking 模式放在基类中（DashScope 和 vllm 都支持 `enable_thinking`/`reasoning_content`）
- `topK` 是 DashScope 特有参数，仅保留在 `DashscopeLLM` 子类中

## Technical Approach

### 类继承关系

```
LLM (interface)
 └── AbstractOpenAILLM (abstract base)
      ├── OpenAICompatibleLLM   (通用，base URL 完全可配置)
      └── DashscopeLLM          (DashScope 默认值 + topK 特有参数)

EmbeddingModel (interface)
 └── AbstractOpenAIEmbeddingModel (abstract base)
      ├── OpenAICompatibleEmbeddingModel  (通用)
      └── DashScopeEmbeddingModel         (DashScope 默认值)
```

### API Key 处理策略

* `OpenAICompatibleLLM`: API Key 可选，未提供时使用占位符 `"no-api-key"`（openai-java SDK 要求非空）
* `DashscopeLLM`: 保持现有行为，从构造参数或 `DASHSCOPE_API_KEY` 环境变量获取

### 关键设计

* 基类 `AbstractOpenAILLM` 包含：消息构建、tool calling、streaming、callback 包装、thinking 提取等全部通用逻辑
* 配置项（baseUrl, apiKey, model, maxCompletionTokens, temperature, topP, enableThinking, thinkingBudget）通过基类构造函数传入
* 子类只需提供默认值和特有参数（如 DashScope 的 topK）

## Out of Scope

* Provider 注册/工厂机制（当前直接构造即可，未来如需要再加）
* Ollama、LiteLLM 等其他 provider 的专用适配（通用 `OpenAICompatibleLLM` 即可覆盖）
* 配置文件驱动的 provider 选择（当前保持编程式配置）
* Embedding 模型的具体部署（本次只提供 Embedding provider，部署是独立工作）

## Technical Notes

### 生产环境信息

* LLM host: 10.100.10.21, port: 40000
* 实际模型: qwen3.5-35b-a3b, model-name: qwen3-14b
* 无需 API Key（内网无认证）
* Embedding 模型未来本地部署

### 关键文件

| 文件 | 作用 | 变更 |
|------|------|------|
| `chain/.../provider/LLM.java` | LLM 接口 | 不变 |
| `chain/.../provider/AbstractOpenAILLM.java` | 通用基类 | **新增** |
| `chain/.../provider/OpenAICompatibleLLM.java` | 通用 provider | **新增** |
| `chain/.../provider/DashscopeLLM.java` | DashScope 实现 | **重构**（继承基类） |
| `chain/.../embedding/EmbeddingModel.java` | Embedding 接口 | 不变 |
| `chain/.../embedding/AbstractOpenAIEmbeddingModel.java` | 通用基类 | **新增** |
| `chain/.../embedding/OpenAICompatibleEmbeddingModel.java` | 通用 Embedding | **新增** |
| `chain/.../embedding/DashScopeEmbeddingModel.java` | DashScope 实现 | **重构**（继承基类） |

### DashScope 特有 vs 通用

| 功能 | 位置 | 说明 |
|------|------|------|
| enableThinking / thinkingBudget | 基类 | vllm 也支持 qwen3 thinking |
| reasoning_content 提取 | 基类 | vllm 也返回此字段 |
| topK | DashscopeLLM 子类 | DashScope 特有，非标准 OpenAI 参数 |
| temperature / topP | 基类 | 标准 OpenAI 参数 |
| maxCompletionTokens | 基类 | 标准 OpenAI 参数 |
