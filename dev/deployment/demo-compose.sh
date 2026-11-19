#!/usr/bin/env bash
# Runs docker compose for the one-host synthetic demo with the right files and env:
#   bash dev/deployment/demo-compose.sh /ABSOLUTE/ENV_DIRECTORY <compose arguments>
# It adds compose.rauthy-email.demo.yaml when configure-demo-email.sh has stored email settings.
# OPENCLUB_DEMO_PROJECT overrides the project name (CI uses its own disposable project).
set -euo pipefail
[[ $# -ge 2 && "$1" == /* && -f "$1/.env" ]] ||
  { echo "Usage: bash dev/deployment/demo-compose.sh /ABSOLUTE/ENV_DIRECTORY <compose arguments>" >&2; exit 2; }
env_dir="$1"; shift
root="$(cd "$(dirname "$0")/../.." && pwd)"
project="${OPENCLUB_DEMO_PROJECT:-$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' "$env_dir/.env")}"
[[ -n "$project" ]] || { echo "COMPOSE_PROJECT_NAME missing from $env_dir/.env" >&2; exit 1; }
files=(-f "$root/compose.yaml" -f "$root/compose.https.demo.yaml" -f "$root/compose.rauthy.demo.yaml")
grep -q '^RAUTHY_SMTP_HOST=' "$env_dir/.env" && files+=(-f "$root/compose.rauthy-email.demo.yaml")
exec docker compose --project-directory "$root" --env-file "$env_dir/.env" -p "$project" "${files[@]}" "$@"
