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

## Safety and limitations

- Default password `openclub_dev` is **local development only**. Set `POSTGRES_PASSWORD` to override it before creating a volume; changing it later does not update an existing database user password.
- Ports 5173 and 5432 bind to localhost. The API is internal to the Compose network.
- API startup runs Flyway migrations; `/health` reports liveness rather than full application readiness.
- Initial image builds download dependencies and may take several minutes.
- This is not a production deployment configuration; production requires secrets, TLS, backups and hardening.
