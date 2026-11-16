#!/usr/bin/env bash
# Read-only M1 synthetic deployment acceptance probe; never provisions or mutates data.
set -euo pipefail

if [[ "${1:-}" != "--synthetic-only" || $# -ne 1 ]]; then
  echo "Usage: OPENCLUB_DEMO_ORIGIN=https://club.example OPENCLUB_OIDC_ISSUER=https://idp.example/realms/openclub bash dev/verify-synthetic-https-demo.sh --synthetic-only" >&2
  exit 2
fi
origin="${OPENCLUB_DEMO_ORIGIN:-}"
issuer="${OPENCLUB_OIDC_ISSUER:-}"
# Exact origins: no userinfo, query, fragment, URL path or insecure schemes.
if [[ ! "$origin" =~ ^https://[a-zA-Z0-9.-]+(:[0-9]+)?$ ]] ||
   [[ ! "$issuer" =~ ^https://[a-zA-Z0-9.-]+(:[0-9]+)?/[-a-zA-Z0-9._/]+$ ]]; then
  echo "Require a bare HTTPS demo origin and an HTTPS OIDC issuer URL; never use --insecure." >&2
  exit 2
fi
for command in curl jq; do
  command -v "$command" >/dev/null || { echo "$command is required" >&2; exit 1; }
done
umask 077
work="$(mktemp -d "${TMPDIR:-/tmp}/openclub-demo.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT

# Never follow redirects, disable proxy-protocol downgrades and verify certificate chains.
curl_https() {
  curl --proto '=https' --tlsv1.2 --silent --show-error --max-time 20 "$@"
}
check_header() {
  local header="$1" expected="$2" file="$3"
  if ! tr -d '\r' < "$file" | grep -Fxi -- "$header: $expected" >/dev/null; then
    echo "Missing $header on verified HTTPS response" >&2
    exit 1
  fi
}

status="$(curl_https -D "$work/web.headers" -o "$work/index.html" -w '%{http_code}' "$origin/")"
[[ "$status" == 200 ]] || { echo "Web landing did not return 200" >&2; exit 1; }
check_header "X-Content-Type-Options" "nosniff" "$work/web.headers"
check_header "X-Frame-Options" "DENY" "$work/web.headers"
check_header "Referrer-Policy" "no-referrer" "$work/web.headers"
check_header "Permissions-Policy" "camera=(), microphone=(), geolocation=()" "$work/web.headers"
check_header "Content-Security-Policy" "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; style-src 'self'; script-src 'self'" "$work/web.headers"
# Enforce a modest HTTPS-only retention period on the public demo hostname.
hsts="$(tr -d '\r' < "$work/web.headers" | grep -im1 '^Strict-Transport-Security:' || true)"
hsts_age="$(printf '%s\n' "$hsts" | grep -Eio 'max-age=[0-9]+' | head -n1 | cut -d= -f2 || true)"
if [[ ! "$hsts_age" =~ ^[0-9]{1,9}$ ]] || (( 10#$hsts_age < 86400 )); then
  echo "HTTPS demo must advertise HSTS max-age of at least one day" >&2
  exit 1
fi

status="$(curl_https -D "$work/ready.headers" -o "$work/ready.json" -w '%{http_code}' "$origin/api/ready")"
[[ "$status" == 200 ]] && jq -e '.status == "ready"' "$work/ready.json" >/dev/null ||
  { echo "API/database readiness failed" >&2; exit 1; }
check_header "Cache-Control" "no-store" "$work/ready.headers"
check_header "X-Content-Type-Options" "nosniff" "$work/ready.headers"

# No cookie is sent: authenticated club data must be denied.
status="$(curl_https -D "$work/club.headers" -o /dev/null -w '%{http_code}' "$origin/api/v1/club")"
[[ "$status" == 401 ]] || { echo "Anonymous club metadata was not denied with 401" >&2; exit 1; }
check_header "Cache-Control" "no-store" "$work/club.headers"
check_header "X-Frame-Options" "DENY" "$work/club.headers"
check_header "Content-Security-Policy" "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; style-src 'self'; script-src 'self'" "$work/club.headers"

status="$(curl_https -D "$work/oidc.headers" -o "$work/oidc.json" -w '%{http_code}' "$issuer/.well-known/openid-configuration")"
[[ "$status" == 200 ]] || { echo "OIDC discovery did not return 200" >&2; exit 1; }
jq -e --arg issuer "$issuer" '
  .issuer == $issuer and
  (.authorization_endpoint | type == "string" and startswith("https://")) and
  (.token_endpoint | type == "string" and startswith("https://")) and
  (.jwks_uri | type == "string" and startswith("https://"))
' "$work/oidc.json" >/dev/null || { echo "OIDC discovery issuer/endpoints are invalid" >&2; exit 1; }
jwks="$(jq -er '.jwks_uri' "$work/oidc.json")"
# The app pins its issuer and discovery; verify that the advertised JWKS is reachable over TLS.
curl_https --fail -o "$work/jwks.json" "$jwks"
jq -e '.keys | type == "array" and length > 0' "$work/jwks.json" >/dev/null ||
  { echo "OIDC JWKS is invalid" >&2; exit 1; }

echo "HTTPS certificate validation, browser ingress, API readiness, anonymous denial and OIDC discovery/JWKS passed."
echo "Not tested: actual sign-in, admin capabilities, DE/EN/FR/IT browser workflows, persistence, recovery or M2 privacy release."
