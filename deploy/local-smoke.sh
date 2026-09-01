#!/usr/bin/env bash
#
# Prove deploy/compose.production.yml on the dev machine, without a server.
#
# This is ADR 0061's local proof: every image this script uses is built from
# this checkout's own Dockerfiles (never pulled), every config file is the
# unmodified production one, and the only things that differ from a real
# deploy are the port numbers, the TLS issuer (Caddy's own internal CA
# instead of Let's Encrypt, since nobody owns *.horecaos.test), and a
# handful of test secret values this script generates itself rather than an
# operator loading real ones into OpenBao by hand. See deploy/README.md,
# "What the local proof actually proves" for the full list of what this
# does and does not stand in for.
#
# Isolation, stated once because it matters more than anything else in this
# file: everything below runs under compose project name
# "${PROJECT}" — never "horecaos-platform", which is the dev
# stack's own project name (platform/compose.yaml). `down -v` at the end of
# this script is scoped to this project only, by every invocation naming it
# explicitly. If you are reading this because something went wrong, the dev
# stack (`make up` / `make down` in platform/) was never touched by this
# script and does not need to be recovered.
#
# Usage:
#   deploy/local-smoke.sh
#
# Env:
#   HORECAOS_SMOKE_KEEP=1   Skip teardown on exit, for debugging. Remember to
#                           run the teardown command this prints, by hand,
#                           when you are done.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_DIR="${REPO_ROOT}/deploy"
PLATFORM_DIR="${REPO_ROOT}/platform"

PROJECT="horecaos-prod-smoke"
ENV_FILE="${DEPLOY_DIR}/env.local-test"
COMPOSE_FILES=(-f "${DEPLOY_DIR}/compose.production.yml" -f "${DEPLOY_DIR}/compose.local-test.override.yml")

# Sourced (not just passed to compose via --env-file) so the checklist below
# can read HORECAOS_STOREFRONT_TENANT_ID, HORECAOS_AUTH_ORIGIN and friends
# directly, with one file as the single source of truth for both this
# script and the containers it starts. `set -a` exports everything the file
# defines; `env.local-test` is a plain KEY=VALUE file with no command
# substitution in it, so sourcing it is exactly as safe as reading it.
set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/horecaos-prod-smoke.XXXXXX")"
SECRET_DIR="${WORK_DIR}/secrets"
CA_FILE="${WORK_DIR}/caddy-internal-ca.pem"
LOG_FILE="${WORK_DIR}/smoke.log"

REGISTRY="horecaos-smoke"
TAG="local"

FAILURES=0

say()  { printf '\n==> %s\n' "$*" | tee -a "${LOG_FILE}"; }
warn() { printf '\n!!  %s\n' "$*" | tee -a "${LOG_FILE}" >&2; }
fail() { warn "FAIL: $*"; FAILURES=$((FAILURES + 1)); }
check() { printf '    CHECK ok: %s\n' "$*" | tee -a "${LOG_FILE}"; }
die()  { warn "$*"; exit 1; }

compose() {
    docker compose -p "${PROJECT}" "${COMPOSE_FILES[@]}" --env-file "${ENV_FILE}" "$@"
}

cleanup() {
    local status=$?
    local keep_workdir=0
    if [ "${status}" -ne 0 ] || [ "${FAILURES}" -gt 0 ]; then
        keep_workdir=1
        warn "Something failed. Recent logs from every service:"
        compose logs --tail 30 2>&1 | tee -a "${LOG_FILE}" || true
    fi
    if [ "${HORECAOS_SMOKE_KEEP:-0}" = "1" ]; then
        warn "HORECAOS_SMOKE_KEEP=1: leaving the stack up for inspection."
        printf 'Tear it down yourself with:\n    docker compose -p %s %s --env-file %s down -v\n' \
            "${PROJECT}" "${COMPOSE_FILES[*]}" "${ENV_FILE}"
        printf 'Work dir (secrets, CA cert, full log): %s\n' "${WORK_DIR}"
        return
    fi
    say "Tearing down project ${PROJECT} (and only that project)"
    compose down -v --remove-orphans >>"${LOG_FILE}" 2>&1 || true
    if [ "${keep_workdir}" -eq 1 ]; then
        warn "Keeping ${WORK_DIR} (secrets, CA cert, full log) because the run did not pass cleanly."
    else
        rm -rf "${WORK_DIR}"
    fi
}
trap cleanup EXIT

