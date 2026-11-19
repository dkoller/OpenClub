#!/usr/bin/env bash
# Fills a freshly bootstrapped SYNTHETIC demo club with fictional teams and members (seed-demo-data.sql).
# Runs in one transaction and refuses any database that is not a fresh club, so it cannot touch real data
# or run twice.
set -euo pipefail
[[ $# -eq 2 && "$1" == --synthetic-only && "$2" == /* && -f "$2/.env" ]] ||
  { echo "Usage: bash dev/deployment/seed-demo-data.sh --synthetic-only /ABSOLUTE/ENV_DIRECTORY" >&2; exit 2; }
here="$(dirname "$0")"
bash "$here/demo-compose.sh" "$2" exec -T postgres \
  psql --single-transaction -v ON_ERROR_STOP=1 -U openclub -d openclub < "$here/seed-demo-data.sql"
