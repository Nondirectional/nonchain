# Release 0.11.0

## Goal

Prepare repository files for the `0.11.0` release so Maven coordinates,
release notes, user-facing documentation, and project Skills consistently
describe the product changes since `0.10.0`.

## Background / Confirmed Facts

- Release date is `2026-07-15` (the task started on July 14 and release
  verification resumed on July 15).
- Commit `cf69e36` (`chore: bump version to 0.10.0`) is the authoritative
  `0.10.0` baseline. The newest Git tag is only `v0.9.0`, so tag-based diffing
  would incorrectly include already released `0.10.0` work.
- Root `pom.xml` and the six module POMs currently use parent/project version
  `0.10.0`: `chain`, `chain-elasticsearch`, `chain-document`, `chain-example`,
  `chain-mysql`, and `chain-postgres`.
- Product changes after the baseline are confined to `chain` and
  `chain-example`; Trellis runtime/task commits are repository maintenance and
  are not end-user release features.
- The release adds the Skill API (`SkillDefinition`, `SkillRegistry`), Agent and
  SubAgent Skill integration, configurable `SkillInjectionMode`, wrapped
  `AgentEvent.SubAgentProgress`, and LLM request-message compatibility via
  `supportsMultipleSystemMessages(...)` / `prepareMessages(...)`.
- The compatibility fix also enforces SubAgent context invariants: parent
  system-message isolation, invisible-message filtering, and complete tool-call
  groups.
- `README.md` and `CHANGELOG.md` already contain partial Skill documentation,
  but the CHANGELOG omits later injection-mode, event, and compatibility work.
  Overview, architecture, message, example, installation, and TODO documents
  contain stale feature lists, example counts, or nonchain dependency versions.
- `.agents/skills/` contains Trellis/release workflow Skills, not a nonchain
  public-API Skill. No matching project Skill requires an API-example update.

## Requirements

### R1. Maven version consistency

- Change the root project version and every module parent version from `0.10.0`
  to `0.11.0`.
- Preserve external dependency versions and `${project.version}` inter-module
  references.
- Remove every stale literal `<version>0.10.0</version>` from project POMs.

### R2. Accurate release notes

- Replace `[Unreleased]` with `[0.11.0] - 2026-07-15`.
- Preserve the existing Skill and SubAgent Skill entries and add the missing
  public behavior: configurable injection role and nested SubAgent progress
  events.
- Record the SubAgent/model message-normalization fix, including the
  multi-system capability declaration and tool-call-group safety.
- Do not present Trellis maintenance changes as library features.

### R3. Documentation synchronization

- Update nonchain Maven dependency snippets in README/docs to `0.11.0` without
  changing third-party dependency versions.
- Update `README.md` where needed to explain provider-level multi-system
  compatibility in addition to explicit Skill injection mode.
- Update `docs/overview/introduction.md` with version `0.11.0`, Agent/Skill
  capabilities, current module descriptions, and current example count.
- Update `docs/overview/architecture.md` component descriptions and public API
  tables for Agent, Skill, SubAgent progress events, and LLM message preparation.
- Update `docs/examples/overview.md` to list and describe `SkillExample` and
  `SubAgentSkillExample`, and correct the example count.
- Update `docs/llm/message.md` with the multi-system capability contract and
  request-copy normalization behavior.
- Update `TODO.md` so completed Agent/Skill/event compatibility work is visible.
- Keep examples compilable and avoid unrelated rewrites.

### R4. Project Skill review

- Review `.agents/skills/` for nonchain public API examples.
- If none exist, leave workflow Skills unchanged and report that result; never
  introduce or change a pinned Maven version in a Skill.

### R5. Validation and repository safety

- Run consistency searches, Maven tests, and example compilation for the final
  worktree.
- Review the final diff for accidental third-party version changes or unrelated
  edits.
- Preserve user changes and do not create a Git commit.

## Acceptance Criteria

- [x] All seven project POMs resolve to version `0.11.0`; no project POM
  contains literal version `0.10.0`.
- [x] `CHANGELOG.md` contains one accurate `0.11.0` entry dated `2026-07-15`
  covering all product commits after `cf69e36`.
- [x] README, overview, architecture, message, examples, installation/feature
  dependency snippets, and TODO content match the released APIs and behavior.
- [x] No stale nonchain artifact version remains in README/docs; third-party
  versions remain unchanged.
- [x] Project Skills were reviewed and only changed if an applicable nonchain
  API Skill exists.
- [x] Relevant Maven tests and consistency checks pass.
- [x] No Git commit, tag, push, or publication is performed.

## Out of Scope

- Creating a release tag, Git commit, branch, or pull request.
- Publishing artifacts to Maven repositories or deploying documentation.
- Rewriting historical CHANGELOG entries.
- Modifying Trellis runtime behavior or archived task artifacts.
- Updating user-global Skills outside this repository.
