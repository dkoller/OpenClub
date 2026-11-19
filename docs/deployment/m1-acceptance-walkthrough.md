# M1 acceptance walkthrough on the hosted demo (operator checklist)

**For the operator (the owner, [ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md) decision 30) after the
[Infomaniak runbook](infomaniak-demo.md).** Work through it in one sitting (about 60 to 90 minutes) and copy the results
table at the end into [#28](https://github.com/dkoller/OpenClub/issues/28). Everything is synthetic: use only the
fictional demo data and `example.invalid` addresses. Never paste secrets, cookies, account IDs, tokens or screenshots
containing them into the issue.

Placeholders: `club.example.ch` (app), `id.example.ch` (Rauthy), `/opt/openclub-demo-env` (generated secrets). Use a
desktop browser in a private window per person; you need **three** Rauthy users, created in the Rauthy admin UI
(`https://id.example.ch/auth/v1/admin`): `admin@…` (you, the club administrator), `coach@…` and `parent@…`.

## A. Deploy and first club (runbook sections 3 to 6)

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

## C. Teams and permissions

- [ ] C1 *Teams*: Juniors U12 lists its members; assign and remove a member.
- [ ] C2 *Invitations*: create an invitation for Peter Zürcher (adult); open the link in a second private window, sign in
      as `coach@…` and accept. The coach sees the club but no administration.
- [ ] C3 As administrator, give Peter a **team** grant `MANAGE_TEAMS` for Juniors U12 only. As `coach@…` (reload): only
      Juniors U12 is visible; *Contact details* of a U12 member works; another team's member is not reachable.
- [ ] C4 Revoke the grant: the coach loses the team at the next reload. *Unlink account* for Peter: the coach's session ends.

## D. Guardians

- [ ] D1 Invite Sandra Gerber (adult) and accept as `parent@…`.
- [ ] D2 As administrator, *Guardians*: find Lea Brunner, add Sandra (the attestation text is shown). A third guardian is
      refused after a second one.
- [ ] D3 As `parent@…` (reload): *My children* lists Lea; *Open* shows birth date, player flag and team; change her email;
      it is saved. Nothing else about other members is visible.
- [ ] D4 Remove Sandra as guardian: *My children* disappears for `parent@…` after a reload.

## E. Denial and sessions

- [ ] E1 A Rauthy user who is not a club member sees "not yet a club member" and nothing else.
- [ ] E2 *Sign out* ends the session; the back button shows no member data.
- [ ] E3 The audit trail has the expected identifier-only events (run on the server, do **not** paste the output):
      `bash dev/deployment/demo-compose.sh /opt/openclub-demo-env exec -T api java -cp '/app/lib/*' org.openclub.api.database.AuditTrailCliKt`

## F. Restart, reboot and resources

- [ ] F1 `bash dev/deployment/demo-compose.sh /opt/openclub-demo-env restart api web` then sign in again: data unchanged.
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
| Members and languages (B1–B6) | pass / fail, notes \_ |
| Teams, invitations, scoped permissions (C1–C4) | pass / fail, notes \_ |
| Guardians (D1–D4) | pass / fail, notes \_ |
| Denial and sessions (E1–E3) | pass / fail, notes \_ |
| Restart and reboot persistence (F1–F2) | pass / fail |
| Peak memory / swap / disk (F3) | \_ MB / \_ MB / \_ GB used |
| Monthly cost (F4) | CHF \_ |
| Limitations observed | \_ |

M1 is accepted only when this table is filled from the real host; it never authorizes real member data (M2 gates stay
open).
