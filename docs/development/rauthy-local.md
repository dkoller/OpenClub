# Optional Rauthy evaluation (synthetic data only)

**Status:** Experimental provider compatibility, not a complete login integration or production hosting recipe. [ADR-0006](../decisions/0006-oidc-provider-choice.md) requires **both Rauthy and Keycloak** to stay supported. Keep using the existing [Keycloak development and browser tests](keycloak-local.md) for established end-to-end acceptance.

## Inspect a lightweight provider

With Docker Compose v2, from the repository root:

```sh
docker compose -f compose.yaml -f compose.rauthy.eval.yaml config --quiet
docker compose -f compose.yaml -f compose.rauthy.eval.yaml up -d --no-deps rauthy
curl --fail --silent --show-error --insecure https://localhost:8443/.well-known/openid-configuration
docker compose -f compose.yaml -f compose.rauthy.eval.yaml stop rauthy
```

The **only** permitted use of `--insecure` above is against Rauthy's explicitly isolated, localhost-only **self-signed development certificate**. Never bypass certificate checks for deployed OpenClub or its external OIDC issuer. The Rauthy `LOCAL_TEST=true` fixture includes unsafe demo defaults and must never be enabled on an internet-facing service. The 8443 port is bound to loopback; the evaluation has a separate persistent named volume and does not enable OpenClub authentication by itself. Do not log or commit generated administrator credentials.

Rauthy publishes its discovery document at the **origin root**, but advertises a path-qualified issuer (in local evaluation, `https://localhost:8443/auth/v1/`). OpenClub's OIDC discovery now tries the issuer-relative URL first and, **only on an HTTP 404**, tries the issuer's root-origin discovery URL. Metadata still must match the configured issuer exactly, and endpoints/RS256/JWKS remain pinned. The discovery endpoint exposes the issuer, endpoints, supported signing algorithms and JWKS. It does not establish that an OpenClub confidential client exists, or that an ID token will use RS256.

## Disposable confidential-client bootstrap

The optional evaluation overlay now mounts `dev/rauthy/bootstrap/clients.json` **read-only**.
On first start with an empty Rauthy evaluation database it provisions a synthetic
`openclub-rauthy-ci` client, using an **internally generated secret**, exact
`http://localhost:5173/api/v1/auth/callback` redirect, authorization-code flow,
S256 challenge and **RS256** ID tokens. This is a local test client only; the
callback uses HTTP solely because the development web server is loopback-only.

In the disposable GitHub Compose test, the Rauthy CLI retrieves the generated
client secret *inside the running container*, checks its length without
printing or archiving it, and continues to validate RSA JWKS/discovery. The disposable CI then restarts only the Rauthy container (keeping its named volume), waits for discovery to recover, and checks that the RSA public signing keys and generated confidential-client secret are unchanged; this is a narrow identity-persistence regression, not an independently recovered backup or actual authenticated login.
The bootstrap client is created on **first initialization only**; changing its
JSON later does not update an existing Rauthy volume. A successful bootstrap
is **not** a proven browser login, MFA/passkey flow or usable OpenClub session.
Rauthy's independent provider credentials and database remain isolated from
Keycloak. Do not copy this synthetic client or configuration to production.

## Synthetic user and authorization initiation

The read-only `dev/rauthy/bootstrap/users.json` fixture creates an enabled,
email-verified, non-administrator test identity with an **internally generated
password** in disposable CI. Its credentials are retrieved via Rauthy's local
bootstrap CLI only into shell variables, not logs or uploaded artifacts.

