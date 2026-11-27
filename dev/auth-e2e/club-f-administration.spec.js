import { ensureTeam, fixtureSql, ensureMara, expect, openForm, show, test } from './club-fixtures.js';
import { readFileSync } from 'node:fs';
import { request as playwrightRequest } from '@playwright/test';
// Join requests, fees and QR bills, Excel import, fields and halls, announcements.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('the join form opens, a visitor applies and the application is declined', async ({ page }) => {
  // Join requests (OpenAPI 1.22.0): the form is off by default; the administrator opens it, an anonymous visitor
  // applies, the administrator declines.
  await show(page, '#/admin/join-requests');
  const applications = page.getByRole('region', { name: 'Membership applications' });
  const formSwitch = applications.getByLabel('Public application form is on');
  await expect(formSwitch).not.toBeChecked();
  await formSwitch.click();
  await expect(formSwitch).toBeChecked();
  const visitor = await playwrightRequest.newContext({ baseURL: 'http://localhost:5173' });
  expect((await (await visitor.get('/api/v1/public/join-requests')).json()).open).toBe(true);
  const applied = await visitor.post('/api/v1/public/join-requests', { headers: { Origin: 'http://localhost:5173' }, data: {
    kind: 'SELF', firstName: 'Vera', lastName: 'Visitor', dateOfBirth: '1990-04-01', email: 'vera@example.invalid',
    phone: '+41 79 000 00 09', acceptedPrivacyNotice: true, website: '' } });
  expect(applied.status()).toBe(202);
  // Closing the form again reloads the list; open applications stay until handled.
  await formSwitch.click();
  await expect(formSwitch).not.toBeChecked();
  const vera = applications.getByRole('listitem').filter({ hasText: 'Vera Visitor' });
  await expect(vera).toContainText('vera@example.invalid');
  await vera.getByRole('button', { name: 'Decline' }).click();
  await vera.getByRole('button', { name: 'Really decline and delete' }).click();
  await expect(applications.getByText('No open applications.')).toBeVisible();
});

test('a fee period, a paid fee, payment details and a QR bill', async ({ page, context }) => {
  ensureMara();
  // Membership fees (OpenAPI 1.12.0): the first administrator holds MANAGE_FEES from the bootstrap. A new period
  // includes every active member; marking Mara's fee paid updates the totals; the administrator sees their own fee.
  await show(page, '#/admin/fees');
  const feeAdmin = page.getByRole('region', { name: 'Membership fees' });
  await openForm(feeAdmin, 'New fee period');
  await feeAdmin.getByLabel('Name').fill('Season 2026/27');
  await feeAdmin.getByLabel('Due date').fill('2026-12-31');
  await feeAdmin.getByLabel('Amount (CHF)').fill('150');
  await feeAdmin.getByRole('button', { name: 'Create period' }).click();
  const season = feeAdmin.getByRole('listitem').filter({ hasText: 'Season 2026/27' });
  await expect(season).toContainText(/0 of \d+ paid/);
  await season.getByRole('button', { name: 'Open' }).click();
  const maraFee = feeAdmin.getByRole('region', { name: 'Season 2026/27' }).getByRole('row').filter({ hasText: 'Demo Mara' });
  await expect(maraFee).toContainText('150.00');
  await maraFee.getByRole('button', { name: 'Mark paid' }).click();
  await expect(maraFee).toContainText('Paid on');
  await expect(season).toContainText(/1 of \d+ paid/);
  await page.reload();
  await show(page, '#/family/fees');
  await expect(page.getByRole('region', { name: 'My fees' })).toContainText('Season 2026/27');

  // Swiss QR bills (OpenAPI 1.26.0): the treasurer enters the club's account (a fictional IBAN with a valid checksum);
  // the fee list then shows each member's reference and every open fee has a QR bill (A4 PDF) to download.
  await show(page, '#/admin/fees');
  const payment = page.getByRole('region', { name: 'Payment details for QR bills' });
  await expect(payment).toContainText('No payment details yet');
  await payment.getByLabel('IBAN or QR-IBAN').fill('CH93 0076 2011 6238 5295 8');
  await payment.getByLabel('Account holder').fill('FC Demo');
  await payment.getByLabel('Postal code').fill('8000');
  await payment.getByLabel('Town').fill('Zürich');
  await payment.getByRole('button', { name: 'Save payment details' }).click();
  await expect(payment.getByRole('alert')).toContainText('Please check');
  await payment.getByLabel('IBAN or QR-IBAN').fill('CH93 0076 2011 6238 5295 7');
  await payment.getByRole('button', { name: 'Save payment details' }).click();
  await expect(payment.getByRole('status')).toHaveText('Saved.');
  await expect(payment.getByLabel('IBAN or QR-IBAN')).toHaveValue('CH9300762011623852957');
  await season.getByRole('button', { name: 'Open' }).click();
  await expect(maraFee).toContainText(/RF\d{2} P\d+M/);
  await show(page, '#/family/fees');
  const qrBill = page.getByRole('region', { name: 'My fees' }).getByRole('link', { name: /^QR bill \(PDF\): Season 2026\/27/ }).first();
  const qrPdf = await context.request.get(await qrBill.getAttribute('href'));
  expect(qrPdf.status()).toBe(200);
  expect(qrPdf.headers()['content-type']).toContain('application/pdf');
  expect((await qrPdf.body()).subarray(0, 4).toString('latin1')).toBe('%PDF');
});

