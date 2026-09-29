#!/usr/bin/env bash
#
# Deploy the HorecaOS Platform to the colocated production host.
#
# This script exists so that the deploy is one command with no judgement calls
# in it. The runbook (docs/runbooks/deploy.md) explains what each phase is for
# and what to do when one of them stops; this file is the executable version and
# the two must not drift.
#
# It refuses far more often than it improvises. Every refusal below is something
# that has an obvious wrong answer available at 3am.
#
# Usage:
#   sudo HORECAOS_ENV_FILE=/etc/horecaos/production.env infra/production/deploy.sh
#
# Optional switch: HORECAOS_REMINT_OBJECT_STORE_CREDENTIALS=1 mints the media and
# backup object-store credentials again even though OpenBao already holds them
# (Phase 6a). For a replaced object store or a rotation; see the note there.
#
# Root is required for two things and nothing else: mounting the tmpfs that
# holds secrets, and talking to the Docker socket.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/compose.production.yaml"
ENV_FILE="${HORECAOS_ENV_FILE:-/etc/horecaos/production.env}"
SECRET_DIR="${HORECAOS_SECRET_DIR:-/run/horecaos/secrets}"

# Secret paths in OpenBao. These are the KV v2 logical paths; the `data/` segment
# the HTTP API needs is added by `bao kv`, not here.
DB_MIGRATOR_PATH="horecaos/production/database/platform/migrator-password"
DB_APP_PATH="horecaos/production/database/platform/app-password"
KEYCLOAK_DB_PATH="horecaos/production/database/keycloak/password"
OBJECT_STORE_ROOT_PATH="horecaos/production/object_storage/platform/root-password"
# The backup service account's own pair (ADR 0135, checklist item 3's
# sibling for the backup bucket). docs/runbooks/production-setup.md used to
# ask an operator to mint this by hand; Phase 6a below scripts it instead, so
# these two paths are written here on every deploy rather than read once at
# bootstrap.
OBJECT_STORE_BACKUP_ACCESS_PATH="horecaos/production/object_storage/platform/backup-access-key"
OBJECT_STORE_BACKUP_SECRET_PATH="horecaos/production/object_storage/platform/backup-secret-key"
# The application's own media pair (ADR 0135, checklist item 3). platform-app
# resolves these two by reference at call time (compose.production.yaml's
# HORECAOS_MEDIA_ACCESS_KEY_REF / HORECAOS_MEDIA_SECRET_KEY_REF), so they must be
# in OpenBao before the application starts. bootstrap.sh and
# docs/runbooks/production-setup.md used to leave minting them to an operator;
# Phase 6a below does it, the same way it does the backup pair.
OBJECT_STORE_MEDIA_ACCESS_PATH="horecaos/production/object_storage/platform/media-access-key"
OBJECT_STORE_MEDIA_SECRET_PATH="horecaos/production/object_storage/platform/media-secret-key"
# 1 = mint the media and backup pairs even though OpenBao already holds them.
# For the two situations where the pair on file is wrong: the object store was
# replaced underneath it (docs/runbooks/object-store-migration.md, step 6 -- a
# MinIO-era pair means nothing to RustFS) and a deliberate rotation. The old
# value stays recoverable as a previous KV version; the old service account, if
# it lives on this same object store, stays valid until someone deletes it
# (docs/runbooks/production-setup.md says how).
REMINT_OBJECT_STORE_CREDENTIALS="${HORECAOS_REMINT_OBJECT_STORE_CREDENTIALS:-0}"

say()  { printf '\n==> %s\n' "$*"; }
warn() { printf '\n!!  %s\n' "$*" >&2; }
die()  { printf '\n!!  %s\n' "$*" >&2; exit 1; }

compose() {
    docker compose --file "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"
}


# -----------------------------------------------------------------------------
# Phase 0 — refuse to deploy something nobody can identify later
# -----------------------------------------------------------------------------

[ -f "${COMPOSE_FILE}" ] || die "${COMPOSE_FILE} not found. Run this from a checkout."
[ -r "${ENV_FILE}" ]     || die "${ENV_FILE} not readable. See infra/production/production.env.example."

cd "${REPO_ROOT}"

