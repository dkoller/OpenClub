#!/usr/bin/env bash
# Copies the OpenClub client secret that Rauthy generated on its first start into the demo env file
# created by generate-demo-env.sh (envDirectory in the demo config). Run once, after `up -d rauthy` and before starting the API. It reads
# the secret with Rauthy's own `bootstrap get` command inside the container and never prints it.
set -euo pipefail

[[ $# -eq 1 && "$1" == --synthetic-only ]] ||
  { echo "Usage: bash dev/deployment/fetch-demo-client-secret.sh --synthetic-only   (settings from the demo config)" >&2; exit 2; }
env_dir="$(python3 "$(dirname "$0")/demo-config.py" get envDirectory)"
demo=(bash "$(dirname "$0")/demo-compose.sh")

value=""
for _ in {1..60}; do
  # The image's default config file plus the container's env overrides (encryption keys) decrypt the secret.
  if value="$("${demo[@]}" exec -T rauthy /app/rauthy bootstrap get -c /app/config.toml \
      --kind client --id openclub --field secret --format raw 2>/dev/null)" && [[ ${#value} -ge 32 ]]; then
    break
  fi
  value=""
  sleep 2
done
[[ -n "$value" ]] || { echo "Rauthy has not generated the openclub client secret yet (is rauthy running?)" >&2; exit 1; }
[[ "$value" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Unexpected client secret format" >&2; exit 1; }

umask 077
tmp="$(mktemp "$env_dir/.env.XXXXXX")"
trap 'rm -f -- "$tmp"' EXIT
grep -v '^OPENCLUB_OIDC_CLIENT_SECRET=' "$env_dir/.env" > "$tmp"
printf 'OPENCLUB_OIDC_CLIENT_SECRET=%s\n' "$value" >> "$tmp"
chmod 0600 "$tmp"
mv -f -- "$tmp" "$env_dir/.env"
trap - EXIT
echo "Stored the generated OpenClub client secret in $env_dir/.env. No secret was printed."
