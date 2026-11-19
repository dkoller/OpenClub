#!/usr/bin/env bash
# Creates the private environment directory (envDirectory in the demo config) for the one-host synthetic demo
# (compose.https.demo.yaml + compose.rauthy.demo.yaml): a Compose env file with fresh random secrets and the Rauthy bootstrap file
# for the OpenClub client. Rauthy generates the client secret itself on first start (its bootstrap accepts
# only "generate"); fetch-demo-client-secret.sh then copies it into the env file. This script never prints
# a secret and refuses to touch an existing directory, so a rerun can never replace the keys of a running
# installation. See docs/deployment/infomaniak-demo.md.
set -euo pipefail

# Hostnames, administrator email and the target directory come from the demo config (demo.example.yaml).
[[ $# -eq 1 && "$1" == --synthetic-only ]] ||
  { echo "Usage: bash dev/deployment/generate-demo-env.sh --synthetic-only   (settings: OPENCLUB_DEMO_CONFIG or /etc/openclub/demo.yaml)" >&2; exit 2; }
cfg() { python3 "$(dirname "$0")/demo-config.py" get "$1"; }
python3 "$(dirname "$0")/demo-config.py" check >/dev/null
club_host="$(cfg clubHost)" id_host="$(cfg idHost)" admin_email="$(cfg adminEmail)" target="$(cfg envDirectory)"
[[ ! -e "$target" ]] || { echo "Refusing to overwrite existing $target" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

# Alphanumeric secrets avoid quoting problems in env files and JSON.
secret() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | head -c "$1"; }

umask 077
mkdir -p "$target/rauthy-bootstrap"
key_id="k$(openssl rand -hex 4)"

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
# Placeholder until fetch-demo-client-secret.sh copies the secret Rauthy generated on first start.
OPENCLUB_OIDC_CLIENT_SECRET=pending-run-fetch-demo-client-secret-sh
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
    "secret": "generate",
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

# Only .env holds secrets; the bootstrap file holds none and Rauthy (uid 10001) must be able to read it.
chmod 0600 "$target/.env"
chmod 0755 "$target" "$target/rauthy-bootstrap"
chmod 0644 "$target/rauthy-bootstrap/clients.json"

echo "Created $target (.env and rauthy-bootstrap/clients.json). No secret was printed."
echo "The Rauthy administrator password is RAUTHY_ADMIN_PASSWORD in $target/.env."
