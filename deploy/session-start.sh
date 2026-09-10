#!/usr/bin/env bash
#
# Bring the deploy/ stack back after the host has rebooted.
#
# A reboot destroys two things by design: the RAM-backed directory holding the
# startup secrets, and OpenBao's unsealed state. Every container that reads a
# password from that directory then crash-loops until someone restores it.
# This script is the restoration, and nothing more.
#
# Usage, from wherever compose.production.yml lives:
#   sudo ./session-start.sh                 you type the unseal shares and a token
#   session-start.sh --unattended           horecaos-boot.service runs this at boot
#                                           on a host enrolled for ADR 0080; the
#                                           shares and a narrow credential come
#                                           from systemd, sealed to the host's TPM
#
# What it deliberately does NOT do — each of these is a different operation
# with its own runbook, and doing any of them here would turn a restart into
# something else:
#
#   * No realm import. After Keycloak hardening rotates the realm's client
#     secrets, re-importing horecaos-realm.json silently restores the fallback
#     secrets published in the repository. first-boot must never be re-run to
#     restart, for exactly this reason.
#   * No migrations, no image pull, no tag change. A restart runs what was
#     running. Changing the image is an upgrade: runbook section 8.
#   * No unseal share or token written anywhere, in either mode. By hand you
#     type both: the shares go straight to OpenBao's prompt, the token over
#     stdin. Unattended, systemd decrypts them into a directory only its unit
#     can read, and they reach OpenBao over stdin the same way; this script
#     never copies them out of it. Enrolling is unattended-boot.sh's job.
#
# Modelled on platform/infra/production/deploy.sh, which does the same job for
# the older build-on-host topology. Like it, this refuses far more often than
# it improvises.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${HERE}/compose.production.yml"
ENV_FILE="${HORECAOS_ENV_FILE:-/etc/horecaos/production.env}"
SECRET_DIR="${HORECAOS_SECRET_DIR:-/run/horecaos/secrets}"
export HORECAOS_SECRET_DIR="${SECRET_DIR}"

# KV v2 logical paths. `bao kv` adds the `data/` segment the HTTP API needs,
# and the environment segment is HORECAOS_ENVIRONMENT's (read below), the same
# one the policies are rendered with.
DEPLOY_POLICY="horecaos-deploy"
APPROLE="auth/approle/role/horecaos-platform"

DEPENDENCIES=(platform-db keycloak-db kafka minio openbao-agent)
SERVICES=(keycloak platform-app storefront-web operations-web control-plane-web edge)

say()  { printf '\n==> %s\n' "$*"; }
warn() { printf '\n!!  %s\n' "$*" >&2; }
die()  { warn "$*"; exit 1; }
compose() { docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"; }

UNATTENDED=false
case "${1:-}" in
    --unattended) UNATTENDED=true ;;
    "") ;;
    *) die "Unknown argument: $1. The only one is --unattended." ;;
esac

# The five credentials horecaos-boot.service hands over. Their names are the
# unit's LoadCredentialEncrypted= names; unattended-boot.sh writes them.
BOOT_CREDENTIALS=(horecaos-unseal-1 horecaos-unseal-2 horecaos-unseal-3
                  horecaos-boot-role-id horecaos-boot-secret-id)


# -----------------------------------------------------------------------------
# Refusals
# -----------------------------------------------------------------------------

[ "$(id -u)" -eq 0 ] || die "Run with sudo: mounting the secret tmpfs needs root."
[ -f "${COMPOSE_FILE}" ] || die "No compose.production.yml next to this script (${HERE})."
[ -f "${ENV_FILE}" ] || die "No environment file at ${ENV_FILE}. Set HORECAOS_ENV_FILE."

# The OpenBao environment segment, read from the file compose reads, so this
# script and the stack cannot disagree about which store they are in. The
# default is compose's own.
ENVIRONMENT="$(sed -n 's/^HORECAOS_ENVIRONMENT=//p' "${ENV_FILE}" | tail -1 | tr -d "\"' ")"
ENVIRONMENT="${ENVIRONMENT:-production}"
[[ "${ENVIRONMENT}" =~ ^[a-z][a-z0-9-]{0,30}$ ]] \
    || die "HORECAOS_ENVIRONMENT in ${ENV_FILE} is not a plain lower-case name."
