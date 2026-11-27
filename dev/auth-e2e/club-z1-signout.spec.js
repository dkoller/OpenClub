import { expect, openForm, test } from './club-fixtures.js';

// Runs after all club tests (teardown): signing out ends the saved session.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('signing out ends the session and leaves only the public list', async ({ page, context }) => {
  await page.getByRole('button', { name: 'Sign out' }).click();
  // Other accessible status elements (such as transient team loading) may
  // coexist with the session status while the UI settles after logout.
  await expect(page.getByText('Please sign in.', { exact: true })).toBeVisible();
  await openForm(page.getByRole('region', { name: 'Members', exact: true }), 'Show members');
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
