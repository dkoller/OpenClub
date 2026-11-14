import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: ['auth.spec.js', 'rotation.spec.js'],
  workers: 1,
  retries: 0,
  timeout: 60000,
  reporter: 'line',
  use: { baseURL: 'http://localhost:5173', trace: 'off', screenshot: 'off', video: 'off' },
});
