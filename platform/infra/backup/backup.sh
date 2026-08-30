#!/usr/bin/env bash
#
# ADR 0034 encrypted off-site backup.
#
# Colocation means nobody else is checking that this ran, that it produced
# something restorable, or that the copy left the building. The script therefore
# fails loudly rather than continuing, and always verifies what it uploaded.
#
# Encryption happens before upload, so the destination never holds readable
# data. Losing the passphrase loses the backups; it belongs in OpenBao and in a
# sealed offline copy, not in this repository.
#
# Two destinations, and the second one is the point. The local object store
# shares a building, a power feed and a disk controller with the database it is
# backing up: a copy that only exists there survives a `DROP TABLE` and nothing
# else. The off-site destination is therefore required rather than optional —
# see "Refusing to run local-only" below.

set -euo pipefail

: "${QOIDA_BACKUP_DB_URL:?set the PostgreSQL connection URL}"
: "${QOIDA_BACKUP_PASSPHRASE:?set the encryption passphrase}"
: "${QOIDA_BACKUP_BUCKET:=qoida-backups}"
: "${QOIDA_BACKUP_S3_ENDPOINT:=http://localhost:9000}"
: "${QOIDA_BACKUP_ACCESS_KEY:?set the primary object-store access key}"
: "${QOIDA_BACKUP_SECRET_KEY:?set the primary object-store secret key}"
: "${QOIDA_BACKUP_RETENTION_DAYS:=30}"

# The off-site destination. It has no default of any kind: a default endpoint is
# how this ended up pointing at the same MinIO it was backing up, and a default
# credential is a credential in a repository. The operator generates the key on
# the provider they chose and hands all four values in.
#
# The bucket name defaults to the primary's only because reusing one name across
# two providers is a naming convenience, not a location — the endpoint is what
# makes a copy off-site.
: "${QOIDA_BACKUP_OFFSITE_BUCKET:=${QOIDA_BACKUP_BUCKET}}"

# Refusing to run local-only.
#
# The alternative — warn, upload locally, exit zero — was rejected. The cron
# entry only records that this exited zero, the heartbeat only alerts on a
# backup that did not run, and a warning on stdout at 02:17 is read by nobody.
# Every one of those signals would have said the backups were healthy right up
# to the morning the building was gone. A failed backup is an alert tonight; a
# local-only backup is a discovery during the restore.
missing=()
for required in QOIDA_BACKUP_OFFSITE_ENDPOINT QOIDA_BACKUP_OFFSITE_ACCESS_KEY \
                QOIDA_BACKUP_OFFSITE_SECRET_KEY; do
  [ -n "${!required:-}" ] || missing+=("${required}")
done
if [ "${#missing[@]}" -gt 0 ]; then
  cat >&2 <<EOF
!! No off-site destination is configured, so this would produce a copy that
   lives on the machine it is backing up. That is not a backup. Nothing was
   dumped and nothing was uploaded.

   Missing: ${missing[*]}

   Set all three. The endpoint is an S3-compatible bucket outside the building;
   the credential is generated on that provider, scoped to that one bucket, and
   stored in OpenBao under object_storage — never written into a file here. The
   bucket needs versioning and object-lock or a retention rule, because a backup
   an attacker can delete is not a backup either.

   infra/backup/README.md, "Switching to a real destination", is the procedure.
EOF
  exit 1
fi

if [ "${QOIDA_BACKUP_OFFSITE_ENDPOINT}" = "${QOIDA_BACKUP_S3_ENDPOINT}" ]; then
  echo "!! The off-site endpoint is the primary endpoint. A second bucket on the" >&2
  echo "   same store shares the failure domain this copy exists to escape." >&2
  exit 1
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

dump="${workdir}/qoida-${timestamp}.dump"
encrypted="${dump}.enc"
object="$(basename "${encrypted}")"

echo "==> Dumping ${QOIDA_BACKUP_DB_URL%%\?*}"
# Custom format: parallel restore, selective restore, and it compresses.
pg_dump --format=custom --no-owner --no-privileges --file="${dump}" "${QOIDA_BACKUP_DB_URL}"

