import { defineConfig } from '@playwright/test';

// Screenshots for the user guides (docs/guides/images), taken by docs-screenshots.spec.js against a disposable
// stack with the fictional demo club. The workflow always rewrites every picture (`--update-snapshots=all`) and
// decides by the changed source files whether they replace the ones in the guides.
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
    // Date and time fields are drawn by the browser in its own language, not the page's: Swiss formats (01.12.2026,
    // 09:15) like a Swiss member's phone, instead of the runner's US default.
    launchOptions: { args: ['--lang=de-CH'] },
    timezoneId: 'Europe/Zurich',
    colorScheme: 'light',
    reducedMotion: 'reduce',
  },
});
