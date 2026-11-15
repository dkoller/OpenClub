import { test, expect } from '@playwright/test';

test('real frontend shows session, membership denial, language and logout states', async ({ page }) => {
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

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('status')).toHaveText('Please sign in.');
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();

  await page.reload();
  await expect(page.locator('#language')).toHaveValue('en');
  await expect(page.getByRole('status')).toHaveText('Please sign in.');
});
