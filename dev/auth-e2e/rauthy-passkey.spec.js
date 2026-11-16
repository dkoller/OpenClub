import { test, expect } from '@playwright/test';

// A virtual, platform-like FIDO2 authenticator. No credential leaves Chromium,
// and no private keys or browser state are ever logged or uploaded in CI.
test('Rauthy account can enroll a passkey and use it for OpenClub sign-in', async ({ page, context }) => {
  const password = process.env.OPENCLUB_RAUTHY_E2E_USER_PASSWORD;
  if (!password) throw new Error('Synthetic Rauthy password missing');
  test.setTimeout(70000);
  page.setDefaultTimeout(10000);

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
    await page.getByRole('button', { name: 'Register New Passkey' }).click();
    // Rauthy requires a fresh password-confirmed MFA modification token
    // before allowing registration. This is NOT the OpenClub password login.
    await page.getByPlaceholder('Current Password').fill(password);
    await page.getByRole('button', { name: 'Authenticate', exact: true }).click();
    // The MFA authorization opens an account security dialog. Keep all
    // subsequent actions inside that dialog rather than clicking behind it.
    const dialog = page.locator('dialog[open]');
    await expect(dialog).toBeVisible();
    // The dialog contains no secrets except hidden password input values,
    // which must never be included in diagnostics.
    console.log('Rauthy passkey dialog text:', (await dialog.innerText()).slice(0, 1000));
    console.log('Rauthy passkey dialog buttons:', await dialog.getByRole('button').evaluateAll(
      nodes => nodes.map(n => ({ label: n.getAttribute('aria-label'), title: n.title, type: n.type, text: n.textContent?.trim().slice(0, 80) }))));
    console.log('Rauthy passkey dialog fields:', await dialog.locator('input').evaluateAll(
      nodes => nodes.map(n => ({ type: n.type, placeholder: n.placeholder }))));
    const nameField = dialog.locator('input[type="text"]');
    if (await nameField.count() === 1) await nameField.fill('Synthetic virtual passkey');
    await expect(dialog).not.toBeVisible({ timeout: 10000 });
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
    await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId }).catch(() => {});
    await cdp.detach().catch(() => {});
  }
});
