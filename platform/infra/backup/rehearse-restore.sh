#!/usr/bin/env bash
#
# ADR 0034 restore rehearsal.
#
# Runs the real path — dump, verify, encrypt, upload, download, decrypt,
# restore — into a scratch database, then compares row counts against the
# source. This is what converts "we have backups" into a measured recovery
# time, and it is meant to run on a schedule rather than once.
#
# Everything runs in containers on the compose network, so it needs no local
# PostgreSQL client and exercises the same images a scheduled job would.

set -euo pipefail

PROJECT="${COMPOSE_PROJECT_NAME:-horecaos-platform}"
NETWORK="${PROJECT}_default"
PG_IMAGE="postgres:18"
# AWS CLI, not `mc` (ADR 0135, 2026-09-25): MinIO's own client image is gone
# from every registry the same way the server image is. Pinned by digest,
# same as every other third-party image the migration touched.
AWS_CLI_IMAGE="amazon/aws-cli:2.37.3@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5"
WORK_VOLUME="horecaos-backup-rehearsal"

SOURCE_URL="postgresql://horecaos:horecaos@platform-db:5432/horecaos"
ADMIN_URL="postgresql://horecaos:horecaos@platform-db:5432/postgres"
TARGET_DB="horecaos_restore_rehearsal"
TARGET_URL="postgresql://horecaos:horecaos@platform-db:5432/${TARGET_DB}"
PASSPHRASE="${HORECAOS_BACKUP_PASSPHRASE:-local-rehearsal-passphrase}"
BUCKET="${HORECAOS_BACKUP_BUCKET:-horecaos-backups}"

# The primary and off-site destinations. Locally these are platform/
# compose.yaml's own object-store and object-store-offsite (RustFS, ADR
# 0135); in production the off-site one is a real bucket elsewhere.
# Everything between here and the restore is identical either way, which is
# the point.
PRIMARY_ENDPOINT="${HORECAOS_BACKUP_S3_ENDPOINT:-http://object-store:9000}"
PRIMARY_ACCESS_KEY="${HORECAOS_BACKUP_ACCESS_KEY:-horecaos}"
PRIMARY_SECRET_KEY="${HORECAOS_BACKUP_SECRET_KEY:-horecaos-local-secret}"
OFFSITE_ENDPOINT="${HORECAOS_BACKUP_OFFSITE_ENDPOINT:-http://object-store-offsite:9000}"
OFFSITE_ACCESS_KEY="${HORECAOS_BACKUP_OFFSITE_ACCESS_KEY:-horecaos}"
OFFSITE_SECRET_KEY="${HORECAOS_BACKUP_OFFSITE_SECRET_KEY:-horecaos-offsite-secret}"
OBJECT="horecaos-$(date -u +%Y%m%dT%H%M%SZ).dump.enc"

cleanup() { docker volume rm -f "${WORK_VOLUME}" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker volume create "${WORK_VOLUME}" >/dev/null

pg() {
  docker run --rm --network "${NETWORK}" -v "${WORK_VOLUME}:/work" \
    -e PGPASSWORD=horecaos -e PASSPHRASE="${PASSPHRASE}" \
    --entrypoint bash "${PG_IMAGE}" -c "$1"
}

awscli() {
  docker run --rm --network "${NETWORK}" -v "${WORK_VOLUME}:/work" \
    --entrypoint sh "${AWS_CLI_IMAGE}" -c "$1"
}

echo "==> Baseline"
BASELINE="$(pg "psql '${SOURCE_URL}' -qAt -c \"
  SELECT (SELECT count(*) FROM tenant.tenants) || ',' ||
         (SELECT count(*) FROM audit.audit_events) || ',' ||
         (SELECT count(*) FROM flyway_schema_history WHERE success);\"" | tr -d '[:space:]')"
echo "    tenants,audit_events,migrations = ${BASELINE}"

