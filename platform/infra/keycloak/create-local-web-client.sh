#!/usr/bin/env bash
#
# The browser client for hand-testing against a local Keycloak.
#
# It used to live in the realm import file, which meant the production bootstrap
# — the same `kc.sh import --dir infra/keycloak/realm` — created a public client
# with `http://localhost:5173/*` in its redirect URIs on the production realm. A
# public client needs no secret, so its only defence is that list, and a
# localhost entry is one an attacker can satisfy on their own machine.
#
# So it is a script rather than a fixture, and the script refuses to run against
# anything that is not loopback. Production cannot receive this client by
# importing a directory, by copying a file, or by anyone running this by mistake
# through a tunnel to the real server.
#
# Idempotent: safe to run repeatedly, like assign-service-account-roles.sh.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:-admin}"

CLIENT_ID="horecaos-local-web"
REDIRECT_URI="${HORECAOS_LOCAL_WEB_REDIRECT_URI:-http://localhost:5173/*}"
WEB_ORIGIN="${REDIRECT_URI%/\*}"

# The guard, and the reason this file exists. A hostname check rather than a
# reminder in a comment: an SSH tunnel is exactly how an operator would reach the
# production admin API, and `-L 8081:keycloak:8080` makes production look like
# localhost. Loopback is necessary here, not sufficient — which is why the
# redirect URI is checked too, and why it is the thing that would actually be
# dangerous on a real realm.
case "${KEYCLOAK_URL}" in
  http://localhost:*|http://127.0.0.1:*|http://[::1]:*) ;;
  *) echo "!! ${KEYCLOAK_URL} is not a loopback address. This client is for local development only." >&2
     exit 1 ;;
esac
case "${REDIRECT_URI}" in
  http://localhost:*|http://127.0.0.1:*) ;;
  *) echo "!! ${REDIRECT_URI} is not a localhost redirect. Do not create this client for a real origin." >&2
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

# PKCE with S256 and no implicit flow, because a public client has no secret and
# the authorization code is the whole credential in transit.
payload="$(python3 -c "
import json, sys
print(json.dumps({
    'clientId': '${CLIENT_ID}',
    'name': 'HorecaOS Local Web Development',
    'description': 'Local development only. Created by infra/keycloak/create-local-web-client.sh; never import this into a real realm.',
    'enabled': True,
    'protocol': 'openid-connect',
    'publicClient': True,
    'standardFlowEnabled': True,
    'implicitFlowEnabled': False,
    'directAccessGrantsEnabled': False,
    'serviceAccountsEnabled': False,
    'redirectUris': ['${REDIRECT_URI}'],
    'webOrigins': ['${WEB_ORIGIN}'],
    'attributes': {'pkce.code.challenge.method': 'S256'},
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
  echo "==> Updated ${CLIENT_ID} in realm ${REALM} (${REDIRECT_URI})"
else
  curl -sf -o /dev/null -X POST \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "${payload}"
  echo "==> Created ${CLIENT_ID} in realm ${REALM} (${REDIRECT_URI})"
fi