# A deploy from a dirty tree produces an image whose git sha is a lie, and the
# rollback procedure is entirely built on that sha meaning something.
if [ -n "$(git status --porcelain)" ]; then
    die "The working tree has uncommitted changes.
    Commit or stash them: the image tag is the git sha, and a tag that does not
    describe what is in the image makes the rollback procedure useless."
fi

GIT_SHA="$(git rev-parse HEAD)"
IMAGE_TAG="$(git rev-parse --short HEAD)"
export HORECAOS_GIT_SHA="${GIT_SHA}"
export HORECAOS_IMAGE_TAG="${IMAGE_TAG}"
export HORECAOS_SECRET_DIR="${SECRET_DIR}"

say "Deploying ${IMAGE_TAG} (${GIT_SHA})"


# -----------------------------------------------------------------------------
# Phase 1 — the secret directory
# -----------------------------------------------------------------------------
#
# tmpfs, mode 0700, root only. Every credential this stack needs at startup is
# written here, read by the containers that need it, and lost on reboot. It is
# never on the disk, so it is never in a backup, never on a decommissioned drive,
# and never recoverable from a stolen machine that was powered off.

ensure_secret_dir() {
    if mountpoint -q "${SECRET_DIR}" 2>/dev/null; then
        say "Secret tmpfs already mounted at ${SECRET_DIR}"
        return
    fi

    if [ "${HORECAOS_ALLOW_NON_TMPFS:-0}" = "1" ]; then
        warn "HORECAOS_ALLOW_NON_TMPFS=1: writing secrets to an ordinary directory.
    This is for verifying the stack on a workstation. On the production host it
    means the database password is on the disk, and it must never be set there."
        mkdir -p "${SECRET_DIR}"
        chmod 0700 "${SECRET_DIR}"
        return
    fi

    say "Mounting a tmpfs at ${SECRET_DIR}"
    mkdir -p "${SECRET_DIR}"
    mount -t tmpfs -o size=1m,mode=0700,noexec,nosuid,nodev tmpfs "${SECRET_DIR}" \
        || die "Could not mount the secret tmpfs. Are you root?"
}

ensure_secret_dir


# -----------------------------------------------------------------------------
# Phase 2 — the operator authenticates to OpenBao, once
# -----------------------------------------------------------------------------
#
# The operator's token stays in this shell and reaches OpenBao over stdin. It is
# never an argument to any command, so it is never in `ps`, never in a shell
# history, and never in the Docker CLI's own logging.

if ! compose ps --status running --services 2>/dev/null | grep -qx openbao; then
    say "Starting OpenBao"
    compose up -d openbao
fi

if ! compose exec -T openbao bao status >/dev/null 2>&1; then
    die "OpenBao is sealed or unreachable.
    Unseal it first — that is the one step of this deployment that a human has
    to do, and it is deliberate:

        docker compose -f compose.production.yaml exec openbao bao operator unseal

    Run it once per key share. The shares are not on this machine; see
    docs/runbooks/deploy.md."
fi

printf 'OpenBao token for the deploy operator (input hidden): '
read -r -s OPERATOR_TOKEN
printf '\n'
[ -n "${OPERATOR_TOKEN}" ] || die "No token given."

# `sh -c '...' "$1"` puts the path in $0 inside the container and the token on
# stdin. Neither ends up in an argument list on this host.
bao_run() {
    printf '%s' "${OPERATOR_TOKEN}" \
        | compose exec -T openbao sh -c 'BAO_TOKEN="$(cat)"; export BAO_TOKEN; "$@"' _ "$@"
}

bao_field() {
    bao_run bao kv get -field=value "$1"
}

# Writes a single value to a KV v2 path. The value travels over stdin behind
# the operator's own token, the same protection this file already gives
# OPERATOR_TOKEN itself (see bao_run's own comment): an argument to
# `docker compose exec` sits in this host's own `ps` output for as long as
# the exec runs, and a service-account secret key is exactly the kind of
# value that must never appear there, however briefly.
bao_put_value() {
    local path="$1" value="$2"
    printf '%s\n%s' "${OPERATOR_TOKEN}" "${value}" \
        | compose exec -T openbao sh -c '
            IFS= read -r BAO_TOKEN; export BAO_TOKEN
            IFS= read -r VALUE
            bao kv put "$1" "value=${VALUE}"' _ "${path}"
}

