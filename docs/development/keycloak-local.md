# Local Keycloak development identity provider

**Scope:** Optional local OIDC test provider for [R05/#14](https://github.com/dkoller/OpenClub/issues/14), not an authentication implementation or production configuration. R05 implemented and tested Ktor OIDC login and sessions. The first-release web UI now has an installation-local sign-in landing; no real member data is enabled. This setup is intentionally separate from the normal `docker compose up` path.

## Start a fresh local instance

Use Docker Compose v2. Supply a **unique development-only** administrator password through your shell or a gitignored `.env` file; do not place it in a tracked file or commit it.

```sh
# In your shell, securely set KEYCLOAK_DEV_ADMIN_PASSWORD first.
docker compose -f compose.yaml -f compose.auth.dev.yaml up -d keycloak
```

Open the local administration console at http://localhost:8180/admin/ and sign in as `openclub-local-admin` with the password you chose. Keycloak publishes its OIDC discovery metadata at:

```text
http://localhost:8180/realms/openclub-dev/.well-known/openid-configuration
```

The source file is `dev/keycloak/openclub-realm.json`, mounted inside the container as `openclub-dev-realm.json` to satisfy Keycloak's required `<realm-name>-realm.json` startup-import naming convention. The CI smoke test starts Keycloak and checks the realm discovery document, not just Compose syntax.

The imported realm `openclub-dev` intentionally contains **no preset users, passwords, clients, or club memberships**. Self-registration is disabled. The Keycloak bootstrap administrator is **only** an identity-provider administrator; it is not automatically a Club ADMIN.

## Configure a test OIDC client

In the Keycloak admin console, choose the `openclub-dev` realm and create an OIDC client named `openclub-bff` with **Client authentication ON** (confidential client), Standard flow ON, Direct access grants OFF, and implicit flow OFF. Use a narrowly scoped local callback URI such as `http://localhost:5173/api/v1/auth/callback`; do not use wildcard redirect URIs or production credentials. The implemented login requires PKCE S256 and wire the client ID and generated secret through runtime configuration outside source control.

For reproducible synthetic browser-login tests, an **optional** [Keycloak fixture provisioning script](keycloak-e2e-fixtures.md) can create a restricted confidential client and test user with runtime-generated credentials. Normal startup still creates no users or clients; fixture provisioning is explicit and must never be used with real member data.

## Identical issuer URL inside Docker (optional backchannel)

The browser reaches Keycloak at `http://localhost:8180` through the existing host-only published port. The API container has its **own** localhost. To make the *same verified issuer URL* available to the API **without rewriting issuer claims or discovered endpoints**, start the optional sidecar proxy:

```sh
# Start the normal stack and optional local Keycloak first.
docker compose up -d --build --wait
docker compose -f compose.yaml -f compose.auth.dev.yaml up -d keycloak oidc-backchannel

# Browser/host discovery:
curl --fail http://localhost:8180/realms/openclub-dev/.well-known/openid-configuration

# Exact same issuer URL from *inside* the API container:
docker compose -f compose.yaml -f compose.auth.dev.yaml exec -T api \
  wget -q -O - http://localhost:8180/realms/openclub-dev/.well-known/openid-configuration
```

The `oidc-backchannel` service shares the API network namespace (`network_mode: service:api`) and serves a **loopback-only** `127.0.0.1:8180` proxy to `keycloak:8080`. This does **not** publish the proxy to the host or other containers. Keycloak continues to advertise `http://localhost:8180/realms/openclub-dev`; signed ID-token `iss` and discovery must match that exact string. The CI Compose smoke test fetches discovery and JWKS through this API-internal path, compares host/container issuer and checks API health.

This is **development-only**: loopback HTTP, Keycloak's `start-dev` mode and the optional proxy must not ship as production defaults. Do not modify `OidcSettings` to accept arbitrary HTTP issuer hosts. Docker startup alone does not imply a login works: a separately configured OIDC client, callback, sessions and a verified member link are still required. If the API container is replaced/recreated, restart the sharing proxy too: `docker compose -f compose.yaml -f compose.auth.dev.yaml up -d --force-recreate oidc-backchannel`.

## Restart, reset and verify

```sh
# Confirm development realm discovery after Keycloak starts:
curl --fail http://localhost:8180/realms/openclub-dev/.well-known/openid-configuration

# Stop Keycloak, retaining local test accounts/config:
docker compose -f compose.yaml -f compose.auth.dev.yaml stop keycloak

# To remove only Keycloak's dev volume, inspect volumes and remove it manually.
# Do not use "down -v" against this combined project: it also removes OpenClub PostgreSQL data.
```

The realm import only creates missing realms; changes to the JSON are not reapplied to an existing realm. For local experiments, update settings through the UI or reset the specific Keycloak dev volume after taking any needed backup. User data in the development realm is synthetic only.

This uses Keycloak `26.8.2` pinned for reproducibility. Check release notes and security updates when upgrading. `start-dev`, loopback HTTP and the embedded database are strictly **development-only**; production requires secure configuration, TLS, a durable supported database, secret management and resource sizing.

## Smoke test and limitations

```sh
KEYCLOAK_DEV_ADMIN_PASSWORD=temporary-only-for-compose-validation \
  docker compose -f compose.yaml -f compose.auth.dev.yaml config --quiet
```

The command validates Compose syntax only and does **not** start the identity provider or test OIDC authentication. See [BFF security design](../security/oidc-bff-design.md) and [ADR-0003](../decisions/0003-oidc-session-proposal.md) for security requirements. This is a development-only provider guide; the current HTTP auth contract and installation-local club lookup are documented separately.

### JWKS rotation troubleshooting

The API refreshes the startup-vetted JWKS URL after five minutes or on an unknown
signing-key ID, with a shared 30-second cooldown. Publish replacement keys before
using them. If the provider is unavailable after cache expiry, login fails closed;
restore provider connectivity and retry after the cooldown. Changing metadata or
the JWKS URL requires an API restart. See [refresh policy](../security/oidc-provider-discovery.md#jwks-runtime-refresh-2026-11-14).
