import { defineConfig } from '@playwright/test';

// Swiss time on both sides: the browser and the test's own date arithmetic use the club's time zone, so a run
// around midnight in Zurich (23:00–01:00 UTC) cannot see two different "todays".
process.env.TZ = 'Europe/Zurich';

// Three steps against one disposable Keycloak and database:
// 1. setup: login and denial before the club exists, then the operator bootstrap; it saves the signed-in state.
// 2. club: one test per area (club-a… to club-i…), each starting signed in and creating the data it needs, so a
//    failure stops only that test and `pnpm test -g "<title>"` re-runs it alone.
// 3. last (teardown of setup, runs even when club tests failed): sign-out, which ends the saved session, then the
//    tests that sign in on their own (invitation, signing-key rotation).
export default defineConfig({
  testDir: '.',
  workers: 1,
  retries: 0,
  timeout: 60000,
  reporter: 'line',
  use: { baseURL: 'http://localhost:5173', trace: 'off', screenshot: 'off', video: 'off', timezoneId: 'Europe/Zurich' },
  projects: [
    { name: 'setup', testMatch: ['auth.spec.js', 'club-setup.spec.js'], teardown: 'last' },
    { name: 'club', testMatch: /club-[a-y]-[a-z-]+\.spec\.js$/, dependencies: ['setup'] },
    { name: 'last', testMatch: ['club-z1-signout.spec.js', 'club-z2-invitation.spec.js', 'rotation.spec.js'] },
  ],
});