# -- Write-access preflight ----------------------------------------------------
#
# Phase 6a mints object-store service accounts and only then stores their keys
# in OpenBao. A mint cannot be taken back from here, and the secret key exists
# nowhere but a shell variable: a token that turns out unable to store it leaves
# a live s3:* account that nobody holds and nothing records, and every retry
# adds another one. So before anything is minted, ask OpenBao what THIS token
# may do on the paths that would be written, and stop while nothing has changed.
#
# 0 = the token may create and update the KV v2 secret at this logical path,
# 1 = it may not, 2 = OpenBao would not say (which is treated as a no).
token_can_store() {
    # KV v2 authorises the API path, which carries a data/ segment that
    # `bao kv put` adds and this script's logical paths leave out.
    local api_path="horecaos/data/${1#horecaos/}" caps
    caps="$(bao_run bao token capabilities "${api_path}" 2>/dev/null)" || return 2
    # `create, read, update` or `root`; compare whole words.
    caps=",$(printf '%s' "${caps}" | tr -d '[:space:]'),"
    case "${caps}" in *,root,*) return 0 ;; esac
    # create for a path that is new, update for one that is overwritten (a
    # remint, or the surviving half of a failed run): a deploy cannot know
    # which it will meet, so it needs both.
    case "${caps}" in *,create,*) ;; *) return 1 ;; esac
    case "${caps}" in *,update,*) return 0 ;; esac
    return 1
}

# Decides which pairs Phase 6a will mint exactly as Phase 6a decides it (the
# switch, or a missing half), then holds only those paths to the rule. A routine
# deploy with both pairs on file writes nothing, so a read-only horecaos-deploy
# token still deploys.
require_write_access_before_minting() {
    local -a to_write=()
    if [ "${REMINT_OBJECT_STORE_CREDENTIALS}" = "1" ] \
        || ! bao_field "${OBJECT_STORE_BACKUP_ACCESS_PATH}" >/dev/null 2>&1; then
        to_write+=("${OBJECT_STORE_BACKUP_ACCESS_PATH}" "${OBJECT_STORE_BACKUP_SECRET_PATH}")
    fi
    if [ "${REMINT_OBJECT_STORE_CREDENTIALS}" = "1" ] \
        || ! bao_field "${OBJECT_STORE_MEDIA_ACCESS_PATH}" >/dev/null 2>&1 \
        || ! bao_field "${OBJECT_STORE_MEDIA_SECRET_PATH}" >/dev/null 2>&1; then
        to_write+=("${OBJECT_STORE_MEDIA_ACCESS_PATH}" "${OBJECT_STORE_MEDIA_SECRET_PATH}")
    fi
    [ "${#to_write[@]}" -gt 0 ] || return 0

    local path status refused="" unanswered=0
    for path in "${to_write[@]}"; do
        status=0
        token_can_store "${path}" || status=$?
        case "${status}" in
            0) ;;
            1) refused="${refused}
        ${path}" ;;
            *) unanswered=1 ;;
        esac
    done

    [ "${unanswered}" -eq 0 ] \
        || die "OpenBao would not say what this token may do (\`bao token capabilities\` failed).
    Nothing has been minted. Use a token that carries the default policy, or a
    policy that names sys/capabilities-self (horecaos-deploy.hcl does)."
    [ -z "${refused}" ] \
        || die "This token cannot store the object-store service-account keys this deploy
    is about to mint, so nothing has been minted. It needs create and update on:${refused}
    A token with only the horecaos-deploy policy is read-only and cannot. Log in
    with a token that can write these paths (bootstrap.sh's closing note says
    which), then run the deploy again."
}
# end of the write-access preflight helpers

compose exec -T openbao sh -c 'true' >/dev/null 2>&1 \
    || die "Cannot exec into the OpenBao container."

bao_run bao token lookup >/dev/null 2>&1 \
    || die "OpenBao rejected that token."

# Before anything on the object store is touched (Phase 6a): see the helper above.
require_write_access_before_minting


# -----------------------------------------------------------------------------
# Phase 3 — materialise the startup secrets onto the tmpfs
# -----------------------------------------------------------------------------
#
# Four values, for the three containers that read a password from a file at
# startup and have no OpenBao client of their own. Everything the application
# itself needs travels as an ADR 0028 reference and is resolved at call time.

