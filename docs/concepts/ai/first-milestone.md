# First development milestone: validated membership slice

**Status:** Proposed sequence, not a scheduled commitment.

## Milestone 0 — foundations (this PR)

- [x] Clarify MVP hypothesis and out-of-scope features
- [x] Establish AI/human contribution conventions
- [x] Record the stack decision as open
- [x] Link concept documents from README

## Milestone 1 — decision and executable skeleton

- [ ] Confirm the first pilot user workflow
- [ ] Compare Kotlin and TypeScript choices against ADR-0001 criteria
- [ ] Choose first frontend/backend and test tooling
- [ ] Create the minimum runnable app, a health endpoint and basic CI
- [ ] Document one-command local startup and tests

## Milestone 2 — first real feature

- [ ] Define a club/member/role model with organization isolation
- [ ] Document or generate API contracts
- [ ] Build the backend membership flow
- [ ] Build a minimal frontend membership screen
- [ ] Add authorization, integration and cross-club isolation tests
- [ ] Demo with non-sensitive sample data

## Milestone 3 — first multi-agent experiment

- [ ] Assign a narrow implementation task to one coding agent
- [ ] Have an independent agent review the PR
- [ ] Trial a separate worktree and explicit task handoff
- [ ] Measure implementation time, API usage, defects and review work
- [ ] Decide whether a coordinator/agent protocol needs automation

## Definition of done for each technical PR

A focused change with a clear purpose, reproducible build/test commands, documented decisions, no committed secrets and passing required CI before human-approved merge.

## Guiding rule

Ship a working use case before building sophisticated agent infrastructure. Record cross-agent discussions in issues and PRs so work survives context resets.
