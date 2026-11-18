#!/usr/bin/env bash
# CI helper: install the pinned Playwright Chromium.
#
# Fast path: download only the browser binary and check that it launches. GitHub's Ubuntu runners
# already carry Chromium's shared libraries; `--with-deps` only added fonts and a Mesa upgrade, and
# the Ubuntu mirror downloads of those packages crawl or stall (seen on main after PRs #322-#327),
# which failed whole browser jobs. apt is used only if the browser cannot start without it.
set -euo pipefail
cd "$(dirname "$0")"

pnpm install --frozen-lockfile

can_launch() {
  node --input-type=module -e "
    import { chromium } from '@playwright/test';
    try {
      const browser = await chromium.launch();
      const page = await browser.newPage();
      await page.setContent('<p>ok</p>');
      await browser.close();
    } catch (error) {
      console.error(String(error.message).split('\n')[0]);
      process.exit(1);
    }
  "
}

# The browser download is plain HTTPS from Playwright's CDN (no apt, no dpkg lock), so a deadline
# and a retry are safe.
for attempt in 1 2 3; do
  echo "Chromium download attempt ${attempt}" >&2
  if timeout --kill-after=10 120 pnpm exec playwright install chromium; then
    if can_launch; then
      echo "Chromium launches without system packages" >&2
      exit 0
    fi
    echo "Chromium does not launch without system packages; falling back to --with-deps" >&2
    break
  fi
  echo "Chromium download attempt ${attempt} failed or timed out" >&2
  sleep $((attempt * 5))
done

# Fallback: install Chromium's system packages with apt. Bound each connection and each attempt.
sudo tee /etc/apt/apt.conf.d/80-ci-network >/dev/null <<'APT'
Acquire::Retries "5";
Acquire::http::Timeout "20";
Acquire::https::Timeout "20";
APT

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
  echo "Chromium --with-deps attempt ${attempt}" >&2
  if timeout --kill-after=10 100 pnpm exec playwright install --with-deps chromium; then
    exit 0
  fi
  echo "Chromium --with-deps attempt ${attempt} failed or timed out" >&2
  stop_apt
  # An attempt killed during package unpacking can leave dpkg half-configured.
  sudo dpkg --configure -a || true
  sudo apt-get update -o Acquire::Retries=2 || true
  sleep $((attempt * 5))
done
exit 1
