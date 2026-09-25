#!/usr/bin/env bash
#
# Copy platform object-store data from one S3-compatible endpoint to another
# — written for the MinIO -> RustFS migration (ADR 0135) but not specific to
# either product, because AGENTS.md says object storage stays S3-compatible,
# never S3-specific, and this script is the proof of that.
#
# This implements steps 3-5 of
# platform/docs/runbooks/object-store-migration.md: the media bucket, the
# audit archive (preserving Object Lock retention per version), and the
# backups bucket. Read that runbook before running this; it says what has to
# be true before step 3 starts and what to do with the result afterwards.
#
# Safe to re-run:
#   - media and backups use "aws s3 sync" through a local staging directory —
#     the AWS CLI takes one --endpoint-url per invocation, so a single "sync"
#     command cannot read from one S3-compatible endpoint and write to
#     another; two syncs through a shared local directory is the standard
#     workaround, and each hop only transfers what changed;
#   - the audit-archive loop tags every object it writes with
#     x-amz-meta-source-version-id and, before copying a source version,
#     asks the target whether a version carrying that tag already exists
#     for the same key. If one does, the source version is skipped, not
#     re-uploaded as a duplicate.
#
# Nothing here reads a credential from a command-line argument or prints one.
# Every AWS CLI call runs inside a container from the pinned
# amazon/aws-cli image, credentials arrive only through --env-file (a
# temporary, mode-0600 file this script writes and removes), and the
# temporary files this script does leave behind (in a mode-0700 scratch
# directory) hold object bytes and metadata, never a key.
#
# Usage:
#   export SOURCE_ENDPOINT=http://minio-legacy:9000
#   export TARGET_ENDPOINT=http://minio:9000
#   export MIGRATE_DOCKER_NETWORK=horecaos-production_core
#   export SOURCE_ACCESS_KEY_FILE=/run/horecaos/secrets/minio-legacy-access-key
#   export SOURCE_SECRET_KEY_FILE=/run/horecaos/secrets/minio-legacy-secret-key
#   export TARGET_ACCESS_KEY_FILE=/run/horecaos/secrets/media-access-key
#   export TARGET_SECRET_KEY_FILE=/run/horecaos/secrets/media-secret-key
#   ./migrate-object-store.sh --dry-run
#   ./migrate-object-store.sh
#
# Run --help for the full option and environment-variable list.

set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

# rustfs-d-migration-runbook, 2026-09-25: docker.io/rustfs/rustfs:1.0.0's own
# manifest, pinned by the digest the orchestrator verified against a running
# pull. This script never touches the object-store image directly — it only
# ever pulls the AWS CLI — but the pin lives here as the record of what this
# script was proven against.
: "${RUSTFS_IMAGE_REFERENCE:=rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff}"

AWS_CLI_IMAGE="${AWS_CLI_IMAGE:-amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5}"

MEDIA_BUCKET="${MEDIA_BUCKET:-horecaos-media}"
AUDIT_ARCHIVE_BUCKET="${AUDIT_ARCHIVE_BUCKET:-horecaos-audit-archive}"
BACKUP_BUCKET="${BACKUP_BUCKET:-horecaos-backups}"
AWS_REGION_VALUE="${AWS_REGION:-us-east-1}"

DRY_RUN=0
RUN_MEDIA=1
RUN_AUDIT=1
RUN_BACKUPS=1
VERIFY_ONLY=0
KEEP_WORK_DIR="${MIGRATE_KEEP_WORK_DIR:-0}"

