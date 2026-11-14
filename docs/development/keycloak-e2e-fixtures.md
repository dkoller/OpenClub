# Synthetic Keycloak OIDC fixtures (local/CI only)

**Scope:** Optional fixture provisioning for [R05/#14](https://github.com/dkoller/OpenClub/issues/14), not a normal startup step, default client or production realm. Main [Keycloak development runbook](keycloak-local.md).

`dev/keycloak/provision-test-fixtures.sh` targets **only** `http://127.0.0.1:8180` and the `openclub-dev` realm. It creates/updates a confidential `openclub-bff` OIDC client with an exact `http://localhost:5173/api/v1/auth/callback` redirect URI, authorization-code standard flow enabled, **PKCE S256 required**, direct access grants/implicit/service accounts disabled, and no web origins. It creates one synthetic ordinary Keycloak user `openclub-ci-user` without email, roles or club memberships. A Keycloak user is **not** an OpenClub club administrator.

On repeat runs, provisioning rejects a synthetic user with any group membership or unexpected effective realm/client roles (including inherited and composite permissions) **before** updating the user or resetting the password. Stock Keycloak default realm roles and account self-service roles are allowed; privilege escalation is not automatically repaired. Remove the unexpected grants explicitly or recreate the disposable realm before rerunning. Compose CI grants `realm-management/realm-admin`, verifies that provisioning rejects the privileged user, then revokes the role and verifies recovery.

The client secret and test password are **required at runtime**; none are committed, defaulted or echoed. The script requests an ephemeral local master-realm admin API token using the admin password already configured for the dev Keycloak container. The script can be re-run to update the same synthetic client and user. Do not run with `bash -x` or against an identity provider holding real data. This fixed-name fixture is for disposable local instances, **not** shared or production use.

```sh
# Start the isolated Keycloak dev realm first; keep the existing admin password
# in your shell (as required by compose.auth.dev.yaml).
docker compose -f compose.yaml -f compose.auth.dev.yaml up -d keycloak

# Generate and retain these only for the current local synthetic test run:
export OPENCLUB_E2E_CLIENT_SECRET="$(openssl rand -hex 32)"
export OPENCLUB_E2E_USER_PASSWORD="$(openssl rand -hex 32)"
bash dev/keycloak/provision-test-fixtures.sh
```

The CI Compose job starts Keycloak with an explicitly synthetic admin credential, generates **unique** fixture secrets with `openssl rand` and calls the script. It verifies the confidential client, restricted redirect, grant controls and secret using the Keycloak Admin REST API, plus the enabled synthetic user. Existing issuer/JWKS/API-backchannel checks remain. The normal `docker compose up` does **not** provision accounts.

This proves **reproducible test fixtures**, not browser login, a real HTTP callback, valid OIDC token exchange, or session/CSRF behavior. Those are follow-up reviewed PRs; do not treat this as an E2E login pass. Once callback routes exist, use the synthetic user only for deterministic login/logout and negative tests. Avoid persistent fixture credentials and dispose of the local dev Keycloak volume explicitly if it is no longer needed.