The Compose smoke test now also sends a **real authorization-code request** to
Rauthy's advertised authorization endpoint, naming the provisioned confidential
client and exact OpenClub development callback, with `state`, `nonce`,
`openid` scope and a valid S256 PKCE challenge. It verifies that the provider
accepts this initiation without following redirects or exposing callback values.
This checks an earlier interoperability boundary than the Kotlin callback:
it **does not yet prove password/MFA/passkey sign-in, code exchange or an OpenClub
session**. Those remain acceptance criteria in [#279](https://github.com/dkoller/OpenClub/issues/279).

## Real HTTPS browser acceptance (additional provider, not a replacement)

Rauthy 0.36.1 advertised a non-BCP47 `zhhans` UI locale by default, which the strict Nimbus parser correctly rejected during the real JVM startup. The optional local evaluation fixture is now pinned to **Rauthy 0.37.2** (a later security release), which fixes the advertised tag to `zh-Hans`. The Kotlin OIDC verifier and metadata validation stay unchanged; no local filter or parsing bypass is needed.

The optional `.github/workflows/rauthy-e2e.yml` starts the **real** Rauthy
identity provider and a disposable PostgreSQL database on a GitHub runner,
then starts Kotlin/Ktor in its normal **production HTTPS-only auth mode**
with a confidential RS256 OIDC client. It serves the existing React frontend
through a loopback-only Caddy proxy at `https://localhost:5443`.

Only the Rauthy fixture's generated localhost certificate is imported into a
**throwaway JVM truststore**. The application does **not** disable Java HTTPS
certificate or hostname verification. Playwright ignores certificate warnings
only for the separately isolated, localhost-only testing authorities. No
insecure external provider, public hosting, OAuth verifier relaxation, test
storage state, token, credential or authentication trace is published.

The `openclub-rauthy-https-ci` client in the bootstrap fixture is separate
from the existing HTTP initiation-only client and has an exact HTTPS callback.
It has a generated secret, authorization-code flow, S256 PKCE and RS256 tokens.
Synthetic test account credentials are generated on the disposable provider
and used only in CI.

The browser test is intended to verify a **real** Rauthy password login,
a valid app-side authenticated session, denied access to private club/member/
team data until an explicit operator bootstrap, and logout. The provider's
**MFA and passkey browser flows remain separate pending #279 acceptance**.
The existing Keycloak dev overlay and all Keycloak browser checks remain
supported, are neither removed nor replaced, and must continue passing.

## Secure HTTPS loopback configuration for the next login test

OpenClub now permits **matching HTTPS issuer and HTTPS callback** in
`OPENCLUB_AUTH_MODE=development` **only when both hosts are loopback**.
HTTPS development uses the same `Secure`, `HttpOnly`, host-only session
and login-binding cookies as production. Existing loopback HTTP Keycloak
development still works with its original development cookies; production
continues to require HTTPS without exception. Mixed HTTP/HTTPS schemes and
non-loopback development issuers or callbacks are rejected at startup.

A future browser login fixture can configure `OPENCLUB_OIDC_ISSUER` as
`https://localhost:8443/auth/v1/`, the **exact** advertised Rauthy
authorization endpoint, and an `https://localhost:5173` OpenClub callback.
This does **not** make the existing HTTP-only Rauthy bootstrap callback
compatible with HTTPS: that client must be provisioned separately with the
exact HTTPS callback before an actual browser login is attempted.

The provider certificate must be trusted by the JVM and browser using an
explicit, test-scoped CA/trust store. Do **not** disable certificate or hostname
validation, globally modify Java trust, accept self-signed certificates in
production, or reuse Rauthy's unsafe `LOCAL_TEST` configuration externally.
This change enables secure runtime configuration only; no actual Rauthy login
or passkey browser acceptance is claimed.

## Synthetic Rauthy passkey browser acceptance

The optional Rauthy evaluation overlay uses `WEBAUTHN_PASSKEY_REG_TYPE=resident_key`
**only for disposable virtual-authenticator testing**. This restricts the local
fixture to discoverable credentials so Chromium can test username-less passkey
login and prove the WebAuthn assertion actually signed. **Do not copy this
setting into a production identity provider without reviewing supported
authenticators and security-key storage limits.** Users of ordinary nonresident
keys can instead use Rauthy's E-Mail-first WebAuthn flow.

The additional `dev/auth-e2e/rauthy-passkey.spec.js` exercises password-based
account login, password reauthentication to obtain permission to modify MFA,
named passkey registration through Chromium's virtual FIDO2 authenticator,
independent Rauthy logout and clearing of SSO cookies, then passkey-only
sign-in through OpenClub. The test must observe a signed assertion and the
normal application session with **no club grants**. No virtual credential,
private key, raw WebAuthn response, account session or browser trace is
stored in artifacts. The **existing Keycloak test and Rauthy password
test must both remain green**. This acceptance does not cover TOTP/email
OTP recovery, independent backup restore or live-data deployment.

## OpenClub OIDC compatibility checklist

OpenClub's existing runtime requires one exact verified issuer, a confidential `client_secret_basic` client, HTTPS (unless explicitly isolated loopback development), exact redirect URI, S256 PKCE, same-origin authorization/token/JWKS endpoints, and **RS256** ID tokens. Rauthy normally defaults new clients to EdDSA; configure **RS256** on the specific OpenClub OIDC client, retain PKCE S256, and verify that a signed login token passes the real Kotlin verifier. Never widen validation or match an account by email to make a provider switch work.

Before marking Rauthy integrated: exercise a real browser callback with a disposable account, **password + MFA** and **passkey** registration/login, sign-out/session revocation, rejected invalid tokens, issuer-key rotation, identity-provider restart, and restore. If same-origin discovery/JWKS or confidential-client authentication do not work, fix the integration instead of disabling OpenClub security checks. Document the exact Rauthy version and configuration used; preserve all existing Keycloak CI coverage.

For a publicly reachable Swiss VPS, replace this evaluation fixture with a separate **production-style Rauthy configuration**: publicly trusted HTTPS issuer, protected encryption/Hiqlite secrets, durable and independently backed-up data, restricted admin bootstrap, email recovery, resource sizing and appropriately isolated proxy routing. The current public HTTPS OpenClub overlay assumes an *independently reachable* OIDC issuer and is not proof of self-hosted Rauthy production readiness.

Rauthy references: [Docker evaluation](https://sebadob.github.io/rauthy/getting_started/docker.html), [bootstrap](https://sebadob.github.io/rauthy/config/bootstrap.html), [RSA signing support](https://sebadob.github.io/rauthy/work/jwks.html).
