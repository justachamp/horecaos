#!/usr/bin/env bash
#
# ADR 0009 service-account role assignment.
#
# Idempotent: safe to run after every realm import. Fails loudly if a role is
# missing, because a service account that silently holds nothing produces
# confusing 403s at the worst possible moment.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:-admin}"

# Verified against Keycloak 26.7. Deliberately excludes manage-realm,
# realm-admin, manage-clients, and impersonation.
PROVISIONING_ROLES="manage-organizations,manage-users,view-users,query-users"
READER_ROLES="view-organizations,query-organizations,view-users,query-users"

# The placeholder the realm file falls back to when the environment does not
# supply a secret. Anything starting with this is a value that is in the
# repository, and `horecaos-provisioning` holds manage-users — so a repository
# secret on that client is realm-wide user administration for anyone with a
# checkout.
DEV_SECRET_PREFIX="development-only-not-a-secret"

# Set to 1 wherever the realm is not a laptop. The check below then refuses to
# report success while either service account is still on a value that came out
# of git. docs/runbooks/deploy.md sets it during the bootstrap.
REQUIRE_ROTATED="${HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS:-0}"
unrotated=0

token() {
  curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" -d "username=${ADMIN_USER}" \
    -d "password=${ADMIN_PASSWORD}" -d "grant_type=password" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}

TOKEN="$(token)"
api() { curl -sf -H "Authorization: Bearer ${TOKEN}" "$@"; }

RM_ID="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=realm-management" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")"

assign() {
  local client_id="$1" roles="$2"

  local cid
  cid="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${client_id}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")"
  [ -n "${cid}" ] || { echo "!! client ${client_id} not found; import the realm first" >&2; exit 1; }

  local sa
  sa="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${cid}/service-account-user" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")"

  local payload
  payload="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${RM_ID}/roles" | python3 -c "
import sys, json
wanted = set('${roles}'.split(','))
available = {r['name']: r for r in json.load(sys.stdin)}
missing = wanted - available.keys()
if missing:
    sys.stderr.write('missing realm-management roles: %s\n' % sorted(missing))
    sys.exit(1)
print(json.dumps([{'id': available[n]['id'], 'name': n} for n in sorted(wanted)]))")"

  curl -sf -o /dev/null -X POST \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${sa}/role-mappings/clients/${RM_ID}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "${payload}"

  echo "    ${client_id}: ${roles}"

  # Checked here because this is the one script that is run after every import,
  # already holds an admin token, and already knows which clients matter. A
  # secret that is still the import file's fallback is not a weak secret, it is a
  # published one — and the two shapes below are both published: the placeholder
  # default, and the raw `${VAR:default}` text when substitution did not happen
  # at all.
  local secret
  secret="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${cid}/client-secret" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('value',''))")"
  case "${secret}" in
    "${DEV_SECRET_PREFIX}"*|'${HORECAOS_KEYCLOAK'*)
      echo "!!  ${client_id} is still using the secret from the import file." >&2
      unrotated=1
      ;;
  esac
}

echo "==> Assigning ADR 0009 service-account roles in realm ${REALM}"
assign horecaos-provisioning "${PROVISIONING_ROLES}"
assign horecaos-identity-reader "${READER_ROLES}"

if [ "${unrotated}" -eq 1 ]; then
  if [ "${REQUIRE_ROTATED}" = "1" ]; then
    echo "!! Refusing to report success: a service account is on a secret that is in
   the repository, and horecaos-provisioning can create users with it. Rotate both,
   store the new values in OpenBao under identity_admin, and re-run.
   docs/runbooks/deploy.md, 'Rotate the service-account secrets'." >&2
    exit 1
  fi
  echo "    (expected on a local realm; set HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1 anywhere else)"
fi

echo "==> Done"
