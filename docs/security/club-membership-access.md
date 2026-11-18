# Installation-local club lookup and explicit capability guards

**M1 same-session role authorization regression (2026-11-17):** PostgreSQL-backed role HTTP acceptance explicitly removes and restores an administrator's `MANAGE_PERMISSIONS` grant without replacing the browser session: the revoked manager is denied; restored rights work; then deleting its verified OIDC identity denies both reads and writes even though the grant remains. The role stays unchanged. This tests defense in depth, not full M2 production security acceptance.

**Current-vs-historical boundary (2026-11-17):** This document records how authorization primitives were originally implemented as internal-only database services. **Member, team, role and grant HTTP routes and DE/EN/FR/IT React workflows have since been implemented and exercised in synthetic Keycloak/Chromium CI** (see [roadmap](../roadmap.md) and [R04 contract review](../contracts/contract-review.md)). Statements in the historical implementation subsections below saying that no such route or UI exists describe their earlier delivery stage; they are **not current blocker claims**. Current routes still require real identity, explicit grants, CSRF/Origin and backend authorization. Private-data contracts and M2 human/legal approval remain outstanding.

**Current implementation:** `org.openclub.api.members.MemberAccess`. The former `ClubMembershipAccess(personId, clubId)` multi-tenant API is retired in the single-club reset.

`MemberAccess.authorizedClubId(accountId)` performs a single database-side, installation-local club+membership check. Both linked ADMIN and MEMBER accounts may read only the local club UUID; unlinked accounts are denied. The account UUID must come from a verified server session. Existing `GET /api/v1/club` uses this check, returning 401 for missing authentication and 403 for authenticated accounts without an authorized local member. This guard **does not authorize member writes**. The previously unused role-based `MANAGE_MEMBERS` and `canManageMembers` helpers were removed: member writes use explicit current `EDIT_MEMBERS` grants via `ClubCapabilityAccess` and the internal SQL write services. PostgreSQL tests verify that a club-ID lookup works for either role and denies an unlinked account immediately.

**Security boundary:** The lookup is a server-side authorization building block, not an automatic Ktor middleware. Any future state-changing HTTP route must separately enforce trusted session, approved permission, CSRF and Origin before touching private data. `GET /api/v1/club` now invokes the common guard. PostgreSQL `MemberAccessTest` covers authorized admin/member and an outsider. The HTTP integration test also asserts a minimal ID-only response, `Cache-Control: no-store` on authenticated and denied responses, explicit 401 for missing/invalid sessions, an exact nondisclosing 403 for an unlinked local account, ordinary MEMBER access, and immediate 403 after account unlinking, followed by restored ID-only access when the same account is explicitly relinked, and immediate 403 after the linked member is deleted while the session remains active. No cross-club lookup or switcher is supported.