write_secret() {
    local name="$1" path="$2" value
    value="$(bao_field "${path}")" || die "Could not read ${path} from OpenBao."
    [ -n "${value}" ] || die "${path} is empty in OpenBao."

    # 0444 rather than 0400: several containers run as their own non-root user
    # and read these through a bind mount. The protection is the parent
    # directory, which is 0700 and root-owned, plus the fact that the whole
    # thing is RAM.
    ( umask 133; printf '%s' "${value}" > "${SECRET_DIR}/${name}" )
    chmod 0444 "${SECRET_DIR}/${name}"
}

say "Reading startup secrets from OpenBao"
write_secret platform-db-migrator-password "${DB_MIGRATOR_PATH}"
write_secret platform-db-app-password      "${DB_APP_PATH}"
write_secret keycloak-db-password          "${KEYCLOAK_DB_PATH}"
# object-store-secret-key, not minio-root-password (ADR 0135, 2026-09-25):
# compose.production.yaml's `secrets:` block reads this file name by default.
write_secret object-store-secret-key       "${OBJECT_STORE_ROOT_PATH}"


# -----------------------------------------------------------------------------
# Phase 4 — mint a fresh AppRole credential for the agent
# -----------------------------------------------------------------------------
#
# A new secret-id on every deploy. Its useful life is therefore one release
# cycle, and a copy taken from a host that has since been redeployed is dead.

say "Issuing a new AppRole secret-id for the OpenBao agent"
role_id="$(bao_run bao read -field=role_id auth/approle/role/horecaos-platform/role-id)" \
    || die "The horecaos-platform AppRole does not exist. Run infra/production/bootstrap.sh."
secret_id="$(bao_run bao write -field=secret_id -f auth/approle/role/horecaos-platform/secret-id)" \
    || die "Could not issue a secret-id."

( umask 133; printf '%s' "${role_id}"   > "${SECRET_DIR}/openbao-role-id" )
( umask 133; printf '%s' "${secret_id}" > "${SECRET_DIR}/openbao-secret-id" )
chmod 0444 "${SECRET_DIR}/openbao-role-id" "${SECRET_DIR}/openbao-secret-id"
unset role_id secret_id


# -----------------------------------------------------------------------------
# Phase 5 — build
# -----------------------------------------------------------------------------
#
# Built here rather than pulled, because there is no registry yet. The moment one
# exists this becomes a pull, and the build moves to CI where it belongs: a
# server that builds its own images cannot roll back to a release whose source it
# no longer has, and spends production RAM on a Maven run.

# Label the image that is running right now, before it is replaced. Rollback then
# needs no memory and no notes: `horecaos/platform:previous` is by definition what
# was serving traffic before this deploy started.
previous_image="$(compose ps --format '{{.Image}}' platform-app 2>/dev/null | head -1 || true)"
if [ -n "${previous_image}" ] && [ "${previous_image}" != "horecaos/platform:${IMAGE_TAG}" ]; then
    say "Tagging the currently running image (${previous_image}) as horecaos/platform:previous"
    docker image tag "${previous_image}" "horecaos/platform:previous" \
        || die "Could not tag ${previous_image} as horecaos/platform:previous.
    The rollback procedure is built entirely on that tag, so a deploy that could
    not move it is a deploy with no way back."
elif [ -n "${previous_image}" ]; then
    say "Already running horecaos/platform:${IMAGE_TAG}; horecaos/platform:previous still names the release before it"
elif [ "${HORECAOS_NO_ROLLBACK_TARGET:-0}" = "1" ]; then
    warn "HORECAOS_NO_ROLLBACK_TARGET=1: horecaos/platform:previous is left as it is.
    It does not name the release this deploy replaces, so do not roll back to it.
    The way back from this release is another deploy."
elif docker image inspect horecaos/platform:previous >/dev/null 2>&1; then
    # No running application container, but a `previous` tag from some earlier
    # deploy. This branch used to be silent, and silence here is the dangerous
    # answer: the tag now names an image two or more releases old, the deploy
    # succeeds, and the rollback in docs/runbooks/deploy.md starts the wrong
    # release — during whatever incident made somebody reach for it.
    die "platform-app is not running, so the image this deploy replaces cannot be
    identified — and horecaos/platform:previous already points at an older release.
    Rolling back after this deploy would start the wrong one.

    Either start the release that is supposed to be running and re-run this
    script, or, if that image is genuinely gone, say what it was:

        docker image tag horecaos/platform:<sha> horecaos/platform:previous

    If there is no rollback target at all — a rebuilt host, a first deploy of a
    checkout — re-run with HORECAOS_NO_ROLLBACK_TARGET=1 and accept that the only
    way back from this release is another deploy."
