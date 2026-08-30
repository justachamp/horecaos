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

# `docker compose up -d` returns as soon as the container starts, not once
# Keycloak has finished `--import-realm` and is answering admin requests — the
# keycloak service declares no healthcheck for another service to wait on.
# Called right after `docker compose up`, the first token request would race a
# server that is still importing the realm and fail with a connection refusal
# that has nothing to do with credentials. Retry briefly rather than making
# every `make up` racy, and say plainly what is being waited for so a genuine
# outage does not read as a silent hang.
WAIT_ATTEMPTS="${HORECAOS_KEYCLOAK_WAIT_ATTEMPTS:-30}"
WAIT_INTERVAL_SECONDS="${HORECAOS_KEYCLOAK_WAIT_INTERVAL_SECONDS:-2}"

wait_for_keycloak() {
  local attempt=1
  while [ "${attempt}" -le "${WAIT_ATTEMPTS}" ]; do
    if curl -sf -o /dev/null "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/certs"; then
      return 0
    fi
    echo "==> Waiting for Keycloak at ${KEYCLOAK_URL} (attempt ${attempt}/${WAIT_ATTEMPTS})..." >&2
    sleep "${WAIT_INTERVAL_SECONDS}"
    attempt=$((attempt + 1))
  done
  echo "!! Keycloak at ${KEYCLOAK_URL} did not become reachable after $((WAIT_ATTEMPTS * WAIT_INTERVAL_SECONDS))s.
   Check it is running and importing the realm: docker compose ps keycloak && docker compose logs keycloak.
   Increase the wait with HORECAOS_KEYCLOAK_WAIT_ATTEMPTS if the machine is just slow." >&2
  exit 1
}

wait_for_keycloak
TOKEN="$(token)"
api() { curl -sf -H "Authorization: Bearer ${TOKEN}" "$@"; }

# The master realm answering (waited for above) does not guarantee the
# `--import-realm` pass for the *target* realm has finished registering its
# clients yet. A short retry here absorbs that gap instead of failing on
# a still-importing realm right after Keycloak became reachable.
realm_management_client_id() {
  local attempt=1
  local found
  while [ "${attempt}" -le "${WAIT_ATTEMPTS}" ]; do
    found="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=realm-management" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else '')" 2>/dev/null || true)"
    if [ -n "${found}" ]; then
      echo "${found}"
      return 0
    fi
    echo "==> Waiting for realm ${REALM} to finish importing (attempt ${attempt}/${WAIT_ATTEMPTS})..." >&2
    sleep "${WAIT_INTERVAL_SECONDS}"
    attempt=$((attempt + 1))
  done
  echo "!! Realm ${REALM} never appeared with a realm-management client after
   $((WAIT_ATTEMPTS * WAIT_INTERVAL_SECONDS))s. Check the import succeeded:
   docker compose logs keycloak | grep -i import" >&2
  exit 1
}

RM_ID="$(realm_management_client_id)"

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
