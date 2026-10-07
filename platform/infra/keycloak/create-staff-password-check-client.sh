#!/usr/bin/env bash
#
# ADR 0148: gives an already-running Keycloak the three realm facts staff
# multi-factor sign-in rests on, over the Admin API:
#
#   1. the OTP policy, declared (totp, 6 digits, 30 seconds, HmacSHA1, look-ahead
#      1) instead of inherited from whatever the Keycloak default is this year;
#   2. an authentication flow, `horecaos-password-only-direct-grant`, that
#      validates a username and a password and has NO OTP step;
#   3. `horecaos-staff-password-check`, a confidential, direct-grant-only client
#      bound to that flow, with no scopes beyond `basic` (which carries `sub`),
#      no offline_access and a one-second access token.
#
# Why the platform needs a second client at all: Keycloak's direct grant cannot
# say "this account needs a code". A missing code and a wrong password are the
# same invalid_grant, so a sign-in page could not ask for the code without
# either an oracle that tells an anonymous caller which accounts exist, or a
# question the platform can put to Keycloak safely: "is this password right?".
# That is this client, asked FIRST on every sign-in (ADR 0148, Decision 2). Its
# tokens never leave the adapter: StaffPasswordCheckClient reads the subject id
# out of the answer, ends the session it opened, and returns nothing that signs.
#
# The realm import file (infra/keycloak/realm/horecaos-realm.json) declares the
# OTP policy too, but it deliberately carries no authentication flows: Keycloak
# creates its built-in flows on import only when the file names none, and a file
# that named just one would leave the realm without a browser or reset flow. So
# the flow and the client come from here, on a fresh realm and an old one alike,
# and `make up` runs this after the stack is healthy.
#
# Idempotent, like its siblings: safe to run again. Local only: the loopback
# guard below refuses a remote Keycloak, because an SSH tunnel makes a real
# realm answer at "localhost" and this would pin its secret back to the
# development placeholder. For a real realm follow
# docs/runbooks/production-setup.md ("Staff second factor"), which does the same
# three things with a generated secret.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:-admin}"

CLIENT_ID="horecaos-staff-password-check"
FLOW_ALIAS="horecaos-password-only-direct-grant"
# The same fallback value compose.yaml's openbao-seed writes to
# horecaos/local/identity_admin/keycloak/staff-password-check-secret and
# application-local.yml supplies to the `environment` provider. All three must
# agree, or the platform's first question on every sign-in fails and every
# sign-in is refused.
CLIENT_SECRET="${HORECAOS_KEYCLOAK_STAFF_PASSWORD_CHECK_SECRET:-development-only-not-a-secret-staff-password-check}"

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
send() { # method path json
  curl -sf -o /dev/null -X "$1" "${KEYCLOAK_URL}/admin/realms/${REALM}$2" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" -d "$3"
}

# 1. The OTP policy. A realm PUT with only these fields changes only these.
send PUT "" '{"otpPolicyType":"totp","otpPolicyAlgorithm":"HmacSHA1","otpPolicyDigits":6,"otpPolicyPeriod":30,"otpPolicyLookAheadWindow":1,"otpPolicyInitialCounter":0}'
echo "==> Declared the OTP policy of realm ${REALM}" >&2

# 2. The password-only flow: username and password, both required, nothing else.
flow_id() {
  api "${KEYCLOAK_URL}/admin/realms/${REALM}/authentication/flows" \
    | python3 -c "import sys,json; print(next((f['id'] for f in json.load(sys.stdin) if f['alias']=='${FLOW_ALIAS}'), ''))"
}
FLOW_ID="$(flow_id)"
if [ -z "${FLOW_ID}" ]; then
  send POST "/authentication/flows" "{\"alias\":\"${FLOW_ALIAS}\",\"description\":\"ADR 0148: validates a username and a password and nothing else. Bound only to horecaos-staff-password-check.\",\"providerId\":\"basic-flow\",\"topLevel\":true,\"builtIn\":false}"
  FLOW_ID="$(flow_id)"
  for provider in direct-grant-validate-username direct-grant-validate-password; do
    send POST "/authentication/flows/${FLOW_ALIAS}/executions/execution" "{\"provider\":\"${provider}\"}"
  done
  echo "==> Created flow ${FLOW_ALIAS}" >&2
