import { test, expect } from '@playwright/test';

// A virtual, platform-like FIDO2 authenticator. No credential leaves Chromium,
// and no private keys or browser state are ever logged or uploaded in CI.
test('Rauthy account can enroll a passkey and use it for OpenClub sign-in', async ({ page, context }) => {
  const password = process.env.OPENCLUB_RAUTHY_E2E_USER_PASSWORD;
  if (!password) throw new Error('Synthetic Rauthy password missing');
  test.setTimeout(120000);

  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  });

  try {
    await page.goto('https://localhost:8443/auth/v1/account');
    await page.locator('input[type="email"]').fill('openclub-rauthy-ci@example.invalid');
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await page.locator('input[type="password"]:visible').fill(password);
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await expect(page).toHaveURL(/\/auth\/v1\/account/);

    // The account dashboard is the only place where a normal user enrolls
    // a new passkey. The virtual authenticator handles the WebAuthn ceremony.
    await page.getByText('MFA', { exact: true }).first().click();
    await page.getByRole('button', { name: /add|register|new passkey/i }).first().click();
    await expect.poll(async () => (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials.length)
      .toBeGreaterThan(0);

    await page.goto('https://localhost:5443/');
    await page.getByRole('link', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);

    await page.locator('input[type="email"]').fill('openclub-rauthy-ci@example.invalid');
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    // A registered passkey should be offered instead of requiring a password.
    await page.getByRole('button', { name: /passkey|security key/i }).click();
    await expect(page).toHaveURL('https://localhost:5443/');
    await expect(page.getByRole('status')).toHaveText('This account is not yet a club member.');
    expect((await context.request.get('/api/v1/club')).status()).toBe(403);
  } finally {
    await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
    await cdp.detach();
  }
});
