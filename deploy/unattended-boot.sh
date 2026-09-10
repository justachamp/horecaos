#!/usr/bin/env bash
#
# Let this host restore the stack by itself after it boots (ADR 0080).
#
#   sudo ./unattended-boot.sh enrol     once, interactively; again to rotate
#   sudo ./unattended-boot.sh status    what is installed, without decrypting it
#   sudo ./unattended-boot.sh revoke    back to a person unsealing every boot
#
# Without this, every boot leaves OpenBao sealed and the stack down until
# someone runs session-start.sh and types three unseal shares and a token.
# Enrolling gives the host what that person supplies, in a form only this host
# can open: three shares and a narrow OpenBao credential, each encrypted by
# systemd-creds with this host's key AND its TPM. horecaos-boot.service runs
# session-start.sh --unattended at boot with them.
#
# What that changes, said plainly: this host can now unseal its own OpenBao.
# Three shares are the threshold, so the escrow copies are no longer the only
# way in -- a running, booted copy of this machine is one too. A copy of its
# disk is not: without this TPM it decrypts nothing. ADR 0080 records the
# trade and what was rejected for it.
#
# The boot credential is not horecaos-deploy. It reads the four startup
# secrets by exact path and issues the agent's secret-id, nothing else, and
# only from inside the openbao container (bound to 127.0.0.1). Your root token
# is used here and never stored.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${HERE}/compose.production.yml"
ENV_FILE="${HORECAOS_ENV_FILE:-/etc/horecaos/production.env}"
export HORECAOS_SECRET_DIR="${HORECAOS_SECRET_DIR:-/run/horecaos/secrets}"
POLICY_FILE="${HERE}/infra/openbao/policies/horecaos-boot.hcl"
SERVICE_TEMPLATE="${HERE}/systemd/horecaos-boot.service.in"
MOUNT_UNIT="${HERE}/systemd/run-horecaos-secrets.mount"
CREDSTORE=/etc/credstore.encrypted
UNIT_DIR=/etc/systemd/system
ROLE=auth/approle/role/horecaos-boot
CREDENTIALS=(horecaos-unseal-1 horecaos-unseal-2 horecaos-unseal-3
             horecaos-boot-role-id horecaos-boot-secret-id)

say()  { printf '\n==> %s\n' "$*"; }
warn() { printf '\n!!  %s\n' "$*" >&2; }
die()  { warn "$*"; exit 1; }
compose() { docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"; }

# Every token reaches OpenBao over stdin, never as an argument.
bao_as() {
    local tok="$1"; shift
    printf '%s' "${tok}" \
        | compose exec -T openbao sh -c 'BAO_TOKEN="$(cat)"; export BAO_TOKEN; "$@"' _ "$@"
}

is_unsealed() { compose exec -T openbao bao status 2>/dev/null | grep -qE '^Sealed +false'; }

# The token on stdin's first line and the rendered policy after it: one pipe,
# nothing written to disk, nothing in argv. @ENVIRONMENT@ becomes this host's
# HORECAOS_ENVIRONMENT, so one policy file serves every environment.
write_policy() {
    local tok="$1" name="$2" file="$3"
    { printf '%s\n' "${tok}"; sed "s/@ENVIRONMENT@/${ENVIRONMENT}/g" "${file}"; } \
        | compose exec -T openbao sh -c 'IFS= read -r BAO_TOKEN; export BAO_TOKEN; bao policy write "$1" -' _ "${name}"
}

read_root_token() {
    printf 'OpenBao root token (hidden): '; read -r -s ROOT; printf '\n'
    [ -n "${ROOT}" ] || die "No token given."
    local lookup
    if ! lookup="$(bao_as "${ROOT}" bao token lookup -format=table 2>&1)"; then
        case "${lookup}" in
            *"permission denied"*|*"bad token"*|*"invalid token"*) die "OpenBao rejected that token." ;;
            *) die "Could not look the token up. OpenBao said:
$(printf '%s\n' "${lookup}" | head -5 | sed 's/^/      /')" ;;
        esac
    fi
    case "$(printf '%s\n' "${lookup}" | awk '$1 == "policies" { $1 = ""; print }')" in
        *root*) ;;
        *) die "That is not the root token: creating the boot role and its policy needs root." ;;
    esac
}

# The credential's name is sealed inside it, and systemd refuses to hand a
# credential to a unit under any other name.
seal_credential() {
    local name="$1" value="$2"
    printf '%s' "${value}" \
        | systemd-creds encrypt --with-key=host+tpm2 --tpm2-pcrs= --name="${name}" - "${CREDSTORE}/${name}" \
        || die "systemd-creds could not seal ${name}."
    chmod 0600 "${CREDSTORE}/${name}"
    # Read back through the TPM and compared here, in memory; never printed.
    [ "$(systemd-creds decrypt --name="${name}" "${CREDSTORE}/${name}" -)" = "${value}" ] \
        || die "${name} did not decrypt to what was sealed. Nothing is enabled; run enrol again."
}


