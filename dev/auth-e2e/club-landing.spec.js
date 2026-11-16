import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

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
  await expect(page.getByRole('status')).toHaveText(
    'Signed in and authorized for this club.',
  );
  // The public directory renders the synthetic adult name, even after logout.
  await expect(page.getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
  const publicNames = await context.request.get('/api/v1/public/members');
  expect(publicNames.status()).toBe(200);
  expect(await publicNames.json()).toEqual([{ displayName: 'Synthetic Administrator' }]);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('status')).toHaveText('Please sign in.');
  await expect(page.getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
});
