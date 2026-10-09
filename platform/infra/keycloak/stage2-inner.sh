#!/usr/bin/env bash
#
# Runs INSIDE the ops container, driven by deploy/keycloak-stage2.sh. Do not run
# it by hand. It reads five values from stdin, one per line, and holds no
# credential in this file: a rotation-scoped OpenBao token (never the root
# token), the administrator's email, their chosen password, and the temporary
# Keycloak admin's username and password.
#
# The email is personal data (ADR 0029) and this container's output passes
# through Docker's log driver, so it is never printed here -- not in a heading,
# not in an error. The driver names it on the operator's own terminal.
#
# Every step is safe to repeat. A run that stops part-way is fixed by running
# the driver again.
set -euo pipefail

IFS= read -r BAO_TOKEN
IFS= read -r ADMIN_EMAIL
IFS= read -r ADMIN_PASSWORD
IFS= read -r TMP_USER
IFS= read -r TMP_PASSWORD

K=http://keycloak:8080
REALM=horecaos
# The environment segment the driver read from the host's env file.
KV="${BAO_ADDR:?ops has no BAO_ADDR}/v1/horecaos/data/${HORECAOS_ENVIRONMENT:?the driver passes HORECAOS_ENVIRONMENT}/identity_admin/keycloak"

say() { printf '  %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

token="$(curl -sf -X POST "${K}/realms/master/protocol/openid-connect/token" \
    -d client_id=admin-cli -d grant_type=password \
    --data-urlencode "username=${TMP_USER}" --data-urlencode "password=${TMP_PASSWORD}" \
    | jq -r .access_token)" || die "The temporary Keycloak admin could not sign in."
[ -n "${token}" ] && [ "${token}" != null ] || die "Keycloak issued no admin token."
H="Authorization: Bearer ${token}"
client_uuid() { curl -sf -H "${H}" "${K}/admin/realms/${REALM}/clients?clientId=$1" | jq -r '.[0].id'; }


echo "== Staff second factor: the OTP policy and the password-only client (ADR 0148)"
# What infra/keycloak/create-staff-password-check-client.sh does on a laptop, for the
# real realm: the same three facts, with a secret nobody chose. The client is created
# here and its secret is rotated by the very next step, so the value it is created with
# never reaches OpenBao and never signs anything.
FLOW=horecaos-password-only-direct-grant
CLIENT=horecaos-staff-password-check
put_json() { curl -sf -o /dev/null -X "$1" -H "${H}" -H "Content-Type: application/json" "${K}/admin/realms/${REALM}$2" -d "$3"; }
put_json PUT "" '{"otpPolicyType":"totp","otpPolicyAlgorithm":"HmacSHA1","otpPolicyDigits":6,"otpPolicyPeriod":30,"otpPolicyLookAheadWindow":1,"otpPolicyInitialCounter":0}' \
    || die "Could not declare the OTP policy."
flow_id() { curl -sf -H "${H}" "${K}/admin/realms/${REALM}/authentication/flows" | jq -r --arg a "${FLOW}" '.[] | select(.alias == $a) | .id'; }
flow="$(flow_id)"
if [ -z "${flow}" ]; then
    put_json POST "/authentication/flows" "{\"alias\":\"${FLOW}\",\"description\":\"ADR 0148: a username and a password and nothing else.\",\"providerId\":\"basic-flow\",\"topLevel\":true,\"builtIn\":false}" \
        || die "Could not create the password-only flow."
    flow="$(flow_id)"
    for provider in direct-grant-validate-username direct-grant-validate-password; do
        put_json POST "/authentication/flows/${FLOW}/executions/execution" "{\"provider\":\"${provider}\"}" \
            || die "Could not add ${provider} to the password-only flow."
    done
fi
curl -sf -H "${H}" "${K}/admin/realms/${REALM}/authentication/flows/${FLOW}/executions" \
    | jq -c '.[] | .requirement = "REQUIRED"' \
    | while IFS= read -r execution; do
        put_json PUT "/authentication/flows/${FLOW}/executions" "${execution}" \
            || die "Could not require every step of the password-only flow."
    done
[ -n "${flow}" ] && [ "${flow}" != null ] || die "The password-only flow has no id."
client_payload="$(jq -n --arg id "${CLIENT}" --arg flow "${flow}" --arg secret "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" '{
    clientId: $id, name: $id, enabled: true, protocol: "openid-connect", publicClient: false,
    standardFlowEnabled: false, implicitFlowEnabled: false, directAccessGrantsEnabled: true,
    serviceAccountsEnabled: false, secret: $secret,
    defaultClientScopes: ["basic"], optionalClientScopes: [],
    authenticationFlowBindingOverrides: {direct_grant: $flow},
    attributes: {"access.token.lifespan": "1", "client.session.idle.timeout": "60", "client.session.max.lifespan": "60"}}')"
existing="$(client_uuid "${CLIENT}")"
if [ -z "${existing}" ] || [ "${existing}" = null ]; then
    put_json POST "/clients" "${client_payload}" || die "Could not create ${CLIENT}."
else
    # An existing client keeps the secret rotated on a previous run: only the binding, the
    # lifetimes and the grant types are reasserted.
    current="$(curl -sf -H "${H}" "${K}/admin/realms/${REALM}/clients/${existing}")" || die "Could not read ${CLIENT}."
    updated="$(printf '%s' "${current}" | jq --arg flow "${flow}" '
        .authenticationFlowBindingOverrides = {direct_grant: $flow}
        | .attributes["access.token.lifespan"] = "1"
        | .attributes["client.session.idle.timeout"] = "60"
        | .attributes["client.session.max.lifespan"] = "60"
        | .directAccessGrantsEnabled = true | .standardFlowEnabled = false | .serviceAccountsEnabled = false')"
    put_json PUT "/clients/${existing}" "${updated}" || die "Could not reassert ${CLIENT}."
