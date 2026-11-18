#!/usr/bin/env bash
# CI helper: install the pinned Playwright Chromium and its Ubuntu packages.
# The Ubuntu mirror occasionally stalls mid-download (seen on main after PRs #322-#324),
# so bound each apt connection and retry instead of waiting out the whole job.
set -euo pipefail
cd "$(dirname "$0")"

sudo tee /etc/apt/apt.conf.d/80-ci-network >/dev/null <<'APT'
Acquire::Retries "5";
Acquire::http::Timeout "20";
Acquire::https::Timeout "20";
APT

pnpm install --frozen-lockfile
for attempt in 1 2 3; do
  if pnpm exec playwright install --with-deps chromium; then
    exit 0
  fi
  echo "Chromium install attempt ${attempt} failed" >&2
  sleep $((attempt * 5))
done
exit 1
