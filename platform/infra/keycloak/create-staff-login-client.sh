#!/usr/bin/env bash
#
# ADR 0062: hydrates an already-running local Keycloak with
# horecaos-staff-login.
#
# `docker compose up`'s `keycloak` service runs `start-dev --import-realm`,
# which imports `infra/keycloak/realm/horecaos-realm.json` once, the first
# time the realm does not already exist in the container's database — see
# `assign-service-account-roles.sh`'s own note on this. A checkout with an
# already-running stack from before this client existed in that file will
# never pick it up from a file edit alone; only a fresh `docker compose down -v
# && make up` re-runs the import. This script is the alternative to that: it
# creates or updates the client live, over the Admin API, the same way
# `create-local-dev-client.sh` and `create-local-web-client.sh` already handle
# their own realm additions.
#
# Unlike those two, this client is NOT local-development-only — it is also in
# the checked-in realm file, so a fresh `docker compose up` and a production
# `kc.sh import --dir infra/keycloak/realm` both create it on their own. This
# script exists only to reconcile a realm that has already finished importing
# an older file. The loopback guard below is still worth keeping: an SSH
# tunnel makes a remote admin API answer at "localhost" too, and running this
# against a real realm by mistake would silently pin its secret back to the
# development placeholder.
#
# Idempotent: safe to run again, like its two siblings.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:-admin}"

CLIENT_ID="horecaos-staff-login"
# The same fallback horecaos-realm.json substitutes at import time, and the
# same value openbao-seed writes to
# horecaos/local/identity_admin/keycloak/staff-login-secret in compose.yaml.
# All three must agree, or the "environment"/OpenBao secret provider resolves
# a value Keycloak does not recognise and every sign-in fails with a refusal
# indistinguishable from a wrong password.
CLIENT_SECRET="${HORECAOS_KEYCLOAK_STAFF_LOGIN_SECRET:-development-only-not-a-secret-staff-login}"

case "${KEYCLOAK_URL}" in
  http://localhost:*|http://127.0.0.1:*|http://[::1]:*) ;;
  *) echo "!! ${KEYCLOAK_URL} is not a loopback address. This script only reconciles a local realm." >&2
     exit 1 ;;
esac

token() {
  curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" -d "username=${ADMIN_USER}" \
    -d "password=${ADMIN_PASSWORD}" -d "grant_type=password" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}

TOKEN="$(token)"
api() { curl -sf -H "Authorization: Bearer ${TOKEN}" "$@"; }

# Same shape as the realm file's own horecaos-staff-login entry -- see
# infra/keycloak/realm/horecaos-realm.json. A confidential, direct-grant-only
# client with no browser flow and no service account: the backend signs real
# staff users in with their own username and password, it does not act as
# itself.
payload="$(python3 -c "
import json
print(json.dumps({
    'clientId': '${CLIENT_ID}',
    'name': 'horecaos-staff-login',
    'description': 'ADR 0062: confidential direct-grant client the platform backend alone uses to exchange staff credentials with Keycloak.',
    'enabled': True,
    'protocol': 'openid-connect',
    'publicClient': False,
    'standardFlowEnabled': False,
    'implicitFlowEnabled': False,
    'directAccessGrantsEnabled': True,
    'serviceAccountsEnabled': False,
    'secret': '${CLIENT_SECRET}',
    'defaultClientScopes': ['basic', 'profile', 'email', 'roles', 'web-origins', 'acr'],
    'optionalClientScopes': ['organization', 'offline_access'],
    'protocolMappers': [{
        'name': 'horecaos-api-audience',
        'protocol': 'openid-connect',
        'protocolMapper': 'oidc-audience-mapper',
        'consentRequired': False,
        'config': {
            'included.client.audience': 'horecaos-api',
            'id.token.claim': 'false',
            'access.token.claim': 'true',
        },
    }],
}))")"

existing="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")"

if [ -n "${existing}" ]; then
  curl -sf -o /dev/null -X PUT \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${existing}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "${payload}"
  client_uuid="${existing}"
  echo "==> Updated ${CLIENT_ID} in realm ${REALM}" >&2
else
  curl -sf -o /dev/null -X POST \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "${payload}"
  client_uuid="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")"
  echo "==> Created ${CLIENT_ID} in realm ${REALM}" >&2
fi

# Same reason create-local-dev-client.sh calls this per-scope endpoint on
# every run rather than trusting the payload's own defaultClientScopes/
# optionalClientScopes fields: Keycloak honours those on CREATE but silently
# ignores them on a full-representation PUT to an existing client, which would
# otherwise make every run after the first mint a token with no organization
# claim and no offline_access scope to request.
assign_scope() {
  local endpoint="$1" scope_name="$2"
  local scope_id
  scope_id="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/client-scopes" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(next((s['id'] for s in d if s['name']=='${scope_name}'), ''))")"
  [ -n "${scope_id}" ] || { echo "!! client scope '${scope_name}' does not exist in realm ${REALM}" >&2; exit 1; }
  curl -sf -o /dev/null -X PUT \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/${endpoint}/${scope_id}" \
    -H "Authorization: Bearer ${TOKEN}"
}
for s in basic profile email roles web-origins acr; do assign_scope default-client-scopes "${s}"; done
assign_scope optional-client-scopes organization
assign_scope optional-client-scopes offline_access

# Confirms the secret actually took. The representation PUT/POST above sets it
# in the same call that creates or updates everything else, but a mismatch
# here would otherwise surface only as a sign-in failure indistinguishable
# from a wrong password -- worth catching here, loudly, instead.
secret="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/client-secret" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('value',''))")"
if [ "${secret}" != "${CLIENT_SECRET}" ]; then
  echo "!! ${CLIENT_ID}'s secret in Keycloak does not match CLIENT_SECRET. Sign-in will fail with" >&2
  echo "   a refusal indistinguishable from a wrong password until this is fixed." >&2
  exit 1
fi

echo "==> Done"
