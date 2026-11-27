import { adminAccountId, ensureTeam, expect, fixtureSql, openForm, openMember, show, test } from './club-fixtures.js';

// Real database authorization during a live login, and lost sessions.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('removing a real grant during a login hides editors and names at once', async ({ page, context }) => {
  // Exercise real database authorization during an existing Keycloak login.
  const accountId = adminAccountId();
  const fixtureGrant = (verb, capability = 'EDIT_MEMBERS') => {
    if (!['EDIT_MEMBERS', 'VIEW_MEMBER_DIRECTORY'].includes(capability)) {
      throw new Error('Unrecognized synthetic fixture capability');
    }
    const command = verb === 'remove'
      ? "DELETE FROM club_capability_grants WHERE member_id = " +
          "(SELECT id FROM members WHERE account_id = '" + accountId + "') " +
          "AND capability = '" + capability + "'"
      : "INSERT INTO club_capability_grants (member_id, capability) " +
          "SELECT id, '" + capability + "' FROM members WHERE account_id = '" + accountId + "' " +
          "ON CONFLICT DO NOTHING";
    fixtureSql(command);
  };
  await show(page, '#/admin/members');
  fixtureGrant('remove');
  try {
    expect((await context.request.get('/api/v1/members?q=Synthetic')).status()).toBe(200);
    const adminMember = page.getByRole('region', { name: 'Manage members' })
      .getByRole('listitem').filter({ hasText: 'Synthetic Administrator' });
    await expect(adminMember).toBeVisible();
    // Editing starts with the restricted detail read, which the server now refuses: no form, no write.
    await openMember(adminMember);
    const readResponse = page.waitForResponse(response =>
      /\/api\/v1\/members\/[0-9a-f-]+\/details$/.test(new URL(response.url()).pathname));
    await adminMember.getByRole('button', { name: 'Edit' }).click();
    expect((await readResponse).status()).toBe(403);
    await expect(page.locator('li form').filter({ has: page.getByRole('button', { name: 'Save' }) })).toHaveCount(0);
    const deniedMemberAdmin = page.getByRole('region', { name: 'Manage members' });
    await expect(deniedMemberAdmin.getByRole('alert'))
      .toContainText('No permission to manage members.');
    await expect(deniedMemberAdmin.getByRole('button', { name: /^(Add member|New member)$/ })).toHaveCount(0);
    const readBack = await context.request.get('/api/v1/members?q=Synthetic');
    expect(readBack.status()).toBe(200);
    expect((await readBack.json()).items)
      .toContainEqual(expect.objectContaining({ firstName: 'Synthetic', lastName: 'Administrator' }));
  } finally {
    fixtureGrant('restore');
  }
  await page.reload();
  await expect(page.getByRole('region', { name: 'Manage members' })
    .getByRole('button', { name: 'New member' })).toBeVisible();

  // A real read-grant revocation must also hide already rendered member names
  // and edit controls, without affecting the separate anonymous name policy.
  fixtureGrant('remove', 'VIEW_MEMBER_DIRECTORY');
  try {
    expect((await context.request.get('/api/v1/members?q=Synthetic')).status()).toBe(403);
    const memberPanel = page.getByRole('region', { name: 'Manage members' });
    await memberPanel.getByLabel('Search names').fill('Synthetic');
    await memberPanel.getByRole('button', { name: 'Search' }).click();
    await expect(memberPanel.getByRole('alert'))
      .toContainText('No permission to manage members.');
    await expect(memberPanel.getByRole('listitem')).toHaveCount(0);
    await expect(memberPanel.getByRole('button', { name: /^(Add member|New member)$/ })).toHaveCount(0);
    const publicAfterRevocation = await context.request.get('/api/v1/public/members');
    expect(publicAfterRevocation.status()).toBe(200);
    expect(await publicAfterRevocation.json())
      .toContainEqual({ displayName: 'Synthetic Administrator' });
  } finally {
    fixtureGrant('restore', 'VIEW_MEMBER_DIRECTORY');
  }
  await page.reload();
  await expect(page.getByRole('region', { name: 'Manage members' })
    .getByRole('listitem').filter({ hasText: 'Synthetic Administrator' })).toBeVisible();
});

