#!/usr/bin/env bash
#
# The on-box half of ADR 0023's alerting, and the whole of its threshold table.
#
# What this is, and what it is not
# --------------------------------
# ADR 0034 splits watching this platform in two, because a monitor hosted on the
# machine it monitors cannot report that the machine is gone. This script is the
# **on-box** half: it runs from cron on the production VM, evaluates every alert
# in ADR 0023's table against the platform's own metrics, routes each one to its
# tier, and pings a dead-man's switch when it has finished. The **off-box** half —
# the external uptime check, the dead-man's switch itself, and the loud/silent
# split on the operator's phone — is configured outside this repository, and
# README.md in this directory says exactly what it has to be.
#
# The alert of last resort is the absence of the ping. If the host is unplugged,
# the disk fails, the kernel panics, or cron stops, this script stops reporting
# and a service that is not on this machine raises the alarm. Nothing that runs
# here can do that for itself.
#
# The three tiers, and why the budget is a budget
# -----------------------------------------------
#   night    wakes the operator at any hour. ADR 0034 caps this at three, and
#            ADR 0023 spends all three. A fourth is available only by removing
#            one and saying which.
#   trading  loud between 09:00 and 23:30 Asia/Tashkent, and folded into the
#            digest outside those hours. Everything here has an action that is
#            worth taking at noon and pointless at 03:00.
#   morning  a silent message read at the start of the next working day.
#
# There is one person carrying this pager. An alert nobody can act on at 3am is
# not a cheap alert; it is the mechanism by which the pager stops being read, and
# after that the three that matter do not work either.
#
# Installation
# ------------
#   */1 * * * * /opt/horecaos/horecaos-platform/infra/observability/horecaos-probe.sh
#
# It supersedes infra/production/heartbeat.sh, which evaluated five checks at one
# tier. Remove that crontab entry when this one is added, or every problem is
# reported twice and the second report is louder than the tier it belongs to.
#
# Configuration is /etc/horecaos/alerting.env, root-owned, mode 0600. The URLs in it
# are deliberately NOT in OpenBao: the alert path must not depend on the thing it
# is monitoring, and a sealed OpenBao is exactly when this script has to work.
# That is the one considered exception to ADR 0028 on this host.
#
# Exit status is for a human reading cron mail, not for control flow: 0 when
# nothing is firing, 1 when something is.

set -uo pipefail

CONFIG="${HORECAOS_ALERTING_ENV:-/etc/horecaos/alerting.env}"
COMPOSE_FILE="${HORECAOS_COMPOSE_FILE:-/opt/horecaos/horecaos-platform/compose.production.yaml}"
STATE_DIR="${HORECAOS_PROBE_STATE_DIR:-/var/lib/horecaos/probe}"
RUNBOOKS="${HORECAOS_RUNBOOK_BASE:-/opt/horecaos/horecaos-platform/docs/runbooks}"

# Touched by the backup cron entry only when infra/backup/backup.sh exits zero.
BACKUP_STAMP="${HORECAOS_BACKUP_STAMP:-/var/lib/horecaos/last-backup}"

# Thresholds. Every one of these is in ADR 0023's table with the reason it has
# that value. They are overridable so that a rehearsal or a staging VM can make
# an alert fire on demand — which is the only way to satisfy the exit criterion
# that each of the three night alerts has been verified to fire — and not so that
# a noisy night can be quietened by editing a number.
STALL_SECONDS="${HORECAOS_STALL_SECONDS:-900}"
CONSUMER_LAG_THRESHOLD="${HORECAOS_CONSUMER_LAG_THRESHOLD:-500}"
CALLBACK_FAILURES="${HORECAOS_CALLBACK_FAILURES:-3}"
CALLBACK_WINDOW_SECONDS="${HORECAOS_CALLBACK_WINDOW_SECONDS:-300}"
CIRCUIT_OPEN_SECONDS="${HORECAOS_CIRCUIT_OPEN_SECONDS:-600}"
FENCE_BURST="${HORECAOS_FENCE_BURST:-10}"
FENCE_WINDOW_SECONDS="${HORECAOS_FENCE_WINDOW_SECONDS:-300}"
BACKUP_MAX_AGE_HOURS="${HORECAOS_BACKUP_MAX_AGE_HOURS:-26}"
DISK_THRESHOLD_PERCENT="${HORECAOS_DISK_THRESHOLD_PERCENT:-85}"
CERTIFICATE_MIN_DAYS="${HORECAOS_CERTIFICATE_MIN_DAYS:-7}"
ONBOARDING_STALL_SECONDS="${HORECAOS_ONBOARDING_STALL_SECONDS:-3600}"

