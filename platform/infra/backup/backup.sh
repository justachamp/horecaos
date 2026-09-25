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

: "${HORECAOS_BACKUP_DB_URL:?set the PostgreSQL connection URL}"
: "${HORECAOS_BACKUP_PASSPHRASE:?set the encryption passphrase}"
: "${HORECAOS_BACKUP_BUCKET:=horecaos-backups}"
: "${HORECAOS_BACKUP_S3_ENDPOINT:=http://localhost:9000}"
: "${HORECAOS_BACKUP_ACCESS_KEY:?set the primary object-store access key}"
: "${HORECAOS_BACKUP_SECRET_KEY:?set the primary object-store secret key}"
: "${HORECAOS_BACKUP_RETENTION_DAYS:=30}"

# The off-site destination. It has no default of any kind: a default endpoint is
# how this ended up pointing at the same MinIO it was backing up, and a default
# credential is a credential in a repository. The operator generates the key on
# the provider they chose and hands all four values in.
#
# The bucket name defaults to the primary's only because reusing one name across
# two providers is a naming convenience, not a location — the endpoint is what
# makes a copy off-site.
: "${HORECAOS_BACKUP_OFFSITE_BUCKET:=${HORECAOS_BACKUP_BUCKET}}"

# Refusing to run local-only.
#
# The alternative — warn, upload locally, exit zero — was rejected. The cron
# entry only records that this exited zero, the heartbeat only alerts on a
# backup that did not run, and a warning on stdout at 02:17 is read by nobody.
# Every one of those signals would have said the backups were healthy right up
# to the morning the building was gone. A failed backup is an alert tonight; a
# local-only backup is a discovery during the restore.
missing=()
for required in HORECAOS_BACKUP_OFFSITE_ENDPOINT HORECAOS_BACKUP_OFFSITE_ACCESS_KEY \
                HORECAOS_BACKUP_OFFSITE_SECRET_KEY; do
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

if [ "${HORECAOS_BACKUP_OFFSITE_ENDPOINT}" = "${HORECAOS_BACKUP_S3_ENDPOINT}" ]; then
  echo "!! The off-site endpoint is the primary endpoint. A second bucket on the" >&2
  echo "   same store shares the failure domain this copy exists to escape." >&2
  exit 1
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

dump="${workdir}/horecaos-${timestamp}.dump"
encrypted="${dump}.enc"
object="$(basename "${encrypted}")"

echo "==> Dumping ${HORECAOS_BACKUP_DB_URL%%\?*}"
# Custom format: parallel restore, selective restore, and it compresses.
pg_dump --format=custom --no-owner --no-privileges --file="${dump}" "${HORECAOS_BACKUP_DB_URL}"

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
  -in "${dump}" -out "${encrypted}" -pass "pass:${HORECAOS_BACKUP_PASSPHRASE}"

checksum="$(openssl dgst -sha256 -r "${encrypted}" | cut -d' ' -f1)"
echo "    sha256 ${checksum}"
echo "${checksum}  ${object}" > "${workdir}/checksum.txt"

# AWS CLI, not `mc` (ADR 0135, 2026-09-25): MinIO's own client image is gone
# from every registry the same way the server image is. There is no `mc
# alias set` equivalent to reach for in its place -- the AWS CLI takes an
# endpoint and a pair of credentials on every call instead of remembering
# them under a name -- so each site gets its own small wrapper function
# instead of one `alias set` up front. That is also strictly less exposed
# than what it replaces: `mc alias set <name> <endpoint> <key> <secret>`
# puts the secret key in argv, readable from `ps` for as long as the alias
# lives; AWS_SECRET_ACCESS_KEY below is process environment, which does not
# appear in argv at all.
#
# us-east-1 rather than a configurable region: both destinations are
# self-hosted S3-compatible stores today (ADR 0034/0135), which do not
# enforce SigV4's region matching the way a real AWS bucket in a named
# region would. Revisit if HORECAOS_BACKUP_OFFSITE_ENDPOINT is ever pointed
# at real AWS S3 outside us-east-1.
aws_backup()  {
  AWS_ACCESS_KEY_ID="${HORECAOS_BACKUP_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HORECAOS_BACKUP_SECRET_KEY}" \
  AWS_DEFAULT_REGION=us-east-1 AWS_EC2_METADATA_DISABLED=true \
  aws --endpoint-url "${HORECAOS_BACKUP_S3_ENDPOINT}" "$@"
}
aws_offsite() {
  AWS_ACCESS_KEY_ID="${HORECAOS_BACKUP_OFFSITE_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HORECAOS_BACKUP_OFFSITE_SECRET_KEY}" \
  AWS_DEFAULT_REGION=us-east-1 AWS_EC2_METADATA_DISABLED=true \
  aws --endpoint-url "${HORECAOS_BACKUP_OFFSITE_ENDPOINT}" "$@"
}

