#!/usr/bin/env bash
#
# ADR 0034 restore.
#
# This is the half that matters. A backup nobody has restored is a belief, not a
# capability, and the point of running this on a schedule is to convert it into a
# known recovery time.
#
# Restores into a NEW database. It never targets the live one: a restore run
# against production during an incident is how a bad day becomes a worse one.
# That sentence used to be the whole of the enforcement, and a comment does not
# stop a paste at 3am — the guard below does.

set -euo pipefail

: "${QOIDA_RESTORE_TARGET_URL:?set the target PostgreSQL URL (must not be production)}"
: "${QOIDA_BACKUP_PASSPHRASE:?set the encryption passphrase}"
: "${QOIDA_BACKUP_BUCKET:=qoida-backups}"
: "${QOIDA_BACKUP_S3_ENDPOINT:=http://localhost:9000}"

# The names this script will not restore over, comma separated. `pg_restore
# --clean --if-exists` drops every object it is about to recreate, so aiming it
# at the live database is not a mistake anything recovers from: the drops commit
# whether or not the restore then succeeds.
#
# docs/runbooks/restore.md restores into `qoida_restore` and swaps it in with
# ALTER DATABASE ... RENAME, so nothing in the documented recovery path needs
# this list widened. Changing it is the escape hatch, and changing it is a
# deliberate act rather than a paste.
: "${QOIDA_RESTORE_FORBIDDEN_DATABASES:=qoida}"

# Asked of the server rather than parsed out of the URL. A connection string can
# reach production through a hostname alias, an SSH tunnel, a pooler, or simply
# by omitting the database and defaulting to the role name — a string match on
# the text of the URL passes all four. `current_database()` is what will actually
# be dropped.
echo "==> Checking what the target URL really points at"
target_database="$(psql "${QOIDA_RESTORE_TARGET_URL}" -Atqc 'SELECT current_database()')" || {
  echo "!! Cannot reach ${QOIDA_RESTORE_TARGET_URL%%\?*}. Nothing was changed." >&2
  exit 1
}
[ -n "${target_database}" ] || { echo "!! The target server named no database." >&2; exit 1; }

for forbidden in ${QOIDA_RESTORE_FORBIDDEN_DATABASES//,/ }; do
  [ "${target_database}" != "${forbidden}" ] || {
    cat >&2 <<EOF
!! Refusing to restore into "${target_database}".

   This is the live database. pg_restore --clean drops every object it is about
   to recreate, and it does that before it knows whether the dump is any good.

   Restore into a new database and swap it in instead:

       CREATE DATABASE qoida_restore OWNER qoida_migrator;
       QOIDA_RESTORE_TARGET_URL=postgresql://qoida_migrator@platform-db:5432/qoida_restore

   docs/runbooks/restore.md, section 3, is the whole procedure.
EOF
    exit 1; }
done

object="${1:-}"
workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

mc alias set qoida-backup "${QOIDA_BACKUP_S3_ENDPOINT}" \
  "${QOIDA_BACKUP_ACCESS_KEY}" "${QOIDA_BACKUP_SECRET_KEY}" >/dev/null

if [ -z "${object}" ]; then
  echo "==> Selecting the most recent backup"
  object="$(mc ls "qoida-backup/${QOIDA_BACKUP_BUCKET}/" \
    | grep '\.dump\.enc$' | sort | tail -1 | awk '{print $NF}')"
fi
[ -n "${object}" ] || { echo "!! No backup found" >&2; exit 1; }
echo "    ${object}"

echo "==> Downloading and checking the stored checksum"
mc cp "qoida-backup/${QOIDA_BACKUP_BUCKET}/${object}" "${workdir}/backup.enc" >/dev/null
if mc cp "qoida-backup/${QOIDA_BACKUP_BUCKET}/${object}.sha256" \
     "${workdir}/checksum.txt" >/dev/null 2>&1; then
  expected="$(cut -d' ' -f1 < "${workdir}/checksum.txt")"
  actual="$(openssl dgst -sha256 -r "${workdir}/backup.enc" | cut -d' ' -f1)"
  [ "${expected}" = "${actual}" ] || { echo "!! Checksum mismatch" >&2; exit 1; }
  echo "    checksum matches"
fi

echo "==> Decrypting"
# The passphrase reaches openssl on a file descriptor. `-pass pass:...` puts it
# in the argument list, where every process on the host can read it out of `ps`
# for as long as the decrypt runs — and a restore runs for minutes, on a machine
# that is having an incident and may well have somebody else's shell on it.
# `env:` only moves it to /proc/<pid>/environ, and `file:` would put it on a disk
# this script does not control the lifetime of.
openssl enc -d -aes-256-cbc -pbkdf2 -iter 250000 \
  -in "${workdir}/backup.enc" -out "${workdir}/backup.dump" \
  -pass fd:3 3< <(printf '%s' "${QOIDA_BACKUP_PASSPHRASE}")

echo "==> Restoring into ${QOIDA_RESTORE_TARGET_URL%%\?*}"
pg_restore --clean --if-exists --no-owner --no-privileges \
  --dbname="${QOIDA_RESTORE_TARGET_URL}" "${workdir}/backup.dump"

# Restoring without error is not the same as restoring something usable. These
# checks are the difference between "the command exited zero" and "the platform
# could actually run on this".
echo "==> Verifying the restored database"
psql "${QOIDA_RESTORE_TARGET_URL}" -v ON_ERROR_STOP=1 -qAt <<'SQL'
SELECT 'flyway history: ' || count(*) FROM flyway_schema_history WHERE success;
SELECT 'schemas: ' || count(*) FROM information_schema.schemata
 WHERE schema_name IN ('tenant','iam','integration','audit','ordering','reporting');
SELECT 'tenants: ' || count(*) FROM tenant.tenants;
SELECT 'audit events: ' || count(*) FROM audit.audit_events;
SQL

echo "==> Restore verified"
