# Implementation Plan — Release 0.11.0

## 1. Version files

- [x] Update root `pom.xml` from `0.10.0` to `0.11.0`.
- [x] Update parent versions in all six module POMs.
- [x] Search all POMs for stale `0.10.0` literals and inspect every remaining
  version hit to avoid changing third-party dependencies.

## 2. Release notes

- [x] Finalize `[Unreleased]` as `[0.11.0] - 2026-07-15`.
- [x] Preserve and tighten the Skill / SubAgent Skill entries.
- [x] Add injection-mode and `SubAgentProgress` behavior.
- [x] Add the SubAgent/model compatibility fix from commit `9628a69`.
- [x] Compare the result against product commits in `cf69e36..HEAD`.

## 3. Documentation

- [x] Update nonchain Maven coordinates in README and docs to `0.11.0`.
- [x] Update README compatibility wording for
  `supportsMultipleSystemMessages(false)`.
- [x] Update introduction version, Agent/Skill feature summary, module table,
  and example count.
- [x] Update architecture diagram/text/API table for Agent, SkillRegistry,
  Skill injection, SubAgent progress events, and LLM request preparation.
- [x] Update message documentation for multi-system normalization.
- [x] Add `SkillExample` and `SubAgentSkillExample` to example overview with
  runnable commands/descriptions; set the actual count to 34.
- [x] Update TODO completed-capability wording.
- [x] Review `.agents/skills/`; make no change unless an applicable nonchain API
  Skill is discovered.

## 4. Validation

- [x] Run `rg` checks for stale POM version `0.10.0`.
- [x] Run `rg` checks for stale nonchain dependency versions in README/docs and
  verify third-party version literals were not changed.
- [x] Run `mvn test` for the reactor.
- [x] Run `mvn compile -pl chain-example -am` to validate examples.
- [x] Inspect `git diff --check`, `git diff --stat`, and the final focused diff.
- [x] Confirm no commit/tag/push/publish action occurred.

## Risky files / rollback points

- `CHANGELOG.md`: wording must distinguish added APIs from the compatibility fix.
- Documentation dependency blocks: update only `com.non` artifacts, never
  adjacent third-party versions.
- `docs/overview/architecture.md`: keep the existing architecture structure;
  extend component lists instead of rewriting the document.

## Review gate

Implementation starts only after the user approves `prd.md`, `design.md`, and
this plan. Then run `task.py start`, load `trellis-before-dev`, and execute the
checklist in order.
