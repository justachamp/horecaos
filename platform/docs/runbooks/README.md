# Runbooks

These are written for one specific reader: the person who built this, at 3am,
having forgotten how it works, alone, and not in the mood to reason from first
principles.

That shapes them more than any style guide would.

- **Commands are complete and copy-pasteable.** No `<fill this in>` where a real
  value could have been named, no "adjust as appropriate".
- **Every step says what to check before the next one.** A procedure you cannot
  verify halfway through is a procedure you have to run to the end before finding
  out it was wrong.
- **Every procedure says how to get back.** If there is no way back, it says so
  in those words, at the top, before the first command.
- **Explanations come after the commands, not before.** When you need this you
  need the command; the reason it works is useful the following morning.

| Runbook | When |
|---|---|
| [alerts.md](alerts.md) | **Start here when something woke you.** Every alert, its tier, and the one runbook that answers it |
| [deploy.md](deploy.md) | Shipping a release, bootstrapping a host, rolling back, and everything that goes wrong in between |
| [restore.md](restore.md) | The database is gone, corrupted, or a migration has to be undone |
| [postgresql-down.md](postgresql-down.md) | Night alert: the database is not answering while the host is |
| [outbox-not-draining.md](outbox-not-draining.md) | Night alert: order flow stalled — outbox age, inbox age, or consumer lag |
| [payment-callback-failing.md](payment-callback-failing.md) | Non-200s from the Payme or Click callback roots |
| [openbao-sealed.md](openbao-sealed.md) | OpenBao is sealed, which it is after every reboot |
| [provider-circuit-stuck-open.md](provider-circuit-stuck-open.md) | A payment or POS breaker has stayed open for ten minutes |
| [dead-letter-decision.md](dead-letter-decision.md) | A message reached `DEAD_LETTER` and needs a person |
| [migration-scope-fencing-writes.md](migration-scope-fencing-writes.md) | Cutover: a burst of writes refused by the ADR 0024 gate |
| [container-crash-loop.md](container-crash-loop.md) | The watchdog restarted it and the restart did not fix it |
| [disk-filling.md](disk-filling.md) | The data volume is above 85% |
| [onboarding-run-stalled.md](onboarding-run-stalled.md) | An ADR 0008 onboarding run has not moved for an hour |
| [customers-cannot-sign-in.md](customers-cannot-sign-in.md) | No SMS is arriving, or a customer cannot get past the code screen |
| [laptop-lost.md](laptop-lost.md) | Revoking your own access from the second device |
| [control-band-response.md](control-band-response.md) | A control band breached and an agent wrote an `intent.md` to triage |

**A runbook that has never been executed is a draft.** Each file above carries a
`Last executed` line in its header, and every one except `deploy.md` and
`restore.md` currently reads `never`. ADR 0023 requires each to be exercised once
— as a game day or during a real incident — before it counts, and the date to go
in that line is the date it was actually run rather than the date it was written.

## The two numbers to know before reading anything else

**Recovery point.** Backups run daily. Losing the database means losing up to
24 hours of orders. This is a choice, not a limit: continuous archiving would
reduce it to minutes and has not been set up.

**Recovery time.** Unmeasured. The restore path was exercised against this exact
production stack on 2026-08-23 and completed in under a minute — against a
database holding 234 empty tables. That number is worthless as a production
estimate and must be re-measured against real data volume, then written down
here and in `infra/backup/README.md`.

An unmeasured recovery time is a guess, and a guess is what turns a bad incident
into a surprising one.

## What has no runbook, and why

**Failover.** There is nothing to fail over to. One machine, one disk, one of
everything. If the host is gone, the procedure is `restore.md` onto new hardware,
and it will take hours.

**Scaling.** Manual, by editing `deploy.resources.limits` in
`compose.production.yaml` and redeploying. There is no autoscaling and there is
no second node.

**Zero-downtime deploys.** There are none. `deploy.sh` stops the application
container and starts a new one; the gap is roughly the JVM's startup time.
Caddy holds requests for up to ten seconds across the swap, which covers a clean
restart and does not cover a slow one.

These are the stated ceiling of Docker Compose on a single VM, not oversights.
The ceiling is accepted because the alternative — a Kubernetes control plane
operated by the same one person who operates six stateful dependencies — buys
rolling deploys at the price of a much larger thing to be woken up by.

**Revisit the moment any one of these becomes true**, and treat it as an ADR,
not a weekend:

1. **The quietest hour of the week still carries one order per minute.** Today
   there is a window at 03:00 Tashkent where a sixty-second gap costs nothing.
   When there is not, "deploy at night" has stopped being an answer.
2. **A second application host exists, for any reason at all** — a read replica
   that must be promoted, a staging box that has to match production, a second
   colocation. Compose does not coordinate two machines, and the first attempt
   to make it do so is where this stops being a small system.
3. **Two incidents in one quarter were caused by what this cannot do** — a
   deploy gap that dropped orders, or a failure that a restart policy plus
   autoheal could not recover from. Once is bad luck; twice in a quarter is the
   topology telling you its answer.
