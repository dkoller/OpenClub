# ADR-0001: Application stack selection

- **Status:** Proposed / undecided
- **Date:** 2026-11-14

## Context

OpenClub should be easy to build with human and AI contributors, cheap to host, suitable for volunteer maintainers, and open-source. Earlier discussion compared TypeScript full stack with Kotlin frontend/backend approaches. The repository's initial ignore rules reference Kotlin/Java but do not constitute a technical decision.

## Candidates for evaluation

1. TypeScript frontend + TypeScript backend.
2. TypeScript frontend + Kotlin/JVM backend.
3. Kotlin-oriented frontend and backend, including Kotlin Multiplatform where justified.

## Decision criteria

- Ease of recruiting contributors and maintaining the code
- Development speed for a tested, accessible web interface
- Type safety and tooling quality
- Runtime/deployment cost for small installations
- Security and authentication ecosystem
- Long-term maintainability and interoperability (e.g. OpenAPI)
- Working efficiently with Claude Code, Codex and Copilot

## Decision

**Not selected yet.** First validate the MVP use case and prototype the smallest relevant technical spikes. Prefer reversible choices and a vendor-neutral API boundary.

## Consequences

No application scaffold, framework-specific build pipeline or backend API implementation is introduced by this ADR. Update this record before selecting a framework.
