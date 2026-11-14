# ADR-0003: OIDC and browser session architecture

- **Status:** Accepted architecture (2026-11-14); security implementation details remain open
- **Date:** 2026-11-14
- **Related:** [R02 #11](https://github.com/dkoller/OpenClub/issues/11), [finding #35](https://github.com/dkoller/OpenClub/issues/35), [ADR-0002](0002-identity-and-membership-model.md)
- **Input:** [OIDC comparison](../research/auth-options.md)

## Problem and constraints

OpenClub needs trusted sign-in, club-scoped roles, a self-hostable pilot environment, and no bespoke password/token infrastructure. Its members may be added before creating accounts. The browser client must never authorize club access on its own.

## Confirmed decision

1. **Provider: Keycloak, self-hosted as the reference implementation.** It offers standard OIDC and a mature deployment story, but imposes extra JVM/service operations; benchmark the resource footprint and maintenance before acceptance. Keep the OIDC integration provider-neutral so that other self-hosted or externally managed providers can be configured later; multi-provider support within one installation is not part of the MVP.
2. **Browser pattern: Ktor backend-for-frontend (BFF), approved.** React uses same-origin API calls and an opaque server-managed session cookie. The backend performs OIDC authorization-code + PKCE login and token exchange. No access or refresh tokens in browser localStorage.
3. **Identity mapping:** Only resolve a person using verified OIDC `issuer` + `subject`. An email is contact information, not a unique stable login identifier.
4. **Authorization:** For each club-specific request, resolve session -> person -> membership in the requested club -> required role; explicitly deny cross-club operations. No membership APIs until verified sessions and this guard exist.
5. **First administrator:** Club creation and initial ADMIN membership must be atomically authorized. New-club creation is configurable by installation, with invitation/approval as the secure default. The MVP implements controlled onboarding first; recovery and optional open creation require separate designs. Never permit open self-appointment to existing clubs.

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

## Implementation and deployment gates (architecture accepted; these remain open)

- [ ] Confirm pilot identity needs and users (including accountless member records) from [R01 #10](https://github.com/dkoller/OpenClub/issues/10).
- [x] Select Keycloak as the initial self-hosted reference provider; preserve standard OIDC portability.
- [ ] Verify compatible versions, operational footprint and production configuration.
- [x] Approve Ktor BFF with opaque server-side sessions and HttpOnly cookies.
- [ ] Review and approve the concrete proposed session store, CSRF controls, logout, expiry, and production cookie settings in [R05 security design](../security/oidc-bff-design.md); implement and test each before treating this gate as complete.
- [x] Set controlled club creation as the default; permit deployment-level configuration later.
- [ ] Approve exact first-admin approval mechanism, authorization and recovery.
- [ ] Approve the Nimbus JVM OIDC SDK candidate and mock identity-provider test strategy in [R05 security design](../security/oidc-bff-design.md); confirm/pin versions in the implementation PR.
- [ ] Update the OpenAPI draft/session contract as part of the implementation PR.
- [ ] Obtain appropriate privacy review before any real pilot identities are used.

**No login code or privileges are introduced by this ADR.** Its architecture is accepted, but do not expose membership endpoints until authentication, authorization, bootstrap and privacy controls have been implemented and tested.

## JWKS runtime implementation note (2026-11-14)

Issuer pinning and the BFF architecture are unchanged. The code-exchange runtime
uses a five-minute public signing-key lifetime, serialized refresh and a shared
30-second refresh-attempt cooldown against the startup-vetted JWKS URL. Expired
keys fail closed on outage. See [refresh policy](../security/oidc-provider-discovery.md#jwks-runtime-refresh-2026-11-14)
for rotation availability and key-removal limits. No new provider or deployment
configuration is introduced; release gates remain open.
