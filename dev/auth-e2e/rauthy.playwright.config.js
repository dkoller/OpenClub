import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: ['rauthy-login.spec.js', 'rauthy-passkey.spec.js'],
  workers: 1,
  retries: 0,
  timeout: 90000,
  reporter: 'line',
  use: {
    baseURL: 'https://localhost:5443',
    // Only the disposable loopback Caddy and Rauthy instances use local CAs.
    // The real Kotlin OIDC client still verifies TLS using a pinned CI truststore.
    ignoreHTTPSErrors: true,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
});