say "Working directory: ${WORK_DIR} (full log: ${LOG_FILE})"
mkdir -p "${SECRET_DIR}"
chmod 0700 "${SECRET_DIR}"

# Exported before the FIRST `docker compose` call of any kind: compose
# interpolates every top-level section of the file — including `secrets:`,
# which every service in it can reference — regardless of which single
# service a given `up`/`run` targets. HORECAOS_SECRET_DIR has no default in
# deploy/compose.production.yml on purpose (see that file's own comment), so
# it has to exist in this shell's environment before anything below runs,
# not merely by the time platform-app itself starts.
export HORECAOS_SECRET_DIR="${SECRET_DIR}"

# -----------------------------------------------------------------------------
# 0. Refuse to run anywhere near the dev stack
# -----------------------------------------------------------------------------

if docker compose -p "${PROJECT}" ps -q >/dev/null 2>&1 && \
   [ -n "$(docker compose -p "${PROJECT}" ps -q 2>/dev/null || true)" ]; then
    die "Project ${PROJECT} already has containers. Run 'docker compose -p ${PROJECT} down -v' first, or this run's cleanup would tear down a stack you may still want."
fi

# -----------------------------------------------------------------------------
# 1. Build every image from this checkout (never pulled — see header)
# -----------------------------------------------------------------------------

say "Building images (this is the slow part; Docker layer cache makes repeat runs fast)"

build() {
    local name="$1" context="$2" dockerfile="$3"
    printf '    %s\n' "${REGISTRY}/${name}:${TAG}"
    docker build -q -t "${REGISTRY}/${name}:${TAG}" -f "${dockerfile}" "${context}" >>"${LOG_FILE}"
}

build horecaos-platform          "${PLATFORM_DIR}"                 "${PLATFORM_DIR}/Dockerfile"
build horecaos-platform-migrate  "${PLATFORM_DIR}"                 "${PLATFORM_DIR}/infra/production/migrate/Dockerfile"
build horecaos-platform-ops      "${PLATFORM_DIR}"                 "${PLATFORM_DIR}/infra/production/ops/Dockerfile"
build horecaos-postgres-postgis  "${PLATFORM_DIR}/infra/postgres"  "${PLATFORM_DIR}/infra/postgres/Dockerfile"
build horecaos-storefront        "${REPO_ROOT}/frontend/storefront"    "${REPO_ROOT}/frontend/storefront/Dockerfile"
build horecaos-control-plane     "${REPO_ROOT}/frontend/control-plane" "${REPO_ROOT}/frontend/control-plane/Dockerfile"
build horecaos-operations        "${REPO_ROOT}/frontend/operations"    "${REPO_ROOT}/frontend/operations/Dockerfile"

say "All 7 images built"

# -----------------------------------------------------------------------------
# 2. OpenBao: up, initialised, unsealed, non-interactively
# -----------------------------------------------------------------------------
#
# Real production does this by hand (production-setup.md, "Bootstrap
# OpenBao") because the unseal shares must never all exist in one place. A
# throwaway local instance that is deleted at the end of this script has no
# such requirement, so the keys are captured in this shell only for as long
# as it takes to unseal, and never written to disk.

say "Starting OpenBao"
compose up -d openbao >>"${LOG_FILE}" 2>&1

