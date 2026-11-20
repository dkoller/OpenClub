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

- [ ] B1 Switch DE, EN, FR, IT: headings, buttons and messages change; nothing stays in another language.
- [ ] B2 *Manage members*: search "Keller" finds Noah Keller; *Details* shows his birth date and no contacts.
- [ ] B3 *Add member*: an adult without email or phone is refused; with both it is created.
- [ ] B4 *Edit* that adult: the form is prefilled; change the phone; *Details* shows the new phone.
- [ ] B5 *Archive* the adult, then *Undo archive*: back in the list. Archive again; *Archived members* lists them with the
      date; *Reactivate* brings them back.
- [ ] B6 The anonymous member list (signed out, or `https://club.example.ch/api/v1/public/members`) shows minors only as
      first name plus initial (for example "Lea B.").
- [ ] B7 *My profile* shows the administrator's own record and role; change the phone, *Save contacts*, reload: the new
      phone is kept and nothing else changed.
- [ ] B8 *Membership fees*: create "Season 2026/27" (due date, CHF 150): every active member is listed; *Mark paid*
      for one, *Change amount* for another, *Exclude* a third; the totals change. After a reload *My fees* shows the
      administrator's own fee. Only members with the `MANAGE_FEES` permission see the panel.
- [ ] B9 *Excel import and export*: download the member list and open it in Excel (umlauts and phone numbers shown
      correctly). Add two new rows and one copy of an existing member, *Save As* → *CSV UTF-8*, *Check file*: 2 will be
      imported, 1 already exists; *Import 2 members*: both appear in *Manage members*.
- [ ] B10 *Announcements*: post one to the whole club with two lines of text; it shows with *Whole club* and both lines.
      As `coach@…` in C3 post one for Juniors U12 only; `parent@…` in D3 sees both, a member of another team only the
      club-wide one.
- [ ] B11 *My profile*: untick *Show my name on the public member list*; the anonymous list (B6) no longer shows the
      administrator; tick it again. *Email notifications* says the club sends no emails (the demo has no SMTP server).
- [ ] B12 *My schedule* → *Calendar subscription*: *Create link*, add it to a phone or desktop calendar ("subscribe" /
      "from URL"); the U12 dates from C4 appear after the next refresh. *Revoke link*: the calendar stops updating.

## C. Teams and permissions

- [ ] C1 *Teams*: Juniors U12 lists its members; assign and remove a member.
- [ ] C2 *Invitations*: create an invitation for Peter Zürcher (adult); open the link in a second private window, sign in
      as `coach@…` and accept. The coach sees the club but no administration.
- [ ] C3 As administrator, give Peter a **team** grant `MANAGE_TEAMS` for Juniors U12 only. As `coach@…` (reload): only
      Juniors U12 is visible; *Contact details* of a U12 member works; another team's member is not reachable.
- [ ] C4 Still as `coach@…`: *Schedule* under Juniors U12, add a training with *Repeat weekly until* three weeks later:
      four dates appear. *Cancel event* on one (marked *Cancelled*), *Delete* and *Confirm delete* another. *My
      schedule* (reload) lists the remaining U12 dates, because Peter is in that team; answer *Yes* for one. In the
      team's *Schedule*, *Show answers* lists Peter Zürcher with *Yes* and the minors as first name plus initial.
      *Show volunteer shifts* on one date: add "Bar" with 1 person; *Sign up: Me* fills it; the button for anyone else
      is disabled (full). *Attendance statistics* shows the team's members with no events held yet (dates lie ahead).
- [ ] C5 Revoke the grant: the coach loses the team at the next reload. *Unlink account* for Peter: the coach's session ends.

## D. Guardians

- [ ] D1 Invite Sandra Gerber (adult) and accept as `parent@…`.
- [ ] D2 As administrator, *Guardians*: find Lea Brunner, add Sandra (the attestation text is shown). A third guardian is
      refused after a second one.
- [ ] D3 As `parent@…` (reload): *My children* lists Lea; *Open* shows birth date, player flag and team; change her email;
      it is saved. *My schedule* lists the U12 trainings from C4 marked *For Lea*; answer *No* for Lea and see the totals
      change, but no other member's answer. Nothing else about other members is visible. *Show volunteer shifts* on a U12
      date offers *Sign up: Lea*.
- [ ] D4 Remove Sandra as guardian: *My children* disappears for `parent@…` after a reload.

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