# -----------------------------------------------------------------------------

enrol() {
    [ "$(id -u)" -eq 0 ] || die "Run with sudo."
    for f in "${COMPOSE_FILE}" "${ENV_FILE}" "${POLICY_FILE}" "${SERVICE_TEMPLATE}" "${MOUNT_UNIT}" \
             "${HERE}/session-start.sh"; do
        [ -f "${f}" ] || die "Missing ${f}."
    done
    command -v systemd-creds >/dev/null || die "No systemd-creds on this host."
    # The OpenBao environment segment, read from the file compose reads, so this
    # script and the stack cannot disagree about which store they are in. The
    # default is compose's own.
    ENVIRONMENT="$(sed -n 's/^HORECAOS_ENVIRONMENT=//p' "${ENV_FILE}" | tail -1 | tr -d "\"' ")"
    ENVIRONMENT="${ENVIRONMENT:-production}"
    [[ "${ENVIRONMENT}" =~ ^[a-z][a-z0-9-]{0,30}$ ]] \
        || die "HORECAOS_ENVIRONMENT in ${ENV_FILE} is not a plain lower-case name."
    # No TPM, no enrolment. A key sealed with the host key alone would open
    # from a copy of the disk, which is the one thing this is built to prevent.
    systemd-analyze has-tpm2 >/dev/null 2>&1 \
        || die "This host has no usable TPM 2.0 (systemd-analyze has-tpm2). It cannot enrol."
    is_unsealed || die "OpenBao is sealed. Run session-start.sh first; enrolling needs it running."

    cat <<'WHAT'

This lets the host unseal OpenBao and restore the stack after every boot, with
nobody present. It stores three of your unseal shares and a narrow OpenBao
credential on this machine, each sealed to its TPM. ADR 0080 explains the trade.
Enrolling again replaces everything and invalidates the previous credential.
WHAT
    printf 'Continue? [y/N] '; read -r answer; [ "${answer}" = y ] || exit 0

    read_root_token

    SHARES=()
    for n in 1 2 3; do
        while :; do
            printf 'Unseal share %d of 3 (hidden): ' "${n}"; read -r -s share; printf '\n'
            share="${share//[[:space:]]/}"
            # The two forms `bao operator init` prints: base64 (44) or hex (66).
            if [[ ! "${share}" =~ ^[A-Za-z0-9+/]{43}=$ && ! "${share}" =~ ^[0-9a-fA-F]{66}$ ]]; then
                warn "That is not an unseal share: expected 44 characters of base64 or 66 of hex."; continue
            fi
            for earlier in "${SHARES[@]}"; do
                [ "${earlier}" != "${share}" ] || { warn "That share was already given."; continue 2; }
            done
            SHARES+=("${share}"); break
        done
    done
    unset share earlier

    say "Writing the horecaos-boot policy and recreating its role"
    write_policy "${ROOT}" horecaos-boot "${POLICY_FILE}" >/dev/null
    # Deleted first so that every secret-id issued by an earlier enrolment dies
    # with the old role: rotating is re-enrolling, and nothing is left behind.
    bao_as "${ROOT}" bao delete "${ROLE}" >/dev/null 2>&1 || true
    bao_as "${ROOT}" bao write "${ROLE}" \
        token_policies=horecaos-boot token_ttl=15m token_max_ttl=30m \
        secret_id_ttl=0 secret_id_num_uses=0 \
        secret_id_bound_cidrs=127.0.0.1/32 token_bound_cidrs=127.0.0.1/32 >/dev/null
    role_id="$(bao_as "${ROOT}" bao read -field=role_id "${ROLE}/role-id")"
    secret_id="$(bao_as "${ROOT}" bao write -f -field=secret_id "${ROLE}/secret-id")"

    say "Sealing five credentials to this host's key and TPM"
    install -d -m 0700 "${CREDSTORE}"
    for n in 1 2 3; do seal_credential "horecaos-unseal-${n}" "${SHARES[$((n - 1))]}"; done
    seal_credential horecaos-boot-role-id "${role_id}"
    seal_credential horecaos-boot-secret-id "${secret_id}"
    unset SHARES secret_id

    say "Proving the sealed credential logs in, from where the boot service will"
    token="$(systemd-creds decrypt --name=horecaos-boot-secret-id "${CREDSTORE}/horecaos-boot-secret-id" - \
        | compose exec -T openbao sh -c 'bao write -field=token auth/approle/login role_id="$1" secret_id=-' \
            _ "${role_id}")" || die "The sealed boot credential was refused. Nothing is enabled."
    bao_as "${token}" bao kv get -field=value "horecaos/${ENVIRONMENT}/database/keycloak/password" >/dev/null \
        || die "The boot credential logged in but cannot read a startup secret. Nothing is enabled."
    bao_as "${token}" bao token revoke -self >/dev/null 2>&1 || true
    unset token role_id

    say "Installing and enabling horecaos-boot.service"
    install -m 0644 "${MOUNT_UNIT}" "${UNIT_DIR}/run-horecaos-secrets.mount"
    sed "s|@DEPLOY_DIR@|${HERE}|g" "${SERVICE_TEMPLATE}" > "${UNIT_DIR}/horecaos-boot.service"
    chmod 0644 "${UNIT_DIR}/horecaos-boot.service"
    systemctl daemon-reload
    systemctl enable horecaos-boot.service >/dev/null 2>&1

    cat <<'PROVE'

