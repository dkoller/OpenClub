import { test, expect } from '@playwright/test';
import { fixtureSql, show } from './club-fixtures.js';

// Runs after all club tests (teardown): its own sign-ins as the administrator and a second synthetic user.

test('an administrator invites a member, a second synthetic user accepts, and unlinking ends that session', async ({ page, context, browser }) => {
  test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
    'Invitations write synthetic data and must only run against the disposable CI database');
  test.setTimeout(180000);
  const password = process.env.OPENCLUB_E2E_USER_PASSWORD;
  if (!password) throw new Error('Disposable synthetic login password required');
  const signIn = async (target, username) => {
    await target.goto('/');
    await target.locator('#language').selectOption('en');
    await target.getByRole('link', { name: 'Sign in' }).click();
    await target.locator('#username').fill(username);
    await target.locator('#password').fill(password);
    await Promise.all([
      target.waitForURL(/^http:\/\/localhost:5173\//),
      target.locator('#kc-login').click(),
    ]);
  };

  // A synthetic adult with no account, created directly so the journey stays about invitations.
  fixtureSql("DELETE FROM members WHERE last_name = 'Invitee' AND account_id IS NULL");
  fixtureSql("INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone) " +
    "VALUES (gen_random_uuid(), 'Ines', 'Invitee', DATE '1990-05-05', 'ines@example.invalid', '+41000000009')");
  const invitee = await browser.newContext({ baseURL: 'http://localhost:5173' });
  try {
    await signIn(page, 'openclub-ci-user');
    await show(page, '#/admin/members/invitations');
    const region = page.getByRole('region', { name: 'Invitations and account links' });
    await expect(region).toBeVisible();
    await region.getByRole('textbox', { name: 'Find member' }).fill('Invitee');
    await region.getByRole('button', { name: 'Search' }).click();
    await expect(region.getByRole('combobox', { name: 'Member' })).toContainText('Ines Invitee');
    await region.getByRole('button', { name: 'Create invitation' }).click();
    await expect(region.getByText('Invitation created.', { exact: true })).toBeVisible();
    const link = await region.getByRole('textbox', { name: /Invitation link/ }).inputValue();
    expect(link).toMatch(/^http:\/\/localhost:5173\/#invite=[A-Za-z0-9_-]{43}$/);
    // A second invitation while one is open is refused with the generic explanation.
    await region.getByRole('button', { name: 'Create invitation' }).click();
    await expect(region.getByText(/cannot be invited or unlinked/)).toBeVisible();

    // The second synthetic user opens the link, signs in (the secret survives the round trip) and accepts.
    const second = await invitee.newPage();
    await second.goto(link);
    await expect(second).toHaveURL('http://localhost:5173/');
    await second.locator('#language').selectOption('en');
    await second.getByRole('link', { name: 'Sign in' }).click();
    await second.locator('#username').fill('openclub-ci-invitee');
    await second.locator('#password').fill(password);
    await Promise.all([
      second.waitForURL(/^http:\/\/localhost:5173\//),
      second.locator('#kc-login').click(),
    ]);
    await expect(second.getByText('This account is not yet a club member.', { exact: true })).toBeVisible();
    const accept = second.getByRole('region', { name: 'Accept an invitation' });
    await expect(accept.getByRole('textbox', { name: 'Invitation code or link' })).toHaveValue(/^[A-Za-z0-9_-]{43}$/);
    // A wrong code gets the one generic answer and changes nothing.
    const secret = await accept.getByRole('textbox', { name: 'Invitation code or link' }).inputValue();
    await accept.getByRole('textbox', { name: 'Invitation code or link' }).fill('A'.repeat(43));
    await accept.getByRole('button', { name: 'Accept invitation' }).click();
    await expect(accept.getByRole('alert')).toHaveText('The invitation is invalid, expired or already used.');
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('0');
    await accept.getByRole('textbox', { name: 'Invitation code or link' }).fill(secret);
    await accept.getByRole('button', { name: 'Accept invitation' }).click();
    await expect(second.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('1');
    // Linking grants nothing: the new member sees no administration.
    expect((await invitee.request.get('/api/v1/club')).status()).toBe(200);
    expect((await invitee.request.get('/api/v1/members')).status()).toBe(403);

    // The administrator unlinks; the member's session and access end at once.
    await region.getByRole('textbox', { name: 'Find member' }).fill('Invitee');
    await region.getByRole('button', { name: 'Search' }).click();
    await expect(region.getByRole('combobox', { name: 'Member' })).toContainText('Ines Invitee');
    await region.getByRole('button', { name: 'Unlink account' }).click();
    await expect(region.getByText('Account unlinked.', { exact: true })).toBeVisible();
    expect((await invitee.request.get('/api/v1/club')).status()).toBe(401);
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('0');
  } finally {
    await invitee.close();
    fixtureSql("DELETE FROM members WHERE last_name = 'Invitee'");
    await context.clearCookies();
  }
});
