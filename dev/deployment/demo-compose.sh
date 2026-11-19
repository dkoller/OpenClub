#!/usr/bin/env bash
# Runs docker compose for the one-host synthetic demo with the right files and env:
#   bash dev/deployment/demo-compose.sh <compose arguments>        (for example: ps, logs api, restart web)
# Settings come from the demo config (OPENCLUB_DEMO_CONFIG or /etc/openclub/demo.yaml); the generated secrets from
# its envDirectory. It adds compose.rauthy-email.demo.yaml once configure-demo-email.sh has stored email settings.
# OPENCLUB_DEMO_PROJECT overrides the project name (CI uses its own disposable project).
set -euo pipefail
[[ $# -ge 1 ]] || { echo "Usage: bash dev/deployment/demo-compose.sh <compose arguments>" >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
env_dir="$(python3 "$here/demo-config.py" get envDirectory)"
[[ -f "$env_dir/.env" ]] || { echo "$env_dir/.env not found; run generate-demo-env.sh (or deploy-demo.sh) first" >&2; exit 1; }
project="${OPENCLUB_DEMO_PROJECT:-$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' "$env_dir/.env")}"
[[ -n "$project" ]] || { echo "COMPOSE_PROJECT_NAME missing from $env_dir/.env" >&2; exit 1; }
files=(-f "$root/compose.yaml" -f "$root/compose.https.demo.yaml" -f "$root/compose.rauthy.demo.yaml")
grep -q '^RAUTHY_SMTP_HOST=' "$env_dir/.env" && files+=(-f "$root/compose.rauthy-email.demo.yaml")
exec docker compose --project-directory "$root" --env-file "$env_dir/.env" -p "$project" "${files[@]}" "$@"
