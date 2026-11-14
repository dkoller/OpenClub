# R05 authentication implementation closeout (2026-11-14)

**Status: proposed for human acceptance after this PR merges and CI passes.**
Parent: [R05/#14](https://github.com/dkoller/OpenClub/issues/14).
This is an authentication implementation milestone, **not** approval to expose
real club/member data or a public deployment.

| R05 acceptance criterion | Merged evidence |
| --- | --- |
| Keycloak development login, authorization code + PKCE/state/nonce | PRs #50–#57, #64, [browser E2E #66](https://github.com/dkoller/OpenClub/pull/66) |
| Issuer/audience/signature/expiry checks; wrong-token negative tests | PRs #54–#56 |
| Verified issuer+subject mapping without email-based club role adoption | [#59](https://github.com/dkoller/OpenClub/pull/59) |
| Hashed, opaque server-side sessions, idle/hard expiry and revocation | [#58](https://github.com/dkoller/OpenClub/pull/58), [#65](https://github.com/dkoller/OpenClub/pull/65) |
| HTTP session read/logout and Origin/CSRF verification | [#65](https://github.com/dkoller/OpenClub/pull/65) |
| Issuer-pinned JWKS refresh and rotation verification | [#67](https://github.com/dkoller/OpenClub/pull/67), [#70](https://github.com/dkoller/OpenClub/pull/70) |
| Process-local login/callback admission limits | [#68](https://github.com/dkoller/OpenClub/pull/68) |
| Expired login/session record cleanup | [#69](https://github.com/dkoller/OpenClub/pull/69) |
| JSON errors for all login/callback contract status codes | This closeout PR; requires CI and reviewer acceptance |

The browser CI tests real Keycloak, PostgreSQL, login/logout, old-session
revocation and signing-key rotation without restarting the API. Provider-outage
and signing-key expiry/cooldown cases have isolated tests; they are not fully
exercised through a real production-like network failure.

## Explicitly not closed by R05

- [R02/#11](https://github.com/dkoller/OpenClub/issues/11): operational
  identity-provider decisions and lost-first-admin recovery.
- [R03/#12](https://github.com/dkoller/OpenClub/issues/12): privacy, consent,
  retention and minor-contact safeguards before real member identities.
- [R06/#15](https://github.com/dkoller/OpenClub/issues/15) and
  [R07/#16](https://github.com/dkoller/OpenClub/issues/16): club-scoped
  authorization and secure first-admin bootstrap.
- The development/browser E2E bypasses the web proxy. Existing web proxies
  strip `/api`, while Ktor's auth routes include `/api`; fix and validate the
  path mapping before frontend/deployment use.
- Process-local shared admission budgets are not per-client quotas or
  distributed DDoS protection. Validate edge controls for any public service.
- HTTPS reverse-proxy cookie behavior, operational deployment/backups and
  production-provider failure recovery require separate release acceptance.

After merged-head CI and independent review, update the R05 issue's
implementation checklist, cite the merged closeout PR, and close #14 **only
as an engineering milestone**. Keep the distinct release gates open.