fi
say "${CLIENT} bound to ${FLOW}"

echo "== Rotating the realm's confidential client secrets"
# All four the platform resolves. The runbook's loop names three and would
# leave horecaos-device-provisioning (ADR 0079) on the secret it was imported
# with.
#
# Each secret is generated by Keycloak's own CSPRNG, stored in OpenBao, then
# read back and compared before the next. A secret Keycloak holds and OpenBao
# does not is an outage for whatever uses that client, so it stops here rather
# than carrying on.
for pair in \
    horecaos-provisioning:provisioning-secret \
    horecaos-identity-reader:reader-secret \
    horecaos-staff-login:staff-login-secret \
    horecaos-staff-password-check:staff-password-check-secret \
    horecaos-device-provisioning:device-provisioning-secret
do
    client="${pair%%:*}"; slot="${pair##*:}"
    id="$(client_uuid "${client}")"
    [ -n "${id}" ] && [ "${id}" != null ] || die "No client ${client} in realm ${REALM}."

    value="$(curl -sf -X POST -H "${H}" "${K}/admin/realms/${REALM}/clients/${id}/client-secret" \
        | jq -r .value)" || die "Keycloak refused to rotate ${client}."
    [ -n "${value}" ] && [ "${value}" != null ] || die "Rotating ${client} returned no secret."

    jq -n --arg v "${value}" '{data: {value: $v}}' \
        | curl -sf -o /dev/null -X POST -H "X-Vault-Token: ${BAO_TOKEN}" \
            -H "Content-Type: application/json" "${KV}/${slot}" -d @- \
        || die "Could not store ${slot} in OpenBao. ${client} is now rotated in Keycloak only -- run this again."

    back="$(curl -sf -H "X-Vault-Token: ${BAO_TOKEN}" "${KV}/${slot}" | jq -r .data.data.value)" \
        || die "Could not read ${slot} back from OpenBao."
    [ "${back}" = "${value}" ] || die "OpenBao returned something other than what was stored for ${slot}."
    unset value back
    say "${client} -> identity_admin/keycloak/${slot}, stored and read back"
done


echo "== Service-account roles"
# The repository's own script, after rotation and never before it: it grants
# horecaos-provisioning manage-users, and granting that while the client still
# held its imported secret would hand user management to whoever had that
# secret. HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS makes it refuse if any
# secret still matches the import file.
HORECAOS_KEYCLOAK_URL="${K}" \
HORECAOS_KEYCLOAK_ADMIN="${TMP_USER}" \
HORECAOS_KEYCLOAK_ADMIN_PASSWORD="${TMP_PASSWORD}" \
HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1 \
    bash /keycloak/assign-service-account-roles.sh 2>&1 | sed 's/^/  /'


echo "== Platform administrator"
# Found by listing and filtering here, never by `?username=<email>`: an email is
# personal data (ADR 0029) and has no place in a URL, where Keycloak's request
# logging can keep it. A fresh realm holds a handful of staff accounts at most.
find_admin() {
    curl -sf -H "${H}" "${K}/admin/realms/${REALM}/users?briefRepresentation=true&max=1000" \
        | jq -r --arg e "${ADMIN_EMAIL}" '.[] | select((.username | ascii_downcase) == ($e | ascii_downcase)) | .id' \
        | head -1
}
# No required actions, deliberately. Staff sign in through the platform's
# backend using Keycloak's direct grant (ADR 0062), and a direct grant is
# refused outright while any required action is pending -- "set your password
# on first login" would lock the account out instead.
uid="$(find_admin)"
if [ -z "${uid}" ]; then
    jq -n --arg e "${ADMIN_EMAIL}" \
        '{username: $e, email: $e, enabled: true, emailVerified: true, requiredActions: []}' \
        | curl -sf -o /dev/null -X POST -H "${H}" -H "Content-Type: application/json" \
            "${K}/admin/realms/${REALM}/users" -d @- || die "Could not create the administrator account."
    uid="$(find_admin)"
    say "created"
else
    say "already exists; resetting its password to the one you chose"
fi
[ -n "${uid}" ] && [ "${uid}" != null ] || die "Keycloak returned no subject id for the administrator account."

jq -n --arg v "${ADMIN_PASSWORD}" '{type: "password", value: $v, temporary: false}' \
    | curl -sf -o /dev/null -X PUT -H "${H}" -H "Content-Type: application/json" \
        "${K}/admin/realms/${REALM}/users/${uid}/reset-password" -d @- \
    || die "Keycloak refused the password. The realm's password policy may require more."
unset ADMIN_PASSWORD

api="$(client_uuid horecaos-api)"
role="$(curl -sf -H "${H}" "${K}/admin/realms/${REALM}/clients/${api}/roles/platform-admin")" \
    || die "The realm has no horecaos-api platform-admin role."
printf '[%s]' "${role}" | curl -sf -o /dev/null -X POST -H "${H}" -H "Content-Type: application/json" \
    "${K}/admin/realms/${REALM}/users/${uid}/role-mappings/clients/${api}" -d @- \
    || die "Could not grant platform-admin."
say "holds platform-admin"

# The one line the driver parses. A subject id is an identifier, not a secret.
echo "SUBJECT=${uid}"
