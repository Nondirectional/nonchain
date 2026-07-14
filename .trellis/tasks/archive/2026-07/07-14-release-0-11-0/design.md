# Design — Release 0.11.0

## 1. Release boundary

Use `cf69e36..HEAD` as the product-delta window. Classify changes into:

1. library API/runtime changes under `chain/`;
2. executable examples under `chain-example/`;
3. user-facing docs and release notes;
4. Trellis/task maintenance, excluded from release feature wording.

The latest tag (`v0.9.0`) is not used as the baseline because it predates the
already released `0.10.0` work.

## 2. Version propagation

The root POM remains the single project-version source. Update:

- `pom.xml` project version;
- each module's `<parent><version>`;
- literal inter-module versions only if present.

Existing `${project.version}` dependency references remain unchanged. External
dependency versions must not move.

## 3. CHANGELOG model

Finalize the current `[Unreleased]` section as `0.11.0` and describe only
observable product behavior:

- Added: Skill registry/definition, Agent and SubAgent integration, configurable
  injection role, SubAgent progress-event wrapping.
- Fixed: SubAgent context sanitation and provider request normalization for
  models that reject multiple system messages.

Keep compatibility facts explicit: default multi-system capability remains
`true`, callers opt out per LLM instance, original transcripts are not mutated,
and tool-call groups stay contiguous.

## 4. Documentation mapping

| Contract | Documentation consumers |
|---|---|
| Release version / Maven coordinates | `README.md`, installation, getting-started, and feature docs containing `com.non` dependencies |
| Skill API and injection role | `README.md`, introduction, architecture, examples, TODO |
| SubAgent Skill and progress events | `README.md`, architecture, examples, TODO |
| Multi-system request normalization | `README.md`, `docs/llm/message.md`, architecture, CHANGELOG |
| New examples | README example table and `docs/examples/overview.md` |

Dependency snippets are updated only when they refer to nonchain artifacts.
Versions such as LangChain4j, PDFBox, POI, jsoup, jtokkit, and Elasticsearch
remain untouched.

## 5. Skill boundary

The release workflow checks `.agents/skills/` only. That tree currently contains
Trellis operational Skills and this bump-release Skill; it has no nonchain Agent
API reference Skill. Therefore the expected result is a reviewed/no-change
Skills section. User-global Skills are outside repository scope.

## 6. Compatibility and rollback

All intended changes are metadata or Markdown edits. Rollback is file-local:

- revert POM literals if version validation exposes an unintended dependency
  change;
- revert individual documentation hunks if examples no longer match public API;
- restore `[Unreleased]` if release preparation is abandoned.

No database, generated binary, remote service, or irreversible operation is
involved.
