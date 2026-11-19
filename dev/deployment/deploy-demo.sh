#!/usr/bin/env bash
# One-command deploy of the one-host SYNTHETIC demo on a fresh Ubuntu 24.04 VPS (Infomaniak runbook,
# docs/deployment/infomaniak-demo.md). Put the settings in /etc/openclub/demo.yaml (copy demo.example.yaml; or set
# OPENCLUB_DEMO_CONFIG), then run as root from the repository checkout:
#
#   bash dev/deployment/deploy-demo.sh --synthetic-only
#
# It installs Docker and the host firewall, checks DNS, generates the secrets once, starts Rauthy, stores the
# client secret Rauthy generated, starts everything and runs the read-only HTTPS probe. Rerunning it is safe:
# existing secrets are reused, and it updates the running stack to the checked-out code.
set -euo pipefail

[[ $# -eq 1 && "$1" == --synthetic-only ]] ||
  { echo "Usage: bash dev/deployment/deploy-demo.sh --synthetic-only   (settings: OPENCLUB_DEMO_CONFIG or /etc/openclub/demo.yaml)" >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
step() { printf '\n==> %s\n' "$*"; }

[[ "$(id -u)" == 0 ]] || { echo "Run as root on the demo VPS." >&2; exit 1; }
# shellcheck disable=SC1091
. /etc/os-release
[[ "${ID:-}" == ubuntu ]] || { echo "This script supports Ubuntu (24.04 LTS recommended); found ${ID:-unknown}." >&2; exit 1; }

step "Base packages and automatic security updates"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q ca-certificates curl git jq openssl python3-yaml ufw unattended-upgrades
systemctl enable --now unattended-upgrades

step "Settings"
python3 "$here/demo-config.py" check
cfg() { python3 "$here/demo-config.py" get "$1"; }
club_host="$(cfg clubHost)" id_host="$(cfg idHost)" admin_email="$(cfg adminEmail)" env_dir="$(cfg envDirectory)"

if ! docker compose version >/dev/null 2>&1; then
  step "Docker Engine and Compose plugin (official Docker repository)"
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi

step "Host firewall: SSH, HTTP and HTTPS only"
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null
echo "Also allow only TCP 22, 80 and 443 in the Infomaniak Manager firewall for this VPS."

step "DNS: both names must point to this server before certificates are requested"
public_ip="$(curl -4 -fsS --max-time 10 https://api.ipify.org || true)"
for name in "$club_host" "$id_host"; do
  resolved="$(getent ahostsv4 "$name" | awk 'NR==1 {print $1}')"
  if [[ -z "$resolved" ]]; then
    echo "$name does not resolve. Create its A record first." >&2; exit 1
  fi
  if [[ -n "$public_ip" && "$resolved" != "$public_ip" && "${OPENCLUB_SKIP_DNS_CHECK:-}" != 1 ]]; then
    echo "$name resolves to $resolved, but this server is $public_ip. Fix DNS (or set OPENCLUB_SKIP_DNS_CHECK=1 if you are sure)." >&2
    exit 1
  fi
  echo "$name -> $resolved"
done

if [[ -e "$env_dir" ]]; then
  step "Reusing the existing secrets in $env_dir"
  for pair in "OPENCLUB_DEMO_HOST=$club_host" "OPENCLUB_ID_HOST=$id_host"; do
    grep -qxF "$pair" "$env_dir/.env" ||
      { echo "$env_dir was generated for other hostnames than the config ($pair expected). Changing hostnames needs a fresh start; see the runbook." >&2; exit 1; }
  done
else
  step "Generating the secrets in $env_dir"
  bash "$here/generate-demo-env.sh" --synthetic-only
fi
demo() { bash "$here/demo-compose.sh" "$@"; }
demo config --quiet

step "Starting Rauthy"
demo up -d rauthy
if grep -q '^OPENCLUB_OIDC_CLIENT_SECRET=pending-' "$env_dir/.env"; then
  bash "$here/fetch-demo-client-secret.sh" --synthetic-only
fi

step "Building and starting the whole demo"
# The first start can fail while Caddy is still obtaining the identity certificate; retry once.
if ! demo up -d --build --wait; then
  echo "First start did not become healthy (often the certificate is still being issued); retrying in 60 s."
  sleep 60
  demo up -d --wait
fi

step "Read-only HTTPS and OIDC probe"
probe_ok=false
for _ in {1..10}; do
  if OPENCLUB_DEMO_ORIGIN="https://$club_host" OPENCLUB_OIDC_ISSUER="https://$id_host/auth/v1/" \
     bash "$root/dev/verify-synthetic-https-demo.sh" --synthetic-only; then
    probe_ok=true; break
  fi
  sleep 15
done
[[ "$probe_ok" == true ]] || { echo "The HTTPS probe did not pass; see docs/deployment/infomaniak-demo.md (Troubleshooting)." >&2; exit 1; }

cat <<NEXT

Demo is running: https://$club_host (identity provider https://$id_host).
Next:
  1. Sign in to https://$id_host/auth/v1/admin as $admin_email (password: RAUTHY_ADMIN_PASSWORD in $env_dir/.env).
  2. Sign in to https://$club_host once, open https://$club_host/api/v1/auth/me and copy accountId.
  3. bash dev/deployment/bootstrap-demo-club.sh --synthetic-only <accountId>
  4. Optional fictional data: bash dev/deployment/seed-demo-data.sh --synthetic-only
NEXT
if [[ -n "$(cfg emailSender)" ]] && ! grep -q '^RAUTHY_SMTP_HOST=' "$env_dir/.env"; then
  echo "Email is set in the config but not configured yet: pipe the mailbox password into"
  echo "  bash dev/deployment/configure-demo-email.sh --synthetic-only   and run this script again."
fi
