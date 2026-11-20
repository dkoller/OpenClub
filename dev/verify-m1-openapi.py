#!/usr/bin/env python3
"""Guard implemented synthetic M1 Ktor routes against accidental OpenAPI drift.

This is a static route/contract check, not runtime behavior, contract approval,
private-field authorization, or a replacement for PostgreSQL/browser tests.
"""
from pathlib import Path
import re
import sys

import yaml

ROOT = Path(__file__).resolve().parents[1]
API = Path("apps/api/src/main/kotlin/org/openclub/api")

# Only files serving the approved synthetic M1 administration surface.
# Auth/session routes are independently documented and tested.
ROUTE_SOURCES = {
    "Main.kt": None,
    "club/SingleClubRoutes.kt": "installSingleClubRoutes",
    "club/ClubNameRoutes.kt": "installClubNameRoutes",
    "club/ClubPlayerPolicyRoutes.kt": "installClubPlayerPolicyRoutes",
    "members/MemberHttpRoutes.kt": "installMemberHttpRoutes",
    "members/MemberRoleHttpRoutes.kt": "installMemberRoleHttpRoutes",
    "members/MemberInvitationHttpRoutes.kt": "installMemberInvitationHttpRoutes",
    "members/MemberErasureHttpRoute.kt": "installMemberErasureHttpRoute",
    "members/MemberDetailsHttpRoute.kt": "installMemberDetailsHttpRoute",
    "members/GuardianHttpRoutes.kt": "installGuardianHttpRoutes",
    "members/MyProfileHttpRoutes.kt": "installMyProfileHttpRoutes",
    "teams/TeamEventHttpRoutes.kt": "installTeamEventHttpRoutes",
    "teams/EventShiftHttpRoutes.kt": "installEventShiftHttpRoutes",
    "teams/AbsenceHttpRoutes.kt": "installAbsenceHttpRoutes",
    "teams/RideHttpRoutes.kt": "installRideHttpRoutes",
    "join/JoinRequestHttpRoutes.kt": "installJoinRequestHttpRoutes",
    "calendar/CalendarHttpRoutes.kt": "installCalendarHttpRoutes",
    "notifications/NotificationHttpRoutes.kt": "installNotificationHttpRoutes",
    "fees/FeeHttpRoutes.kt": "installFeeHttpRoutes",
    "members/MemberListHttpRoutes.kt": "installMemberListHttpRoutes",
    "announcements/AnnouncementHttpRoutes.kt": "installAnnouncementHttpRoutes",
    "members/GrantHttpRoutes.kt": "installGrantHttpRoutes",
    "teams/TeamHttpRoutes.kt": "installTeamHttpRoutes",
    "teams/TeamMemberHttpRoutes.kt": "installTeamMemberHttpRoutes",
}

# A reviewed baseline makes both new unreviewed exposure and silently removed
# routes explicit. Changing this list is a contract-review action and needs an
# owner-approved contract change (PUT and reactivation: ADR-0008 addendum 5).
EXPECTED = set("""
GET /v1/public/members
GET /v1/club
PATCH /v1/club
PATCH /v1/club/player-gender-policy
PATCH /v1/club/adult-age-policy
GET /v1/members
POST /v1/members
GET /v1/members/{memberId}
PATCH /v1/members/{memberId}
POST /v1/members/{memberId}/archive
GET /v1/members/{memberId}/role
PATCH /v1/members/{memberId}/role
GET /v1/teams
POST /v1/teams
PATCH /v1/teams/{teamId}
DELETE /v1/teams/{teamId}
GET /v1/teams/{teamId}/members
POST /v1/teams/{teamId}/members/{memberId}
DELETE /v1/teams/{teamId}/members/{memberId}
GET /v1/grants
POST /v1/grants
DELETE /v1/grants
POST /v1/members/{memberId}/invitation
DELETE /v1/members/{memberId}/invitation
POST /v1/invitations/accept
DELETE /v1/members/{memberId}/account-link
GET /v1/members/{memberId}/details
POST /v1/members/{memberId}/erase
PUT /v1/members/{memberId}
POST /v1/members/{memberId}/reactivate
GET /v1/members/archived
GET /v1/members/{memberId}/guardians
POST /v1/members/{memberId}/guardians
DELETE /v1/members/{memberId}/guardians/{guardianMemberId}
GET /v1/me/dependents
GET /v1/me/dependents/{memberId}
PUT /v1/me/dependents/{memberId}/contacts
GET /v1/me/profile
PUT /v1/me/profile/contacts
PUT /v1/me/profile/public-listing
GET /v1/me/calendar-feed
GET /v1/me/notifications
GET /v1/me/absences
GET /v1/events/{eventId}/rides
GET /v1/public/join-requests
POST /v1/public/join-requests
GET /v1/club/join-requests/settings
PUT /v1/club/join-requests/settings
GET /v1/join-requests
POST /v1/join-requests/{requestId}/accept
DELETE /v1/join-requests/{requestId}
POST /v1/events/{eventId}/rides
PUT /v1/rides/{rideId}
DELETE /v1/rides/{rideId}
PUT /v1/rides/{rideId}/passengers/{memberId}
DELETE /v1/rides/{rideId}/passengers/{memberId}
POST /v1/me/absences
DELETE /v1/absences/{absenceId}
PUT /v1/me/notifications
POST /v1/me/calendar-feed
DELETE /v1/me/calendar-feed
GET /v1/calendar/{secret}
GET /v1/events/{eventId}/shifts
GET /v1/teams/{teamId}/attendance-statistics
POST /v1/events/{eventId}/shifts
PUT /v1/shifts/{shiftId}
DELETE /v1/shifts/{shiftId}
PUT /v1/shifts/{shiftId}/volunteers/{memberId}
DELETE /v1/shifts/{shiftId}/volunteers/{memberId}
PUT /v1/members/{memberId}/public-listing
GET /v1/me/events
GET /v1/teams/{teamId}/events
POST /v1/teams/{teamId}/events
PUT /v1/events/{eventId}
DELETE /v1/events/{eventId}
GET /v1/events/{eventId}/attendance
PUT /v1/events/{eventId}/attendance/{memberId}
DELETE /v1/events/{eventId}/attendance/{memberId}
GET /v1/fee-periods
POST /v1/fee-periods
PATCH /v1/fee-periods/{periodId}
DELETE /v1/fee-periods/{periodId}
GET /v1/fee-periods/{periodId}/fees
PUT /v1/fee-periods/{periodId}/fees/{memberId}
DELETE /v1/fee-periods/{periodId}/fees/{memberId}
GET /v1/me/fees
GET /v1/members/export.csv
POST /v1/members/import
GET /v1/me/announcements
POST /v1/announcements
PUT /v1/announcements/{announcementId}
DELETE /v1/announcements/{announcementId}
GET /v1/members/{memberId}/export
""".strip().splitlines())