test('a failed next page keeps the loaded names and retries the same cursor', async ({ page }) => {
  // A failed subsequent page must preserve the already loaded member names,
  // retry the same opaque cursor and append only the missing results.
  fixtureSql(`INSERT INTO members (id, first_name, last_name, date_of_birth)
    SELECT gen_random_uuid(), 'PagingFixture', 'Synthetic' || lpad(n::text, 3, '0'),
           DATE '2015-01-01'
    FROM generate_series(1, 25) n`);
  try {
    const pagedMembers = page.getByRole('region', { name: 'Manage members' });
    await pagedMembers.getByLabel('Search names').fill('PagingFixture');
    await pagedMembers.getByRole('button', { name: 'Search' }).click();
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(20);
    await page.route('**/api/v1/members?**', route => route.abort(), { times: 1 });
    await pagedMembers.getByRole('button', { name: 'More members' }).click();
    await expect(pagedMembers.getByRole('alert'))
      .toContainText('Member management unavailable.');
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(20);
    await expect(pagedMembers.getByRole('button', { name: 'More members' })).toBeDisabled();
    await pagedMembers.getByRole('button', { name: 'Try again' }).click();
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(25);
    await expect(pagedMembers.getByRole('button', { name: 'More members' })).toHaveCount(0);
    await pagedMembers.getByLabel('Search names').fill('NoSuchPagingFixture');
    await pagedMembers.getByRole('button', { name: 'Search' }).click();
    await expect(pagedMembers.getByRole('listitem')).toHaveCount(0);
  } finally {
    fixtureSql("DELETE FROM members WHERE first_name = 'PagingFixture' " +
      "AND last_name LIKE 'Synthetic%' AND account_id IS NULL");
  }
  await page.reload();
});

test('a denied or expired permission start and lost sessions hide private data', async ({ page }) => {
  const teams = page.getByRole('region', { name: 'Teams', exact: true });
  // Reload and wait until the permission bootstrap has really answered, so the checks below never
  // pass vacuously before the page has settled (the flaky timeout seen on PRs #324 and #328).
  const reloadAndWaitForPermissionProbe = async () => {
    const probe = page.waitForResponse(response =>
      response.url().includes('/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000'),
    { timeout: 20000 });
    await page.reload();
    await probe;
    // Other regions (team member lists) also use role=status, so match the text, not the role.
    await expect(page.getByText('Signed in and authorized for this club.', { exact: true }))
      .toBeVisible({ timeout: 20000 });
  };

  // A genuine capability denial must not be turned into a retryable grant editor (nor offer the page).
  await show(page, '#/admin/permissions');
  await page.route('**/api/v1/grants?**', async route => {
    if (route.request().method() === 'GET' &&
        new URL(route.request().url()).searchParams.get('memberId') === '00000000-0000-0000-0000-000000000000') {
      await route.fulfill({ status: 403, body: 'Forbidden' });
    } else {
      await route.continue();
    }
  });
  await reloadAndWaitForPermissionProbe();
  await expect(page.getByRole('region', { name: 'Manage permissions' })).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Administration' }).getByRole('link', { name: 'Permissions' })).toHaveCount(0);
  await page.unroute('**/api/v1/grants?**');

  // Authentication expiry on the permission bootstrap must not expose a retryable editor.
  await page.route('**/api/v1/grants?**', async route => {
    if (route.request().method() === 'GET' &&
        new URL(route.request().url()).searchParams.get('memberId') === '00000000-0000-0000-0000-000000000000') {
      await route.fulfill({ status: 401, body: 'Unauthorized' });
    } else {
      await route.continue();
    }
  });
  await reloadAndWaitForPermissionProbe();
  await expect(page.getByRole('region', { name: 'Manage permissions' })).toHaveCount(0);
  await page.unroute('**/api/v1/grants?**');

  // A lost session must hide previously authorized private member names and controls.
  await page.route('**/api/v1/members?**', route =>
    new URL(route.request().url()).searchParams.get('q') === 'Synthetic'
      ? route.fulfill({ status: 401, body: 'Unauthorized' }) : route.continue());
  await show(page, '#/admin/members');
  const privateMembers = page.getByRole('region', { name: 'Manage members' });
  await expect(privateMembers).toBeVisible({ timeout: 20000 });
  await privateMembers.getByLabel('Search names').fill('Synthetic');
  await privateMembers.getByRole('button', { name: 'Search' }).click();
  await expect(privateMembers.getByRole('alert')).toContainText('No permission to manage members.');
  await expect(privateMembers.getByRole('listitem')).toHaveCount(0);
  await expect(privateMembers.getByRole('button', { name: /^(Add member|New member)$/ })).toHaveCount(0);
  await page.unroute('**/api/v1/members?**');
  // A session-expired team write must not leave previously authorized team names visible.
  await page.route('**/api/v1/teams', route =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 401, body: 'Unauthorized' }) : route.continue());
  await show(page, '#/team');
  await openForm(teams, 'New team');
  await teams.getByLabel('Team name').fill('Expired Session Team');
  await teams.getByRole('button', { name: 'Add team' }).click();
  await expect(teams).toHaveCount(0);
  await page.unroute('**/api/v1/teams');

  // A lost session on role-member search hides all privileged role controls.
  await page.reload();
  await show(page, '#/admin/members/roles');
  const rolesAfterReload = page.getByRole('region', { name: 'Manage club roles' });
  await page.route('**/api/v1/members?**', route =>
    new URL(route.request().url()).searchParams.get('q') === 'Mara'
      ? route.fulfill({ status: 401, body: 'Unauthorized' }) : route.continue());
  await rolesAfterReload.getByLabel('Find member').fill('Mara');
  await rolesAfterReload.getByRole('button', { name: 'Search' }).click();
  await expect(rolesAfterReload).toHaveCount(0);
  await page.unroute('**/api/v1/members?**');
});