else
    say "No running application container and no horecaos/platform:previous tag; this is the first deploy on this host"
fi

say "Building the application image (horecaos/platform:${IMAGE_TAG})"
compose build platform-app platform-migrate ops


# -----------------------------------------------------------------------------
# Phase 6 — dependencies, then migration, then application
# -----------------------------------------------------------------------------
#
# The order is the whole point. The migration job runs against a healthy database
# and finishes before the new application container starts, so no version of the
# application ever observes a half-applied schema.

say "Starting dependencies"
# object-store, not minio (ADR 0135, 2026-09-25): compose.production.yaml has
# no service literally named `minio` any more. object-store-seed itself
# starts later (below), once it has a create-bucket-only credential to run
# with -- starting it here would fall back to the object store's own root
# credential, which is exactly the corner Phase 6a below closes.
compose up -d platform-db keycloak-db kafka object-store openbao openbao-agent

say "Waiting for the object store to become healthy"
for _ in $(seq 1 30); do
    if compose ps --format json object-store 2>/dev/null | grep -q '"Health":"healthy"'; then
        break
    fi
    sleep 2
done


# -----------------------------------------------------------------------------
# Phase 6a — provision the object store's own scoped service accounts
# -----------------------------------------------------------------------------
#
# Three service accounts, minted against the object store's root credential
# and used nowhere but here: a create-bucket-only account for the seed job
# below, and the backup and media accounts docs/runbooks/production-setup.md
# used to ask an operator to create by hand, "Then create the scoped service
# accounts". None of them is the root credential. The backup and media pairs
# are long-lived (see the notes on each below) and are written to OpenBao, where
# the nightly backup job and platform-app read them; the seed pair is re-minted
# every run and never leaves the secret tmpfs. RustFS 1.0.0 has no MinIO-shaped
# `mc admin user add`; its own
# admin API is `PUT /rustfs/admin/v3/add-service-account`, SigV4-signed —
# reached here through the `ops` container the same way
# deploy/local-smoke.sh's own proof of this call does (verified 2026-09-25
# against a running RustFS 1.0.0 container). The `ops` service already
# carries the root access key and the root secret file (compose.production.
# yaml's own comment on `ops` explains why), so nothing here ever holds the
# root credential in this script's own shell.

say "Provisioning a create-bucket-only service account for the seed job"
read -r -d '' SEED_SVC_ACCOUNT_SCRIPT <<'SCRIPT' || true
set -euo pipefail
root_secret="$(cat /run/secrets/object-store-secret-key)"
policy=$(jq -nc --arg media "${HORECAOS_MEDIA_BUCKET}" --arg backup "${HORECAOS_BACKUP_BUCKET}" \
    --arg audit "${HORECAOS_AUDIT_ARCHIVE_BUCKET}" \
    '{Version:"2012-10-17",Statement:[{Effect:"Allow",Action:["s3:CreateBucket","s3:HeadBucket","s3:PutBucketVersioning"],Resource:[("arn:aws:s3:::"+$media),("arn:aws:s3:::"+$backup),("arn:aws:s3:::"+$audit)]}]}')
body=$(jq -nc --argjson policy "${policy}" --arg name "object-store-seed" '{policy:$policy,name:$name}')
curl -fsS -X PUT "http://object-store:9000/rustfs/admin/v3/add-service-account" \
    --user "${OBJECT_STORE_ROOT_ACCESS_KEY}:${root_secret}" \
    --aws-sigv4 "aws:amz:us-east-1:s3" \
    -H "Content-Type: application/json" \
    --data "${body}"
SCRIPT
SEED_SVC_JSON="$(compose run --rm --no-TTY ops bash -c "${SEED_SVC_ACCOUNT_SCRIPT}")" \
    || die "Could not create the seed service account against RustFS's admin API."
SEED_ACCESS_KEY="$(printf '%s' "${SEED_SVC_JSON}" | jq -r '.credentials.accessKey // empty')"
SEED_SECRET_KEY="$(printf '%s' "${SEED_SVC_JSON}" | jq -r '.credentials.secretKey // empty')"
if [ -z "${SEED_ACCESS_KEY}" ] || [ -z "${SEED_SECRET_KEY}" ]; then
    die "RustFS did not return a service-account access key/secret for the seed account at .credentials.accessKey/.credentials.secretKey."
