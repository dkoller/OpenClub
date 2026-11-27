import { expect, show, test } from './club-fixtures.js';

// Club name, the public member list and the administrator's own profile.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('the club name is changed and shown, and the public list names the administrator', async ({ page, context }) => {
  const clubSettings = page.getByRole('region', { name: 'Edit club name' });
  await show(page, '#/admin/settings');
  await clubSettings.getByRole('textbox', { name: 'Club name' }).fill('Synthetic Updated Club');
  await clubSettings.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Synthetic Updated Club' })).toBeVisible();
  const updatedClub = await context.request.get('/api/v1/club');
  expect(updatedClub.status()).toBe(200);
  expect((await updatedClub.json()).name).toBe('Synthetic Updated Club');

  // The public directory renders the synthetic adult name, even after logout (editors see it under Members).
  await show(page, '#/admin/members/public');
  await expect(page.getByRole('region', { name: 'Members', exact: true }).getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
  const publicNames = await context.request.get('/api/v1/public/members');
  expect(publicNames.status()).toBe(200);
  expect(await publicNames.json()).toContainEqual({ displayName: 'Synthetic Administrator' });
});

test('my profile saves contacts, notifications say no email, the public-list switch hides and shows', async ({ page, context }) => {
  // My profile (OpenAPI 1.9.0): the administrator changes their own phone; the form keeps the email it read.
  await show(page, '#/family/profile');
  const myProfile = page.getByRole('region', { name: 'My profile' });
  await expect(myProfile.getByText('Administrator', { exact: true })).toBeVisible();
  const ownEmail = await myProfile.getByLabel('Email', { exact: true }).inputValue();
  expect(ownEmail).toContain('@');
  await myProfile.getByLabel('Phone').fill('+41 79 000 00 01');
  await myProfile.getByRole('button', { name: 'Save contacts' }).click();
  await expect(myProfile.getByRole('status')).toHaveText('Contacts saved.');
  await page.reload();
  await expect(page.getByRole('region', { name: 'My profile' }).getByLabel('Phone')).toHaveValue('+41 79 000 00 01');
  await expect(page.getByRole('region', { name: 'My profile' }).getByLabel('Email', { exact: true })).toHaveValue(ownEmail);
  // Email notifications (OpenAPI 1.19.0): this test installation has no SMTP server and no push, so the page says so
  // and shows no switches that would do nothing.
  await show(page, '#/family/notifications');
  const mailSettings = page.getByRole('region', { name: 'Email notifications' });
  await expect(mailSettings.getByText('This club does not send emails.')).toBeVisible();
  await expect(mailSettings.getByLabel('New events')).toHaveCount(0);
  // The public-list switch (OpenAPI 1.15.0, controller decision C5): hidden, then shown again.
  await show(page, '#/family/profile');
  const listing = page.getByRole('region', { name: 'My profile' }).getByLabel('Show my name on the public member list');
  await expect(listing).toBeChecked();
  // A controlled box: click, then wait for the saved state (uncheck() would expect an immediate change).
  await listing.click();
  await expect(listing).not.toBeChecked();
  await expect(page.getByRole('region', { name: 'My profile' }).getByRole('status')).toHaveText('Setting saved.');
  expect(await (await context.request.get('/api/v1/public/members')).json())
    .not.toContainEqual({ displayName: 'Synthetic Administrator' });
  await page.getByRole('region', { name: 'My profile' }).getByLabel('Show my name on the public member list').click();
  await expect(page.getByRole('region', { name: 'My profile' }).getByLabel('Show my name on the public member list')).toBeChecked();
  await expect.poll(async () => (await context.request.get('/api/v1/public/members')).json())
    .toContainEqual({ displayName: 'Synthetic Administrator' });
});
