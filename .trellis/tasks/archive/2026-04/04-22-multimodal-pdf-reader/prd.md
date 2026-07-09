# LLM Multimodal Scanned PDF Reader

## Goal

利用大模型的多模态（视觉）能力，实现对扫描件 PDF 的智能识别，替代传统 OCR 引擎（如 Tesseract），获得更高质量的文本提取效果。

## What I already know

- `PdfDocumentReader` 已有扫描件检测逻辑（`isScanned()`：页均文字 < 50 字符则判定为扫描件）
- 已有 `OcrEngine` 接口（`String recognize(BufferedImage image)`），是天然的扩展点
- LLM 层已支持图片输入：`ImageDataPart`（base64）、`ImageUrlPart`（URL）
- 所有 Provider（DashScope、OpenAICompatible、VLLM）均继承多模态支持
- `PdfDocumentReader` 在 OCR 模式下逐页渲染 300 DPI BufferedImage 后调用 `ocrEngine.recognize()`

## Assumptions (temporary)

- 实现为 `OcrEngine` 接口的一个新实现类
- 利用现有 LLM provider 层发送图片给视觉模型
- 用户需自行提供配置好的 LLM 实例

## Open Questions

- 是否需要支持结构化输出（表格、标题层级），还是仅纯文本提取？
- 放在哪个模块？（chain-document / 新模块 chain-llm-ocr）
- 是否需要自定义 prompt（让用户控制识别行为）？

## Requirements (evolving)

- 实现一个基于 LLM 多模态能力的 `OcrEngine`
- 将 `BufferedImage` 转为 `ImageDataPart` 发送给视觉模型
- 与现有 `PdfDocumentReader` 的 OCR 流程无缝集成

## Acceptance Criteria (evolving)

- [ ] 新类实现 `OcrEngine` 接口
- [ ] 能正确识别扫描件 PDF 中的中文/英文文本
- [ ] 可通过 `PdfDocumentReader.builder().ocrEngine(...)` 直接使用
- [ ] 有单元测试或示例代码

## Definition of Done

- Lint / typecheck 通过
- 有示例或测试代码
- 与现有 OCR 流程集成无破坏

## Out of Scope

- (待定)

## Technical Notes

- `OcrEngine` 接口签名：`String recognize(BufferedImage image)`
- `ImageDataPart.of(base64, mime)` 可直接使用
- `PdfDocumentReader` 的 OCR 路径：`extractWithOcr()` 逐页渲染 → `ocrEngine.recognize()`
- 关键文件：
  - `chain/src/main/java/com/non/chain/document/OcrEngine.java`
  - `chain-document/src/main/java/com/non/chain/document/pdf/PdfDocumentReader.java`
  - `chain/src/main/java/com/non/chain/ImageDataPart.java`
  - `chain/src/main/java/com/non/chain/provider/LLM.java`
  - `chain/src/main/java/com/non/chain/provider/AbstractOpenAILLM.java`