fi
export HORECAOS_OBJECT_STORE_SEED_ACCESS_KEY="${SEED_ACCESS_KEY}"
( umask 133; printf '%s' "${SEED_SECRET_KEY}" > "${SECRET_DIR}/object-store-seed-secret-key" )
chmod 0444 "${SECRET_DIR}/object-store-seed-secret-key"
unset SEED_SVC_ACCOUNT_SCRIPT SEED_SVC_JSON SEED_ACCESS_KEY SEED_SECRET_KEY

# Unlike the seed account above, this one is provisioned once, not re-minted
# on every deploy: it is the nightly backup job's own long-lived credential
# (`s3:*` on the backup bucket, the same breadth the media pair has on the
# media bucket), and RustFS's admin API has no "already exists" response for
# a repeated `add-service-account` call -- minting a fresh one on every
# deploy would leave every earlier pair live and un-revoked forever, an
# ever-growing set of valid, backup-bucket-wide credentials nobody is
# tracking. So this checks OpenBao first and only mints when the pair is
# genuinely missing -- the first deploy after infra/production/bootstrap.sh
# on a fresh host -- or when the operator says so with
# HORECAOS_REMINT_OBJECT_STORE_CREDENTIALS=1 (see REMINT_OBJECT_STORE_CREDENTIALS
# above).
say "Provisioning the backup bucket's own service account"
if [ "${REMINT_OBJECT_STORE_CREDENTIALS}" != "1" ] \
    && bao_field "${OBJECT_STORE_BACKUP_ACCESS_PATH}" >/dev/null 2>&1; then
    say "Backup service account already provisioned; leaving it as it is"
else
    read -r -d '' BACKUP_SVC_ACCOUNT_SCRIPT <<'SCRIPT' || true
set -euo pipefail
root_secret="$(cat /run/secrets/object-store-secret-key)"
policy=$(jq -nc --arg b "${HORECAOS_BACKUP_BUCKET}" \
    '{Version:"2012-10-17",Statement:[{Effect:"Allow",Action:["s3:*"],Resource:[("arn:aws:s3:::"+$b),("arn:aws:s3:::"+$b+"/*")]}]}')
body=$(jq -nc --argjson policy "${policy}" --arg name "backup-production" '{policy:$policy,name:$name}')
curl -fsS -X PUT "http://object-store:9000/rustfs/admin/v3/add-service-account" \
    --user "${OBJECT_STORE_ROOT_ACCESS_KEY}:${root_secret}" \
    --aws-sigv4 "aws:amz:us-east-1:s3" \
    -H "Content-Type: application/json" \
    --data "${body}"
SCRIPT
    BACKUP_SVC_JSON="$(compose run --rm --no-TTY ops bash -c "${BACKUP_SVC_ACCOUNT_SCRIPT}")" \
        || die "Could not create the backup service account against RustFS's admin API."
    BACKUP_ACCESS_KEY="$(printf '%s' "${BACKUP_SVC_JSON}" | jq -r '.credentials.accessKey // empty')"
    BACKUP_SECRET_KEY="$(printf '%s' "${BACKUP_SVC_JSON}" | jq -r '.credentials.secretKey // empty')"
    if [ -z "${BACKUP_ACCESS_KEY}" ] || [ -z "${BACKUP_SECRET_KEY}" ]; then
        die "RustFS did not return a service-account access key/secret for the backup account at .credentials.accessKey/.credentials.secretKey."
    fi
    bao_put_value "${OBJECT_STORE_BACKUP_ACCESS_PATH}" "${BACKUP_ACCESS_KEY}" \
        || die "Could not write ${OBJECT_STORE_BACKUP_ACCESS_PATH} to OpenBao."
    bao_put_value "${OBJECT_STORE_BACKUP_SECRET_PATH}" "${BACKUP_SECRET_KEY}" \
        || die "Could not write ${OBJECT_STORE_BACKUP_SECRET_PATH} to OpenBao."
    unset BACKUP_SVC_ACCOUNT_SCRIPT BACKUP_SVC_JSON BACKUP_ACCESS_KEY BACKUP_SECRET_KEY