test('an administrator without grants sees nothing private, a team grant shows one team', async ({ page, context }) => {
  const accountId = adminAccountId();
  ensureTeam('Juniors');
  ensureTeam('Seniors');
  // Real database authorization, no injected responses (#25): an ADMIN whose club grants are all
  // removed, then a member who may see exactly one team through a team-scoped grant.
  const adminMemberSql = "(SELECT id FROM members WHERE account_id = '" + accountId + "')";
  const originalGrants = fixtureSql(
    'SELECT capability FROM club_capability_grants WHERE member_id = ' + adminMemberSql)
    .split('\n').filter(Boolean);
  expect(originalGrants).toEqual(expect.arrayContaining(
    ['MANAGE_PERMISSIONS', 'EDIT_MEMBERS', 'VIEW_MEMBER_DIRECTORY']));
  for (const capability of originalGrants) expect(capability).toMatch(/^[A-Z_]+$/);
  // The step below deletes this member's team grants again, so it must start with none.
  expect(fixtureSql('SELECT COUNT(*) FROM team_capability_grants WHERE member_id = ' + adminMemberSql))
    .toBe('0');
  const allTeams = (await (await context.request.get('/api/v1/teams')).json()).items;
  expect(allTeams.length).toBeGreaterThan(1);
  const [visibleTeam, ...hiddenTeams] = allTeams;
  expect(visibleTeam.id).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i);
  const answered = (path, status) => page.waitForResponse(response =>
    new URL(response.url()).pathname === path && response.status() === status, { timeout: 20000 });
  let contactMember = '';
  try {
    // ADMIN role alone grants nothing: every private region fails closed on a real 403.
    fixtureSql('DELETE FROM club_capability_grants WHERE member_id = ' + adminMemberSql);
    const denied = [answered('/api/v1/members', 403), answered('/api/v1/teams', 403),
      answered('/api/v1/grants', 403)];
    await page.reload();
    await Promise.all(denied);
    // Without any grant, Team and Administration are not offered, and their addresses open Start.
    const mainNav = page.getByRole('navigation', { name: 'Main navigation' });
    await expect(mainNav.getByRole('link', { name: 'Start' })).toBeVisible();
    await expect(mainNav.getByRole('link', { name: 'Administration' })).toHaveCount(0);
    await expect(mainNav.getByRole('link', { name: 'Team' })).toHaveCount(0);
    await expect(page.getByRole('heading', { level: 1, name: 'Start' })).toBeVisible();
    // Private panels never render for a member without the grants (not even an empty form).
    for (const name of ['Manage members', 'Manage permissions', 'Invitations and account links', 'Guardians',
      'Archived members', 'Manage club roles', 'Edit club name']) {
      await expect(page.getByRole('region', { name }), name).toHaveCount(0);
    }
    for (const part of ['invitations', 'guardians', 'roles', 'transfer', 'archived']) {
      await show(page, '#/admin/members/' + part);
      await expect(page.getByRole('heading', { level: 1, name: 'Start' }), part).toBeVisible();
      await expect(page.getByRole('navigation', { name: 'More tasks' })).toHaveCount(0);
    }
    await expect(page.getByRole('region', { name: 'Teams', exact: true })).toHaveCount(0);
    expect((await context.request.get('/api/v1/members/00000000-0000-0000-0000-000000000000/export')).status()).toBe(403);
    for (const path of ['/api/v1/members', '/api/v1/teams', '/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000']) {
      expect((await context.request.get(path)).status()).toBe(403);
    }
    // Membership alone still shows the club and the approved anonymous directory.
    expect((await context.request.get('/api/v1/club')).status()).toBe(200);
    expect(await (await context.request.get('/api/v1/public/members')).json())
      .toContainEqual({ displayName: 'Synthetic Administrator' });

    // Cross-team boundary: a grant for one team shows that team and no other.
    fixtureSql("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES ('" +
      visibleTeam.id + "', " + adminMemberSql + ", 'VIEW_TEAMS')");
    const listed = answered('/api/v1/teams', 200);
    await page.reload();
    await listed;
    await show(page, '#/team');
    const teamsRegion = page.getByRole('region', { name: 'Teams', exact: true });
    await expect(teamsRegion.getByRole('listitem').filter({ hasText: visibleTeam.name })).toHaveCount(1);
    for (const hidden of hiddenTeams) {
      await expect(teamsRegion.getByRole('listitem').filter({ hasText: hidden.name })).toHaveCount(0);
      expect((await context.request.get('/api/v1/teams/' + hidden.id + '/members')).status()).toBe(403);
    }
    expect((await (await context.request.get('/api/v1/teams')).json()).items.map(team => team.id))
      .toEqual([visibleTeam.id]);

    // Contact reads (OpenAPI 1.3.0) follow the same team boundary, on real grants: VIEW_TEAMS reads no
    // contacts, a team-scoped MANAGE_TEAMS reads only the manager fields of that team's active members,
    // every read is audited, and a member of another team stays refused.
    contactMember = fixtureSql("INSERT INTO members (id, first_name, last_name, date_of_birth, phone) " +
      "VALUES (gen_random_uuid(), 'Contact', 'Fixture', DATE '2014-01-01', '+41000000088') RETURNING id").split('\n')[0];
    expect(contactMember).toMatch(/^[0-9a-f-]{36}$/);
    fixtureSql("INSERT INTO member_team_assignments (team_id, member_id) VALUES ('" + visibleTeam.id + "', '" + contactMember + "')");
    const detailsPath = '/api/v1/members/' + contactMember + '/details';
    expect((await context.request.get(detailsPath)).status()).toBe(403);
    fixtureSql("INSERT INTO team_capability_grants (team_id, member_id, capability) VALUES ('" +
      visibleTeam.id + "', " + adminMemberSql + ", 'MANAGE_TEAMS')");
    const auditedBefore = Number(fixtureSql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'CONTACT_READ' " +
      "AND target_member_id = '" + contactMember + "'"));
    const managed = await context.request.get(detailsPath);
    expect(managed.status()).toBe(200);
    expect(managed.headers()['cache-control']).toBe('no-store');
    expect(Object.keys(await managed.json()).sort()).toEqual(['email', 'firstName', 'id', 'isPlayer', 'lastName', 'phone']);
    expect(Number(fixtureSql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'CONTACT_READ' " +
      "AND target_member_id = '" + contactMember + "'"))).toBe(auditedBefore + 1);
    fixtureSql("UPDATE member_team_assignments SET team_id = '" + hiddenTeams[0].id + "' WHERE member_id = '" + contactMember + "'");
    expect((await context.request.get(detailsPath)).status()).toBe(403);
  } finally {
    if (contactMember) fixtureSql("DELETE FROM members WHERE id = '" + contactMember + "'");
    fixtureSql('DELETE FROM team_capability_grants WHERE member_id = ' + adminMemberSql);
    fixtureSql('DELETE FROM club_capability_grants WHERE member_id = ' + adminMemberSql);
    for (const capability of originalGrants) {
      fixtureSql("INSERT INTO club_capability_grants (member_id, capability) SELECT id, '" + capability +
        "' FROM members WHERE account_id = '" + accountId + "' ON CONFLICT DO NOTHING");
    }
  }
  await page.reload();
  await show(page, '#/admin/permissions');
  await expect(page.getByRole('region', { name: 'Manage permissions' })).toBeVisible();
  await show(page, '#/admin/settings');
  await expect(page.getByRole('region', { name: 'Edit club name' })).toBeVisible();
});