# A dump that restores nothing is worse than no dump, because it looks like a
# backup. Reading the table of contents proves the file is at least coherent.
echo "==> Verifying dump integrity"
pg_restore --list "${dump}" > "${workdir}/toc.txt"
table_count="$(grep -c 'TABLE DATA' "${workdir}/toc.txt" || true)"
if [ "${table_count}" -lt 1 ]; then
  echo "!! Dump contains no table data; refusing to upload" >&2
  exit 1
fi
echo "    ${table_count} tables present"

echo "==> Encrypting"
openssl enc -aes-256-cbc -pbkdf2 -iter 250000 -salt \
  -in "${dump}" -out "${encrypted}" -pass "pass:${QOIDA_BACKUP_PASSPHRASE}"

checksum="$(openssl dgst -sha256 -r "${encrypted}" | cut -d' ' -f1)"
echo "    sha256 ${checksum}"
echo "${checksum}  ${object}" > "${workdir}/checksum.txt"

mc alias set qoida-backup "${QOIDA_BACKUP_S3_ENDPOINT}" \
  "${QOIDA_BACKUP_ACCESS_KEY}" "${QOIDA_BACKUP_SECRET_KEY}" >/dev/null
mc alias set qoida-offsite "${QOIDA_BACKUP_OFFSITE_ENDPOINT}" \
  "${QOIDA_BACKUP_OFFSITE_ACCESS_KEY}" "${QOIDA_BACKUP_OFFSITE_SECRET_KEY}" >/dev/null

echo "==> Uploading to ${QOIDA_BACKUP_BUCKET}"
mc cp "${encrypted}" "qoida-backup/${QOIDA_BACKUP_BUCKET}/${object}"
mc cp "${workdir}/checksum.txt" "qoida-backup/${QOIDA_BACKUP_BUCKET}/${object}.sha256"

echo "==> Copying off-site to ${QOIDA_BACKUP_OFFSITE_ENDPOINT}/${QOIDA_BACKUP_OFFSITE_BUCKET}"
# Uploaded from the local file rather than mirrored from the primary bucket: a
# server-side copy would faithfully reproduce a truncated primary object, and
# the whole point of the second destination is that it does not depend on the
# first one being intact.
mc cp "${encrypted}" "qoida-offsite/${QOIDA_BACKUP_OFFSITE_BUCKET}/${object}"
mc cp "${workdir}/checksum.txt" "qoida-offsite/${QOIDA_BACKUP_OFFSITE_BUCKET}/${object}.sha256"

# Read it back rather than trusting the upload. A silently truncated object is
# indistinguishable from a good one until the day it is needed.
#
# Read back the OFF-SITE copy, not the local one, and match what
# rehearse-restore.sh does: verifying the local copy proves nothing about the
# one that survives losing the primary, and it is the off-site copy that crosses
# a network long enough for a transfer to end early. It costs one download of
# the dump per night, which is the cheapest evidence available that the copy
# outside the building is readable.
echo "==> Verifying the off-site copy"
mc cp "qoida-offsite/${QOIDA_BACKUP_OFFSITE_BUCKET}/${object}" \
  "${workdir}/roundtrip.enc" >/dev/null
uploaded_checksum="$(openssl dgst -sha256 -r "${workdir}/roundtrip.enc" | cut -d' ' -f1)"
if [ "${uploaded_checksum}" != "${checksum}" ]; then
  echo "!! The off-site object does not match the local checksum" >&2
  exit 1
fi
echo "    off-site copy matches"

echo "==> Expiring copies older than ${QOIDA_BACKUP_RETENTION_DAYS} days"
mc rm --recursive --force --older-than "${QOIDA_BACKUP_RETENTION_DAYS}d" \
  "qoida-backup/${QOIDA_BACKUP_BUCKET}/" || true
# Off-site expiry is best-effort and deliberately never fatal: a bucket whose
# object-lock rule refuses the delete is the bucket behaving correctly, and a
# backup that succeeded must not be reported as failed because the housekeeping
# after it was denied.
mc rm --recursive --force --older-than "${QOIDA_BACKUP_RETENTION_DAYS}d" \
  "qoida-offsite/${QOIDA_BACKUP_OFFSITE_BUCKET}/" || true

echo "==> Done: ${object} (local and off-site)"
