import { test, expect } from '@playwright/test';

// Separate disposable account: OTP enrollment must never alter the passkey
// acceptance account. Mailpit is isolated to the CI Compose network.
test('Rauthy email OTP requires a delivered code before OpenClub login', async ({ page, context, request }) => {
  const password = process.env.OPENCLUB_RAUTHY_OTP_USER_PASSWORD;
  if (!password) throw new Error('Synthetic OTP password required');
  const email = 'openclub-rauthy-otp-ci@example.invalid';
  test.setTimeout(90000);

  await page.goto('https://localhost:8443/auth/v1/account');
  await page.locator('input[type="email"]').fill(email);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await page.locator('input[type="password"]:visible').fill(password);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await expect(page).toHaveURL(/\/auth\/v1\/account/);
  await page.getByText('MFA', { exact: true }).first().click();
  await page.getByRole('button', { name: /register.*otp/i }).click();
  await page.getByPlaceholder('Current Password').fill(password);
  await page.getByRole('button', { name: 'Authenticate', exact: true }).click();

  // Rauthy must deliver the enrollment code through the actual SMTP sink.
  let code = null;
  for (let attempt = 0; attempt < 30 && !code; attempt++) {
    const res = await request.get('http://localhost:8025/api/v1/messages');
    if (res.ok()) {
      const messages = (await res.json()).messages ?? [];
      const match = messages.find(m => JSON.stringify(m.To ?? m.to ?? '').includes(email));
      if (match) {
        const detail = await request.get(`http://localhost:8025/api/v1/message/${match.ID}`);
        const text = JSON.stringify(await detail.json());
        code = text.match(/\\b\\d{6}\\b/)?.[0] ?? null;
      }
    }
    if (!code) await page.waitForTimeout(500);
  }
  expect(code, 'Rauthy must send an OTP to the isolated SMTP sink').not.toBeNull();

  // Keep the actual enrollment UI and OTP separate from the test logs.
  await page.locator('input[inputmode="numeric"]').fill(code);
  await page.getByRole('button', { name: /verify|confirm|register/i }).last().click();
  await page.getByRole('button', { name: 'Logout', exact: true }).click();
  await context.clearCookies();

  await page.goto('https://localhost:5443/');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.locator('input[type="email"]').fill(email);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await page.locator('input[type="password"]:visible').fill(password);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
});
