import { defineConfig } from '@playwright/test';

// Screenshots for the user guides (docs/guides/images), taken by docs-screenshots.spec.js against a disposable
// stack with the fictional demo club. `--update-snapshots=changed` rewrites only pictures that differ by more than
// the threshold below, so a date that moved by a day does not produce a new picture, while a changed screen does.
export default defineConfig({
  testDir: '.',
  testMatch: ['docs-screenshots.spec.js'],
  workers: 1,
  retries: 0,
  timeout: 300000,
  reporter: 'line',
  snapshotPathTemplate: '{testDir}/../../docs/guides/images/{arg}{ext}',
  expect: {
    toHaveScreenshot: { maxDiffPixelRatio: 0.03, animations: 'disabled', caret: 'hide', scale: 'css' },
  },
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    locale: 'en-GB',
    timezoneId: 'Europe/Zurich',
    colorScheme: 'light',
    reducedMotion: 'reduce',
  },
});
