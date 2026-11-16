import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

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
  await expect(page.getByRole('heading', { name: 'Synthetic Browser Club' })).toBeVisible();
  const clubSettings = page.getByRole('region', { name: 'Edit club name' });
  await clubSettings.getByRole('textbox', { name: 'Club name' }).fill('Synthetic Updated Club');
  await clubSettings.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Synthetic Updated Club' })).toBeVisible();
  const updatedClub = await context.request.get('/api/v1/club');
  expect(updatedClub.status()).toBe(200);
  expect((await updatedClub.json()).name).toBe('Synthetic Updated Club');

  // The public directory renders the synthetic adult name, even after logout.
  await expect(page.getByRole('region', { name: 'Members', exact: true }).getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
  const publicNames = await context.request.get('/api/v1/public/members');
  expect(publicNames.status()).toBe(200);
  expect(await publicNames.json()).toEqual([{ displayName: 'Synthetic Administrator' }]);

  // Complete the real authorized browser journey with fictional member data.
  await expect(page.getByRole('heading', { name: 'Manage members' })).toBeVisible();
  // Native required-field validation prevents an incomplete browser write.
  await page.getByLabel('First name').fill('Incomplete');
  await page.getByLabel('Last name').fill('Synthetic');
  await page.getByRole('button', { name: 'Add member' }).click();
  expect(await page.getByLabel('Date of birth').evaluate(input => input.validity.valueMissing)).toBe(true);
  await expect(page.getByRole('listitem').filter({ hasText: 'Incomplete Synthetic' })).toHaveCount(0);

  await page.getByLabel('First name').fill('Nina');
  await page.getByLabel('Last name').fill('Private');
  await page.getByLabel('Date of birth').fill('2013-01-01');
  await page.getByRole('button', { name: 'Add member' }).click();
  const created = page.getByRole('listitem').filter({ hasText: 'Nina Private' });
  await expect(created).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'Nina P.' })).toBeVisible();
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
  await searchForm.getByRole('button', { name: 'Try again' }).click();
  await expect(searchForm.getByText('No members found.')).toBeVisible();
  await searchForm.getByLabel('Search names').fill('');
  await searchForm.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(searchForm.getByRole('listitem').filter({ hasText: 'Nina Private' })).toBeVisible();

  await created.getByRole('button', { name: 'Edit' }).click();
  // The row text is replaced by editable inputs, so locate the active form.
  const editForm = page.locator('li form').filter({ has: page.getByRole('button', { name: 'Save' }) });
  await editForm.getByLabel('Last name').fill('Renamed');
  await editForm.getByRole('button', { name: 'Save' }).click();
  const renamed = page.getByRole('listitem').filter({ hasText: 'Nina Renamed' });
  await expect(renamed).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'Nina R.' })).toBeVisible();
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
  await renamed.getByRole('button', { name: 'Cancel' }).click();
  await expect(renamed).toBeVisible();
  await renamed.getByRole('button', { name: 'Archive' }).click();
  await renamed.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(renamed).toHaveCount(0);
  await expect(page.getByRole('listitem').filter({ hasText: 'Nina R.' })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Members', exact: true })
    .getByRole('listitem').filter({ hasText: 'Nina R.' })).toHaveCount(0);
  // Archived minors must disappear from the anonymous API, not only the UI.
  const archivedPublicNames = await context.request.get('/api/v1/public/members');
  expect(archivedPublicNames.status()).toBe(200);
  expect(await archivedPublicNames.json()).not.toContainEqual({ displayName: 'Nina R.' });

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


  // Canceling a member-specific archive must work after switching languages.
  await memberAdminForAdult.getByLabel('First name').fill('Locale');
  await memberAdminForAdult.getByLabel('Last name').fill('Synthetic');
  await memberAdminForAdult.getByLabel('Date of birth').fill('2015-01-01');
  await memberAdminForAdult.getByRole('button', { name: 'Add member' }).click();
  for (const locale of [
    ['de', 'Mitglieder verwalten', 'Archivieren', 'Mitglied wirklich archivieren?', 'Abbrechen', 'Für Erwachsene sind E-Mail und Telefonnummer erforderlich; für Minderjährige sind beide freiwillig.'],
    ['fr', 'Gérer les membres', 'Archiver', 'Archiver ce membre ?', 'Annuler', 'Pour les adultes, l’e-mail et le téléphone sont obligatoires ; les deux sont facultatifs pour les mineurs.'],
    ['it', 'Gestisci membri', 'Archivia', 'Archiviare questo membro?', 'Annulla', 'Per gli adulti sono obbligatori sia l’email sia il telefono; per i minorenni sono facoltativi.'],
    ['en', 'Manage members', 'Archive', 'Archive this member?', 'Cancel', 'Adults must provide both email and phone; both are optional for minors.'],
  ]) {
    await page.locator('#language').selectOption(locale[0]);
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
  await clubSettings.getByLabel('Require gender for players').check();
  await clubSettings.getByRole('button', { name: 'Save policy' }).click();
  await expect.poll(async () => (await (await context.request.get('/api/v1/club')).json()).genderRequiredForPlayers).toBe(true);

  // When the club enables this rule, player creation cannot omit gender.
  await memberAdmin.getByLabel('First name').fill('Player');
  await memberAdmin.getByLabel('Last name').fill('Missing');
  await memberAdmin.getByLabel('Date of birth').fill('2014-01-01');
  await memberAdmin.getByLabel('Player', { exact: true }).check();
  await expect(memberAdmin.getByLabel('Gender (free text)')).toHaveAttribute('required', '');
  await memberAdmin.getByRole('button', { name: 'Add member' }).click();
  await expect(memberAdmin.getByRole('listitem').filter({ hasText: 'Player Missing' })).toHaveCount(0);
  await memberAdmin.getByLabel('Player', { exact: true }).uncheck();


  const roleAdmin = page.getByRole('region', { name: 'Manage club roles' });
  await roleAdmin.getByLabel('Find member').fill('Mara');
  await roleAdmin.getByRole('button', { name: 'Search' }).click();
  await expect(roleAdmin.getByRole('combobox', { name: 'Member' })
    .getByRole('option', { name: 'Mara Demo' })).toHaveCount(1);
  await roleAdmin.getByRole('combobox', { name: 'Role' }).selectOption('ADMIN');
  await roleAdmin.getByRole('button', { name: 'Save role' }).click();
  await expect(roleAdmin.getByRole('combobox', { name: 'Role' })).toHaveValue('ADMIN');
  await roleAdmin.getByRole('combobox', { name: 'Role' }).selectOption('MEMBER');
  await roleAdmin.getByRole('button', { name: 'Save role' }).click();
  await expect(roleAdmin.getByRole('combobox', { name: 'Role' })).toHaveValue('MEMBER');


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
  await expect(teams.getByRole('listitem').filter({ hasText: 'Juniors' })).toBeVisible();
  await expect.poll(() => failedRoster).toBe(true);
  await expect(teams.getByRole('alert').filter({ hasText: 'Team assignment failed.' })).toBeVisible();
  await expect(teams.getByText('Loading team members…')).toHaveCount(0);
  await page.unroute('**/api/v1/teams/**');
  await teams.getByRole('button', { name: 'Try again' }).click();
  await expect(teams.getByText('No assignments.')).toBeVisible();
  await expect(teams.getByRole('alert').filter({ hasText: 'Team assignment failed.' })).toHaveCount(0);
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
  await teams.getByLabel('Find member').fill('Slow');
  await teams.getByRole('button', { name: 'Search' }).click();
  await slowCandidateStarted;
  await teams.getByLabel('Find member').fill('Mara');
  await teams.getByRole('button', { name: 'Search' }).click();
  await canceledCandidate;
  await expect(teams.getByRole('option', { name: 'Mara Demo' })).toHaveCount(1);
  await page.unroute('**/api/v1/members?**');
  await teams.getByRole('button', { name: 'Assign' }).click();
  await expect(teams.getByRole('button', { name: 'Remove' })).toBeVisible();
  await expect(teams.getByText('Juniors — 1 Members')).toBeVisible();

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

  await teams.getByRole('button', { name: 'Remove' }).click();
  await expect(teams.getByText('No assignments.')).toBeVisible();
  // A rename preserves the team's ID, membership relationship and permissions.
  await teams.getByRole('button', { name: 'Rename' }).click();
  const renameForm = teams.getByRole('button', { name: 'Save' }).locator('..');
  await renameForm.getByRole('textbox', { name: 'Team name' }).fill('Youth Team');
  await renameForm.getByRole('button', { name: 'Save' }).click();
  await expect(teams.getByText('Youth Team — 0 Members')).toBeVisible();
  // The whole authenticated workflow remains reachable with localized
  // member, team and delegation controls, not just a translated landing page.
  for (const locale of [
    { code: 'de', member: 'Mitglieder verwalten', team: 'Teams', grant: 'Berechtigungen verwalten' },
    { code: 'fr', member: 'Gérer les membres', team: 'Équipes', grant: 'Gérer les autorisations' },
    { code: 'it', member: 'Gestisci membri', team: 'Squadre', grant: 'Gestisci permessi' },
    { code: 'en', member: 'Manage members', team: 'Teams', grant: 'Manage permissions' },
  ]) {
    await page.locator('#language').selectOption(locale.code);
    await expect(page.getByRole('heading', { name: locale.member })).toBeVisible();
    await expect(page.getByRole('region', { name: locale.team, exact: true })).toBeVisible();
    await expect(page.getByRole('region', { name: locale.grant, exact: true })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', locale.code);
  }

  await memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' })
    .getByRole('button', { name: 'Archive' }).click();
  await memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' })
    .getByRole('button', { name: 'Confirm archive' }).click();
  await expect(memberAdmin.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('status')).toHaveText('Please sign in.');
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
