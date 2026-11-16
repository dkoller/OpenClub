# One-command local startup

Docker Compose v2 runs PostgreSQL, the Kotlin/Ktor API and the React web application on Windows, macOS and Linux.

## Requirements

- Windows: Docker Desktop with WSL 2 and Linux containers
- macOS: Docker Desktop (Intel or Apple Silicon)
- Linux: Docker Engine and the Compose plugin

No host Java, Gradle, Node.js or pnpm installation is required.

From the repository root:

```sh
docker compose up -d --build
```

Open http://localhost:5173. View logs with `docker compose logs -f`. Stop with `docker compose down`.

The PostgreSQL named volume survives stopping. **Only** `docker compose down -v` deletes its data.

## Development mode

For Vite hot reload and continuous Gradle recompilation:

```sh
docker compose -f compose.yaml -f compose.dev.yaml up --build
```

Stop with `docker compose -f compose.yaml -f compose.dev.yaml down`.

On Windows, a WSL 2 filesystem generally improves file-watch performance. Gradle continuous mode recompiles changes but **does not guarantee restarting a running Ktor process**. Restart the API container to apply Kotlin changes when necessary.

## Optional identity provider for local auth development

Keycloak is **not** part of the default startup. The backend's OIDC browser login, server-side sessions, logout, and guarded club/member/team/grant administration **are implemented** for synthetic-data development; they remain disabled in the default Compose command because no OIDC client, credentials, or verified club identity is preconfigured. To enable local sign-in, use [Keycloak setup](../../development/keycloak-local.md), create a confidential OIDC client and synthetic user, provide the documented auth environment variables, start the `compose.auth.dev.yaml` overlay (including its API loopback backchannel), and use the restricted [first-club operator bootstrap](../../deployment/single-club.md#operator-only-first-club-bootstrap-pre-production). Sign-in alone does **not** grant club membership or ADMIN capability. The [real Keycloak browser CI](../../../.github/workflows/auth-e2e.yml) proves the synthetic authorization journey; stock `docker compose up` does not.

## Safety and limitations

- Default password `openclub_dev` is **local development only**. Set `POSTGRES_PASSWORD` to override it before creating a volume; changing it later does not update an existing database user password.
- Ports 5173 and 5432 bind to localhost. The API is internal to the Compose network.
- API startup runs Flyway migrations; `/health` reports liveness, while `/api/ready` through the web proxy checks PostgreSQL availability. Neither validates OIDC availability or production security.
- Initial image builds download dependencies and may take several minutes.
- This is not a production deployment configuration; production requires secrets, TLS, backups and hardening.

## Optional backend authentication settings (implemented, opt-in)

Normal one-command startup is unchanged: auth defaults to disabled and has no OIDC client credentials. The implemented backend accepts explicit `OPENCLUB_AUTH_MODE=development` only with the complete local loopback issuer, client ID, at-least-32-character secret, callback URL, public origin and PostgreSQL settings described in [runtime configuration](../../security/auth-runtime.md). The browser callback is `/api/v1/auth/callback`; use only an exact, approved redirect URI. No public registration or self-claim of club admin rights is provided. Never commit client secrets, fixture passwords, or real member records. Production mode requires HTTPS origin and issuer and additional unfulfilled M1/M2 operational gates.
