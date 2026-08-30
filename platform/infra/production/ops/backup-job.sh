#!/usr/bin/env bash
#
# The nightly backup, from inside the ops container.
#
# This lives in the image rather than being passed to `bash -c` from the host,
# and that is not a style preference. The first version of it was an inline
# single-quoted string in infra/production/run-backup.sh, and a comment
# containing an apostrophe closed the quote early: the container started, ran a
# fragment of the script, exited zero, and backed up nothing. Cron would have
# reported success every night.
#
# A script in a file cannot fail that way, and it can be read, linted, and run
# by hand during an incident.
#
# Credentials come from OpenBao using the agent token mounted at /run/bao/token.
# Nothing is passed in, so nothing can be read out of the process table on the
# host or in `docker inspect`.

set -euo pipefail

get() { /usr/local/bin/bao-get.sh "$1"; }

# The database password goes in PGPASSWORD rather than into the connection URL,
# so it stays out of the argument list pg_dump is invoked with.
PGPASSWORD="$(get production/database/platform/migrator-password)"
export PGPASSWORD

export QOIDA_BACKUP_DB_URL="postgresql://qoida_migrator@platform-db:5432/qoida"
QOIDA_BACKUP_PASSPHRASE="$(get production/data_encryption/platform/backup-passphrase)"
QOIDA_BACKUP_ACCESS_KEY="$(get production/object_storage/platform/backup-access-key)"
QOIDA_BACKUP_SECRET_KEY="$(get production/object_storage/platform/backup-secret-key)"
export QOIDA_BACKUP_PASSPHRASE QOIDA_BACKUP_ACCESS_KEY QOIDA_BACKUP_SECRET_KEY

# The off-site destination's own credentials, resolved here rather than passed
# through compose. They are deliberately not in the env file and not in the ops
# service's environment block: an env value is readable from the host process
# table and from `docker inspect`, and these are the credentials that can reach
# the copy of the backups that exists specifically because the machine holding
# the others may be gone.
#
# The passphrase above is fetched separately and stays separate. A destination
# that holds both the ciphertext and the key to it is one account compromise
# away from being no encryption at all.
QOIDA_BACKUP_OFFSITE_ACCESS_KEY="$(get production/object_storage/platform/backup-offsite-access-key)"
QOIDA_BACKUP_OFFSITE_SECRET_KEY="$(get production/object_storage/platform/backup-offsite-secret-key)"
export QOIDA_BACKUP_OFFSITE_ACCESS_KEY QOIDA_BACKUP_OFFSITE_SECRET_KEY

exec /opt/qoida/backup/backup.sh
