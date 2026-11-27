import { ensureMara, expect, show, test } from './club-fixtures.js';

// Club roles: the latest lookup wins, and losing the right hides the editor.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('a role is changed for the latest searched member only', async ({ page }) => {
  ensureMara();
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
});
