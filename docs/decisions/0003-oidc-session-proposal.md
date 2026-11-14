# ADR-0003 (proposal): OIDC and browser session architecture

- **Status:** Proposed — requires human approval before implementation
- **Date:** 2026-11-14
- **Related:** [R02 #11](https://github.com/dkoller/OpenClub/issues/11), [finding #35](https://github.com/dkoller/OpenClub/issues/35), [ADR-0002](0002-identity-and-membership-model.md)
- **Input:** [OIDC comparison](../research/auth-options.md)

## Problem and constraints

OpenClub needs trusted sign-in, club-scoped roles, a self-hostable pilot environment, and no bespoke password/token infrastructure. Its members may be added before creating accounts. The browser client must never authorize club access on its own.

## Proposed decision (not yet adopted)

1. **Provider candidate: Keycloak, self-hosted for development and initial pilot.** It offers standard OIDC and a mature deployment story, but imposes extra JVM/service operations; benchmark the resource footprint and maintenance before acceptance. authentik and ZITADEL remain alternatives, especially if the pilot already uses either.
2. **Browser pattern: Ktor backend-for-frontend (BFF).** React uses same-origin API calls and an opaque server-managed session cookie. The backend performs OIDC authorization-code + PKCE login and token exchange. No access or refresh tokens in browser localStorage.
3. **Identity mapping:** Only resolve a person using verified OIDC `issuer` + `subject`. An email is contact information, not a unique stable login identifier.
4. **Authorization:** For each club-specific request, resolve session -> person -> membership in the requested club -> required role; explicitly deny cross-club operations. No membership APIs until verified sessions and this guard exist.
5. **First administrator:** Club creation and initial ADMIN membership must be atomically authorized. Require an explicit onboarding policy and tested recovery for lost access; never permit open self-appointment to existing clubs.

## Threats and required controls

- Verify OIDC metadata, signing keys, signature, issuer, intended audience, token expiry, authorization response state and nonce through maintained JVM libraries; allow only known redirect URIs. Use PKCE and TLS outside localhost.
- Generate unpredictable opaque sessions, persist only server-side state with bounded lifetime, rotate on sign-in/privilege changes, and revoke on logout. Prefer `HttpOnly`, `Secure` (outside local HTTP development), narrowly scoped `SameSite` cookies, and CSRF protection for cookie-authenticated state changes.
- Prevent login CSRF, token substitution, replay, session fixation, open redirects, and account linking through unverified browser input.
- Keep provider administration, client secrets, session keys and bootstrap credentials out of the repository. Never create a default production administrator.
- Ensure cross-club non-disclosure and last-admin protection with PostgreSQL-backed integration tests.
- Write a minimal privacy/retention policy for identity/session data before using real member records ([R03 #12](https://github.com/dkoller/OpenClub/issues/12)).

## Alternatives and trade-offs

| Option | Reason not selected for this proposal |
| --- | --- |
| authentik | Supported self-hostable OIDC alternative; choose if pilot already operates it or values integration features |
| ZITADEL | Viable hosted/self-hosted path; examine licensing and hosting-cost impact before approval |
| Browser-only OAuth/OIDC | Public-client token storage and callback implementation need extra browser security work; BFF matches existing Kotlin server |
| Custom password or JWT issuer | Unnecessary security-sensitive maintenance; avoid |

## Acceptance checklist before switching status to Accepted

- [ ] Confirm pilot identity needs and users (including accountless member records) from [R01 #10](https://github.com/dkoller/OpenClub/issues/10).
- [ ] Approve provider or select a documented alternative; verify licenses, supported versions and free/self-hosted operational footprint.
- [ ] Approve BFF session store, CSRF controls, logout, expiry, cookie rules and local developer experience.
- [ ] Approve initial club provisioning, first-admin authorization and recovery.
- [ ] Select maintained Kotlin OIDC libraries and define mock identity provider for offline CI tests.
- [ ] Update the OpenAPI draft/session contract as part of the implementation PR.
- [ ] Obtain appropriate privacy review before any real pilot identities are used.

**No login code or privileges are introduced by this proposal.** Do not mark R02 complete or start exposing membership endpoints on the basis of this draft alone.