# Prunes objects whose LastModified is older than the given number of days.
# `mc rm --older-than` had no single AWS CLI equivalent, so this is
# list-objects-v2 filtered by LastModified (a JMESPath string comparison,
# which is correct here because every timestamp S3 returns is the same
# zero-padded ISO 8601/UTC shape, so lexicographic order is chronological
# order) followed by a batch delete-objects. One call, so it prunes at most
# 1000 objects a night -- the S3 API's own limit on a single delete-objects
# batch -- which is not a limit this backup job is expected to reach.
prune_older_than() {
  local wrapper="$1" bucket="$2" days="$3" cutoff keys count payload
  cutoff="$(date -u -d "@$(( $(date -u +%s) - days * 86400 ))" +%Y-%m-%dT%H:%M:%SZ)"
  keys="$("${wrapper}" s3api list-objects-v2 --bucket "${bucket}" \
    --query "Contents[?LastModified<='${cutoff}'].Key" --output json)"
  count="$(printf '%s' "${keys}" | jq 'length')"
  if [ "${count}" -eq 0 ]; then
    echo "    nothing older than ${days}d in ${bucket}"
    return 0
  fi
  payload="${workdir}/prune-${bucket}.json"
  printf '%s' "${keys}" | jq '{Objects: (map({Key: .})), Quiet: true}' > "${payload}"
  "${wrapper}" s3api delete-objects --bucket "${bucket}" --delete "file://${payload}" >/dev/null
  echo "    deleted ${count} object(s) older than ${days}d from ${bucket}"
}

echo "==> Uploading to ${HORECAOS_BACKUP_BUCKET}"
aws_backup s3 cp "${encrypted}" "s3://${HORECAOS_BACKUP_BUCKET}/${object}"
aws_backup s3 cp "${workdir}/checksum.txt" "s3://${HORECAOS_BACKUP_BUCKET}/${object}.sha256"

echo "==> Copying off-site to ${HORECAOS_BACKUP_OFFSITE_ENDPOINT}/${HORECAOS_BACKUP_OFFSITE_BUCKET}"
# Uploaded from the local file rather than mirrored from the primary bucket: a
# server-side copy would faithfully reproduce a truncated primary object, and
# the whole point of the second destination is that it does not depend on the
# first one being intact.
aws_offsite s3 cp "${encrypted}" "s3://${HORECAOS_BACKUP_OFFSITE_BUCKET}/${object}"
aws_offsite s3 cp "${workdir}/checksum.txt" "s3://${HORECAOS_BACKUP_OFFSITE_BUCKET}/${object}.sha256"

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
aws_offsite s3 cp "s3://${HORECAOS_BACKUP_OFFSITE_BUCKET}/${object}" \
  "${workdir}/roundtrip.enc" >/dev/null
uploaded_checksum="$(openssl dgst -sha256 -r "${workdir}/roundtrip.enc" | cut -d' ' -f1)"
if [ "${uploaded_checksum}" != "${checksum}" ]; then
  echo "!! The off-site object does not match the local checksum" >&2
  exit 1
fi
echo "    off-site copy matches"

echo "==> Expiring copies older than ${HORECAOS_BACKUP_RETENTION_DAYS} days"
prune_older_than aws_backup "${HORECAOS_BACKUP_BUCKET}" "${HORECAOS_BACKUP_RETENTION_DAYS}" || true
# Off-site expiry is best-effort and deliberately never fatal: a bucket whose
# object-lock rule refuses the delete is the bucket behaving correctly, and a
# backup that succeeded must not be reported as failed because the housekeeping
# after it was denied.
prune_older_than aws_offsite "${HORECAOS_BACKUP_OFFSITE_BUCKET}" "${HORECAOS_BACKUP_RETENTION_DAYS}" || true

echo "==> Done: ${object} (local and off-site)"
