#!/usr/bin/env bash
# Operator-only first-club bootstrap for the one-host SYNTHETIC demo: runs the restricted BootstrapCli inside
# the API container (database settings come from the container). The administrator's member record gets
# fictional details; the account must already have signed in once.
#   bash dev/deployment/bootstrap-demo-club.sh --synthetic-only ACCOUNT_ID
# The club name is clubName in the demo config.
set -euo pipefail
[[ $# -eq 2 && "$1" == --synthetic-only ]] ||
  { echo "Usage: bash dev/deployment/bootstrap-demo-club.sh --synthetic-only ACCOUNT_ID" >&2; exit 2; }
account="$2"
club_name="$(python3 "$(dirname "$0")/demo-config.py" get clubName)"
[[ "$account" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] ||
  { echo "ACCOUNT_ID must be the lowercase UUID shown as accountId at /api/v1/auth/me" >&2; exit 2; }
[[ -n "${club_name// }" && ${#club_name} -le 200 ]] || { echo "Club name must be 1 to 200 characters" >&2; exit 2; }
slug="$(printf '%s' "$club_name" | tr '[:upper:]' '[:lower:]' |
  sed 's/ä/ae/g; s/ö/oe/g; s/ü/ue/g; s/é/e/g; s/è/e/g; s/ê/e/g; s/à/a/g; s/â/a/g; s/ç/c/g' | tr -cs 'a-z0-9' '-' | sed 's/^-*//; s/-*$//')"
# The database accepts 3 to 60 lowercase letters, digits and single hyphens.
slug="$(printf '%s' "${slug:0:60}" | sed 's/-*$//')"
[[ ${#slug} -ge 3 ]] || slug=demo-club

bash "$(dirname "$0")/demo-compose.sh" exec -T \
  -e OPENCLUB_BOOTSTRAP_CONFIRM=create-first-club \
  -e OPENCLUB_BOOTSTRAP_ACCOUNT_ID="$account" \
  -e OPENCLUB_BOOTSTRAP_CLUB_NAME="$club_name" \
  -e OPENCLUB_BOOTSTRAP_CLUB_SLUG="$slug" \
  -e OPENCLUB_BOOTSTRAP_FIRST_NAME=Demo \
  -e OPENCLUB_BOOTSTRAP_LAST_NAME=Administrator \
  -e OPENCLUB_BOOTSTRAP_BIRTH_DATE=1990-01-01 \
  -e OPENCLUB_BOOTSTRAP_EMAIL=administrator@example.invalid \
  -e OPENCLUB_BOOTSTRAP_PHONE=+41000000000 \
  api java -cp '/app/lib/*' org.openclub.api.club.BootstrapCliKt
echo "Club \"$club_name\" created; reload https://$(python3 "$(dirname "$0")/demo-config.py" get clubHost) to see the administration."