# Trading hours in Asia/Tashkent. The pilot's restaurants close at about 23:00;
# the window runs to 23:30 so that a problem arriving with the last orders is
# still loud, and stops there because the deploy window opens at 23:30 and a
# deploy legitimately moves several of these signals.
TRADING_START="${HORECAOS_TRADING_START:-0900}"
TRADING_END="${HORECAOS_TRADING_END:-2330}"

# How often a still-firing alert is repeated. A night alert that repeats every
# minute is a phone nobody can silence without silencing the next one too.
NIGHT_REPEAT_SECONDS="${HORECAOS_NIGHT_REPEAT_SECONDS:-1800}"
TRADING_REPEAT_SECONDS="${HORECAOS_TRADING_REPEAT_SECONDS:-3600}"

# shellcheck disable=SC1090
[ -r "${CONFIG}" ] && . "${CONFIG}"

export TZ="${HORECAOS_TIMEZONE:-Asia/Tashkent}"

mkdir -p "${STATE_DIR}/samples" "${STATE_DIR}/sent" 2>/dev/null
SCRAPE="$(mktemp)"
DIGEST="${STATE_DIR}/digest"
NOW="$(date +%s)"
CLOCK="$(date +%H%M)"

trap 'rm -f "${SCRAPE}"' EXIT

night_alert_failed_to_send=0
firing=0
database_up=yes


# ---------------------------------------------------------------------------
# Delivery
# ---------------------------------------------------------------------------

# Two webhooks, not one. The loud one is expected to be a notification channel
# with sound; the quiet one is the same chat with `disable_notification=true`, or
# a second chat entirely. Splitting them here rather than in the operator's phone
# settings means the tier is a property of the alert and not of what time the
# phone happens to think it is.
push() {
    local url="$1" text="$2"
    [ -z "${url}" ] && return 1
    curl -fsS --max-time 20 --retry 2 \
        --data-urlencode "text=${text}" \
        "${url}" >/dev/null 2>&1
}

# Whether this alert may be repeated yet. Firing is continuous; notifying is not.
may_notify() {
    local key="$1" interval="$2"
    local marker="${STATE_DIR}/sent/${key}"
    if [ -r "${marker}" ]; then
        local last
        last="$(cat "${marker}" 2>/dev/null || echo 0)"
        [ $(( NOW - last )) -lt "${interval}" ] && return 1
    fi
    printf '%s\n' "${NOW}" > "${marker}"
    return 0
}

clear_alert() {
    local key="$1" description="$2" tier="$3"
    local marker="${STATE_DIR}/sent/${key}"
    [ -r "${marker}" ] || return 0
    rm -f "${marker}"
    # Only the night tier reports its own recovery. Being woken and never told
    # whether it fixed itself is how an operator learns to drive to the office
    # for something that had already recovered.
    if [ "${tier}" = "night" ]; then
        push "${HORECAOS_ALERT_WEBHOOK_LOUD:-${HORECAOS_ALERT_WEBHOOK:-}}" \
            "horecaos RECOVERED: ${description}"
    fi
}