wait_healthy() {
    local service="$1" timeout="${2:-120}" waited=0 cid status
    while [ "${waited}" -lt "${timeout}" ]; do
        cid="$(compose ps -q "${service}" 2>/dev/null || true)"
        if [ -n "${cid}" ]; then
            status="$(docker inspect --format '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo "")"
            [ "${status}" = "healthy" ] && return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

wait_reachable() {
    # For OpenBao specifically: its own healthcheck (`bao status`) fails by
    # design while sealed (exit 2) — see deploy/compose.production.yml's own
    # comment on that healthcheck. Waiting for Docker's "healthy" here would
    # wait forever, since unsealing is the very next step. This instead waits
    # for the API to answer at all: exit 0 (unsealed) or 2 (sealed but
    # reachable) both mean "up"; anything else (connection refused, container
    # not started yet) keeps waiting.
    local service="$1" timeout="${2:-60}" waited=0 rc
    while [ "${waited}" -lt "${timeout}" ]; do
        compose exec -T "${service}" bao status >/dev/null 2>&1
        rc=$?
        if [ "${rc}" -eq 0 ] || [ "${rc}" -eq 2 ]; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

wait_reachable openbao 60 || die "OpenBao's API never became reachable."
check "openbao is running and reachable (still sealed at this point — that is expected)"

say "Initialising and unsealing OpenBao"
INIT_JSON="$(compose exec -T openbao bao operator init -key-shares=5 -key-threshold=3 -format=json)"
ROOT_TOKEN="$(printf '%s' "${INIT_JSON}" | jq -r '.root_token')"
# Not `mapfile` (bash 4+ only): macOS ships bash 3.2 as /bin/bash, and this
# script has to run on the machine running the smoke test, not just on the
# Ubuntu production host the rest of this ADR targets.
UNSEAL_KEYS=()
while IFS= read -r key; do
    UNSEAL_KEYS+=("${key}")
done < <(printf '%s' "${INIT_JSON}" | jq -r '.unseal_keys_b64[]')
unset INIT_JSON

for i in 0 1 2; do
    compose exec -T openbao bao operator unseal "${UNSEAL_KEYS[$i]}" >>"${LOG_FILE}" 2>&1
done
unset UNSEAL_KEYS

compose exec -T openbao bao status >>"${LOG_FILE}" 2>&1 || die "OpenBao is still sealed after 3 unseal shares."
check "OpenBao unsealed"

bao_run() {
    printf '%s' "${ROOT_TOKEN}" \
        | compose exec -T openbao sh -c 'BAO_TOKEN="$(cat)"; export BAO_TOKEN; "$@"' _ "$@"
}

say "Enabling the horecaos KV v2 mount, policies, and the platform AppRole"
bao_run bao secrets enable -path=horecaos -version=2 kv >>"${LOG_FILE}" 2>&1 || true

for policy in horecaos-platform horecaos-deploy; do
    compose cp "${DEPLOY_DIR}/infra/openbao/policies/${policy}.hcl" "openbao:/tmp/${policy}.hcl" >>"${LOG_FILE}" 2>&1
    bao_run bao policy write "${policy}" "/tmp/${policy}.hcl" >>"${LOG_FILE}" 2>&1
done

bao_run bao auth enable approle >>"${LOG_FILE}" 2>&1 || true
bao_run bao write auth/approle/role/horecaos-platform \
    token_policies=horecaos-platform token_ttl=1h token_period=1h \
    secret_id_ttl=720h secret_id_num_uses=0 bind_secret_id=true >>"${LOG_FILE}" 2>&1

# -----------------------------------------------------------------------------
# 3. Seed every secret the stack resolves at startup
# -----------------------------------------------------------------------------
#
# Generated in THIS shell (not inside the container, unlike the real
# bootstrap procedure) because a local throwaway instance has no operator
# to keep them from — see the header comment. The provisioning/reader
# secrets are the one exception: they are fixed to the realm import file's
# own fallback values, matched deliberately rather than randomised, because
# this script does not rotate them (see step 6 below).

rand() { openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 32; }

DB_MIGRATOR_PW="$(rand)"
DB_APP_PW="$(rand)"
KEYCLOAK_DB_PW="$(rand)"
MINIO_ROOT_PW="$(rand)"
HANDOVER_PEPPER="smoke-test-handover-pepper-not-for-any-other-use"
KEK="smoke-test-key-encryption-key-not-for-any-other-use"

put() { bao_run bao kv put "horecaos/production/$1" "value=$2" >>"${LOG_FILE}" 2>&1; }

put database/platform/migrator-password  "${DB_MIGRATOR_PW}"
put database/platform/app-password       "${DB_APP_PW}"
put database/keycloak/password           "${KEYCLOAK_DB_PW}"
put object_storage/platform/root-password "${MINIO_ROOT_PW}"
put data_encryption/platform/kek              "${KEK}"
put data_encryption/platform/handover-pepper  "${HANDOVER_PEPPER}"
# Cutting a corner real production does not: the media credential is the
# MinIO root credential rather than a bucket-scoped service account.
# production-setup.md creates the scoped account; this script does not,
# because creating one needs a running MinIO to ask, and proving that step
# works is exactly what the runbook's own "Check" does — recorded as a gap
# in this task's final report, not silently assumed to be equivalent.
put object_storage/platform/media-access-key  "${HORECAOS_MINIO_ROOT_USER:-horecaos-smoke-root}"
put object_storage/platform/media-secret-key  "${MINIO_ROOT_PW}"
# Matches horecaos-realm.json's own fallback default exactly (see that
# file's ${VAR:default} syntax) — this script does not rotate these secrets
# (step 6), so Keycloak is still issuing them, and the resolver must agree.
put identity_admin/keycloak/provisioning-secret "development-only-not-a-secret-provisioning"
put identity_admin/keycloak/reader-secret       "development-only-not-a-secret-reader"

check "OpenBao seeded with test values (never written to disk outside this script's own KV store)"

say "Issuing an AppRole secret-id and writing the four startup-secret files"
ROLE_ID="$(bao_run bao read -field=role_id auth/approle/role/horecaos-platform/role-id)"
SECRET_ID="$(bao_run bao write -field=secret_id -f auth/approle/role/horecaos-platform/secret-id)"

write_secret() { ( umask 133; printf '%s' "$2" > "${SECRET_DIR}/$1" ); chmod 0444 "${SECRET_DIR}/$1"; }
write_secret platform-db-migrator-password "${DB_MIGRATOR_PW}"
write_secret platform-db-app-password      "${DB_APP_PW}"
write_secret keycloak-db-password          "${KEYCLOAK_DB_PW}"
write_secret minio-root-password           "${MINIO_ROOT_PW}"
write_secret openbao-role-id               "${ROLE_ID}"
write_secret openbao-secret-id             "${SECRET_ID}"

export HORECAOS_MINIO_ROOT_USER="${HORECAOS_MINIO_ROOT_USER:-horecaos-smoke-root}"
export HORECAOS_REGISTRY="${REGISTRY}"
export HORECAOS_IMAGE_TAG="${TAG}"
export HORECAOS_FRONTEND_IMAGE_TAG="${TAG}"
export HORECAOS_POSTGRES_IMAGE_TAG="${TAG}"

# -----------------------------------------------------------------------------
# 4. Dependencies, then a fresh-volume migration, then the app
# -----------------------------------------------------------------------------

say "Starting platform-db, keycloak-db, kafka, minio, openbao-agent"
compose up -d platform-db keycloak-db kafka minio openbao-agent >>"${LOG_FILE}" 2>&1

wait_healthy platform-db 90    || die "platform-db never became healthy."
wait_healthy keycloak-db 60    || die "keycloak-db never became healthy."
wait_healthy kafka 90          || die "kafka never became healthy."
wait_healthy minio 60          || die "minio never became healthy."
wait_healthy openbao-agent 60  || die "openbao-agent never rendered the application's secrets."
check "platform-db, keycloak-db, kafka, minio, openbao-agent all healthy"

say "Applying migrations to a fresh volume"
export FLYWAY_PASSWORD="${DB_MIGRATOR_PW}"
compose run --rm platform-migrate migrate >>"${LOG_FILE}" 2>&1 \
    || die "Migration failed. See ${LOG_FILE}."
unset FLYWAY_PASSWORD
check "all migrations applied via the pinned platform-migrate image"

say "Auditing the application role (fails the run if a table has no GRANT)"
compose exec -T platform-db psql -U horecaos_migrator -d horecaos -v ON_ERROR_STOP=1 -q \
    < "${PLATFORM_DIR}/infra/production/audit-grants.sql" >>"${LOG_FILE}" 2>&1 \
    || die "The grant audit failed — a migration created a table the application role cannot read."
check "grant audit passed"

say "Importing the Keycloak realm (one-shot, never on every boot — see the Caddyfile's own comment on why)"
compose up -d keycloak-db >>"${LOG_FILE}" 2>&1
compose run --rm --no-TTY \
    --volume "${PLATFORM_DIR}/infra/keycloak/realm:/opt/keycloak/data/import:ro" \
    keycloak import --dir /opt/keycloak/data/import >>"${LOG_FILE}" 2>&1 \
    || die "Realm import failed. See ${LOG_FILE}."
check "realm imported"

say "Starting Keycloak, the application, the frontends, and the edge"
compose up -d keycloak >>"${LOG_FILE}" 2>&1
wait_healthy keycloak 150 || die "Keycloak never became healthy after import."
compose up -d edge storefront-web control-plane-web operations-web autoheal >>"${LOG_FILE}" 2>&1
compose up -d platform-app >>"${LOG_FILE}" 2>&1

wait_healthy platform-app 180 || die "platform-app never became healthy. See ${LOG_FILE} and platform/docs/runbooks/production-setup.md 'It did not come up'."
check "platform-app healthy"
wait_healthy edge 30 || die "edge never became healthy."
wait_healthy storefront-web 30 || fail "storefront-web never became healthy"
wait_healthy control-plane-web 30 || fail "control-plane-web never became healthy"
wait_healthy operations-web 30 || fail "operations-web never became healthy"

say "Full container status"
compose ps | tee -a "${LOG_FILE}"

# -----------------------------------------------------------------------------
# 5. Extract Caddy's internal CA so the checks below verify TLS for real,
#    rather than curling with -k and proving nothing about the certificate.
# -----------------------------------------------------------------------------

say "Fetching Caddy's internal CA root certificate"
for _ in $(seq 1 10); do
    if compose exec -T edge cat /data/caddy/pki/authorities/local/root.crt > "${CA_FILE}" 2>/dev/null && [ -s "${CA_FILE}" ]; then
        break
    fi
    sleep 2
done
[ -s "${CA_FILE}" ] || die "Caddy never wrote its internal CA root certificate."
check "internal CA extracted to ${CA_FILE}"

curl_smoke() {
    local host="$1" path="$2"
    curl -fsS --cacert "${CA_FILE}" --resolve "${host}:18443:127.0.0.1" \
        "https://${host}:18443${path}"
}

curl_smoke_status() {
    local host="$1" path="$2"
    curl -sS -o /dev/null -w '%{http_code}' --cacert "${CA_FILE}" \
        --resolve "${host}:18443:127.0.0.1" "https://${host}:18443${path}"
}

# -----------------------------------------------------------------------------
# 6. The smoke checklist
# -----------------------------------------------------------------------------

say "Smoke checklist"

READY="$(curl_smoke api.horecaos.test /actuator/health/readiness || true)"
if printf '%s' "${READY}" | grep -q '"status":"UP"'; then
    check "GET /actuator/health/readiness -> ${READY}  (TLS chain verified against Caddy's internal CA, not skipped)"
else
    fail "readiness probe did not answer UP: ${READY}"
fi

CUSTOMER="$(curl_smoke api.horecaos.test /actuator/health/customer || true)"
printf '    /actuator/health/customer -> %s\n' "${CUSTOMER}" | tee -a "${LOG_FILE}"

OIDC="$(curl_smoke auth.horecaos.test /realms/horecaos/.well-known/openid-configuration || true)"
if printf '%s' "${OIDC}" | grep -q '"issuer"'; then
    ISSUER="$(printf '%s' "${OIDC}" | jq -r .issuer 2>/dev/null || echo "?")"
    check "Keycloak realm discovery answers, issuer=${ISSUER}"
    [ "${ISSUER}" = "${HORECAOS_AUTH_ORIGIN:-https://auth.horecaos.test:18443}/realms/horecaos" ] \
        || fail "issuer (${ISSUER}) does not match HORECAOS_AUTH_ORIGIN"
else
    fail "Keycloak OIDC discovery did not answer: ${OIDC}"
fi

# The fixtureless answer. This stack ran only db/migration, never
# db/local-fixtures (that Flyway location activates under the `local`
# profile only — see platform/docs/local-fixtures.md) — so the demo
# tenant/brand/location ids below name rows that do not exist. What comes
# back here is captured verbatim into the log and this script's own report,
# because "what does empty production answer" is exactly the thing nobody
# should have to guess.
MENU_STATUS="$(curl_smoke_status api.horecaos.test "/api/v1/storefront/tenants/${HORECAOS_STOREFRONT_TENANT_ID}/brands/${HORECAOS_STOREFRONT_BRAND_ID}/locations/${HORECAOS_STOREFRONT_LOCATION_ID}/menu?locale=uz" || true)"
MENU_BODY="$(curl -sS --cacert "${CA_FILE}" --resolve api.horecaos.test:18443:127.0.0.1 \
    "https://api.horecaos.test:18443/api/v1/storefront/tenants/${HORECAOS_STOREFRONT_TENANT_ID}/brands/${HORECAOS_STOREFRONT_BRAND_ID}/locations/${HORECAOS_STOREFRONT_LOCATION_ID}/menu?locale=uz" || true)"
printf '    fixtureless menu fetch -> HTTP %s\n    body: %s\n' "${MENU_STATUS}" "${MENU_BODY}" | tee -a "${LOG_FILE}"

PICKUP_STATUS="$(curl_smoke_status api.horecaos.test "/api/v1/storefront/pickup-locations?lat=41.311341&lon=69.282722" || true)"
PICKUP_BODY="$(curl -sS --cacert "${CA_FILE}" --resolve api.horecaos.test:18443:127.0.0.1 \
    "https://api.horecaos.test:18443/api/v1/storefront/pickup-locations?lat=41.311341&lon=69.282722" || true)"
printf '    fixtureless pickup-locations fetch -> HTTP %s\n    body: %s\n' "${PICKUP_STATUS}" "${PICKUP_BODY}" | tee -a "${LOG_FILE}"

for pair in "storefront:storefront.horecaos.test" "control-plane:admin.horecaos.test" "operations:operations.horecaos.test"; do
    name="${pair%%:*}"; host="${pair##*:}"
    status="$(curl_smoke_status "${host}" "/" || true)"
    if [ "${status}" = "200" ]; then
        check "${name} frontend answers 200 at https://${host}:18443/"
    else
        fail "${name} frontend answered HTTP ${status}, not 200"
    fi
done

DOCS_STATUS="$(curl_smoke_status api.horecaos.test "/swagger-ui/index.html" || true)"
[ "${DOCS_STATUS}" != "200" ] && check "Swagger UI is disabled in production (HTTP ${DOCS_STATUS})" \
    || fail "Swagger UI answered 200 -- HORECAOS_SWAGGER_UI_ENABLED did not take effect"

ACTUATOR_STATUS="$(curl_smoke_status api.horecaos.test "/actuator/metrics" || true)"
[ "${ACTUATOR_STATUS}" = "404" ] && check "/actuator/metrics is not exposed publicly (404)" \
    || warn "/actuator/metrics answered HTTP ${ACTUATOR_STATUS}, expected 404 (see the Caddyfile's actuator allowlist)"

say "Container health, one more time, at the end of the run"
compose ps --format 'table {{.Name}}\t{{.Status}}' | tee -a "${LOG_FILE}"

if [ "${FAILURES}" -eq 0 ]; then
    say "SMOKE TEST PASSED — 0 failures. Full log: ${LOG_FILE} (deleted with the rest of ${WORK_DIR} on exit unless HORECAOS_SMOKE_KEEP=1)"
else
    die "SMOKE TEST FAILED — ${FAILURES} check(s) did not pass. See above and ${LOG_FILE}."
fi
