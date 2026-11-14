# R05 / C4a — Trust-pinned provider discovery and JWKS

**Status:** An internal, synchronous discovery component only. [Issue #14](https://github.com/dkoller/OpenClub/issues/14). It does not enable public login, callback, remote token exchange, sessions or authorization.

The `OidcProviderDiscovery` component starts only from the configured issuer (never request input), fetches `<issuer>/.well-known/openid-configuration` and uses Nimbus to parse it. The discovered issuer must **exactly equal** the configured issuer, including its path. Authorization endpoint must match the configured one. Token and JWKS endpoints must use the issuer's exact scheme, parsed host and port; userinfo, query and fragment are forbidden. This intentionally **does not support cross-origin OIDC endpoints** yet; providers requiring them need a separately reviewed, pinned allowlist and negative tests.

Only RS256 ID tokens are currently supported. Discovery must advertise RS256; returned JWKS must contain suitable public RSA signing keys with a nonblank key ID and no private keys. No URL in JWT headers is used to load keys. `OidcIdTokenVerifier` accepts the vetted public key set, but this PR does **not** connect both classes to an HTTP route.

The network fetch uses Java 21 HTTPS validation, never follows redirects, enforces a 3-second connect timeout and a **five-second deadline through complete body receipt**; a timed-out asynchronous request is cancelled. A bounded streaming subscriber rejects more than 256 KiB before accumulating an oversized response, including chunked bodies without Content-Length. Unlike `BodyHandlers.ofInputStream()`, this does not leave a blocking body read outside the deadline. Plain HTTP is permitted only for explicit loopback development issuers by `OidcSettings`. Network calls are blocking and must eventually execute on a bounded I/O dispatcher.

Synthetic tests exercise good discovery and key material; negative issuer, endpoint, host, Unicode URI, algorithm and private-key cases. Invalid metadata tests record attempted fetch URLs and assert that **only the pinned discovery URL is fetched**: a fake fetcher throwing for an untrusted JWKS URL is not enough to prove origin validation. A loopback HTTP server verifies successful chunked metadata and JWKS reads, exactly 256 KiB accepted, 256 KiB plus one byte rejected for the **size limit**, redirects rejected and delayed-body failure (headers sent immediately, body stalled beyond five seconds). This is **not a full end-to-end Keycloak smoke test**. Metadata and JWKS refresh/caching/expiry, issuer-consistent container access and authorization-code exchange are follow-ups. **Fail closed** if discovery cannot be validated; never use stale keys without an explicit reviewed rotation policy.

No auth routes were introduced, so OpenAPI is unchanged. See [BFF design](oidc-bff-design.md), [ID token validation](oidc-id-token-validation.md) and [security review checklist](../contributing/security-review.md).

## JWKS runtime refresh (2026-11-14)

The authorization-code exchange now shares one signing-key cache per runtime.
Startup discovery still validates issuer, endpoints and the first key set before
routes start. Refresh fetches only that same vetted JWKS URL, with the same public
RS256 key checks, no redirects, 256 KiB body limit and five-second deadline.
Duplicate signing key IDs are rejected. Metadata/endpoint changes require restart.

Keys expire five minutes after loading. An unknown nonblank RS256 key ID can
trigger an earlier refresh. A synchronized cache permits one fetch at a time;
all refresh attempts, including failures, share a 30-second cooldown. Unknown
IDs never create cache entries. Removed keys disappear on successful refresh.
Expired keys are never accepted after a failed refresh. Still-fresh known keys
remain usable during an unknown-key refresh outage; this bounds key-removal
visibility to five minutes. A provider must publish new keys before using them;
rotation during the cooldown may temporarily reject login. Existing sessions
are unaffected. JWT claims/signatures remain subject to Nimbus verification.

Refresh runs within the existing four-worker token-exchange dispatcher, never
on a Ktor event loop. Token header URLs cannot select network destinations.
No endpoint, cookie, dependency or environment-variable contract changes.

Run targeted tests with `./gradlew test --tests '*OidcSigningKeysTest'
--tests '*OidcProviderDiscoveryTest' --tests '*OidcAuthorizationCodeExchangeTest'`
from the API directory (use `gradlew.bat` on Windows). Provider HTTP limit tests
remain in the discovery suite. Real Keycloak rotation E2E remains a release gate.

Separate follow-ups: login/callback admission rate limits (bounded worker count
is not a request rate limit), and bounded indexed batches to delete expired
login transactions/sessions with lifecycle shutdown and PostgreSQL tests. R05
remains open; session routes, browser E2E and authorization/privacy gates must
also be reviewed before public use.
