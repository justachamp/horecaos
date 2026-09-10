#!/usr/bin/env bash
#
# Keycloak hardening, and the platform's first administrator. Run once, after
# the first boot has imported the realm and the stack is healthy.
#
#   sudo ./keycloak-stage2.sh
#
# The realm is imported with its confidential clients on placeholder secrets
# whose defaults are printed in the repository. This rotates all four the
# platform resolves, stores each in OpenBao and reads it back, then assigns the
# service-account roles -- after rotation and never before, because it grants
# horecaos-provisioning manage-users. Then it creates your platform
# administrator account, with a password you choose here, and restarts the
# platform so it grants that account platform scope.
#
# Safe to run again. A run that stops part-way is fixed by running it again.
#
# Nothing it asks for is written to disk or passed as an argument. Your root
# token stays in this shell: it loads a narrow policy and mints a 10-minute
# token under it, and only that token reaches the container that talks to
# Keycloak and OpenBao. The temporary Keycloak admin it uses is deleted at the
# end, whether the run succeeds or not.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${HERE}/compose.production.yml"
ENV_FILE="${HORECAOS_ENV_FILE:-/etc/horecaos/production.env}"
export HORECAOS_SECRET_DIR="${HORECAOS_SECRET_DIR:-/run/horecaos/secrets}"
KEYCLOAK_DIR="${HERE}/platform/infra/keycloak"
POLICY_FILE="${HERE}/infra/openbao/policies/horecaos-keycloak-rotation.hcl"

say()  { printf '\n==> %s\n' "$*"; }
warn() { printf '\n!!  %s\n' "$*" >&2; }
die()  { warn "$*"; exit 1; }
compose() { docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"; }


# ----------------------------------------------------------------- refusals

[ "$(id -u)" -eq 0 ] || die "Run with sudo."
for f in "${COMPOSE_FILE}" "${ENV_FILE}" "${KEYCLOAK_DIR}/stage2-inner.sh" \
         "${KEYCLOAK_DIR}/assign-service-account-roles.sh" "${POLICY_FILE}"; do
    [ -f "${f}" ] || die "Missing ${f}."
done
compose exec -T openbao bao status 2>/dev/null | grep -qE '^Sealed +false' \
    || die "OpenBao is sealed. Run session-start.sh first."
for svc in keycloak platform-app; do
    [ "$(compose ps --format '{{.Health}}' "${svc}" 2>/dev/null)" = healthy ] \
        || die "${svc} is not healthy. Run session-start.sh first."
done
if grep -qE '^HORECAOS_IAM_BOOTSTRAP_PLATFORM_ADMINS=.+' "${ENV_FILE}"; then
    warn "This host already has a bootstrap administrator. Running again rotates the four
    client secrets once more and resets that account's password to the one you enter."
    printf 'Continue? [y/N] '; read -r answer; [ "${answer}" = y ] || exit 0
fi


# ------------------------------------------------------------------- inputs

printf '\nYour email -- it becomes your platform-admin username: '
read -r ADMIN_EMAIL
case "${ADMIN_EMAIL}" in *@*.*) ;; *) die "That does not look like an email address." ;; esac

while :; do
    printf 'Choose a password, at least 12 characters (hidden): '; read -r -s PW1; printf '\n'
    printf 'Again (hidden): '; read -r -s PW2; printf '\n'
    [ "${PW1}" = "${PW2}" ] || { warn "They did not match."; continue; }
    [ "${#PW1}" -ge 12 ] || { warn "Too short."; continue; }
    break
done
unset PW2

printf 'OpenBao root token (hidden): '; read -r -s ROOT; printf '\n'
[ -n "${ROOT}" ] || die "No token given."

# Every call carries its token on stdin, never as an argument.
bao_as() {
    local tok="$1"; shift
    printf '%s' "${tok}" \
        | compose exec -T openbao sh -c 'BAO_TOKEN="$(cat)"; export BAO_TOKEN; "$@"' _ "$@"
}

if ! lookup="$(bao_as "${ROOT}" bao token lookup -format=table 2>&1)"; then
    case "${lookup}" in
        *"permission denied"*|*"bad token"*) die "OpenBao rejected that token." ;;
        *) die "Could not look the token up. OpenBao said:
$(printf '%s\n' "${lookup}" | head -5 | sed 's/^/      /')" ;;
    esac
fi
case "$(printf '%s\n' "${lookup}" | awk '$1 == "policies" { $1 = ""; print }')" in
    *root*) ;;
    *) unset lookup; die "That is not the root token. Rotation stores secrets, which a deploy token cannot." ;;
esac
unset lookup


# -------------------------------------------- a token scoped to rotation only

say "Loading the rotation policy and minting a 10-minute token under it"
docker cp "${POLICY_FILE}" "$(compose ps -q openbao):/tmp/horecaos-keycloak-rotation.hcl"
bao_as "${ROOT}" bao policy write horecaos-keycloak-rotation /tmp/horecaos-keycloak-rotation.hcl >/dev/null
SCOPED="$(bao_as "${ROOT}" bao token create -policy=horecaos-keycloak-rotation -ttl=10m -field=token)" \
    || die "Could not mint the rotation token."
unset ROOT
say "Root token cleared from this shell"

TMP_USER="stage2-$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
TMP_PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 32)"

