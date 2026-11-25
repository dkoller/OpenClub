#!/usr/bin/env bash
# Turns on push notifications for the one-host SYNTHETIC demo (OpenAPI 1.33.0): generates a VAPID key pair with
# the app's own command inside the API container and writes it, the contact (adminEmail) and the allowed push
# hosts (push.hosts in the demo config) to the private env file. Keys are never printed. Rerunning keeps the
# existing keys (replacing them would make every device subscribe again) and only updates the hosts.
# Afterwards restart the API: bash dev/deployment/demo-compose.sh up -d api
set -euo pipefail
[[ $# -eq 1 && "$1" == --synthetic-only ]] ||
  { echo "Usage: bash dev/deployment/configure-demo-push.sh --synthetic-only" >&2; exit 2; }
cfg() { python3 "$(dirname "$0")/demo-config.py" get "$1"; }
env_dir="$(cfg envDirectory)" hosts="$(cfg pushHosts)" contact="$(cfg adminEmail)"
[[ -n "$hosts" ]] || { echo "Set push.hosts in the demo config first" >&2; exit 2; }
[[ -f "$env_dir/.env" ]] || { echo "$env_dir/.env not found; run deploy-demo.sh first" >&2; exit 1; }

umask 077
keys="$(grep -E '^OPENCLUB_PUSH_(PUBLIC|PRIVATE)_KEY=.+' "$env_dir/.env" || true)"
if [[ -z "$keys" ]]; then
  keys="$(bash "$(dirname "$0")/demo-compose.sh" exec -T api java -cp '/app/lib/*' org.openclub.api.notifications.PushKeysCliKt)"
fi
[[ "$(printf '%s\n' "$keys" | grep -c '^OPENCLUB_PUSH_')" -eq 2 ]] || { echo "Could not create the push keys" >&2; exit 1; }
tmp="$(mktemp "$env_dir/.env.XXXXXX")"
trap 'rm -f -- "$tmp"' EXIT
grep -v '^OPENCLUB_PUSH_' "$env_dir/.env" > "$tmp"
{
  printf '%s\n' "$keys"
  echo "OPENCLUB_PUSH_SUBJECT=mailto:$contact"
  echo "OPENCLUB_PUSH_HOSTS=$hosts"
} >> "$tmp"
chmod 0600 "$tmp"
mv -f -- "$tmp" "$env_dir/.env"
trap - EXIT
echo "Push settings stored in $env_dir/.env (hosts: $hosts). No key was printed. Restart the API to use them."