# A single direct-route pattern avoids interpreting JSON builder put() as an
# HTTP PUT. Member list/create use bare get/post within a Ktor route() block.
DIRECT_ROUTE = re.compile(r'^\s+(get|post|patch|delete|put)\("(/api/v1/[^"]+)"\)\s*\{')
MEMBER_ROOT = re.compile(r'^            (get|post)\s*\{\s*$')
MEMBER_CHILD = re.compile(r'^            (get|post|patch|delete|put)\("(/[^"]+)"\)\s*\{')

def fail(message):
    raise ValueError(message)


def implemented_routes():
    main = (ROOT / API / "Main.kt").read_text()
    observed = set()
    for source, installer in ROUTE_SOURCES.items():
        if installer and f"{installer}(" not in main:
            fail(f"Main.kt no longer registers {installer}")
        content = (ROOT / API / source).read_text()
        if source == "members/MemberHttpRoutes.kt" and 'route("/api/v1/members") {' not in content:
            fail("Ktor member route root changed")
        for line in content.splitlines():
            direct = DIRECT_ROUTE.match(line)
            if direct:
                method, path = direct.groups()
                observed.add(f"{method.upper()} {path.removeprefix('/api')}")
            elif source == "members/MemberHttpRoutes.kt":
                nested = MEMBER_ROOT.match(line)
                child = MEMBER_CHILD.match(line)
                if nested:
                    observed.add(f"{nested.group(1).upper()} /v1/members")
                elif child:
                    observed.add(f"{child.group(1).upper()} /v1/members{child.group(2)}")
    return observed


FROZEN_VERSION = "1.22.0"
# Empty since 1.6.0: the guardian operations were implemented (ADR-0008 addendum 8).
M2_PROPOSALS = ()
OPERATIONS = {"get", "post", "put", "patch", "delete"}


def operations(spec):
    return {f"{method.upper()} {path}" for path, item in spec["paths"].items()
            for method in item if method in OPERATIONS}


