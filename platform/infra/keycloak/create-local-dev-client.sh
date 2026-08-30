#!/usr/bin/env bash
#
# A confidential, password-grant-enabled Keycloak client for driving the API
# non-interactively from a script (proving runs, smoke tests, CI) — the same
# purpose docs/runbooks/proving-run.md's tools/proving-run needs and nothing
# horecaos-realm.json ships out of the box:
#
#   - horecaos-api is bearerOnly with directAccessGrantsEnabled=false: it is
#     the resource server, not a client anybody signs in through.
#   - horecaos-local-web (create-local-web-client.sh) is a *public* PKCE
#     client for a browser. A public client has no secret, and the Resource
#     Owner Password Credentials grant needs a client that can authenticate
#     itself — public clients cannot use it safely, and Keycloak simply
#     refuses direct grants on `publicClient: true` clients issued a secret.
#
# So this is a third, purpose-built client: confidential (has a secret),
# directAccessGrantsEnabled (password grant), no browser redirect, no service
# account of its own — it authenticates *real* realm users (platform-admin,
# a tenant owner created by onboarding) by username+password from a script,
# which is exactly what an interactive Swagger UI **Authorize** login does for
# a human. It is local-dev-only in the same way create-local-web-client.sh is,
# and for the same reason: it refuses to run against anything but a loopback
# Keycloak, and a public confidential secret is worthless the moment
# `docker compose down -v` removes the realm's own generated one.
#
# Idempotent: safe to run repeatedly.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:-admin}"

CLIENT_ID="horecaos-local-dev"

# Same loopback guard as create-local-web-client.sh: an SSH tunnel makes a real
# realm's admin API look like localhost, so the check is on the URL, not on a
# comment asking nicely.
case "${KEYCLOAK_URL}" in
  http://localhost:*|http://127.0.0.1:*|http://[::1]:*) ;;
  *) echo "!! ${KEYCLOAK_URL} is not a loopback address. This client is for local development only." >&2
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

payload="$(python3 -c "
import json
print(json.dumps({
    'clientId': '${CLIENT_ID}',
    'name': 'HorecaOS Local Dev (password grant)',
    'description': 'Local development and proving-run only. Created by infra/keycloak/create-local-dev-client.sh; never import this into a real realm.',
    'enabled': True,
    'protocol': 'openid-connect',
    'publicClient': False,
    'standardFlowEnabled': False,
    'implicitFlowEnabled': False,
    'directAccessGrantsEnabled': True,
    'serviceAccountsEnabled': False,
    # Explicit, not incidental: the Admin API does NOT fall back to the
    # realm's defaultDefaultClientScopes when this key is simply absent from
    # a create payload — it creates the client with *no* default scopes at
    # all. The first version of this script omitted it and every token came
    # back with no 'sub', no 'realm_access', no 'resource_access' — a token
    # that decodes but authorizes nothing and identifies nobody, which is a
    # much harder failure to notice than an outright 401. Named here exactly
    # like infra/keycloak/realm/horecaos-realm.json's own
    # defaultDefaultClientScopes so behaviour matches every other client.
    'defaultClientScopes': ['basic', 'profile', 'email', 'roles', 'web-origins', 'acr'],
    'optionalClientScopes': ['organization'],
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

# The client representation's own `defaultClientScopes`/`optionalClientScopes`
# fields (set in the create/update payload above) are honoured by Keycloak on
# CREATE but silently ignored by a full-representation PUT on an existing
# client — there is no error, no warning, just a client whose token comes
# back with no `sub`, no `realm_access`, no `resource_access` the first time
# this script runs against an already-existing client (every run after the
# first, since this script is meant to be idempotent). The only way that is
# respected on every run is the dedicated per-scope endpoint below, called
# for every default/optional scope every time — cheap, and a PUT here is
# already idempotent.
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

secret="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/client-secret" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['value'])")"

# The secret is the one piece of output a caller needs; everything else above
# is progress noise on stderr so `CLIENT_SECRET=$(...)` composes cleanly.
echo "${secret}"