fi

# The media pair follows the backup pair's rule -- long-lived, checked and then
# minted only when missing -- for the same reason: RustFS's admin API cannot say
# "already exists", so a mint on every deploy would leave every earlier media
# pair live, un-revoked and untracked, each one bucket-wide on the bucket that
# holds customer uploads. Unlike the backup pair the check needs BOTH halves: a
# lone access key or secret key is a failed earlier run, and the cure for a
# half-written pair is a new whole one.
#
# `s3:*` on the media bucket and its objects and on nothing else: platform-app
# presigns, uploads, lists and deletes there, and must not be able to reach the
# backup bucket. Verified 2026-09-29 against RustFS 1.0.0: this policy put,
# listed, head-ed and deleted in horecaos-media and got AccessDenied on
# horecaos-backups.
#
# Storing them needs an operator token that can create and update these two
# paths (and the backup pair's). infra/openbao/policies/horecaos-deploy.hcl is
# read-only, so a token carrying only that policy cannot. That is found out
# BEFORE this block runs: require_write_access_before_minting (Phase 2) asks
# OpenBao what the token may do on the paths a mint would write and stops while
# nothing has been minted. The dies below are for a write that fails anyway
# (OpenBao sealed mid-run, a policy changed under the token) -- bootstrap.sh's
# closing note says which paths the operator login needs.
#
# The response holds the secret key. It is captured into a variable and read
# with jq; none of it reaches a die/warn/say line (a parse failure below names
# the JSON paths, never the body), and the values reach OpenBao over stdin
# through bao_put_value, not through an argument list.
say "Provisioning the media bucket's own service account"
media_access_present=0
media_secret_present=0
bao_field "${OBJECT_STORE_MEDIA_ACCESS_PATH}" >/dev/null 2>&1 && media_access_present=1
bao_field "${OBJECT_STORE_MEDIA_SECRET_PATH}" >/dev/null 2>&1 && media_secret_present=1
if [ "${media_access_present}" -eq 1 ] && [ "${media_secret_present}" -eq 1 ] \
    && [ "${REMINT_OBJECT_STORE_CREDENTIALS}" != "1" ]; then
    say "Media service account already provisioned; leaving it as it is"
else
    if [ "${media_access_present}" -ne "${media_secret_present}" ]; then
        warn "OpenBao holds only one half of the media pair. Minting a whole new pair and
    overwriting both. Whatever service account the surviving half belonged to is
    still live on the object store: docs/runbooks/production-setup.md says how to
    list and delete it."
    fi
    read -r -d '' MEDIA_SVC_ACCOUNT_SCRIPT <<'SCRIPT' || true
set -euo pipefail
root_secret="$(cat /run/secrets/object-store-secret-key)"
policy=$(jq -nc --arg b "${HORECAOS_MEDIA_BUCKET}" \
    '{Version:"2012-10-17",Statement:[{Effect:"Allow",Action:["s3:*"],Resource:[("arn:aws:s3:::"+$b),("arn:aws:s3:::"+$b+"/*")]}]}')
body=$(jq -nc --argjson policy "${policy}" --arg name "media-production" '{policy:$policy,name:$name}')
curl -fsS -X PUT "http://object-store:9000/rustfs/admin/v3/add-service-account" \
    --user "${OBJECT_STORE_ROOT_ACCESS_KEY}:${root_secret}" \
    --aws-sigv4 "aws:amz:us-east-1:s3" \
    -H "Content-Type: application/json" \
    --data "${body}"
