# Alerts

Every alert this platform can raise, what it means, and the one runbook section
that answers it. ADR 0023 requires that mapping to be exactly one-to-one: an
alert with two possible runbooks is an alert you have to think about at 3am, and
the whole design assumes you are not able to.

**Last executed:** never. Every runbook in this directory except `deploy.md` and
`restore.md` is a draft until it has been run once, as a game day or during a
real incident, and its own header records that date.

## The three that wake you

ADR 0034 caps this at three. A fourth is available only by removing one of these
and saying which.

| Alert | Where it is evaluated | Runbook |
|---|---|---|
| Platform unreachable | Off the box: the external uptime check fails twice consecutively | [deploy.md](deploy.md), "It did not come up" — after checking the host answers at all |
| PostgreSQL down while the host is up | On the box: `horecaos-probe.sh` | [postgresql-down.md](postgresql-down.md) |
| Order flow stalled | On the box: outbox age, inbox age, or consumer lag past 15 minutes | [outbox-not-draining.md](outbox-not-draining.md) |

## Trading hours, 09:00–23:30 Asia/Tashkent

Loud inside the window, in the morning digest outside it.

| Alert | Runbook |
|---|---|
| Payment callback failing | [payment-callback-failing.md](payment-callback-failing.md) |
| Secrets manager sealed or unreachable | [openbao-sealed.md](openbao-sealed.md) |
| Provider circuit stuck open | [provider-circuit-stuck-open.md](provider-circuit-stuck-open.md) |
| Monetary dead letter | [dead-letter-decision.md](dead-letter-decision.md) |
| Ownership fence burst (cutover only) | [migration-scope-fencing-writes.md](migration-scope-fencing-writes.md) |
| A container the watchdog could not fix | [container-crash-loop.md](container-crash-loop.md) |

## Morning digest

| Alert | Runbook |
|---|---|
| Backup did not run | [restore.md](restore.md), "The backup did not run" |
| Data volume above 85% | [disk-filling.md](disk-filling.md) |
| TLS certificate expiring within 7 days | [deploy.md](deploy.md), certificates |
| An onboarding run has not moved for an hour | [onboarding-run-stalled.md](onboarding-run-stalled.md) |

## Not an alert, on purpose

Single request failures. Individual retries. Latency percentiles. CPU and
memory. Consumer lag below the age threshold. Cache hit rate. A breaker opening,
as opposed to staying open. Non-monetary dead letters. An onboarding run sitting
in `READY` while it waits for a platform administrator to activate it — that is a
person who has not decided yet rather than a workflow that has stopped, and the
gauge behind the stalled-run alert excludes it by name for exactly that reason.

Each of these would be an interruption you cannot act on, and that is the
mechanism by which a pager stops being read. The cost is stated in ADR 0023 and
is real: **some problems will be found by a customer telephoning before they are
found by a graph.**

## Before any of them, during cutover

While ADR 0024's programme is running, two systems serve one business and the
first question is always the same one:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT capability, state, write_mode, read_mode, state_entered_at
     FROM migration.scopes ORDER BY capability"
```

Who owns the capability decides whether the thing you are looking at is even
supposed to be working. Answer it from that table and not from memory.

## Lost the laptop?

That is not an alert. It is [laptop-lost.md](laptop-lost.md), and it is the one
procedure here you cannot run from the laptop.
