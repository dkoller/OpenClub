import { chooseLanguage, ensureTeam, expect, openForm, show, test } from './club-fixtures.js';

// Every language, invalid input in the real browser, and machine-checkable accessibility.
test.skip(process.env.OPENCLUB_E2E_DISPOSABLE !== 'true',
  'Club tests write synthetic data and must only run against the disposable CI database');

test('member, team and permission pages work in every language, with the keyboard', async ({ page }) => {
  // The whole authenticated workflow remains reachable with localized
  // member, team and delegation controls, not just a translated landing page.
  for (const locale of [
    { code: 'de', member: 'Mitglieder verwalten', team: 'Teams', grant: 'Berechtigungen verwalten', search: 'Name suchen', find: 'Suchen' },
    { code: 'fr', member: 'Gérer les membres', team: 'Équipes', grant: 'Gérer les autorisations', search: 'Rechercher un nom', find: 'Rechercher' },
    { code: 'it', member: 'Gestisci membri', team: 'Squadre', grant: 'Gestisci permessi', search: 'Cerca nomi', find: 'Cerca' },
    { code: 'en', member: 'Manage members', team: 'Teams', grant: 'Manage permissions', search: 'Search names', find: 'Search' },
  ]) {
    await chooseLanguage(page, locale.code);
    await show(page, '#/team');
    await expect(page.getByRole('region', { name: locale.team, exact: true })).toBeVisible();
    await show(page, '#/admin/permissions');
    await expect(page.getByRole('region', { name: locale.grant, exact: true })).toBeVisible();
    await show(page, '#/admin/members');
    await expect(page.getByRole('heading', { name: locale.member })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', locale.code);
    // Real Chromium keyboard semantics in each locale: visible label,
    // predictable Tab order and Enter-submitted member search.
    const names = page.getByRole('region', { name: locale.member });
    await names.getByLabel(locale.search).focus();
    await page.keyboard.press('Tab');
    const submit = names.getByRole('button', { name: locale.find, exact: true });
    await expect(submit).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(names.getByLabel(locale.search)).toBeVisible();
  }
});

test('invalid input is stopped by the browser or refused by the server, with no write', async ({ page, context }) => {
  ensureTeam('Juniors');
  // Invalid input in the real browser (#25): the browser's own constraints stop what they can,
  // and what only the server can judge is rejected with a visible, announced error and no write.
  await show(page, '#/admin/members');
  const memberForm = page.getByRole('region', { name: 'Manage members' });
  const membersNamed = (lastName) => context.request
    .get('/api/v1/members?q=' + lastName).then(response => response.json()).then(body => body.items.length);
  const addMemberResponse = () => page.waitForResponse(response =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/members');
  // Typing is capped by maxLength, so an oversized name cannot even be entered.
  await openForm(memberForm, 'New member');
  const firstNameInput = memberForm.getByLabel('First name');
  await firstNameInput.pressSequentially('a'.repeat(250));
  expect((await firstNameInput.inputValue()).length).toBe(200);
  await firstNameInput.fill('');
  // A malformed email is blocked by the browser before any request is sent.
  await memberForm.getByLabel('First name').fill('Invalid');
  await memberForm.getByLabel('Last name').fill('EmailShape');
  await memberForm.getByLabel('Date of birth').fill('1990-01-01');
  await memberForm.getByLabel('Email').fill('not-an-email');
  await memberForm.getByLabel('Phone').fill('+41000000001');
  expect(await memberForm.getByLabel('Email').evaluate(input => input.validity.typeMismatch)).toBe(true);
  expect(await memberForm.getByLabel('Email')
    .evaluate(input => input.form.checkValidity())).toBe(false);
  // A birth date in the future and a whitespace-only name pass the browser but not the server.
  const futureBirthDate = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
  for (const [label, first, last, dob] of [
    ['future birth date', 'Future', 'FutureBirth', futureBirthDate],
    ['whitespace-only first name', '   ', 'BlankFirstName', '2016-01-01'],
  ]) {
    await memberForm.getByLabel('First name').fill(first);
    await memberForm.getByLabel('Last name').fill(last);
    await memberForm.getByLabel('Date of birth').fill(dob);
    await memberForm.getByLabel('Email').fill('');
    await memberForm.getByLabel('Phone').fill('');
    const rejected = addMemberResponse();
    await memberForm.getByRole('button', { name: 'Add member' }).click();
    expect((await rejected).status(), label).toBe(400);
    await expect(memberForm.getByRole('alert'), label).toContainText('Action failed.');
    expect(await membersNamed(last), label).toBe(0);
  }
  // Duplicate (ignoring case and padding) and whitespace-only team names are rejected the same way.
  await show(page, '#/team');
  const teamsForm = page.getByRole('region', { name: 'Teams', exact: true });
  const teamsBeforeInvalid = (await (await context.request.get('/api/v1/teams')).json()).items;
  for (const [label, name] of [
    ['duplicate team name', '  ' + teamsBeforeInvalid[0].name.toUpperCase() + ' '],
    ['whitespace-only team name', '    '],
  ]) {
    await openForm(teamsForm, 'New team');
    await teamsForm.getByLabel('Team name').fill(name);
    const rejected = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/teams');
    await teamsForm.getByRole('button', { name: 'Add team' }).click();
    expect((await rejected).status(), label).toBeGreaterThanOrEqual(400);
    await expect(teamsForm.getByRole('alert'), label).toContainText('Could not create team.');
    expect((await (await context.request.get('/api/v1/teams')).json()).items.length, label)
      .toBe(teamsBeforeInvalid.length);
  }
  // The adult age accepts only whole numbers inside its range; anything else leaves Save disabled.
  await show(page, '#/admin/settings');
  const adultAgeInput = page.getByRole('region', { name: 'Edit club name' }).getByLabel('Adult from age', { exact: false });
  const saveAgeButton = page.getByRole('region', { name: 'Edit club name' })
    .locator('form').filter({ has: page.getByLabel('Adult from age') }).getByRole('button');
  for (const [value, flag] of [['12', 'rangeUnderflow'], ['26', 'rangeOverflow'], ['18.5', 'stepMismatch']]) {
    await adultAgeInput.fill(value);
    expect(await adultAgeInput.evaluate((input, name) => input.validity[name], flag), value).toBe(true);
    await expect(saveAgeButton, value).toBeDisabled();
  }
  await page.reload();
  await expect(page.getByText('Signed in and authorized for this club.', { exact: true })).toBeVisible();
});

test('pages have one main region and heading, named controls and valid references', async ({ page, context }) => {
  const juniors = ensureTeam('Juniors');
  const juniorsPage = '#/team/' + juniors;
  // Assistive technology (#25): structure, names and references that screen readers rely on. This
  // checks what is machine-checkable; it is not a substitute for a manual screen-reader audit.
  // Installable on the home screen: the manifest and its icons are served by the app itself.
  const manifest = await (await context.request.get('/manifest.webmanifest')).json();
  expect(manifest).toMatchObject({ name: 'OpenClub', display: 'standalone', start_url: '/#/' });
  for (const icon of manifest.icons) expect((await context.request.get(icon.src)).status(), icon.src).toBe(200);
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest.webmanifest');
  // Every page has one main region and one level-1 heading, and shows its own sections.
  for (const [hash, names] of [
    ['#/', ['Announcements']],
    ['#/admin/settings', ['Edit club name']],
    ['#/admin/permissions', ['Manage permissions']],
    ['#/team', ['Teams']],
    [juniorsPage, ['Manage team']],
    [juniorsPage + '/schedule', ['Schedule Juniors']],
    [juniorsPage + '/statistics', ['Attendance statistics Juniors']],
    ['#/admin/members', ['Manage members']],
    ['#/admin/members/invitations', ['Invitations and account links']],
    ['#/admin/members/guardians', ['Guardians']],
    ['#/admin/members/roles', ['Manage club roles']],
    ['#/admin/members/transfer', ['Excel import and export']],
    ['#/admin/members/archived', ['Archived members']],
    ['#/admin/members/public', ['Members']],
  ]) {
    await show(page, hash);
    await expect(page.getByRole('main'), hash).toHaveCount(1);
    for (const name of names) await expect(page.getByRole('region', { name, exact: true }), name).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 }), hash).toHaveCount(1);
  }
  // The document language follows the selected language (screen readers pick pronunciation from it).
  for (const [code, label] of [['de', 'Deutsch'], ['fr', 'Français'], ['it', 'Italiano'], ['en', 'English']]) {
    await chooseLanguage(page, code);
    await expect(page.locator('html'), label).toHaveAttribute('lang', code);
  }
  // Every control a user can operate has an accessible name, in English and in another language.
  for (const code of ['en', 'fr']) {
    await chooseLanguage(page, code);
    const unnamedControls = await page.locator('main input:not([type=hidden]), main select, main textarea, main button')
      .evaluateAll(elements => elements.filter(element => {
        if (element.offsetParent === null) return false;
        const labelled = element.labels && element.labels.length > 0;
        const named = element.getAttribute('aria-label') || element.getAttribute('aria-labelledby') ||
          element.getAttribute('title');
        const text = element.tagName === 'BUTTON' ? element.textContent.trim() : '';
        return !(labelled || named || text);
      }).map(element => element.outerHTML.slice(0, 120)));
    expect(unnamedControls, 'controls without an accessible name (' + code + ')').toEqual([]);
  }
  await chooseLanguage(page, 'en');
  // aria-describedby and aria-labelledby must point at elements that exist.
  const danglingReferences = await page.locator('[aria-describedby], [aria-labelledby]').evaluateAll(elements =>
    elements.flatMap(element => ['aria-describedby', 'aria-labelledby'].flatMap(attribute =>
      (element.getAttribute(attribute) || '').split(/\s+/).filter(Boolean)
        .filter(id => !document.getElementById(id)).map(id => attribute + ' -> ' + id))));
  expect(danglingReferences).toEqual([]);
});
