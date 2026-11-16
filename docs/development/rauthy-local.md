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
printing or archiving it, and continues to validate RSA JWKS/discovery.
The bootstrap client is created on **first initialization only**; changing its
JSON later does not update an existing Rauthy volume. A successful bootstrap
is **not** a proven browser login, MFA/passkey flow or usable OpenClub session.
Rauthy's independent provider credentials and database remain isolated from
Keycloak. Do not copy this synthetic client or configuration to production.

## OpenClub OIDC compatibility checklist

OpenClub's existing runtime requires one exact verified issuer, a confidential `client_secret_basic` client, HTTPS (unless explicitly isolated loopback development), exact redirect URI, S256 PKCE, same-origin authorization/token/JWKS endpoints, and **RS256** ID tokens. Rauthy normally defaults new clients to EdDSA; configure **RS256** on the specific OpenClub OIDC client, retain PKCE S256, and verify that a signed login token passes the real Kotlin verifier. Never widen validation or match an account by email to make a provider switch work.

Before marking Rauthy integrated: exercise a real browser callback with a disposable account, **password + MFA** and **passkey** registration/login, sign-out/session revocation, rejected invalid tokens, issuer-key rotation, identity-provider restart, and restore. If same-origin discovery/JWKS or confidential-client authentication do not work, fix the integration instead of disabling OpenClub security checks. Document the exact Rauthy version and configuration used; preserve all existing Keycloak CI coverage.

For a publicly reachable Swiss VPS, replace this evaluation fixture with a separate **production-style Rauthy configuration**: publicly trusted HTTPS issuer, protected encryption/Hiqlite secrets, durable and independently backed-up data, restricted admin bootstrap, email recovery, resource sizing and appropriately isolated proxy routing. The current public HTTPS OpenClub overlay assumes an *independently reachable* OIDC issuer and is not proof of self-hosted Rauthy production readiness.

Rauthy references: [Docker evaluation](https://sebadob.github.io/rauthy/getting_started/docker.html), [bootstrap](https://sebadob.github.io/rauthy/config/bootstrap.html), [RSA signing support](https://sebadob.github.io/rauthy/work/jwks.html).
