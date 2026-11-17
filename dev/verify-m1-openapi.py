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
    "members/GrantHttpRoutes.kt": "installGrantHttpRoutes",
    "teams/TeamHttpRoutes.kt": "installTeamHttpRoutes",
    "teams/TeamMemberHttpRoutes.kt": "installTeamMemberHttpRoutes",
}

# A reviewed baseline makes both new unreviewed exposure and silently removed
# routes explicit. Changing this list is a contract-review action, not approval
# to expose the proposed private member PUT/reactivation endpoints.
EXPECTED = set("""
GET /v1/public/members
GET /v1/club
PATCH /v1/club
PATCH /v1/club/player-gender-policy
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
""".strip().splitlines())

# A single direct-route pattern avoids interpreting JSON builder put() as an
# HTTP PUT. Member list/create use bare get/post within a Ktor route() block.
DIRECT_ROUTE = re.compile(r'^\s+(get|post|patch|delete|put)\("(/api/v1/[^"]+)"\)\s*\{')
MEMBER_ROOT = re.compile(r'^            (get|post)\s*\{\s*


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


def verify_contract(spec):
    if spec.get("servers") != [{"url": "/api"}]:
        fail("M1 contract must remain mounted at /api")
    paths = spec["paths"]
    if not spec.get("security"):
        fail("M1 contract lost its default authenticated session requirement")

    for entry in sorted(EXPECTED):
        method, path = entry.split(" ", 1)
        operation = paths.get(path, {}).get(method.lower())
        if not operation:
            fail(f"Missing implemented operation in OpenAPI: {entry}")
        responses = operation.get("responses", {})
        success = "201" if entry in {"POST /v1/members", "POST /v1/teams"} else (
            "200" if method == "GET" else "204"
        )
        if success not in responses or "503" not in responses:
            fail(f"Expected success/unavailable statuses missing: {entry}")
        if entry == "GET /v1/public/members":
            if operation.get("security") != []:
                fail("Public display names must explicitly override session security")
            continue
        if operation.get("security") == [] or not {"401", "403"} <= responses.keys():
            fail(f"Private endpoint has incomplete authentication/denial contract: {entry}")
        if method not in {"POST", "PATCH", "DELETE"}:
            continue
        parameters = paths[path].get("parameters", []) + operation.get("parameters", [])
        csrf = any(
            p.get("$ref") == "#/components/parameters/CsrfToken"
            or (p.get("name") == "X-CSRF-Token" and p.get("in") == "header" and p.get("required"))
            for p in parameters
        )
        if not csrf:
            fail(f"Mutating operation has no required CSRF header: {entry}")

    # These are *proposals*, not M1 API routes. Keep them visibly unimplemented.
    for entry in ("PUT /v1/members/{memberId}", "POST /v1/members/{memberId}/reactivate"):
        method, path = entry.split(" ", 1)
        summary = paths[path][method.lower()].get("summary", "")
        if "NOT IMPLEMENTED" not in summary:
            fail(f"Proposed private-data route lost its not-implemented marker: {entry}")


def main():
    observed = implemented_routes()
    if observed != EXPECTED:
        fail(f"Ktor M1 route inventory differs: missing={sorted(EXPECTED - observed)} "
             f"unexpected={sorted(observed - EXPECTED)}")
    with (ROOT / "docs/contracts/openapi-draft.yaml").open() as contract:
        spec = yaml.safe_load(contract)
    verify_contract(spec)
    print(f"Verified {len(EXPECTED)} registered synthetic M1 operations against the working OpenAPI draft.")
    print("Not verified: runtime payloads, privacy decisions, real data, M2 approval or proposed operations.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError) as error:
        print(f"M1 contract drift: {error}", file=sys.stderr)
        sys.exit(1)
)
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
                if nested:
                    observed.add(f"{nested.group(1).upper()} /v1/members")
    return observed


def verify_contract(spec):
    if spec.get("servers") != [{"url": "/api"}]:
        fail("M1 contract must remain mounted at /api")
    paths = spec["paths"]
    if not spec.get("security"):
        fail("M1 contract lost its default authenticated session requirement")

    for entry in sorted(EXPECTED):
        method, path = entry.split(" ", 1)
        operation = paths.get(path, {}).get(method.lower())
        if not operation:
            fail(f"Missing implemented operation in OpenAPI: {entry}")
        responses = operation.get("responses", {})
        success = "201" if entry in {"POST /v1/members", "POST /v1/teams"} else (
            "200" if method == "GET" else "204"
        )
        if success not in responses or "503" not in responses:
            fail(f"Expected success/unavailable statuses missing: {entry}")
        if entry == "GET /v1/public/members":
            if operation.get("security") != []:
                fail("Public display names must explicitly override session security")
            continue
        if operation.get("security") == [] or not {"401", "403"} <= responses.keys():
            fail(f"Private endpoint has incomplete authentication/denial contract: {entry}")
        if method not in {"POST", "PATCH", "DELETE"}:
            continue
        parameters = paths[path].get("parameters", []) + operation.get("parameters", [])
        csrf = any(
            p.get("$ref") == "#/components/parameters/CsrfToken"
            or (p.get("name") == "X-CSRF-Token" and p.get("in") == "header" and p.get("required"))
            for p in parameters
        )
        if not csrf:
            fail(f"Mutating operation has no required CSRF header: {entry}")

    # These are *proposals*, not M1 API routes. Keep them visibly unimplemented.
    for entry in ("PUT /v1/members/{memberId}", "POST /v1/members/{memberId}/reactivate"):
        method, path = entry.split(" ", 1)
        summary = paths[path][method.lower()].get("summary", "")
        if "NOT IMPLEMENTED" not in summary:
            fail(f"Proposed private-data route lost its not-implemented marker: {entry}")


def main():
    observed = implemented_routes()
    if observed != EXPECTED:
        fail(f"Ktor M1 route inventory differs: missing={sorted(EXPECTED - observed)} "
             f"unexpected={sorted(observed - EXPECTED)}")
    with (ROOT / "docs/contracts/openapi-draft.yaml").open() as contract:
        spec = yaml.safe_load(contract)
    verify_contract(spec)
    print(f"Verified {len(EXPECTED)} registered synthetic M1 operations against the working OpenAPI draft.")
    print("Not verified: runtime payloads, privacy decisions, real data, M2 approval or proposed operations.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError) as error:
        print(f"M1 contract drift: {error}", file=sys.stderr)
        sys.exit(1)
