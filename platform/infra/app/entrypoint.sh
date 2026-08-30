#!/bin/sh
#
# HorecaOS Platform container entrypoint.
#
# The single job of this script is to get startup secrets into the JVM's
# environment without any of them existing in the image, in the compose file, in
# the container's declared environment, or on a persistent disk.
#
# How that works (ADR 0028):
#
#   1. The `openbao-agent` sidecar authenticates to OpenBao with an AppRole whose
#      secret-id was response-wrapped seconds earlier by the deploy script.
#   2. The agent writes a renewed OpenBao token to /run/bao/token and renders
#      /run/bao/horecaos.env from live secret values.
#   3. /run/bao is a tmpfs shared between the agent and this container. It exists
#      in memory only: it is not in the image, not in a volume, and not on the
#      disk. A reboot loses it, which is the intended behaviour.
#   4. This script sources that file and hands the values to the JVM.
#
# Values therefore reach the process environment but never the container's
# declared environment, so `docker inspect` and `docker compose config` show
# nothing. `/proc/1/environ` inside this container does hold them, which is the
# same exposure any process has to its own environment and is the boundary this
# design accepts.
#
# Nothing here echoes a value, and `set -x` must never be added.

set -eu

BAO_DIR="${HORECAOS_BAO_DIR:-/run/bao}"
TOKEN_FILE="${BAO_DIR}/token"
ENV_FILE="${BAO_DIR}/horecaos.env"

# Bounded, then give up. Waiting forever would leave a container that is neither
# running nor restarting, which is the state that hides longest in `docker ps`.
# Exiting non-zero hands the problem to the restart policy, which retries with
# backoff — so an operator who unseals OpenBao ten minutes after a power cut does
# not also have to remember to start the platform.
WAIT_SECONDS="${HORECAOS_SECRET_WAIT_SECONDS:-120}"

wait_for_secrets() {
    waited=0
    while [ "${waited}" -lt "${WAIT_SECONDS}" ]; do
        if [ -s "${TOKEN_FILE}" ] && [ -s "${ENV_FILE}" ]; then
            return 0
        fi
        if [ "${waited}" -eq 0 ]; then
            echo "entrypoint: waiting for the OpenBao agent to render ${ENV_FILE}"
        fi
        sleep 2
        waited=$((waited + 2))
    done
    echo "entrypoint: no secrets after ${WAIT_SECONDS}s." >&2
    echo "entrypoint: check that OpenBao is unsealed and openbao-agent is healthy." >&2
    return 1
}

if [ "${HORECAOS_SECRETS_PROVIDER:-openbao}" = "openbao" ]; then
    wait_for_secrets

    # The rendered file is a list of KEY=value lines and nothing else. `set -a`
    # exports what it defines; the file is never printed and never logged.
    set -a
    # shellcheck disable=SC1090
    . "${ENV_FILE}"
    set +a

    HORECAOS_OPENBAO_TOKEN="$(cat "${TOKEN_FILE}")"
    export HORECAOS_OPENBAO_TOKEN
fi

# Container-aware heap sizing. A fixed -Xmx would have to be edited whenever the
# container's memory limit changes, and the two drifting apart is how a JVM ends
# up being killed by the kernel while believing it has headroom.
#
# ExitOnOutOfMemoryError is a deliberate choice for a single-operator deployment:
# a JVM that has exhausted its heap is not going to recover, and a process that
# dies is restarted by the restart policy in seconds. A process that limps along
# throwing OutOfMemoryError from random threads is an incident that needs a human
# at 3am. Dying is the cheaper failure.
JVM_OPTS="-XX:MaxRAMPercentage=${HORECAOS_MAX_RAM_PERCENTAGE:-70}
 -XX:+ExitOnOutOfMemoryError
 -XX:+HeapDumpOnOutOfMemoryError
 -XX:HeapDumpPath=/tmp
 -Djava.io.tmpdir=/tmp
 -Dfile.encoding=UTF-8
 -Duser.timezone=${TZ:-Asia/Tashkent}"

# `exec` so the JVM becomes pid 1 and receives SIGTERM directly. That is what
# makes `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase`
# mean anything: without it a shell would swallow the signal and Docker would
# escalate to SIGKILL after ten seconds, cutting in-flight requests and leaving
# outbox leases held until they expire.
# shellcheck disable=SC2086
exec java ${JVM_OPTS} ${HORECAOS_JAVA_OPTS:-} \
    org.springframework.boot.loader.launch.JarLauncher "$@"
