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
  const viewport = page.viewportSize();
  if (viewport.width > 600) {
    await expect(page).toHaveScreenshot(name + '.png', { fullPage: true });
    return;
  }
  // A phone picture shows the whole page as one tall screen, so the fixed tab bar stays at the bottom (a normal
  // full-page capture would leave it in the middle).
  const height = await page.evaluate(() => document.documentElement.scrollHeight);
  await page.setViewportSize({ width: viewport.width, height: Math.min(Math.max(height, viewport.height), 4000) });
  await page.waitForTimeout(200);
  await expect(page).toHaveScreenshot(name + '.png');
  await page.setViewportSize(viewport);
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

  // Extra fictional data so every page has something to show (all relative to today, like the seed).
  sql(`
    UPDATE clubs SET join_requests_open = TRUE, helper_quota = 2, season_start_month = 8;
    INSERT INTO venues (id, name) VALUES (gen_random_uuid(), 'Musterfeld pitch 1'), (gen_random_uuid(), 'Sports hall Musterfeld');
    UPDATE team_events SET venue_id = (SELECT id FROM venues WHERE name = 'Musterfeld pitch 1')
      WHERE team_id = (SELECT id FROM teams WHERE name = 'Juniors U12');
    INSERT INTO announcements (id, title, body, show_until) VALUES (gen_random_uuid(), 'Club assembly on 15 January',
      'All members are invited to the annual assembly in the clubhouse at 19:30.', current_date + 30);
    INSERT INTO fee_periods (id, name, due_date, amount_cents) VALUES (gen_random_uuid(), 'Season 2026/27', current_date + 30, 18000);
    INSERT INTO member_fees (period_id, member_id, amount_cents, paid_on)
      SELECT p.id, m.id, 18000, CASE WHEN m.last_name IN ('Keller', 'Schmid', 'Weber') THEN current_date - 3 END
      FROM fee_periods p CROSS JOIN members m WHERE m.archived_at IS NULL;
    UPDATE members SET js_person_number = (100000 + (abs(hashtext(first_name || last_name)) % 900000))::text
      WHERE id IN (SELECT a.member_id FROM member_team_assignments a JOIN teams t ON t.id = a.team_id WHERE t.name = 'Juniors U12');
    INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, location)
      SELECT gen_random_uuid(), id, 'TRAINING', 'Training', date_trunc('day', now()) - interval '2 days' + interval '16 hours 30 minutes',
             date_trunc('day', now()) - interval '2 days' + interval '18 hours', 'Musterfeld sports ground'
      FROM teams WHERE name = 'Juniors U12';
    INSERT INTO event_attendance (event_id, member_id, response)
      SELECT e.id, a.member_id, 'YES' FROM team_events e JOIN member_team_assignments a ON a.team_id = e.team_id
      WHERE e.starts_at < now() AND e.team_id = (SELECT id FROM teams WHERE name = 'Juniors U12');
    INSERT INTO event_presence (event_id, member_id, role)
      SELECT e.id, a.member_id, 'PARTICIPANT' FROM team_events e JOIN member_team_assignments a ON a.team_id = e.team_id
      JOIN members m ON m.id = a.member_id
      WHERE e.starts_at < now() AND e.team_id = (SELECT id FROM teams WHERE name = 'Juniors U12') AND m.first_name <> 'Luca';
  `);

  // Signed out: the start page with the join link.
  const visitor = await browser.newContext({ viewport: phone, hasTouch: true, isMobile: true });
  const visitorPage = await visitor.newPage();
  await visitorPage.goto('/');
  await visitorPage.locator('#language').selectOption('en');
  await shoot(visitorPage, '#/', 'public-signed-out');

  // A parent: Sandra Gerber, guardian of Lea (U12) and Nico (U17). Signed in but not yet linked, she sees the
  // invitation page.
  const parent = await browser.newContext({ viewport: phone, hasTouch: true, isMobile: true });
  const parentPage = await parent.newPage();
  await signIn(parentPage, 'openclub-ci-invitee');
  await expect(parentPage.getByText('This account is not yet a club member.', { exact: true })).toBeVisible();
  await shoot(parentPage, '#/', 'public-not-member');
  const parentAccount = (await (await parent.request.get('/api/v1/auth/me')).json()).accountId;
  if (!/^[0-9a-f-]{36}$/i.test(parentAccount)) throw new Error('Unexpected account ID');
  // The seed has Sandra Gerber without a login; she gets the second synthetic login, as the walkthrough does.
  sql(`UPDATE members SET account_id = '${parentAccount}' WHERE first_name = 'Sandra' AND last_name = 'Gerber'`);
  sql(`INSERT INTO member_guardianships (minor_member_id, guardian_member_id)
       SELECT minor.id, guardian.id FROM members minor, members guardian
       WHERE guardian.last_name = 'Gerber' AND minor.last_name = 'Brunner' AND minor.first_name IN ('Lea', 'Nico')`);

  const u12 = sql("SELECT id FROM teams WHERE name = 'Juniors U12'");
  const match = sql(`SELECT id FROM team_events WHERE team_id = '${u12}' AND kind = 'MATCH' ORDER BY starts_at LIMIT 1`);
  const past = sql(`SELECT id FROM team_events WHERE team_id = '${u12}' AND starts_at < now() ORDER BY starts_at DESC LIMIT 1`);
  for (const id of [u12, match, past]) if (!/^[0-9a-f-]{36}$/.test(id)) throw new Error('Seed data missing');
  sql(`INSERT INTO event_squads (event_id, meet_at, meeting_point)
       SELECT id, starts_at - interval '45 minutes', 'Clubhouse car park' FROM team_events WHERE id = '${match}';
       INSERT INTO squad_members (event_id, member_id)
       SELECT '${match}', a.member_id FROM member_team_assignments a JOIN members m ON m.id = a.member_id
       WHERE a.team_id = '${u12}' AND m.first_name IN ('Lea', 'Noah', 'Mia')`);

  // Members and parents, on a phone: every page of Start, Schedule and Family.
  await parentPage.reload();
  await expect(parentPage.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
  await shoot(parentPage, '#/', 'member-start');
  await shoot(parentPage, '#/schedule', 'member-schedule');
  await shoot(parentPage, '#/schedule/' + match, 'member-event');
  await shoot(parentPage, '#/schedule/venues', 'member-venues');
  await shoot(parentPage, '#/family', 'member-family');
  for (const section of ['profile', 'children', 'absences', 'fees', 'calendar', 'notifications']) {
    await shoot(parentPage, '#/family/' + section, 'member-family-' + section);
  }

  // Coaches, on a phone at the field: every page of Team.
  const coach = await browser.newContext({ viewport: phone, hasTouch: true, isMobile: true, storageState: await admin.storageState() });
  const coachPage = await coach.newPage();
  await coachPage.goto('/');
  await expect(coachPage.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
  await shoot(coachPage, '#/team', 'coach-teams');
  await shoot(coachPage, `#/team/${u12}`, 'coach-team');
  for (const section of ['schedule', 'members', 'statistics']) await shoot(coachPage, `#/team/${u12}/${section}`, 'coach-team-' + section);
  await shoot(coachPage, `#/team/${u12}/event/${match}`, 'coach-event');
  await shoot(coachPage, `#/team/${u12}/event/${past}`, 'coach-event-past');

  // The treasurer and administrators, on a laptop: every page of Administration.
  await adminPage.reload();
  await expect(adminPage.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
  await shoot(adminPage, '#/admin', 'admin-overview');
  await shoot(adminPage, '#/admin/members', 'admin-members');
  for (const part of ['invitations', 'guardians', 'roles', 'transfer', 'archived', 'public']) {
    await shoot(adminPage, '#/admin/members/' + part, 'admin-members-' + part);
  }
  await shoot(adminPage, '#/admin/join-requests', 'admin-join-requests');
  await shoot(adminPage, '#/admin/fees', 'treasurer-fees');
  await shoot(adminPage, '#/admin/venues', 'admin-venues');
  await shoot(adminPage, '#/admin/permissions', 'admin-permissions');
  await shoot(adminPage, '#/admin/settings', 'admin-settings');

  await Promise.all([admin.close(), parent.close(), coach.close(), visitor.close()]);
});
