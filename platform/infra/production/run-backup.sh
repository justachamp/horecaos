#!/usr/bin/env bash
#
# The nightly backup, as cron runs it.
#
#   17 2 * * * /opt/horecaos/horecaos-platform/infra/production/run-backup.sh \
#              && touch /var/lib/horecaos/last-backup
#
# The `&& touch` is not decoration. `infra/production/heartbeat.sh` alerts when
# that file is older than 26 hours, which means it alerts on a backup that
# *did not run* — silence — as well as on one that failed. Silence is the
# failure mode that hides longest, and it is the one that is only ever
# discovered during a restore.
#
# All the real work is in infra/backup/backup.sh, which already dumps, verifies,
# encrypts, uploads to two destinations, reads the upload back, and expires old
# copies. This wrapper exists to answer one question that script deliberately
# leaves open: where do its credentials come from at 2am with nobody awake?
#
# The answer is the OpenBao agent's token, mounted read-only into the ops
# container. See infra/production/ops/bao-get.sh.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/compose.production.yaml"
ENV_FILE="${HORECAOS_ENV_FILE:-/etc/horecaos/production.env}"
SECRET_DIR="${HORECAOS_SECRET_DIR:-/run/horecaos/secrets}"

export HORECAOS_SECRET_DIR="${SECRET_DIR}"
export HORECAOS_IMAGE_TAG="${HORECAOS_IMAGE_TAG:-latest}"

# Everything below runs inside the ops container. Nothing is resolved on this
# host, so no credential ever reaches this shell, this crontab, or this host's
# process table. The job itself is a file in the image rather than a string
# passed on the command line — see the note at the top of
# infra/production/ops/backup-job.sh for the bug that taught us why.
exec docker compose --file "${COMPOSE_FILE}" --env-file "${ENV_FILE}" \
    run --rm --no-TTY ops /usr/local/bin/backup-job.sh
