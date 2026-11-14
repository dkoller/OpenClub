# Real Keycloak JWKS rotation verification

The browser CI workflow now runs an additional isolated synthetic scenario,
`rotation.spec.js`, after the existing login/session journey. It authenticates
against the configured local Keycloak, adds a higher-priority
`rsa-generated` Keycloak key provider through its Admin REST API, confirms
that the active RS256 key ID and published JWKS changed, and signs in again
through the same running API. The second successful login exercises the API's
unknown-key JWKS refresh without restarting it.

The added provider is deleted in `finally`, including after assertion failure.
This requires only the disposable `openclub-dev` realm and CI-only credentials;
never run it against production or a shared Keycloak instance. No signed ID
token, admin access token, browser trace, screenshot, or server log is uploaded.

This test does **not** advance the clock to five minutes, simulate a JWKS
outage, or prove old-key removal. Those failure paths are covered by unit
tests; complete operational rotation/recovery scenarios remain a security
review topic. No runtime code or authentication contract changes.