# The single entry point. Tier decides loudness; the runbook reference is
# mandatory, because ADR 0023 requires every alert to link to exactly one runbook
# section whose first line is a command.
alert() {
    local tier="$1" key="$2" description="$3" runbook="$4"
    firing=1
    # Upper-cased with tr rather than ${tier^^}: this has to run on whatever
    # bash the host image ships, and the parameter expansion is bash 4 only.
    local text
    text="horecaos $(printf '%s' "${tier}" | tr '[:lower:]' '[:upper:]'): ${description} -> ${RUNBOOKS}/${runbook}"

    case "${tier}" in
        night)
            if may_notify "${key}" "${NIGHT_REPEAT_SECONDS}"; then
                if ! push "${HORECAOS_ALERT_WEBHOOK_LOUD:-${HORECAOS_ALERT_WEBHOOK:-}}" "${text}"; then
                    # The push failed, so the operator has not been told. Suppress
                    # the dead-man's-switch ping and let its silence do the job the
                    # webhook could not. This is the only condition that suppresses
                    # the ping, and it is why a disk at 86% must never suppress it.
                    night_alert_failed_to_send=1
                fi
            fi
            ;;
        trading)
            if in_trading_hours; then
                if may_notify "${key}" "${TRADING_REPEAT_SECONDS}"; then
                    push "${HORECAOS_ALERT_WEBHOOK_LOUD:-${HORECAOS_ALERT_WEBHOOK:-}}" "${text}"
                fi
            else
                defer_to_digest "${key}" "${text}"
            fi
            ;;
        morning)
            defer_to_digest "${key}" "${text}"
            ;;
    esac
    printf '%s\n' "${text}" >&2
}

# One line per alert key per digest, so a condition that has been true for eight
# hours produces one line at 09:00 rather than four hundred and eighty.
defer_to_digest() {
    local key="$1" text="$2"
    touch "${DIGEST}"
    grep -q "^${key}\t" "${DIGEST}" 2>/dev/null && return 0
    printf '%s\t%s\n' "${key}" "${text}" >> "${DIGEST}"
}

send_digest_if_due() {
    [ -s "${DIGEST}" ] || return 0
    local stamp="${STATE_DIR}/digest-sent-on"
    local today
    today="$(date +%Y-%m-%d)"
    [ "$(cat "${stamp}" 2>/dev/null)" = "${today}" ] && return 0
    # 09:00 local: the start of the working day, and the beginning of the trading
    # window, so the digest and the loud tier turn on together.
    [ "${CLOCK}" \< "${TRADING_START}" ] && return 0

    local body
    body="horecaos morning digest ${today}:"$'\n'"$(cut -f2- "${DIGEST}")"
    if push "${HORECAOS_ALERT_WEBHOOK_QUIET:-${HORECAOS_ALERT_WEBHOOK:-}}" "${body}"; then
        printf '%s\n' "${today}" > "${stamp}"
        : > "${DIGEST}"
    fi
}

in_trading_hours() {
    [ "${CLOCK}" \> "${TRADING_START}" ] || [ "${CLOCK}" = "${TRADING_START}" ] || return 1
    [ "${CLOCK}" \< "${TRADING_END}" ] || return 1
    return 0
}


# ---------------------------------------------------------------------------
# Reading the platform
# ---------------------------------------------------------------------------

compose() {
    docker compose --file "${COMPOSE_FILE}" "$@"
}

# One scrape per run, from inside the network. /actuator/prometheus is not
# published and the edge answers 404 for it, so this is the only way in — which
# is the intended shape: metrics describe the platform's internals to anyone who
# can read them.
scrape() {
    compose exec -T platform-app \
        wget -q -O - "http://127.0.0.1:8080/actuator/prometheus" > "${SCRAPE}" 2>/dev/null
}

# Sum of every series with this name, optionally restricted to series whose label
# set matches an extended regular expression. Summing is right for the gauges
# here: the oldest-age gauges have exactly one series, and the dead-letter gauges
# are counted across their label sets on purpose.
metric_sum() {
    local name="$1" label_filter="${2:-}"
    awk -v name="${name}" -v filter="${label_filter}" '
        $0 ~ "^" name "([{ ])" {
            if (filter != "" && $0 !~ filter) { next }
            total += $NF
            seen = 1
        }
        END { if (seen) printf "%.0f\n", total; else print "" }
    ' "${SCRAPE}"
}

metric_max() {
    local name="$1" label_filter="${2:-}"
    awk -v name="${name}" -v filter="${label_filter}" '
        $0 ~ "^" name "([{ ])" {
            if (filter != "" && $0 !~ filter) { next }
            if (!seen || $NF > best) { best = $NF }
            seen = 1
        }
        END { if (seen) printf "%.0f\n", best; else print "" }
    ' "${SCRAPE}"
}

