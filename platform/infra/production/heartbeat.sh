#!/usr/bin/env bash
#
# The whole of Qoida's production alerting.
#
# The tension this resolves
# -------------------------
# Monitoring hosted on the machine it monitors cannot tell you the machine is
# gone. A Prometheus and an Alertmanager on this box would produce beautiful
# dashboards right up until the disk failed, and then produce nothing — at the
# one moment their output mattered. Adding them would also add two more services
# for one person to keep patched.
#
# So the alert path deliberately runs the other way. This script runs on the box
# every five minutes, checks a small number of things that are worth waking
# someone for, and **pings an external dead-man's-switch** when they all pass.
# The alert is the *absence* of that ping, evaluated by a service that is not on
# this machine and does not depend on it. If the host is unplugged, the ping
# stops, and the alert fires from somewhere else entirely.
#
# Two independent external observers, so that neither is a single point:
#
#   * The dead-man's-switch (healthchecks.io, Better Stack, or equivalent). Fires
#     when this script stops reporting. Catches: power, network, disk, kernel,
#     Docker daemon, and anything else that stops the whole machine.
#   * An external HTTP uptime check against ${QOIDA_API_ORIGIN}/actuator/health/readiness.
#     Catches: the application being down while the host is fine, TLS expiry,
#     DNS, and the colocation provider's routing.
#
# What this deliberately does not do
# ----------------------------------
# It does not alert on CPU, memory, request rate, queue depth, or anything else a
# dashboard is for. A solo operator with a noisy pager stops reading the pager,
# and the second time that happens the pager is decoration. Every check below is
# one an operator can act on at 3am, and each one names the action.
#
# What is accepted rather than solved
# -----------------------------------
# When this host is unreachable, there is no metric history for the outage
# window beyond what survived on its disk. The external checks say *that* it is
# down, not *why*. Diagnosing why waits until the machine is reachable again.
# That is a deliberate trade: on a single colocated box, a second machine to hold
# telemetry is a second machine to operate, and the honest ranking is that
# knowing quickly beats knowing precisely. Revisit it when there is a second
# server for any reason.
#
# Installation
# ------------
#   */5 * * * * /opt/qoida/qoida-platform/infra/production/heartbeat.sh
#
# Configuration lives in /etc/qoida/alerting.env, root-owned, mode 0600:
#
#   QOIDA_HEARTBEAT_URL=https://hc-ping.com/<uuid>
#   QOIDA_ALERT_WEBHOOK=https://api.telegram.org/bot<token>/sendMessage?chat_id=<id>
#   QOIDA_BACKUP_STAMP=/var/lib/qoida/last-backup
#
# Those two URLs are deliberately NOT in OpenBao. The alert path must not depend
# on the thing it is monitoring: an OpenBao that is sealed, or a Docker daemon
# that is wedged, is exactly when this script has to work. They are the only
# credentials on this host that are not in the secrets manager, and that is a
# considered exception rather than an oversight.

set -uo pipefail

CONFIG="${QOIDA_ALERTING_ENV:-/etc/qoida/alerting.env}"
COMPOSE_FILE="${QOIDA_COMPOSE_FILE:-/opt/qoida/qoida-platform/compose.production.yaml}"
DISK_THRESHOLD_PERCENT="${QOIDA_DISK_THRESHOLD_PERCENT:-85}"
BACKUP_MAX_AGE_HOURS="${QOIDA_BACKUP_MAX_AGE_HOURS:-26}"

# Touched by the backup cron entry only when infra/backup/backup.sh exits zero.
# See docs/runbooks/restore.md for the crontab line that maintains it.
BACKUP_STAMP="${QOIDA_BACKUP_STAMP:-/var/lib/qoida/last-backup}"

# shellcheck disable=SC1090
[ -r "${CONFIG}" ] && . "${CONFIG}"

problems=()

note() { problems+=("$1"); }


# 1. Every container that should be running is running and healthy.
#
#    Action if this fires: `docker compose -f … ps` and then the logs of whatever
#    is named. autoheal restarts unhealthy containers by itself, so a container
#    still unhealthy five minutes later is one autoheal could not fix.
unhealthy="$(docker compose --file "${COMPOSE_FILE}" ps --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null \
    | awk '$2 != "running" || ($3 != "" && $3 != "healthy") {print $1"("$2"/"$3")"}' \
    | tr '\n' ' ')"
if [ -n "${unhealthy}" ]; then
    note "containers not healthy: ${unhealthy}"
fi

# 2. Disk headroom.
#
#    Action if this fires: `docker system df`, then `docker image prune`. A full
#    disk on this topology is an outage with nothing to fail over to, and it
#    arrives slowly enough that there is never an excuse for being surprised.
disk_used="$(df --output=pcent / 2>/dev/null | tail -1 | tr -dc '0-9')"
if [ -n "${disk_used}" ] && [ "${disk_used}" -ge "${DISK_THRESHOLD_PERCENT}" ]; then
    note "root filesystem ${disk_used}% full"
fi

# 3. The application answers its readiness probe from inside the network.
#
#    Action if this fires: docs/runbooks/deploy.md, "It did not come up".
if ! docker compose --file "${COMPOSE_FILE}" exec -T platform-app \
        wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null; then
    note "application readiness probe failed"
fi

# 4. A backup exists and is recent.
#
#    This is the check that matters most and is the one most likely to be
#    forgotten, because nothing breaks when a backup silently stops running —
#    until the day something else breaks. Alerting on "did not run" rather than
#    only on "ran and failed" is the difference between noticing in a day and
#    noticing during a restore.
#
#    Action if this fires: docs/runbooks/restore.md, "The backup did not run".
if [ -r "${BACKUP_STAMP}" ]; then
    stamp_age_hours=$(( ( $(date +%s) - $(stat -c %Y "${BACKUP_STAMP}" 2>/dev/null || echo 0) ) / 3600 ))
    if [ "${stamp_age_hours}" -gt "${BACKUP_MAX_AGE_HOURS}" ]; then
        note "last successful backup was ${stamp_age_hours}h ago"
    fi
else
    note "no backup stamp file at ${BACKUP_STAMP}"
fi

# 5. OpenBao is unsealed.
#
#    Action if this fires: unseal it. Nothing else on this host can be fixed
#    while it is sealed, because no container can get a credential.
if ! docker compose --file "${COMPOSE_FILE}" exec -T openbao bao status >/dev/null 2>&1; then
    note "OpenBao is sealed or unreachable"
fi


if [ "${#problems[@]}" -eq 0 ]; then
    # All clear. Ping the dead-man's-switch; its silence is the alert.
    [ -n "${QOIDA_HEARTBEAT_URL:-}" ] \
        && curl -fsS --max-time 20 --retry 3 "${QOIDA_HEARTBEAT_URL}" >/dev/null 2>&1
    exit 0
fi

message="qoida production: $(printf '%s; ' "${problems[@]}")"
printf '%s\n' "${message}" >&2

# Push once, directly, and do not ping the heartbeat. If the push fails because
# the network is the problem, the missed heartbeat raises the alarm anyway —
# which is the reason the heartbeat is the primary path and this is the detail.
if [ -n "${QOIDA_ALERT_WEBHOOK:-}" ]; then
    curl -fsS --max-time 20 --retry 2 \
        --data-urlencode "text=${message}" \
        "${QOIDA_ALERT_WEBHOOK}" >/dev/null 2>&1
fi

exit 1
