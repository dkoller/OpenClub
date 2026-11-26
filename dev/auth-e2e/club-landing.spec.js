import { readFileSync } from 'node:fs';
import { test, expect, request as playwrightRequest } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

/**
 * Signed in, the language is chosen under Family → My profile (not in the header). Opens it, chooses, and returns to
 * the page the test was on.
 */
async function chooseLanguage(page, code) {
  const back = await page.evaluate(() => window.location.hash);
  await page.evaluate(() => { window.location.hash = '#/family/profile'; });
  await page.locator('#language').selectOption(code);
  await expect(page.locator('html')).toHaveAttribute('lang', code);
  await page.evaluate(target => { window.location.hash = target; }, back || '#/');
}

// The app shows one page at a time (Start, Schedule, Family, Team, Administration); tests open a page by its
// address, which a reload keeps.
async function show(page, hash) {
  await page.evaluate(target => { window.location.hash = target; }, hash);
}

test('real frontend shows session, membership denial, language and logout states', async ({ page, context }) => {
  const password = process.env.OPENCLUB_E2E_USER_PASSWORD;
  if (!password) throw new Error('Disposable synthetic login password required');

  await page.goto('/');
  // Chromium's default locale may be English; explicitly exercise German.
  await page.locator('#language').selectOption('de');
  await expect(page.getByRole('status')).toHaveText('Bitte anmelden.');
  await page.locator('#language').selectOption('en');
  await expect(page.getByRole('status')).toHaveText('Please sign in.');

  await page.getByRole('link', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/^http:\/\/localhost:8180\/realms\/openclub-dev\//);
  await page.locator('#username').fill('openclub-ci-user');
  await page.locator('#password').fill(password);
  await Promise.all([
    page.waitForURL('http://localhost:5173/'),
    page.locator('#kc-login').click(),
  ]);

  // Real OIDC identity is unlinked: authentication alone grants no club access.
  await expect(page.getByRole('status')).toHaveText(
    'This account is not yet a club member.',
  );
  await expect(page.getByRole('link', { name: 'Sign in' })).toHaveCount(0);

  // A verified login without a linked club member must not expose private API data.
  for (const path of [
    '/api/v1/club',
    '/api/v1/members',
    '/api/v1/teams',
  ]) {
    const response = await context.request.get(path);
    expect(response.status(), path).toBe(403);
    expect(response.headers()['cache-control']).toContain('no-store');
  }

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('status')).toHaveText('Please sign in.');
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();

  await page.reload();
  await expect(page.locator('#language')).toHaveValue('en');
  await expect(page.getByRole('status')).toHaveText('Please sign in.');
});

