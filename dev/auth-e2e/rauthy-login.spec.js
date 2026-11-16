import { test, expect } from '@playwright/test';

test('Rauthy password login creates a verified OpenClub session without club permissions', async ({ page, context }) => {
  const password = process.env.OPENCLUB_RAUTHY_E2E_USER_PASSWORD;
  if (!password) throw new Error('Disposable synthetic Rauthy login password required');

  await page.goto('/');
  await page.locator('#language').selectOption('en');
  await expect(page.getByRole('status')).toHaveText('Please sign in.');

  await page.getByRole('link', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);

  // Rauthy's normal browser flow requests email first, then the password.
  await page.locator('input[type="email"]').fill('openclub-rauthy-ci@example.invalid');
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await page.locator('input[type="password"]:visible').fill(password);
  await Promise.all([
    page.waitForURL('https://localhost:5443/'),
    page.locator('form').getByRole('button', { name: 'Login', exact: true }).click(),
  ]);

  // A real Rauthy-signed ID token created an app session, but did NOT
  // grant any club permissions merely because its provider login succeeded.
  await expect(page.getByRole('status')).toHaveText('This account is not yet a club member.');
  const me = await context.request.get('/api/v1/auth/me');
  expect(me.status()).toBe(200);
  expect((await me.json()).accountId).toMatch(/^[0-9a-f-]{36}$/i);
  for (const route of ['/api/v1/club', '/api/v1/members', '/api/v1/teams']) {
    const response = await context.request.get(route);
    expect(response.status(), route).toBe(403);
    expect(response.headers()['cache-control']).toContain('no-store');
  }

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('status')).toHaveText('Please sign in.');
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
});
