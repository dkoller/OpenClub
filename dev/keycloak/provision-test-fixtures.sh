#!/usr/bin/env bash
# Development/CI only. Never run against a production realm or real identities.
set -euo pipefail
: "${KEYCLOAK_DEV_ADMIN_PASSWORD:?Set the local Keycloak admin password}"
: "${OPENCLUB_E2E_CLIENT_SECRET:?Generate a unique test-only client secret}"
: "${OPENCLUB_E2E_USER_PASSWORD:?Generate a unique synthetic user password}"
[[ ${#OPENCLUB_E2E_CLIENT_SECRET} -ge 32 ]] || { echo "Test client secret too short" >&2; exit 1; }
[[ ${#OPENCLUB_E2E_USER_PASSWORD} -ge 32 ]] || { echo "Test user password too short" >&2; exit 1; }
command -v curl >/dev/null
command -v jq >/dev/null

# Deliberately fixed loopback endpoint, realm and identities: no arbitrary URL,
# realm, user ID or privilege inputs. Keep xtrace off (secrets in POST bodies).
base=http://127.0.0.1:8180
realm=openclub-dev
client=openclub-bff
username=openclub-ci-user
callback=http://localhost:5173/api/v1/auth/callback

admin_token="$(curl --fail --silent --show-error --max-time 10 \
    -X POST "$base/realms/master/protocol/openid-connect/token" \
    -d 'grant_type=password' -d 'client_id=admin-cli' \
    --data-urlencode 'username=openclub-local-admin' \
    --data-urlencode "password=$KEYCLOAK_DEV_ADMIN_PASSWORD" |
    jq -er '.access_token | select(type == "string" and length > 20)')"

admin_get() {
    curl --fail --silent --show-error --max-time 10 \
        -H "Authorization: Bearer $admin_token" "$1"
}
admin_write() {
    local method="$1" url="$2" json="$3"
    curl --fail --silent --show-error --max-time 10 -o /dev/null \
        -X "$method" -H "Authorization: Bearer $admin_token" \
        -H 'Content-Type: application/json' --data-binary "$json" "$url"
}

client_json="$(jq -nc --arg id "$client" --arg secret "$OPENCLUB_E2E_CLIENT_SECRET" \
    --arg callback "$callback" '{
        clientId: $id,
        enabled: true,
        protocol: "openid-connect",
        publicClient: false,
        clientAuthenticatorType: "client-secret",
        secret: $secret,
        standardFlowEnabled: true,
        implicitFlowEnabled: false,
        directAccessGrantsEnabled: false,
        serviceAccountsEnabled: false,
        redirectUris: [$callback],
        webOrigins: [],
        attributes: {"pkce.code.challenge.method": "S256"}
    }')"

client_list="$base/admin/realms/$realm/clients?clientId=$client"
client_id="$(admin_get "$client_list" |
    jq -r --arg id "$client" '.[] | select(.clientId == $id) | .id')"
if [[ -z "$client_id" ]]; then
    admin_write POST "$base/admin/realms/$realm/clients" "$client_json"
else
    [[ "$client_id" =~ ^[a-f0-9-]{36}$ ]] || { echo "Unexpected Keycloak client ID" >&2; exit 1; }
    admin_write PUT "$base/admin/realms/$realm/clients/$client_id" "$client_json"
fi
client_id="$(admin_get "$client_list" |
    jq -er --arg id "$client" '.[] | select(.clientId == $id) | .id')"
admin_get "$base/admin/realms/$realm/clients/$client_id" |
    jq -e --arg cb "$callback" '
        .clientId == "openclub-bff" and .publicClient == false and
        .standardFlowEnabled == true and .implicitFlowEnabled == false and
        .directAccessGrantsEnabled == false and .serviceAccountsEnabled == false and
        .redirectUris == [$cb] and .attributes["pkce.code.challenge.method"] == "S256"
    ' >/dev/null
admin_get "$base/admin/realms/$realm/clients/$client_id/client-secret" |
    jq -e --arg expected "$OPENCLUB_E2E_CLIENT_SECRET" '.value == $expected' >/dev/null

# Populate only synthetic profile fields required by Keycloak 26 to avoid
# VERIFY_PROFILE on first login. No real inbox, membership or privileges.
user_json="$(jq -nc --arg name "$username" '{
    username: $name,
    firstName: "OpenClub",
    lastName: "CI",
    email: "openclub-ci-user@example.invalid",
    enabled: true,
    emailVerified: true,
    requiredActions: []
}')"

# Keycloak stores group membership and role mappings separately from the user
# representation. Check *effective* roles (including composite roles and group
# inheritance) rather than trusting a user PUT to clear existing privileges.
assert_ordinary_user() {
    local id="$1" clients_json client_id client_name roles
    [[ "$id" =~ ^[a-f0-9-]{36}$ ]] || { echo "Unexpected Keycloak user ID" >&2; return 1; }

    if ! admin_get "$base/admin/realms/$realm/users/$id/groups?max=1000" |
        jq -e 'type == "array" and length == 0' >/dev/null; then
        echo "Synthetic user has unexpected group membership; refusing provisioning" >&2
        return 1
    fi

    if ! admin_get "$base/admin/realms/$realm/users/$id/role-mappings/realm/composite" |
        jq -e 'type == "array" and all(.[];
            .name == "default-roles-openclub-dev" or
            .name == "offline_access" or .name == "uma_authorization")' >/dev/null; then
        echo "Synthetic user has unexpected effective realm roles; refusing provisioning" >&2
        return 1
    fi

    # Only stock Keycloak account self-service roles may be inherited from
    # default-roles-openclub-dev. In particular realm-management must be empty.
    clients_json="$(admin_get "$base/admin/realms/$realm/clients?max=1000")"
    while IFS=$'\t' read -r client_id client_name; do
        [[ "$client_id" =~ ^[a-f0-9-]{36}$ ]] || { echo "Unexpected Keycloak client ID" >&2; return 1; }
        roles="$(admin_get "$base/admin/realms/$realm/users/$id/role-mappings/clients/$client_id/composite")"
        if ! jq -e --arg client "$client_name" '
            type == "array" and all(.[];
                $client == "account" and
                (.name == "manage-account" or .name == "manage-account-links" or
                 .name == "view-profile"))' <<< "$roles" >/dev/null; then
            echo "Synthetic user has unexpected effective client roles; refusing provisioning" >&2
            return 1
        fi
    done < <(jq -r '.[] | [.id, .clientId] | @tsv' <<< "$clients_json")
}