usage() {
  cat <<'USAGE'
Usage: migrate-object-store.sh [options]

Copies object-store data from SOURCE_ENDPOINT to TARGET_ENDPOINT: media
(step 3), the audit archive with Object Lock retention preserved per version
(step 4), and backups (step 5) of
platform/docs/runbooks/object-store-migration.md. Verifies object-count and
byte parity after each bucket, and full retention equality after the audit
archive. Exits non-zero if anything was skipped that should not have been,
or if a verification check fails.

Required environment (never pass these as arguments):
  SOURCE_ENDPOINT              http://host:port of the old object store
  TARGET_ENDPOINT              http://host:port of the new object store
  MIGRATE_DOCKER_NETWORK       docker network both endpoints are reachable on
  SOURCE_ACCESS_KEY (or _FILE) and SOURCE_SECRET_KEY (or _FILE)
  TARGET_ACCESS_KEY (or _FILE) and TARGET_SECRET_KEY (or _FILE)

  The "_FILE" form takes a path and reads the value from it (one line, no
  trailing newline required) — for a secret materialised by
  infra/production/ops/bao-get.sh or mounted the way
  docs/runbooks/production-setup.md describes. Never set both a value and
  its _FILE variant for the same name.

Optional environment:
  MEDIA_BUCKET            default horecaos-media
  AUDIT_ARCHIVE_BUCKET    default horecaos-audit-archive
  BACKUP_BUCKET           default horecaos-backups
  AWS_REGION              default us-east-1
  AWS_CLI_IMAGE           default the pinned amazon/aws-cli digest below
  MIGRATE_KEEP_WORK_DIR   1 = do not delete the scratch directory on exit
                          (it holds no credential; useful for debugging a
                          failed run)

Options:
  --dry-run              Perform every read (list, download, retention
                         lookup) for real, so the report is accurate, but
                         never write to TARGET_ENDPOINT.
  --skip-media           Skip step 3.
  --skip-audit-archive   Skip step 4.
  --skip-backups         Skip step 5.
  --verify-only          Skip all copying; only run the verification checks
                         against whatever is already on both endpoints.
  -h, --help             This.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --skip-media) RUN_MEDIA=0 ;;
    --skip-audit-archive) RUN_AUDIT=0 ;;
    --skip-backups) RUN_BACKUPS=0 ;;
    --verify-only) VERIFY_ONLY=1 ;;
    -h|--help) usage; exit 0 ;;
    *) printf '!!  Unknown argument: %s\n' "$1" >&2; usage >&2; exit 1 ;;
  esac
  shift
done

: "${SOURCE_ENDPOINT:?set SOURCE_ENDPOINT, e.g. http://minio-legacy:9000}"
: "${TARGET_ENDPOINT:?set TARGET_ENDPOINT, e.g. http://minio:9000}"
: "${MIGRATE_DOCKER_NETWORK:?set MIGRATE_DOCKER_NETWORK to the docker network both endpoints are reachable on}"

# -----------------------------------------------------------------------------
# Small helpers
# -----------------------------------------------------------------------------

