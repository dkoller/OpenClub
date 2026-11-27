import { ensureMara, expect, openForm, show, test } from './club-fixtures.js';

// Guardians, and the club settings (player gender rule, adult age).
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('a guardian is added, reads and edits the child, and loses access when removed', async ({ page, context }) => {
  ensureMara();
  // Guardians (OpenAPI 1.6.0): the editor attests a guardianship, the guardian sees "My children", reads
  // the minor and replaces only her contacts; removing the guardianship ends access at the next request.
  await show(page, '#/admin/members/guardians');
  const guardiansRegion = page.getByRole('region', { name: 'Guardians' });
  // The page starts with the children who have no guardian yet (OpenAPI 1.38.0).
  await expect(guardiansRegion.getByRole('button', { name: 'Add a guardian: Mara Demo' })).toBeVisible();
  await guardiansRegion.getByLabel('Find a minor').fill('Mara');
  await guardiansRegion.getByRole('button', { name: 'Search' }).first().click();
  await expect(guardiansRegion.getByText('No guardians recorded.')).toBeVisible();
  await guardiansRegion.getByLabel('Find a guardian').fill('Synthetic Administrator');
  await guardiansRegion.getByRole('button', { name: 'Search' }).nth(1).click();
  await expect(guardiansRegion.getByRole('combobox', { name: 'Guardian' })).toContainText('Synthetic Administrator');
  await guardiansRegion.getByRole('button', { name: 'Add as guardian' }).click();
  await expect(guardiansRegion.getByText('Guardian added.')).toBeVisible();
  await expect(guardiansRegion.getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
  await expect(guardiansRegion.getByRole('button', { name: 'Add a guardian: Mara Demo' })).toHaveCount(0);
  // Adding the same guardian twice is refused with the rule, not an error.
  await guardiansRegion.getByRole('button', { name: 'Add as guardian' }).click();
  await expect(guardiansRegion.getByText(/Not possible/)).toBeVisible();
  await page.reload();
  await show(page, '#/family/children');
  const children = page.getByRole('region', { name: 'My children' });
  const maraChild = children.getByRole('listitem').filter({ hasText: 'Mara Demo' });
  const dependentRead = page.waitForResponse(response => /\/api\/v1\/me\/dependents\/[^/]+$/.test(new URL(response.url()).pathname));
  await maraChild.locator('button.row-toggle').click();
  expect((await dependentRead).headers()['cache-control']).toBe('no-store');
  await expect(maraChild.getByText('01.01.2015')).toBeVisible();
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
});

test('the player gender rule and the adult age apply to the member form', async ({ page, context }) => {
  const clubSettings = page.getByRole('region', { name: 'Edit club name' });
  const memberAdmin = page.getByRole('region', { name: 'Manage members' });
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
  await openForm(memberAdmin, 'New member');
  await expect(memberAdmin.getByText('Adult from age 20.')).toBeVisible();
  await show(page, '#/admin/settings');
  await adultAge.fill('18');
  await clubSettings.getByRole('button', { name: 'Save adult age' }).click();
  await expect.poll(async () => (await (await context.request.get('/api/v1/club')).json()).adultAgeYears).toBe(18);
  await show(page, '#/admin/members');
  await openForm(memberAdmin, 'New member');
  await expect(memberAdmin.getByText('Adult from age 18.')).toBeVisible();

  // When the club enables this rule, player creation cannot omit gender.
  await openForm(memberAdmin, 'New member');
  await memberAdmin.getByLabel('First name').fill('Player');
  await memberAdmin.getByLabel('Last name').fill('Missing');
  await memberAdmin.getByLabel('Date of birth').fill('2014-01-01');
  await memberAdmin.getByLabel('Player', { exact: true }).check();
  await expect(memberAdmin.getByLabel('Gender (free text)')).toHaveAttribute('required', '');
  await memberAdmin.getByRole('button', { name: 'Add member' }).click();
  await expect(memberAdmin.getByRole('listitem').filter({ hasText: 'Player Missing' })).toHaveCount(0);
  await memberAdmin.getByLabel('Player', { exact: true }).uncheck();
});

test('policy conflicts and failures are shown next to the setting and recover', async ({ page }) => {
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
});
