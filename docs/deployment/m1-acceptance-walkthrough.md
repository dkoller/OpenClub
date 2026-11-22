# M1 acceptance walkthrough on the hosted demo (operator checklist)

**For the operator (the owner, [ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md) decision 30) after the
[Infomaniak runbook](infomaniak-demo.md).** Work through it in one sitting (about 60 to 90 minutes) and copy the results
table at the end into [#28](https://github.com/dkoller/OpenClub/issues/28). Everything is synthetic: use only the
fictional demo data and `example.invalid` addresses. Never paste secrets, cookies, account IDs, tokens or screenshots
containing them into the issue.

Hostnames below are the examples from `/etc/openclub/demo.yaml` (`clubHost`, `idHost`); use yours. The generated
secrets are in its `envDirectory`. Use a
desktop browser in a private window per person; you need **three** Rauthy users, created in the Rauthy admin UI
(`https://id.example.ch/auth/v1/admin`): `admin@…` (you, the club administrator), `coach@…` and `parent@…`.

## A. Deploy and first club (runbook sections 3 to 7)

- [ ] A1 `deploy-demo.sh` finished with the HTTPS probe passing. Note the date.
- [ ] A2 Optional email configured; a Rauthy password-reset email for `coach@…` arrives.
- [ ] A3 Signed in as `admin@…`, bootstrapped the club, ran the demo seed. The club page shows the administration.

## B. Languages and members (as the administrator)

- [ ] B0 On a phone (or a narrow window): the menu sits at the bottom with *Start*, *Schedule*, *Family*, *Team* and
      *Administration*; *Administration* opens on *Waiting for you*, which lists the seeded join request (Mila Baumann,
      applied for by Patrick Baumann). A reload keeps the open page. *Fees* → a period shows one card per member, nothing scrolls sideways.
- [ ] B1 Switch DE, EN, FR, IT: headings, buttons and messages change; nothing stays in another language.
- [ ] B2 *Manage members*: search "Keller" finds Noah Keller; *Details* shows his birth date and no contacts.
- [ ] B3 *Add member*: an adult without email or phone is refused; with both it is created.
- [ ] B4 *Edit* that adult: the form is prefilled; change the phone; *Details* shows the new phone.
- [ ] B5 *Archive* the adult, then *Undo archive*: back in the list. Archive again; *Archived members* lists them with the
      date; *Reactivate* brings them back.
- [ ] B6 The anonymous member list (signed out, or `https://club.example.ch/api/v1/public/members`) shows minors only as
      first name plus initial (for example "Lea B.").
- [ ] B7 *Family* shows one link per part (*My profile*, *Absences*, *Fees*, *Calendar subscription*, *Email
      notifications*; no *My children* for the administrator); each opens its own page with *← Family*. *My profile* shows the administrator's own record and role; change the phone, *Save contacts*, reload: the new
      phone is kept and nothing else changed.
- [ ] B8 *Membership fees*: create "Season 2026/27" (due date, CHF 150): every active member is listed; *Mark paid*
      for one, *Change amount* for another, *Exclude* a third; the totals change. After a reload *Family* shows how many fees are open and *Family* → *Fees* shows the
      administrator's own fee. Only members with the `MANAGE_FEES` permission see the panel.
- [ ] B9 *Excel import and export*: download the member list and open it in Excel (umlauts and phone numbers shown
      correctly). Add two new rows and one copy of an existing member, *Save As* → *CSV UTF-8*, *Check file*: 2 will be
      imported, 1 already exists; *Import 2 members*: both appear in *Manage members*.
- [ ] B10 *Announcements*: post one to the whole club with two lines of text; it shows with *Whole club* and both lines.
      As `coach@…` in C3 post one for Juniors U12 only; `parent@…` in D3 sees both, a member of another team only the
      club-wide one.
- [ ] B11 *Family* → *My profile*: untick *Show my name on the public member list*; the anonymous list (B6) no longer shows the
      administrator; tick it again. *Family* → *Email notifications* says the club sends no emails (the demo has no SMTP server).
- [ ] B12 *Family* → *Calendar subscription*: *Create link*, add it to a phone or desktop calendar ("subscribe" /
      "from URL"); the U12 dates from C4 appear after the next refresh. *Revoke link*: the calendar stops updating.

## C. Teams and permissions

- [ ] C1 *Team* → *Teams*: each team is one row with its number of members. Open Juniors U12: its page shows the
      name, the member count, *Schedule*, *Members*, *Statistics* and *Manage team*. *Members* lists its members; assign
      and remove a member. *Rename* a test team and back; on a phone (390 px) nothing scrolls sideways.
- [ ] C2 *Invitations*: create an invitation for Peter Zürcher (adult); open the link in a second private window, sign in
      as `coach@…` and accept. The coach sees *Start*, *Schedule* and *Family*, but no *Team* or *Administration* in the menu.
- [ ] C3 As administrator, give Peter a **team** grant `MANAGE_TEAMS` for Juniors U12 only. As `coach@…` (reload): *Team* appears in the
      menu and only Juniors U12 is listed under *Teams*; on its *Members* page *Contact details* of a U12 member works; another
      team's page (pasted address `#/team/<id>`) says the team does not exist or is not visible, and its members are not reachable.
      On Juniors U12's page, *Rename* under *Manage team* is refused (it needs a club-wide grant): the buttons give way
      to *No permission to change this team.*
- [ ] C4 Still as `coach@…`: Juniors U12 → *Schedule*, add a training (*Add event*) with *Repeat weekly until* three weeks later:
      four dates appear. *Cancel event* on one (marked *Cancelled*), *Delete* and *Confirm delete* another. *Start*
      (reload) lists the remaining U12 dates under *Things for you to do*, because Peter is in that team; answer *Yes*
      for one with one tap: it leaves the list. On *Team*,
      *Next events* shows the training with its open answers; opening it lists Peter Zürcher under *Yes* and the minors
      as first name plus initial under *No answer yet*.
      Tap one date in the team's *Schedule*: its page shows the same answers, then the helper shifts and rides; add
      "Bar" with 1 person; *Sign up: Me* fills it; the button for anyone else is disabled (full). Before that, the
      coach's *Team* page listed it under *Helpers missing*. The team's *Statistics* page shows the team's members with
      no events held yet (dates lie ahead).
      The seeded U12 dates are there too: the first training shows Yes, No and open answers from other members; on the
      second one Luca F. counts as *No* because of an absence; the home match five days ahead has a full *Kiosk* shift
      and a *Cake stall* with 2 of 3 places free (also under *Helpers missing*), and two rides (one full, one with
      2 seats free).
- [ ] C5 Revoke the grant: the coach loses the team at the next reload. *Unlink account* for Peter: the coach's session ends.

## D. Guardians

- [ ] D1 Invite Sandra Gerber (adult) and accept as `parent@…`.
- [ ] D2 As administrator, *Guardians*: find Lea Brunner (Juniors U12), add Sandra (the attestation text is shown); do the
      same for Nico Brunner (Juniors U17). A third guardian is refused after a second one.
- [ ] D3 As `parent@…` (reload): *Family* → *My children* lists Lea and Nico; *Open* shows birth date, player flag and team; change Lea's email;
      it is saved. *Start* asks *Is Lea coming?* for the seeded U12 training and the trainings from C4, and *Is Nico
      coming?* for the U17 away match; answer *No* for Lea on one, open the
      event and see the totals change, but no other member's answer. *Start* also shows *Ride for Lea* for the U12
      home match (Lea said Yes; Daniel Roth's ride, 2 seats free; Martina Wyss's full ride is not offered). *Schedule*
      has *Lea* and *Nico* buttons; *Lea* shows only her events. Nothing else about other members is visible. The
      event's page offers *Sign up: Lea* for the shift.
- [ ] D4 Remove Sandra as guardian of both: *My children* disappears for `parent@…` after a reload.

## E. Denial and sessions

- [ ] E1 A Rauthy user who is not a club member sees "not yet a club member" and nothing else.
- [ ] E2 *Sign out* ends the session; the back button shows no member data.
- [ ] E3 The audit trail has the expected identifier-only events (run on the server, do **not** paste the output):
      `bash dev/deployment/demo-compose.sh exec -T api java -cp '/app/lib/*' org.openclub.api.database.AuditTrailCliKt`

## F. Restart, reboot and resources

- [ ] F1 `bash dev/deployment/demo-compose.sh restart api web` then sign in again: data unchanged.
- [ ] F2 `reboot` the VPS. After a few minutes the site and sign-in work and the data is unchanged (services are
      `unless-stopped`).
- [ ] F3 Resources during a walkthrough step: `COMPOSE_PROJECT_NAME=openclub-demo bash dev/measure-synthetic-compose.sh --synthetic-only`
      and `free -m`, `df -h /`. Note the peak used memory and swap.
- [ ] F4 The Infomaniak invoice for the month (CHF, with or without VAT, plus the domain).

## Results for #28 (copy, fill, no secrets)

| Evidence | Result |
| --- | --- |
| Operator and host | Owner; Infomaniak VPS Lite \_ vCPU / \_ GB; Ubuntu \_ ; date \_ |
| HTTPS probe (A1), identity provider | pass / fail, Rauthy, date \_ |
| Members, languages, own profile, fees, Excel, announcements, public list and calendar (B1–B12) | pass / fail, notes \_ |
| Teams, invitations, scoped permissions, schedule (C1–C5) | pass / fail, notes \_ |
| Guardians (D1–D4) | pass / fail, notes \_ |
| Denial and sessions (E1–E3) | pass / fail, notes \_ |
| Restart and reboot persistence (F1–F2) | pass / fail |
| Peak memory / swap / disk (F3) | \_ MB / \_ MB / \_ GB used |
| Monthly cost (F4) | CHF \_ |
| Limitations observed | \_ |

M1 is accepted only when this table is filled from the real host; it never authorizes real member data (M2 gates stay
open).
