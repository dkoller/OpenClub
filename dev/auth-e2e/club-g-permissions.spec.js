import { ensureMara, expect, show, test } from './club-fixtures.js';

// Permissions: grant and revoke with the overview, and recovery after failures.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('a permission is granted and revoked, and stale lookups never win', async ({ page }) => {
  ensureMara();
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
  await expect(permissionAdmin.getByRole('listitem').filter({ hasText: 'See teams' })).toBeVisible();
  // The overview lists the new permission in plain words, and its Change button opens that member in the editor.
  const whoCan = page.getByRole('region', { name: 'Who can do what' });
  await expect(whoCan.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toContainText('See teams');
  await whoCan.getByRole('button', { name: 'Change: Mara Demo' }).click();
  await expect(permissionAdmin.getByRole('combobox', { name: 'Member' })).toHaveValue(
    await permissionAdmin.getByRole('option', { name: 'Mara Demo' }).getAttribute('value'));
  await permissionAdmin.getByRole('button', { name: 'Revoke' }).click();
  await expect(permissionAdmin.getByText('No grants.')).toBeVisible();
  await expect(whoCan.getByRole('listitem').filter({ hasText: 'Mara Demo' })).toHaveCount(0);
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
});

test('a failed permission start recovers, and a denied write hides the editor', async ({ page }) => {
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
});
