import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// The same invitation journey as the Keycloak suite, against real Rauthy (ADR-0006: both providers stay
// supported). It bootstraps a disposable club through the real operator CLI and removes it afterwards, so
// the other Rauthy specs still see accounts that are not club members, whatever order the files run in.
const origin = 'https://localhost:5443';
const root = fileURLToPath(new URL('../../', import.meta.url));

const fixtureSql = (sql) => String(execFileSync('docker', [
  'compose', '-f', 'compose.yaml', '-f', 'compose.rauthy.eval.yaml',
  'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-t', '-A',
  '-U', 'openclub', '-d', 'openclub', '-c', sql,
], { cwd: root, stdio: 'pipe' })).trim();

// Only synthetic rows exist in this disposable database; the audit trail is append-only and stays.
const dropDisposableClub = () => fixtureSql('DELETE FROM members; DELETE FROM teams; DELETE FROM clubs');

async function signIn(page, email, password) {
  await page.locator('#language').selectOption('en');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/^https:\/\/localhost:8443\/auth\/v1\//);
  await page.locator('input[type="email"]').fill(email);
  await page.locator('form').getByRole('button', { name: 'Login', exact: true }).click();
  await page.locator('input[type="password"]:visible').fill(password);
  await Promise.all([
    page.waitForURL(url => url.origin === origin),
    page.locator('form').getByRole('button', { name: 'Login', exact: true }).click(),
  ]);
}

