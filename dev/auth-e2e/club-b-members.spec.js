import { expect, openForm, openMember, show, test } from './club-fixtures.js';
import { readFileSync } from 'node:fs';
// Members: create, search, edit, archive and reactivate; server rules; details and export.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('members are created, searched, edited, archived and reactivated', async ({ page, context }) => {
  test.setTimeout(120000);
  // Native required-field validation prevents an incomplete browser write.
  await show(page, '#/admin/members');
  await openForm(page, 'New member');
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

  await openMember(created);
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
  await openMember(renamed);
  await expect(renamed.getByRole('region', { name: 'Details: Nina Renamed' }).getByText('+41000000077')).toBeVisible();
  await renamed.getByRole('button', { name: 'Close' }).click();
  // Turning a minor into an adult without email is refused by the server; nothing changes.
  await openMember(renamed);
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
  await openMember(renamed);
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
  await openMember(renamed);
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
  await openMember(renamed);
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
  await openMember(backInList);
  await backInList.getByRole('button', { name: 'Archive' }).click();
  await backInList.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(backInList).toHaveCount(0);
  await show(page, '#/admin/members/archived');
  await archivedRegion.getByLabel('Search names').fill('Nina');
  await archivedRegion.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(archivedNina).toBeVisible();
  await show(page, '#/admin/members');
});

test('adult contacts are enforced by the server and an archive can be cancelled', async ({ page }) => {
  const memberAdminForAdult = page.getByRole('region', { name: 'Manage members' });
  await show(page, '#/admin/members');
  // Adult contacts are enforced by the server, not merely by browser hints.
  await openForm(memberAdminForAdult, 'New member');
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
  await openMember(completeAdult);
  await completeAdult.getByRole('button', { name: 'Archive' }).click();
  await completeAdult.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(completeAdult).toHaveCount(0);


  // Canceling a member-specific archive must work.
  await openForm(memberAdminForAdult, 'New member');
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
    await openMember(row);
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
  await openMember(localeRow);
  await localeRow.getByRole('button', { name: 'Archive', exact: true }).click();
  await localeRow.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(localeRow).toHaveCount(0);
});

test('a player\'s private fields show only on request and export as JSON', async ({ page, context }) => {
  await show(page, '#/admin/members');
  // One Keycloak-backed browser journey now also verifies team and grant workflows.
  await openForm(page, 'New member');
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
  await openMember(maraRow);
  expect((await detailsResponse).headers()['cache-control']).toBe('no-store');
  const maraDetails = maraRow.getByRole('region', { name: 'Details: Mara Demo' });
  await expect(maraDetails.getByText('Synthetic test value')).toBeVisible();
  await expect(maraDetails.getByText('01.01.2015')).toBeVisible();
  await expect(maraDetails.getByText('not set').first()).toBeVisible();
  await expect(maraRow.locator('button.row-toggle')).toHaveAttribute('aria-expanded', 'true');
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
});