**Privacy/release:** See [single-club architecture](../decisions/0005-single-club-installation.md) and [R03](https://github.com/dkoller/OpenClub/issues/12). Tests use synthetic identities only.

## Internal member directory foundation (R09/#18; not an HTTP API)

`MemberDirectory.search(accountId, query, limit, after)` is a PostgreSQL-only query for a trusted installation-local session account ID. It checks both the acting account's linked member **and an explicit club-wide `VIEW_MEMBER_DIRECTORY` grant in the same SQL statement** as the read. No caller-supplied role, club ID, or tenant selector exists; an unlinked account or an account without the directory grant gets an empty result. Only local UUID, first name and last name are selected; birth dates, contact details, gender and login identities are never loaded into the summary. Queries use bound parameters and literal substring matching, not wildcard matching; names sort deterministically by last name, first name, then UUID. Limit is 1–50, default 20; the next-page key is an **internal typed value**, never a public, unsigned cursor. PostgreSQL regression tests also cover one-row pages across identical names (UUID tiebreaker) and literal `%`/`_` searches so wildcard characters cannot broaden results. A previously issued internal page cursor does not bypass grant revocation: subsequent pages are denied until an explicit grant is restored.

**Exposure gate:** No Ktor route is registered for this query. R03/#12 must approve directory visibility (particularly children), R06/#15 and #76 must approve fine-grained capabilities, and R04/#13 must finalize an authenticated, tamper-resistant opaque public cursor before a member-directory HTTP endpoint is enabled. Do not mistake this internal query or synthetic SQL test for approval to handle real member data.

## Explicit club-wide capability checks — R01/#76 increment

Flyway `V2__club_capability_grants.sql` introduces **installation-local**, explicitly stored member grants for `VIEW_MEMBER_DIRECTORY`, `EDIT_MEMBERS` and `MANAGE_PERMISSIONS`. There are no tenant IDs or global accounts. Each (member, capability) combination is unique, and deleting a member removes its grants. The restricted first-club bootstrap explicitly seeds the first ADMIN with `MANAGE_PERMISSIONS`, `VIEW_MEMBER_DIRECTORY` and `EDIT_MEMBERS` atomically; no other ADMIN automatically inherits grants. **Upgrades with a pre-existing ADMIN intentionally do not backfill grants**: a separately reviewed operator-controlled initialization procedure is required before adding a grant UI.

`ClubCapabilityAccess.has(verifiedSessionAccountId, capability)` checks the current database state on every call; missing, revoked or unlinked member grants deny immediately. Managing permissions requires **all three**: an explicit `MANAGE_PERMISSIONS` grant, an `ADMIN` role, and a current verified OIDC identity binding on that account. The common read-side capability guard now matches the stricter identity eligibility already required by internal grant mutations and protected role changes; removing the binding denies the next check. Being an ADMIN alone, managing members, or holding a directory grant does not confer delegation powers. This is a read-only security building block: there is no grant/revoke HTTP endpoint and no permission UI. Direct SQL fixture writes in PostgreSQL integration tests are **not a delegable application interface**.

**Deliberate limitations:** V4 now supports exact-team grants after team assignments (#75) landed, and internal grant/revoke transactions serialize permission changes and protect the final eligible manager. These internal foundations are not a public delegation or member-data API. Existing club-ID reads still require local member linkage; no role-based member-edit shortcut remains. New capabilities do **not** implicitly authorize member HTTP APIs or sensitive contact visibility. R03/#12 privacy, R04/#13 contract review, R06/#15 route enforcement, and #76 delegation testing remain open.

## Installation-local teams and assignments (#75)

Flyway V3 adds `teams` and `member_team_assignments`, using local member/team UUIDs and a unique (team, member) pair. Team names are unique ignoring case **and surrounding whitespace**, including for direct SQL writes (V6 normalized unique index); deleting a team/member cascades its assignments. Members may belong to multiple teams without copying their personal data. V3 also extends the fixed club-wide grants with `VIEW_TEAMS` and `MANAGE_TEAMS` **without automatically granting either capability**.

`TeamAssignments` is **internal-only**, not a Ktor route. Its trusted account ID must come from a verified session. Creation, rename, deletion, assignment and unassignment each check an explicit `MANAGE_TEAMS` grant inside the same SQL statement performing the mutation; list requires `VIEW_TEAMS` or `MANAGE_TEAMS`. A valid login, ADMIN role alone, ordinary team membership and permission to edit members cannot manage teams. Duplicate assignments return false even under competing insertions, and deleting a team removes its assignments.

**Still blocked for HTTP exposure:** Internal team-scoped grants now exist (V4), but there is no team HTTP API or multilingual management UI. Public delegation still needs an approved API, trusted-session Origin/CSRF enforcement, audit policy and independent security review under #76. R03/#12 privacy review and final R04/#13 contracts remain mandatory before exposing team-member identities. Internal SQL operations must be called on `DatabaseRuntime.onDatabaseThread`, never the Ktor event loop.

## Team-scoped capability enforcement (V4; #76 increment)

V4 adds `team_capability_grants(team_id, member_id, capability)` with foreign keys to actual local teams and members, a unique team/member/capability tuple and cascading deletion. Only `VIEW_TEAMS` and `MANAGE_TEAMS` are valid at team scope; `EDIT_MEMBERS` and `MANAGE_PERMISSIONS` **cannot** be granted at team scope. No grants are created automatically.

`ClubCapabilityAccess.hasForTeam(verifiedSessionAccountId, teamId, capability)` checks either an explicit club-wide grant or an explicit grant **for that exact team** in the current database. A team `MANAGE_TEAMS` grant implies team visibility, not club-wide control; regression tests also assert the scoped grant does not satisfy any club-wide `VIEW_TEAMS` or `MANAGE_TEAMS` capability check. A club-wide `VIEW_TEAMS` grant permits listing all local teams but does not authorize assignment mutations; synthetic PostgreSQL tests enforce this read-versus-write separation. Internal `TeamAssignments.list` returns only teams for which the actor has scoped/team-wide read/manage authority. Assignment and unassignment check club-wide or matching team `MANAGE_TEAMS` **inside the same SQL statement**. Team creation, renaming and deletion still require **club-wide** `MANAGE_TEAMS`. Revoking or unlinking a grant denies subsequent operations; deleting a team/member removes grants automatically.

**Not an API or delegation mechanism:** These are internal backend operations with synthetic tests, not permission-edit routes. Database fixture writes are privileged test setup, never client-accessible. Safe grant/revoke authorization, prevention of self-escalation/over-delegation, last-authorized-manager protection, concurrent grant changes, member field visibility, privacy approval and frontend UI remain open under #76/#12/#13. No member/team HTTP route has been introduced. Blocking SQL must run through the bounded database dispatcher.

## Serialized internal grant mutations (#76)

`CapabilityGrants` provides **internal-only** `grant` and `revoke` operations with a verified session account ID and explicit `CLUB` or `TEAM` scope. All operations run in a PostgreSQL transaction under the same installation-wide advisory lock; the manager's ADMIN role plus explicit club-wide `MANAGE_PERMISSIONS` grant are rechecked inside that transaction. Unknown recipients and nonexistent teams fail closed. Team-scoped grants may include only `VIEW_TEAMS` and `MANAGE_TEAMS`, never member-edit or permission-management rights. Duplicate grants and revokes are idempotent, including revoking a nonexistent `MANAGE_PERMISSIONS` grant from an otherwise eligible administrator when only one manager remains; the last-manager guard applies only to an actual grant removal.

The actor cannot modify their **own grants**, preventing self-escalation and accidental self-lockout. A `MANAGE_PERMISSIONS` grant can be issued only to an ADMIN member whose account has a verified OIDC identity binding. Last-manager checks count only administrators with such a binding, not accountless or unverifiable administrators. Revocation rejects removal of the last eligible ADMIN permission-manager; serialized grant mutations avoid competing revocations. Protected role changes in `MemberRoles` already share this transaction lock and last-manager invariant. Member deletion, unlinking, archiving and public role changes still require their own reviewed lifecycle guards before exposure. This API does not protect against a privileged operator directly manipulating SQL.

**Not web-exposed:** No grant/revoke HTTP route, public administrator interface, or audit trail is introduced. Caller-supplied actor IDs must never reach this internal interface; production HTTP mutations also require validated session, Origin, CSRF, error masking and audit policy. R03/#12 privacy, R04/#13 contract acceptance, and independent security review remain pending.

## Internal protected role changes (#19)

`MemberRoles.change(verifiedSessionAccountId, targetMemberId, role)` allows only an ADMIN holding an explicit club-wide `MANAGE_PERMISSIONS` grant to change **another** member's `ADMIN`/`MEMBER` role. Self-change, unknown actor/target, unlinked account, and absent permission are denied. PostgreSQL regression coverage also confirms that revoking the actor's grant or removing the verified identity binding immediately blocks role changes without modifying the target. The service holds the **same PostgreSQL transaction advisory lock** as `CapabilityGrants` (#92), so racing role changes and grant revocations cannot remove the final authorized administrator/permission-manager through these service paths. Demotion checks that at least one ADMIN remains and, if the target is an eligible permission manager with a verified OIDC identity binding, that another eligible manager remains. Every role transition also clears the target's `MANAGE_PERMISSIONS` grant in the same transaction, so a demoted user cannot regain delegation authority automatically on later promotion; a separate explicitly authorized grant is required. The role is never inferred from a request.

This is a **backend-only security foundation**. No role-change HTTP endpoint, admin UI or audit ledger exists. Full authorization, exact Origin/CSRF, reviewable API contract, privacy and concurrent unlink/archive/delete guards are still needed before exposing member role management. Direct privileged SQL bypasses these service invariants and must remain operator-restricted.

## Historical implementation milestones (not current HTTP availability)

The sections below preserve the incremental internal-service design and its original security constraints. Statements such as “No Ktor route”, “no HTTP route” and “club settings are not yet editable through HTTP” refer to the date of those individual implementation slices. **Current synthetic member creation, name-only PATCH, archive, club-name PATCH and player-gender settings have secured HTTP/DE/EN/FR/IT UI flows.** Full private-details replacement, adult-age policy changes, retention and real-data approval remain outstanding; see [R04 contract review](../contracts/contract-review.md), [roadmap](../roadmap.md) and [#12](https://github.com/dkoller/OpenClub/issues/12).

## Accountless member creation foundation (#18)

`MemberCreation.create(verifiedSessionAccountId, input)` is an **internal-only** jOOQ operation that creates an installation-local member with a generated UUID and **no linked account**. It authorizes the acting account using an explicit, current club-wide `EDIT_MEMBERS` grant **inside the INSERT ... SELECT**; unlinked or ungranted accounts insert nothing. No club ID, OIDC account, identity binding, role or capability can be supplied through the input. Duplicate names are permitted because they are not a trustworthy identity key.

Names are trimmed and length-limited; date of birth is required and cannot be future-dated; optional contact/gender strings are trimmed. The PostgreSQL trigger remains authoritative for configured adult email **and** phone, and conditional player-gender rules. Invalid records are rejected rather than silently dropping required fields. Database errors are internal and must be converted to nondisclosing API responses **only after** an approved HTTP interface is designed.

**Security/privacy gates:** No Ktor route, browser form submission or new authority has been introduced. Before exposing any member-creation endpoint, approve R03/#12 legal/field policy and the R04/#13 contract, verify trusted session, exact Origin, CSRF, field-level authorization and safe audit/error behavior. Continue using synthetic tests only. Execute blocking JDBC work via `DatabaseRuntime.onDatabaseThread`.

## Protected member details update (#18)

`MemberDetailsUpdate.update(verifiedSessionAccountId, memberId, input)` is an **internal-only** database operation. It changes only first/last name, birth date, adult contact, player gender and player status. It deliberately cannot change local member ID, OIDC account link, ADMIN/MEMBER role or capability grants: those are separate privileged operations with their own last-manager and identity safeguards. The acting account must hold a current explicit `EDIT_MEMBERS` club-wide grant, checked **inside the same UPDATE**. Unknown IDs, unlinked accounts and revoked/ungranted actors do not update rows.

Names, dates and contact lengths are validated before persistence; the PostgreSQL trigger remains authoritative for adult email-plus-phone and configured player-gender rules. Flyway V5 now also enforces nonblank gender values of at most 200 characters (or NULL) for **direct SQL INSERT and UPDATE** paths, matching the existing application validator; no binary-only gender list is introduced. Flyway V8 additionally enforces **raw** maximum lengths for member names, contact fields and gender, and for team names, preventing large whitespace-padded values from bypassing trimmed-length checks. Synthetic PostgreSQL tests cover rejected empty/oversized values on both operations. Synthetic PostgreSQL regression coverage verifies that a rejected policy update leaves every selected member field unchanged, and that a revoked editor cannot change a name. Additional coverage verifies that converting a contactless minor into an adult is rejected without both contacts, preserves the original row, and succeeds once the required contacts are provided. Callers must not treat a database validation exception as a safe public response or echo private input in logs. This service has **no HTTP route**, browser submission, or field-read API, and does not authorize real data: R03 privacy, R04 contract, audit, session/Origin/CSRF and field-level visibility must all be reviewed before exposure.


## Safe club policy transitions (V7; pre-production)

Club settings are not yet editable through HTTP. Flyway V7 rejects database updates that **lower the adult age threshold** while any existing newly adult member lacks email or phone, or that **enable required player gender** while any existing player lacks that field. Operators must explicitly correct local synthetic records before tightening either policy; rejected settings changes keep the prior policy. The member validation trigger now reads the singleton club row with `FOR SHARE`, serializing member writes against policy-row updates so concurrent transactions cannot evade the new rule. PostgreSQL tests cover both rejections, subsequent valid transitions, and enforcement on later member edits.

This does not create a retention decision, authorise publication of DOB/gender/contact data, resolve real-world birthday-driven transitions, or approve a settings UI. R03/R04 and operational/privacy acceptance remain blocking.

## Internal-only archived member lifecycle (V10; #26)

Flyway V10 adds nullable `members.archived_at` to distinguish inactive records
from active members without irreversible erasure. The database rejects any
archived row retaining a linked account or the ADMIN role. The internal
`MemberArchive.archive(verifiedSessionAccountId, memberId)` operation requires
current explicit `EDIT_MEMBERS`, targets an **unlinked ordinary MEMBER** only,
and sets the timestamp while clearing team assignments and all club/team grants
in one transaction. It serializes privilege revocation through the existing
permission-mutation advisory lock. Repeating archive or acting without authority
returns false. Linked members and administrators cannot be archived through this
path; first unlink and perform separate reviewed authorization decisions.

Active member directory queries exclude archived records; detail updates, role
changes, team assignments, capability grants, and club-access checks refuse
archived targets. Team assignment locks its target member row to serialize with
archival and avoid a post-archive assignment race. Synthetic PostgreSQL tests
verify the visibility, revocation, non-reactivation, and database constraints.

### Database-level archive invariants (V11)

V11 rejects direct SQL `INSERT`/`UPDATE` into club grants, team grants and
team assignments when the referenced member is archived. Grant/assignment
writes take a sharing lock on the member row, serializing with archive updates.
Setting `archived_at` by direct SQL fails while any such grant or assignment
still exists. The internal archive service takes the permission-mutation lock,
then holds the editor's current `EDIT_MEMBERS` grant and the target row locked,
removes dependent rows and archives in **one transaction**. A denied or failed
attempt does not commit partial privilege removal. Synthetic PostgreSQL tests
cover direct-SQL bypass attempts, unauthorized archiving and cleanup. A
controlled two-connection race test additionally holds an archive row lock,
observes the opposing grant insert waiting in PostgreSQL `pg_stat_activity`,
then commits archival and verifies the insert fails without leaving a dormant
grant. This checks actual lock contention, not just concurrent thread startup.

These constraints guard data integrity; database operators still need strict
privilege separation and audit review. They do not approve a public archive
endpoint or access to private member records.

The internal `MemberReactivation.reactivate(verifiedSessionAccountId, memberId)`
operation may return an archived, unlinked ordinary MEMBER to active status
only with a current `EDIT_MEMBERS` grant. It never recreates removed club/team
grants, team assignments or account links; those require new separately
authorized operations. The member-field validation trigger still applies on
reactivation, so an archived minor who has become an adult may be rejected
until a separately reviewed policy-compliant correction is made. There is no
HTTP endpoint or automatic unarchive action.

**Not a deletion or release approval:** Archived personal details remain in the
installation database and backups. There is no HTTP endpoint, browser UI,
restore action, retention duration, or erasure mechanism. R03 privacy review,
R04 API contract, audit behavior, authorized unlink, and operator retention
policy remain prerequisites for real club data. Do not call an archived record
anonymized or permanently deleted.

**Internal member input normalization (2026-11-16):** Shared member field validation rejects embedded control characters in names, emails, phone and gender before writing through the internal creation/update services; leading/trailing whitespace is still trimmed. This does not establish email deliverability, private-field visibility or approval for HTTP member APIs.

**Internal name-only member update (2026-11-16):** `MemberDetailsUpdate.updateNames` updates just validated first/last names under a live `EDIT_MEMBERS` grant and active actor/target checks. DOB, contacts, player attributes, account links, roles and grants are untouched. This supports a proposed narrow `PATCH /v1/members/{memberId}` without a private-field round trip, but the route remains **unimplemented** pending R03 review of names/minors and R04 contract approval. Full details replacement remains a separate, more sensitive operation.

**Email validation baseline (2026-11-16):** The shared internal member validator now requires a single `@`, nonempty local/domain components, no embedded whitespace or control characters, and length 3–320 for nonempty emails. This is intentionally a minimal format check, **not** address existence, verification, deliverability or legal/consent approval. The PostgreSQL adult contact policy still applies independently.

## Authenticated member HTTP slice (synthetic M1 only; 2026-11-16)

Ktor now exposes a distinct, verified-session member administration boundary: permission-checked name search/detail via `VIEW_MEMBER_DIRECTORY`, and accountless creation/name-only PATCH/unlinked ordinary-member archive via explicit `EDIT_MEMBERS`. Writes validate exact Origin and session-bound CSRF before parsing bounded JSON. Member field validation and PostgreSQL constraints remain authoritative; name-only edits preserve DOB, contacts and gender. AES-GCM page cursors are bound to the acting account and normalized query and expire on restart. The operator-created first ADMIN receives three **explicit** grants (`MANAGE_PERMISSIONS`, `VIEW_MEMBER_DIRECTORY`, `EDIT_MEMBERS`) atomically; no other ADMIN receives implicit rights and privileged role/permission management remains separate. Anonymous public output still contains only server-masked display names. Synthetic API denial and real Keycloak browser tests cover the workflow. This is not legal approval for real data or implementation of confidential detail, team or grant HTTP APIs.

## Check order and denial behavior: member and club-settings routes (synthetic M1)

Callers without the required grant always get `403`, never `400`, so a denied caller learns nothing about the expected input. After authentication (`401`) and, for writes, the exact Origin and session CSRF checks (`403`), each route checks its capability **before** it validates the path, query or body:

- `GET /api/v1/members` and `GET /api/v1/members/{memberId}`: the club-wide `VIEW_MEMBER_DIRECTORY` grant (then `limit`, `q`, `cursor` and the path id are validated: `400`; an unknown or archived member is `404`).
- `POST /api/v1/members`, `PATCH /api/v1/members/{memberId}` and `POST /api/v1/members/{memberId}/archive`: the club-wide `EDIT_MEMBERS` grant (then the path id and body are validated: `400`; an unknown or archived member is `404`; archiving a protected member is `409`).
- `PATCH /api/v1/club`, `/api/v1/club/player-gender-policy` and `/api/v1/club/adult-age-policy`: a live, identity-bound ADMIN with `MANAGE_PERMISSIONS` (then the body is validated: `400`).

The SQL-authorized services and `UPDATE` statements still re-check the grant atomically and remain authoritative; the up-front check is only for ordering. Field validation (names, birth date, contacts, gender) now runs while the request is parsed, because only parse errors map to `400`: an unexpected exception from the database or a service is `503`. A non-boolean `isPlayer` (string, number or `null`) is `400`; it previously escaped as `503`. When PostgreSQL itself rejects a write, the status depends on why. A rejection caused by the data (a data exception, an integrity constraint, or one of the schema's business-rule triggers such as the adult-contact and player-gender rules, which raise SQLSTATE `P0001`) is `400` on member creation, and `409` on team create or rename and on a stricter club policy. Any other database failure (a lost connection, a timeout, a serialization failure) is `503`, not a client error or a conflict.

The auth routes (`/api/v1/auth/login`, `/callback`, `/me`, `/logout`) and the anonymous `GET /api/v1/public/members` were checked with malformed input and needed no change: a garbage, oversized or duplicated session cookie is always `401`, a malformed callback is `400` before the backend is called, `logout` accepts only an empty JSON object, and the anonymous list ignores query parameters and accepts only `GET`.

## Audit trail (V12; privacy proposal section 5)

`audit_events` records who changed what, as identifiers only: event type, acting account UUID (empty for operator commands), target member UUID, and for grants the capability, scope and team, for roles the new role. It has no name, birth-date, contact or free-text column (tested). Events are written **in the same statement or transaction as the change**: member creation and updates use a data-modifying CTE, and archive, role changes, grants and administrator recovery write as the last step of their existing transaction. Denied, repeated (idempotent) and failed changes write nothing. A role change or an archive implies the cleanup of the target's grants, which has no separate event. The table is append-only (an `UPDATE` is rejected), has no foreign keys so it outlives deleted members, accounts and teams, and its UUIDs are pseudonymous identifiers, not anonymous data. The 12-month retention job exists ([retention cleanup](retention-cleanup.md)), and operators read the trail with the read-only `showAuditTrail` command ([single-club operations](../deployment/single-club.md#reading-the-audit-trail-operator-only-read-only)). Invitation and unlink events are written by the services in V17 ([invitations](#invitations-and-account-unlinking-v17-services-and-http-routes-built)). Not built yet: the `CONTACT_READ` event (allowed by V17, written with the detail read) and reactivation. First-club bootstrap is audited (`CLUB_BOOTSTRAPPED`, V14; operator action with no actor, target the first administrator). Erasing an archived member is audited as `MEMBER_ERASED` (V15), by an operator command, by an authorized editor through the service, or by the 24-month retention job (no actor); only the member UUID remains, kept for the 12-month audit retention. An operator export of one member's data is audited as `MEMBER_EXPORTED` (V16, no actor).

## Invitations and account unlinking (V17; services and HTTP routes built)

Owner-approved design ([invitation design](invitation-linking-design.md); ADR-0008 addenda 2 to 4). `MemberInvitations` lets an administrator with a live club-wide `EDIT_MEMBERS` grant invite an **active, unlinked, adult, ordinary** member (an administrator, an archived member and a minor are refused: a guardian signs in for a minor, which is a separate, reviewed design). The invitation secret is 256 random bits, shown once; **only its SHA-256 hash is stored** (tested: the secret appears in no column). It lives 7 days, there is at most one unused, unrevoked invitation per member (an expired one is retired when a new one is created), and creating, revoking and accepting are audited (`INVITATION_CREATED`, `INVITATION_REVOKED`, `INVITATION_ACCEPTED`).

Accepting needs **two proofs**: the secret and a verified account that has a verified identity and no member yet. In one transaction the service locks the account, then the invitation, then the member (a fixed order, so concurrent calls cannot deadlock), re-checks that the member is still active, unlinked, ordinary and adult under the club's current adult age, and only then links the account and marks the invitation used. **Every failure is the same `REFUSED`** (wrong, malformed, used, expired, revoked, an ineligible or since-changed member, an account without a verified identity or that already has a member), so a caller learns nothing about members or invitations; a refusal changes nothing and writes no event. Two accounts racing one secret have exactly one winner (tested over ten rounds). Linking **grants nothing**: the member keeps role `MEMBER` and no capability. Email, name and birth date are never inputs.

`MemberAccountLink` unlinks a member from their account for a lost provider login or before archiving. It needs a live club-wide `MANAGE_PERMISSIONS` grant held by an `ADMIN` with a verified identity, serializes under the permission-mutation advisory lock, deletes the account's sessions, clears the member's grants, and records `ACCOUNT_UNLINKED`. Administrators are never unlinked here (operator recovery only). Open invitations of a member who is later archived or erased are harmless (acceptance refuses an archived member and erasure cascades). Not built yet: the HTTP routes and contract 1.2.0, the four-language administration and acceptance screens, a browser journey with a second synthetic user, and retention of old invitation rows (used and expired rows stay until the member is erased).

**HTTP routes (OpenAPI 1.2.0).** `POST` and `DELETE /api/v1/members/{memberId}/invitation` need `EDIT_MEMBERS`; `DELETE /api/v1/members/{memberId}/account-link` needs `MANAGE_PERMISSIONS`; `POST /api/v1/invitations/accept` needs no capability. Order of answers: session (401), exact Origin and CSRF (403), capability (403, before any input is read, so a caller without the grant never sees 400), then validation (400), then the service result (404, 409). Acceptance answers one generic `400 {"code":"invalid"}` for every failure (wrong, expired, used or revoked secret, an unverified account, an account that already has a member, a member who is no longer eligible) and never reflects the secret. It is rate limited per process (`429` with `Retry-After: 60`, budget checked before any database work). The secret is returned once, in the create response, and only its hash is stored. `MemberInvitationHttpTest` covers the order of answers, input matrix, the whole flow, generic refusals and the rate limit; `PrivateRoutesRevocationTest` covers the three capability routes. **Browser UI (DE/EN/FR/IT).** An administrator with the grants searches a member and creates, revokes or unlinks from the *Invitations and account links* section; the one-time link (`/#invite=<secret>`) is shown once and the secret travels in the URL fragment, so it is not sent to any server or logged. The link is opened by the member, who signs in with their own provider account; because sign-in leaves the page, the secret is kept in `sessionStorage` for that round trip only, removed from the address bar immediately and deleted on acceptance. A signed-in account that is not yet a member sees *Accept an invitation* (prefilled, or paste a code or link). Every failure is one generic message. The Playwright journey creates a second synthetic Keycloak user (`openclub-ci-invitee`), accepts the invitation in a separate browser context, checks that linking grants nothing, and that unlinking ends that session. Rauthy has no second synthetic user yet.

## Independent review (#24)

The repository owner reports that secureIt AG performed an independent security review on 2026-11-18 and that it found nothing worth fixing ([ADR-0008](../decisions/0008-owner-decisions-2026-11-18.md), decision 5). The report is not in the repository and was not read by the agent; the scope was not provided and is not recorded. This is a statement by the owner, not evidence the agent has verified.

## Revoked verified identity on every private route (#24, synthetic M1)

`PrivateRoutesRevocationTest` drives all 21 private club, member, role, grant, team and roster routes with one ADMIN holding every club grant. It first proves that the holder is authorized on each route (no 401/403/5xx), then deletes the account's verified OIDC identity while the session cookie stays valid and the grants stay in place. Every route must then answer `403` with `Cache-Control: no-store` and no member data, and the target member, grants and member list must be unchanged. A mutation check (skipping the identity deletion) makes the denial test fail, so it is not vacuous. A route-level test for a database that cannot answer (503) is still open: the unit-level mapping is covered by `DatabaseErrorsTest`.

## Concurrent last-manager protection over HTTP (#24, synthetic M1)

`ConcurrentLastManagerHttpTest` races destructive requests through the real routes (`DELETE /api/v1/grants` for `MANAGE_PERMISSIONS`, `PATCH /api/v1/members/{id}/role` to `MEMBER`) from two or three eligible managers, 15 rounds each, released at the same moment: revoke vs revoke, demote vs demote, revoke vs demote, and a three-way cycle. After every round at least one eligible manager (ADMIN, club-wide `MANAGE_PERMISSIONS`, verified identity, not archived) must remain, the remaining count must equal the starting count minus the `204` responses, and every other response must be a clean `403`. Mutation checks: removing the advisory lock from `CapabilityGrants` or from `MemberRoles` makes these tests fail (two `204` responses and no manager left). The SQL services have their own concurrency tests; this adds the HTTP pre-check and session lookup to the race. A route-level `503` test and the independent security review remain open on #24.

## Authorized club identity on the browser landing (2026-11-16)

The session-linked `GET /api/v1/club` returns only the installation-local `id`, `name` and `slug` of the singleton club to an active linked member, regardless of ADMIN or MEMBER role. Anonymous, unlinked and archived accounts remain denied. The React landing validates these fields and displays the escaped club name only for a ready authorized session; it never accepts a caller-supplied club or uses this access check to authorize edits. The response remains `Cache-Control: no-store`. Club settings mutation and M2 real-data approval are not implemented by this change.

## Narrow club-name mutation (synthetic M1 only)

`PATCH /api/v1/club` updates **only** the installation-local name; it never accepts club IDs, slugs, adult-age or player-gender policy fields. A verified browser session must identify an active, identity-bound `ADMIN` with an explicit live club `MANAGE_PERMISSIONS` grant. The server rechecks authorization in the database UPDATE rather than trusting client state. Exact configured Origin, matching CSRF token, JSON content type and a strict 4 KiB name-only object are required; names are trimmed, bounded 1–200 characters and reject control characters. It returns `204` on success, `400` invalid, `401` unauthenticated, `403` unauthorized, `503` unavailable, and `Cache-Control: no-store`. The DE/EN/FR/IT browser form updates the club heading after a successful write. Synthetic PostgreSQL and real Keycloak tests check allowed/denied behavior. **No other settings writes or real-data/legal privacy approval are implied.**

## Role management synthetic M1 boundary (2026-11-16)

The authenticated `GET /api/v1/members/{memberId}/role` reveals only the active member's `ADMIN` or `MEMBER` value to a current verified, linked `ADMIN` with an explicit club `MANAGE_PERMISSIONS` grant. `PATCH` accepts only `{"role":"ADMIN"|"MEMBER"}` with exact configured Origin, session-bound CSRF and a bounded JSON body. The existing `MemberRoles` transaction coordinates with grant mutations, rejects self-change, inactive/unknown targets, archived/unlinked permission managers, and last-admin/last-permission-manager lockout. It strips dormant permission-manager grants on role transitions; promotion does not automatically grant capabilities or issue a login. The browser role-management form is available in DE/EN/FR/IT, and Ktor/PostgreSQL/Keycloak tests use synthetic members. This is **not** permission for handling real member data or approval of full private-field/role/audit contracts; M2 review remains required.

## Synthetic player creation form and gender-policy visibility (2026-11-16, PR #213)

The authorized `GET /api/v1/club` includes `genderRequiredForPlayers` as a boolean to inform the client-side player creation form; it does **not** expose any person's gender or birth date. The existing POST member contract already accepts `isPlayer` and optional free-text `gender`. The four-language browser sends those fields only when a player is selected and marks the gender input required only when the stored club flag is true. PostgreSQL continues to enforce the club's active policy, so invalid writes return a generic 400 without reflecting private values. All member directory and anonymous public name responses remain unchanged. This is synthetic M1 usability, not approval of a gender taxonomy, legal basis, consent, disclosure, or real-record processing under R03/R04/M2.

## Synthetic club player-gender requirement update (PR #214)

A verified active identity-bound ADMIN with an explicit current MANAGE_PERMISSIONS grant can send `PATCH /api/v1/club/player-gender-policy` using exact Origin, matching session CSRF and the single boolean `genderRequiredForPlayers`. The server checks authorization in the same SQL statement as the singleton update, and Flyway V7 protects concurrent member writes and refuses enabling the rule if any existing player lacks gender. Invalid bodies yield 400, missing authentication 401, denial 403, incompatible existing records 409 and other failures 503; responses are no-store. The four-language settings form surfaces the conflict without revealing which members lack the field. This does not approve gender categories, disclosure, consent, retention or real-member processing under R03/R04/M2.

## Browser validation acceptance (synthetic M1, PR #215)

The disposable Keycloak/Chromium administrator journey now asserts that an adult member cannot be created without both email and phone even if the browser allows a submit; PostgreSQL/Ktor returns a generic validation error and no partial member. Supplying both enables the same fictional adult to be created and archived. Once the club's player-gender requirement is enabled, the localized member form marks gender required for players and blocks a missing value before submission. These tests are synthetic only and do not decide disclosure, retention, consent or age thresholds for real club records.

## Synthetic accountless creation: identity revocation boundary (PR #216)

The member-create INSERT now checks the current linked, unarchived actor, explicit `EDIT_MEMBERS` club grant **and a current verified OIDC identity association in the same SQL statement**. This closes a gap where a database caller with a stale grant and account binding could bypass the verified-identity condition that already protects privileged role/grant changes. Removing the identity association immediately prevents new member records even if a synthetic session handle remains valid; the Ktor route still applies exact Origin and session CSRF checks. PostgreSQL and route-level denial tests cover missing and revoked identity. No new personal fields or data disclosures, and M2 privacy/production approval remains separate.

## Member archive confirmation in synthetic browser (PR #217)

The DE/EN/FR/IT member-management form now uses a two-step, member-specific archive confirmation. Opening the confirmation does not call the backend; Cancel leaves the member active and visible, while Confirm archives only the selected member via the existing session/CSRF-guarded route. The Keycloak/Chromium acceptance test exercises both paths. This usability safeguard does **not** replace the server's account-link/role/grant checks, nor does archive equal GDPR erasure; retention and M2 privacy approvals remain open.

## Member edit identity-revocation guard (PR #218)

Both the synthetic name-only HTTP member update and the currently internal full-detail update now check for a live verified account-identity association **in the same SQL statement** that changes fields, alongside the linked active actor and explicit EDIT_MEMBERS grant. Removing the identity association immediately prevents new edits, without depending on a separate permission preflight; no roles, grants, account links or private fields are added to the name-only response. PostgreSQL regression tests cover absent and revoked identities for both edit paths; Ktor HTTP tests verify the revoked session cannot edit names. Full-detail HTTP remains unavailable pending R03/R04 privacy review.

## Lifecycle identity-revocation boundary (PR #220, synthetic M1)

Member archival now rejects an actor whose verified account-identity association was removed, even if a stale session and EDIT_MEMBERS grant remain. The authorized actor and grant are locked inside the existing PostgreSQL archive transaction; the identity existence check is part of that authorization query. The internal-only member reactivation update applies the same identity requirement in its SQL statement. Synthetic PostgreSQL tests cover absent/revoked identity for both operations; a Ktor test checks the revoked identity cannot archive a separate active accountless member. No exported personal data or deletion/erasure is introduced; R03/M2 legal retention and release review remain open.

## Team identity-revocation protection (PR #227, synthetic M1)

Team create/rename/confirmed-delete, team-scoped assign/unassign, capability-limited team list and the exact-team roster now deny a session-derived account once its verified OIDC account-identity association is removed. The team service's six SQL operations and the scoped team capability lookup all check the live identity row in the same statement as the read/write; list and rename/delete HTTP preflight checks return generic 403 rather than empty 200 or misleading 404 after revocation. PostgreSQL and Ktor regressions exercise absent and revoked identity while team grants and a synthetic browser session remain. Team/club visibility and member-directory permission gates remain separate. This prevents stale authorization from surviving identity revocation; it is not a substitute for session invalidation, provider security, or M2 real-data/legal review.

## Identity-bound private member reads (PR #228, synthetic M1)

All club-wide capability checks now require an active linked member, an explicit grant **and a current verified OIDC identity association**; MANAGE_PERMISSIONS still additionally requires ADMIN. The member-directory SQL, keyset pagination and member name-only detail query enforce the identity in the same database statement as the private-data read. Once an identity binding is removed, stale sessions and retained VIEW_MEMBER_DIRECTORY/EDIT_MEMBERS grants cannot enumerate member names or read a detail. Synthetic PostgreSQL and Ktor tests cover missing/revoked identity, with generic forbidden responses and no private data. The anonymous, masked public display-name endpoint remains a separate owner-approved synthetic-M1 policy, not private directory access. No real member data or M2 privacy authorization.

## Localized archive safety in the real browser (PR #231, synthetic M1)

The disposable Keycloak/Chromium member-administration journey now opens and cancels an archive confirmation on the **same fictional member** in German, French, Italian and English, verifying that the member remains visible after every cancellation. It then explicitly confirms archival and verifies removal from the active administration list. This adds four-language behavioral coverage beyond translated labels, while retaining backend identity/grant/CSRF authorization, and does not imply permanent deletion, production accessibility acceptance or M2 privacy approval.

## Accessible archive target context (PR #232, synthetic M1)

The inline archive-confirmation prompt and its Cancel/Confirm buttons now sit inside a named accessibility group. Its accessible name combines the localized archive question with the **selected member's name**, so assistive technology can distinguish the target when more than one member row has an Archive action. The real Keycloak/Chromium journey asserts this group context in DE/EN/FR/IT and confirms cancellation behavior remains unchanged. This is an incremental accessibility improvement, not a complete WCAG audit or any M2 privacy/release approval.

## Single-club metadata identity revocation (PR #233, synthetic M1)

The private `GET /api/v1/club` lookup now requires an active linked local member **and a current verified OIDC identity row in the same SQL query**. A test-only still-valid synthetic session cannot retrieve the club's name, slug, ID or player-policy flag after identity revocation. Direct SQL and Ktor tests cover unverified linkage, normal access, revocation and generic no-store 403 denial. This complements the real session revocation boundary from #229–#230 and does not disclose new personal fields or constitute M2 approval.

## Adult contact guidance in the member form (PR #235, synthetic M1)

The member-create form now explains in DE/EN/FR/IT that both email and telephone are mandatory for **adults** and optional for **minors**. The same hint is associated with both inputs through `aria-describedby`, and a real Keycloak browser test checks the translated message. Because the installation's adult threshold is not exposed by the minimal club API, the UI does not guess age eligibility or mark both fields globally required; PostgreSQL remains the final authority for birthdate-dependent contact validation. The guidance uses no new personal fields or real member data and does not substitute for R03/M2 policy/consent sign-off.

## Anonymous minor-name browser regression (synthetic M1)

The real disposable Keycloak/Chromium member journey additionally checks the anonymous public endpoint after creating a fictional minor: it returns an abbreviated surname (`Nina P.`), never the private full name (`Nina Private`). The same browser journey checks that renaming a minor immediately updates the anonymous initial (`Nina P.` to `Nina R.`), removes the old display name and never reveals the full new surname; archiving then removes the abbreviated name from the anonymous API, not just from the administration UI. This complements the authorized administrator's full-name member row and the already tested anonymous browser display; it does not authorize publishing real minors' data or change the R03/M2 privacy gate.

## Unlinked identity API denial in browser acceptance (synthetic M1)

The real disposable Keycloak/Chromium sign-in journey now calls the private club, member-directory and team-list HTTP endpoints while authenticated as a verified but **unlinked** OIDC account. Each must return a no-store 403 rather than exposing club data. This verifies that login alone does not grant membership across actual browser cookies and proxied API routes; it neither changes the access policy nor substitutes for independent M2 security and privacy review.

## In-flight member search cancellation (synthetic M1)

The authorized member administration now aborts the previous directory request when a new search, pagination read or mutation refresh starts, and also aborts outstanding requests when the component unmounts. A superseded response cannot replace newer member names or display a stale error. Real Keycloak/Chromium acceptance holds a synthetic older search while submitting a new one and verifies network cancellation and the latest results; all existing server-side identity, grant and privacy checks remain authoritative.

## Public member list refresh after changes (synthetic M1)

On member create/edit/archive, the anonymous public-name component now clears its previous display while reloading from the server with cache bypass, instead of temporarily displaying stale minor names. The existing abortable request and strict display-name-only response validation remain intact. Real Keycloak/Chromium acceptance verifies the archived minor is absent from the browser's public list as well as the anonymous HTTP response. This is a synthetic usability/privacy safeguard, not permission to publish real member information.

## Member search failure and retry (synthetic M1)

If a private member search fails, the four-language browser shows its existing localized error and retry action without simultaneously claiming the directory is still loading forever. The real Keycloak/Chromium acceptance deliberately fails one search request, checks the visible error state, retries successfully and restores the full list. No private error details are exposed; the server-side authorization and access rules remain unchanged.

## Team candidate search freshness (synthetic M1)

The team-assignment member picker now clears outdated options and cancels any previous candidate search when a new query starts or the team component unmounts. Aborted requests cannot replace the current selection or report a misleading error. The disposable Keycloak/Chromium journey delays a fictional old search, requests a new member and checks cancellation before performing the normal authorized team assignment. Server-side team and member-directory grants are unchanged.

## Initial team roster failure state (synthetic M1)

If a first team-roster request fails, the UI displays its localized error rather than showing an indefinite loading message. Existing loaded roster pages remain visible on later request failure. The real disposable Keycloak/Chromium acceptance injects one failed team-roster response and confirms the loading state clears before continuing the synthetic assignment journey. This does not change team permissions or expose private server errors.

## Team roster retry (synthetic M1)

After a failed team-roster read, a localized retry control in DE/FR/IT/EN reloads the current team without changing assignment grants. The synthetic Keycloak/Chromium journey verifies recovery from an injected first-read failure before selecting and assigning a fictional member.

## Role editor member search and detail freshness (synthetic M1)

The role editor aborts superseded member searches and role-detail lookups, clears outdated selection while searching, and discards canceled responses without exposing raw errors. This prevents stale role reads from setting the role dropdown for a different selected member. The disposable Keycloak/Chromium flow verifies cancellation of a fictional old member query before selecting and safely updating the intended member role. Backend authorization, administrator safeguards and M2 privacy approvals remain unchanged.

## Localized player-policy conflict after language changes (synthetic M1)

The club player-gender policy editor retains a semantic conflict/failure code rather than a language-specific error string. A failed save therefore displays the correct current DE/EN/FR/IT message after a language change; it never changes the persisted rule on a 409. Disposable Keycloak/Chromium acceptance injects a synthetic conflict, checks all four translations and verifies a subsequent valid save. The PostgreSQL constraint and existing administrator permissions remain authoritative; M2 privacy approval is unchanged.

## Localized transient player-policy failure (synthetic M1)

Disposable Keycloak/Chromium acceptance also injects a transient server failure on the existing policy-save endpoint, switches among all four UI languages and verifies that the generic error follows the selected language. Retrying after recovery clears the error. This does not change policy authorization, server enforcement or M2 approval.

## Member directory error localization after language switching (synthetic M1)

Member directory failure messages now store a semantic error state and render in the currently selected DE/FR/IT/EN language, rather than retaining the language used when a request failed. The real disposable Keycloak/Chromium journey fails a synthetic member search, switches all four languages, verifies the localized error and retries successfully. No raw error data, private member field or access-control rule changes.

## Role mutation denial closes privileged editor (synthetic M1)

If a role-change request returns HTTP 403, the browser hides the privileged role editor rather than retaining stale authorized controls with only a generic error. Disposable Keycloak/Chromium acceptance injects the denial after a successful synthetic promotion/demotion and verifies no role editor remains. PostgreSQL/server role and last-manager checks are unchanged; this is a fail-closed presentation guard, not production security acceptance.

## Grant operation denial hides permission editor (synthetic M1)

The permissions editor now fails closed on HTTP 403 from member search, grant-detail reads or grant mutations, hiding its privileged UI instead of leaving stale member/grant controls visible. The disposable Keycloak/Chromium workflow injects a denied grant write after authorized grant/revoke operations and verifies the editor disappears. Database grants, role checks and M2 release requirements remain unchanged.

## Team candidate search denial hides roster controls (synthetic M1)

An HTTP 403 during team-assignment candidate member search now hides that team's member roster and name-bearing assignment controls, matching the existing roster-read and mutation authorization-denial behavior. A disposable Keycloak/Chromium test injects an explicit denied search after a permitted team workflow. This frontend guard does not replace server-side directory/team grant enforcement or legal/privacy approvals.

## Team roster request freshness (synthetic M1)

The team roster cancels superseded paginated/reload GET requests and pending requests on unmount. A delayed old response or denial cannot overwrite a newer roster after a member assignment changes. A disposable Keycloak/Chromium acceptance test delays the post-removal roster read, reassigns the synthetic member and verifies the stale fetch is canceled while the current roster remains visible. Server-side permissions remain authoritative; this is not a real-data release approval.

## Expired member session hides private administration (synthetic M1)

Member search and mutation HTTP 401 responses now close the private member editor, the same as HTTP 403, instead of leaving previously fetched names and edit/archive controls visible after session expiry. The disposable Keycloak/Chromium journey simulates a lost session during private member search and verifies that stale private rows and creation controls disappear. Authorization remains server-side; the separate approved anonymous display-name list remains available after logout. This is not production privacy approval.

## Expired sessions across private club administration (synthetic M1)

Club settings, role management, team rosters and permission management now treat HTTP 401 as lost private access, just like HTTP 403. Team directory HTTP 401 on a create/rename/delete also hides team names, while an HTTP 403 for a write can retain legitimately read-only team visibility. Real disposable Keycloak/Chromium acceptance injects a rejected team creation after an authorized workflow and checks that existing private teams disappear. Server-side session and grant validation remains authoritative; this does not authorize real member data.

## Expired-session browser denial matrix (synthetic M1)

The disposable Keycloak/Chromium workflow injects HTTP 401 during the initial permission-capability check and role-member search, confirming both privileged editors disappear without exposing retry controls or previously selected names. This complements HTTP 401 denial tests for member searches and team mutations. These targeted injected responses verify frontend fail-closed presentation, not an independent production-role security audit or live-data permission.

## Shared actor-authorization predicate (2026-11-17)

Previously, each protected query had its own copy of the actor check: active, unarchived linked member; current verified OIDC identity row; explicit grant. These now come from one place, `members/ActorSql.kt`:

- `activeVerified(alias)`: the member is unarchived and has a current verified identity.
- `holdsClubCapability(capability, requireAdmin)`: EXISTS gate for a club-wide grant, optionally requiring ADMIN.
- `holdsTeamCapability(team, capabilities)`: EXISTS gate for a club-wide grant or a grant on the exact team.

Authorization stays **inside the same SQL statement** as the read or write; the fragments are embedded, not run as a separate preflight check. The fragments interpolate only validated aliases and enum names; the actor account ID stays a bind parameter.

Consolidating the copies exposed one that had drifted. The internal team roster query (`TeamMemberDirectory.list`) checked grants but not the actor's verified identity. The HTTP route already rejected revoked identities through separate preflight checks, so this was a defence-in-depth gap, not a reachable bypass. The roster query now requires a verified identity itself, and `TeamMemberDirectoryTest` covers revoking and restoring it.

Behaviour changes are fail-closed only. Club-wide capability gates for member and club-settings writes now also require the installation's club row to exist, as team writes already did. That can only matter in an inconsistent database with no club.

## Per-club adult age (2026-11-17, ADR-0007)

`PATCH /api/v1/club/adult-age-policy` accepts only `{"adultAgeYears": <whole number 13–25>}`. It uses the same guards as the player-gender policy: a verified session, exact Origin and session CSRF, and an UPDATE that itself requires a live, identity-bound ADMIN with `MANAGE_PERMISSIONS` (`ActorSql.holdsClubCapability`).

The V7 trigger rejects a lower threshold while members who would become adults lack email or phone. The API answers `409 {"code":"conflict"}` without naming them. Raising the threshold never conflicts.

`GET /api/v1/club` exposes the threshold (`adultAgeYears`), not any person's data. Public names already use the club's threshold to decide between full names and first name plus initial.