# The series with the largest value, reported as its label set, so an alert can
# name which provider or which capability rather than saying "one of them".
metric_max_label() {
    local name="$1" label="$2" label_filter="${3:-}"
    awk -v name="${name}" -v label="${label}" -v filter="${label_filter}" '
        $0 ~ "^" name "([{ ])" {
            if (filter != "" && $0 !~ filter) { next }
            if (!seen || $NF > best) {
                best = $NF
                match($0, label "=\"[^\"]*\"")
                found = RSTART ? substr($0, RSTART + length(label) + 2, RLENGTH - length(label) - 3) : "unknown"
            }
            seen = 1
        }
        END { print (seen ? found : "unknown") }
    ' "${SCRAPE}"
}

# How much a monotonic counter has risen over a window.
#
# Kept as a short series of samples rather than a single previous value, because
# the question ADR 0023 asks is "more than three in five minutes" and this script
# runs every minute. Comparing against the previous run would answer "more than
# three in one minute", which is a different and much less sensitive alert.
#
# A counter that went backwards means the process restarted; the sample history
# is dropped rather than reported as a negative delta, and the next run starts a
# fresh window. That loses one window's detection after a restart, which is
# preferable to a restart looking like a burst.
counter_delta() {
    local key="$1" current="$2" window="$3"
    local file="${STATE_DIR}/samples/${key}"
    [ -z "${current}" ] && { echo ""; return; }

    local previous=""
    if [ -r "${file}" ]; then
        previous="$(awk -v now="${NOW}" -v window="${window}" '
            $1 >= now - window { if (!found) { print $2; found = 1 } }
        ' "${file}")"
        if [ -n "${previous}" ] && awk -v a="${current}" -v b="${previous}" 'BEGIN { exit !(a < b) }'; then
            : > "${file}"
            previous=""
        fi
    fi

    printf '%s %s\n' "${NOW}" "${current}" >> "${file}"
    # Keep twice the longest window so that a slow cron run cannot leave the file
    # with nothing old enough to compare against.
    awk -v now="${NOW}" '$1 >= now - 1800' "${file}" > "${file}.tmp" 2>/dev/null \
        && mv "${file}.tmp" "${file}"

    [ -z "${previous}" ] && { echo ""; return; }
    awk -v a="${current}" -v b="${previous}" 'BEGIN { printf "%.0f\n", a - b }'
}

# A condition that has to hold for a period before it counts. Prints the number
# of seconds it has held for, and clears when it stops.
sustained_for() {
    local key="$1" holding="$2"
    local file="${STATE_DIR}/samples/holding-${key}"
    if [ "${holding}" != "yes" ]; then
        rm -f "${file}"
        echo 0
        return
    fi
    [ -r "${file}" ] || printf '%s\n' "${NOW}" > "${file}"
    local since
    since="$(cat "${file}" 2>/dev/null || echo "${NOW}")"
    echo $(( NOW - since ))
}


# ===========================================================================
# NIGHT — three, and ADR 0034 caps it there.
# ===========================================================================

# 1. Platform unreachable.
#
#    Not evaluated here and it cannot be. It is the off-box uptime check failing
#    twice consecutively, and a script running on the box cannot observe that the
#    box is unreachable. README.md in this directory specifies it.

# 2. PostgreSQL down while the host is up.
#
#    The host being up is not asserted; it is demonstrated by this script running
#    at all, which is the whole reason this alert belongs on the box and the
#    unreachable one does not. It is the availability anchor: nothing degrades
#    gracefully without it, and it subsumes the full-disk case, because a data
#    volume at 100% presents as PostgreSQL refusing writes.
check_database() {
    if compose exec -T platform-db pg_isready -U horecaos_migrator -d horecaos >/dev/null 2>&1; then
        clear_alert database "PostgreSQL is answering again" night
        return
    fi
    database_up=no
    alert night database \
        "PostgreSQL is not answering while the host is up" \
        "postgresql-down.md"
}

