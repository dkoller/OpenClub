import { test, expect } from '@playwright/test';

const origin = 'http://localhost:5173';
const keycloak = 'http://localhost:8180';
const realm = 'openclub-dev';
const adminRoot = `${keycloak}/admin/realms/${realm}`;

test('real Keycloak RSA signing-key rotation refreshes API login without restart', async ({ page, context, request }) => {
  const userPassword = process.env.OPENCLUB_E2E_USER_PASSWORD;
  const adminPassword = process.env.KEYCLOAK_DEV_ADMIN_PASSWORD;
  if (!userPassword || !adminPassword) throw new Error('Disposable fixture credentials required');

  // First login warms the API cache with Keycloak's initial signing key.
  await page.goto('/api/v1/auth/login');
  await page.locator('#username').fill('openclub-ci-user');
  await page.locator('#password').fill(userPassword);
  await Promise.all([page.waitForURL(`${origin}/`), page.locator('#kc-login').click()]);
  expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);

  const adminLogin = await request.post(`${keycloak}/realms/master/protocol/openid-connect/token`, {
    form: { grant_type: 'password', client_id: 'admin-cli',
      username: 'openclub-local-admin', password: adminPassword },
  });
  expect(adminLogin.status()).toBe(200);
  const accessToken = (await adminLogin.json()).access_token;
  expect(typeof accessToken).toBe('string');
  const headers = { Authorization: `Bearer ${accessToken}` };
  const getJson = async path => {
    const response = await request.get(`${adminRoot}${path}`, { headers });
    expect(response.status()).toBe(200);
    return response.json();
  };
  const activeRsa = keys => keys.filter(key => key.algorithm === 'RS256' && key.status === 'ACTIVE')
    .sort((a, b) => b.providerPriority - a.providerPriority)[0];
  const before = await getJson('/keys');
  const initial = activeRsa(before.keys);
  expect(initial?.kid).toBeTruthy();
  const realmInfo = await getJson('');
  let providerId;
  try {
    const priority = Math.max(...before.keys.map(key => key.providerPriority ?? 0)) + 100;
    const create = await request.post(`${adminRoot}/components`, {
      headers,
      data: {
        name: 'openclub-ci-rotation',
        providerType: 'org.keycloak.keys.KeyProvider',
        providerId: 'rsa-generated',
        parentId: realmInfo.id,
        config: { priority: [String(priority)], enabled: ['true'],
          active: ['true'], keySize: ['2048'] },
      },
    });
    expect(create.status()).toBe(201);
    const location = create.headers().location ?? '';
    providerId = location.split('/').pop();
    expect(providerId).toMatch(/^[0-9a-f-]{36}$/);

    const after = await getJson('/keys');
    const rotated = activeRsa(after.keys);
    expect(rotated?.kid).toBeTruthy();
    expect(rotated.kid).not.toBe(initial.kid);
    const publicKeys = await request.get(`${keycloak}/realms/${realm}/protocol/openid-connect/certs`);
    expect(publicKeys.status()).toBe(200);
    expect((await publicKeys.json()).keys.some(key => key.kid === rotated.kid)).toBe(true);

    // Existing session remains valid; a fresh code exchange must validate the new kid.
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
    await page.goto('/api/v1/auth/login');
    await page.waitForURL(`${origin}/`);
    expect((await context.request.get('/api/v1/auth/me')).status()).toBe(200);
  } finally {
    // Undo the rotation even when an assertion fails; disposable CI realm only.
    if (providerId) {
      const removal = await request.delete(`${adminRoot}/components/${providerId}`, { headers });
      expect(removal.status()).toBe(204);
    }
  }
});