SCRIPT
    MEDIA_SVC_JSON="$(compose run --rm --no-TTY ops bash -c "${MEDIA_SVC_ACCOUNT_SCRIPT}")" \
        || die "Could not create the media service account against RustFS's admin API."
    MEDIA_ACCESS_KEY="$(printf '%s' "${MEDIA_SVC_JSON}" | jq -r '.credentials.accessKey // empty')"
    MEDIA_SECRET_KEY="$(printf '%s' "${MEDIA_SVC_JSON}" | jq -r '.credentials.secretKey // empty')"
    if [ -z "${MEDIA_ACCESS_KEY}" ] || [ -z "${MEDIA_SECRET_KEY}" ]; then
        die "RustFS did not return a service-account access key/secret for the media account at .credentials.accessKey/.credentials.secretKey."
    fi
    bao_put_value "${OBJECT_STORE_MEDIA_ACCESS_PATH}" "${MEDIA_ACCESS_KEY}" \
        || die "Could not write ${OBJECT_STORE_MEDIA_ACCESS_PATH} to OpenBao.
    Re-run with HORECAOS_REMINT_OBJECT_STORE_CREDENTIALS=1 to mint a consistent pair."
    bao_put_value "${OBJECT_STORE_MEDIA_SECRET_PATH}" "${MEDIA_SECRET_KEY}" \
        || die "Could not write ${OBJECT_STORE_MEDIA_SECRET_PATH} to OpenBao.
    OpenBao now holds a mismatched media pair. Re-run with
    HORECAOS_REMINT_OBJECT_STORE_CREDENTIALS=1 to mint a consistent one."
    unset MEDIA_SVC_ACCOUNT_SCRIPT MEDIA_SVC_JSON MEDIA_ACCESS_KEY MEDIA_SECRET_KEY
fi

say "Starting the bucket-creation seed job with its own scoped credential"
compose up -d object-store-seed

say "Waiting for the OpenBao agent to render the application's secrets"
for _ in $(seq 1 30); do
    if compose ps --format json openbao-agent 2>/dev/null | grep -q '"Health":"healthy"'; then
        break
    fi
    sleep 2
done

say "Applying migrations"
# Exported for the lifetime of one `run --rm` container and then removed. It is
# never written to the tmpfs, because unlike the four secrets above nothing needs
# it after the job exits.
export FLYWAY_PASSWORD
FLYWAY_PASSWORD="$(bao_field "${DB_MIGRATOR_PATH}")"
migrate_status=0
compose run --rm platform-migrate migrate || migrate_status=$?
unset FLYWAY_PASSWORD

[ "${migrate_status}" -eq 0 ] \
    || die "The migration failed. STOP. Do not start the new application image.
    docs/runbooks/deploy.md has the half-applied-migration procedure; the short
    version is: read \`compose run --rm platform-migrate info\`, fix forward, and
    never reach for \`repair\` until you have read what it will do."

# -----------------------------------------------------------------------------
# Phase 6b — audit what the migrations granted
# -----------------------------------------------------------------------------
#
# The application connects as a role that owns nothing, so a table without a
# GRANT is a table it cannot read. In development it connects as the owner and
# the difference is invisible, which is exactly how 24 tables reached production
# ungranted. V0035 carries the grants those migrations should have made, and the
# stopgap file that stood in for them until then is gone. This audit is what
# stops a new gap appearing: it runs after every migration, on every deploy, and
# refuses to let one through.

say "Auditing the application role"
docker compose --file "${COMPOSE_FILE}" --env-file "${ENV_FILE}" \
    exec -T platform-db psql -U horecaos_migrator -d horecaos -v ON_ERROR_STOP=1 -q \
    < "${REPO_ROOT}/infra/production/audit-grants.sql" \
    || die "A table exists that the application role cannot read. STOP.
    The message above names it. The fix is a GRANT in the migration that created
    the table — not a manual statement on this server, which the next restore
    would silently drop."


say "Starting Keycloak and the application"
compose up -d keycloak platform-app edge autoheal


# -----------------------------------------------------------------------------
# Phase 7 — prove it
# -----------------------------------------------------------------------------

say "Waiting for the application to report ready"
ready=0
for _ in $(seq 1 60); do
    if compose ps --format json platform-app 2>/dev/null | grep -q '"Health":"healthy"'; then
        ready=1
        break
    fi
    sleep 5
done

if [ "${ready}" -ne 1 ]; then
    warn "The application did not become healthy within five minutes."
    compose logs --tail 60 platform-app || true
    die "Deploy incomplete. docs/runbooks/deploy.md, section 'It did not come up'."
fi

say "Deployed ${IMAGE_TAG}"
compose ps

cat <<-EOF

	Check before you walk away:

	  1. The public health endpoint answers from outside this machine:
	         curl -fsS "\${HORECAOS_API_ORIGIN}/actuator/health/readiness"
	  2. The external uptime monitor has gone green again.
	  3. The running image is the one you meant:
	         docker compose -f compose.production.yaml images platform-app

	The previous image is still on this host, tagged horecaos/platform:previous.
	Rollback is in docs/runbooks/deploy.md and does not require this script.
EOF
