# ADR-0005: One club per independent OpenClub installation

- **Status:** Accepted architecture; pre-production schema/auth foundation merged in PR #79 on 2026-11-15; further implementation and production/privacy approval outstanding
- **Supersedes:** Multi-tenant/global-person and club-switching assumptions from ADR-0002, early MVP proposal and R01 historical scenario
- **Work:** [#74](https://github.com/dkoller/OpenClub/issues/74), [R04/#13](https://github.com/dkoller/OpenClub/issues/13), [R06/#15](https://github.com/dkoller/OpenClub/issues/15), [R18/#27](https://github.com/dkoller/OpenClub/issues/27)

## Decision and purpose

Exactly **one club per deployment and PostgreSQL database**, reusable for any sports club, orchestra or nonprofit by deploying the same open-source software separately. Each installation has independent members, accounts, secrets, backups and operations. OpenClub does not provide cross-installation member lookup, shared account identity, synchronization or club switching.

A club's administrator can create member records without login. Verified OIDC accounts may later be explicitly linked to local members. Login alone grants no member or administrator authority; never adopt member records by matching email/name. Club-wide and team-specific capabilities, multiple team assignments, and four supported languages (DE/EN/FR/IT) remain required for the first usable release.

## Pre-production schema reset (merged PR #79)

The owner confirmed **no live database exists** and authorized replacing, not incrementally upgrading, the old schema. Historical Flyway V1–V4 SQL files are removed and replaced by **fresh `V1__single_club.sql`**:

| Local table | Responsibility |
| --- | --- |
| `clubs` | One row maximum, enforced by a unique singleton constraint; club name, slug, configurable player gender policy and adult-age threshold |
| `members` | Club-local member ID, mandatory first name/last name/birth date, optional account ID, email, phone, gender, player flag and existing interim ADMIN/MEMBER role; **no `club_id` tenant column** |
| `accounts` | Installation-local opaque account ID; placeholder label kept for tested OIDC compatibility, **not a global person profile** |
| `account_identities` | Verified unique issuer+subject binding to an installation-local account; no matching by email |
| `oidc_login_transactions` and `auth_sessions` | Browser login transaction and opaque hashed/CSRF-protected session state; sessions reference local `account_id` |

A database trigger validates a nonfuture birth date, **both email and phone for adults**, and gender for players when the club requires it, on member inserts/updates. The provisional initial adult threshold defaults to **18 years**, configurable on the club row; owner/privacy review remains required before live use. Policy changes and birthdays require additional lifecycle validation.

The new Flyway history intentionally fails on old database volumes rather than silently migrating/dropping them. **Only disposable pre-production environments without data to keep may be reset manually**. Do not run automatic destructive upgrades, and do not point multiple installations at the same schema.

## Application structure

Kotlin is organized by responsibility: `org.openclub.api.auth` (OIDC, sessions, browser routes), `org.openclub.api.database` (pool/migrations), `org.openclub.api.members` (local membership lookup), `org.openclub.api.club` (single-club route), and the root application composition. Authentication internals use `accountId`, not the former globally shared `personId`. The former multi-club access code is replaced by a local membership lookup.

The application currently implements **`GET /api/v1/club`** with a verified session and a locally linked member; missing sessions return 401, unlinked accounts return 403. It does not allow selecting or creating a second club. The React landing screen supports sign-in, sign-out and four languages, without a club switcher. Nginx and Vite now preserve `/api` in proxy requests.

## Invariants, tradeoffs and unfinished work

- **Single club:** The unique singleton constraint prohibits a second club row. An operator-only transactional CLI initializes the one club and first administrator (#16). A separate restricted operator command can rebind the existing sole administrator to a verified, unlinked OIDC account and revoke old sessions. Independent recovery approval/drill, privacy, and release acceptance remain open. No unauthenticated setup endpoint exists.
- **Local privacy:** No OpenClub cross-installation member/data service exists; operators must configure separate databases, credentials, backups and OIDC clients for each installation. Sensitive member fields still require R03 privacy decisions and server field-level permission checks.
- **Authentication:** Preserve verified OIDC issuer/subject, session expiry, CSRF/Origin checks and concurrency invariants. A login account can remain unlinked; a member can remain accountless. Account/member linking still needs R11.
- **Authorization:** The initial installation-local guard checks session-linked membership for club reading and interim ADMIN-only member management. It is not a complete club/team capability model. R06 and #76 must add least-privilege grants and last-authorized-admin safeguards before member-write endpoints are exposed.
- **Release:** Member/team/grant APIs, first-admin provisioning, browser workflows, validated backups/restores and operational sign-off remain unfinished. This PR is **not** release approval.
- **Cost:** Local development uses free/open-source dependencies; hosting multiple independent installations can involve costs. Nothing automatically provisions or purchases hosting. See [operating guide](../deployment/single-club.md).

## Acceptance strategy

Run Kotlin/PostgreSQL tests, verified Keycloak browser login, web tests and Compose smoke checks; specifically verify the singleton-club uniqueness, member required fields, accountless member, login isolation, 401/403 local access and `/api` proxy. The existing historical multi-club tests are replaced by singleton and local access tests; never delete security coverage without a replacement.

The request to suspend PR-size CI for this **one owner-approved pre-production reset** is temporary. Its normal check must be restored by a distinct follow-up PR before future large changes are accepted. No live personal data is authorized by the reset.
