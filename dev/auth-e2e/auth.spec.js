import { test, expect } from '@playwright/test';

const origin = 'http://localhost:5173';
const sessionName = 'openclub_dev_session';

test('real Keycloak login, replay rejection, session replacement and logout', async ({ page, context, playwright }) => {
  const password = process.env.OPENCLUB_E2E_USER_PASSWORD;
  if (!password) throw new Error('Synthetic user password is required');
  const anonymous = await context.request.get('/api/v1/auth/me');
  expect(anonymous.status()).toBe(401);
  let callback;
  page.on('request', request => {
    if (request.url().startsWith(`${origin}/api/v1/auth/callback?`)) callback = request.url();
  });
  await page.goto('/api/v1/auth/login');
  await expect(page).toHaveURL(/^http:\/\/localhost:8180\/realms\/openclub-dev\//);
  await page.locator('#username').fill('openclub-ci-user');
  await page.locator('#password').fill(password);
  await Promise.all([
    page.waitForURL(`${origin}/`),
    page.locator('#kc-login').click(),
  ]);
  const me = await context.request.get('/api/v1/auth/me');
  expect(me.status()).toBe(200);
  expect(me.headers()['cache-control']).toBe('no-store');
  const identity = await me.json();
  expect(Object.keys(identity).sort()).toEqual(['csrfToken', 'personId']);
  expect(identity.personId).toMatch(/^[0-9a-f-]{36}$/);
  expect(/^[A-Za-z0-9_-]{43}$/.test(identity.csrfToken)).toBe(true);
  const first = (await context.cookies(origin)).find(cookie => cookie.name === sessionName);
  expect(first).toBeDefined();
  expect(first.httpOnly).toBe(true);
  expect(first.sameSite).toBe('Lax');
  expect(first.path).toBe('/');
  expect(/^[A-Za-z0-9_-]{43}$/.test(first.value)).toBe(true);
  expect((await context.cookies(origin)).some(cookie => cookie.name === 'openclub_dev_login')).toBe(false);
  // Keep the previous handle outside the browser to prove server-side revocation.
  const oldSession = await playwright.request.newContext({
    baseURL: origin, extraHTTPHeaders: { Cookie: `${sessionName}=${first.value}` },
  });
  try {
    expect(callback).toBeDefined();
    const replay = await context.request.get(callback, { maxRedirects: 0 });
    expect(replay.status()).toBe(401);
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
    // Keycloak SSO should complete a second real authorization-code flow.
    await page.goto('/api/v1/auth/login');
    await page.waitForURL(`${origin}/`);
    const second = (await context.cookies(origin)).find(cookie => cookie.name === sessionName);
    expect(second.value === first.value).toBe(false);
    expect((await oldSession.get('/api/v1/auth/me')).status()).toBe(401);
    const current = await (await context.request.get('/api/v1/auth/me')).json();
    expect(current.personId === identity.personId).toBe(true);
    // Real same-origin browser fetch supplies Origin and the current cookie.
    const logout = await page.evaluate(async csrfToken => {
      const response = await fetch('/api/v1/auth/logout', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: '{}',
      });
      return response.status;
    }, current.csrfToken);
    expect(logout).toBe(204);
    expect((await context.cookies(origin)).some(cookie => cookie.name === sessionName)).toBe(false);
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(401);
    const revoked = await playwright.request.newContext({
      baseURL: origin, extraHTTPHeaders: { Cookie: `${sessionName}=${second.value}` },
    });
    try { expect((await revoked.get('/api/v1/auth/me')).status()).toBe(401); }
    finally { await revoked.dispose(); }
  } finally { await oldSession.dispose(); }
});
