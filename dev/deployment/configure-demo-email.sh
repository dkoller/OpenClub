#!/usr/bin/env bash
# Adds outgoing email for the demo's Rauthy to the env file created by generate-demo-env.sh, using an
# Infomaniak mailbox (mail.infomaniak.com, port 465, implicit TLS, username = full address). The sender is
# email.sender in the demo config. The mailbox password is read from standard input, never from the command
# line or the config, and is never printed.
# Afterwards include compose.rauthy-email.demo.yaml (deploy-demo.sh does this automatically).
set -euo pipefail

[[ $# -eq 1 && "$1" == --synthetic-only ]] ||
  { echo "Usage: printf '%s' \"\$MAILBOX_PASSWORD\" | bash dev/deployment/configure-demo-email.sh --synthetic-only" >&2; exit 2; }
cfg() { python3 "$(dirname "$0")/demo-config.py" get "$1"; }
env_dir="$(cfg envDirectory)" sender="$(cfg emailSender)"
[[ -n "$sender" ]] || { echo "Set email.sender in the demo config first" >&2; exit 2; }
[[ -f "$env_dir/.env" ]] || { echo "$env_dir/.env not found; run deploy-demo.sh first" >&2; exit 1; }
[[ ! -t 0 ]] || { echo "Pipe the mailbox password on standard input; it is never taken as an argument." >&2; exit 2; }
IFS= read -r password || [[ -n "$password" ]]
[[ -n "$password" ]] || { echo "Empty mailbox password" >&2; exit 2; }
# Compose env files have no escaping for newlines or a leading quote; refuse what cannot round-trip.
[[ "$password" != *$'\n'* && "$password" != *"'"* ]] || { echo "Mailbox password contains a newline or a single quote; change it in the Infomaniak Manager first." >&2; exit 2; }

umask 077
tmp="$(mktemp "$env_dir/.env.XXXXXX")"
trap 'rm -f -- "$tmp"' EXIT
grep -v '^RAUTHY_SMTP_' "$env_dir/.env" > "$tmp"
{
  echo "RAUTHY_SMTP_HOST=mail.infomaniak.com"
  echo "RAUTHY_SMTP_PORT=465"
  echo "RAUTHY_SMTP_USERNAME=$sender"
  echo "RAUTHY_SMTP_FROM='OpenClub demo <$sender>'"
  printf "RAUTHY_SMTP_PASSWORD='%s'\n" "$password"
} >> "$tmp"
chmod 0600 "$tmp"
mv -f -- "$tmp" "$env_dir/.env"
trap - EXIT
echo "Email settings stored in $env_dir/.env for $sender. No password was printed."
