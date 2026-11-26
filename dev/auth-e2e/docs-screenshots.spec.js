import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';

// Screenshots for the user guides (docs/guides/images). Runs only against the disposable CI stack: it bootstraps
// the fictional demo club (FC Musterfeld), fills it with dev/deployment/seed-demo-data.sql and photographs the
// pages the guides describe, in English. Sign-in pages are never photographed. See docs/contributing/documentation.md.

const root = fileURLToPath(new URL('../../', import.meta.url));
const phone = { width: 390, height: 844 };
const laptop = { width: 1280, height: 800 };

function sql(statement, input) {
  if (process.env.OPENCLUB_E2E_DISPOSABLE !== 'true') throw new Error('Screenshots need the disposable synthetic stack');
  const args = ['compose', '-f', 'compose.yaml', '-f', 'compose.auth.dev.yaml', 'exec', '-T', 'postgres',
    'psql', '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-U', 'openclub', '-d', 'openclub'];
  return String(execFileSync('docker', statement ? [...args, '-c', statement] : [...args, '--single-transaction'],
    { cwd: root, stdio: 'pipe', input })).trim();
}

async function signIn(page, username) {
  await page.goto('/');
  await page.locator('#language').selectOption('en');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(process.env.OPENCLUB_E2E_USER_PASSWORD);
  await Promise.all([page.waitForURL(/^http:\/\/localhost:5173\//), page.locator('#kc-login').click()]);
}

/** Opens a page by its address and waits until every request it started has finished. */
async function open(page, hash) {
  let pending = 0;
  const started = () => { pending += 1; };
  const ended = () => { pending -= 1; };
  page.on('request', started);
  page.on('requestfinished', ended);
  page.on('requestfailed', ended);
  await page.evaluate(target => { window.location.hash = target; }, hash);
  await page.waitForTimeout(300);
  // Quiet for 600 ms, or give up waiting after 8 s (a long-lived request must not hold the run).
  for (let quiet = 0, rounds = 0; quiet < 4 && rounds < 53; rounds += 1) {
    await page.waitForTimeout(150);
    quiet = pending <= 0 ? quiet + 1 : 0;
  }
  page.off('request', started);
  page.off('requestfinished', ended);
  page.off('requestfailed', ended);
  await expect(page.getByText('Loading…')).toHaveCount(0);
}

async function shoot(page, hash, name) {
  await open(page, hash);
  await expect(page).toHaveScreenshot(name + '.png', { fullPage: true });
}

test('screenshots of the pages the guides describe', async ({ browser }) => {
  test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true', 'Needs the disposable synthetic stack');
  if (!process.env.OPENCLUB_E2E_USER_PASSWORD) throw new Error('Disposable synthetic login password required');

  // The administrator: the first club member, created by the operator command.
  const admin = await browser.newContext({ viewport: laptop });
  const adminPage = await admin.newPage();
  await signIn(adminPage, 'openclub-ci-user');
  const { accountId } = await (await admin.request.get('/api/v1/auth/me')).json();
  execFileSync('./gradlew', ['bootstrapFirstClub', '--no-daemon', '--console=plain'], {
    cwd: fileURLToPath(new URL('../../apps/api/', import.meta.url)), timeout: 120000, stdio: 'pipe',
    env: {
      ...process.env,
      OPENCLUB_BOOTSTRAP_CONFIRM: 'create-first-club',
      OPENCLUB_BOOTSTRAP_ACCOUNT_ID: accountId,
      OPENCLUB_BOOTSTRAP_CLUB_NAME: 'FC Musterfeld',
      OPENCLUB_BOOTSTRAP_CLUB_SLUG: 'fc-musterfeld',
      OPENCLUB_BOOTSTRAP_FIRST_NAME: 'Ruth',
      OPENCLUB_BOOTSTRAP_LAST_NAME: 'Amstutz',
      OPENCLUB_BOOTSTRAP_BIRTH_DATE: '1979-04-12',
      OPENCLUB_BOOTSTRAP_EMAIL: 'ruth.amstutz@example.invalid',
      OPENCLUB_BOOTSTRAP_PHONE: '+41000000001',
    },
  });
  sql(null, readFileSync(new URL('../deployment/seed-demo-data.sql', import.meta.url)));

  // A parent: Sandra Gerber, guardian of Lea (U12) and Nico (U17).
  const parent = await browser.newContext({ viewport: phone, hasTouch: true, isMobile: true });
  const parentPage = await parent.newPage();
  await signIn(parentPage, 'openclub-ci-invitee');
  const parentAccount = (await (await parent.request.get('/api/v1/auth/me')).json()).accountId;
  if (!/^[0-9a-f-]{36}$/i.test(parentAccount)) throw new Error('Unexpected account ID');
  // The seed has Sandra Gerber without a login; she gets the second synthetic login, as the walkthrough does.
  sql(`UPDATE members SET account_id = '${parentAccount}' WHERE first_name = 'Sandra' AND last_name = 'Gerber'`);
  sql(`INSERT INTO member_guardianships (minor_member_id, guardian_member_id)
       SELECT minor.id, guardian.id FROM members minor, members guardian
       WHERE guardian.last_name = 'Gerber' AND minor.last_name = 'Brunner' AND minor.first_name IN ('Lea', 'Nico')`);

  const u12 = sql("SELECT id FROM teams WHERE name = 'Juniors U12'");
  const match = sql(`SELECT id FROM team_events WHERE team_id = '${u12}' AND kind = 'MATCH' ORDER BY starts_at LIMIT 1`);
  if (!/^[0-9a-f-]{36}$/.test(u12) || !/^[0-9a-f-]{36}$/.test(match)) throw new Error('Seed data missing');

  // Members and parents, on a phone.
  await parentPage.reload();
  await expect(parentPage.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
  await shoot(parentPage, '#/', 'member-start');
  await shoot(parentPage, '#/schedule', 'member-schedule');
  await shoot(parentPage, '#/schedule/' + match, 'member-event');
  await shoot(parentPage, '#/family', 'member-family');
  await shoot(parentPage, '#/family/absences', 'member-absences');

  // Coaches, on a phone at the field.
  const coach = await browser.newContext({ viewport: phone, hasTouch: true, isMobile: true, storageState: await admin.storageState() });
  const coachPage = await coach.newPage();
  await coachPage.goto('/');
  await expect(coachPage.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
  await shoot(coachPage, `#/team/${u12}`, 'coach-team');
  await shoot(coachPage, `#/team/${u12}/event/${match}`, 'coach-event');

  // The treasurer and administrators, on a laptop.
  await adminPage.reload();
  await expect(adminPage.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
  await shoot(adminPage, '#/admin/fees', 'treasurer-fees');
  await shoot(adminPage, '#/admin', 'admin-overview');
  await shoot(adminPage, '#/admin/members', 'admin-members');
  await shoot(adminPage, '#/admin/join-requests', 'admin-join-requests');
  await shoot(adminPage, '#/admin/permissions', 'admin-permissions');

  await Promise.all([admin.close(), parent.close(), coach.close()]);
});
