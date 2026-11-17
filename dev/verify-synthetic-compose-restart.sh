#!/usr/bin/env bash
# CI-only, disposable database/application restart: never run on a real club.
set -euo pipefail

if [[ "${1:-}" != "--synthetic-only" || $# -ne 1 ||
      "${GITHUB_ACTIONS:-}" != true || "${OPENCLUB_RESTART_CI_CONFIRM:-}" != disposable-only ||
      "${COMPOSE_PROJECT_NAME:-}" != openclub ]]; then
  echo "Only the guarded disposable GitHub Compose project may run this restart test." >&2
  exit 2
fi
command -v docker >/dev/null || exit 1
[[ -f compose.yaml ]] || { echo "Run from the repository root." >&2; exit 2; }

compose=(docker compose -f compose.yaml)
for service in postgres api web; do
  container="$("${compose[@]}" ps -q "$service")"
  [[ "$container" =~ ^[[:xdigit:]]{12,64}$ ]] || exit 1
  project="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$container")"
  actual_service="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$container")"
  [[ "$project" == openclub && "$actual_service" == "$service" ]] || exit 1
done

# No existing club may be touched, even on a mislabeled disposable runner.
sql() {
  "${compose[@]}" exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -U openclub -d openclub -c "$1"
}
[[ "$(sql 'SELECT COUNT(*) FROM clubs')" == 0 ]] ||
  { echo "Refusing to modify an initialized club." >&2; exit 2; }
# The fixture uses only synthetic club metadata and the *actual Flyway schema*.
fixture_slug=m1-disposable-restart-probe
fixture_id="$(sql "INSERT INTO clubs (id, name, slug)
  VALUES (gen_random_uuid(), 'Synthetic CI Restart', '$fixture_slug') RETURNING id")"
[[ "$fixture_id" =~ ^[[:xdigit:]-]{36}$ ]] ||
  { echo "Failed to insert a synthetic club fixture." >&2; exit 1; }

cleanup() {
  sql "DELETE FROM clubs WHERE id = '$fixture_id' AND slug = '$fixture_slug'" >/dev/null || true
}
trap cleanup EXIT

# Docker Compose restart does not remove or replace named PostgreSQL volumes.
"${compose[@]}" restart postgres api web >/dev/null
ready=false
for attempt in {1..45}; do
  if curl --fail --silent --max-time 5 http://localhost:5173/api/ready |
     grep -Fxq '{"status":"ready"}'; then
    ready=true
    break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo "API/database did not recover after restart." >&2; exit 1; }
[[ "$(sql "SELECT id FROM clubs WHERE slug = '$fixture_slug'")" == "$fixture_id" ]] ||
  { echo "Synthetic club record did not persist across restart." >&2; exit 1; }
[[ "$(curl --fail --silent --max-time 5 http://localhost:5173/api/health)" == '{"status":"ok"}' ]] ||
  { echo "Application liveness did not recover." >&2; exit 1; }
echo "Verified real Flyway club record and API readiness survived PostgreSQL, API and web restarts."
echo "Not verified: signed-in browser journey, real member data, provider restart or external hosting."