echo "==> Dump and verify"
pg "set -e
    pg_dump --format=custom --no-owner --no-privileges --file=/work/db.dump '${SOURCE_URL}'
    tables=\$(pg_restore --list /work/db.dump | grep -c 'TABLE DATA' || true)
    # A dump that restores nothing is worse than none, because it looks like a backup.
    [ \"\$tables\" -ge 1 ] || { echo 'no table data in dump' >&2; exit 1; }
    echo \"    \$tables tables\""

echo "==> Encrypt"
pg "set -e
    openssl enc -aes-256-cbc -pbkdf2 -iter 250000 -salt \
      -in /work/db.dump -out /work/db.enc -pass \"pass:\${PASSPHRASE}\"
    sha256sum /work/db.enc | cut -d' ' -f1 > /work/before.sha
    echo \"    sha256 \$(cat /work/before.sha)\""

echo "==> Upload, replicate off-site, and read back from off-site"
awscli "set -e
     export AWS_DEFAULT_REGION=us-east-1 AWS_EC2_METADATA_DISABLED=true

     export AWS_ACCESS_KEY_ID=${PRIMARY_ACCESS_KEY} AWS_SECRET_ACCESS_KEY=${PRIMARY_SECRET_KEY}
     aws --endpoint-url ${PRIMARY_ENDPOINT} s3api create-bucket --bucket ${BUCKET} >/dev/null 2>&1 || true
     aws --endpoint-url ${PRIMARY_ENDPOINT} s3 cp /work/db.enc s3://${BUCKET}/${OBJECT} >/dev/null

     export AWS_ACCESS_KEY_ID=${OFFSITE_ACCESS_KEY} AWS_SECRET_ACCESS_KEY=${OFFSITE_SECRET_KEY}
     aws --endpoint-url ${OFFSITE_ENDPOINT} s3api create-bucket --bucket ${BUCKET} >/dev/null 2>&1 || true
     aws --endpoint-url ${OFFSITE_ENDPOINT} s3 cp /work/db.enc s3://${BUCKET}/${OBJECT} >/dev/null

     # Read back from the OFF-SITE copy, not the local one. Verifying the local
     # copy would prove nothing about the one that survives losing the primary.
     aws --endpoint-url ${OFFSITE_ENDPOINT} s3 cp s3://${BUCKET}/${OBJECT} /work/roundtrip.enc >/dev/null
     sha256sum /work/roundtrip.enc | cut -d' ' -f1 > /work/after.sha
     before=\$(cat /work/before.sha)
     after=\$(cat /work/after.sha)
     [ \"\$before\" = \"\$after\" ] || { echo 'off-site object differs from local' >&2; exit 1; }
     echo '    off-site copy matches'"

echo "==> Restore from the off-site copy into a scratch database"
pg "set -e
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 250000 \
      -in /work/roundtrip.enc -out /work/restored.dump -pass \"pass:\${PASSPHRASE}\"
    psql '${ADMIN_URL}' -qAt -c 'DROP DATABASE IF EXISTS ${TARGET_DB}' >/dev/null
    psql '${ADMIN_URL}' -qAt -c 'CREATE DATABASE ${TARGET_DB}' >/dev/null
    pg_restore --no-owner --no-privileges --dbname='${TARGET_URL}' /work/restored.dump
    echo '    restored'"

echo "==> Verify the restored database"
RESTORED="$(pg "psql '${TARGET_URL}' -qAt -c \"
  SELECT (SELECT count(*) FROM tenant.tenants) || ',' ||
         (SELECT count(*) FROM audit.audit_events) || ',' ||
         (SELECT count(*) FROM flyway_schema_history WHERE success);\"" | tr -d '[:space:]')"

echo
echo "    baseline : ${BASELINE}"
echo "    restored : ${RESTORED}"

if [ "${BASELINE}" = "${RESTORED}" ]; then
  echo
  echo "==> REHEARSAL PASSED — restored from the off-site copy, matches the source"
  pg "psql '${ADMIN_URL}' -qAt -c 'DROP DATABASE IF EXISTS ${TARGET_DB}'" >/dev/null
else
  echo "!! REHEARSAL FAILED — counts differ; scratch database ${TARGET_DB} left for inspection" >&2
  exit 1
fi
