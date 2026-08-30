#!/usr/bin/env bash
#
# Read one secret from OpenBao, using the token the agent already keeps renewed.
#
#   bao-get.sh production/data_encryption/platform/backup-passphrase
#
# The backup job runs unattended at 02:17 and there is nobody awake to type a
# token. Rather than giving the backup its own long-lived credential — a second
# thing to rotate and a second thing to leak — it borrows the agent's, which is
# mounted read-only at /run/bao/token and is renewed continuously. The agent's
# policy is read-only over `horecaos/production/*`, which is exactly and only what
# this needs.
#
# If the agent is not running, this fails, and so does the backup, and the
# missing backup stamp raises the alarm through the heartbeat. That chain is
# intentional: a backup that silently ran without encryption would be worse than
# one that did not run.

set -euo pipefail

TOKEN_FILE="${HORECAOS_BAO_TOKEN_FILE:-/run/bao/token}"
BAO_ADDR="${BAO_ADDR:-http://openbao:8200}"
MOUNT="${HORECAOS_OPENBAO_MOUNT:-horecaos}"

path="${1:?usage: bao-get.sh <environment>/<category>/<owner>/<id>}"

[ -r "${TOKEN_FILE}" ] || {
    echo "!! ${TOKEN_FILE} is not readable. Is openbao-agent running?" >&2
    exit 1
}

# The token goes in a header read from a file, never on the command line.
value="$(curl -fsS \
    --header "@/dev/stdin" \
    "${BAO_ADDR}/v1/${MOUNT}/data/${path}" \
    <<<"X-Vault-Token: $(cat "${TOKEN_FILE}")" \
    | jq -r '.data.data.value')"

[ -n "${value}" ] && [ "${value}" != "null" ] || {
    echo "!! ${path} is missing or empty in OpenBao" >&2
    exit 1
}

printf '%s' "${value}"
