import { adminAccountId, ensureTeam, expect, fixtureSql, openForm, show, test } from './club-fixtures.js';

// The administrator also plays: assigned to a team of their own for this test only, they use the member side of
// OpenClub 1.39.0–1.41.0 (a note with an answer, the answer deadline, repeats every two weeks, new times for a series,
// ride requests). Runs last among the club tests (club-y) and removes its team, so nothing else sees the assignment.
test('as a team member: a note with the answer, the deadline, a fortnightly series with new times, a ride request', async ({ page }) => {
  const account = adminAccountId();
  if (!/^[0-9a-f-]{36}$/i.test(account)) throw new Error('Unexpected synthetic account ID');
  const admin = fixtureSql(`SELECT id FROM members WHERE account_id = '${account}'`);
  const team = ensureTeam('Admin Squad', [admin]);
  // Dates in Swiss time, as the browser sees them.
  const swissDay = offset => fixtureSql(`SELECT to_char((now() AT TIME ZONE 'Europe/Zurich')::date + ${offset}, 'YYYY-MM-DD')`);
  try {
    const event = fixtureSql(`INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at)
      VALUES (gen_random_uuid(), '${team}', 'TRAINING', 'Member training',
        (((now() AT TIME ZONE 'Europe/Zurich')::date + 3) + time '18:00') AT TIME ZONE 'Europe/Zurich',
        (((now() AT TIME ZONE 'Europe/Zurich')::date + 3) + time '19:30') AT TIME ZONE 'Europe/Zurich') RETURNING id`).split('\n')[0];

    // A note with the answer: saved with it, and shown to the coach under the group.
    await show(page, '#/schedule/' + event);
    const answers = page.getByRole('region', { name: 'Are you coming?' });
    const mine = answers.getByRole('group', { name: 'Are you coming? Member training' });
    await mine.getByRole('button', { name: 'Yes' }).click();
    await expect(mine.getByRole('button', { name: 'Yes' })).toHaveAttribute('aria-pressed', 'true');
    await answers.getByLabel('Note for the coach (optional)').fill('Comes at 18:30');
    await answers.getByRole('button', { name: 'Save note' }).click();
    await expect(answers.getByRole('button', { name: 'Save note' })).toBeDisabled();
    expect(fixtureSql(`SELECT note FROM event_attendance WHERE event_id = '${event}' AND member_id = '${admin}'`)).toBe('Comes at 18:30');
    await show(page, `#/team/${team}/event/${event}`);
    await expect(page.getByRole('region', { name: 'Yes', exact: true })).toContainText(': Comes at 18:30');

    // A ride request: listed under "Rides wanted", withdrawn with "No longer needed".
    await show(page, '#/schedule/' + event);
    const rides = page.getByRole('region', { name: 'Carpooling: Member training' });
    await rides.getByRole('button', { name: 'Ask for a ride' }).click();
    const ask = rides.getByRole('form', { name: 'Ask for a ride' });
    await ask.getByLabel('Direction').selectOption({ label: 'There' });
    await ask.getByRole('button', { name: 'Ask for a ride' }).click();
    await expect(rides.getByRole('heading', { name: 'Rides wanted' })).toBeVisible();
    await expect(rides.getByRole('listitem').filter({ hasText: '· There' })).toHaveCount(1);
    expect(fixtureSql(`SELECT direction FROM ride_requests WHERE event_id = '${event}' AND member_id = '${admin}'`)).toBe('THERE');
    await rides.getByRole('button', { name: 'No longer needed' }).click();
    await expect(rides.getByRole('heading', { name: 'Rides wanted' })).toHaveCount(0);
    await expect(rides.getByRole('button', { name: 'Ask for a ride' })).toBeVisible();

    // After the answer deadline the answer can no longer change.
    fixtureSql(`UPDATE team_events SET answers_until = now() - INTERVAL '1 minute' WHERE id = '${event}'`);
    await page.reload();
    await expect(page.getByText('Answers closed', { exact: true })).toBeVisible();
    await expect(mine.getByRole('button', { name: 'No' })).toBeDisabled();

    // A series every two weeks with a deadline the evening before, then new times for all of it.
    const first = swissDay(7);
    await show(page, `#/team/${team}/schedule`);
    const schedule = page.getByRole('region', { name: 'Schedule Admin Squad' });
    await openForm(schedule, 'New event');
    await schedule.getByLabel('Title').fill('Fortnightly');
    await schedule.getByLabel('Date', { exact: true }).fill(first);
    await schedule.getByLabel('Answers until (optional)').fill(`${swissDay(6)}T20:00`);
    await schedule.getByLabel('Repeat weekly until').fill(swissDay(7 + 28));
    await schedule.getByLabel('How often').selectOption('2');
    await schedule.getByRole('button', { name: 'Add event' }).click();
    const series = () => fixtureSql(`SELECT to_char(starts_at AT TIME ZONE 'Europe/Zurich', 'YYYY-MM-DD HH24:MI') || ' ' ||
      extract(epoch FROM starts_at - answers_until)::int / 3600 FROM team_events WHERE team_id = '${team}' AND title = 'Fortnightly'
      ORDER BY starts_at`).split('\n');
    await expect.poll(series).toEqual([`${first} 18:00 22`, `${swissDay(21)} 18:00 22`, `${swissDay(35)} 18:00 22`]);

    const bulk = schedule.locator('details').filter({ hasText: 'Change several events' });
    await bulk.locator('summary').click();
    await bulk.getByLabel('From').fill(first);
    await bulk.getByLabel('To').fill(swissDay(35));
    await bulk.getByLabel('New times').check();
    await bulk.getByLabel('Start').fill('17:15');
    await bulk.getByLabel('End').fill('18:45');
    await bulk.getByRole('button', { name: 'Check' }).click();
    await expect(bulk.getByRole('status')).toHaveText('3 events are affected.');
    await bulk.getByRole('button', { name: 'Change 3 events' }).click();
    await expect(bulk.getByRole('status')).toHaveText('3 events changed.');
    // Each keeps its day; the deadline keeps its lead before the new start.
    expect(series()).toEqual([`${first} 17:15 22`, `${swissDay(21)} 17:15 22`, `${swissDay(35)} 17:15 22`]);
  } finally {
    fixtureSql(`DELETE FROM teams WHERE id = '${team}'`);
  }
});
