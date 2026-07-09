# RapidOCREngine 改用 uv run python

## Goal

将 `RapidOCREngine` 的 Python 调用方式从硬编码 `python3` 改为 `uv run python`，适配没有直接安装 python3 但有 uv 的环境。

## Requirements

* `buildCommand()` 使用 `uv run python` 替代 `python3`
* 命令格式：`["uv", "run", "python", "-c", script]`

## Acceptance Criteria

* [ ] `buildCommand()` 构造的命令为 `["uv", "run", "python", "-c", script]`
* [ ] OCR 功能正常工作

## Definition of Done

* Tests added/updated
* Lint / typecheck / CI green

## Out of Scope

* 不支持 python3 回退或自动检测
* 不涉及其他 Python 调用场景

## Decision (ADR-lite)

**Context**: 环境可能没有 python3 但有 uv
**Decision**: 直接将默认命令从 `python3` 改为 `uv run python`
**Consequences**: 所有使用 RapidOCREngine 的环境需要安装 uv

## Technical Notes

* 核心文件：`chain/src/main/java/com/non/chain/document/RapidOCREngine.java:62-77`
* 改动：第73行 `cmd.add("python3")` → `cmd.add("uv"); cmd.add("run"); cmd.add("python");`
