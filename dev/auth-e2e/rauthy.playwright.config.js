import { defineConfig } from '@playwright/test';

// Swiss time on both sides: the browser and the test's own date arithmetic use the club's time zone, so a run
// around midnight in Zurich (23:00–01:00 UTC) cannot see two different "todays".
process.env.TZ = 'Europe/Zurich';

export default defineConfig({
  testDir: '.',
  testMatch: ['rauthy-login.spec.js', 'rauthy-otp.spec.js', 'rauthy-passkey.spec.js', 'rauthy-invitation.spec.js'],
  workers: 1,
  retries: 0,
  timeout: 90000,
  reporter: 'line',
  use: {
    baseURL: 'https://localhost:5443',
    // Only the disposable loopback Caddy and Rauthy instances use local CAs.
    // The real Kotlin OIDC client still verifies TLS using a pinned CI truststore.
    ignoreHTTPSErrors: true,
    timezoneId: 'Europe/Zurich',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
});