def verify_contract(spec):
    if spec.get("info", {}).get("version") != FROZEN_VERSION:
        fail(f"Frozen M1 contract must stay at version {FROZEN_VERSION}; "
             "a change needs a new owner-approved version")
    if spec.get("servers") != [{"url": "/api"}]:
        fail("M1 contract must remain mounted at /api")
    documented = operations(spec)
    if documented != EXPECTED:
        fail(f"Frozen contract operations differ from implemented routes: "
             f"undocumented={sorted(EXPECTED - documented)} "
             f"unimplemented={sorted(documented - EXPECTED)}")
    paths = spec["paths"]
    if not spec.get("security"):
        fail("M1 contract lost its default authenticated session requirement")

    for entry in sorted(EXPECTED):
        method, path = entry.split(" ", 1)
        operation = paths.get(path, {}).get(method.lower())
        if not operation:
            fail(f"Missing implemented operation in OpenAPI: {entry}")
        responses = operation.get("responses", {})
        success = "201" if entry in {"POST /v1/members", "POST /v1/teams", "POST /v1/members/{memberId}/invitation", "POST /v1/teams/{teamId}/events", "POST /v1/fee-periods", "POST /v1/announcements", "POST /v1/events/{eventId}/shifts", "POST /v1/me/calendar-feed", "POST /v1/me/absences", "POST /v1/events/{eventId}/rides", "POST /v1/join-requests/{requestId}/accept"} else (
            "200" if method == "GET" or entry == "POST /v1/members/import" else "204"
        )
        if entry == "POST /v1/public/join-requests":
            success = "202"
        if success not in responses or "503" not in responses:
            fail(f"Expected success/unavailable statuses missing: {entry}")
        if entry == "GET /v1/public/members":
            if operation.get("security") != []:
                fail("Public display names must explicitly override session security")
            continue
        # The public join-request form (1.22.0): no session; the POST is limited by Origin and rate (403/429).
        if entry in ("GET /v1/public/join-requests", "POST /v1/public/join-requests"):
            if operation.get("security") != []:
                fail(f"{entry} must explicitly override session security")
            if entry.startswith("POST") and not {"202", "403", "429"} <= responses.keys():
                fail("The public join-request POST must document 202, 403 and 429")
            continue
        # The calendar feed's secret path is its credential (1.17.0): no session, one 404 for every refusal.
        if entry == "GET /v1/calendar/{secret}":
            if operation.get("security") != [] or "404" not in responses or {"401", "403"} & responses.keys():
                fail("The calendar feed must be session-free with a single 404 refusal")
            continue
        # A guardian's own list answers an empty list to everyone else (never 403), so it reveals nothing.
        denial = {"401"} if entry == "GET /v1/me/dependents" else {"401", "403"}
        if operation.get("security") == [] or not denial <= responses.keys():
            fail(f"Private endpoint has incomplete authentication/denial contract: {entry}")
        if method not in {"POST", "PUT", "PATCH", "DELETE"}:
            continue
        parameters = paths[path].get("parameters", []) + operation.get("parameters", [])
        csrf = any(
            p.get("$ref") == "#/components/parameters/CsrfToken"
            or (p.get("name") == "X-CSRF-Token" and p.get("in") == "header" and p.get("required"))
            for p in parameters
        )
        if not csrf:
            fail(f"Mutating operation has no required CSRF header: {entry}")



def verify_m2_draft(draft):
    # These are *proposals*, not M1 API routes. Keep them visibly unimplemented
    # and outside the frozen contract (the operations check above).
    if "draft" not in str(draft.get("info", {}).get("version", "")):
        fail("M2 proposals must stay in an unversioned draft")
    documented = operations(draft)
    if documented != set(M2_PROPOSALS):
        fail(f"M2 draft operations differ from the reviewed proposal list: "
             f"unlisted={sorted(documented - set(M2_PROPOSALS))} missing={sorted(set(M2_PROPOSALS) - documented)}")
    ids = []
    for entry in M2_PROPOSALS:
        method, path = entry.split(" ", 1)
        operation = draft["paths"][path][method.lower()]
        if "NOT IMPLEMENTED" not in operation.get("summary", ""):
            fail(f"Proposed private-data route lost its not-implemented marker: {entry}")
        ids.append(operation.get("operationId"))
        if method == "GET":
            continue
        parameters = draft["paths"][path].get("parameters", []) + operation.get("parameters", [])
        if not any(p.get("$ref") == "#/components/parameters/CsrfToken" for p in parameters):
            fail(f"Proposed write has no required CSRF header: {entry}")
        if "503" not in operation.get("responses", {}) or "401" not in operation["responses"]:
            fail(f"Proposed write lacks the 401/503 contract: {entry}")
    if len(set(ids)) != len(ids) or None in ids:
        fail("M2 draft operations need unique operationIds")


def main():
    observed = implemented_routes()
    if observed != EXPECTED:
        fail(f"Ktor M1 route inventory differs: missing={sorted(EXPECTED - observed)} "
             f"unexpected={sorted(observed - EXPECTED)}")
    with (ROOT / "docs/contracts/openapi-v1.yaml").open() as contract:
        spec = yaml.safe_load(contract)
    verify_contract(spec)
    with (ROOT / "docs/contracts/openapi-m2-draft.yaml").open() as contract:
        verify_m2_draft(yaml.safe_load(contract))
    print(f"Verified {len(EXPECTED)} registered synthetic M1 operations against the frozen "
          f"OpenAPI {FROZEN_VERSION} contract (exact match) and the separate M2 proposal draft.")
    print("Not verified: runtime payloads, privacy decisions, real data, M2 approval or proposed operations.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError) as error:
        print(f"M1 contract drift: {error}", file=sys.stderr)
        sys.exit(1)