# 3. Order flow stalled.
#
#    Fifteen minutes, because ADR 0006's relay polls every second and backs off to
#    at most five minutes across ten attempts — about thirteen and a half minutes
#    in total — so nothing healthy is fifteen minutes old. Past that, orders are
#    not reaching the POS, and per-aggregate ordering means every later event for
#    that key is stuck behind the first one.
#
#    Three inputs, one alert, because the operator's first three runbook steps are
#    the same whichever of them fired.
check_order_flow() {
    local outbox inbox lag holding=no detail=""

    outbox="$(metric_sum horecaos_outbox_oldest_pending_age_seconds)"
    inbox="$(metric_sum horecaos_inbox_oldest_pending_age_seconds)"
    lag="$(consumer_lag)"

    if [ -n "${outbox}" ] && [ "${outbox}" -ge "${STALL_SECONDS}" ]; then
        holding=yes
        detail="${detail}oldest unpublished outbox row ${outbox}s; "
    fi
    if [ -n "${inbox}" ] && [ "${inbox}" -ge "${STALL_SECONDS}" ]; then
        holding=yes
        detail="${detail}oldest unprocessed inbox row ${inbox}s; "
    fi
    if [ -n "${lag}" ] && [ "${lag}" -ge "${CONSUMER_LAG_THRESHOLD}" ]; then
        # Lag is a count rather than an age, so unlike the two above it needs the
        # fifteen minutes measured here instead of being read off the metric.
        local held
        held="$(sustained_for consumer-lag yes)"
        if [ "${held}" -ge "${STALL_SECONDS}" ]; then
            holding=yes
            detail="${detail}consumer lag ${lag} for ${held}s; "
        fi
    else
        sustained_for consumer-lag no >/dev/null
    fi

    if [ "${holding}" = "no" ]; then
        clear_alert order-flow "order flow is moving again" night
        return
    fi
    alert night order-flow "order flow stalled: ${detail}" "outbox-not-draining.md"
}

consumer_lag() {
    compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server localhost:29092 --describe \
            --group "${HORECAOS_INBOX_GROUP_ID:-horecaos-platform}" 2>/dev/null \
        | awk '$6 ~ /^[0-9]+$/ { total += $6 } END { if (NR > 1) printf "%.0f\n", total; else print "" }'
}


# ===========================================================================
# TRADING — loud 09:00 to 23:30 Asia/Tashkent, digest outside.
# ===========================================================================