test('Rauthy: an administrator invites a member, a second Rauthy user accepts, and unlinking ends that session', async ({ page, context, browser }) => {
  test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
    'Invitations bootstrap a club and must only run against the disposable CI database');
  test.setTimeout(180000);
  const adminPassword = process.env.OPENCLUB_RAUTHY_ADMIN_PASSWORD;
  const inviteePassword = process.env.OPENCLUB_RAUTHY_INVITEE_PASSWORD;
  if (!adminPassword || !inviteePassword) throw new Error('Disposable synthetic Rauthy passwords required');
  expect(fixtureSql('SELECT COUNT(*) FROM clubs'), 'starts without a club').toBe('0');

  const invitee = await browser.newContext({ baseURL: origin, ignoreHTTPSErrors: true });
  try {
    await page.goto('/');
    await signIn(page, 'openclub-rauthy-admin-ci@example.invalid', adminPassword);
    await expect(page.getByRole('status')).toHaveText('This account is not yet a club member.');
    const { accountId } = await (await context.request.get('/api/v1/auth/me')).json();
    expect(accountId).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i);
    // The real restricted operator CLI from the already built distribution (no forged session).
    execFileSync('java', ['-cp', 'build/install/openclub-api/lib/*', 'org.openclub.api.club.BootstrapCliKt'], {
      cwd: fileURLToPath(new URL('../../apps/api/', import.meta.url)),
      timeout: 120000,
      stdio: 'pipe',
      env: {
        ...process.env,
        OPENCLUB_BOOTSTRAP_CONFIRM: 'create-first-club',
        OPENCLUB_BOOTSTRAP_ACCOUNT_ID: accountId,
        OPENCLUB_BOOTSTRAP_CLUB_NAME: 'Synthetic Rauthy Club',
        OPENCLUB_BOOTSTRAP_CLUB_SLUG: 'synthetic-rauthy',
        OPENCLUB_BOOTSTRAP_FIRST_NAME: 'Synthetic',
        OPENCLUB_BOOTSTRAP_LAST_NAME: 'Administrator',
        OPENCLUB_BOOTSTRAP_BIRTH_DATE: '1990-01-01',
        OPENCLUB_BOOTSTRAP_EMAIL: 'administrator@example.invalid',
        OPENCLUB_BOOTSTRAP_PHONE: '+41000000000',
      },
    });
    fixtureSql("INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone) " +
      "VALUES (gen_random_uuid(), 'Rita', 'Invitee', DATE '1990-05-05', 'rita@example.invalid', '+41000000009')");
    // A fictional minor for the guardian steps below (OpenAPI 1.6.0).
    fixtureSql("INSERT INTO members (id, first_name, last_name, date_of_birth) " +
      "VALUES (gen_random_uuid(), 'Tim', 'Minor', current_date - interval '10 years')");
    await page.reload();
    await expect(page.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();

    const region = page.getByRole('region', { name: 'Invitations and account links' });
    await region.getByRole('textbox', { name: 'Find member' }).fill('Invitee');
    await region.getByRole('button', { name: 'Search' }).click();
    await expect(region.getByRole('combobox', { name: 'Member' })).toContainText('Rita Invitee');
    await region.getByRole('button', { name: 'Create invitation' }).click();
    await expect(region.getByText('Invitation created.', { exact: true })).toBeVisible();
    const link = await region.getByRole('textbox', { name: /Invitation link/ }).inputValue();
    expect(link).toMatch(/^https:\/\/localhost:5443\/#invite=[A-Za-z0-9_-]{43}$/);

    // The invitee opens the link and signs in at Rauthy; the secret survives the provider round trip.
    const second = await invitee.newPage();
    await second.goto(link);
    await expect(second).toHaveURL(origin + '/');
    await signIn(second, 'openclub-rauthy-invitee-ci@example.invalid', inviteePassword);
    await expect(second.getByText('This account is not yet a club member.', { exact: true })).toBeVisible();
    const accept = second.getByRole('region', { name: 'Accept an invitation' });
    const code = accept.getByRole('textbox', { name: 'Invitation code or link' });
    await expect(code).toHaveValue(/^[A-Za-z0-9_-]{43}$/);
    const secret = await code.inputValue();
    await code.fill('A'.repeat(43));
    await accept.getByRole('button', { name: 'Accept invitation' }).click();
    await expect(accept.getByRole('alert')).toHaveText('The invitation is invalid, expired or already used.');
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('0');
    await code.fill(secret);
    await accept.getByRole('button', { name: 'Accept invitation' }).click();
    await expect(second.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('1');
    // Linking grants nothing beyond membership.
    expect((await invitee.request.get('/api/v1/club')).status()).toBe(200);
    expect((await invitee.request.get('/api/v1/members')).status()).toBe(403);

    // Guardians with Rauthy accounts: the administrator records the invitee as Tim's guardian; the guardian
    // sees only "My children", reads Tim and replaces only his contacts, and still has no administration rights.
    const guardians = page.getByRole('region', { name: 'Guardians' });
    await guardians.getByLabel('Find a minor').fill('Tim');
    await guardians.getByRole('button', { name: 'Search' }).first().click();
    await expect(guardians.getByText('No guardians recorded.')).toBeVisible();
    await guardians.getByLabel('Find a guardian').fill('Rita Invitee');
    await guardians.getByRole('button', { name: 'Search' }).nth(1).click();
    await expect(guardians.getByRole('combobox', { name: 'Guardian' })).toContainText('Rita Invitee');
    await guardians.getByRole('button', { name: 'Add as guardian' }).click();
    await expect(guardians.getByText('Guardian added.')).toBeVisible();
    expect(fixtureSql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'GUARDIAN_LINKED'")).toBe('1');
    await second.reload();
    const children = second.getByRole('region', { name: 'My children' });
    const tim = children.getByRole('listitem').filter({ hasText: 'Tim Minor' });
    await tim.getByRole('button', { name: 'Open' }).click();
    await expect(tim.getByText('Date of birth')).toBeVisible();
    await tim.getByLabel('Phone').fill('+41000000066');
    await tim.getByRole('button', { name: 'Save contacts' }).click();
    await expect(children.getByText('Contacts saved.')).toBeVisible();
    expect(fixtureSql("SELECT phone FROM members WHERE first_name = 'Tim' AND last_name = 'Minor'")).toBe('+41000000066');
    expect((await invitee.request.get('/api/v1/members')).status()).toBe(403);
    await expect(second.getByRole('region', { name: 'Guardians' })).toHaveCount(0);
    await expect(second.getByRole('region', { name: 'Invitations and account links' })).toHaveCount(0);

    // Unlinking ends the member's session and access at once.
    await region.getByRole('textbox', { name: 'Find member' }).fill('Invitee');
    await region.getByRole('button', { name: 'Search' }).click();
    await expect(region.getByRole('combobox', { name: 'Member' })).toContainText('Rita Invitee');
    await region.getByRole('button', { name: 'Unlink account' }).click();
    await expect(region.getByText('Account unlinked.', { exact: true })).toBeVisible();
    expect((await invitee.request.get('/api/v1/club')).status()).toBe(401);
    expect(fixtureSql("SELECT COUNT(*) FROM members WHERE last_name = 'Invitee' AND account_id IS NOT NULL")).toBe('0');
    // Unlinking the guardian's login ends the guardianship in the same statement (V19).
    expect(fixtureSql('SELECT COUNT(*) FROM member_guardianships')).toBe('0');
  } finally {
    await invitee.close();
    await context.clearCookies();
    dropDisposableClub();
  }
});
