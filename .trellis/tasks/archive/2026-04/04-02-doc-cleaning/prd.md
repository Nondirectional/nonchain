# 文档内容清洗模块设计

## Goal

为文档解析后的 `ParsedDocument` 提供可组合的清洗管道（Pipeline），将原始提取内容转化为更适合下游 RAG / LLM 处理的干净文本，提升检索质量和生成准确性。

## Requirements

### 核心接口

- `DocumentCleaner` 接口：`ParsedDocument clean(ParsedDocument doc)`
- `CleanerPipeline` 组合器：串联多个 Cleaner，依次执行
- 每个 Cleaner 独立可测试、可组合、可排序

### 完整清洗套件（MVP）

1. **WhitespaceNormalizer** — 空白规范化：合并多余空格/换行，标准化空白字符
2. **ControlCharacterRemover** — 控制字符移除：去除零宽字符、不可见控制字符
3. **DuplicateRemover** — 重复段落去重：检测并移除 PDF 页眉/页脚等重复内容
4. **ShortFragmentMerger** — 短片段合并：将过短的文本片段合并到相邻元素
5. **BoilerplateRemover** — 样板内容移除：过滤版权声明、页码等模板文本
6. **TableSerializer** — 表格序列化：将 TableElement 转为可索引的文本格式
7. **UnicodeNormalizer** — Unicode 规范化：全角→半角、特殊字符统一
8. **ImageStrategyCleaner** — 图片策略处理：可配置的保留/丢弃策略

### PDF 扫描件处理（Reader 阶段，非清洗阶段）

- **检测 + 执行**: PdfDocumentReader 提取文字后检测文本密度，稀疏时直接在 Reader 阶段执行 OCR 提取文字
- **正常 PDF**: 正常提取文字 + 图片，不做 OCR
- **OCR 库**: optional 依赖，不使用 OCR 时无需引入
- **清洗层不涉及 OCR**，OCR 属于"内容提取"职责

## Acceptance Criteria

- [ ] `DocumentCleaner` 接口定义清晰
- [ ] `CleanerPipeline` 支持组合多个 Cleaner 并按序执行
- [ ] 实现 8 个 Cleaner（上述列表）
- [ ] ParsedDocument 不可变性保持（Cleaner 返回新实例）
- [ ] 单元测试覆盖每个 Cleaner 的核心逻辑
- [ ] PDF 扫描件检测 + OCR 逻辑在 PdfDocumentReader 中实现（独立任务，不在本清洗模块范围）

## Definition of Done

- Tests added/updated (unit/integration where appropriate)
- Lint / typecheck / CI green
- Docs/notes updated if behavior changes

## Decision (ADR-lite)

**Context**: 需要在文档解析和下游处理之间提供内容清洗能力
**Decision**:
- 采用 Pipeline 模式（`DocumentCleaner` 接口 + `CleanerPipeline` 组合器）
- PDF 扫描件 OCR 在 Reader 阶段完成（检测 + 提取），清洗层不涉及 OCR
- 图片策略可配置（透传/丢弃）
**Consequences**: 清洗层职责纯粹（仅文本清洗），OCR 作为内容提取逻辑留在 Reader

## Out of Scope

- LLM 图片描述/理解（未来扩展）
- 语言检测和过滤
- 敏感信息脱敏
- 自定义正则规则引擎

## Technical Notes

### 关键文件

- `chain/src/main/java/com/non/chain/document/` — 核心抽象
- `chain-document/src/main/java/com/non/chain/document/` — Reader 实现
- `chain/src/main/java/com/non/chain/knowledge/` — 下游接口

### 设计约束

- ParsedDocument 和 DocumentElement 都是不可变的 → Cleaner 通过 Builder 重建返回新实例
- ElementType 枚举已有 PAGE_BREAK（未使用）
- Element 的 metadata 是 `Map<String, Object>`，可扩展
- OCR 相关改动属于 PdfDocumentReader 的独立任务，不在本清洗模块范围内

### 模块位置

- 接口 + 实现 均在 `chain-document` 模块
- 包路径: `com.non.chain.document.cleaner`

### 包结构

```
chain-document/src/main/java/com/non/chain/document/cleaner/
  DocumentCleaner.java           — 接口
  CleanerPipeline.java           — 组合器
  WhitespaceNormalizer.java
  ControlCharacterRemover.java
  DuplicateRemover.java
  ShortFragmentMerger.java
  BoilerplateRemover.java
  TableSerializer.java
  UnicodeNormalizer.java
  ImageStrategyCleaner.java
```

### 实施计划（小 PR 拆分）

- **PR1**: 核心接口 + CleanerPipeline + 基础 Cleaner（WhitespaceNormalizer、ControlCharacterRemover、UnicodeNormalizer）+ 单元测试
- **PR2**: 内容 Cleaner（DuplicateRemover、ShortFragmentMerger、BoilerplateRemover）+ 单元测试
- **PR3**: 结构 Cleaner（TableSerializer、ImageStrategyCleaner）+ 单元测试
- **PR4**（独立任务）: PdfDocumentReader 扫描件检测 + OCR 集成
