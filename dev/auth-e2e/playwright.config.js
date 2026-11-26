import { defineConfig } from '@playwright/test';

// Swiss time on both sides: the browser and the test's own date arithmetic use the club's time zone, so a run
// around midnight in Zurich (23:00–01:00 UTC) cannot see two different "todays".
process.env.TZ = 'Europe/Zurich';

export default defineConfig({
  testDir: '.',
  testMatch: ['auth.spec.js', 'club-landing.spec.js', 'rotation.spec.js'],
  workers: 1,
  retries: 0,
  timeout: 60000,
  reporter: 'line',
  use: { baseURL: 'http://localhost:5173', trace: 'off', screenshot: 'off', video: 'off', timezoneId: 'Europe/Zurich' },
});