user_list="$base/admin/realms/$realm/users?username=$username&exact=true"
user_id="$(admin_get "$user_list" |
    jq -r --arg name "$username" '.[] | select(.username == $name) | .id')"
if [[ -z "$user_id" ]]; then
    admin_write POST "$base/admin/realms/$realm/users" "$user_json"
else
    [[ "$user_id" =~ ^[a-f0-9-]{36}$ ]] || { echo "Unexpected Keycloak user ID" >&2; exit 1; }
    assert_ordinary_user "$user_id"
    admin_write PUT "$base/admin/realms/$realm/users/$user_id" "$user_json"
fi
user_id="$(admin_get "$user_list" |
    jq -er --arg name "$username" '.[] | select(.username == $name) | .id')"
assert_ordinary_user "$user_id"
password_json="$(jq -nc --arg password "$OPENCLUB_E2E_USER_PASSWORD" '{
    type: "password", temporary: false, value: $password
}')"
admin_write PUT "$base/admin/realms/$realm/users/$user_id/reset-password" "$password_json"
admin_get "$base/admin/realms/$realm/users/$user_id" |
    jq -e '.username == "openclub-ci-user" and .enabled == true and
        .firstName == "OpenClub" and .lastName == "CI" and
        .email == "openclub-ci-user@example.invalid" and
        .emailVerified == true and (.requiredActions | length == 0)' >/dev/null

# An ephemeral test client/user have been provisioned. Print no tokens or secrets.
echo "Synthetic local Keycloak OIDC client and user verified"