# Payment callback failing.
#
#    More than three non-200 responses in five minutes. Both providers read any
#    non-200 as a transport failure and retry until the payment reaches their
#    manual investigation queue, so this is a customer's money in a state HorecaOS
#    cannot see rather than an availability statistic. Three in five minutes
#    separates one malformed arrival from an endpoint that is down.
#
#    Not a night alert because nobody is paying at 3am and both retry windows
#    outlast the night.
check_payment_callbacks() {
    local failures delta
    # Counted here rather than through metric_sum, because the condition is a
    # conjunction over two labels — the callback roots, and a status that is not
    # 2xx — and a label filter that matched either would count every successful
    # callback as a failure.
    failures="$(awk '
        /^http_server_requests_seconds_count\{/ {
            if ($0 !~ /uri="\/providers\/(payme|click)\//) { next }
            if ($0 ~ /status="2[0-9][0-9]"/) { next }
            total += $NF
            seen = 1
        }
        END { if (seen) printf "%.0f\n", total; else print "0" }
    ' "${SCRAPE}")"

    delta="$(counter_delta payment-callback-failures "${failures}" "${CALLBACK_WINDOW_SECONDS}")"
    if [ -z "${delta}" ] || [ "${delta}" -le "${CALLBACK_FAILURES}" ]; then
        clear_alert payment-callback "payment callbacks are answering 200 again" trading
        return
    fi
    alert trading payment-callback \
        "${delta} non-200 payment callback responses in the last ${CALLBACK_WINDOW_SECONDS}s" \
        "payment-callback-failing.md"
}

# Secrets manager sealed or unreachable.
#
#    OpenBao comes back sealed after a reboot, and ADR 0028's bounded cache hides
#    that for one TTL — after which every provider call fails while HTTP still
#    reports healthy. It is the failure most likely to look fine on a dashboard
#    and be an outage in the restaurant.
#
#    Trading rather than night, which means a 3am reboot is found in the morning.
#    That is affordable only because trading has ended, and a tenant that trades
#    overnight invalidates the reasoning immediately.
check_openbao() {
    local held
    if compose exec -T openbao bao status >/dev/null 2>&1; then
        sustained_for openbao no >/dev/null
        clear_alert openbao "OpenBao is unsealed and answering" trading
        return
    fi
    # Three consecutive failures, as ADR 0023 specifies, so that a restart of the
    # container during a deploy does not report a seal.
    held="$(sustained_for openbao yes)"
    [ "${held}" -lt 120 ] && return
    alert trading openbao "OpenBao is sealed or unreachable" "openbao-sealed.md"
}

# Provider circuit stuck open.
#
#    Ten minutes, because the breakers half-open automatically after thirty
#    seconds, so ten minutes is roughly twenty failed probes and the provider is
#    genuinely down. The action is commercial — tell the restaurant to take cash —
#    which is worth doing at noon and pointless at 3am.
#
#    Payment and POS breakers only. A courier partner being down is on the
#    dashboard: it does not stop an order being taken, and the platform routes to
#    whichever partner is up.
check_provider_circuits() {
    local open_for provider
    open_for="$(metric_max horecaos_provider_circuit_open_duration_seconds 'family="(payment|pos)"')"
    if [ -z "${open_for}" ] || [ "${open_for}" -lt "${CIRCUIT_OPEN_SECONDS}" ]; then
        clear_alert circuit-open "the provider circuit has closed" trading
        return
    fi
    provider="$(metric_max_label horecaos_provider_circuit_open_duration_seconds provider 'family="(payment|pos)"')"
    alert trading circuit-open \
        "the ${provider} circuit has been open for ${open_for}s" \
        "provider-circuit-stuck-open.md"
}

# Monetary dead letter.
#
#    Any outbox or inbox row entering DEAD_LETTER on an ordering or payments
#    topic. Reaching dead letter already means about half an hour of automatic
#    retry failed, so this fires only after self-healing has lost. Restricted to
#    monetary topics because those are where waiting costs a customer their money;
#    every other dead letter is a morning item, and ADR 0006 dead-letters
#    PAYLOAD_INVALID and CONTRACT_UNSUPPORTED immediately by design, so alerting
#    on all of them would fire on correct behaviour.
check_monetary_dead_letters() {
    local total delta
    total="$(metric_sum 'horecaos_(outbox|inbox)_dead_letters' 'monetary="true"')"
    delta="$(counter_delta monetary-dead-letters "${total:-0}" "${CALLBACK_WINDOW_SECONDS}")"
    if [ -z "${delta}" ] || [ "${delta}" -le 0 ]; then
        return
    fi
    # Deliberately not cleared on recovery: a dead letter does not resolve itself,
    # it is resolved by a person through the ADR 0006 failure API. Re-notification
    # is bounded by the trading repeat interval instead.
    alert trading monetary-dead-letter \
        "${delta} new monetary dead letters; ${total} outstanding" \
        "dead-letter-decision.md"
}

# Ownership fence burst (cutover only).
#
#    One TargetWritesFencedException is the ADR 0024 gate working correctly. More
#    than ten in five minutes for one capability means routing and ownership
#    disagree — writes are arriving at a platform that believes legacy owns the
#    capability — and every one of them is a customer action that did not happen.
#
#    Outside a cutover the counter does not move, so this costs nothing to leave
#    enabled and cannot be forgotten when a cutover starts.
check_fence_burst() {
    local total delta capability
    total="$(metric_sum horecaos_migration_writes_fenced_total)"
    [ -z "${total}" ] && return
    delta="$(counter_delta fenced-writes "${total}" "${FENCE_WINDOW_SECONDS}")"
    if [ -z "${delta}" ] || [ "${delta}" -le "${FENCE_BURST}" ]; then
        clear_alert fence-burst "fenced writes are back to normal" trading
        return
    fi
    capability="$(metric_max_label horecaos_migration_writes_fenced_total capability)"
    alert trading fence-burst \
        "${delta} writes fenced in ${FENCE_WINDOW_SECONDS}s, worst capability ${capability}" \
        "migration-scope-fencing-writes.md"
}

# A container autoheal could not fix.
#
#    ADR 0034 is explicit that a page whose resolution is "restart it" is a bug in
#    the automation rather than an entry in a runbook, so this deliberately does
#    not fire on a container being unhealthy — autoheal restarts those within a
#    minute. It fires when the restart did not work: a container that autoheal has
#    restarted three or more times since the last probe is in a crash loop, and a
#    crash loop is the state restarting cannot leave.
check_crash_loops() {
    local service container restarts delta
    for service in $(compose ps --services 2>/dev/null); do
        container="$(compose ps -q "${service}" 2>/dev/null | head -1)"
        [ -z "${container}" ] && continue
        restarts="$(docker inspect --format '{{.RestartCount}}' "${container}" 2>/dev/null)"
        [ -z "${restarts}" ] && continue
        delta="$(counter_delta "restarts-${service}" "${restarts}" 600)"
        [ -z "${delta}" ] && continue
        if [ "${delta}" -ge 3 ]; then
            alert trading "crash-loop-${service}" \
                "${service} has restarted ${delta} times in ten minutes; the restart is not fixing it" \
                "container-crash-loop.md"
        else
            clear_alert "crash-loop-${service}" "${service} has stopped restarting" trading
        fi
    done
}


# ===========================================================================
# MORNING — silent, read at the start of the working day.
# ===========================================================================

# Backup did not run.
#
#    Twenty-six hours: a daily schedule plus two hours of grace for a slow dump.
#    ADR 0034 requires alerting on a backup that did not run rather than only on
#    one that failed, because a job that never fires produces no failure to
#    observe — and the day that matters is the day something else has already
#    broken.
check_backup() {
    if [ ! -r "${BACKUP_STAMP}" ]; then
        alert morning backup "no backup stamp at ${BACKUP_STAMP}" "restore.md"
        return
    fi
    local modified age_hours
    modified="$(stat -c %Y "${BACKUP_STAMP}" 2>/dev/null || stat -f %m "${BACKUP_STAMP}" 2>/dev/null || echo 0)"
    age_hours=$(( ( NOW - modified ) / 3600 ))
    if [ "${age_hours}" -gt "${BACKUP_MAX_AGE_HOURS}" ]; then
        alert morning backup "the last successful backup was ${age_hours}h ago" "restore.md"
    fi
}

# Data volume above 85%.
#
#    PostgreSQL, Kafka segments, audit partitions and the trace store all grow
#    monotonically, and the slope is usually days. The recovery order — expire
#    Kafka segments, prune backups past retention, drop leftover rehearsal
#    databases, extend the volume — needs a working day, and 15% buys that day.
#    Only the last of those steps needs the facility, which is why the order
#    matters more than the threshold does.
check_disk() {
    local used
    # `df -P` rather than `df --output=pcent`: the portable form works on the
    # host and on a laptop, and this script is only trustworthy if it can be run
    # somewhere other than production before it is trusted in production.
    used="$(df -P "${HORECAOS_DATA_VOLUME:-/}" 2>/dev/null | awk 'NR == 2 { gsub("%", "", $5); print $5 }')"
    [ -z "${used}" ] && return
    if [ "${used}" -ge "${DISK_THRESHOLD_PERCENT}" ]; then
        alert morning disk "the data volume is ${used}% full" "disk-filling.md"
    fi
}

# TLS certificate expiring.
#
#    Renewal is automatic; this catches the automation failing. A silently failed
#    renewal is a total outage on a date known weeks in advance, which makes it
#    the least excusable outage available. Seven days is several Caddy renewal
#    attempts plus a working day.
check_certificate() {
    local host expiry_epoch days
    host="${HORECAOS_API_HOSTNAME:-}"
    [ -z "${host}" ] && return
    expiry_epoch="$(echo | openssl s_client -servername "${host}" -connect "${host}:443" 2>/dev/null \
        | openssl x509 -noout -enddate 2>/dev/null \
        | cut -d= -f2)"
    [ -z "${expiry_epoch}" ] && return
    local expiry_seconds
    expiry_seconds="$(date -d "${expiry_epoch}" +%s 2>/dev/null \
        || date -j -f '%b %d %T %Y %Z' "${expiry_epoch}" +%s 2>/dev/null \
        || echo "${NOW}")"
    days=$(( ( expiry_seconds - NOW ) / 86400 ))
    if [ "${days}" -lt "${CERTIFICATE_MIN_DAYS}" ]; then
        alert morning certificate "the TLS certificate for ${host} expires in ${days} days" "deploy.md"
    fi
}

# An onboarding run has stopped moving.
#
#    One hour, and the number comes from what the ADR 0008 workflow does when it
#    is healthy rather than from how long a tenant may reasonably take to go
#    live. The scheduler polls every five seconds; a step claim is leased for
#    five minutes and a dead worker's step is handed back at the next poll after
#    that; a retry backs off by at most sixteen seconds before the fifth attempt
#    fails outright. So the longest a healthy step is due and untouched is one
#    expired lease plus one poll — a little over five minutes — and handing the
#    step back resets the age this gauge reads. An hour is ten of those. It
#    survives a deploy, a JVM restart, and a database maintenance window without
#    putting a line in the digest, and the margin costs nothing because the tier
#    is a message read at 09:00 anyway.
#
#    Morning rather than trading, because the tenant this concerns is by
#    definition not live: no orders are flowing through it, no customer's money
#    is in flight, and the action — read the run, fix the cause, resume it — is a
#    working-day action performed by the same person either way. The night budget
#    is spent and ADR 0034 caps it at three.
#
#    The gauge deliberately excludes a run parked on TENANT_ACTIVATE, which is
#    waiting for a platform administrator and is not stuck at all. That exclusion
#    is the whole value of this alert: one that fires because a person has not
#    decided yet is one that teaches the person to ignore it, and after that it
#    reports nothing.
check_onboarding_stalled() {
    local age
    age="$(metric_max horecaos_onboarding_runs_stalled_age_seconds)"
    [ -z "${age}" ] && return
    # -1 is the gauge's own "I could not read the database" sentinel rather than
    # an age. That is check_database's alert, not this one's, and treating it as
    # an age would compare a negative number against the threshold and silently
    # never fire.
    [ "${age}" -lt 0 ] && return
    [ "${age}" -lt "${ONBOARDING_STALL_SECONDS}" ] && return
    alert morning onboarding-stalled \
        "an onboarding run has not moved for ${age}s" \
        "onboarding-run-stalled.md"
}


# ===========================================================================
# Run
# ===========================================================================

# The database check comes first and does not need the scrape. If PostgreSQL is
# down the application's metrics are stale or absent, and evaluating backlog
# thresholds against stale gauges would raise a second night alert for the same
# fault — spending two of the three on one failure.
check_database

# Everything below the database check is suppressed while the database is down,
# and this is the budget being defended rather than an optimisation. Every gauge
# the backlog and dead-letter alerts read is computed by polling PostgreSQL, so a
# database outage freezes them at their last values and then, once the poller
# starts failing, ages the outbox row that cannot be published anyway. Left
# unguarded, one fault would raise two of the three night alerts and the operator
# would be woken twice for something they are already awake for.
if [ "${database_up}" = "no" ]; then
    :
elif scrape; then
    check_order_flow
    check_payment_callbacks
    check_provider_circuits
    check_monetary_dead_letters
    check_fence_burst
    # A morning check, grouped here rather than below with the other three
    # because it reads a gauge: without the scrape it has nothing to evaluate,
    # and against a stale one it would report an age that stopped advancing.
    check_onboarding_stalled
else
    # No scrape and the database is up: the application is not answering from
    # inside the network. That is the off-box uptime check's alert, not a fourth
    # night alert of its own, so it goes to the digest and the external check
    # does the waking.
    defer_to_digest app-unreachable \
        "horecaos: the application did not answer /actuator/prometheus from inside the network"
    firing=1
fi

check_openbao
check_crash_loops
check_backup
check_disk
check_certificate

send_digest_if_due

# The ping is the platform's proof of life, and it is sent whether or not
# something is firing. Withholding it for a morning-tier problem — a disk at 86%,
# say — would turn the dead-man's switch into a fourth night alert and break the
# budget ADR 0034 fixed at three. The one exception is a night alert whose push
# failed, where silence is the only remaining channel.
if [ "${night_alert_failed_to_send}" -eq 0 ]; then
    [ -n "${HORECAOS_HEARTBEAT_URL:-}" ] \
        && curl -fsS --max-time 20 --retry 3 "${HORECAOS_HEARTBEAT_URL}" >/dev/null 2>&1
fi

exit "${firing}"