if [ "${UNATTENDED}" = true ]; then
    [ -n "${CREDENTIALS_DIRECTORY:-}" ] \
        || die "--unattended is for horecaos-boot.service, which supplies the credentials. By hand, run without it."
    for name in "${BOOT_CREDENTIALS[@]}"; do
        [ -s "${CREDENTIALS_DIRECTORY}/${name}" ] \
            || die "No ${name} among this unit's credentials. Re-enrol: unattended-boot.sh enrol."
    done
fi

# Re-running mid-session must not disturb a stack that is working. Stopping
# containers whose bind mounts point into the tmpfs, or remounting it under
# them, would take down exactly what this script exists to bring up.
if compose ps --format '{{.Service}} {{.Health}}' platform-app 2>/dev/null | grep -q ' healthy$' \
   && compose exec -T openbao bao status 2>/dev/null | grep -qE '^Sealed +false'; then
    say "OpenBao is unsealed and the platform is healthy. Nothing to restore."
    compose ps --format '    {{.Service}}: {{.State}} {{.Health}}'
    exit 0
fi


# -----------------------------------------------------------------------------
# Phase 1 — stop the crash loops, keep OpenBao
# -----------------------------------------------------------------------------
#
# After a reboot every service that reads a secret file restarts forever,
# because the file is gone. Stopping them first means nothing races the
# restoration below, and nothing is mid-restart when the files reappear.

say "Stopping services that cannot start without their secrets"
compose stop "${DEPENDENCIES[@]}" "${SERVICES[@]}" >/dev/null 2>&1 || true
compose up -d openbao >/dev/null


# -----------------------------------------------------------------------------
# Phase 2 — the RAM-backed secret directory
# -----------------------------------------------------------------------------

if mountpoint -q "${SECRET_DIR}"; then
    say "Secret tmpfs already mounted at ${SECRET_DIR}"
else
    say "Mounting a tmpfs at ${SECRET_DIR}"
    mkdir -p "${SECRET_DIR}"
    mount -t tmpfs -o size=1m,mode=0700,noexec,nosuid,nodev tmpfs "${SECRET_DIR}" \
        || die "Could not mount the secret tmpfs."
fi


# -----------------------------------------------------------------------------
# Phase 3 — unseal
# -----------------------------------------------------------------------------
#
# OpenBao seals itself on every restart. Three of the five shares open it. By
# hand, none of them is on this machine and OpenBao's own prompt hides what you
# type. Unattended, three are, sealed to its TPM (ADR 0080).

for _ in $(seq 1 30); do
    compose exec -T openbao bao status >/dev/null 2>&1 && break
    compose exec -T openbao bao status 2>/dev/null | grep -q '^Sealed' && break
    sleep 2
done

is_unsealed() { compose exec -T openbao bao status 2>/dev/null | grep -qE '^Sealed +false'; }

if [ "${UNATTENDED}" = true ]; then
    # `bao write sys/unseal key=-` reads the share from stdin, so it is never an
    # argument and never in `ps` -- checked against OpenBao 2.4.1 before this
    # was written. `bao operator unseal` only takes a share as an argument or
    # from a terminal, and here there is no terminal.
    for n in 1 2 3; do
        is_unsealed && break
        compose exec -T openbao bao write -format=json sys/unseal key=- \
            < "${CREDENTIALS_DIRECTORY}/horecaos-unseal-${n}" >/dev/null \
            || die "OpenBao refused stored share ${n}. Unseal by hand (sudo session-start.sh), then re-enrol."
    done
    is_unsealed || die "Still sealed after the three stored shares: at least one is not a share of this OpenBao.
    Unseal by hand (sudo session-start.sh), then re-enrol with shares that are."
fi

attempts=0
while ! is_unsealed; do
    attempts=$((attempts + 1))
    [ "${attempts}" -le 5 ] || die "Still sealed after five shares. Stopping rather than guessing."
    # `bao status` exits 2 whenever OpenBao is sealed -- which is every time this
    # line runs. Under `set -euo pipefail` that failed pipeline aborts the whole
    # script, silently, at exactly the point it should be asking for a share.
    progress="$(compose exec -T openbao bao status 2>/dev/null | awk '/^Unseal Progress/{print $3}' || true)"
    say "OpenBao is sealed (progress ${progress:-0/3}). Enter one unseal share."
    # No redirect: OpenBao prints its own hidden-input prompt, and swallowing it
    # would leave you staring at a cursor with no idea what is being asked.
    compose exec openbao bao operator unseal || warn "That share was not accepted."
