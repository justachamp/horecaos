#!/usr/bin/env bash
#
# Deploy the Qoida Platform to the colocated production host.
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
#   sudo QOIDA_ENV_FILE=/etc/qoida/production.env infra/production/deploy.sh
#
# Root is required for two things and nothing else: mounting the tmpfs that
# holds secrets, and talking to the Docker socket.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/compose.production.yaml"
ENV_FILE="${QOIDA_ENV_FILE:-/etc/qoida/production.env}"
SECRET_DIR="${QOIDA_SECRET_DIR:-/run/qoida/secrets}"

# Secret paths in OpenBao. These are the KV v2 logical paths; the `data/` segment
# the HTTP API needs is added by `bao kv`, not here.
DB_MIGRATOR_PATH="qoida/production/database/platform/migrator-password"
DB_APP_PATH="qoida/production/database/platform/app-password"
KEYCLOAK_DB_PATH="qoida/production/database/keycloak/password"
MINIO_ROOT_PATH="qoida/production/object_storage/platform/root-password"

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
export QOIDA_GIT_SHA="${GIT_SHA}"
export QOIDA_IMAGE_TAG="${IMAGE_TAG}"
export QOIDA_SECRET_DIR="${SECRET_DIR}"

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

    if [ "${QOIDA_ALLOW_NON_TMPFS:-0}" = "1" ]; then
        warn "QOIDA_ALLOW_NON_TMPFS=1: writing secrets to an ordinary directory.
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

compose exec -T openbao sh -c 'true' >/dev/null 2>&1 \
    || die "Cannot exec into the OpenBao container."

bao_run bao token lookup >/dev/null 2>&1 \
    || die "OpenBao rejected that token."


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
write_secret minio-root-password           "${MINIO_ROOT_PATH}"


# -----------------------------------------------------------------------------
# Phase 4 — mint a fresh AppRole credential for the agent
# -----------------------------------------------------------------------------
#
# A new secret-id on every deploy. Its useful life is therefore one release
# cycle, and a copy taken from a host that has since been redeployed is dead.

say "Issuing a new AppRole secret-id for the OpenBao agent"
role_id="$(bao_run bao read -field=role_id auth/approle/role/qoida-platform/role-id)" \
    || die "The qoida-platform AppRole does not exist. Run infra/production/bootstrap.sh."
secret_id="$(bao_run bao write -field=secret_id -f auth/approle/role/qoida-platform/secret-id)" \
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
# needs no memory and no notes: `qoida/platform:previous` is by definition what
# was serving traffic before this deploy started.
previous_image="$(compose ps --format '{{.Image}}' platform-app 2>/dev/null | head -1 || true)"
if [ -n "${previous_image}" ] && [ "${previous_image}" != "qoida/platform:${IMAGE_TAG}" ]; then
    say "Tagging the currently running image (${previous_image}) as qoida/platform:previous"
    docker image tag "${previous_image}" "qoida/platform:previous" \
        || die "Could not tag ${previous_image} as qoida/platform:previous.
    The rollback procedure is built entirely on that tag, so a deploy that could
    not move it is a deploy with no way back."
elif [ -n "${previous_image}" ]; then
    say "Already running qoida/platform:${IMAGE_TAG}; qoida/platform:previous still names the release before it"
elif [ "${QOIDA_NO_ROLLBACK_TARGET:-0}" = "1" ]; then
    warn "QOIDA_NO_ROLLBACK_TARGET=1: qoida/platform:previous is left as it is.
    It does not name the release this deploy replaces, so do not roll back to it.
    The way back from this release is another deploy."
elif docker image inspect qoida/platform:previous >/dev/null 2>&1; then
    # No running application container, but a `previous` tag from some earlier
    # deploy. This branch used to be silent, and silence here is the dangerous
    # answer: the tag now names an image two or more releases old, the deploy
    # succeeds, and the rollback in docs/runbooks/deploy.md starts the wrong
    # release — during whatever incident made somebody reach for it.
    die "platform-app is not running, so the image this deploy replaces cannot be
    identified — and qoida/platform:previous already points at an older release.
    Rolling back after this deploy would start the wrong one.

    Either start the release that is supposed to be running and re-run this
    script, or, if that image is genuinely gone, say what it was:

        docker image tag qoida/platform:<sha> qoida/platform:previous

    If there is no rollback target at all — a rebuilt host, a first deploy of a
    checkout — re-run with QOIDA_NO_ROLLBACK_TARGET=1 and accept that the only
    way back from this release is another deploy."
else
    say "No running application container and no qoida/platform:previous tag; this is the first deploy on this host"
fi

say "Building the application image (qoida/platform:${IMAGE_TAG})"
compose build platform-app platform-migrate


# -----------------------------------------------------------------------------
# Phase 6 — dependencies, then migration, then application
# -----------------------------------------------------------------------------
#
# The order is the whole point. The migration job runs against a healthy database
# and finishes before the new application container starts, so no version of the
# application ever observes a half-applied schema.

say "Starting dependencies"
compose up -d platform-db keycloak-db kafka minio openbao openbao-agent

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
    exec -T platform-db psql -U qoida_migrator -d qoida -v ON_ERROR_STOP=1 -q \
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
	         curl -fsS "\${QOIDA_API_ORIGIN}/actuator/health/readiness"
	  2. The external uptime monitor has gone green again.
	  3. The running image is the one you meant:
	         docker compose -f compose.production.yaml images platform-app

	The previous image is still on this host, tagged qoida/platform:previous.
	Rollback is in docs/runbooks/deploy.md and does not require this script.
EOF