say()  { printf '\n==> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '!!  %s\n' "$*" >&2; }
die()  { warn "$*"; exit 1; }

# Reads NAME from the environment, or from the file named by NAME_FILE if
# NAME itself is unset or empty. Never prints the value it returns; the
# caller is responsible for keeping it out of anything that gets logged.
resolve_secret() {
  local name="$1" file_name value file_path
  file_name="${name}_FILE"
  value="${!name:-}"
  if [ -n "${value}" ]; then
    printf '%s' "${value}"
    return 0
  fi
  file_path="${!file_name:-}"
  if [ -n "${file_path}" ]; then
    [ -r "${file_path}" ] || die "${file_path} (from \${${file_name}}) is not readable"
    # Command substitution already strips one trailing newline, which is
    # exactly the shape bao-get.sh and every mounted secret file in this
    # repo already uses.
    value="$(cat "${file_path}")"
    [ -n "${value}" ] || die "${file_path} (from \${${file_name}}) is empty"
    printf '%s' "${value}"
    return 0
  fi
  die "Set \${${name}} or \${${file_name}} (a path to a file holding the value)"
}

# -----------------------------------------------------------------------------
# Scratch directory and credential files — created before anything else runs
# so the EXIT trap can always find them, deleted (secrets first) no matter
# how the script leaves.
# -----------------------------------------------------------------------------

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/horecaos-object-store-migration.XXXXXX")"
chmod 0700 "${WORK_DIR}"
SOURCE_CRED_FILE="${WORK_DIR}/.source.env"
TARGET_CRED_FILE="${WORK_DIR}/.target.env"

cleanup() {
  local status=$?
  rm -f "${SOURCE_CRED_FILE}" "${TARGET_CRED_FILE}"
  if [ "${KEEP_WORK_DIR}" = "1" ]; then
    warn "MIGRATE_KEEP_WORK_DIR=1: leaving ${WORK_DIR} in place (credentials already removed)."
  else
    rm -rf "${WORK_DIR}"
  fi
  exit "${status}"
}
trap cleanup EXIT

write_cred_file() {
  local path="$1" endpoint="$2" access_key="$3" secret_key="$4"
  ( umask 077
    {
      printf 'AWS_ACCESS_KEY_ID=%s\n' "${access_key}"
      printf 'AWS_SECRET_ACCESS_KEY=%s\n' "${secret_key}"
      printf 'AWS_DEFAULT_REGION=%s\n' "${AWS_REGION_VALUE}"
      printf 'AWS_ENDPOINT_URL=%s\n' "${endpoint}"
      printf 'AWS_EC2_METADATA_DISABLED=true\n'
    } > "${path}"
  )
  chmod 0600 "${path}"
}

write_cred_file "${SOURCE_CRED_FILE}" "${SOURCE_ENDPOINT}" \
  "$(resolve_secret SOURCE_ACCESS_KEY)" "$(resolve_secret SOURCE_SECRET_KEY)"
write_cred_file "${TARGET_CRED_FILE}" "${TARGET_ENDPOINT}" \
  "$(resolve_secret TARGET_ACCESS_KEY)" "$(resolve_secret TARGET_SECRET_KEY)"

# -----------------------------------------------------------------------------
# Docker CLI wrappers. Every one runs one AWS CLI invocation in a throwaway
# container on MIGRATE_DOCKER_NETWORK, with the scratch directory bind-mounted
# at /work. "_sh" variants run one of the helper scripts written into
# /work below instead of the aws binary directly, for the few operations
# (list-then-filter, retention lookup) that need more than one AWS CLI call.
# -----------------------------------------------------------------------------

aws_source() {
  docker run --rm --network "${MIGRATE_DOCKER_NETWORK}" \
    --env-file "${SOURCE_CRED_FILE}" -v "${WORK_DIR}:/work" -w /work \
    "${AWS_CLI_IMAGE}" "$@"
}

aws_target() {
  docker run --rm --network "${MIGRATE_DOCKER_NETWORK}" \
    --env-file "${TARGET_CRED_FILE}" -v "${WORK_DIR}:/work" -w /work \
    "${AWS_CLI_IMAGE}" "$@"
}

sh_source() {
  docker run --rm --network "${MIGRATE_DOCKER_NETWORK}" \
    --env-file "${SOURCE_CRED_FILE}" -v "${WORK_DIR}:/work" -w /work \
    --entrypoint sh "${AWS_CLI_IMAGE}" "$@"
}

sh_target() {
  docker run --rm --network "${MIGRATE_DOCKER_NETWORK}" \
    --env-file "${TARGET_CRED_FILE}" -v "${WORK_DIR}:/work" -w /work \
    --entrypoint sh "${AWS_CLI_IMAGE}" "$@"
}

# -----------------------------------------------------------------------------
# Helper scripts run inside the AWS CLI container (it ships jq and /bin/sh).
# Written once, up front, to /work so both the source- and target-context
# containers can read them from the same bind mount. Every value they need
# arrives as a positional argument or through the container's own
# environment — never interpolated from this script's variables into the
# text below, so there is no quoting hazard between the two shells.
# -----------------------------------------------------------------------------

mkdir -p "${WORK_DIR}/bin"

cat > "${WORK_DIR}/bin/list-versions.sh" <<'EOF'
#!/bin/sh
# list-versions.sh <bucket>
# Prints one line per object version: key<TAB>version-id<TAB>size-bytes.
# Delete markers are not versions and are not listed here — see
# count-delete-markers.sh, which is what decides whether that is safe.
set -eu
bucket="$1"
aws s3api list-object-versions --bucket "${bucket}" --output json \
  | jq -r '(.Versions // []) | .[] | [.Key, .VersionId, (.Size|tostring)] | @tsv'
EOF

cat > "${WORK_DIR}/bin/count-delete-markers.sh" <<'EOF'
#!/bin/sh
# count-delete-markers.sh <bucket>
# Prints the number of delete markers in the bucket.
set -eu
bucket="$1"
aws s3api list-object-versions --bucket "${bucket}" \
  --query 'length(DeleteMarkers || `[]`)' --output text
EOF

cat > "${WORK_DIR}/bin/get-retention.sh" <<'EOF'
#!/bin/sh
# get-retention.sh <bucket> <key> <version-id>
# Prints mode<TAB>retain-until for that version's current retention, or two
# empty fields if it carries none. Never downloads the object body — this
# is the read the verification pass uses, so checking retention on a large
# archive does not mean re-transferring it.
set -eu
bucket="$1"; key="$2"; version="$3"
if retention_json="$(aws s3api get-object-retention --bucket "${bucket}" --key "${key}" \
     --version-id "${version}" --output json 2>/dev/null)"; then
  printf '%s' "${retention_json}" | jq -r '[.Retention.Mode, .Retention.RetainUntilDate] | @tsv'
else
  printf '\t\n'
fi
EOF

cat > "${WORK_DIR}/bin/download-version.sh" <<'EOF'
#!/bin/sh
# download-version.sh <bucket> <key> <version-id> <outfile>
# Downloads the object to <outfile> (under /work, so the target-context
# container can read the same file). Then prints its current retention as
# mode<TAB>retain-until, or two empty fields if the object carries none.
set -eu
bucket="$1"; key="$2"; version="$3"; outfile="$4"
aws s3api get-object --bucket "${bucket}" --key "${key}" --version-id "${version}" \
  "${outfile}" >/dev/null
if retention_json="$(aws s3api get-object-retention --bucket "${bucket}" --key "${key}" \
     --version-id "${version}" --output json 2>/dev/null)"; then
  printf '%s' "${retention_json}" | jq -r '[.Retention.Mode, .Retention.RetainUntilDate] | @tsv'
else
  printf '\t\n'
fi
EOF

cat > "${WORK_DIR}/bin/find-existing-copy.sh" <<'EOF'
#!/bin/sh
# find-existing-copy.sh <bucket> <key> <source-version-id>
# Exits 0 and prints target-version-id<TAB>mode<TAB>retain-until if some
# existing version of <key> on this endpoint already carries
# x-amz-meta-source-version-id equal to <source-version-id>. Exits 1 if none
# does (nothing printed) — that is "not copied yet", not an error.
set -eu
bucket="$1"; key="$2"; source_version_id="$3"
versions="$(aws s3api list-object-versions --bucket "${bucket}" --prefix "${key}" \
  --query 'Versions[]' --output json \
  | jq -r --arg k "${key}" '.[] | select(.Key == $k) | .VersionId')"
for v in ${versions}; do
  tag="$(aws s3api head-object --bucket "${bucket}" --key "${key}" --version-id "${v}" \
    --query 'Metadata."source-version-id"' --output text 2>/dev/null || printf 'None')"
  if [ "${tag}" = "${source_version_id}" ]; then
    if retention_json="$(aws s3api get-object-retention --bucket "${bucket}" --key "${key}" \
         --version-id "${v}" --output json 2>/dev/null)"; then
      printf '%s\t' "${v}"
      printf '%s' "${retention_json}" | jq -r '[.Retention.Mode, .Retention.RetainUntilDate] | @tsv'
    else
      printf '%s\t\t\n' "${v}"
    fi
    exit 0
  fi
done
exit 1
EOF

cat > "${WORK_DIR}/bin/upload-version.sh" <<'EOF'
#!/bin/sh
# upload-version.sh <bucket> <key> <infile> <mode-or-empty> <retain-or-empty> <source-version-id>
set -eu
bucket="$1"; key="$2"; infile="$3"; mode="$4"; retain="$5"; source_version_id="$6"
if [ -n "${mode}" ] && [ -n "${retain}" ]; then
  aws s3api put-object --bucket "${bucket}" --key "${key}" --body "${infile}" \
    --object-lock-mode "${mode}" --object-lock-retain-until-date "${retain}" \
    --metadata source-version-id="${source_version_id}" >/dev/null
else
  aws s3api put-object --bucket "${bucket}" --key "${key}" --body "${infile}" \
    --metadata source-version-id="${source_version_id}" >/dev/null
fi
EOF

chmod +x "${WORK_DIR}"/bin/*.sh

run_source_helper() { sh_source "/work/bin/$1" "${@:2}"; }
run_target_helper() { sh_target "/work/bin/$1" "${@:2}"; }

# -----------------------------------------------------------------------------
# Bucket parity: object count and total bytes, both current-version only
# (what "aws s3 ls --recursive --summarize" reports).
# -----------------------------------------------------------------------------

bucket_totals() {
  # aws_fn is the name of aws_source or aws_target; echoes "count<TAB>bytes".
  local aws_fn="$1" bucket="$2" output count bytes
  output="$("${aws_fn}" s3 ls "s3://${bucket}" --recursive --summarize 2>/dev/null || true)"
  count="$(printf '%s\n' "${output}" | awk -F': ' '/^Total Objects:/ {print $2}')"
  bytes="$(printf '%s\n' "${output}" | awk -F': ' '/^ *Total Size:/ {print $2}')"
  printf '%s\t%s\n' "${count:-0}" "${bytes:-0}"
}

verify_bucket_parity() {
  local bucket="$1" label="$2" source_totals target_totals
  local source_count source_bytes target_count target_bytes
  source_totals="$(bucket_totals aws_source "${bucket}")"
  target_totals="$(bucket_totals aws_target "${bucket}")"
  source_count="$(printf '%s' "${source_totals}" | cut -f1)"
  source_bytes="$(printf '%s' "${source_totals}" | cut -f2)"
  target_count="$(printf '%s' "${target_totals}" | cut -f1)"
  target_bytes="$(printf '%s' "${target_totals}" | cut -f2)"
  info "${label}: source ${source_count} objects / ${source_bytes} bytes; target ${target_count} objects / ${target_bytes} bytes"
  if [ "${source_count}" = "${target_count}" ] && [ "${source_bytes}" = "${target_bytes}" ]; then
    info "${label}: PARITY OK"
    return 0
  fi
  warn "${label}: PARITY MISMATCH"
  return 1
}

# -----------------------------------------------------------------------------
# Step 3: media
# -----------------------------------------------------------------------------

migrate_media() {
  say "Media: ${MEDIA_BUCKET}"
  local staging="${WORK_DIR}/media-staging"
  mkdir -p "${staging}"
  info "Downloading from source (read-only against ${SOURCE_ENDPOINT})"
  aws_source s3 sync "s3://${MEDIA_BUCKET}" /work/media-staging
  if [ "${DRY_RUN}" = 1 ]; then
    info "[dry-run] would upload the staged copy to target; showing what would change:"
    aws_target s3 sync /work/media-staging "s3://${MEDIA_BUCKET}" --dryrun
  else
    info "Uploading staged copy to target (${TARGET_ENDPOINT})"
    aws_target s3 sync /work/media-staging "s3://${MEDIA_BUCKET}"
  fi
}

# -----------------------------------------------------------------------------
# Step 5: backups. Latest objects only — no version history needed, because
# every nightly run writes a uniquely timestamped key (infra/backup/backup.sh
# never overwrites an existing object), so there is at most one version per
# key that has ever mattered for recovery. The bucket's own versioning stays
# enabled on the target (wave C's seed step sets that up); this script only
# has to reproduce the current object set, which "aws s3 sync" without
# --delete already does incrementally.
# -----------------------------------------------------------------------------

migrate_backups() {
  say "Backups: ${BACKUP_BUCKET}"
  local staging="${WORK_DIR}/backups-staging"
  mkdir -p "${staging}"
  info "Downloading from source (read-only against ${SOURCE_ENDPOINT})"
  aws_source s3 sync "s3://${BACKUP_BUCKET}" /work/backups-staging
  if [ "${DRY_RUN}" = 1 ]; then
    info "[dry-run] would upload the staged copy to target; showing what would change:"
    aws_target s3 sync /work/backups-staging "s3://${BACKUP_BUCKET}" --dryrun
  else
    info "Uploading staged copy to target (${TARGET_ENDPOINT})"
    aws_target s3 sync /work/backups-staging "s3://${BACKUP_BUCKET}"
  fi
}

# -----------------------------------------------------------------------------
# Step 4: audit archive, preserving Object Lock retention per version.
# copy-object cannot cross endpoints (there is only one endpoint per AWS CLI
# call), so every version is a real download-then-upload; the JOB this
# script implements says so and it stays true for exactly that reason.
# -----------------------------------------------------------------------------

migrate_audit_archive() {
  say "Audit archive: ${AUDIT_ARCHIVE_BUCKET}"

  local delete_markers
  delete_markers="$(run_source_helper count-delete-markers.sh "${AUDIT_ARCHIVE_BUCKET}")"
  if [ "${delete_markers}" != "0" ]; then
    die "${AUDIT_ARCHIVE_BUCKET} has ${delete_markers} delete marker(s) on the source. \
This script only copies real object versions and their retention; a delete \
marker changes which version currently reads as \"deleted\" for that key, \
which is itself part of the evidence. Decide by hand what the target's \
top-of-stack state should be for those keys before running this step — see \
platform/docs/runbooks/object-store-migration.md, step 4."
  fi

  local versions_file="${WORK_DIR}/source-audit-versions.tsv"
  run_source_helper list-versions.sh "${AUDIT_ARCHIVE_BUCKET}" > "${versions_file}"

  local total=0 copied=0 skipped=0 key version size
  local existing outfile mode retain
  while IFS="$(printf '\t')" read -r key version size || [ -n "${key:-}" ]; do
    [ -n "${key}" ] || continue
    total=$((total + 1))

    if existing="$(run_target_helper find-existing-copy.sh "${AUDIT_ARCHIVE_BUCKET}" "${key}" "${version}" 2>/dev/null)"; then
      skipped=$((skipped + 1))
      info "already present: ${key} (source version ${version}) -> target version $(printf '%s' "${existing}" | cut -f1)"
      continue
    fi

    outfile="${WORK_DIR}/audit-$(printf '%s' "${version}" | tr -c 'A-Za-z0-9' '_').bin"
    local dl
    dl="$(run_source_helper download-version.sh "${AUDIT_ARCHIVE_BUCKET}" "${key}" "${version}" "/work/$(basename "${outfile}")")"
    mode="$(printf '%s' "${dl}" | cut -f1)"
    retain="$(printf '%s' "${dl}" | cut -f2)"

    if [ "${DRY_RUN}" = 1 ]; then
      info "[dry-run] would copy ${key} (version ${version}, ${size} bytes, retention ${mode:-none} ${retain:-})"
    else
      run_target_helper upload-version.sh "${AUDIT_ARCHIVE_BUCKET}" "${key}" \
        "/work/$(basename "${outfile}")" "${mode}" "${retain}" "${version}"
      info "copied: ${key} (version ${version}, ${size} bytes, retention ${mode:-none} ${retain:-})"
    fi
    copied=$((copied + 1))
    rm -f "${outfile}"
  done < "${versions_file}"

  info "Audit archive: ${total} source version(s) — ${copied} copied, ${skipped} already present"
}

# -----------------------------------------------------------------------------
# Post-copy verification: full retention equality for every audit-archive
# version, not a sample — the runbook's step 4 asks an operator to spot-check
# three by hand as a second, independent look, but the script itself can and
# does check all of them, because the cost of doing so is one AWS CLI call
# pair per version, already paid for by the copy loop's own bookkeeping.
# -----------------------------------------------------------------------------

verify_audit_archive_retention() {
  local versions_file="${WORK_DIR}/source-audit-versions.tsv"
  [ -f "${versions_file}" ] || run_source_helper list-versions.sh "${AUDIT_ARCHIVE_BUCKET}" > "${versions_file}"

  local total=0 mismatches=0 key version size existing target_mode target_retain source_mode source_retain
  while IFS="$(printf '\t')" read -r key version size || [ -n "${key:-}" ]; do
    [ -n "${key}" ] || continue
    total=$((total + 1))

    local source_ret
    source_ret="$(run_source_helper get-retention.sh "${AUDIT_ARCHIVE_BUCKET}" "${key}" "${version}")"
    source_mode="$(printf '%s' "${source_ret}" | cut -f1)"
    source_retain="$(printf '%s' "${source_ret}" | cut -f2)"

    if existing="$(run_target_helper find-existing-copy.sh "${AUDIT_ARCHIVE_BUCKET}" "${key}" "${version}" 2>/dev/null)"; then
      target_mode="$(printf '%s' "${existing}" | cut -f2)"
      target_retain="$(printf '%s' "${existing}" | cut -f3)"
      if [ "${source_mode}" = "${target_mode}" ] && [ "${source_retain}" = "${target_retain}" ]; then
        continue
      fi
      warn "retention mismatch for ${key} version ${version}: source (${source_mode:-none} ${source_retain:-}) != target (${target_mode:-none} ${target_retain:-})"
      mismatches=$((mismatches + 1))
    else
      warn "no target copy found for ${key} version ${version}"
      mismatches=$((mismatches + 1))
    fi
  done < "${versions_file}"

  info "Audit archive retention check: ${total} source version(s), ${mismatches} mismatch(es)"
  [ "${mismatches}" = 0 ]
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

say "SOURCE_ENDPOINT=${SOURCE_ENDPOINT}  TARGET_ENDPOINT=${TARGET_ENDPOINT}  network=${MIGRATE_DOCKER_NETWORK}"
[ "${DRY_RUN}" = 1 ] && info "--dry-run: reads are real, no write reaches ${TARGET_ENDPOINT}"

FAILURES=0

if [ "${VERIFY_ONLY}" = 0 ]; then
  [ "${RUN_MEDIA}" = 1 ] && migrate_media
  [ "${RUN_BACKUPS}" = 1 ] && migrate_backups
  [ "${RUN_AUDIT}" = 1 ] && migrate_audit_archive
fi

if [ "${DRY_RUN}" = 1 ]; then
  say "Skipping verification: --dry-run never wrote to ${TARGET_ENDPOINT}, so target/source parity is not the right check right now."
else
  say "Verifying"
  if [ "${RUN_MEDIA}" = 1 ]; then
    verify_bucket_parity "${MEDIA_BUCKET}" "media" || FAILURES=$((FAILURES + 1))
  fi
  if [ "${RUN_BACKUPS}" = 1 ]; then
    verify_bucket_parity "${BACKUP_BUCKET}" "backups" || FAILURES=$((FAILURES + 1))
  fi
  if [ "${RUN_AUDIT}" = 1 ]; then
    audit_version_count() {
      # shellcheck disable=SC2016 # JMESPath for the AWS CLI, not a shell expansion.
      "$1" s3api list-object-versions --bucket "${AUDIT_ARCHIVE_BUCKET}" \
        --query 'length(Versions || `[]`)' --output text
    }
    source_versions="$(audit_version_count aws_source)"
    target_versions="$(audit_version_count aws_target)"
    info "audit archive: source ${source_versions} version(s); target ${target_versions} version(s)"
    if [ "${source_versions}" = "${target_versions}" ]; then
      info "audit archive: VERSION COUNT PARITY OK"
    else
      warn "audit archive: VERSION COUNT MISMATCH"
      FAILURES=$((FAILURES + 1))
    fi
    verify_audit_archive_retention || FAILURES=$((FAILURES + 1))
  fi
fi

if [ "${FAILURES}" -gt 0 ]; then
  die "${FAILURES} verification check(s) failed. See the warnings above."
fi

say "Done. Every requested check passed."
