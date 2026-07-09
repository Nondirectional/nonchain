# Backend Development Guidelines

> Best practices for backend development in this project.

---

## Overview

This is a **Java 11+ library** (minimum Java 11, verified compatible with JDK 11 / 17 / 21) for building LLM-powered applications. It provides:
- LLM provider abstraction (currently Dashscope/Alibaba Cloud)
- Tool/function calling framework (annotation + fluent API)
- Graph-based workflow engine with conditional routing

**Tech stack**: Java 11+ (minimum; verified on JDK 11/17/21), Maven, OpenAI-compatible SDK (openai-java 4.30.0), no database, no web framework.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Module organization and file layout | Done |
| [Database Guidelines](./database-guidelines.md) | No database (state management notes) | Done |
| [Error Handling](./error-handling.md) | Standard Java exceptions, fail-fast pattern | Done |
| [Quality Guidelines](./quality-guidelines.md) | Immutable objects, builder pattern, forbidden patterns | Done |
| [Tool Function-Calling Contracts](./tool-function-calling.md) | Schema ↔ parse ↔ convert type mapping, JSON parsing | Done |
| [Logging Guidelines](./logging-guidelines.md) | No logging framework, println in examples only | Done |

---

## Pre-Development Checklist

Before writing code, read:
- [Directory Structure](./directory-structure.md) — Where to put new classes
- [Quality Guidelines](./quality-guidelines.md) — Required patterns and forbidden patterns

For specific tasks:
- Adding LLM providers → [Error Handling](./error-handling.md) for exception patterns
- Adding tools/function calling → [Tool Function-Calling Contracts](./tool-function-calling.md) for the schema ↔ parse ↔ convert type contract; [Quality Guidelines](./quality-guidelines.md) for annotation and fluent API patterns
- Adding workflow nodes → [Directory Structure](./directory-structure.md) for module organization
- Adding multimodal content → [Quality Guidelines](./quality-guidelines.md) for ContentPart patterns

---

**Language**: All documentation is written in **English**.