test('verified login gains club access only after restricted operator bootstrap', async ({ page, context }) => {
  test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
    'First-club bootstrap must only run against the disposable CI database');
  test.setTimeout(180000);
  const password = process.env.OPENCLUB_E2E_USER_PASSWORD;
  if (!password) throw new Error('Disposable synthetic login password required');

  await page.goto('/');
  await page.locator('#language').selectOption('en');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.locator('#username').fill('openclub-ci-user');
  await page.locator('#password').fill(password);
  await Promise.all([
    page.waitForURL('http://localhost:5173/'),
    page.locator('#kc-login').click(),
  ]);
  await expect(page.getByRole('status')).toHaveText('This account is not yet a club member.');

  // Use the real restricted operator CLI, not a forged session or HTTP admin API.
  const me = await context.request.get('/api/v1/auth/me');
  expect(me.status()).toBe(200);
  const { accountId } = await me.json();
  expect(accountId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i);
  const apiDirectory = fileURLToPath(new URL('../../apps/api/', import.meta.url));
  execFileSync('./gradlew', ['bootstrapFirstClub', '--no-daemon', '--console=plain'], {
    cwd: apiDirectory,
    timeout: 120000,
    stdio: 'pipe',
    env: {
      ...process.env,
      OPENCLUB_BOOTSTRAP_CONFIRM: 'create-first-club',
      OPENCLUB_BOOTSTRAP_ACCOUNT_ID: accountId,
      OPENCLUB_BOOTSTRAP_CLUB_NAME: 'Synthetic Browser Club',
      OPENCLUB_BOOTSTRAP_CLUB_SLUG: 'synthetic-browser',
      OPENCLUB_BOOTSTRAP_FIRST_NAME: 'Synthetic',
      OPENCLUB_BOOTSTRAP_LAST_NAME: 'Administrator',
      OPENCLUB_BOOTSTRAP_BIRTH_DATE: '1990-01-01',
      OPENCLUB_BOOTSTRAP_EMAIL: 'administrator@example.invalid',
      OPENCLUB_BOOTSTRAP_PHONE: '+41000000000',
    },
  });

  await page.reload();
  await expect(page.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Synthetic Browser Club' })).toBeVisible();
  await show(page, '#/admin/settings');
  const clubSettings = page.getByRole('region', { name: 'Edit club name' });
  await clubSettings.getByRole('textbox', { name: 'Club name' }).fill('Synthetic Updated Club');
  await clubSettings.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Synthetic Updated Club' })).toBeVisible();
  const updatedClub = await context.request.get('/api/v1/club');
  expect(updatedClub.status()).toBe(200);
  expect((await updatedClub.json()).name).toBe('Synthetic Updated Club');

  // The public directory renders the synthetic adult name, even after logout (editors see it under Members).
  await show(page, '#/admin/members/public');
  await expect(page.getByRole('region', { name: 'Members', exact: true }).getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
  const publicNames = await context.request.get('/api/v1/public/members');
  expect(publicNames.status()).toBe(200);
  expect(await publicNames.json()).toEqual([{ displayName: 'Synthetic Administrator' }]);

  // Complete the real authorized browser journey with fictional member data.
  await page.getByRole('link', { name: '← Members' }).click();
  await expect(page.getByRole('heading', { name: 'Manage members' })).toBeVisible();

  // My profile (OpenAPI 1.9.0): the administrator changes their own phone; the form keeps the email it read.
  await show(page, '#/family/profile');
  const myProfile = page.getByRole('region', { name: 'My profile' });
  await expect(myProfile.getByText('Administrator', { exact: true })).toBeVisible();
  const ownEmail = await myProfile.getByLabel('Email', { exact: true }).inputValue();
  expect(ownEmail).toContain('@');
  await myProfile.getByLabel('Phone').fill('+41 79 000 00 01');
  await myProfile.getByRole('button', { name: 'Save contacts' }).click();
  await expect(myProfile.getByRole('status')).toHaveText('Contacts saved.');
  await page.reload();
  await expect(page.getByRole('region', { name: 'My profile' }).getByLabel('Phone')).toHaveValue('+41 79 000 00 01');
  await expect(page.getByRole('region', { name: 'My profile' }).getByLabel('Email', { exact: true })).toHaveValue(ownEmail);
  // Email notifications (OpenAPI 1.19.0): this test installation has no SMTP server, so the switches are shown off.
  await show(page, '#/family/notifications');
  const mailSettings = page.getByRole('region', { name: 'Email notifications' });
  await expect(mailSettings.getByText('This club does not send emails.')).toBeVisible();
  await expect(mailSettings.getByLabel('New events')).toBeChecked();
  await expect(mailSettings.getByLabel('New events')).toBeDisabled();
  await expect(mailSettings.getByLabel('Helpers wanted: 7 days before a shift with free places')).toBeChecked();
  await expect(mailSettings.getByLabel('Answer missing: reminder 2 days before an event and when the coach asks')).toBeChecked();
  // The public-list switch (OpenAPI 1.15.0, controller decision C5): hidden, then shown again.
  await show(page, '#/family/profile');
  const listing = page.getByRole('region', { name: 'My profile' }).getByLabel('Show my name on the public member list');
  await expect(listing).toBeChecked();
  // A controlled box: click, then wait for the saved state (uncheck() would expect an immediate change).
  await listing.click();
  await expect(listing).not.toBeChecked();
  await expect(page.getByRole('region', { name: 'My profile' }).getByRole('status')).toHaveText('Setting saved.');
  expect(await (await context.request.get('/api/v1/public/members')).json()).toEqual([]);
  await page.getByRole('region', { name: 'My profile' }).getByLabel('Show my name on the public member list').click();
  await expect(page.getByRole('region', { name: 'My profile' }).getByLabel('Show my name on the public member list')).toBeChecked();
  await expect.poll(async () => (await context.request.get('/api/v1/public/members')).json())
    .toEqual([{ displayName: 'Synthetic Administrator' }]);
  // Native required-field validation prevents an incomplete browser write.
  await show(page, '#/admin/members');
  await page.getByLabel('First name').fill('Incomplete');
  await page.getByLabel('Last name').fill('Synthetic');
  await page.getByRole('button', { name: 'Add member' }).click();
  expect(await page.getByLabel('Date of birth').evaluate(input => input.validity.valueMissing)).toBe(true);
  await expect(page.getByRole('listitem').filter({ hasText: 'Incomplete Synthetic' })).toHaveCount(0);

  await page.getByLabel('First name').fill('Nina');
  await page.getByLabel('Last name').fill('Private');
  await page.getByLabel('Date of birth').fill('2013-01-01');
  await page.getByRole('button', { name: 'Add member' }).click();
  // Scoped to the admin panel: the Archived members panel can list the same name.
  const created = page.getByRole('region', { name: 'Manage members' }).getByRole('listitem').filter({ hasText: 'Nina Private' });
  await expect(created).toBeVisible();
  // The public member list has its own page and shows the minor abbreviated.
  await show(page, '#/admin/members/public');
  await expect(page.getByRole('region', { name: 'Members', exact: true })
    .getByRole('listitem').filter({ hasText: 'Nina P.' })).toBeVisible();
  await show(page, '#/admin/members');
  // Anonymous API consumers must see only the abbreviated minor name.
  const minorPublicNames = await context.request.get('/api/v1/public/members');
  expect(minorPublicNames.status()).toBe(200);
  const visibleMinorNames = await minorPublicNames.json();
  expect(visibleMinorNames).toContainEqual({ displayName: 'Nina P.' });
  expect(JSON.stringify(visibleMinorNames)).not.toContain('Nina Private');

  // A slower prior query must be aborted rather than overwriting the latest search.
  let signalSlowRequest;
  const slowRequestStarted = new Promise(resolve => { signalSlowRequest = resolve; });
  await page.route('**/api/v1/members?**', async route => {
    if (new URL(route.request().url()).searchParams.get('q') !== 'Slow') {
      await route.continue();
      return;
    }
    signalSlowRequest();
    await new Promise(resolve => setTimeout(resolve, 500));
    await route.continue().catch(() => {}); // Superseded request was aborted.
  });
  const canceledSlow = page.waitForEvent('requestfailed',
    request => new URL(request.url()).searchParams.get('q') === 'Slow');
  const searchForm = page.getByRole('region', { name: 'Manage members' });
  await searchForm.getByLabel('Search names').fill('Slow');
  await searchForm.getByRole('button', { name: 'Search', exact: true }).click();
  await slowRequestStarted;
  await searchForm.getByLabel('Search names').fill('Nina');
  await searchForm.getByRole('button', { name: 'Search', exact: true }).click();
  await canceledSlow;
  await expect(searchForm.getByRole('listitem').filter({ hasText: 'Nina Private' })).toBeVisible();
  await page.unroute('**/api/v1/members?**');
  await searchForm.getByLabel('Search names').fill('');
  await searchForm.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(searchForm.getByRole('listitem').filter({ hasText: 'Nina Private' })).toBeVisible();
  // A failed search must show a retry action, not permanent loading text.
  await page.route('**/api/v1/members?**', route => route.abort(), { times: 1 });
  await searchForm.getByLabel('Search names').fill('Missing');
  await searchForm.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(searchForm.getByRole('alert')).toContainText('Member management unavailable.');
  await expect(searchForm.getByText('Loading members…')).toHaveCount(0);
  for (const locale of [
    { code: 'en', heading: 'Manage members', error: 'Member management unavailable.' },
  ]) {
    await expect(page.getByRole('region', { name: locale.heading }).getByRole('alert')).toContainText(locale.error);
  }
  await searchForm.getByRole('button', { name: 'Try again' }).click();
  await expect(searchForm.getByText('No members found.')).toBeVisible();
  await searchForm.getByLabel('Search names').fill('');
  await searchForm.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(searchForm.getByRole('listitem').filter({ hasText: 'Nina Private' })).toBeVisible();

  await created.getByRole('button', { name: 'Edit' }).click();
  // The row text is replaced by editable inputs, so locate the active form. It is filled from the
  // restricted detail read (OpenAPI 1.4.0 full replacement), so the save never clears unseen fields.
  const editForm = page.locator('li form').filter({ has: page.getByRole('button', { name: 'Save' }) });
  await expect(editForm.getByLabel('Date of birth')).toHaveValue('2013-01-01');
  await editForm.getByLabel('Last name').fill('Renamed');
  await editForm.getByLabel('Phone').fill('+41000000077');
  const replaceResponse = page.waitForResponse(response => response.request().method() === 'PUT');
  await editForm.getByRole('button', { name: 'Save' }).click();
  expect((await replaceResponse).status()).toBe(204);
  const renamed = page.getByRole('region', { name: 'Manage members' }).getByRole('listitem').filter({ hasText: 'Nina Renamed' });
  await expect(renamed).toBeVisible();
  await renamed.getByRole('button', { name: 'Details', exact: true }).click();
  await expect(renamed.getByRole('region', { name: 'Details: Nina Renamed' }).getByText('+41000000077')).toBeVisible();
  await renamed.getByRole('button', { name: 'Close' }).click();
  // Turning a minor into an adult without email is refused by the server; nothing changes.
  await renamed.getByRole('button', { name: 'Edit' }).click();
  await editForm.getByLabel('Date of birth').fill('1990-01-01');
  await editForm.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('region', { name: 'Manage members' }).getByRole('status')
    .filter({ hasText: 'Please check the fields' })).toBeVisible();
  // The edit sent the version of its read (OpenAPI 1.8.0). When someone changed the member since, the server
  // answers 412 (covered against the real database in MemberReplaceAndReactivateHttpTest); the form closes,
  // nothing is saved and the editor is told to edit again.
  let ifMatch = null;
  await page.route('**/api/v1/members/*', route => {
    if (route.request().method() !== 'PUT') return route.fallback();
    ifMatch = route.request().headers()['if-match'];
    return route.fulfill({ status: 412, contentType: 'application/json', body: '{"code":"changed","message":"Request rejected"}' });
  });
  await editForm.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('region', { name: 'Manage members' }).getByRole('status')
    .filter({ hasText: 'someone changed this member in the meantime' })).toBeVisible();
  expect(ifMatch).toMatch(/^"[1-9][0-9]*"$/);
  await expect(editForm).toHaveCount(0);
  await page.unroute('**/api/v1/members/*');
  // Archive, undo (reactivation restores the member, without former rights), and archive again below.
  await renamed.getByRole('button', { name: 'Archive' }).click();
  await renamed.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(renamed).toHaveCount(0);
  const memberAdminRegion = page.getByRole('region', { name: 'Manage members' });
  await expect(memberAdminRegion.getByText('Member archived.')).toBeVisible();
  await memberAdminRegion.getByRole('button', { name: 'Undo archive' }).click();
  await expect(memberAdminRegion.getByText(/Member reactivated/)).toBeVisible();
  await expect(renamed).toBeVisible();
  const renamedPublicNames = await context.request.get('/api/v1/public/members');
  expect(renamedPublicNames.status()).toBe(200);
  const renamedDisplays = await renamedPublicNames.json();
  expect(renamedDisplays).toContainEqual({ displayName: 'Nina R.' });
  expect(renamedDisplays).not.toContainEqual({ displayName: 'Nina P.' });
  expect(JSON.stringify(renamedDisplays)).not.toContain('Nina Renamed');
  await renamed.getByRole('button', { name: 'Archive' }).click();
  await expect(renamed.getByRole('group', {
    name: 'Archive this member? Nina Renamed',
  })).toBeVisible();
  await expect(renamed.getByText('Archive this member?')).toBeVisible();
  // The named confirmation group must be operable without a pointer.
  await renamed.getByRole('button', { name: 'Confirm archive' }).focus();
  await page.keyboard.press('Tab');
  await expect(renamed.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(renamed.getByRole('group', {
    name: 'Archive this member? Nina Renamed',
  })).toHaveCount(0);
  await expect(renamed).toBeVisible();
  await renamed.getByRole('button', { name: 'Archive' }).click();
  await renamed.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(renamed).toHaveCount(0);
  await show(page, '#/admin/members/public');
  await expect(page.getByRole('region', { name: 'Members', exact: true })
    .getByRole('listitem').filter({ hasText: 'Nina R.' })).toHaveCount(0);
  // Archived minors must disappear from the anonymous API, not only the UI.
  const archivedPublicNames = await context.request.get('/api/v1/public/members');
  expect(archivedPublicNames.status()).toBe(200);
  expect(await archivedPublicNames.json()).not.toContainEqual({ displayName: 'Nina R.' });
  // Archived members (OpenAPI 1.5.0): an editor finds the member later and reactivates them; no erase button.
  // The member list links each further task to its own page.
  await show(page, '#/admin/members');
  await page.getByRole('navigation', { name: 'More tasks' }).getByRole('link', { name: 'Archive', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Archive' })).toBeVisible();
  const archivedRegion = page.getByRole('region', { name: 'Archived members' });
  await archivedRegion.getByLabel('Search names').fill('Nina');
  const archivedSearch = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/members/archived');
  await archivedRegion.getByRole('button', { name: 'Search', exact: true }).click();
  expect((await archivedSearch).headers()['cache-control']).toBe('no-store');
  const archivedNina = archivedRegion.getByRole('listitem').filter({ hasText: 'Nina Renamed' });
  await expect(archivedNina).toContainText('archived on');
  await expect(archivedRegion.getByRole('button', { name: /erase/i })).toHaveCount(0);
  await archivedNina.getByRole('button', { name: 'Reactivate' }).click();
  await expect(archivedRegion.getByText(/Member reactivated/)).toBeVisible();
  await expect(archivedNina).toHaveCount(0);
  await page.getByRole('link', { name: '← Members' }).click();
  const backInList = page.getByRole('region', { name: 'Manage members' }).getByRole('listitem').filter({ hasText: 'Nina Renamed' });
  await page.getByRole('region', { name: 'Manage members' }).getByRole('button', { name: 'Search', exact: true }).click();
  await expect(backInList).toBeVisible();
  // Archive her again so the rest of the journey sees the same state as before.
  await backInList.getByRole('button', { name: 'Archive' }).click();
  await backInList.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(backInList).toHaveCount(0);
  await show(page, '#/admin/members/archived');
  await archivedRegion.getByLabel('Search names').fill('Nina');
  await archivedRegion.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(archivedNina).toBeVisible();
  await show(page, '#/admin/members');

  // Adult contacts are enforced by the server, not merely by browser hints.
  const memberAdminForAdult = page.getByRole('region', { name: 'Manage members' });
  await memberAdminForAdult.getByLabel('First name').fill('Adult');
  await memberAdminForAdult.getByLabel('Last name').fill('Synthetic');
  await memberAdminForAdult.getByLabel('Date of birth').fill('1990-01-01');
  await memberAdminForAdult.getByRole('button', { name: 'Add member' }).click();
  await expect(memberAdminForAdult.getByRole('alert')).toContainText('Action failed.');
  await expect(memberAdminForAdult.getByRole('listitem').filter({ hasText: 'Adult Synthetic' })).toHaveCount(0);
  await memberAdminForAdult.getByLabel('Email').fill('adult@example.invalid');
  await memberAdminForAdult.getByLabel('Phone').fill('+41000000000');
  await memberAdminForAdult.getByRole('button', { name: 'Add member' }).click();
  const completeAdult = memberAdminForAdult.getByRole('listitem').filter({ hasText: 'Adult Synthetic' });
  await expect(completeAdult).toBeVisible();
  await completeAdult.getByRole('button', { name: 'Archive' }).click();
  await completeAdult.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(completeAdult).toHaveCount(0);


  // Canceling a member-specific archive must work.
  await memberAdminForAdult.getByLabel('First name').fill('Locale');
  await memberAdminForAdult.getByLabel('Last name').fill('Synthetic');
  await memberAdminForAdult.getByLabel('Date of birth').fill('2015-01-01');
  await memberAdminForAdult.getByRole('button', { name: 'Add member' }).click();
  for (const locale of [
    ['en', 'Manage members', 'Archive', 'Archive this member?', 'Cancel', 'Adults must provide both email and phone; both are optional for minors.'],
  ]) {
    const form = page.getByRole('region', { name: locale[1] });
    await expect(form.getByText(locale[5])).toBeVisible();
    const row = page.getByRole('region', { name: locale[1] })
      .getByRole('listitem').filter({ hasText: 'Locale Synthetic' });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: locale[2], exact: true }).click();
    await expect(row.getByRole('group', {
      name: locale[3] + ' Locale Synthetic',
    })).toBeVisible();
    await expect(row.getByText(locale[3])).toBeVisible();
    await row.getByRole('button', { name: locale[4] }).click();
    await expect(row).toBeVisible();
  }
  const localeRow = page.getByRole('region', { name: 'Manage members' })
    .getByRole('listitem').filter({ hasText: 'Locale Synthetic' });
  await localeRow.getByRole('button', { name: 'Archive', exact: true }).click();
  await localeRow.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(localeRow).toHaveCount(0);

  // One Keycloak-backed browser journey now also verifies team and grant workflows.
  await page.getByLabel('First name').fill('Mara');
  await page.getByLabel('Last name').fill('Demo');
  await page.getByLabel('Date of birth').fill('2015-01-01');
  await page.getByLabel('Player', { exact: true }).check();
  await page.getByLabel('Gender (free text)').fill('Synthetic test value');
  await page.getByRole('button', { name: 'Add member' }).click();
  const memberNames = await context.request.get('/api/v1/members?q=Mara');
  expect(memberNames.status()).toBe(200);
  expect(JSON.stringify(await memberNames.json())).not.toContain('Synthetic test value');
  const memberAdmin = page.getByRole('region', { name: 'Manage members' });
  await expect(memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toBeVisible();
  // The restricted detail read (OpenAPI 1.3.0) shows private fields only on request and is never cached.
  const maraRow = memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' });
  const detailsResponse = page.waitForResponse(response => /\/api\/v1\/members\/[^/]+\/details$/.test(new URL(response.url()).pathname));
  await maraRow.getByRole('button', { name: 'Details', exact: true }).click();
  expect((await detailsResponse).headers()['cache-control']).toBe('no-store');
  const maraDetails = maraRow.getByRole('region', { name: 'Details: Mara Demo' });
  await expect(maraDetails.getByText('Synthetic test value')).toBeVisible();
  await expect(maraDetails.getByText('2015-01-01')).toBeVisible();
  await expect(maraDetails.getByText('not set').first()).toBeVisible();
  await expect(maraRow.getByRole('button', { name: 'Details', exact: true })).toHaveAttribute('aria-expanded', 'true');
  // Export (OpenAPI 1.7.0): the editor downloads everything stored about Mara as a JSON attachment.
  const download = page.waitForEvent('download');
  await maraDetails.getByRole('link', { name: 'Export all data (JSON)' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^member-[0-9a-f-]{36}\.json$/);
  const exported = JSON.parse(readFileSync(await file.path(), 'utf8'));
  expect(Object.keys(exported).sort()).toEqual(['absences', 'attendance', 'clubGrants', 'exportedAt', 'fees', 'guardianships', 'invitations', 'member', 'presence', 'rides', 'shifts', 'squads', 'teamGrants', 'teams']);
  expect(exported.member.firstName).toBe('Mara');
  expect(exported.member).not.toHaveProperty('accountId');
  await maraDetails.getByRole('button', { name: 'Close' }).click();
  await expect(maraDetails).toHaveCount(0);

  // Guardians (OpenAPI 1.6.0): the editor attests a guardianship, the guardian sees "My children", reads
  // the minor and replaces only her contacts; removing the guardianship ends access at the next request.
  await show(page, '#/admin/members/guardians');
  const guardiansRegion = page.getByRole('region', { name: 'Guardians' });
  await guardiansRegion.getByLabel('Find a minor').fill('Mara');
  await guardiansRegion.getByRole('button', { name: 'Search' }).first().click();
  await expect(guardiansRegion.getByText('No guardians recorded.')).toBeVisible();
  await guardiansRegion.getByLabel('Find a guardian').fill('Synthetic Administrator');
  await guardiansRegion.getByRole('button', { name: 'Search' }).nth(1).click();
  await expect(guardiansRegion.getByRole('combobox', { name: 'Guardian' })).toContainText('Synthetic Administrator');
  await guardiansRegion.getByRole('button', { name: 'Add as guardian' }).click();
  await expect(guardiansRegion.getByText('Guardian added.')).toBeVisible();
  await expect(guardiansRegion.getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
  // Adding the same guardian twice is refused with the rule, not an error.
  await guardiansRegion.getByRole('button', { name: 'Add as guardian' }).click();
  await expect(guardiansRegion.getByText(/Not possible/)).toBeVisible();
  await page.reload();
  await show(page, '#/family/children');
  const children = page.getByRole('region', { name: 'My children' });
  const maraChild = children.getByRole('listitem').filter({ hasText: 'Mara Demo' });
  const dependentRead = page.waitForResponse(response => /\/api\/v1\/me\/dependents\/[^/]+$/.test(new URL(response.url()).pathname));
  await maraChild.getByRole('button', { name: 'Open' }).click();
  expect((await dependentRead).headers()['cache-control']).toBe('no-store');
  await expect(maraChild.getByText('2015-01-01')).toBeVisible();
  await maraChild.getByLabel('Email').fill('mara.parent@example.invalid');
  await maraChild.getByRole('button', { name: 'Save contacts' }).click();
  await expect(children.getByText('Contacts saved.')).toBeVisible();
  await expect(maraChild.getByLabel('Email')).toHaveValue('mara.parent@example.invalid');
  await show(page, '#/admin/members/guardians');
  const guardianRegion = page.getByRole('region', { name: 'Guardians' });
  await guardianRegion.getByLabel('Find a minor').fill('Mara');
  await guardianRegion.getByRole('button', { name: 'Search' }).first().click();
  await guardianRegion.getByRole('listitem').filter({ hasText: 'Synthetic Administrator' }).getByRole('button', { name: 'Remove' }).click();
  await expect(guardianRegion.getByText('Guardian removed.')).toBeVisible();
  await page.reload();
  await show(page, '#/family');
  await expect(page.getByRole('heading', { level: 1, name: 'Family' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'My children' })).toHaveCount(0);
  // The overview offers no My children entry once the guardianship has ended.
  await expect(page.getByRole('main').getByRole('link', { name: /My children/ })).toHaveCount(0);
  await expect(page.getByRole('main').getByRole('link', { name: /My profile/ })).toBeVisible();
  const mineAfter = await context.request.get('/api/v1/me/dependents');
  expect((await mineAfter.json()).items).toEqual([]);
  await show(page, '#/admin/settings');
  await clubSettings.getByLabel('Require gender for players').check();
  await clubSettings.getByRole('button', { name: 'Save policy' }).click();
  await expect.poll(async () => (await (await context.request.get('/api/v1/club')).json()).genderRequiredForPlayers).toBe(true);

  // ADR-0007: the adult age is configurable per club and shown in the member form.
  // Raising it can never conflict with existing members' contacts.
  const adultAge = clubSettings.getByLabel('Adult from age (13–25)');
  await expect(adultAge).toHaveValue('18');
  await adultAge.fill('20');
  await clubSettings.getByRole('button', { name: 'Save adult age' }).click();
  await expect.poll(async () => (await (await context.request.get('/api/v1/club')).json()).adultAgeYears).toBe(20);
  await show(page, '#/admin/members');
  await expect(memberAdmin.getByText('Adult from age 20.')).toBeVisible();
  await show(page, '#/admin/settings');
  await adultAge.fill('18');
  await clubSettings.getByRole('button', { name: 'Save adult age' }).click();
  await expect.poll(async () => (await (await context.request.get('/api/v1/club')).json()).adultAgeYears).toBe(18);
  await show(page, '#/admin/members');
  await expect(memberAdmin.getByText('Adult from age 18.')).toBeVisible();

  // When the club enables this rule, player creation cannot omit gender.
  await memberAdmin.getByLabel('First name').fill('Player');
  await memberAdmin.getByLabel('Last name').fill('Missing');
  await memberAdmin.getByLabel('Date of birth').fill('2014-01-01');
  await memberAdmin.getByLabel('Player', { exact: true }).check();
  await expect(memberAdmin.getByLabel('Gender (free text)')).toHaveAttribute('required', '');
  await memberAdmin.getByRole('button', { name: 'Add member' }).click();
  await expect(memberAdmin.getByRole('listitem').filter({ hasText: 'Player Missing' })).toHaveCount(0);
  await memberAdmin.getByLabel('Player', { exact: true }).uncheck();


  await show(page, '#/admin/members/roles');
  const roleAdmin = page.getByRole('region', { name: 'Manage club roles' });
  // A previous role/member lookup cannot replace the latest selected member.
  let notifySlowRole;
  let notifySlowRoleDone;
  const slowRoleStarted = new Promise(resolve => { notifySlowRole = resolve; });
  const slowRoleDone = new Promise(resolve => { notifySlowRoleDone = resolve; });
  const newerRoleSearch = page.waitForRequest(request =>
    new URL(request.url()).searchParams.get('q') === 'Mara', { timeout: 30_000 }).catch(() => {});
  await page.route('**/api/v1/members?**', async route => {
    if (new URL(route.request().url()).searchParams.get('q') !== 'Slow') {
      await route.continue();
      return;
    }
    notifySlowRole();
    // Hold the slow answer until the newer search has been sent (a fixed delay raced on slow runners).
    await Promise.race([newerRoleSearch, new Promise(resolve => setTimeout(resolve, 10_000))]);
    await route.continue().catch(() => {});
    notifySlowRoleDone();
  });
  await roleAdmin.getByLabel('Find member').fill('Slow');
  await roleAdmin.getByRole('button', { name: 'Search' }).click();
  await slowRoleStarted;
  // Record the selected member's role lookup so an intermittent failure reports
  // its HTTP outcome instead of a generic timeout on a disabled control.
  const maraRoleRead = page.waitForResponse(response =>
    response.request().method() === 'GET' &&
    /\/api\/v1\/members\/[^/]+\/role$/.test(new URL(response.url()).pathname),
  { timeout: 30_000 });
  await roleAdmin.getByLabel('Find member').fill('Mara');
  await roleAdmin.getByRole('button', { name: 'Search' }).click();
  // Once the older answer has been let through, the newer search's member is still the one shown.
  await slowRoleDone;
  await page.unroute('**/api/v1/members?**');
  await expect(roleAdmin.getByRole('combobox', { name: 'Member' })
    .getByRole('option', { name: 'Mara Demo' })).toHaveCount(1);
  await expect(roleAdmin.getByText('No members found.')).toHaveCount(0);
  const roleRead = await maraRoleRead;
  expect(roleRead.status(), `role lookup ${new URL(roleRead.url()).pathname}`).toBe(200);
  await expect(roleAdmin.getByRole('combobox', { name: 'Role' })).toBeEnabled();
  await expect(roleAdmin.getByRole('combobox', { name: 'Role' })).toHaveValue('MEMBER');
  await roleAdmin.getByRole('combobox', { name: 'Role' }).selectOption('ADMIN');
  await roleAdmin.getByRole('button', { name: 'Save role' }).click();
  await expect(roleAdmin.getByRole('combobox', { name: 'Role' })).toHaveValue('ADMIN');
  await roleAdmin.getByRole('combobox', { name: 'Role' }).selectOption('MEMBER');
  await roleAdmin.getByRole('button', { name: 'Save role' }).click();
  await expect(roleAdmin.getByRole('combobox', { name: 'Role' })).toHaveValue('MEMBER');
  // Permission loss during a role write hides the privileged editor immediately.
  await page.route('**/api/v1/members/*/role', route =>
    route.request().method() === 'PATCH'
      ? route.fulfill({ status: 403, body: 'Forbidden' }) : route.continue());
  await roleAdmin.getByRole('combobox', { name: 'Role' }).selectOption('ADMIN');
  await roleAdmin.getByRole('button', { name: 'Save role' }).click();
  await expect(roleAdmin).toHaveCount(0);
  await page.unroute('**/api/v1/members/*/role');


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
  await expect(roster.getByRole('button', { name: 'Remove' })).toBeVisible();
  await expect(roster.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toHaveCount(1);
  // Contact details open from the roster; a 403 from the server is shown as a denial, never as data.
  await roster.getByRole('button', { name: 'Contact details' }).click();
  await expect(roster.getByRole('region', { name: 'Details: Mara Demo' })).toBeVisible();
  await roster.getByRole('button', { name: 'Close' }).click();
  // Not `times: 1`: StrictMode runs the effect twice in development, and the first request is aborted.
  await page.route('**/api/v1/members/*/details', route => route.fulfill({
    status: 403, contentType: 'application/json', body: '{"code":"forbidden"}' }));
  await roster.getByRole('button', { name: 'Contact details' }).click();
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
  await roster.getByRole('button', { name: 'Remove' }).click();
  await oldRosterStarted;
  await roster.getByRole('button', { name: 'Assign' }).click();
  await canceledOldRoster;
  await expect(roster.getByRole('button', { name: 'Remove' })).toBeVisible();
  await page.unroute('**/api/v1/teams/*/members?**');
  // The team list counts the assignment.
  await show(page, '#/team');
  await expect(juniorsRow).toContainText('1 member');

  // Trainings and events (OpenAPI 1.10.0): a weekly training for Juniors in the browser's time zone; one copy is
  // cancelled and one deleted. The administrator is in no team, so their own schedule stays empty.
  const [firstDay, lastDay] = await page.evaluate(() => [2, 16].map(days => {
    const day = new Date(Date.now() + days * 86400000);
    return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
  }));
  await juniorsRow.getByRole('link').click();
  await teamParts.getByRole('link', { name: /^Schedule/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Schedule', exact: true })).toBeVisible();
  const schedule = page.getByRole('region', { name: 'Schedule Juniors' });
  await expect(schedule.getByText('No events in the next eight weeks.')).toBeVisible();
  await schedule.getByLabel('Title').fill('Juniors training');
  await schedule.getByLabel('Date', { exact: true }).fill(firstDay);
  await schedule.getByLabel('Place', { exact: true }).fill('Hall 1');
  await schedule.getByLabel('Repeat weekly until').fill(lastDay);
  await schedule.getByRole('button', { name: 'Add event' }).click();
  await expect(schedule.getByRole('status')).toHaveText('Event saved.');
  const trainings = schedule.getByRole('listitem').filter({ hasText: 'Juniors training' });
  await expect(trainings).toHaveCount(3);
  await expect(trainings.first()).toContainText('18:00');
  await trainings.first().getByRole('button', { name: 'Cancel event' }).click();
  await expect(trainings.first()).toContainText('Cancelled');
  await trainings.last().getByRole('button', { name: 'Delete', exact: true }).click();
  await trainings.last().getByRole('button', { name: 'Confirm delete' }).click();
  await expect(trainings).toHaveCount(2);
  // Attendance (OpenAPI 1.11.0): the team's viewers and managers see each member's answer by public display name,
  // on the event's own coach page, which the schedule row links to.
  await expect(trainings.last()).toContainText('Answers: 0 Yes, 0 No, 0 Maybe');
  await trainings.last().getByRole('link').click();
  await expect(page).toHaveURL(/#\/team\/[0-9a-f-]{36}\/event\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Juniors training' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'No answer yet', exact: true })).toContainText('Mara D.');
  await expect(page.getByRole('region', { name: 'Yes', exact: true })).toContainText('Nobody.');
  // Volunteer shifts (OpenAPI 1.16.0): the manager adds a shift with the event's times as defaults and changes it.
  const shifts = page.getByRole('region', { name: 'Volunteer shifts: Juniors training' });
  await expect(shifts.getByText('No volunteer shifts.')).toBeVisible();
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
  await page.getByRole('button', { name: 'Remind all 1 without an answer' }).click();
  await expect(page.getByRole('alert')).toHaveText('This club does not send emails.');
  await expect(page.getByRole('region', { name: 'Volunteer shifts: Juniors training' })).toContainText('Bar');
  await show(page, juniorsPage);
  await teamParts.getByRole('link', { name: /^Statistics/ }).click();
  // Attendance statistics (OpenAPI 1.18.0): the last 12 months by default; the new trainings lie ahead, so none held.
  const statistics = page.getByRole('region', { name: 'Attendance statistics Juniors' });
  await expect(statistics.getByText('Events held: 0')).toBeVisible();
  await expect(statistics.getByRole('row', { name: /Mara D\./ })).toContainText('0');
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
  // Absences (OpenAPI 1.20.0): enter one for today, see it listed, delete it again.
  await show(page, '#/family/absences');
  const absences = page.getByRole('region', { name: 'Absences' });
  await expect(absences.getByText('No absences.')).toBeVisible();
  // The server checks dates in the club's time zone, so use today's Swiss date (the runner's clock is UTC).
  const todayLocal = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Zurich' }).format(new Date());
  await absences.getByLabel('From').fill(todayLocal);
  await absences.getByRole('button', { name: 'Add absence' }).click();
  await expect(absences.getByRole('listitem')).toHaveText(`Me: ${todayLocal} Delete`);
  await absences.getByRole('button', { name: 'Delete' }).click();
  await expect(absences.getByText('No absences.')).toBeVisible();
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

  // Membership fees (OpenAPI 1.12.0): the first administrator holds MANAGE_FEES from the bootstrap. A new period
  // includes every active member; marking Mara's fee paid updates the totals; the administrator sees their own fee.
  await show(page, '#/admin/fees');
  const feeAdmin = page.getByRole('region', { name: 'Membership fees' });
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

  // Fields and halls (OpenAPI 1.30.0): the first administrator holds MANAGE_VENUES, adds one, and every member sees
  // it under Schedule.
  await show(page, '#/admin/venues');
  await expect(page.getByRole('heading', { level: 1, name: 'Fields and halls' })).toBeVisible();
  const venueAdmin = page.getByRole('region', { name: 'Fields and halls' });
  await venueAdmin.getByLabel('Name').last().fill('Synthetic Hall North');
  await venueAdmin.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(venueAdmin.getByText('Synthetic Hall North', { exact: true })).toBeVisible();
  await show(page, '#/schedule/venues');
  await expect(page.getByRole('heading', { level: 1, name: 'Fields and halls' })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Field or hall' })).toHaveValue(/.+/);
  await expect(page.getByText('Not booked in the next 14 days.')).toBeVisible();
  // Announcements (OpenAPI 1.14.0): the first administrator holds POST_ANNOUNCEMENTS from the bootstrap and posts to
  // the whole club; the text keeps its line breaks and is never interpreted as HTML.
  await show(page, '#/');
  const news = page.getByRole('region', { name: 'Announcements' });
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

  await show(page, '#/admin/permissions');
  const permissionAdmin = page.getByRole('region', { name: 'Manage permissions' });
  // Superseded permission-member lookups must not replace newer selection/grants.
  let notifySlowGrant;
  const slowGrantStarted = new Promise(resolve => { notifySlowGrant = resolve; });
  await page.route('**/api/v1/members?**', async route => {
    if (new URL(route.request().url()).searchParams.get('q') !== 'Slow') {
      await route.continue();
      return;
    }
    notifySlowGrant();
    await new Promise(resolve => setTimeout(resolve, 500));
    await route.continue().catch(() => {});
  });
  const canceledGrantSearch = page.waitForEvent('requestfailed',
    request => new URL(request.url()).searchParams.get('q') === 'Slow');
  await permissionAdmin.getByLabel('Find member').fill('Slow');
  await permissionAdmin.getByRole('button', { name: 'Search' }).click();
  await slowGrantStarted;
  await permissionAdmin.getByLabel('Find member').fill('Mara');
  await permissionAdmin.getByRole('button', { name: 'Search' }).click();
  await canceledGrantSearch;
  await expect(permissionAdmin.getByRole('option', { name: 'Mara Demo' })).toHaveCount(1);
  await page.unroute('**/api/v1/members?**');
  await expect(permissionAdmin.getByText('No grants.')).toBeVisible();
  await permissionAdmin.getByLabel('Capability').selectOption('VIEW_TEAMS');
  await permissionAdmin.getByRole('button', { name: 'Grant' }).click();
  await expect(permissionAdmin.getByRole('listitem').filter({ hasText: 'VIEW_TEAMS' })).toBeVisible();
  await permissionAdmin.getByRole('button', { name: 'Revoke' }).click();
  await expect(permissionAdmin.getByText('No grants.')).toBeVisible();
  // Switching grant targets must cancel the previous member's pending grant read.
  await permissionAdmin.getByLabel('Find member').fill('a');
  await permissionAdmin.getByRole('button', { name: 'Search' }).click();
  const membersPicker = permissionAdmin.getByRole('combobox', { name: 'Member' });
  const adminOption = membersPicker.getByRole('option', { name: 'Synthetic Administrator' });
  await expect(adminOption).toHaveCount(1);
  const maraOption = membersPicker.getByRole('option', { name: 'Mara Demo' });
  await expect(maraOption).toHaveCount(1);
  const maraId = await maraOption.getAttribute('value');
  if (!maraId) throw new Error('Missing synthetic member ID');
  const adminId = await adminOption.getAttribute('value');
  if (!adminId) throw new Error('Missing synthetic admin member ID');
  await membersPicker.selectOption({ label: 'Mara Demo' });
  await expect(permissionAdmin.getByText('No grants.')).toBeVisible();
  let notifyOldGrant;
  const oldGrantStarted = new Promise(resolve => { notifyOldGrant = resolve; });
  await page.route('**/api/v1/grants?**', async route => {
    if (route.request().method() !== 'GET' ||
        new URL(route.request().url()).searchParams.get('memberId') !== adminId) {
      await route.continue();
      return;
    }
    notifyOldGrant();
    await new Promise(resolve => setTimeout(resolve, 500));
    await route.continue().catch(() => {});
  });
  const canceledOldGrant = page.waitForEvent('requestfailed',
    request => new URL(request.url()).searchParams.get('memberId') === adminId);
  await membersPicker.selectOption({ label: 'Synthetic Administrator' });
  await oldGrantStarted;
  await membersPicker.selectOption({ label: 'Mara Demo' });
  await canceledOldGrant;
  await expect(membersPicker).toHaveValue(maraId);
  await expect(permissionAdmin.getByText('No grants.')).toBeVisible();
  await expect(permissionAdmin.getByRole('alert')).toHaveCount(0);
  await page.unroute('**/api/v1/grants?**');
  await show(page, juniorsPage + '/members');
  await roster.getByRole('button', { name: 'Remove' }).click();
  await expect(roster.getByText('No assignments.')).toBeVisible();
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
  await teams.getByLabel('Team name').fill('Transient Team');
  await teams.getByRole('button', { name: 'Add team' }).click();
  await expect.poll(() => failedTeamList).toBe(true);
  await expect(teams.getByRole('alert').filter({ hasText: 'Teams unavailable.' })).toBeVisible();
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
  // The whole authenticated workflow remains reachable with localized
  // member, team and delegation controls, not just a translated landing page.
  for (const locale of [
    { code: 'de', member: 'Mitglieder verwalten', team: 'Teams', grant: 'Berechtigungen verwalten', search: 'Name suchen', find: 'Suchen' },
    { code: 'fr', member: 'Gérer les membres', team: 'Équipes', grant: 'Gérer les autorisations', search: 'Rechercher un nom', find: 'Rechercher' },
    { code: 'it', member: 'Gestisci membri', team: 'Squadre', grant: 'Gestisci permessi', search: 'Cerca nomi', find: 'Cerca' },
    { code: 'en', member: 'Manage members', team: 'Teams', grant: 'Manage permissions', search: 'Search names', find: 'Search' },
  ]) {
    await chooseLanguage(page, locale.code);
    await show(page, '#/team');
    await expect(page.getByRole('region', { name: locale.team, exact: true })).toBeVisible();
    await show(page, '#/admin/permissions');
    await expect(page.getByRole('region', { name: locale.grant, exact: true })).toBeVisible();
    await show(page, '#/admin/members');
    await expect(page.getByRole('heading', { name: locale.member })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', locale.code);
    // Real Chromium keyboard semantics in each locale: visible label,
    // predictable Tab order and Enter-submitted member search.
    const names = page.getByRole('region', { name: locale.member });
    await names.getByLabel(locale.search).focus();
    await page.keyboard.press('Tab');
    const submit = names.getByRole('button', { name: locale.find, exact: true });
    await expect(submit).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(names.getByLabel(locale.search)).toBeVisible();
  }

  // Policy conflicts are shown next to the setting (the language is chosen in My profile, so it cannot change meanwhile).
  await show(page, '#/admin/settings');
  const settings = page.getByRole('region', { name: 'Edit club name' });
  await settings.getByLabel('Require gender for players').uncheck();
  await settings.getByRole('button', { name: 'Save policy' }).click();
  await settings.getByLabel('Require gender for players').check();
  await page.route('**/api/v1/club/player-gender-policy', async route => {
    if (route.request().method() === 'PATCH') {
      await route.fulfill({ status: 409, body: 'Conflict' });
    } else {
      await route.continue();
    }
  });
  await settings.getByRole('button', { name: 'Save policy' }).click();
  for (const locale of [
    { code: 'en', setting: 'Edit club name', error: 'Existing players without gender must be updated first.' },
  ]) {
    await expect(page.getByRole('region', { name: locale.setting }).getByRole('alert')).toContainText(locale.error);
  }
  await page.unroute('**/api/v1/club/player-gender-policy');
  await settings.getByRole('button', { name: 'Save policy' }).click();
  await expect(settings.getByRole('alert')).toHaveCount(0);

  // A transient policy failure must also follow the active language and recover.
  await settings.getByLabel('Require gender for players').uncheck();
  await page.route('**/api/v1/club/player-gender-policy', route =>
    route.request().method() === 'PATCH'
      ? route.fulfill({ status: 503, body: 'Unavailable' }) : route.continue());
  await settings.getByRole('button', { name: 'Save policy' }).click();
  for (const locale of [
    { code: 'en', setting: 'Edit club name', error: 'Could not save the player policy.' },
  ]) {
    await expect(page.getByRole('region', { name: locale.setting }).getByRole('alert')).toContainText(locale.error);
  }
  await page.unroute('**/api/v1/club/player-gender-policy');
  await settings.getByRole('button', { name: 'Save policy' }).click();
  await expect(settings.getByRole('alert')).toHaveCount(0);

  // Revoked member-directory access must hide names and controls in an existing roster.
  await show(page, juniorsPage + '/members');
  await expect(roster.getByText('No assignments.')).toBeVisible();
  await page.route('**/api/v1/members?**', route => route.fulfill({ status: 403, body: 'Forbidden' }), { times: 1 });
  await roster.getByLabel('Find member').fill('Mara');
  await roster.getByRole('button', { name: 'Search' }).click();
  await expect(roster).toHaveCount(0);
  await expect(page.getByLabel('Find member')).toHaveCount(0);
  await expect(page.getByText('You cannot see the members of this team.')).toBeVisible();

  await show(page, '#/admin/members');
  await memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' })
    .getByRole('button', { name: 'Archive' }).click();
  await memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' })
    .getByRole('button', { name: 'Confirm archive' }).click();
  await expect(memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toHaveCount(0);

  // A transient permission-init failure must offer a recoverable localized retry.
  await show(page, '#/admin/permissions');
  await page.route('**/api/v1/grants?**', async route => {
    if (route.request().method() === 'GET' &&
        new URL(route.request().url()).searchParams.get('memberId') === '00000000-0000-0000-0000-000000000000') {
      await route.fulfill({ status: 503, body: 'Unavailable' });
    } else {
      await route.continue();
    }
  });
  await page.reload();
  const grantRecovery = page.getByRole('region', { name: 'Manage permissions' });
  await expect(grantRecovery.getByRole('alert')).toContainText('Permissions unavailable.');
  await page.unroute('**/api/v1/grants?**');
  await grantRecovery.getByRole('button', { name: 'Try again' }).click();
  await expect(grantRecovery.getByRole('heading', { name: 'Manage permissions' })).toBeVisible();
  await expect(grantRecovery.getByRole('button', { name: 'Search' })).toBeEnabled();
  await expect(grantRecovery.getByRole('alert')).toHaveCount(0);

  // A revoked grant-management permission must hide the editor after a denied write.
  await grantRecovery.getByLabel('Find member').fill('Synthetic');
  await grantRecovery.getByRole('button', { name: 'Search' }).click();
  await expect(grantRecovery.getByRole('option', { name: 'Synthetic Administrator' })).toHaveCount(1);
  await page.route('**/api/v1/grants', route =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 403, body: 'Forbidden' }) : route.continue());
  await grantRecovery.getByRole('button', { name: 'Grant' }).click();
  await expect(grantRecovery).toHaveCount(0);
  await page.unroute('**/api/v1/grants');
  await page.reload();
  await expect(page.getByRole('region', { name: 'Manage permissions' })).toBeVisible();


  // Exercise real database authorization during an existing Keycloak login.
  const fixtureSql = (sql) => {
    if (process.env.OPENCLUB_E2E_DISPOSABLE !== 'true') {
      throw new Error('This fixture requires disposable synthetic data');
    }
    return String(execFileSync('docker', [
      'compose', '-f', 'compose.yaml', '-f', 'compose.auth.dev.yaml',
      'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-t', '-A',
      '-U', 'openclub', '-d', 'openclub', '-c', sql,
    ], { cwd: fileURLToPath(new URL('../../', import.meta.url)), stdio: 'pipe' })).trim();
  };
  const fixtureGrant = (verb, capability = 'EDIT_MEMBERS') => {
    if (!['EDIT_MEMBERS', 'VIEW_MEMBER_DIRECTORY'].includes(capability)) {
      throw new Error('Unrecognized synthetic fixture capability');
    }
    const command = verb === 'remove'
      ? "DELETE FROM club_capability_grants WHERE member_id = " +
          "(SELECT id FROM members WHERE account_id = '" + accountId + "') " +
          "AND capability = '" + capability + "'"
      : "INSERT INTO club_capability_grants (member_id, capability) " +
          "SELECT id, '" + capability + "' FROM members WHERE account_id = '" + accountId + "' " +
          "ON CONFLICT DO NOTHING";
    fixtureSql(command);
  };
  await show(page, '#/admin/members');
  fixtureGrant('remove');
  try {
    expect((await context.request.get('/api/v1/members?q=Synthetic')).status()).toBe(200);
    const adminMember = page.getByRole('region', { name: 'Manage members' })
      .getByRole('listitem').filter({ hasText: 'Synthetic Administrator' });
    await expect(adminMember).toBeVisible();
    // Editing starts with the restricted detail read, which the server now refuses: no form, no write.
    const readResponse = page.waitForResponse(response =>
      /\/api\/v1\/members\/[0-9a-f-]+\/details$/.test(new URL(response.url()).pathname));
    await adminMember.getByRole('button', { name: 'Edit' }).click();
    expect((await readResponse).status()).toBe(403);
    await expect(page.locator('li form').filter({ has: page.getByRole('button', { name: 'Save' }) })).toHaveCount(0);
    const deniedMemberAdmin = page.getByRole('region', { name: 'Manage members' });
    await expect(deniedMemberAdmin.getByRole('alert'))
      .toContainText('No permission to manage members.');
    await expect(deniedMemberAdmin.getByRole('button', { name: 'Add member' })).toHaveCount(0);
    const readBack = await context.request.get('/api/v1/members?q=Synthetic');
    expect(readBack.status()).toBe(200);
    expect((await readBack.json()).items)
      .toContainEqual(expect.objectContaining({ firstName: 'Synthetic', lastName: 'Administrator' }));
  } finally {
    fixtureGrant('restore');
  }
  await page.reload();
  await expect(page.getByRole('region', { name: 'Manage members' })
    .getByRole('button', { name: 'Add member' })).toBeVisible();

  // A real read-grant revocation must also hide already rendered member names
  // and edit controls, without affecting the separate anonymous name policy.
  fixtureGrant('remove', 'VIEW_MEMBER_DIRECTORY');
  try {
    expect((await context.request.get('/api/v1/members?q=Synthetic')).status()).toBe(403);
    const memberPanel = page.getByRole('region', { name: 'Manage members' });
    await memberPanel.getByLabel('Search names').fill('Synthetic');
    await memberPanel.getByRole('button', { name: 'Search' }).click();
    await expect(memberPanel.getByRole('alert'))
      .toContainText('No permission to manage members.');
    await expect(memberPanel.getByRole('listitem')).toHaveCount(0);
    await expect(memberPanel.getByRole('button', { name: 'Add member' })).toHaveCount(0);
    const publicAfterRevocation = await context.request.get('/api/v1/public/members');
    expect(publicAfterRevocation.status()).toBe(200);
    expect(await publicAfterRevocation.json())
      .toContainEqual({ displayName: 'Synthetic Administrator' });
  } finally {
    fixtureGrant('restore', 'VIEW_MEMBER_DIRECTORY');
  }
  await page.reload();
  await expect(page.getByRole('region', { name: 'Manage members' })
    .getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();

  // A failed subsequent page must preserve the already loaded member names,
  // retry the same opaque cursor and append only the missing results.
  fixtureSql(`INSERT INTO members (id, first_name, last_name, date_of_birth)
    SELECT gen_random_uuid(), 'PagingFixture', 'Synthetic' || lpad(n::text, 3, '0'),
           DATE '2015-01-01'
    FROM generate_series(1, 25) n`);
  try {
    const pagedMembers = page.getByRole('region', { name: 'Manage members' });
    await pagedMembers.getByLabel('Search names').fill('PagingFixture');
    await pagedMembers.getByRole('button', { name: 'Search' }).click();
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(20);
    await page.route('**/api/v1/members?**', route => route.abort(), { times: 1 });
    await pagedMembers.getByRole('button', { name: 'More members' }).click();
    await expect(pagedMembers.getByRole('alert'))
      .toContainText('Member management unavailable.');
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(20);
    await expect(pagedMembers.getByRole('button', { name: 'More members' })).toBeDisabled();
    await pagedMembers.getByRole('button', { name: 'Try again' }).click();
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(25);
    await expect(pagedMembers.getByRole('button', { name: 'More members' })).toHaveCount(0);
    await pagedMembers.getByLabel('Search names').fill('NoSuchPagingFixture');
    await pagedMembers.getByRole('button', { name: 'Search' }).click();
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(0);
  } finally {
    fixtureSql("DELETE FROM members WHERE first_name = 'PagingFixture' " +
      "AND last_name LIKE 'Synthetic%' AND account_id IS NULL");
  }
  await page.reload();

  // Reload and wait until the permission bootstrap has really answered, so the checks below never
  // pass vacuously before the page has settled (the flaky timeout seen on PRs #324 and #328).
  const reloadAndWaitForPermissionProbe = async () => {
    const probe = page.waitForResponse(response =>
      response.url().includes('/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000'),
    { timeout: 20000 });
    await page.reload();
    await probe;
    // Other regions (team member lists) also use role=status, so match the text, not the role.
    await expect(page.getByText('Signed in and authorized for this club.', { exact: true }))
      .toBeVisible({ timeout: 20000 });
  };

  // A genuine capability denial must not be turned into a retryable grant editor (nor offer the page).
  await show(page, '#/admin/permissions');
  await page.route('**/api/v1/grants?**', async route => {
    if (route.request().method() === 'GET' &&
        new URL(route.request().url()).searchParams.get('memberId') === '00000000-0000-0000-0000-000000000000') {
      await route.fulfill({ status: 403, body: 'Forbidden' });
    } else {
      await route.continue();
    }
  });
  await reloadAndWaitForPermissionProbe();
  await expect(page.getByRole('region', { name: 'Manage permissions' })).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Administration' }).getByRole('link', { name: 'Permissions' })).toHaveCount(0);
  await page.unroute('**/api/v1/grants?**');

  // Authentication expiry on the permission bootstrap must not expose a retryable editor.
  await page.route('**/api/v1/grants?**', async route => {
    if (route.request().method() === 'GET' &&
        new URL(route.request().url()).searchParams.get('memberId') === '00000000-0000-0000-0000-000000000000') {
      await route.fulfill({ status: 401, body: 'Unauthorized' });
    } else {
      await route.continue();
    }
  });
  await reloadAndWaitForPermissionProbe();
  await expect(page.getByRole('region', { name: 'Manage permissions' })).toHaveCount(0);
  await page.unroute('**/api/v1/grants?**');

  // A lost session must hide previously authorized private member names and controls.
  await page.route('**/api/v1/members?**', route =>
    new URL(route.request().url()).searchParams.get('q') === 'Synthetic'
      ? route.fulfill({ status: 401, body: 'Unauthorized' }) : route.continue());
  await show(page, '#/admin/members');
  const privateMembers = page.getByRole('region', { name: 'Manage members' });
  await expect(privateMembers).toBeVisible({ timeout: 20000 });
  await privateMembers.getByLabel('Search names').fill('Synthetic');
  await privateMembers.getByRole('button', { name: 'Search' }).click();
  await expect(privateMembers.getByRole('alert')).toContainText('No permission to manage members.');
  await expect(privateMembers.getByRole('listitem')).toHaveCount(0);
  await expect(privateMembers.getByRole('button', { name: 'Add member' })).toHaveCount(0);
  await page.unroute('**/api/v1/members?**');
  // A session-expired team write must not leave previously authorized team names visible.
  await page.route('**/api/v1/teams', route =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 401, body: 'Unauthorized' }) : route.continue());
  await show(page, '#/team');
  await teams.getByLabel('Team name').fill('Expired Session Team');
  await teams.getByRole('button', { name: 'Add team' }).click();
  await expect(teams).toHaveCount(0);
  await page.unroute('**/api/v1/teams');

  // A lost session on role-member search hides all privileged role controls.
  await page.reload();
  await show(page, '#/admin/members/roles');
  const rolesAfterReload = page.getByRole('region', { name: 'Manage club roles' });
  await page.route('**/api/v1/members?**', route =>
    new URL(route.request().url()).searchParams.get('q') === 'Mara'
      ? route.fulfill({ status: 401, body: 'Unauthorized' }) : route.continue());
  await rolesAfterReload.getByLabel('Find member').fill('Mara');
  await rolesAfterReload.getByRole('button', { name: 'Search' }).click();
  await expect(rolesAfterReload).toHaveCount(0);
  await page.unroute('**/api/v1/members?**');

  // Invalid input in the real browser (#25): the browser's own constraints stop what they can,
  // and what only the server can judge is rejected with a visible, announced error and no write.
  await show(page, '#/admin/members');
  const memberForm = page.getByRole('region', { name: 'Manage members' });
  const membersNamed = (lastName) => context.request
    .get('/api/v1/members?q=' + lastName).then(response => response.json()).then(body => body.items.length);
  const addMemberResponse = () => page.waitForResponse(response =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/members');
  // Typing is capped by maxLength, so an oversized name cannot even be entered.
  const firstNameInput = memberForm.getByLabel('First name');
  await firstNameInput.pressSequentially('a'.repeat(250));
  expect((await firstNameInput.inputValue()).length).toBe(200);
  await firstNameInput.fill('');
  // A malformed email is blocked by the browser before any request is sent.
  await memberForm.getByLabel('First name').fill('Invalid');
  await memberForm.getByLabel('Last name').fill('EmailShape');
  await memberForm.getByLabel('Date of birth').fill('1990-01-01');
  await memberForm.getByLabel('Email').fill('not-an-email');
  await memberForm.getByLabel('Phone').fill('+41000000001');
  expect(await memberForm.getByLabel('Email').evaluate(input => input.validity.typeMismatch)).toBe(true);
  expect(await memberForm.getByLabel('Email')
    .evaluate(input => input.form.checkValidity())).toBe(false);
  // A birth date in the future and a whitespace-only name pass the browser but not the server.
  const futureBirthDate = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
  for (const [label, first, last, dob] of [
    ['future birth date', 'Future', 'FutureBirth', futureBirthDate],
    ['whitespace-only first name', '   ', 'BlankFirstName', '2016-01-01'],
  ]) {
    await memberForm.getByLabel('First name').fill(first);
    await memberForm.getByLabel('Last name').fill(last);
    await memberForm.getByLabel('Date of birth').fill(dob);
    await memberForm.getByLabel('Email').fill('');
    await memberForm.getByLabel('Phone').fill('');
    const rejected = addMemberResponse();
    await memberForm.getByRole('button', { name: 'Add member' }).click();
    expect((await rejected).status(), label).toBe(400);
    await expect(memberForm.getByRole('alert'), label).toContainText('Action failed.');
    expect(await membersNamed(last), label).toBe(0);
  }
  // Duplicate (ignoring case and padding) and whitespace-only team names are rejected the same way.
  await show(page, '#/team');
  const teamsForm = page.getByRole('region', { name: 'Teams', exact: true });
  const teamsBeforeInvalid = (await (await context.request.get('/api/v1/teams')).json()).items;
  for (const [label, name] of [
    ['duplicate team name', '  ' + teamsBeforeInvalid[0].name.toUpperCase() + ' '],
    ['whitespace-only team name', '    '],
  ]) {
    await teamsForm.getByLabel('Team name').fill(name);
    const rejected = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/teams');
    await teamsForm.getByRole('button', { name: 'Add team' }).click();
    expect((await rejected).status(), label).toBeGreaterThanOrEqual(400);
    await expect(teamsForm.getByRole('alert'), label).toContainText('Could not create team.');
    expect((await (await context.request.get('/api/v1/teams')).json()).items.length, label)
      .toBe(teamsBeforeInvalid.length);
  }
  // The adult age accepts only whole numbers inside its range; anything else leaves Save disabled.
  await show(page, '#/admin/settings');
  const adultAgeInput = page.getByRole('region', { name: 'Edit club name' }).getByLabel('Adult from age', { exact: false });
  const saveAgeButton = page.getByRole('region', { name: 'Edit club name' })
    .locator('form').filter({ has: page.getByLabel('Adult from age') }).getByRole('button');
  for (const [value, flag] of [['12', 'rangeUnderflow'], ['26', 'rangeOverflow'], ['18.5', 'stepMismatch']]) {
    await adultAgeInput.fill(value);
    expect(await adultAgeInput.evaluate((input, name) => input.validity[name], flag), value).toBe(true);
    await expect(saveAgeButton, value).toBeDisabled();
  }
  await page.reload();
  await expect(page.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();

  // Assistive technology (#25): structure, names and references that screen readers rely on. This
  // checks what is machine-checkable; it is not a substitute for a manual screen-reader audit.
  // Installable on the home screen: the manifest and its icons are served by the app itself.
  const manifest = await (await context.request.get('/manifest.webmanifest')).json();
  expect(manifest).toMatchObject({ name: 'OpenClub', display: 'standalone', start_url: '/#/' });
  for (const icon of manifest.icons) expect((await context.request.get(icon.src)).status(), icon.src).toBe(200);
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest.webmanifest');
  // Every page has one main region and one level-1 heading, and shows its own sections.
  for (const [hash, names] of [
    ['#/', ['Announcements']],
    ['#/admin/settings', ['Edit club name']],
    ['#/admin/permissions', ['Manage permissions']],
    ['#/team', ['Teams']],
    [juniorsPage, ['Manage team']],
    [juniorsPage + '/schedule', ['Schedule Youth Team']],
    [juniorsPage + '/statistics', ['Attendance statistics Youth Team']],
    ['#/admin/members', ['Manage members']],
    ['#/admin/members/invitations', ['Invitations and account links']],
    ['#/admin/members/guardians', ['Guardians']],
    ['#/admin/members/roles', ['Manage club roles']],
    ['#/admin/members/transfer', ['Excel import and export']],
    ['#/admin/members/archived', ['Archived members']],
    ['#/admin/members/public', ['Members']],
  ]) {
    await show(page, hash);
    await expect(page.getByRole('main'), hash).toHaveCount(1);
    for (const name of names) await expect(page.getByRole('region', { name, exact: true }), name).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 }), hash).toHaveCount(1);
  }
  // The document language follows the selected language (screen readers pick pronunciation from it).
  for (const [code, label] of [['de', 'Deutsch'], ['fr', 'Français'], ['it', 'Italiano'], ['en', 'English']]) {
    await chooseLanguage(page, code);
    await expect(page.locator('html'), label).toHaveAttribute('lang', code);
  }
  // Every control a user can operate has an accessible name, in English and in another language.
  for (const code of ['en', 'fr']) {
    await chooseLanguage(page, code);
    const unnamedControls = await page.locator('main input:not([type=hidden]), main select, main textarea, main button')
      .evaluateAll(elements => elements.filter(element => {
        if (element.offsetParent === null) return false;
        const labelled = element.labels && element.labels.length > 0;
        const named = element.getAttribute('aria-label') || element.getAttribute('aria-labelledby') ||
          element.getAttribute('title');
        const text = element.tagName === 'BUTTON' ? element.textContent.trim() : '';
        return !(labelled || named || text);
      }).map(element => element.outerHTML.slice(0, 120)));
    expect(unnamedControls, 'controls without an accessible name (' + code + ')').toEqual([]);
  }
  await chooseLanguage(page, 'en');
  // aria-describedby and aria-labelledby must point at elements that exist.
  const danglingReferences = await page.locator('[aria-describedby], [aria-labelledby]').evaluateAll(elements =>
    elements.flatMap(element => ['aria-describedby', 'aria-labelledby'].flatMap(attribute =>
      (element.getAttribute(attribute) || '').split(/\s+/).filter(Boolean)
        .filter(id => !document.getElementById(id)).map(id => attribute + ' -> ' + id))));
  expect(danglingReferences).toEqual([]);

  // Real database authorization, no injected responses (#25): an ADMIN whose club grants are all
  // removed, then a member who may see exactly one team through a team-scoped grant.
  const adminMemberSql = "(SELECT id FROM members WHERE account_id = '" + accountId + "')";
  const originalGrants = fixtureSql(
    'SELECT capability FROM club_capability_grants WHERE member_id = ' + adminMemberSql)
    .split('\n').filter(Boolean);
  expect(originalGrants).toEqual(expect.arrayContaining(
    ['MANAGE_PERMISSIONS', 'EDIT_MEMBERS', 'VIEW_MEMBER_DIRECTORY']));
  for (const capability of originalGrants) expect(capability).toMatch(/^[A-Z_]+$/);
  // The step below deletes this member's team grants again, so it must start with none.
  expect(fixtureSql('SELECT COUNT(*) FROM team_capability_grants WHERE member_id = ' + adminMemberSql))
    .toBe('0');
  const allTeams = (await (await context.request.get('/api/v1/teams')).json()).items;
  expect(allTeams.length).toBeGreaterThan(1);
  const [visibleTeam, ...hiddenTeams] = allTeams;
  expect(visibleTeam.id).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i);
  const answered = (path, status) => page.waitForResponse(response =>
    new URL(response.url()).pathname === path && response.status() === status, { timeout: 20000 });
  let contactMember = '';
  try {
    // ADMIN role alone grants nothing: every private region fails closed on a real 403.
    fixtureSql('DELETE FROM club_capability_grants WHERE member_id = ' + adminMemberSql);
    const denied = [answered('/api/v1/members', 403), answered('/api/v1/teams', 403),
      answered('/api/v1/grants', 403)];
    await page.reload();
    await Promise.all(denied);
    // Without any grant, Team and Administration are not offered, and their addresses open Start.
    const mainNav = page.getByRole('navigation', { name: 'Main navigation' });
    await expect(mainNav.getByRole('link', { name: 'Start' })).toBeVisible();
    await expect(mainNav.getByRole('link', { name: 'Administration' })).toHaveCount(0);
    await expect(mainNav.getByRole('link', { name: 'Team' })).toHaveCount(0);
    await expect(page.getByRole('heading', { level: 1, name: 'Start' })).toBeVisible();
    // Private panels never render for a member without the grants (not even an empty form).
    for (const name of ['Manage members', 'Manage permissions', 'Invitations and account links', 'Guardians',
      'Archived members', 'Manage club roles', 'Edit club name']) {
      await expect(page.getByRole('region', { name }), name).toHaveCount(0);
    }
    for (const part of ['invitations', 'guardians', 'roles', 'transfer', 'archived']) {
      await show(page, '#/admin/members/' + part);
      await expect(page.getByRole('heading', { level: 1, name: 'Start' }), part).toBeVisible();
      await expect(page.getByRole('navigation', { name: 'More tasks' })).toHaveCount(0);
    }
    await expect(page.getByRole('region', { name: 'Teams', exact: true })).toHaveCount(0);
    expect((await context.request.get('/api/v1/members/00000000-0000-0000-0000-000000000000/export')).status()).toBe(403);
    for (const path of ['/api/v1/members', '/api/v1/teams', '/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000']) {
      expect((await context.request.get(path)).status()).toBe(403);
    }
    // Membership alone still shows the club and the approved anonymous directory.
    expect((await context.request.get('/api/v1/club')).status()).toBe(200);
    expect(await (await context.request.get('/api/v1/public/members')).json())
      .toContainEqual({ displayName: 'Synthetic Administrator' });

    // Cross-team boundary: a grant for one team shows that team and no other.
    fixtureSql("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES ('" +
      visibleTeam.id + "', " + adminMemberSql + ", 'VIEW_TEAMS')");
    const listed = answered('/api/v1/teams', 200);
    await page.reload();
    await listed;
    await show(page, '#/team');
    const teamsRegion = page.getByRole('region', { name: 'Teams', exact: true });
    await expect(teamsRegion.getByRole('listitem').filter({ hasText: visibleTeam.name })).toHaveCount(1);
    for (const hidden of hiddenTeams) {
      await expect(teamsRegion.getByRole('listitem').filter({ hasText: hidden.name })).toHaveCount(0);
      expect((await context.request.get('/api/v1/teams/' + hidden.id + '/members')).status()).toBe(403);
    }
    expect((await (await context.request.get('/api/v1/teams')).json()).items.map(team => team.id))
      .toEqual([visibleTeam.id]);

    // Contact reads (OpenAPI 1.3.0) follow the same team boundary, on real grants: VIEW_TEAMS reads no
    // contacts, a team-scoped MANAGE_TEAMS reads only the manager fields of that team's active members,
    // every read is audited, and a member of another team stays refused.
    contactMember = fixtureSql("INSERT INTO members (id, first_name, last_name, date_of_birth, phone) " +
      "VALUES (gen_random_uuid(), 'Contact', 'Fixture', DATE '2014-01-01', '+41000000088') RETURNING id").split('\n')[0];
    expect(contactMember).toMatch(/^[0-9a-f-]{36}$/);
    fixtureSql("INSERT INTO member_team_assignments (team_id, member_id) VALUES ('" + visibleTeam.id + "', '" + contactMember + "')");
    const detailsPath = '/api/v1/members/' + contactMember + '/details';
    expect((await context.request.get(detailsPath)).status()).toBe(403);
    fixtureSql("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES ('" +
      visibleTeam.id + "', " + adminMemberSql + ", 'MANAGE_TEAMS')");
    const auditedBefore = Number(fixtureSql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'CONTACT_READ' " +
      "AND target_member_id = '" + contactMember + "'"));
    const managed = await context.request.get(detailsPath);
    expect(managed.status()).toBe(200);
    expect(managed.headers()['cache-control']).toBe('no-store');
    expect(Object.keys(await managed.json()).sort()).toEqual(['email', 'firstName', 'id', 'isPlayer', 'lastName', 'phone']);
    expect(Number(fixtureSql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'CONTACT_READ' " +
      "AND target_member_id = '" + contactMember + "'"))).toBe(auditedBefore + 1);
    fixtureSql("UPDATE member_team_assignments SET team_id = '" + hiddenTeams[0].id + "' WHERE member_id = '" + contactMember + "'");
    expect((await context.request.get(detailsPath)).status()).toBe(403);
  } finally {
    if (contactMember) fixtureSql("DELETE FROM members WHERE id = '" + contactMember + "'");
    fixtureSql('DELETE FROM team_capability_grants WHERE member_id = ' + adminMemberSql);
    fixtureSql('DELETE FROM club_capability_grants WHERE member_id = ' + adminMemberSql);
    for (const capability of originalGrants) {
      fixtureSql("INSERT INTO club_capability_grants (member_id, capability) SELECT id, '" + capability +
        "' FROM members WHERE account_id = '" + accountId + "' ON CONFLICT DO NOTHING");
    }
  }
  await page.reload();
  await show(page, '#/admin/permissions');
  await expect(page.getByRole('region', { name: 'Manage permissions' })).toBeVisible();
  await show(page, '#/admin/settings');
  await expect(page.getByRole('region', { name: 'Edit club name' })).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  // Other accessible status elements (such as transient team loading) may
  // coexist with the session status while the UI settles after logout.
  await expect(page.getByText('Please sign in.', { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Members', exact: true }).getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();

  // Logout revokes the real session. Private member/team/grant reads now deny
  // access, while the approved anonymous display-name directory remains usable.
  for (const path of [
    '/api/v1/members',
    '/api/v1/teams',
    '/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000',
  ]) {
    const response = await context.request.get(path);
    expect(response.status(), path).toBe(401);
  }
  const anonymousNames = await context.request.get('/api/v1/public/members');
  expect(anonymousNames.status()).toBe(200);
  expect(await anonymousNames.json()).toContainEqual({ displayName: 'Synthetic Administrator' });
});

test('an administrator invites a member, a second synthetic user accepts, and unlinking ends that session', async ({ page, context, browser }) => {
  test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
    'Invitations write synthetic data and must only run against the disposable CI database');
  test.setTimeout(180000);
  const password = process.env.OPENCLUB_E2E_USER_PASSWORD;
  if (!password) throw new Error('Disposable synthetic login password required');
  const fixtureSql = (sql) => String(execFileSync('docker', [
    'compose', '-f', 'compose.yaml', '-f', 'compose.auth.dev.yaml',
    'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-t', '-A',
    '-U', 'openclub', '-d', 'openclub', '-c', sql,
  ], { cwd: fileURLToPath(new URL('../../', import.meta.url)), stdio: 'pipe' })).trim();
  const signIn = async (target, username) => {
    await target.goto('/');
    await target.locator('#language').selectOption('en');
    await target.getByRole('link', { name: 'Sign in' }).click();
    await target.locator('#username').fill(username);
    await target.locator('#password').fill(password);
    await Promise.all([
      target.waitForURL(/^http:\/\/localhost:5173\//),
      target.locator('#kc-login').click(),
    ]);
  };

  // A synthetic adult with no account, created directly so the journey stays about invitations.
  fixtureSql("DELETE FROM members WHERE last_name = 'Invitee' AND account_id IS NULL");
  fixtureSql("INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone) " +
    "VALUES (gen_random_uuid(), 'Ines', 'Invitee', DATE '1990-05-05', 'ines@example.invalid', '+41000000009')");
  const invitee = await browser.newContext({ baseURL: 'http://localhost:5173' });
  try {
    await signIn(page, 'openclub-ci-user');
    await show(page, '#/admin/members/invitations');
    const region = page.getByRole('region', { name: 'Invitations and account links' });
    await expect(region).toBeVisible();
    await region.getByRole('textbox', { name: 'Find member' }).fill('Invitee');
    await region.getByRole('button', { name: 'Search' }).click();
    await expect(region.getByRole('combobox', { name: 'Member' })).toContainText('Ines Invitee');
    await region.getByRole('button', { name: 'Create invitation' }).click();
    await expect(region.getByText('Invitation created.', { exact: true })).toBeVisible();
    const link = await region.getByRole('textbox', { name: /Invitation link/ }).inputValue();
    expect(link).toMatch(/^http:\/\/localhost:5173\/#invite=[A-Za-z0-9_-]{43}$/);
    // A second invitation while one is open is refused with the generic explanation.
    await region.getByRole('button', { name: 'Create invitation' }).click();
    await expect(region.getByText(/cannot be invited or unlinked/)).toBeVisible();

    // The second synthetic user opens the link, signs in (the secret survives the round trip) and accepts.
    const second = await invitee.newPage();
    await second.goto(link);
    await expect(second).toHaveURL('http://localhost:5173/');
    await second.locator('#language').selectOption('en');
    await second.getByRole('link', { name: 'Sign in' }).click();
    await second.locator('#username').fill('openclub-ci-invitee');
    await second.locator('#password').fill(password);
    await Promise.all([
      second.waitForURL(/^http:\/\/localhost:5173\//),
      second.locator('#kc-login').click(),
    ]);
    await expect(second.getByText('This account is not yet a club member.', { exact: true })).toBeVisible();
    const accept = second.getByRole('region', { name: 'Accept an invitation' });
    await expect(accept.getByRole('textbox', { name: 'Invitation code or link' })).toHaveValue(/^[A-Za-z0-9_-]{43}$/);
    // A wrong code gets the one generic answer and changes nothing.
    const secret = await accept.getByRole('textbox', { name: 'Invitation code or link' }).inputValue();
    await accept.getByRole('textbox', { name: 'Invitation code or link' }).fill('A'.repeat(43));
    await accept.getByRole('button', { name: 'Accept invitation' }).click();
    await expect(accept.getByRole('alert')).toHaveText('The invitation is invalid, expired or already used.');
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('0');
    await accept.getByRole('textbox', { name: 'Invitation code or link' }).fill(secret);
    await accept.getByRole('button', { name: 'Accept invitation' }).click();
    await expect(second.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('1');
    // Linking grants nothing: the new member sees no administration.
    expect((await invitee.request.get('/api/v1/club')).status()).toBe(200);
    expect((await invitee.request.get('/api/v1/members')).status()).toBe(403);

    // The administrator unlinks; the member's session and access end at once.
    await region.getByRole('textbox', { name: 'Find member' }).fill('Invitee');
    await region.getByRole('button', { name: 'Search' }).click();
    await expect(region.getByRole('combobox', { name: 'Member' })).toContainText('Ines Invitee');
    await region.getByRole('button', { name: 'Unlink account' }).click();
    await expect(region.getByText('Account unlinked.', { exact: true })).toBeVisible();
    expect((await invitee.request.get('/api/v1/club')).status()).toBe(401);
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('0');
  } finally {
    await invitee.close();
    fixtureSql("DELETE FROM members WHERE last_name = 'Invitee'");
    await context.clearCookies();
  }
});