fi
# Every execution REQUIRED, every run: a flow whose password step was left
# DISABLED would verify nothing and answer success.
api "${KEYCLOAK_URL}/admin/realms/${REALM}/authentication/flows/${FLOW_ALIAS}/executions" \
  | python3 -c "
import sys, json
for execution in json.load(sys.stdin):
    execution['requirement'] = 'REQUIRED'
    print(json.dumps(execution))
" | while IFS= read -r execution; do
    send PUT "/authentication/flows/${FLOW_ALIAS}/executions" "${execution}"
  done

# 3. The client.
payload="$(python3 -c "
import json
print(json.dumps({
    'clientId': '${CLIENT_ID}',
    'name': 'horecaos-staff-password-check',
    'description': 'ADR 0148: confidential direct-grant client the platform backend asks FIRST, on every staff sign-in, whether a password is right. No OTP step; its tokens never leave the adapter.',
    'enabled': True,
    'protocol': 'openid-connect',
    'publicClient': False,
    'standardFlowEnabled': False,
    'implicitFlowEnabled': False,
    'directAccessGrantsEnabled': True,
    'serviceAccountsEnabled': False,
    'secret': '${CLIENT_SECRET}',
    'defaultClientScopes': ['basic'],
    'optionalClientScopes': [],
    'authenticationFlowBindingOverrides': {'direct_grant': '${FLOW_ID}'},
    'attributes': {
        'access.token.lifespan': '1',
        'client.session.idle.timeout': '60',
        'client.session.max.lifespan': '60',
    },
}))")"

existing="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")"
if [ -n "${existing}" ]; then
  send PUT "/clients/${existing}" "${payload}"
  client_uuid="${existing}"
  echo "==> Updated ${CLIENT_ID} in realm ${REALM}" >&2
else
  send POST "/clients" "${payload}"
  client_uuid="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")"
  echo "==> Created ${CLIENT_ID} in realm ${REALM}" >&2
fi

# Keycloak honours defaultClientScopes on CREATE and silently ignores them on a
# full-representation PUT (the same note create-staff-login-client.sh makes), so
# the scope set is reconciled by the per-scope endpoints on every run: `basic`
# only. No `organization`, no `offline_access`, nothing that could make the
# token worth keeping.
for kind in default optional; do
  api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/${kind}-client-scopes" \
    | python3 -c "
import sys, json
for scope in json.load(sys.stdin):
    if '${kind}' == 'optional' or scope['name'] != 'basic':
        print(scope['id'])
" | while IFS= read -r scope_id; do
      curl -sf -o /dev/null -X DELETE \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/${kind}-client-scopes/${scope_id}" \
        -H "Authorization: Bearer ${TOKEN}"
    done
done
basic_id="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/client-scopes" \
  | python3 -c "import sys,json; print(next((s['id'] for s in json.load(sys.stdin) if s['name']=='basic'), ''))")"
[ -n "${basic_id}" ] || { echo "!! client scope 'basic' does not exist in realm ${REALM}" >&2; exit 1; }
curl -sf -o /dev/null -X PUT \
  "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/default-client-scopes/${basic_id}" \
  -H "Authorization: Bearer ${TOKEN}"

# Confirms the secret actually took, for the reason create-staff-login-client.sh
# gives: a mismatch would otherwise surface as every sign-in refused.
secret="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/client-secret" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('value',''))")"
if [ "${secret}" != "${CLIENT_SECRET}" ]; then
  echo "!! ${CLIENT_ID}'s secret in Keycloak does not match the one the platform will resolve." >&2
  exit 1
fi
echo "==> ${CLIENT_ID} is ready: password-only flow, basic scope, one-second access token" >&2
