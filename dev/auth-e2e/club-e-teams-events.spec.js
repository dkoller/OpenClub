import { ensureMara, ensureTeam, expect, fixtureSql, openForm, show, test } from './club-fixtures.js';
import { request as playwrightRequest } from '@playwright/test';
// Teams and their members, events with answers, shifts and rides, and the family pages.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('a team is created and a member assigned, with stale answers never winning', async ({ page }) => {
  ensureMara();
  await show(page, '#/team');
  const teams = page.getByRole('region', { name: 'Teams', exact: true });
  // A failed initial roster read must not leave a permanent loading message.
  let failedRoster = false;
  await page.route('**/api/v1/teams/**', async route => {
    if (route.request().method() === 'GET' && new URL(route.request().url()).pathname.endsWith('/members')) {
      failedRoster = true;
      await route.fulfill({ status: 503, body: 'Unavailable' });
    } else {
      await route.continue();
    }
  });
  await openForm(teams, 'New team');
  await teams.getByLabel('Team name').fill('Juniors');
  await teams.getByRole('button', { name: 'Add team' }).click();
  // Each team is one link to its own page (#/team/<id>), which links to its schedule, members and statistics.
  const juniorsRow = teams.getByRole('listitem').filter({ hasText: 'Juniors' });
  await expect(juniorsRow).toContainText('0 members');
  const juniorsPage = await juniorsRow.getByRole('link').getAttribute('href');
  expect(juniorsPage).toMatch(/^#\/team\/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i);
  await juniorsRow.getByRole('link').click();
  await expect(page.getByRole('heading', { level: 1, name: 'Juniors', exact: true })).toBeVisible();
  const teamParts = page.getByRole('navigation', { name: 'Team pages' });
  await teamParts.getByRole('link', { name: /^Members/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Members', exact: true })).toBeVisible();
  const roster = page.getByRole('region', { name: 'Team members', exact: true });
  await expect.poll(() => failedRoster).toBe(true);
  await expect(roster.getByRole('alert').filter({ hasText: 'Team assignment failed.' })).toBeVisible();
  await expect(roster.getByText('Loading team members…')).toHaveCount(0);
  await page.unroute('**/api/v1/teams/**');
  await roster.getByRole('button', { name: 'Try again' }).click();
  await expect(roster.getByText('No assignments.')).toBeVisible();
  await expect(roster.getByRole('alert').filter({ hasText: 'Team assignment failed.' })).toHaveCount(0);
  // A superseded team-candidate search must not replace newer choices.
  let notifySlowCandidate;
  const slowCandidateStarted = new Promise(resolve => { notifySlowCandidate = resolve; });
  await page.route('**/api/v1/members?**', async route => {
    if (new URL(route.request().url()).searchParams.get('q') !== 'Slow') {
      await route.continue();
      return;
    }
    notifySlowCandidate();
    await new Promise(resolve => setTimeout(resolve, 500));
    await route.continue().catch(() => {});
  });
  const canceledCandidate = page.waitForEvent('requestfailed',
    request => new URL(request.url()).searchParams.get('q') === 'Slow');
  await roster.getByLabel('Find member').fill('Slow');
  await roster.getByRole('button', { name: 'Search' }).click();
  await slowCandidateStarted;
  await roster.getByLabel('Find member').fill('Mara');
  await roster.getByRole('button', { name: 'Search' }).click();
  await canceledCandidate;
  await expect(roster.getByRole('option', { name: 'Mara Demo' })).toHaveCount(1);
  await page.unroute('**/api/v1/members?**');
  await roster.getByRole('button', { name: 'Assign' }).click();
  await expect(roster.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toHaveCount(1);
  // Tapping the name opens the contacts; a 403 from the server is shown as a denial, never as data.
  const maraRoster = roster.getByRole('listitem').filter({ hasText: 'Mara Demo' }).locator('button.row-toggle');
  await maraRoster.click();
  await expect(roster.getByRole('region', { name: 'Details: Mara Demo' })).toBeVisible();
  await roster.getByRole('button', { name: 'Close' }).click();
  // Not `times: 1`: StrictMode runs the effect twice in development, and the first request is aborted.
  await page.route('**/api/v1/members/*/details', route => route.fulfill({
    status: 403, contentType: 'application/json', body: '{"code":"forbidden"}' }));
  await maraRoster.click();
  await expect(roster.getByRole('alert').filter({ hasText: 'No permission to see these details.' })).toBeVisible();
  await expect(roster.getByRole('region', { name: 'Details: Mara Demo' })).toHaveCount(0);
  await roster.getByRole('button', { name: 'Close' }).click();
  await page.unroute('**/api/v1/members/*/details');
  // An obsolete roster response must not replace a fresh roster after a new assignment.
  let startedOldRoster;
  const oldRosterStarted = new Promise(resolve => { startedOldRoster = resolve; });
  let delayRoster = true;
  await page.route('**/api/v1/teams/*/members?**', async route => {
    if (delayRoster && route.request().method() === 'GET') {
      delayRoster = false;
      startedOldRoster();
      await new Promise(resolve => setTimeout(resolve, 500));
      await route.continue().catch(() => {});
    } else {
      await route.continue();
    }
  });
  const canceledOldRoster = page.waitForEvent('requestfailed',
    request => request.url().includes('/api/v1/teams/') && request.url().includes('/members?'));
  await maraRoster.click();
  await roster.getByRole('button', { name: 'Remove from team' }).click();
  await oldRosterStarted;
  await roster.getByRole('button', { name: 'Assign' }).click();
  await canceledOldRoster;
  await expect(roster.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toHaveCount(1);
  await page.unroute('**/api/v1/teams/*/members?**');
  // The team list counts the assignment.
  await show(page, '#/team');
  await expect(juniorsRow).toContainText('1 member');
});

test('a weekly training, its answers, a shift, rides, reminders and statistics', async ({ page }) => {
  test.setTimeout(120000);
  const mara = ensureMara();
  const juniorsPage = '#/team/' + ensureTeam('Juniors', [mara]);
  const teamParts = page.getByRole('navigation', { name: 'Team pages' });
  await show(page, juniorsPage);
  // Trainings and events (OpenAPI 1.10.0): a weekly training for Juniors in the browser's time zone; one copy is
  // cancelled and one deleted. The administrator is in no team, so their own schedule stays empty.
  const [firstDay, lastDay] = await page.evaluate(() => [2, 16].map(days => {
    const day = new Date(Date.now() + days * 86400000);
    return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
  }));
  await teamParts.getByRole('link', { name: /^Schedule/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Schedule', exact: true })).toBeVisible();
  const schedule = page.getByRole('region', { name: 'Schedule Juniors' });
  await expect(schedule.getByText('No events in the next eight weeks.')).toBeVisible();
  await openForm(schedule, 'New event');
  await schedule.getByLabel('Title').fill('Juniors training');
  await schedule.getByLabel('Date', { exact: true }).fill(firstDay);
  await schedule.getByLabel('Place', { exact: true }).fill('Hall 1');
  await schedule.getByLabel('Repeat weekly until').fill(lastDay);
  await schedule.getByRole('button', { name: 'Add event' }).click();
  await expect(schedule.getByRole('status')).toHaveText('Event saved.');
  const trainings = schedule.getByRole('listitem').filter({ hasText: 'Juniors training' });
  await expect(trainings).toHaveCount(3);
  await expect(trainings.first()).toContainText('18:00');
  // Cancel and delete are on the event's own page, not in the list.
  await trainings.first().getByRole('link').click();
  await page.getByText('Change, cancel or delete the event').click();
  await page.getByRole('button', { name: 'Cancel event', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Reinstate', exact: true })).toBeVisible();
  await page.goBack();
  await expect(trainings.first()).toContainText('Cancelled');
  await trainings.last().getByRole('link').click();
  await page.getByText('Change, cancel or delete the event').click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm delete' }).click();
  await expect(trainings).toHaveCount(2);
  // Attendance (OpenAPI 1.11.0): the team's viewers and managers see each member's answer by public display name,
  // on the event's own coach page, which the schedule row links to.
  await expect(trainings.last()).toContainText('Answers: 0 Yes, 0 No, 0 Maybe');
  await trainings.last().getByRole('link').click();
  await expect(page).toHaveURL(/#\/team\/[0-9a-f-]{36}\/event\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Juniors training' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'No answer yet', exact: true })).toContainText('Mara D.');
  // An empty group gets no box: the counts already say 0.
  await expect(page.getByRole('region', { name: 'Yes', exact: true })).toHaveCount(0);
  // Volunteer shifts (OpenAPI 1.16.0): the manager adds a shift with the event's times as defaults and changes it.
  const shifts = page.getByRole('region', { name: 'Volunteer shifts: Juniors training' });
  await expect(shifts.getByText('No volunteer shifts.')).toBeVisible();
  await openForm(shifts, 'New shift');
  await shifts.getByLabel('Task').fill('Bar');
  await shifts.getByLabel('People needed').fill('2');
  await shifts.getByRole('button', { name: 'Add shift' }).click();
  const bar = shifts.getByRole('listitem').first(); // by position: while edited, the title is an input value
  await expect(bar).toContainText('0 of 2 filled (nobody yet)');
  await bar.getByRole('button', { name: 'Edit' }).click();
  await bar.getByLabel('People needed').fill('3');
  await bar.getByRole('button', { name: 'Save' }).click();
  await expect(bar).toContainText('Bar · ');
  await expect(bar).toContainText('0 of 3 filled');
  // Carpooling (OpenAPI 1.21.0): a manager outside the team sees the rides but cannot offer one.
  const rides = page.getByRole('region', { name: 'Carpooling: Juniors training' });
  await expect(rides.getByText('No rides offered yet.')).toBeVisible();
  await expect(rides.getByRole('button', { name: 'Offer a ride' })).toHaveCount(0);
  // Ride requests (OpenAPI 1.41.0): everyone who sees the rides sees who needs one, by display name; the manager has
  // nobody to ask for.
  const rideEvent = page.url().split('/').pop();
  if (!/^[0-9a-f-]{36}$/.test(rideEvent)) throw new Error('Unexpected event ID');
  fixtureSql(`INSERT INTO ride_requests (event_id, member_id, direction, requested_by) VALUES ('${rideEvent}', '${mara}', 'THERE', '${mara}')`);
  await page.reload();
  await expect(rides.getByRole('listitem').filter({ hasText: 'Mara D.' })).toHaveText('Mara D. · There');
  await expect(rides.getByRole('button', { name: 'Ask for a ride' })).toHaveCount(0);
  // Helpers missing (OpenAPI 1.23.0): the shift with its free places on Waiting for you and on the Team page.
  await show(page, '#/admin');
  await expect(page.getByRole('article', { name: 'Bar: 3 of 3 places free' })).toContainText('Juniors training · Juniors');
  await show(page, '#/team');
  await expect(page.getByRole('region', { name: 'Helpers missing' })).toContainText('Bar: 3 of 3 places free');
  // The coach view: the next two weeks with who has not answered, and one page per event with names by answer.
  const nextEvents = page.getByRole('region', { name: 'Next events' });
  await expect(nextEvents.getByRole('link').filter({ hasText: 'Cancelled' })).toHaveCount(1);
  await nextEvents.getByRole('link').filter({ hasText: '1 open' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Juniors training' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'No answer yet' })).toContainText('Mara D.');
  // Answer reminders (OpenAPI 1.25.0): this installation sends no email, so the coach is told so.
  await page.getByRole('button', { name: 'Remind the one without an answer' }).click();
  await expect(page.getByRole('alert')).toHaveText('This club does not send emails.');
  // A note with an answer (OpenAPI 1.39.0) stands next to the name for the team's coaches.
  const eventId = page.url().split('/').pop();
  if (!/^[0-9a-f-]{36}$/.test(eventId)) throw new Error('Unexpected event ID');
  fixtureSql(`INSERT INTO event_attendance (event_id, member_id, response, note) VALUES ('${eventId}', '${mara}', 'YES', 'Comes at 18:30')`);
  await page.reload();
  await expect(page.getByRole('region', { name: 'Yes', exact: true })).toContainText('Mara D.: Comes at 18:30');
  await expect(page.getByRole('region', { name: 'Volunteer shifts: Juniors training' })).toContainText('Bar');
  await show(page, juniorsPage);
  await teamParts.getByRole('link', { name: /^Statistics/ }).click();
  // Attendance statistics (OpenAPI 1.18.0): the last 12 months by default; the new trainings lie ahead, so none held.
  const statistics = page.getByRole('region', { name: 'Attendance statistics Juniors' });
  await expect(statistics.getByText('Events held: 0')).toBeVisible();
  // One row per member: the name, then the four counts.
  await expect(statistics.getByRole('listitem').filter({ hasText: 'Mara D.' })).toContainText('Yes 0 · No 0 · Maybe 0 · No answer 0');
});

test('my schedule and a calendar link that ends when revoked', async ({ page }) => {
  await show(page, '#/schedule');
  await expect(page.getByRole('main').getByText('No events in the next eight weeks.')).toBeVisible();
  await show(page, '#/family/calendar');
  // Calendar feed (OpenAPI 1.17.0): the link is shown once, serves iCalendar without a session and ends when revoked.
  const feedSection = page.getByRole('region', { name: 'Calendar subscription' });
  await feedSection.getByRole('button', { name: 'Create link' }).click();
  const feedLink = await feedSection.getByLabel('Your link (shown only now)').inputValue();
  expect(feedLink).toMatch(/\/api\/v1\/calendar\/[A-Za-z0-9_-]{43}$/);
  const feed = await playwrightRequest.newContext().then(anonymous => anonymous.get(feedLink));
  expect(feed.status()).toBe(200);
  expect(await feed.text()).toContain('BEGIN:VCALENDAR');
  await expect(feedSection.getByRole('button', { name: 'Revoke link' })).toBeVisible();
  await feedSection.getByRole('button', { name: 'Revoke link' }).click();
  await expect(feedSection.getByRole('status')).toHaveText('Link revoked.');
  expect((await (await playwrightRequest.newContext()).get(feedLink)).status()).toBe(404);
});

test('an absence for today is listed and deleted', async ({ page }) => {
  // Absences (OpenAPI 1.20.0): enter one for today, see it listed, delete it again.
  await show(page, '#/family/absences');
  const absences = page.getByRole('region', { name: 'Absences' });
  await expect(absences.getByText('No absences.')).toBeVisible();
  // The server checks dates in the club's time zone, so use today's Swiss date (the runner's clock is UTC).
  const todayLocal = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Zurich' }).format(new Date());
  await absences.getByLabel('From').fill(todayLocal);
  // An optional note for the coaches (OpenAPI 1.39.0).
  await absences.getByLabel('Note for the coach (optional)').fill('School camp');
  await absences.getByRole('button', { name: 'Add absence' }).click();
  const [year, month, day] = todayLocal.split('-');
  await expect(absences.getByRole('listitem')).toHaveText(`Me: ${day}.${month}.${year} · School camp Delete`);
  await absences.getByRole('button', { name: 'Delete' }).click();
  await expect(absences.getByText('No absences.')).toBeVisible();
});

test('a team is renamed, a failed list recovers, and a team is deleted by name', async ({ page }) => {
  const teams = page.getByRole('region', { name: 'Teams', exact: true });
  const juniorsPage = '#/team/' + ensureTeam('Rename Me');
  // A rename on the team's page preserves the team's ID (same address), membership relationship and permissions.
  await show(page, juniorsPage);
  const manageTeam = page.getByRole('region', { name: 'Manage team' });
  await manageTeam.getByRole('button', { name: 'Rename' }).click();
  await manageTeam.getByRole('textbox', { name: 'Team name' }).fill('Youth Team');
  await manageTeam.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Youth Team', exact: true })).toBeVisible();
  await show(page, '#/team');
  await expect(teams.getByRole('listitem').filter({ hasText: 'Youth Team' })).toContainText('0 members');
  // A transient team-list failure must clear after a later successful create/reload.
  let failedTeamList = false;
  await page.route('**/api/v1/teams', async route => {
    if (!failedTeamList && route.request().method() === 'GET') {
      failedTeamList = true;
      await route.fulfill({ status: 503, body: 'Unavailable' });
    } else {
      await route.continue();
    }
  });
  await openForm(teams, 'New team');
  await teams.getByLabel('Team name').fill('Transient Team');
  await teams.getByRole('button', { name: 'Add team' }).click();
  await expect.poll(() => failedTeamList).toBe(true);
  await expect(teams.getByRole('alert').filter({ hasText: 'Teams unavailable.' })).toBeVisible();
  await openForm(teams, 'New team');
  await teams.getByLabel('Team name').fill('Recovered Team');
  await teams.getByRole('button', { name: 'Add team' }).click();
  await expect(teams.getByRole('listitem').filter({ hasText: 'Recovered Team' })).toContainText('0 members');
  await expect(teams.getByRole('listitem').filter({ hasText: 'Transient Team' })).toContainText('0 members');
  await expect(teams.getByRole('alert').filter({ hasText: 'Teams unavailable.' })).toHaveCount(0);
  await page.unroute('**/api/v1/teams');
  // Deleting a team on its page needs its name typed; afterwards the list opens without it.
  await teams.getByRole('link', { name: /^Transient Team/ }).click();
  const manageTransient = page.getByRole('region', { name: 'Manage team' });
  await manageTransient.getByRole('button', { name: 'Delete team' }).click();
  await expect(manageTransient.getByRole('button', { name: 'Delete permanently' })).toBeDisabled();
  await manageTransient.getByRole('textbox', { name: 'Team name' }).fill('Transient Team');
  await manageTransient.getByRole('button', { name: 'Delete permanently' }).click();
  await expect(teams.getByRole('listitem').filter({ hasText: 'Recovered Team' })).toBeVisible();
  await expect(teams.getByRole('listitem').filter({ hasText: 'Transient Team' })).toHaveCount(0);
});

test('revoked member-directory access hides an open roster', async ({ page }) => {
  const roster = page.getByRole('region', { name: 'Team members', exact: true });
  const juniorsPage = '#/team/' + ensureTeam('Empty Roster');
  // Revoked member-directory access must hide names and controls in an existing roster.
  await show(page, juniorsPage + '/members');
  await expect(roster.getByText('No assignments.')).toBeVisible();
  await page.route('**/api/v1/members?**', route => route.fulfill({ status: 403, body: 'Forbidden' }), { times: 1 });
  await roster.getByLabel('Find member').fill('Mara');
  await roster.getByRole('button', { name: 'Search' }).click();
  await expect(roster).toHaveCount(0);
  await expect(page.getByLabel('Find member')).toHaveCount(0);
  await expect(page.getByText('You cannot see the members of this team.')).toBeVisible();
});
