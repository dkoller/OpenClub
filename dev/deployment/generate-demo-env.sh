#!/usr/bin/env bash
# Creates the private environment directory for the one-host synthetic demo (compose.https.demo.yaml +
# compose.rauthy.demo.yaml): a Compose env file with fresh random secrets and the Rauthy bootstrap file
# for the OpenClub client. It never prints a secret and refuses to touch an existing directory, so a
# rerun can never replace the keys of a running installation. See docs/deployment/infomaniak-demo.md.
set -euo pipefail

usage() {
  echo "Usage: bash dev/deployment/generate-demo-env.sh --synthetic-only CLUB_HOST ID_HOST ADMIN_EMAIL NEW_DIRECTORY" >&2
  exit 2
}
[[ $# -eq 5 && "$1" == --synthetic-only ]] || usage
club_host="$2" id_host="$3" admin_email="$4" target="$5"

host_pattern='^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$'
[[ "$club_host" =~ $host_pattern && "$id_host" =~ $host_pattern ]] ||
  { echo "Hostnames must be lowercase DNS names such as club.example.ch" >&2; exit 2; }
[[ "$club_host" != "$id_host" ]] || { echo "The club and identity hostnames must differ" >&2; exit 2; }
[[ "$admin_email" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] ||
  { echo "ADMIN_EMAIL must be an email address" >&2; exit 2; }
[[ "$target" == /* ]] || { echo "NEW_DIRECTORY must be an absolute path" >&2; exit 2; }
[[ ! -e "$target" ]] || { echo "Refusing to overwrite existing $target" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

# Alphanumeric secrets avoid quoting problems in env files and JSON.
secret() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | head -c "$1"; }

umask 077
mkdir -p "$target/rauthy-bootstrap"
key_id="k$(openssl rand -hex 4)"
client_secret="$(secret 48)"

cat > "$target/.env" <<ENV
# Generated $(date -u +%Y-%m-%dT%H:%M:%SZ) for a SYNTHETIC demo. Contains secrets: keep private, never commit.
COMPOSE_PROJECT_NAME=openclub-demo
OPENCLUB_DEMO_ENV_DIR=$target
OPENCLUB_DEMO_HOST=$club_host
OPENCLUB_ID_HOST=$id_host
POSTGRES_PASSWORD=$(secret 40)
OPENCLUB_OIDC_ISSUER=https://$id_host/auth/v1/
OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT=https://$id_host/auth/v1/oidc/authorize
OPENCLUB_OIDC_CLIENT_ID=openclub
OPENCLUB_OIDC_CLIENT_SECRET=$client_secret
RAUTHY_ENC_KEYS='$key_id/$(openssl rand -base64 32)'
RAUTHY_ENC_KEY_ACTIVE=$key_id
RAUTHY_HQL_SECRET_RAFT=$(secret 40)
RAUTHY_HQL_SECRET_API=$(secret 40)
RAUTHY_ADMIN_EMAIL=$admin_email
RAUTHY_ADMIN_PASSWORD=$(secret 32)
ENV

cat > "$target/rauthy-bootstrap/clients.json" <<JSON
[
  {
    "id": "openclub",
    "name": "OpenClub synthetic demo",
    "secret": "$client_secret",
    "redirect_uris": ["https://$club_host/api/v1/auth/callback"],
    "enabled": true,
    "flows_enabled": ["authorization_code"],
    "access_token_alg": "RS256",
    "id_token_alg": "RS256",
    "auth_code_lifetime": 60,
    "access_token_lifetime": 600,
    "scopes": ["openid"],
    "default_scopes": ["openid"],
    "challenges": ["S256"],
    "force_mfa": false
  }
]
JSON

# Rauthy runs as uid 10001 and must read its bootstrap file, which holds the client secret. As root (the
# normal case on the host) hand it to 10001 and keep it owner-only; otherwise it must be readable by all.
chmod 0600 "$target/.env"
chmod 0711 "$target"
if [[ "$(id -u)" == 0 ]]; then
  chown -R 10001:10001 "$target/rauthy-bootstrap"
  chmod 0700 "$target/rauthy-bootstrap"
  chmod 0600 "$target/rauthy-bootstrap/clients.json"
else
  chmod 0755 "$target/rauthy-bootstrap"
  chmod 0644 "$target/rauthy-bootstrap/clients.json"
  echo "Not root: rauthy-bootstrap/clients.json is world-readable so Rauthy can read it. Run as root on a shared host." >&2
fi

echo "Created $target (.env and rauthy-bootstrap/clients.json). No secret was printed."
echo "The Rauthy administrator password is RAUTHY_ADMIN_PASSWORD in $target/.env."
