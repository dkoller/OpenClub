# M1 operation-to-test coverage (synthetic; supports R04/#13 review)

Maps each of the 123 registered synthetic M1 operations in
[`openapi-v1.yaml`](openapi-v1.yaml) (version 1.40.0) to the tests that exercise it. The operation list and
documented status codes below were generated from that file and every test class named exists in
`apps/api/src/test/kotlin/org/openclub/api`. It is a review aid. It does **not** approve the contract,
private fields, retention or real data; those remain open in #13 and #12.

How to read the columns of test types:

- `…HttpRoutesTest`: the happy path and the main denials through the real Ktor routes on PostgreSQL.
- `…HttpInputAndEscalationTest`: malformed input, privilege escalation and "authorize before validate"
  (callers without the grant get 403, never 400) for the whole route group (#24).
- `PrivateRoutesRevocationTest`: a still-valid session whose verified identity was revoked gets 403 on every
  private route.
- `ConcurrentLastManagerHttpTest`: racing role changes and grant revocations always leave a manager.
- Service tests (`MemberCreationTest`, `CapabilityGrantsTest`, …): the same rules at the SQL service layer.

| Operation | Documented statuses | Tests |
|---|---|---|
| `GET /v1/club` | 200, 401, 403, 503 | `SingleClubRoutesTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/club` | 204, 400, 401, 403, 503 | `SingleClubRoutesTest`, `ClubSettingsHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/club/player-gender-policy` | 204, 400, 401, 403, 409, 503 | `ClubSettingsHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ClubPolicyChangeTest` |
| `PATCH /v1/club/adult-age-policy` | 204, 400, 401, 403, 409, 503 | `ClubSettingsHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ClubPolicyChangeTest` |
| `GET /v1/public/members` | 200, 503 | `PublicMemberNamesTest`, `AuthAndPublicRoutesInputTest` |
| `GET /v1/public/club` | 200 | `MemberHttpRoutesTest` (name only, `no-store`); 404 and 503 not covered |
| `GET /v1/members` | 200, 400, 401, 403, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberDirectoryTest` (team names per row, sorted, empty list without a team) |
| `GET /v1/members/archived` | 200, 400, 401, 403, 503 | `ArchivedMemberSearchHttpTest` (EDIT_MEMBERS only, key set, paging, cursors bound to list and query), `PrivateRoutesRevocationTest` |
| `POST /v1/members` | 201, 400, 401, 403, 409, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberCreationTest` |
| `GET /v1/members/{memberId}` | 200, 400, 401, 403, 404, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/members/{memberId}` | 204, 400, 401, 403, 404, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberDetailsUpdateTest` |
| `POST /v1/members/{memberId}/archive` | 204, 400, 401, 403, 404, 409, 503 | `MemberHttpRoutesTest`, `MemberHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `MemberArchiveTest`, `ArchivedMemberConcurrencyTest` |
| `GET /v1/members/{memberId}/role` | 200, 400, 401, 403, 503 | `MemberRoleHttpRoutesTest`, `RoleHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `PATCH /v1/members/{memberId}/role` | 204, 400, 401, 403, 503 | `MemberRoleHttpRoutesTest`, `RoleHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ConcurrentLastManagerHttpTest`, `MemberRolesTest` |
| `GET /v1/teams` | 200, 401, 403, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `POST /v1/teams` | 201, 400, 401, 403, 409, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamAssignmentsTest` |
| `PATCH /v1/teams/{teamId}` | 204, 400, 401, 403, 404, 409, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamAssignmentsTest` |
| `DELETE /v1/teams/{teamId}` | 204, 400, 401, 403, 404, 503 | `TeamHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `GET /v1/teams/{teamId}/members` | 200, 400, 401, 403, 503 | `TeamMemberHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamMemberDirectoryTest`, `TeamScopedGrantsTest` |
| `POST /v1/teams/{teamId}/members/{memberId}` | 204, 400, 401, 403, 409, 503 | `TeamMemberHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamScopedGrantsTest` |
| `DELETE /v1/teams/{teamId}/members/{memberId}` | 204, 400, 401, 403, 404, 503 | `TeamMemberHttpRoutesTest`, `TeamHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `TeamScopedGrantsTest` |
| `GET /v1/grants` | 200, 400, 401, 403, 503 | `GrantHttpRoutesTest`, `GrantHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest` |
| `GET /v1/roles/holders` | 200, 401, 403, 503 | `MemberRoleHttpRoutesTest`, `PrivateRoutesRevocationTest` (only club-wide `MANAGE_PERMISSIONS`; names and role, members and archived members left out) |
| `GET /v1/guardians/missing` | 200, 401, 403, 503 | `GuardianHttpTest`, `PrivateRoutesRevocationTest` (only `EDIT_MEMBERS`; minors without a guardian, not adults, guarded minors or archived ones) |
| `GET /v1/grants/overview` | 200, 401, 403, 503 | `GrantHttpRoutesTest` (only club-wide `MANAGE_PERMISSIONS`; names and grants with team names, no contacts, archived members and members without grants left out) |
| `POST /v1/grants` | 204, 400, 401, 403, 503 | `GrantHttpRoutesTest`, `GrantHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `CapabilityGrantsTest` |
| `POST /v1/members/{memberId}/invitation` | 201, 400, 401, 403, 404, 409, 503 | `MemberInvitationHttpTest`, `PrivateRoutesRevocationTest`, `MemberInvitationsTest` |
| `DELETE /v1/members/{memberId}/invitation` | 204, 400, 401, 403, 404, 503 | `MemberInvitationHttpTest`, `PrivateRoutesRevocationTest`, `MemberInvitationsTest` |
| `DELETE /v1/members/{memberId}/account-link` | 204, 400, 401, 403, 404, 409, 503 | `MemberInvitationHttpTest`, `PrivateRoutesRevocationTest`, `MemberInvitationsTest` |
| `POST /v1/invitations/accept` | 204, 400, 401, 429, 503 | `MemberInvitationHttpTest` (no capability: every failure is one generic 400, rate limited), `MemberInvitationsTest` |
| `DELETE /v1/grants` | 204, 400, 401, 403, 503 | `GrantHttpRoutesTest`, `GrantHttpInputAndEscalationTest`, `PrivateRoutesRevocationTest`, `ConcurrentLastManagerHttpTest`, `CapabilityGrantsTest` |
| `GET /v1/members/{memberId}/details` | 200, 400, 401, 403, 404, 503 | `MemberDetailsAndErasureHttpTest` (fields per caller, team scoping, `CONTACT_READ` audit, archived reads by editors), `MemberReplaceAndReactivateHttpTest` (the editor's `ETag`), `PrivateRoutesRevocationTest` |
| `PUT /v1/members/{memberId}` | 204, 400, 401, 403, 404, 412, 428, 503 | `MemberReplaceAndReactivateHttpTest` (database rules as 400, cleared fields, role/link/grants untouched; `If-Match` missing 428, stale/malformed/weak/wildcard 412 with nothing changed, new `ETag` usable once), `PrivateRoutesRevocationTest`, `MemberDetailsUpdateTest` |
| `POST /v1/members/{memberId}/reactivate` | 204, 400, 401, 403, 404, 409, 503 | `MemberReplaceAndReactivateHttpTest` (no rights restored, current policy as 409, `MEMBER_REACTIVATED`), `PrivateRoutesRevocationTest`, `MemberArchiveTest` |
| `GET /v1/members/{memberId}/guardians` | 200, 400, 401, 403, 404, 503 | `GuardianHttpTest`, `PrivateRoutesRevocationTest` |
| `POST /v1/members/{memberId}/guardians` | 204, 400, 401, 403, 404, 409, 503 | `GuardianHttpTest` (eligibility, limit of two, audit), `PrivateRoutesRevocationTest` |
| `DELETE /v1/members/{memberId}/guardians/{guardianMemberId}` | 204, 400, 401, 403, 503 | `GuardianHttpTest`, `PrivateRoutesRevocationTest` |
| `GET /v1/me/dependents` | 200, 401, 503 | `GuardianHttpTest` (empty list for everyone else, revoked identity) |
| `GET /v1/me/dependents/{memberId}` | 200, 401, 403, 503 | `GuardianHttpTest` (exact fields, identical refusals, adult age, archive, revoked identity, audit) |
| `PUT /v1/me/dependents/{memberId}/contacts` | 204, 400, 401, 403, 503 | `GuardianHttpTest` (relationship before body, cleared fields, audit) |
| `GET /v1/me/profile` | 200, 401, 403, 503 | `MyProfileHttpTest` (exact keys, teams, ETag, not audited; no member record or no verified identity 403), `PrivateRoutesRevocationTest` |
| `GET /v1/teams/{teamId}/attendance-statistics` | 200, 400, 401, 403, 404, 503 | `TeamEventHttpTest` (viewer, team and club managers; held excludes cancelled and out-of-range events; per member and totals; default range; player, plain member and other team 403; unknown team 404 only club-wide; malformed, repeated, unknown, reversed and over-a-year ranges 400), `PrivateRoutesRevocationTest`, Keycloak journey |
| `GET /v1/public/join-requests` (no session) | 200, 503 | `JoinRequestHttpTest` (closed: no teams; open: team names), Keycloak journey |
| `POST /v1/public/join-requests` (no session) | 202, 400, 403, 409, 429, 503 | `JoinRequestHttpTest` (Origin 403; privacy notice, age per kind, parent fields, unknown key and team, bad email 400 and nothing stored; honeypot and repeat 202 stored once; closed 409; 5 per address and hour 429, per last forwarded address; no address column), Keycloak journey |
| `GET /v1/club/join-requests/settings` | 200, 401, 403, 503 | `JoinRequestHttpTest` (`EDIT_MEMBERS` only), `PrivateRoutesRevocationTest` |
| `PUT /v1/club/join-requests/settings` | 204, 400, 401, 403, 503 | `JoinRequestHttpTest` (CSRF, `EDIT_MEMBERS`, boolean only), Keycloak journey |
| `GET /v1/join-requests` | 200, 401, 403, 503 | `JoinRequestHttpTest`, `PrivateRoutesRevocationTest`, Keycloak journey |
| `POST /v1/join-requests/{requestId}/accept` | 201, 400, 401, 403, 404, 409, 503 | `JoinRequestHttpTest` (CSRF, `EDIT_MEMBERS`; adult with contacts and wished team, `MEMBER_CREATED`; child without contacts; gone afterwards 404) |
| `DELETE /v1/join-requests/{requestId}` | 204, 400, 401, 403, 404, 503 | `JoinRequestHttpTest`, `PrivateRoutesRevocationTest`, Keycloak journey |
| `GET /v1/events/{eventId}/rides` | 200, 400, 401, 403, 404, 503 | `RideHttpTest` (phones only between driver and booked passengers, a minor's via the booking guardian; viewers, managers and other members see none; other team 403), `PrivateRoutesRevocationTest`, Keycloak journey |
| `POST /v1/events/{eventId}/rides` | 201, 400, 401, 403, 404, 409, 503 | `RideHttpTest` (team adults and guardians only; viewer, other team, unknown event 403; seats, departure window, direction, text bounds 400; second offer 409), `PrivateRoutesRevocationTest` |
| `PUT /v1/rides/{rideId}` | 204, 400, 401, 403, 404, 409, 503 | `RideHttpTest` (driver only, fewer seats than bookings 409), `PrivateRoutesRevocationTest` |
| `DELETE /v1/rides/{rideId}` | 204, 400, 401, 403, 404, 503 | `RideHttpTest` (driver or team manager; viewer 403), `PrivateRoutesRevocationTest` |
| `PUT /v1/rides/{rideId}/passengers/{memberId}` | 204, 400, 401, 403, 409, 503 | `RideHttpTest` (oneself or one's minor in the team only; two bookings for the last seat, one wins, other 409 `full`) |
| `DELETE /v1/rides/{rideId}/passengers/{memberId}` | 204, 400, 401, 403, 409, 503 | `RideHttpTest` (twice is 204, 409 `closed` after departure; leaving the team ends bookings and offers) |
| `GET /v1/me/absences` | 200, 401, 403, 503 | `AbsenceHttpTest` (own and minors' absences only, `people`), `PrivateRoutesRevocationTest`, Keycloak journey |
| `POST /v1/me/absences` | 201, 400, 401, 403, 503 | `AbsenceHttpTest` (self and guardian; someone else, a coach for a player 403; past, reversed, over a year, too far ahead, bad date, extra key 400; implied No, explicit answer wins, coaches see a plain No), Keycloak journey, `AnswerNotesHttpTest` (1.39.0: notes kept, replaced, removed, too long; 409 `answers_closed` after the deadline) |
| `DELETE /v1/absences/{absenceId}` | 204, 400, 401, 403, 503 | `AbsenceHttpTest` (someone else's and unknown alike 403, deleting removes the implied No), Keycloak journey |
| `GET /v1/me/notifications` | 200, 401, 403, 503 | `NotificationsTest` (defaults, `emailEnabled`, `hasEmail`, no member 403), `PrivateRoutesRevocationTest`, Keycloak journey |
| `PUT /v1/me/notifications` | 204, 400, 401, 403, 503 | `NotificationsTest` (CSRF, own row only, strict booleans and languages, extra key 400), `PrivateRoutesRevocationTest`; recipients, switches, outbox sending, retries and cleanup in `NotificationsTest`, configuration in `MailConfigTest` |
| `GET /v1/me/calendar-feed` | 200, 401, 403, 503 | `CalendarFeedHttpTest` (status without the link, no member 403), `PrivateRoutesRevocationTest` |
| `POST /v1/me/calendar-feed` | 201, 401, 403, 503 | `CalendarFeedHttpTest` (CSRF, only the hash stored, a new link replaces the old), `PrivateRoutesRevocationTest`, Keycloak journey |
| `DELETE /v1/me/calendar-feed` | 204, 401, 403, 503 | `CalendarFeedHttpTest` (revoked link 404, revoking twice 204), `PrivateRoutesRevocationTest`, Keycloak journey |
| `GET /v1/calendar/{secret}` (no session) | 200, 404, 503 | `CalendarFeedHttpTest` (own and children's teams only, window, notes, no names; unknown, malformed, hash-as-secret, revoked, replaced, unverified and unlinked all 404; `private, no-store`), `IcalTest` (escaping, folding, UTC), Keycloak journey |
| `GET /v1/events/{eventId}/shifts` | 200, 400, 401, 403, 404, 503 | `EventShiftHttpTest` (team members, guardians, viewers and managers see shifts; display names only, minors by initial; `signedUp` only for the caller's people; other teams and members without a team 403; unknown 404 only club-wide), `PrivateRoutesRevocationTest` |
| `GET /v1/shifts/unfilled` | 200, 401, 403, 503 | `EventShiftHttpTest` (only `MANAGE_TEAMS` holders; viewers, players, guardians and members without grants 403; only managed teams, free places, next 14 days, not cancelled; counts only, no names), `PrivateRoutesRevocationTest` |
| `POST /v1/events/{eventId}/attendance-reminders` | 200, 400, 401, 403, 404, 409, 503 | `NotificationsTest` (only `MANAGE_TEAMS` for the team; other team and unknown event 403 for a team-scoped holder; started 409; count only; recipients without answer, guardians for minors, switch respected; again within 12 hours 409 `recent`; without email 409 `email_off`), `PrivateRoutesRevocationTest`, Keycloak journey |
| `GET /v1/events/{eventId}/presence` | 200, 400, 401, 403, 404, 503 | `EventPresenceHttpTest` (leaders first, minors abbreviated, exact keys; viewer, player and another team's manager 403; unknown event 404), `PrivateRoutesRevocationTest` |
| `PUT /v1/events/{eventId}/presence` | 204, 400, 401, 403, 404, 409, 503 | `EventPresenceHttpTest` (saved and read back; before the start 409; someone outside the event, duplicates, wrong types and extra keys 400; another team 403; a record outlives the team assignment), `PrivateRoutesRevocationTest` |
| `POST /v1/events/bulk` | 200, 400, 401, 403, 404, 409, 503 | `BulkEventsHttpTest` (preview changes nothing; cancel only future events of the chosen kinds of the chosen team, one summary email; already cancelled not counted; reinstate; new place for trainings and matches; one team without the right or an unknown team 403; viewer and player 403; every invalid input 400 and nothing changed), `PrivateRoutesRevocationTest`, `BulkEventsHttpTest` (1.40.0: SET_TIMES keeps each day and the deadline lead, refuses bad times and a double booking) |
| `GET /v1/me/push` | 200, 401, 403, 503 | `PushHttpTest` (enabled with the public key; device count; off without configuration), `PrivateRoutesRevocationTest` |
| `PUT /v1/me/push-subscriptions` | 204, 400, 401, 403, 409, 503 | `PushHttpTest` (only https on an allowed host, no metadata or other hosts; at most 5 devices; 409 without configuration), `PushConfigTest` (endpoint rule), `PrivateRoutesRevocationTest` |
| `DELETE /v1/me/push-subscriptions` | 204, 400, 401, 403, 409, 503 | `PushHttpTest`, `PrivateRoutesRevocationTest` |
| `GET /v1/me/push-inbox` | 200, 400, 401, 403, 503 | `PushHttpTest` (own messages only, subject and time; a message queued for a member without email but with a device; each message wakes devices once; bad `since` 400), `PrivateRoutesRevocationTest` |
| `GET /v1/me/helper-duty` | 200, 401, 403, 503 | `HelperDutyHttpTest` (own and children's started shifts of this season; quota 0 for someone in no team; exact keys), `PrivateRoutesRevocationTest` |
| `GET /v1/helper-duty` | 200, 401, 403, 503 | `HelperDutyHttpTest` (liable adults only; future, cancelled and earlier shifts not counted; player 403), `PrivateRoutesRevocationTest` |
| `PUT /v1/club/helper-duty` | 204, 400, 401, 403, 503 | `HelperDutyHttpTest` (only `MANAGE_FEES`; out of range, text and missing fields 400), `PrivateRoutesRevocationTest` |
| `GET /v1/venues` | 200, 401, 403, 503 | `VenueHttpTest` (every member sees the list; `canManage` only for `MANAGE_VENUES`; signed out 401), `PrivateRoutesRevocationTest` |
| `POST /v1/venues` | 201, 400, 401, 403, 409, 503 | `VenueHttpTest` (only `MANAGE_VENUES`; duplicate ignoring case 409; blank 400) |
| `PUT /v1/venues/{venueId}` | 204, 400, 401, 403, 404, 409, 503 | `VenueHttpTest` (rename), `PrivateRoutesRevocationTest` (unknown 404) |
| `DELETE /v1/venues/{venueId}` | 204, 400, 401, 403, 404, 503 | `VenueHttpTest` (coach 403; events stay without the venue; again 404), `PrivateRoutesRevocationTest` |
| `GET /v1/venues/{venueId}/bookings` | 200, 400, 401, 403, 404, 503 | `VenueHttpTest` (team, kind, title and times only; cancelled left out; unknown 404; bad time 400), `PrivateRoutesRevocationTest` |
| `POST /v1/events/{eventId}/messages` | 200, 400, 401, 403, 404, 409, 503 | `CoachMessageHttpTest` (each audience reaches its members and guardians for children, naming the child and the sender; switch off; 10 per day then 409 `limit`; no text in the log; viewer and member 403; unknown event 403 for a team-scoped coach; squad of a training and long-past event 409; line break in the subject, long text and unknown audience 400; no mail configuration 409 `email_off`), `PrivateRoutesRevocationTest` |
| `GET /v1/events/{eventId}/squad` | 200, 400, 401, 403, 404, 409, 503 | `EventSquadHttpTest` (every team member with answer and call-up, minors abbreviated; a training 409; viewer 403; unknown event 404), `PrivateRoutesRevocationTest` |
| `PUT /v1/events/{eventId}/squad` | 204, 400, 401, 403, 404, 409, 503 | `EventSquadHttpTest` (newly called emailed once, a minor's guardian naming the child; saving again emails nobody; switch off stops it; training and past match 409; outsider, bad time, missing field, long point 400; player 403; leaving the team leaves the squad; own status in My schedule), `PrivateRoutesRevocationTest` |
| `GET /v1/teams/{teamId}/js-attendance` | 200, 400, 401, 403, 404, 503 | `EventPresenceHttpTest` (rows, people without a number, trainings without a place; not audited; viewer and another team's manager 403; from after to 400), `PrivateRoutesRevocationTest` |
| `GET /v1/teams/{teamId}/js-attendance.csv` | 200, 400, 401, 403, 404, 503 | `EventPresenceHttpTest` (exact NDS file with leader and participant rows, Swiss date and time, minutes, the place cleaned, a match as Wettkampf; audited once; cross-site 403), `PrivateRoutesRevocationTest` |
| `POST /v1/events/{eventId}/shifts` | 201, 400, 401, 403, 404, 503 | `EventShiftHttpTest` (CSRF, `MANAGE_TEAMS` for the team only, input bounds, window around the event, trimmed title), `PrivateRoutesRevocationTest`, Keycloak journey |
| `PUT /v1/shifts/{shiftId}` | 204, 400, 401, 403, 404, 409, 503 | `EventShiftHttpTest` (viewer 403, places below volunteers 409 and unchanged), `PrivateRoutesRevocationTest`, Keycloak journey |
| `DELETE /v1/shifts/{shiftId}` | 204, 401, 403, 404, 503 | `EventShiftHttpTest` (sign-ups go with the shift, shifts with the event), `PrivateRoutesRevocationTest` |
| `PUT /v1/shifts/{shiftId}/volunteers/{memberId}` | 204, 400, 401, 403, 409, 503 | `EventShiftHttpTest` (only oneself or one's minor in the team; two concurrent sign-ups for the last place, exactly one wins, other 409 `full`; again is 204; 409 `closed` after the start or for a cancelled event) |
| `DELETE /v1/shifts/{shiftId}/volunteers/{memberId}` | 204, 400, 401, 403, 409, 503 | `EventShiftHttpTest` (withdraw twice is 204, 409 after the start; leaving the team ends sign-ups) |
| `PUT /v1/me/profile/public-listing` | 204, 400, 401, 403, 412, 428, 503 | `MyProfileHttpTest` (CSRF, membership, boolean-only body, `If-Match` 428/412, hidden from and back on the public list, `PUBLIC_LISTING_CHANGED`), `PrivateRoutesRevocationTest`, Keycloak journey |
| `PUT /v1/members/{memberId}/public-listing` | 204, 400, 401, 403, 404, 412, 428, 503 | `MyProfileHttpTest` (`EDIT_MEMBERS` 403, stale 412, unknown 404, a minor hidden and listed again, audited per change), `PrivateRoutesRevocationTest` |
| `PUT /v1/members/{memberId}/js-person-number` | 204, 400, 401, 403, 404, 412, 428, 503 | `EventPresenceHttpTest` (only `EDIT_MEMBERS`; letters and numbers as JSON numbers 400; 428 without and 412 with an old version; shown in My profile; audited), `PrivateRoutesRevocationTest` |
| `PUT /v1/me/profile/contacts` | 204, 400, 401, 403, 412, 428, 503 | `MyProfileHttpTest` (Origin/CSRF and membership before body, only email/phone, adult rule 400, `If-Match` 428/412, stale after an editor's change, `SELF_CONTACTS_UPDATED`, other members untouched), `PrivateRoutesRevocationTest` |
| `GET /v1/me/events` | 200, 400, 401, 403, 503 | `TeamEventHttpTest` (own teams, children's teams with "for", team grants add nothing, ends when the child comes of age, range errors), `PrivateRoutesRevocationTest` |
| `GET /v1/teams/{teamId}/events` | 200, 400, 401, 403, 404, 503 | `TeamEventHttpTest` (viewer and manager of the team, `canManage`, exact keys in UTC, explicit range, other team 403, unknown team 404 club-wide), `PrivateRoutesRevocationTest` |
| `POST /v1/teams/{teamId}/events` | 201, 400, 401, 403, 404, 503 | `TeamEventHttpTest` (grant before body, input rules, weekly copies, other team 403, unknown team 403/404), `TeamEventsWeeklyTest` (local time across daylight saving, 52 limit), `PrivateRoutesRevocationTest`, `TeamEventsWeeklyTest` (1.40.0: every 2 and 4 weeks) |
| `PUT /v1/events/{eventId}` | 204, 400, 401, 403, 404, 503 | `TeamEventHttpTest` (replace and cancel, boolean as string 400, viewer 403, other team 403, unknown 404), `PrivateRoutesRevocationTest` |
| `DELETE /v1/events/{eventId}` | 204, 400, 401, 403, 404, 503 | `TeamEventHttpTest` (other team 403, delete then 404, revoked grant 403), `PrivateRoutesRevocationTest` |
| `GET /v1/events/{eventId}/attendance` | 200, 400, 401, 403, 404, 503 | `TeamEventHttpTest` (viewer and manager lists by display name only, minors as initial, no IDs, other team 403, unknown 404, counts), `PrivateRoutesRevocationTest` |
| `PUT /v1/events/{eventId}/attendance/{memberId}` | 204, 400, 401, 403, 409, 503 | `TeamEventHttpTest` (self and guardian for child; someone else's child, another team, unknown event, a manager for a player 403; lower case or a reason field 400; started or cancelled 409; changing an answer), `AnswerNotesHttpTest` (1.39.0: notes kept, replaced, removed, too long; 409 `answers_closed` after the deadline) |
| `DELETE /v1/events/{eventId}/attendance/{memberId}` | 204, 400, 401, 403, 409, 503 | `TeamEventHttpTest` (clear twice, after the start 409; leaving the team removes answers), `AnswerNotesHttpTest` (1.39.0: notes kept, replaced, removed, too long; 409 `answers_closed` after the deadline) |
| `GET /v1/fee-periods` | 200, 401, 403, 503 | `FeeHttpTest` (editor without `MANAGE_FEES` 403, totals, revoked grant 403), `PrivateRoutesRevocationTest` |
| `POST /v1/fee-periods` | 201, 400, 401, 403, 409, 503 | `FeeHttpTest` (grant before body, eight input errors, duplicate name ignoring case 409, every active member included and audited, archived member not), `PrivateRoutesRevocationTest` |
| `PATCH /v1/fee-periods/{periodId}` | 204, 400, 401, 403, 404, 409, 503 | `FeeHttpTest` (rename, unknown 404), `PrivateRoutesRevocationTest` |
| `DELETE /v1/fee-periods/{periodId}` | 204, 400, 401, 403, 404, 409, 503 | `FeeHttpTest` (wrong confirmation 409, deletion audits FEE_REMOVED per fee), `PrivateRoutesRevocationTest` |
| `GET /v1/fee-periods/{periodId}/fees` | 200, 400, 401, 403, 404, 503 | `FeeHttpTest` (filters all, open, paid, excluded; bad status 400; unknown 404), `QrBillHttpTest` (`reference` as creditor or QR reference, null when excluded or without payment details), `PrivateRoutesRevocationTest` |
| `PUT /v1/fee-periods/{periodId}/fees/{memberId}` | 204, 400, 401, 403, 404, 409, 503 | `FeeHttpTest` (pay, change, unpay, re-include each audited; archived without fee 409; unknown member or period 404; editor 403; revoked grant 403), `PrivateRoutesRevocationTest` |
| `DELETE /v1/fee-periods/{periodId}/fees/{memberId}` | 204, 400, 401, 403, 404, 503 | `FeeHttpTest` (exclude twice audits once), `PrivateRoutesRevocationTest` |
| `GET /v1/me/fees` | 200, 401, 403, 503 | `FeeHttpTest` (own fees, guardian's child's, exact keys, login without member record 403), `QrBillHttpTest` (`qrBill` only for an open fee above zero with payment details), `PrivateRoutesRevocationTest` |
| `GET /v1/me/fees/{periodId}/{memberId}/qr-bill` | 200, 400, 401, 403, 404, 409, 503 | `QrBillHttpTest` (own and child's fee 200 `%PDF` attachment `no-store`; someone else's, another guardian's child, treasurer, unknown member and non-member 403; bad ID, language or query 400; unknown period and excluded 404; paid and zero 409 `not_open`; no details 409 `no_payment_details`), `SwissPaymentsTest` (PDF in four languages, QR-IBAN), `PrivateRoutesRevocationTest`, Keycloak journey |
| `GET /v1/club/payment-details` | 200, 401, 403, 404, 503 | `QrBillHttpTest` (editor, member and stranger 403; none set 404; compact IBAN and `qrIban`; revoked grant 403), `PrivateRoutesRevocationTest` |
| `PUT /v1/club/payment-details` | 204, 400, 401, 403, 503 | `QrBillHttpTest` (grant, Origin and CSRF before body; bad checksum, foreign IBAN, field lengths, building number without street, other country, wrong types, missing and extra keys 400; optional street; replace), `SwissPaymentsTest` (IBAN, field limits), `PrivateRoutesRevocationTest`, Keycloak journey |
| `GET /v1/members/export.csv` | 200, 400, 401, 403, 503 | `MemberListHttpTest` (headers, BOM, German header, neutralized phone, archived members left out, MEMBER_LISTED per member, no grant / cross-site / bad language 403/400 without audit), `MemberCsvTest` (formula neutralization round trip), `PrivateRoutesRevocationTest` |
| `POST /v1/members/import` | 200, 400, 401, 403, 503 | `MemberListHttpTest` (CSRF and grant before the file, wrong type or mode or missing column 400, preview changes nothing, commit creates only OK rows with MEMBER_CREATED, a second commit creates nothing), `MemberCsvTest` (UTF-8 and Windows-1252, separators, quotes, four languages, strict dates, each problem, 1,000-row limit) |
| `GET /v1/me/announcements` | 200, 401, 403, 503 | `AnnouncementHttpTest` (club, own team, child's team, managed team; expired hidden; `canManage`; audiences; exact keys; login without member record 403), `PrivateRoutesRevocationTest` |
| `POST /v1/announcements` | 201, 400, 401, 403, 404, 503 | `AnnouncementHttpTest` (a right somewhere before the body, six input errors, coach club-wide or other team 403, board for a team 403, unknown team 404), `PrivateRoutesRevocationTest` |
| `PUT /v1/announcements/{announcementId}` | 204, 400, 401, 403, 404, 503 | `AnnouncementHttpTest` (coach on club post and board on team post 403, unknown 404, own team 204), `PrivateRoutesRevocationTest` |
| `DELETE /v1/announcements/{announcementId}` | 204, 400, 401, 403, 404, 503 | `AnnouncementHttpTest` (delete then 404, bad id 400, revoked grant 403), `PrivateRoutesRevocationTest` |
| `GET /v1/members/{memberId}/export` | 200, 400, 401, 403, 404, 503 | `MemberExportHttpTest` (editors only, attachment headers, content incl. guardianships, no login binding, audit), `PrivateRoutesRevocationTest`, `MemberExportTest` |
| `POST /v1/members/{memberId}/erase` | 204, 400, 401, 403, 503 | `MemberDetailsAndErasureHttpTest` (one generic 403 for every refusal), `PrivateRoutesRevocationTest`, `MemberErasureTest` |
Browser coverage of the same operations is in `dev/auth-e2e/club-*.spec.js` (real Keycloak and
Chromium, synthetic data): member, team, grant, role and club-setting workflows in four languages, real
database denial with grants removed, a team-scoped grant that shows one team, invalid-input handling and
accessibility structure (#25). The anonymous and authentication routes outside the 98 operations are covered
by `AuthAndPublicRoutesInputTest`, `SessionRoutesTest` and `BrowserLoginRoutesTest`.

## Known gaps in this evidence

- **Route-level 503.** The mapping from database faults to 503 is tested (`DatabaseErrorsTest`) and each
  route's code path is reviewed, but no test makes a real route answer 503 for every operation. A realistic
  fault injection is not built.
- **Runtime payload conformance.** Response bodies are asserted exactly only for member create, search,
  detail, the anonymous directory, the error envelope (#306) and the key sets of the 1.3.0 detail read. Other responses are checked for status and
  for absence of private fields, not validated against the OpenAPI schema. Adding a schema validator would add a
  dependency and needs an owner decision.
- **Statuses never produced by a test.** Where a documented status is only reachable through a race or a
  fault (for example some 409 and 503 cases), it is covered by service tests or reasoning, not by an HTTP test.
- **Not tested at all:** any proposed, unimplemented operation, including member reactivation over HTTP, which the
  [M2 draft](openapi-m2-draft.yaml) keeps separate.
