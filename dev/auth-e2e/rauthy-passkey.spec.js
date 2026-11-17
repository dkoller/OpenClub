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
    await expect(dialog).not.toBeVisible({ timeout: 10000 });
    // The authenticated MFA token exposes Rauthy's Passkey Name form.
    // Register via its own submit so the browser runs a real WebAuthn create.
    await page.getByPlaceholder('Passkey Name').fill('Synthetic virtual passkey');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await expect.poll(async () => (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials.length)
      .toBeGreaterThan(0);

    await page.getByRole('button', { name: 'Logout', exact: true }).click();
    // Force a fresh Rauthy authentication ceremony, not transparent
    // SSO reuse of the earlier password-authenticated provider session.
    await context.clearCookies();
    await page.goto('https://localhost:5443/');
    await page.getByRole('link', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);

    // Also exercise Rauthy's password-plus-registered-security-key path.
    // Assert an actual signature, not simply the presence of an MFA screen.
    const signatureBeforePassword = (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials[0].signCount;
    await page.locator('input[type="email"]').fill('openclub-rauthy-ci@example.invalid');
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await page.locator('input[type="password"]:visible').fill(password);
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await expect(page).toHaveURL('https://localhost:5443/');
    const signatureAfterPassword = (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials[0].signCount;
    expect(signatureAfterPassword, 'Password sign-in must also require a WebAuthn assertion')
      .toBeGreaterThan(signatureBeforePassword);
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
    await page.getByRole('button', { name: 'Sign out' }).click();
    await context.clearCookies();
    await page.goto('https://localhost:5443/');
    await page.getByRole('link', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);

    // A resident (discoverable) passkey supports login without typing the
    // E-Mail or entering a password. Other deployments may choose a
    // nonresident key and use Rauthy's E-Mail-first flow instead.
    // Its button uses aria-label="Login", so match visible text.
    const passkeyChoice = page.locator('button').filter({ hasText: /passkey/i });
    await expect(passkeyChoice).toBeVisible({ timeout: 10000 });
    await passkeyChoice.click();
    await expect(page).toHaveURL('https://localhost:5443/');
    const signedCredentials = (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials;
    expect(signedCredentials.some(c => c.signCount > 0),
      'The new virtual credential must sign an actual Rauthy login assertion').toBe(true);
    await expect(page.getByRole('status')).toHaveText('This account is not yet a club member.');
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
    expect((await context.request.get('/api/v1/club')).status()).toBe(403);

    // Passkey authentication must not leave an app session after logout.
    // Verify Ktor's actual cookie-backed session, not just the React UI.
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('status')).toHaveText('Please sign in.');
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
  } finally {
    await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId }).catch(() => {});
    await cdp.detach().catch(() => {});
  }
});
