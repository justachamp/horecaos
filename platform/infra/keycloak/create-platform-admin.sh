#!/usr/bin/env bash
#
# Creates (or updates the password of) a realm user holding the global
# horecaos-api `platform-admin` client role, for local development and
# proving runs (docs/runbooks/proving-run.md).
#
# horecaos-realm.json ships no realm users at all — ADR 0003 makes
# platform-admin a Keycloak-issued role and deliberately leaves who holds it
# to each deployment. On a laptop that means nobody can ever reach the
# platform-admin-only bootstrap in JdbcAuthorizationService (ADR 0025's
# IAM_GRANT_MANAGE bypass) without first creating a user by hand through the
# admin console. This does the same thing through the admin API, so it is a
# repeatable command instead of a click sequence nobody wrote down.
#
# This script only ever touches Keycloak. It used to be paired with a direct
# `INSERT INTO iam.grants` (in this script's own earlier form, and still in
# tools/proving-run before this same change) to give the new user their first
# PLATFORM-scope grant — the honest workaround for there being no HTTP path
# to one. That grant is now created by PlatformAdminBootstrapReconciler, an
# ApplicationRunner that reconciles horecaos.iam.bootstrap-platform-admins
# (a list of Keycloak subject ids) on every startup. This script's part of
# that is printing the one thing the reconciler needs and Keycloak is the
# only source of: the subject id. Put it under that configuration key (an env
# var locally: HORECAOS_IAM_BOOTSTRAP_PLATFORM_ADMINS) and restart the API —
# no SQL, ever.
#
# Idempotent: safe to run repeatedly; a second run only resets the password
# and re-asserts the role.
#
# Usage:
#   HORECAOS_PLATFORM_ADMIN_PASSWORD=... infra/keycloak/create-platform-admin.sh [username]
#
# Prints the created/reused user's Keycloak subject id on stdout (and only
# that, so `subject="$(... )"` captures it cleanly); everything else goes to
# stderr.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:-admin}"
CLIENT_ID="horecaos-api"

# Deliberately not named USERNAME: that identifier is exported read-only by
# the calling shell on some systems (it names *this* machine's login), so an
# in-script `USERNAME=...` assignment silently loses to the pre-set value
# instead of erroring — the exact failure mode that made this script
# create-and-authenticate the wrong principal the first time it was written.
NEW_USERNAME="${1:-${HORECAOS_PLATFORM_ADMIN_USERNAME:-proving-run-platform-admin}}"
PASSWORD="${HORECAOS_PLATFORM_ADMIN_PASSWORD:?Set HORECAOS_PLATFORM_ADMIN_PASSWORD (a local-only value; never reused outside a laptop)}"

case "${KEYCLOAK_URL}" in
  http://localhost:*|http://127.0.0.1:*|http://[::1]:*) ;;
  *) echo "!! ${KEYCLOAK_URL} is not a loopback address. This user is for local development only." >&2
     exit 1 ;;
esac

token() {
  curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" -d "username=${ADMIN_USER}" \
    -d "password=${ADMIN_PASSWORD}" -d "grant_type=password" \
    | jq -r .access_token
}

TOKEN="$(token)"
api() { curl -sf -H "Authorization: Bearer ${TOKEN}" "$@"; }

user_id="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${NEW_USERNAME}&exact=true" | jq -r '.[0].id // empty')"

if [ -z "${user_id}" ]; then
  # username, email, firstName AND lastName all have to be non-blank together,
  # or Keycloak 26's declarative User Profile silently treats the account as
  # not fully set up and the direct grant below fails with the misleading
  # "invalid_grant: Account is not fully set up" — no exception, no realm
  # setting names it. A real owner hitting this in a browser just sees a
  # "complete your profile" form; a script has no browser to fill it in with,
  # so this has to be supplied at creation. See docs/runbooks/proving-run.md.
  curl -sf -o /dev/null -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "$(jq -n --arg u "${NEW_USERNAME}" --arg e "${NEW_USERNAME}@local.horecaos.dev" \
          '{username:$u, email:$e, firstName:"Proving Run", lastName:"Platform Admin", enabled:true, emailVerified:true}')"
  user_id="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${NEW_USERNAME}&exact=true" | jq -r '.[0].id')"
  echo "==> Created realm user ${NEW_USERNAME} (${user_id})" >&2
else
  echo "==> Reusing realm user ${NEW_USERNAME} (${user_id})" >&2
fi

# Reset the password every run rather than only on creation: a proving run
# after `docker compose down -v` recreates the realm but this script may be
# invoked against a realm where the user already exists from a prior partial
# run, and a stale or unknown password would silently break the next step.
curl -sf -o /dev/null -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/reset-password" \
  -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d "$(jq -n --arg p "${PASSWORD}" '{type:"password", value:$p, temporary:false}')"

client_uuid="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" | jq -r '.[0].id')"

role="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/roles/platform-admin")"
role_id="$(echo "${role}" | jq -r .id)"

already_has="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/role-mappings/clients/${client_uuid}" \
  | jq -r 'any(.[]; .name == "platform-admin")')"

if [ "${already_has}" = "false" ]; then
  curl -sf -o /dev/null -X POST \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/role-mappings/clients/${client_uuid}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "$(jq -n --arg id "${role_id}" '[{id:$id, name:"platform-admin"}]')"
  echo "==> Granted realm-wide horecaos-api platform-admin role to ${NEW_USERNAME}" >&2
else
  echo "==> ${NEW_USERNAME} already holds the platform-admin client role" >&2
fi

echo "==> Subject: ${user_id}" >&2
echo "==> Put it under horecaos.iam.bootstrap-platform-admins" \
     "(HORECAOS_IAM_BOOTSTRAP_PLATFORM_ADMINS locally) and (re)start the API;" \
     "PlatformAdminBootstrapReconciler grants it PLATFORM-scope platform-admin" \
     "on startup, no SQL needed." >&2
echo "${user_id}"
