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

# `timeout` does not reliably reach apt-get (sudo runs it in its own session), and a surviving
# apt-get keeps the dpkg lock, so every later attempt fails at once with "Could not get lock"
# (seen on PR #326). Stop apt explicitly and wait for the lock holders to exit before retrying.
stop_apt() {
  sudo pkill -TERM -x apt-get || true
  sleep 2
  sudo pkill -KILL -x apt-get || true
  # The bracket keeps pkill -f from matching its own (and sudo's) command line.
  sudo pkill -KILL -f '/usr/lib/apt/method[s]/' || true
  # A dpkg that is already unpacking is left to finish rather than killed.
  for _ in $(seq 1 30); do
    pgrep -x 'apt-get|dpkg' >/dev/null || return 0
    sleep 2
  done
  echo "apt-get or dpkg still running after cleanup" >&2
}

# A normal install takes about 35 s; each attempt gets a hard deadline.
for attempt in 1 2 3; do
  echo "Chromium install attempt ${attempt}" >&2
  if timeout --kill-after=10 100 pnpm exec playwright install --with-deps chromium; then
    exit 0
  fi
  echo "Chromium install attempt ${attempt} failed or timed out" >&2
  stop_apt
  # An attempt killed during package unpacking can leave dpkg half-configured.
  sudo dpkg --configure -a || true
  sudo apt-get update -o Acquire::Retries=2 || true
  sleep $((attempt * 5))
done
exit 1
