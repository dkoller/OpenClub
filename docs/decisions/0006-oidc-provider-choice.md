# ADR-0006: Support both Keycloak and Rauthy as OIDC providers

- **Status:** Accepted provider-support decision (2026-11-16); Rauthy synthetic OIDC/browser acceptance implemented through merged #297 (2026-11-17), **not production-approved**
- **Related:** [R05/#14](https://github.com/dkoller/OpenClub/issues/14), [R16/#25](https://github.com/dkoller/OpenClub/issues/25), [R18/#27](https://github.com/dkoller/OpenClub/issues/27), [R19/#28](https://github.com/dkoller/OpenClub/issues/28)
- **Owner instruction:** **Keep Keycloak and add Rauthy; both must remain supported.**

## Decision

OpenClub supports **Keycloak and Rauthy** as alternative OIDC identity providers for a single-club installation. Neither provider is removed, deprecated or made mandatory. Keep the existing Keycloak development setup, smoke/browser tests, and documentation. Rauthy is an **additional** lightweight option to investigate for low-memory, low-cost independent hosting.

The installed OpenClub API currently configures one verified OIDC issuer per installation. This decision means **either provider may be selected when deploying an installation**, not that two independent issuers can automatically authenticate the same installation at the same time. A dual-issuer login selector would require separately reviewed account mapping and session-isolation rules and is **not** approved implicitly.

OpenClub stores member records, application sessions and club/team grants independently from the provider. Logins are bound by **exact OIDC issuer + subject**, never by matching email addresses. A provider switch is **not** an automatic member-account migration and must not silently reattach administrator privileges.

## Compatibility gates for each provider

- Exact issuer and same-origin OIDC authorization, token and JWKS URLs, HTTPS in production.
- Confidential OIDC authorization-code client, `client_secret_basic`, PKCE S256, nonce/state and exact callback URI.
- **RS256 ID tokens** with valid RSA JWKS; Rauthy's default EdDSA on new clients must be explicitly changed for OpenClub. Do not weaken the Ktor verifier to accept a different signing algorithm as a shortcut.
- Browser-to-provider and API-to-provider discovery reachable at the **same verified issuer**; signed ID-token issuer must match exactly.
- Password with MFA **and** passkey journeys, denied/invalid tokens, session logout and revocation, provider restart, and operator recovery verified with synthetic data.
- Persist identity-provider state independently; secure administrator bootstrap, email delivery for password recovery, secrets, TLS and backup/restore before any hosted deployment.
- Preserve all existing Keycloak tests and add real Rauthy OIDC callback/browser acceptance before claiming fully integrated dual-provider support.

## Rollout and evidence

**Historical initial integration slice (superseded by #283–#297 synthetic real-HTTPS acceptance):** An opt-in, loopback-only Rauthy evaluation alongside unchanged Keycloak. Rauthy's OIDC issuer has a path, while its discovery metadata is served at the origin root; the OpenClub backend tries this root discovery endpoint only if issuer-relative discovery returns HTTP 404, then still verifies the exact issuer and endpoints. Its discovery/JWKS compatibility can be smoke-tested without registering a real user. At that initial stage it did **not** demonstrate an authenticated Rauthy callback or WebAuthn journey; both have since been demonstrated in disposable HTTPS Chromium CI, without production approval. The host-facing HTTPS overlay remains a generic **external OIDC** configuration; any production Rauthy deployment requires separately reviewed issuer routing, isolated persistent Hiqlite data, encryption keys, recovery and measured resources.

No production accounts, real member data, external resources or new mandatory service are authorized by this ADR. M1 remains synthetic-only; M2 privacy and operator acceptance stay separate.