done
say "OpenBao is unsealed"


# -----------------------------------------------------------------------------
# Phase 4 — one short-lived, deploy-scoped token
# -----------------------------------------------------------------------------
#
# The token you type reaches OpenBao over stdin only: never an argument, so
# never in `ps`, a shell history, or the Docker CLI's logging.
#
# If it is the root token, it is used for two calls and dropped: one to read
# its own policies, which is how this script tells root from a scoped token,
# and one to mint a token under horecaos-deploy, which can read the startup
# secrets and issue a secret-id and nothing else. Everything after runs as the
# scoped token, which is revoked at the end. The durable fix is an operator
# login carrying horecaos-deploy, so the root token can be revoked outright.

bao_run() {
    printf '%s' "${TOKEN}" \
        | compose exec -T openbao sh -c 'BAO_TOKEN="$(cat)"; export BAO_TOKEN; "$@"' _ "$@"
}

finish() {
    if [ "${MINTED}" = true ] && [ -n "${TOKEN:-}" ]; then
        bao_run bao token revoke -self >/dev/null 2>&1 || true
    fi
    TOKEN=""
}

token_from_a_person() {
    printf '\nOpenBao token — root, or one already scoped to %s (input hidden): ' "${DEPLOY_POLICY}"
    read -r -s TOKEN
    printf '\n'
    [ -n "${TOKEN}" ] || die "No token given."

    # `token lookup` has no -field flag in OpenBao 2.4 -- unlike kv get, read,
    # write and token create, which all do -- so the policies row is taken from the
    # table. That table also carries an `id` row, which for a root token IS the root
    # token, so the output is parsed and discarded here and never printed.
    #
    # And a failure is reported as what it is. This line used to discard stderr and
    # call every failure a rejected token; its first real run died on
    # "flag provided but not defined: -field" and told the operator their correct
    # root token had been rejected.
    if ! lookup="$(bao_run bao token lookup -format=table 2>&1)"; then
        case "${lookup}" in
            *"permission denied"*|*"bad token"*|*"invalid token"*)
                die "OpenBao rejected that token." ;;
            *)
                die "Could not look the token up, which is not the same as rejecting it. OpenBao said:
$(printf '%s\n' "${lookup}" | head -5 | sed 's/^/      /')" ;;
        esac
    fi
    policies="$(printf '%s\n' "${lookup}" | awk '$1 == "policies" { $1 = ""; sub(/^ +/, ""); print }')"
    unset lookup
    [ -n "${policies}" ] || die "OpenBao answered, but with no policies row to read."

    case "${policies}" in
        *root*)
            scoped="$(bao_run bao token create -policy="${DEPLOY_POLICY}" -ttl=15m -field=token)" \
                || die "Could not mint a ${DEPLOY_POLICY} token."
            TOKEN="${scoped}"; unset scoped
            MINTED=true
            say "Root token used only to identify itself and mint a 15-minute ${DEPLOY_POLICY} token; dropped"
            ;;
        *"${DEPLOY_POLICY}"*)
            say "Using the ${DEPLOY_POLICY} token you gave"
            ;;
        *)
            TOKEN=""
            die "That token carries neither root nor ${DEPLOY_POLICY} (${policies})."
            ;;
    esac
}

MINTED=false
if [ "${UNATTENDED}" = true ]; then
    # The horecaos-boot AppRole (policy horecaos-boot): the four startup secrets
    # by exact path and the agent's secret-id, nothing else, and bound to
    # 127.0.0.1 -- which is what every call below is, since each runs inside
    # the openbao container. The role-id is configuration; the secret-id goes
    # over stdin.
    role_id="$(cat "${CREDENTIALS_DIRECTORY}/horecaos-boot-role-id")"
    TOKEN="$(compose exec -T openbao sh -c 'bao write -field=token auth/approle/login role_id="$1" secret_id=-' \
        _ "${role_id}" < "${CREDENTIALS_DIRECTORY}/horecaos-boot-secret-id")" \
        || die "OpenBao refused the boot credential. It may have been revoked; re-enrol, or restore by hand."
    unset role_id
    MINTED=true
    say "Logged in as horecaos-boot, a token that dies with this run"
