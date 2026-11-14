# First development milestone: validated membership slice

**Status:** Historical milestone sketch (partly superseded). For current priorities, status and GitHub issues, use [the numbered roadmap](../../roadmap.md). This file is not a live tracking board.

## Milestone 0 — foundations (this PR)

- [x] Clarify MVP hypothesis and out-of-scope features
- [x] Establish AI/human contribution conventions
- [x] Record initial stack choices and open questions (subsequently resolved in ADR-0001)
- [x] Link concept documents from README

## Milestone 1 — decision and executable skeleton

- [ ] Confirm the first pilot user workflow
- [x] Compare Kotlin and TypeScript choices; approve ADR-0001 ([PR #3](https://github.com/dkoller/OpenClub/pull/3))
- [x] Choose React/TypeScript, Kotlin/Ktor and testing toolchain ([PR #3](https://github.com/dkoller/OpenClub/pull/3))
- [x] Create runnable app, health endpoint and basic CI ([PR #6](https://github.com/dkoller/OpenClub/pull/6))
- [x] Document and test one-command local startup ([PR #7](https://github.com/dkoller/OpenClub/pull/7))

## Milestone 2 — first real feature

- [x] Define club/person/membership/role **schema** and identity boundaries ([PR #9](https://github.com/dkoller/OpenClub/pull/9)); authorization enforcement remains open
- [x] Draft OpenAPI contract ([PR #41](https://github.com/dkoller/OpenClub/pull/41)); **approval and implementation remain open** ([issue #13](https://github.com/dkoller/OpenClub/issues/13))
- [ ] Build the backend membership flow
- [ ] Build a minimal frontend membership screen
- [ ] Add authorization, integration and cross-club isolation tests
- [ ] Demo with non-sensitive sample data

## Milestone 3 — first multi-agent experiment (production work not yet validated)

- [ ] Assign a narrow implementation task to one coding agent
- [ ] Have an independent agent review the PR
- [ ] Trial a separate worktree and explicit task handoff
- [ ] Measure implementation time, API usage, defects and review work
- [ ] Decide whether a coordinator/agent protocol needs automation

**Progress note (2026-11-14):** Pilot and OIDC research drafts have been merged ([PRs #38](https://github.com/dkoller/OpenClub/pull/38) and [#39](https://github.com/dkoller/OpenClub/pull/39)); this does **not** complete pilot validation or provider selection. Privacy and OpenAPI drafts are also merged ([PRs #40](https://github.com/dkoller/OpenClub/pull/40) and [#41](https://github.com/dkoller/OpenClub/pull/41)). The tasks above remain incomplete until accepted against [the active roadmap](../../roadmap.md).

## Definition of done for each technical PR

A focused change with a clear purpose, reproducible build/test commands, up-to-date affected documentation and decisions in the same PR, no committed secrets and passing required CI before human-approved merge.

## Guiding rule

Ship a working use case before building sophisticated agent infrastructure. Record cross-agent discussions in issues and PRs so work survives context resets.
