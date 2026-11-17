import { test, expect } from '@playwright/test';

const email = 'openclub-rauthy-otp-ci@example.invalid';
const inbox = 'http://127.0.0.1:8025';

// Never print codes, message contents, browser storage or authentication tokens.
// Returns this address's messages as { id, code } (code may be null), so a new
// email is recognised by its Mailpit message ID, never by its code differing or
// by list order: a provider may legitimately resend an identical, still-valid code.
async function mailCodes(request) {
  const res = await request.get(`${inbox}/api/v1/messages`);
  if (!res.ok()) return [];
  const list = await res.json();
  const found = [];
  for (const summary of list.messages ?? []) {
    if (!summary.To?.some(to => to.Address === email)) continue;
    const details = await request.get(`${inbox}/api/v1/message/${encodeURIComponent(summary.ID)}`);
    if (!details.ok()) continue;
    const message = await details.json();
    // The provider may group digits in its template. Use only the
    // message text/HTML in memory; never log the code or email content.
    const body = [message.Text ?? '', message.HTML ?? ''].join(' ');
    let code = null;
    for (const candidate of body.match(/(?:\d[\s-]*){6,8}/g) ?? []) {
      const digits = candidate.replace(/\D/g, '');
      if (digits.length === 6) { code = digits; break; }
    }
    found.push({ id: summary.ID, code });
  }
  return found;
}

/** Waits for a message not in `seen` and returns its code; the timeout message is code-free. */
async function nextCode(request, seen) {
  let result = null;
  let observed = [];
  await expect.poll(async () => {
    observed = await mailCodes(request);
    const fresh = observed.find(message => !seen.has(message.id));
    if (fresh?.code) result = fresh;
    return result !== null;
  }, {
    timeout: 15000,
    message: () => `no new OTP email: ${observed.length} message(s) for the address, ` +
      `${observed.filter(m => !seen.has(m.id)).length} new, ` +
      `${observed.filter(m => !seen.has(m.id) && !m.code).length} new without a 6-digit code`,
  }).toBe(true);
  seen.add(result.id);
  return result.code;
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
  const seen = new Set();
  const code = await nextCode(context.request, seen);
  await page.locator('input[name="otp"]:visible').fill(code);
  await expect(page.getByText('Email-based OTP', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Register New OTP' })).not.toBeVisible();

  // Everything delivered so far (enrollment and any other provider mail) is old:
  // only a message arriving after this point can carry the login factor.
  for (const message of await mailCodes(context.request)) seen.add(message.id);
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
  // The login factor must arrive as a new email after enrollment.
  const loginCode = await nextCode(context.request, seen);
  await page.locator('input[name="otp"]:visible').fill(loginCode);
  await expect(page).toHaveURL('https://localhost:5443/');
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
  expect((await context.request.get('/api/v1/club')).status()).toBe(403);
  await page.getByRole('button', { name: 'Sign out' }).click();
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
});