test('the member list exports to CSV and an import is checked first', async ({ page }) => {
  ensureMara();
  // Excel import and export (OpenAPI 1.13.0): the list downloads as CSV; an import is checked first, skips the
  // existing Mara Demo and creates only the new rows.
  await show(page, '#/admin/members/transfer');
  const transfer = page.getByRole('region', { name: 'Excel import and export' });
  const listDownload = page.waitForEvent('download');
  await transfer.getByRole('link', { name: 'Download member list (CSV for Excel)' }).click();
  const listFile = await listDownload;
  expect(listFile.suggestedFilename()).toMatch(/^members-\d{4}-\d{2}-\d{2}\.csv$/);
  const listText = readFileSync(await listFile.path(), 'utf8');
  expect(listText.startsWith('\uFEFF"First name";"Last name";"Date of birth"')).toBe(true);
  expect(listText).toContain('"Mara";"Demo";"01.01.2015"');
  await transfer.getByLabel('CSV file from Excel').setInputFiles({
    name: 'members.csv', mimeType: 'text/csv',
    // With a gender for players, in case the club's "Require gender for players" rule is on at this point.
    buffer: Buffer.from('Vorname;Nachname;Geburtsdatum;E-Mail;Telefon;Spieler;Geschlecht\nImporta;Csvtest;3.2.2014;;;ja;Synthetic\n' +
      'Importb;Csvtest;1.1.1990;importb@example.invalid;+41 79 000 00 09;nein;\nMara;Demo;01.01.2015;;;ja;Synthetic\n' +
      'Broken;Csvtest;31.02.2014;;;ja;Synthetic\n'),
  });
  await transfer.getByRole('button', { name: 'Check file' }).click();
  await expect(transfer.getByRole('status')).toHaveText('2 will be imported, 1 already exist, 1 have problems (not imported).');
  await expect(transfer.getByRole('row').filter({ hasText: 'Broken Csvtest' })).toContainText('date of birth');
  await transfer.getByRole('button', { name: 'Import 2 members' }).click();
  await expect(transfer.getByRole('status')).toHaveText('2 members imported.');
});

