// Shared set-up for the club browser tests (club-*.spec.js). One test signs in and bootstraps the club
// (club-setup.spec.js) and saves the signed-in browser state; every other club test starts from that state and
// creates the synthetic data it needs, so each one runs on its own and a failure stops only that test.
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test as base, expect } from '@playwright/test';

export { expect };

// Outside the repository and never uploaded: the file holds the synthetic administrator's session cookie.
const stateDirectory = process.env.RUNNER_TEMP || tmpdir();
export const adminState = join(stateDirectory, 'openclub-e2e-admin.json');
export const adminAccountFile = join(stateDirectory, 'openclub-e2e-admin-account.txt');

/** Club tests start signed in as the synthetic administrator, on Start. */
export const test = base.extend({
  storageState: adminState,
  page: async ({ page }, use) => {
    await page.goto('/');
    await expect(page.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
    await use(page);
  },
});

export function adminAccountId() {
  return readFileSync(adminAccountFile, 'utf8').trim();
}

/** The administrator's member row, as a SQL sub-select. */
export function adminMemberSql() {
  const accountId = adminAccountId();
  if (!/^[0-9a-f-]{36}$/i.test(accountId)) throw new Error('Unexpected synthetic account ID');
  return "(SELECT id FROM members WHERE account_id = '" + accountId + "')";
}

/** Runs SQL against the disposable CI database (never anything else). */
export function fixtureSql(sql) {
  if (process.env.OPENCLUB_E2E_DISPOSABLE !== 'true') throw new Error('This fixture requires disposable synthetic data');
  return String(execFileSync('docker', [
    'compose', '-f', 'compose.yaml', '-f', 'compose.auth.dev.yaml',
    'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-t', '-A',
    '-U', 'openclub', '-d', 'openclub', '-c', sql,
  ], { cwd: fileURLToPath(new URL('../../', import.meta.url)), stdio: 'pipe' })).trim();
}

/** The synthetic child "Mara Demo" (born 01.01.2015, player), created when no active one exists. Returns her ID. */
export function ensureMara() {
  const existing = fixtureSql("SELECT id FROM members WHERE first_name = 'Mara' AND last_name = 'Demo' " +
    'AND archived_at IS NULL ORDER BY created_at LIMIT 1');
  if (existing) return existing;
  return fixtureSql("INSERT INTO members (id, first_name, last_name, date_of_birth, gender, is_player) " +
    "VALUES (gen_random_uuid(), 'Mara', 'Demo', DATE '2015-01-01', 'Synthetic test value', TRUE) RETURNING id")
    .split('\n')[0];
}

/** A team by name, created when missing; with `members`, those member IDs are assigned to it. Returns its ID. */
export function ensureTeam(name, members = []) {
  if (!/^[A-Za-z0-9 ]+$/.test(name)) throw new Error('Unexpected synthetic team name');
  const id = fixtureSql("SELECT id FROM teams WHERE name = '" + name + "'") ||
    fixtureSql("INSERT INTO teams (id, name) VALUES (gen_random_uuid(), '" + name + "') RETURNING id").split('\n')[0];
  for (const member of members) {
    if (!/^[0-9a-f-]{36}$/i.test(member)) throw new Error('Unexpected synthetic member ID');
    fixtureSql("INSERT INTO member_team_assignments (team_id, member_id) VALUES ('" + id + "', '" + member + "') " +
      'ON CONFLICT DO NOTHING');
  }
  return id;
}

/** A member row opens by its name (details, then Edit and Archive); opens it if it is still closed. */
export async function openMember(row) {
  const toggle = row.locator('button.row-toggle');
  await toggle.waitFor();
  if (await toggle.getAttribute('aria-expanded') === 'false') await toggle.click();
}

/** Occasional forms sit behind a button (Reveal); opens one if it is still closed. */
export async function openForm(scope, name) {
  // The button may render a moment after the page; give it a short wait instead of checking once (a single check
  // skipped the button while the page loaded, and the next fill then waited out the whole test timeout).
  const button = scope.getByRole('button', { name, exact: true }).first();
  if (await button.waitFor({ timeout: 3000 }).then(() => true, () => false)) await button.click();
}

/**
 * Signed in, the language is chosen under Family → My profile (not in the header). Opens it, chooses, and returns to
 * the page the test was on.
 */
export async function chooseLanguage(page, code) {
  const back = await page.evaluate(() => window.location.hash);
  await page.evaluate(() => { window.location.hash = '#/family/profile'; });
  await page.locator('#language').selectOption(code);
  await expect(page.locator('html')).toHaveAttribute('lang', code);
  await page.evaluate(target => { window.location.hash = target; }, back || '#/');
}

// The app shows one page at a time (Start, Schedule, Family, Team, Administration); tests open a page by its
// address, which a reload keeps.
export async function show(page, hash) {
  await page.evaluate(target => { window.location.hash = target; }, hash);
}
