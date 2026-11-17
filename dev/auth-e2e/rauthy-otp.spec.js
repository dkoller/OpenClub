import { test, expect } from '@playwright/test';

const email = 'openclub-rauthy-otp-ci@example.invalid';
const inbox = 'http://127.0.0.1:8025';

// Never print codes, message contents, browser storage or authentication tokens.
async function mailCode(request) {
  const res = await request.get(`${inbox}/api/v1/messages`);
  if (!res.ok()) return null;
  const list = await res.json();
  // Mailpit may store the provider's OTP email as HTML rather than plain
  // text; inspect both without logging either representation.
  for (const entry of list.messages ?? []) {
    if (!JSON.stringify(entry.To ?? '').includes(email)) continue;
    const details = await request.get(`${inbox}/api/v1/message/${encodeURIComponent(entry.ID)}`);
    if (!details.ok()) continue;
    const message = await details.json();
    const code = `${message.Text ?? ''} ${message.HTML ?? ''}`.match(/\b\d{6}\b/)?.[0];
    if (code) return code;
  }
  return null;
}

test('Rauthy registers email OTP and verifies password plus delivered OTP before OpenClub session', async ({ page, context }) => {
  const password = process.env.OPENCLUB_RAUTHY_OTP_PASSWORD;
  if (!password) throw new Error('Disposable OTP user credential missing');
  test.setTimeout(90000);
  page.setDefaultTimeout(10000);

  await page.goto('https://localhost:8443/auth/v1/account');
  await page.locator('input[type="email"]').fill(email);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await page.locator('input[type="password"]:visible').fill(password);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await expect(page).toHaveURL(/\/auth\/v1\/account/);

  await page.getByText('MFA', { exact: true }).first().click();
  await expect(page.getByRole('button', { name: 'Register New OTP' })).toBeVisible();

  // Obtain a password-verified MFA modification token first. Registration
  // then runs with this token instead of relying on a modal's internal state.
  await page.getByRole('button', { name: 'Register New Passkey' }).click();
  await page.getByPlaceholder('Current Password').fill(password);
  await page.getByRole('button', { name: 'Authenticate', exact: true }).click();
  await expect(page.locator('dialog[open]')).not.toBeVisible({ timeout: 10000 });
  await page.getByRole('button', { name: 'Cancel', exact: true }).first().click();

  const [otpRegistration] = await Promise.all([
    page.waitForResponse(response => response.url().includes('/otp') && response.request().method() === 'POST'),
    page.getByRole('button', { name: 'Register New OTP' }).click(),
  ]);
  expect(otpRegistration.status(), 'Rauthy must accept OTP enrollment').toBe(201);
  await expect.poll(async () => {
    const inboxResponse = await context.request.get(`${inbox}/api/v1/messages`);
    if (!inboxResponse.ok()) return -1;
    return (await inboxResponse.json()).total ?? 0;
  }, { timeout: 15000 }).toBeGreaterThan(0);
  let code = null;
  await expect.poll(async () => {
    code = await mailCode(context.request);
    return code !== null;
  }, { timeout: 15000 }).toBe(true);
  await page.locator('input[name="otp"]:visible').fill(code);
  await expect(page.getByText('Email-based OTP', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Register New OTP' })).not.toBeVisible();

  await page.getByRole('button', { name: 'Logout', exact: true }).click();
  await context.clearCookies();
  await page.goto('https://localhost:5443/');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.locator('input[type="email"]').fill(email);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await page.locator('input[type="password"]:visible').fill(password);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();

  // Provider must require the independent email factor, not grant a session
  // from the password before the OTP is submitted.
  await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
  let loginCode = null;
  await expect.poll(async () => {
    const candidate = await mailCode(context.request);
    if (candidate && candidate !== code) loginCode = candidate;
    return loginCode !== null;
  }, { timeout: 15000 }).toBe(true);
  await page.locator('input[name="otp"]:visible').fill(loginCode);
  await expect(page).toHaveURL('https://localhost:5443/');
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
  expect((await context.request.get('/api/v1/club')).status()).toBe(403);
  await page.getByRole('button', { name: 'Sign out' }).click();
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
});