test('a field or hall added by the administrator shows for every member', async ({ page }) => {
  // Fields and halls (OpenAPI 1.30.0): the first administrator holds MANAGE_VENUES, adds one, and every member sees
  // it under Schedule.
  await show(page, '#/admin/venues');
  await expect(page.getByRole('heading', { level: 1, name: 'Fields and halls' })).toBeVisible();
  const venueAdmin = page.getByRole('region', { name: 'Fields and halls' });
  await venueAdmin.getByLabel('Name').last().fill('Synthetic Hall North');
  await venueAdmin.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(venueAdmin.getByText('Synthetic Hall North', { exact: true })).toBeVisible();
  await show(page, '#/schedule/venues');
  await expect(page.getByRole('heading', { level: 1, name: 'When fields and halls are booked' })).toBeVisible();
  const field = page.getByRole('combobox', { name: 'Field or hall' });
  await expect(field).toHaveValue('');
  await expect(field.getByRole('option', { name: 'Synthetic Hall North' })).toBeAttached();
  // The week plan: seven days from Monday, today marked, and the next week one tap away.
  const plan = page.locator('ol.week-plan');
  await expect(plan.locator(':scope > li')).toHaveCount(7);
  await expect(plan.locator('[aria-current="date"]')).toHaveCount(1);
  const week = page.getByRole('navigation', { name: 'Choose a week' }).getByRole('heading', { level: 2 });
  const thisWeek = await week.textContent();
  await page.getByRole('button', { name: 'Next week' }).click();
  await expect(week).not.toHaveText(thisWeek ?? '');
  await expect(plan.locator('[aria-current="date"]')).toHaveCount(0);
  await page.getByRole('button', { name: 'This week' }).click();
  await expect(week).toHaveText(thisWeek ?? '');

  // The event form marks a field or hall that is already booked at the chosen time, before saving.
  const owners = ensureTeam('Hall Owners');
  const guests = '#/team/' + ensureTeam('Hall Guests');
  fixtureSql(`INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, venue_id)
    SELECT gen_random_uuid(), '${owners}', 'TRAINING', 'Owners training',
      (((now() AT TIME ZONE 'Europe/Zurich')::date + 3) + time '18:00') AT TIME ZONE 'Europe/Zurich',
      (((now() AT TIME ZONE 'Europe/Zurich')::date + 3) + time '19:30') AT TIME ZONE 'Europe/Zurich', id
    FROM venues WHERE name = 'Synthetic Hall North'`);
  // Both dates in Swiss time: between midnight in Zurich and midnight UTC the database's current_date is a day behind.
  const day = await page.evaluate(() => {
    const date = new Date(Date.now() + 3 * 86400000);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  });
  await show(page, guests + '/schedule');
  const schedule = page.getByRole('region', { name: 'Schedule Hall Guests' });
  await openForm(schedule, 'New event');
  await schedule.getByLabel('Date', { exact: true }).fill(day);
  const hall = schedule.getByRole('option', { name: /^Synthetic Hall North/ });
  await expect(hall).toHaveText('Synthetic Hall North (booked: Hall Owners 18:00–19:30)');
  await expect(hall).toHaveAttribute('disabled', '');
  await schedule.getByLabel('Start').fill('20:00');
  await schedule.getByLabel('End').fill('21:00');
  await expect(hall).toHaveText('Synthetic Hall North');
  await expect(hall).not.toHaveAttribute('disabled');
});

test('an announcement keeps its line breaks and is never HTML', async ({ page }) => {
  // Announcements (OpenAPI 1.14.0): the first administrator holds POST_ANNOUNCEMENTS from the bootstrap and posts to
  // the whole club; the text keeps its line breaks and is never interpreted as HTML.
  await show(page, '#/');
  const news = page.getByRole('region', { name: 'Announcements' });
  await news.getByRole('button', { name: 'New announcement' }).click();
  await expect(news.getByLabel('For')).toHaveValue('');
  await news.getByLabel('Title').fill('Training starts again');
  await news.getByLabel('Text', { exact: true }).fill('First line <b>not bold</b>\nSecond line');
  await news.getByRole('button', { name: 'Post' }).click();
  await expect(news.getByRole('status')).toHaveText('Posted.');
  const posted = news.getByRole('article', { name: 'Training starts again' });
  await expect(posted).toContainText('Whole club');
  await expect(posted).toContainText('First line <b>not bold</b>');
  await expect(posted.locator('b')).toHaveCount(0);
  await expect(posted.locator('br')).toHaveCount(1);
});