cleanup() {
    # The temporary admin is removed whether the run got this far or not, and
    # the scoped token is revoked rather than left to expire.
    if [ -n "${TMP_PASSWORD:-}" ]; then
        printf '%s\n%s\n' "${TMP_USER}" "${TMP_PASSWORD}" \
          | compose --profile ops run --rm --no-TTY -T --entrypoint sh ops -c '
              IFS= read -r u; IFS= read -r p; K=http://keycloak:8080
              t=$(curl -sf -X POST $K/realms/master/protocol/openid-connect/token -d client_id=admin-cli \
                  -d grant_type=password --data-urlencode "username=$u" --data-urlencode "password=$p" | jq -r .access_token) || exit 0
              id=$(curl -sf -H "Authorization: Bearer $t" "$K/admin/realms/master/users?briefRepresentation=true&max=100" \
                  | jq -r --arg u "$u" ".[] | select(.username==\$u) | .id")
              [ -n "$id" ] && curl -sf -o /dev/null -X DELETE -H "Authorization: Bearer $t" $K/admin/realms/master/users/$id' \
          >/dev/null 2>&1 && printf '\n==> Temporary Keycloak admin deleted\n'
    fi
    [ -n "${SCOPED:-}" ] && bao_as "${SCOPED}" bao token revoke -self >/dev/null 2>&1 \
        && printf '==> Rotation token revoked\n'
    unset SCOPED TMP_PASSWORD PW1
}
trap cleanup EXIT


# ------------------------------------------------- the temporary Keycloak admin

say "Creating a temporary Keycloak admin in a separate container"
# A separate container because `kc.sh bootstrap-admin` inside the running one
# fails: even in non-server mode it opens the management interface on :9000,
# which the live server already holds. Its password is random and dies with
# this run; nobody ever knows it.
# Captured, then searched. `cmd | grep -q` under pipefail can fail a run that
# succeeded: grep exits at its first match and closes the pipe, and Keycloak is
# still printing ("Keycloak stopped ...") when it does, so it can die of SIGPIPE.
boot="$(compose run --rm --no-TTY -e KC_BOOTSTRAP_ADMIN_PASSWORD="${TMP_PASSWORD}" keycloak \
    bootstrap-admin user --username "${TMP_USER}" --password:env KC_BOOTSTRAP_ADMIN_PASSWORD --no-prompt 2>&1)" || true
case "${boot}" in
    *"Created temporary admin"*) ;;
    *) die "Keycloak did not create the temporary admin:
$(printf '%s\n' "${boot}" | grep -E 'ERROR' | head -3 | sed 's/^/      /')" ;;
esac
unset boot


# ----------------------------------------------------- rotation, roles, account

say "Rotating secrets, assigning roles, creating your account"
out="$(printf '%s\n%s\n%s\n%s\n%s\n' "${SCOPED}" "${ADMIN_EMAIL}" "${PW1}" "${TMP_USER}" "${TMP_PASSWORD}" \
    | compose --profile ops run --rm --no-TTY -T \
        --volume "${KEYCLOAK_DIR}:/keycloak:ro" --entrypoint bash ops /keycloak/stage2-inner.sh 2>&1)" \
    || { printf '%s\n' "${out}" | grep -v '^SUBJECT=' >&2; die "Stopped. Running this again is safe."; }
printf '%s\n' "${out}" | grep -v '^SUBJECT='
SUBJECT="$(printf '%s\n' "${out}" | sed -n 's/^SUBJECT=//p' | tail -1)"
unset out
printf '%s' "${SUBJECT}" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' \
    || die "Did not get a subject id back."


# ------------------------------------------ platform scope for that account

say "Granting ${ADMIN_EMAIL} platform scope"
if grep -qE '^HORECAOS_IAM_BOOTSTRAP_PLATFORM_ADMINS=' "${ENV_FILE}"; then
    sed -i "s/^HORECAOS_IAM_BOOTSTRAP_PLATFORM_ADMINS=.*/HORECAOS_IAM_BOOTSTRAP_PLATFORM_ADMINS=${SUBJECT}/" "${ENV_FILE}"
else
    printf '\nHORECAOS_IAM_BOOTSTRAP_PLATFORM_ADMINS=%s\n' "${SUBJECT}" >> "${ENV_FILE}"
fi
# Recreated, not restarted: the reconciler reads the setting at startup, and
# only a recreated container sees a changed environment file.
compose up -d platform-app >/dev/null
for _ in $(seq 1 60); do
    [ "$(compose ps --format '{{.Health}}' platform-app)" = healthy ] && break
    sleep 5
done
[ "$(compose ps --format '{{.Health}}' platform-app)" = healthy ] \
    || die "platform-app did not come back healthy. docker compose logs platform-app says why."


# --------------------------------------------- prove the sign-in, then report

say "Signing in as ${ADMIN_EMAIL}, through the platform, to prove it works"
code="$(printf '%s\n%s\n' "${ADMIN_EMAIL}" "${PW1}" \
    | compose --profile ops run --rm --no-TTY -T --entrypoint sh ops -c '
        IFS= read -r u; IFS= read -r p
        jq -n --arg u "$u" --arg p "$p" "{username: \$u, password: \$p}" \
          | curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" \
              http://platform-app:8080/api/v1/control-plane/auth/sessions -d @-' 2>/dev/null)"
unset PW1
case "${code}" in
    2??) say "Signed in: HTTP ${code}" ;;
    *)   die "The platform refused the sign-in: HTTP ${code}. The account and secrets are in place;
    docker compose logs platform-app says why." ;;
esac

cat <<DONE

Done. Sign in at your control-plane origin as ${ADMIN_EMAIL}, with the password
you chose here. It was never written to disk or printed.
DONE