else
    token_from_a_person
fi
trap finish EXIT


# -----------------------------------------------------------------------------
# Phase 5 — materialise the startup secrets onto the tmpfs
# -----------------------------------------------------------------------------

write_secret() {
    local name="$1" path="$2" value
    value="$(bao_run bao kv get -field=value "${path}")" || die "Could not read ${path}."
    [ -n "${value}" ] || die "${path} is empty in OpenBao."
    # 0444: several containers read these as their own non-root user through a
    # bind mount. The protection is the 0700 root-owned directory, and RAM.
    ( umask 133; printf '%s' "${value}" > "${SECRET_DIR}/${name}" )
    chmod 0444 "${SECRET_DIR}/${name}"
    unset value
}

say "Reading startup secrets from OpenBao into RAM"
write_secret platform-db-migrator-password "horecaos/${ENVIRONMENT}/database/platform/migrator-password"
write_secret platform-db-app-password      "horecaos/${ENVIRONMENT}/database/platform/app-password"
write_secret keycloak-db-password          "horecaos/${ENVIRONMENT}/database/keycloak/password"
write_secret minio-root-password           "horecaos/${ENVIRONMENT}/object_storage/platform/root-password"

say "Issuing a fresh AppRole secret-id for the agent"
role_id="$(bao_run bao read -field=role_id "${APPROLE}/role-id")" || die "Could not read the role-id."
secret_id="$(bao_run bao write -field=secret_id -f "${APPROLE}/secret-id")" || die "Could not issue a secret-id."
( umask 133; printf '%s' "${role_id}"   > "${SECRET_DIR}/openbao-role-id" )
( umask 133; printf '%s' "${secret_id}" > "${SECRET_DIR}/openbao-secret-id" )
chmod 0444 "${SECRET_DIR}/openbao-role-id" "${SECRET_DIR}/openbao-secret-id"
unset role_id secret_id

finish
say "Deploy token revoked and cleared"


# -----------------------------------------------------------------------------
# Phase 6 — start, in dependency order, and wait for health rather than "up"
# -----------------------------------------------------------------------------

wait_healthy() {
    # Each service is asked about by name. Listing them together and filtering
    # out the healthy ones is not the same thing: `compose ps` omits a service
    # that has no container at all, so a service that never started would
    # vanish from the list and be counted as healthy.
    local label="$1" limit="$2"; shift 2
    local waited=0 pending svc health
    while :; do
        pending=""
        for svc in "$@"; do
            health="$(compose ps --format '{{.Health}}' "${svc}" 2>/dev/null || true)"
            [ "${health}" = "healthy" ] || pending="${pending}${svc} (${health:-no container})"$'\n'
        done
        [ -z "${pending}" ] && return 0
        [ "${waited}" -ge "${limit}" ] && {
            warn "${label}: not healthy after ${limit}s:"
            printf '%s' "${pending}" | sed 's/^/      /' >&2
            return 1
        }
        sleep 5; waited=$((waited + 5))
    done
}

say "Starting dependencies"
compose up -d "${DEPENDENCIES[@]}" >/dev/null
wait_healthy "dependencies" 300 "${DEPENDENCIES[@]}" \
    || die "Stopping here: the services listed above are not healthy. docker compose logs <service> says why.
    If openbao-agent is among them, the AppRole credential is the first suspect."

say "Starting Keycloak, the platform, the frontends and the edge"
compose up -d "${SERVICES[@]}" >/dev/null
# edge has no healthcheck of its own; the rest do.
wait_healthy "services" 420 keycloak platform-app storefront-web operations-web control-plane-web \
    || die "Stopping here. docker compose logs <service> says why."

say "Up"
compose ps --format '    {{.Service}}: {{.State}} {{.Health}}'
if [ "${UNATTENDED}" = true ]; then
    say "Restored at boot without a person (ADR 0080)"
else
    cat <<'NEXT'

The stack is running. OpenBao reseals on the next reboot, so the next session
starts with this script again -- unless this host is enrolled for unattended
restart: see unattended-boot.sh.
NEXT
fi
