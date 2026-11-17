# R02 — Authentication options and security design

**Status:** Historical research. The provider decision was later made in [ADR-0006](../decisions/0006-oidc-provider-choice.md) (Keycloak and Rauthy supported, one issuer per installation). This page itself is research and not an ADR. Parent: [#11](https://github.com/dkoller/OpenClub/issues/11); unresolved questions in [#35](https://github.com/dkoller/OpenClub/issues/35). Sources reviewed 2026-11-14.

## Comparison (verify versions, operational costs and licenses before approval)

| Option | Advantages | Tradeoffs for OpenClub |
| --- | --- | --- |
| [Keycloak](https://www.keycloak.org/securing-apps/oidc-layers) | Mature open-source OIDC provider, established documentation, supports authorization-code flow, can self-host | Runs an additional service, resource needs, configuration and upgrades |
| [authentik](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/) | Self-hostable OIDC with PKCE support and flexible integrations | Additional deployment and maintenance components; no managed offering per [vendor pricing FAQ](https://goauthentik.io/pricing/) |
| [ZITADEL](https://github.com/zitadel/zitadel) | OIDC with self-hosted and managed paths; identity APIs | Hosting/feature tradeoffs; v3 licensing uses **AGPL-3.0**, so review [licensing FAQ](https://help.zitadel.com/zitadel-licensing-faqs) |

We should keep OpenClub itself identity-provider neutral, but the *development and pilot environment* needs one documented implementation. No provider is approved here.

## Recommended direction for evaluation

1. Prefer **OIDC authorization code with PKCE**. Compare a **backend-for-frontend (BFF)**: Ktor handles callback and token exchange server-side and sets a secure, HttpOnly, SameSite session cookie; avoid storing access/refresh tokens in browser localStorage.
2. Validate trusted issuer, audience, signature, expiry, nonce/state and other required claims via a maintained library. Do not implement token cryptography.
3. Associate the application person with the verified **(issuer, subject)** pair; never treat email or a browser-supplied person ID as authoritative.
4. Enforce club membership/role on **every** API access; OIDC alone does not provide club authorization.
5. Make initial club ADMIN bootstrap an explicitly authorized operation, with re-entry recovery and last-admin safeguards.
6. Store session and OIDC client secrets outside the repository; define CSRF defenses, cookie settings, logout/rotation, key discovery/caching and test identities.

These are based on [IETF RFC 10017, OAuth for browser-based applications](https://datatracker.ietf.org/doc/html/rfc10017) and [OpenID Connect Core claim stability](https://openid.net/specs/openid-connect-core-1_0.html). Confirm deployment architecture and maintained JVM libraries in the implementation ADR.

## Suggested evaluation criteria

- **Self-hostable and free for the pilot**; acceptable maintenance footprint with Postgres/Ktor stack
- Standard OIDC discovery, PKCE, refresh/session expiration, logout and MFA capabilities
- Local Docker Compose onboarding without insecure permanent defaults
- Supported Kotlin/JVM libraries and adequate CI-compatible test setup
- Swiss/EU hosting options if needed; data transfer documentation
- Vendor portability through issuer/subject mapping and minimal provider-specific data

## Open decisions / exit criteria

- [ ] Choose the provider, hosting arrangement and applicable license.
- [ ] Approve browser session model with CSRF and logout behavior.
- [ ] Document first-admin bootstrap and account recovery controls.
- [ ] Select tested Kotlin library / OIDC mock for CI.
- [ ] Write accepted ADR before implementing R05.

**No authentication code or privileged endpoint is introduced by this research.** See [proposed ADR-0003](../decisions/0003-oidc-session-proposal.md) for the candidate decision; it is not accepted yet.
