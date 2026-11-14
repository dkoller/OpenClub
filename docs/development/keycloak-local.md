# Local Keycloak development identity provider

**Scope:** Optional local OIDC test provider for [R05/#14](https://github.com/dkoller/OpenClub/issues/14), not an authentication implementation or production configuration. No user login flow is wired into Ktor/React yet. This setup is intentionally separate from the normal `docker compose up` path.

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

The imported realm `openclub-dev` intentionally contains **no preset users, passwords, clients, or club memberships**. Self-registration is disabled. The Keycloak bootstrap administrator is **only** an identity-provider administrator; it is not automatically a Club ADMIN.

## Configure a test OIDC client

In the Keycloak admin console, choose the `openclub-dev` realm and create an OIDC client named `openclub-bff` with **Client authentication ON** (confidential client), Standard flow ON, Direct access grants OFF, and implicit flow OFF. Use a narrowly scoped local callback URI such as `http://localhost:5173/api/v1/auth/callback`; do not use wildcard redirect URIs or production credentials. When the login implementation is added, require PKCE S256 and wire the client ID and generated secret through runtime configuration outside source control.

**Important:** Keycloak's local hostname (`localhost:8180`) is designed for browser access. Inside Docker, `localhost` does **not** resolve to the Keycloak service from the API container. PR C must define and test an issuer-consistent internal/public routing strategy before configuring Ktor to verify tokens. Do not work around issuer validation by accepting mismatched issuers or arbitrary URLs. Nothing in this PR claims an end-to-end login is ready.

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

The command validates Compose syntax only and does **not** start the identity provider or test OIDC authentication. See [BFF security design](../security/oidc-bff-design.md) and [ADR-0003](../decisions/0003-oidc-session-proposal.md) for security requirements. No HTTP auth endpoints or OpenAPI behavior change in this PR.
