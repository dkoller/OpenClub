#!/usr/bin/env bash
# Read-only, synthetic-only Docker resource samples. Never restarts or deletes containers.
set -euo pipefail

if [[ "${1:-}" != "--synthetic-only" || $# -ne 1 || -z "${COMPOSE_PROJECT_NAME:-}" ]]; then
  echo "Usage: COMPOSE_PROJECT_NAME=<synthetic-project> bash dev/measure-synthetic-compose.sh --synthetic-only" >&2
  exit 2
fi
if [[ ! "$COMPOSE_PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
  echo "Invalid Compose project name" >&2
  exit 2
fi
command -v docker >/dev/null || { echo "Docker is required" >&2; exit 1; }
[[ -f compose.yaml ]] || { echo "Run from the repository root" >&2; exit 2; }

containers=()
for service in postgres api web; do
  id="$(docker compose -p "$COMPOSE_PROJECT_NAME" -f compose.yaml ps -q "$service")"
  [[ "$id" =~ ^[[:xdigit:]]{12,64}$ ]] || {
    echo "Synthetic $service container is missing" >&2
    exit 1
  }
  # Never accidentally sample a different Compose project or a stopped service.
  actual_project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$id")"
  actual_service="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$id")"
  running="$(docker inspect --format '{{.State.Running}}' "$id")"
  if [[ "$actual_project" != "$COMPOSE_PROJECT_NAME" || "$actual_service" != "$service" || "$running" != true ]]; then
    echo "Unexpected container identity or stopped $service" >&2
    exit 1
  fi
  containers+=("$id")
done

# A database without its configured named volume cannot prove restart persistence.
postgres_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Type}}{{end}}{{end}}' "${containers[0]}")"
[[ "$postgres_volume" == volume ]] || {
  echo "Synthetic PostgreSQL has no persistent named volume" >&2
  exit 1
}

echo "Synthetic Compose project: $COMPOSE_PROJECT_NAME"
echo "Database has a named volume. Read-only Docker container samples follow."
for sample in 1 2 3; do
  echo "Container CPU and memory sample $sample/3:"
  docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' "${containers[@]}"
  if (( sample < 3 )); then sleep 2; fi
done
# Host snapshots complement Docker stats: container samples omit the kernel,
# Docker daemon, identity provider (which may live elsewhere) and cold-start peaks.
# Read-only, no usernames, process lists, volume paths or application secrets.
if command -v free >/dev/null; then
  echo "Host memory and swap snapshot (MiB; Linux):"
  free -m | awk 'NR == 2 { print $1, "total=" $2, "used=" $3, "free=" $4, "available=" $7 }
                 NR == 3 { print $1, "total=" $2, "used=" $3, "free=" $4 }'
else
  echo "Host memory snapshot unavailable: Linux free command not installed."
fi
if command -v df >/dev/null; then
  echo "Filesystem snapshot for current working directory (KiB; other mounts may differ):"
  df -Pk . | awk 'NR == 2 { print "total=" $2, "used=" $3, "available=" $4, "used_percent=" $5 }'
fi
echo "These are instantaneous snapshots, NOT peak load or proof of a 4 GB host."
echo "Record OS/Docker/provider overhead, provider hosting, cold-start/load peaks, separate data/backups volumes, restart/persistence and external TLS/OIDC results independently."
