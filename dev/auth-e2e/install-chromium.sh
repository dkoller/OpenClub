#!/usr/bin/env bash
# CI helper: install the pinned Playwright Chromium and its Ubuntu packages.
# The Ubuntu mirror occasionally stalls mid-download (seen on main after PRs #322-#325) and
# apt then never exits and prints no error, so every attempt gets a hard deadline and is retried.
set -euo pipefail
cd "$(dirname "$0")"

sudo tee /etc/apt/apt.conf.d/80-ci-network >/dev/null <<'APT'
Acquire::Retries "5";
Acquire::http::Timeout "20";
Acquire::https::Timeout "20";
APT

pnpm install --frozen-lockfile

# A normal install takes about 35 s. The deadline signals the whole process group (pnpm, sudo, apt).
for attempt in 1 2 3; do
  echo "Chromium install attempt ${attempt}" >&2
  if timeout --kill-after=10 100 pnpm exec playwright install --with-deps chromium; then
    exit 0
  fi
  echo "Chromium install attempt ${attempt} failed or timed out" >&2
  # An attempt killed during package unpacking can leave dpkg half-configured.
  sudo dpkg --configure -a || true
  sudo apt-get update -o Acquire::Retries=2 || true
  sleep $((attempt * 5))
done
exit 1
