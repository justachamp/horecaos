#!/usr/bin/env bash
#
# Pull the images the Testcontainers suites need, up front, with retries.
#
# Shared by every CI job that runs Java tests (the test shards and the static-gate
# job in .github/workflows/ci.yml), so the list cannot drift between them.
#
# Testcontainers gives a pull two minutes and every suite that needs the image
# retries on its own, so a registry hiccup costs seven minutes per affected class
# inside the forks. Pulling the three images here instead means a registry outage
# fails fast and a slow one is paid for once per job. The Postgres image is the
# base of infra/postgres/Dockerfile, which TestDatabase builds locally.
#
# rustfs/rustfs, not quay.io/minio/minio (ADR 0135, 2026-09-25): MinIO's public
# images were withdrawn -- quay.io/minio/minio answers 401 anonymously -- which is
# exactly the "registry hiccup" this exists to survive, except a withdrawn image
# never recovers no matter how many of the five attempts run. RustFS is the pinned
# replacement the Testcontainers test classes now start.
#
# Run from platform/ (the workflow's working directory), or set PLATFORM_DIR.

set -u

PLATFORM_DIR="${PLATFORM_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

POSTGRES_IMAGE="$(sed -nE 's/^FROM (postgres:[^ ]+).*/\1/p' "${PLATFORM_DIR}/infra/postgres/Dockerfile" | head -1)"
if [ -z "${POSTGRES_IMAGE}" ]; then
    echo "could not read the Postgres base image from infra/postgres/Dockerfile" >&2
    exit 1
fi

# HORECAOS_PULL_COMMAND and HORECAOS_PULL_RETRY_SECONDS exist so the retry loop
# can be exercised without a registry; CI leaves both alone.
PULL_COMMAND="${HORECAOS_PULL_COMMAND:-docker pull --quiet}"
RETRY_SECONDS="${HORECAOS_PULL_RETRY_SECONDS:-30}"

for image in \
    "${POSTGRES_IMAGE}" \
    rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff \
    apache/kafka:4.3.1
do
    for attempt in 1 2 3 4 5; do
        # shellcheck disable=SC2086 # the command is deliberately word-split
        if ${PULL_COMMAND} "${image}"; then break; fi
        if [ "${attempt}" = 5 ]; then
            echo "could not pull ${image} after 5 attempts" >&2
            exit 1
        fi
        sleep "${RETRY_SECONDS}"
    done
done
