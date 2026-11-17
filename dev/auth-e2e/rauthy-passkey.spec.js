import { test, expect } from '@playwright/test';

// A virtual, platform-like FIDO2 authenticator. No credential leaves Chromium,
// and no private keys or browser state are ever logged or uploaded in CI.
test('Rauthy account can enroll a passkey and use it for OpenClub sign-in', async ({ page, context }) => {
  const password = process.env.OPENCLUB_RAUTHY_E2E_USER_PASSWORD;
  if (!password) throw new Error('Synthetic Rauthy password missing');
  test.setTimeout(150000);
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

    // Simulate a lost security key. After MFA enrollment, a password alone
    // must not silently downgrade authentication or issue an OpenClub session.
    await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
    await context.clearCookies();
    await page.goto('https://localhost:5443/');
    await page.getByRole('link', { name: 'Sign in' }).click();
    await page.locator('input[type="email"]').fill('openclub-rauthy-ci@example.invalid');
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await page.locator('input[type="password"]:visible').fill(password);
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);

    // A separate, operator-authorized action is required to recover a user
    // whose ONLY WebAuthn factor was lost. OpenClub never receives this key.
    const recoveryKey = process.env.OPENCLUB_RAUTHY_RECOVERY_KEY;
    if (!recoveryKey) throw new Error('Disposable scoped recovery key missing');
    const usersUrl = 'https://localhost:8443/auth/v1/users';
    const unauthorized = await context.request.get(usersUrl);
    expect([401, 403], 'Anonymous user enumeration must be denied').toContain(unauthorized.status());
    const adminHeaders = { Authorization: `API-Key ${recoveryKey}` };
    const users = await context.request.get(usersUrl, { headers: adminHeaders });
    expect(users.status(), 'Recovery operator must be able to inspect users').toBe(200);
    const response = await users.json();
    const entries = Array.isArray(response) ? response :
      (response.users ?? response.data?.users ?? response.data ?? response.values ?? []);
    expect(Array.isArray(entries), 'Unexpected Rauthy user listing format').toBe(true);
    const matches = entries.filter(user => user.email === 'openclub-rauthy-ci@example.invalid');
    expect(matches, 'Revocation requires an exact verified synthetic account match').toHaveLength(1);
    const userId = matches[0].id;
    expect(typeof userId).toBe('string');
    expect(userId.length).toBeGreaterThan(0);
    const revoked = await context.request.delete(
      `https://localhost:8443/auth/v1/users/${encodeURIComponent(userId)}/webauthn/delete/${encodeURIComponent('Synthetic virtual passkey')}`,
      { headers: adminHeaders, data: {} },
    );
    expect(revoked.status(), 'Authorized operator must revoke the named lost credential').toBe(200);

    // Fresh browser login on a replacement device must now succeed using
    // the still-verified password. The lost authenticator was already removed
    // from Chromium, so this cannot rely on a stale WebAuthn signature.
    await context.clearCookies();
    await page.goto('https://localhost:5443/');
    await page.getByRole('link', { name: 'Sign in' }).click();
    await page.locator('input[type="email"]').fill('openclub-rauthy-ci@example.invalid');
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await page.locator('input[type="password"]:visible').fill(password);
    await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
    await expect(page).toHaveURL('https://localhost:5443/');
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
    expect((await context.request.get('/api/v1/club')).status()).toBe(403);

    // Enroll an independent replacement authenticator before ending the
    // recovery; the original virtual key is permanently gone.
    const { authenticatorId: replacementId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
      options: {
        protocol: 'ctap2', transport: 'internal', hasResidentKey: true,
        hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true,
      },
    });
    try {
      await page.goto('https://localhost:8443/auth/v1/account');
      await page.getByText('MFA', { exact: true }).first().click();
      await page.getByRole('button', { name: 'Register New Passkey' }).click();
      await page.getByPlaceholder('Current Password').fill(password);
      await page.getByRole('button', { name: 'Authenticate', exact: true }).click();
      await expect(page.locator('dialog[open]')).not.toBeVisible({ timeout: 10000 });
      await page.getByPlaceholder('Passkey Name').fill('Replacement virtual passkey');
      await page.getByRole('button', { name: 'Register', exact: true }).click();
      await expect.poll(async () =>
        (await cdp.send('WebAuthn.getCredentials', { authenticatorId: replacementId })).credentials.length,
      ).toBe(1);
    } finally {
      await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId: replacementId });
    }
    await context.clearCookies();
    await page.goto('https://localhost:5443/');
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
  } finally {
    await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId }).catch(() => {});
    await cdp.detach().catch(() => {});
  }
});
