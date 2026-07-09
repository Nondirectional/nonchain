# VLLM 多模态能力集成

## Goal

让 nonchain 框架支持 base64 图片数据和本地图片文件作为多模态输入，方便本地 vLLM 部署场景使用。

## What I already know

* VLLM 已继承 `AbstractOpenAILLM` 的多模态消息转换逻辑，`ImageUrlPart` (URL) 和 `TextPart` 可正常工作
* `ImageUrlPart` 只支持 URL 字符串，没有 base64 和本地文件支持
* `ContentPart` 是标记接口，当前只有 `TextPart` 和 `ImageUrlPart` 两种实现
* `MessageSerializer` 需要同步支持新的 ContentPart 类型的序列化/反序列化
* OpenAI 兼容 API 通过 data URI (`data:image/jpeg;base64,...`) 在 `image_url` 字段传递 base64 图片
* 多模态示例和文档目前只覆盖 DashscopeLLM

## Requirements

* 新增 `ImageDataPart`，支持 base64 编码的图片数据
* 新增从本地文件路径创建图片 ContentPart 的能力（读取文件、检测 MIME 类型、转 base64）
* 更新 `AbstractOpenAILLM.toSdkContentPart()` 支持新的 ContentPart 类型
* 更新 `MessageSerializer` 支持新类型的序列化/反序列化
* 添加 VLLM 多模态使用示例
* 更新文档说明多模态支持

## Acceptance Criteria

* [ ] `ImageDataPart.of(base64, mimeType)` 可创建 base64 图片部件
* [ ] `ImageDataPart.fromFile(path)` 可从本地文件创建图片部件
* [ ] VLLM 和其他 OpenAI 兼容提供者能正确发送包含 base64 图片的消息
* [ ] `MessageSerializer` 能正确序列化/反序列化 `ImageDataPart`
* [ ] 有示例代码演示 VLLM 多模态用法
* [ ] 文档更新

## Definition of Done

* Tests added/updated
* Lint / typecheck / CI green
* Docs updated

## Out of Scope

* 视频/音频等其他媒体类型
* VLLM 提供者本身的修改（无需改动，继承即可）

## Technical Approach

新增 `ImageDataPart` 类（持有 base64 数据和 MIME 类型），通过 data URI 格式转为 OpenAI SDK 的 `ChatCompletionContentPartImage`。`fromFile` 工厂方法读取文件并自动检测 MIME 类型。

### 需修改的文件

1. **新增**: `chain/src/main/java/com/non/chain/ImageDataPart.java`
2. **修改**: `chain/src/main/java/com/non/chain/provider/AbstractOpenAILLM.java` — `toSdkContentPart()` 增加 ImageDataPart 分支
3. **修改**: `chain-mysql/src/main/java/com/non/chain/mysql/MessageSerializer.java` — 序列化/反序列化
4. **新增**: `chain-example/src/main/java/com/non/chain/example/VLLMMultimodalExample.java`
5. **更新**: `docs/llm/vllm.md` 和 `docs/llm/multimodal.md`

## Decision (ADR-lite)

**Context**: 需要支持 base64 和本地文件图片输入
**Decision**: 新增独立的 `ImageDataPart` 类，而非扩展 `ImageUrlPart`
**Consequences**: 类型更安全，意图更明确；需要额外维护一个类和序列化逻辑