Enrolled. The next boot restores the stack by itself.

It can also be proved now, the way a boot will do it: OpenBao is sealed, and
horecaos-boot.service unseals it and restarts every service from its stored
credentials. The platform is down for a few minutes while it does.
PROVE
    printf 'Prove it now? [y/N] '; read -r answer
    if [ "${answer}" != y ]; then
        unset ROOT
        say "Not proved. 'sudo ./unattended-boot.sh status' after the next boot shows how it went."
        return 0
    fi
    bao_as "${ROOT}" bao operator seal >/dev/null || die "Could not seal OpenBao to begin the proof."
    unset ROOT
    say "OpenBao sealed. Starting horecaos-boot.service and waiting for it"
    if systemctl restart horecaos-boot.service; then
        say "Proved: the service unsealed OpenBao and brought the stack back with nobody typing"
        journalctl -u horecaos-boot.service -n 12 --no-pager -o cat
    else
        journalctl -u horecaos-boot.service -n 30 --no-pager -o cat >&2
        die "The boot service did not restore the stack; the journal above says why.
    Restore it by hand now: sudo ${HERE}/session-start.sh"
    fi
}


# -----------------------------------------------------------------------------

status() {
    [ "$(id -u)" -eq 0 ] || die "Run with sudo."
    local enabled result exited
    if [ ! -f "${UNIT_DIR}/horecaos-boot.service" ]; then
        printf 'horecaos-boot.service: not installed -- this host is not enrolled\n'
    else
        # is-enabled exits non-zero for "disabled" too, so its output is kept.
        enabled="$(systemctl is-enabled horecaos-boot.service 2>/dev/null)" || true
        # `show` answers Result=success for a unit that has never run at all, so
        # the result is only reported once there is an exit to report on.
        exited="$(systemctl show -p ExecMainExitTimestampMonotonic --value horecaos-boot.service 2>/dev/null)" || true
        result="$(systemctl show -p Result --value horecaos-boot.service 2>/dev/null)" || true
        if [ -z "${exited}" ] || [ "${exited}" = 0 ]; then result="not run since this boot"; fi
        printf 'horecaos-boot.service: %s; last run: %s\n' "${enabled:-unknown}" "${result:-unknown}"
    fi
    for name in "${CREDENTIALS[@]}"; do
        if [ -f "${CREDSTORE}/${name}" ]; then printf '  %-26s sealed\n' "${name}"
        else printf '  %-26s missing\n' "${name}"; fi
    done
    printf '\nLast run, from the journal:\n'
    journalctl -u horecaos-boot.service -b -n 8 --no-pager -o cat 2>/dev/null | sed 's/^/  /' \
        || printf '  (nothing this boot)\n'
}


# -----------------------------------------------------------------------------

revoke() {
    [ "$(id -u)" -eq 0 ] || die "Run with sudo."
    say "Disabling horecaos-boot.service and deleting the sealed credentials"
    systemctl disable horecaos-boot.service >/dev/null 2>&1 || true
    rm -f "${UNIT_DIR}/horecaos-boot.service" "${UNIT_DIR}/run-horecaos-secrets.mount"
    for name in "${CREDENTIALS[@]}"; do rm -f "${CREDSTORE}/${name}"; done
    systemctl daemon-reload

    # The files are gone, but a copy of the secret-id -- in a backup of /etc,
    # say -- would still log in until the role itself is deleted.
    if is_unsealed; then
        read_root_token
        bao_as "${ROOT}" bao delete "${ROLE}" >/dev/null
        unset ROOT
        say "Deleted the horecaos-boot role: no copy of its credential logs in any more"
    else
        warn "OpenBao is sealed, so the horecaos-boot role still exists. Once it is unsealed:
    bao delete ${ROLE}   (as root)"
    fi
    say "Revoked. Every boot now waits for a person to run session-start.sh"
}


case "${1:-}" in
    enrol)  enrol ;;
    status) status ;;
    revoke) revoke ;;
    *) die "Usage: sudo $0 enrol | status | revoke" ;;
esac
