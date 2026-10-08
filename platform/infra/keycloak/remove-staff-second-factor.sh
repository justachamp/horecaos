#!/usr/bin/env bash
#
# ADR 0148, Decision 5: the break-glass for the night no administrator can reset a
# second factor -- the last platform administrator is the one who lost a phone.
#
# It removes every OTP credential of one account and ends that account's sessions,
# over the Keycloak Admin API, from the host. The password is untouched, so the
# person signs in with it, is told to enrol again (the platform requires a factor of
# a platform account), and sets up a new authenticator.
#
# What it cannot do, and why you must do the second half by hand: it runs outside
# the platform, so the platform's audit trail does not see it. Write down who ran
# it, for whom, why and when, in the operator log (docs/runbooks/staff-second-factor.md),
# and tell the person. An ordinary reset -- by another administrator, from the
# product, with a second signature and an email -- is always preferable.
#
# Usage:
#   HORECAOS_KEYCLOAK_ADMIN_PASSWORD=... infra/keycloak/remove-staff-second-factor.sh <username-or-subject-id>
#
# Needs a Keycloak administrator able to manage users (the temporary admin of
# deploy/keycloak-stage2.sh is one); the password is read from the environment so it
# never appears in a process list or a shell history. Nothing but the count removed is
# printed: not the account's name, not a credential id, never a secret.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:?Set HORECAOS_KEYCLOAK_ADMIN_PASSWORD}"
TARGET="${1:?Usage: remove-staff-second-factor.sh <username-or-subject-id>}"
LOGIN_CLIENT="${HORECAOS_KEYCLOAK_STAFF_LOGIN_CLIENT:-horecaos-staff-login}"

token="$(curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d grant_type=password \
  --data-urlencode "username=${ADMIN_USER}" --data-urlencode "password=${ADMIN_PASSWORD}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")" \
  || { echo "!! The Keycloak administrator could not sign in." >&2; exit 1; }
H="Authorization: Bearer ${token}"
api() { curl -sf -H "${H}" "$@"; }

# A subject id is used as given; anything else is looked up by exact user name, so a
# prefix match can never pick somebody else (the same care KeycloakStaffAccounts takes).
if [[ "${TARGET}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  user_id="${TARGET}"
else
  user_id="$(api -G "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
    --data-urlencode "username=${TARGET}" --data-urlencode "exact=true" --data-urlencode "max=2" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if len(d)==1 else '')")"
fi
[ -n "${user_id}" ] || { echo "!! No single account matches that user name." >&2; exit 1; }

# Only credentials of type otp are ever deleted: Keycloak would delete a password
# credential just as readily from the same endpoint.
removed=0
while IFS= read -r credential_id; do
  [ -n "${credential_id}" ] || continue
  curl -sf -o /dev/null -X DELETE -H "${H}" \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/credentials/${credential_id}"
  removed=$((removed + 1))
done < <(api "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/credentials" \
  | python3 -c "import sys,json; [print(c['id']) for c in json.load(sys.stdin) if c.get('type')=='otp']")

# Two calls, as the platform's own reset makes: the sessions a person is in now, and the
# offline grants every device that stayed signed in holds (revoking the sign-in client's
# consent deletes them). A 404 on the second means there were none.
curl -sf -o /dev/null -X POST -H "${H}" "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/logout" || true
client_uuid="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${LOGIN_CLIENT}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")"
if [ -n "${client_uuid}" ]; then
  curl -s -o /dev/null -X DELETE -H "${H}" \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/consents/${LOGIN_CLIENT}" || true
fi

echo "==> Removed ${removed} authenticator(s) and ended the sessions of one account." >&2
echo "==> Now write it down: who, for whom, why, when. The platform's audit trail did not see this." >&2
