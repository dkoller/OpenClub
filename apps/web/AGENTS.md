# Frontend agent instructions

Read root `AGENTS.md` and ADR-0001 first.

- React + TypeScript + Vite; use strict TypeScript settings.
- Build accessible, responsive UI using semantic elements and keyboard-operable controls.
- Prefer simple functional components and local state over new state-management frameworks.
- Use the OpenAPI contract for request/response types and integration boundaries.
- When changing UI flows, commands, dependencies or API usage, update relevant README/setup instructions, contract examples and user-facing documentation **in the same PR**. Check [documentation maintenance](../../docs/contributing/documentation.md).
- Never trust the client to enforce club permissions: the backend is authoritative.
- Handle loading, error and empty states when user-facing behavior requires them.
- Test critical interactions and avoid snapshots that only duplicate implementation details.
- Keep request, validation and denial handling for admin panels in plain modules that take `fetch` as a parameter (`api.ts`, `auth.ts`, `members.ts`, `grants.ts`) and unit-test them with Vitest. There is no DOM test environment: rendered interactions are covered by the Playwright browser suites in `dev/auth-e2e`.
- Do not add CSS/UI dependencies for trivial layout or controls.
