# ADR 0023: Production operating model, observability, security, and recovery

- Decision status: Accepted
- Implementation status: Partial — the alerts, the probes, the watchdog, and the runbooks are built. `uz.horecaos.platform.observability` publishes the gauges; `infra/observability/horecaos-probe.sh` evaluates every alert in the table below at its stated threshold and tier and pings the dead-man's switch; `management.endpoint.health.group` splits liveness, readiness, and the external customer probe; the platform runbooks are written. **Two deployment trees exist as of wave 55**, kept in parity rather than one left to drift: `infra/production/` with `compose.production.yaml`, this record's original, and `deploy/`, ADR 0061's newer registry-pull tree — see `deploy/README.md`'s "Relationship" section for which a new deploy should use and why the other is still kept (the alert, backup and restore fabric is still wired to the original). **Wave 55 closed the edge hardening** this record's checklist named as open: body caps and per-binding/per-IP rate limits on both Caddyfiles, the latter needing a purpose-built image (`infra/production/caddy/Dockerfile`) because stock `caddy:2.10-alpine` carries no `rate_limit` directive at all. The Payme allowlist's *mechanism* is built and **fails closed**, which means it currently rejects every Payme callback, genuine ones included, until the owner supplies the real address list — see the reverse-proxy checklist line. **Wave 58 closed the `app`/`worker` role split's application half**: `horecaos.runtime.role` gates `SchedulingConfiguration`'s single `@EnableScheduling`, so every `@Scheduled` method — thirty-two classes, forty-one methods, not the twenty-one this line once counted — stops uniformly under role `app` regardless of its own switch, and the fourth named switch (`horecaos.messaging.inbox.listener.enabled`, guarding a `@KafkaListener` the split never reached) now carries the same gate. Wave 58 reports rather than papers over two things: the compose wiring that runs a second container is not written, and `PosOrderExportTrigger.dispatchPending` and `RealtimeStreamMaintenance.tick`/`onGrantChanged` hold in-process state tying them to whichever process serves HTTP and SSE, so a strict `app`/`worker` split is **not yet safe to deploy** until they move to a durable handoff — see Runtime shape. **Four things remain unbuilt and are enumerated under "What is not built yet" below**: the off-box half (two external services), the single dashboard, traces, and one alert that cannot be implemented as specified — dead letters by `FailureCategory` on the outbox side, which has no column to group by. **Seven checklist items remain open**: Prometheus, Alertmanager and the dashboard; Payme's actual address list; the WireGuard and key-only-SSH host configuration; the laptop-loss rehearsal; the OpenBao AppRole file mounts; the restore rehearsal's money reconciliation; the cutover suppression window and ownership panel; and the external vulnerability scan
- Date proposed: 2026-08-19
- Date decided: 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture, and the person who carries the pager)
- Depends on: ADR 0004–0007, ADR 0024, ADR 0027, ADR 0028, ADR 0029, ADR 0033, ADR 0034
- Supersedes / Superseded by: —
- Open inputs: none
- Closed inputs: hosting, orchestrator, off-site backups, and the recovery-time table are settled in ADR 0034 (2026-08-23); the platform is operated by one person; Clopos is the pilot's POS

## Context

> **Scope narrowed 2026-08-20.** Controls that later ADRs need long before
> production readiness moved out of this record: secrets to
> [ADR 0028](../partial/0028-secrets-management-and-credential-lifecycle.md), audit and
> approvals to [ADR 0027](../partial/0027-audit-evidence-and-approval-model.md), caching and
> rate limiting to [ADR 0033](../partial/0033-caching-rate-limiting-and-shared-runtime-state.md),
> and hosting, environments, and residency to
> [ADR 0034](../partial/0034-hosting-environments-topology-and-data-residency.md).

The first draft of this record described an operating model for a team: an
on-call rotation with somebody to escalate to, five separately scaled runtime
roles, autoscaling on queue depth, error budgets computed from traffic the
platform does not have, and highly available PostgreSQL and Kafka underneath.
None of that is wrong in principle. All of it is wrong here.

[ADR 0034](../partial/0034-hosting-environments-topology-and-data-residency.md) settled the
ground on 2026-08-23 and **is the authority for everything about the machine**:
a colocated server in Tashkent, Docker Compose on VMs rather than Kubernetes,
one operator, off-site backups gated before the first live tenant order, the
single-point-of-failure table with its honest recovery times, and the split
between what is watched on the box and what is watched from outside it. That
record also fixes the two constants this one has to spend: a **night-alert
budget of three**, and the rule that a page whose resolution is "restart it" is
a bug in the automation rather than an entry in a runbook.

This record is what runs on top of that machine. It decides what the platform
measures, which of those measurements are allowed to make a noise, what recovers
itself and what cannot, how the box is reached and how that access is revoked,
and what a restore rehearsal has to prove before anyone may call it a backup.

Three facts about the platform shape those answers more than the hosting does:

- **Money moves through two public endpoints that cannot use the platform's own
  authentication.** Payme and Click both read any non-200 as a transport failure
  and retry until the payment reaches their manual investigation queue (ADR
  0013). A 5xx there is not an availability statistic; it is a customer's money
  in a state Qoida cannot see.
- **The messaging spine already recovers from most of what goes wrong.** ADR
  0006's classification, backoff, leases, and dead-letter semantics, the outbox
  relay's lease reclaim, and the per-provider circuit breakers cover the large
  majority of transient failures without anyone waking up. The operating model's
  job is to name what falls outside that set, not to reinvent it.
- **During cutover, two systems serve one business.** ADR 0024's ownership gate
  decides which one may write, per capability and per scope, failing closed.
  That is an operating condition for the whole cutover period, and it changes
  what "is it down" even means.

The pilot is one tenant on Clopos, trading until roughly 23:00. Other POS
vendors follow. Thresholds below are absolute numbers rather than rates or error
budgets, because a rate needs traffic this platform will not have for a while.

## Decision

Operate the ADR 0034 machine with the smallest set of signals that answers three
questions at 3am — **is it down, is it losing money, is it losing data** — and
put effort into automatic recovery ahead of paging.

- **Three alerts may wake the operator**, as ADR 0034 sets. This record spends
  that budget, adds a trading-hours tier for the signals whose action is
  meaningless while restaurants are closed, and sends everything else to a
  morning digest. Every alert names its threshold and the reason for it.
- **What can heal itself does**, through restart policies, the watchdog, leases,
  backoff, and the existing circuit breakers. **What cannot is enumerated** with
  the runbook step that recovers it. That second list is the honest part.
- **The box is reached over WireGuard and nothing else**, with a second key on a
  second device, so losing the laptop is a revocation and not a lockout.
- **The two payment callbacks stay reachable and protected** by measures that are
  never allowed to reject a genuine callback.
- **A restore proves itself with money**, not with an exit code, and a rehearsal
  that misses its recorded time reopens the number instead of producing an
  explanation.
- **Cutover is an operating mode**, with its own alert, its own first runbook
  question, and its own suppression rule.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Managed observability (Grafana Cloud, Datadog, and similar) | Not rejected on residency: ADR 0029 makes telemetry PII-free by construction, so it could legally leave, and ADR 0034 already enumerates what does. Rejected because it is a foreign-currency subscription contracted from Uzbekistan — the same practical obstacle that keeps AWS at arm's length — and because at one host, an uptime checker plus a dead-man's switch answers the only question a hosted plane would answer better | International contracting becomes routine, or the host count exceeds one, at which point watching the failure domain from outside it stops being optional |
| A second machine dedicated to monitoring | Doubles the hardware and the operating surface in order to watch one machine, and answers a question the external uptime check already answers for a few dollars a month | A second machine exists for another reason — a standby PostgreSQL being the likely one — at which point monitoring belongs on it and is nearly free |
| An on-call rotation with an escalation policy | There is one person. An escalation policy whose every tier terminates at the same phone is theatre, and writing one manufactures an impression of coverage that does not exist. ADR 0034's credential escrow is the real answer to the operator being unavailable | A second operator exists |
| Paging on SLOs and error budgets | An error budget needs enough traffic for a rate to be stable. One pilot tenant produces days where three failed requests are a 30% error rate, and a page derived from that is noise with arithmetic in front of it | A month in which the platform serves more than 10,000 orders |
| A fourth night alert | ADR 0034 fixes the budget at three and requires that a fourth displace one deliberately. The pressure to add a fourth came from the payment callbacks and from a sealed OpenBao; both were placed in the trading-hours tier instead, because neither has an action worth taking while every restaurant is shut | A signal appears whose 3am action is real and whose absence loses money overnight. Then remove one and say which |
| Five runtime roles as five containers | Five containers on one host, three nearly idle, multiplies exactly the misconfiguration this record's negative consequences warn about: a role that silently enables nothing. Two long-running containers make the same mistake visible within a minute | A role's measured resource profile conflicts with another's, or the ADR 0034 second-machine trigger fires |
| Liveness probes that check dependencies | Unchanged from the first draft and more important now: a slow database that restarts the only application container converts a degradation into an outage, with nothing to fail over to | Never |
| Tenant identifier as a metric label | Unbounded cardinality on a Prometheus sharing a disk with PostgreSQL. Tenant stays a log and trace field, as ADR 0033 already requires of cache metrics | Never |
| An aggregated log stack (Loki, ELK) | A second datastore to operate, back up, and keep from filling the disk PostgreSQL is on, in order to search the logs of one host. Rotated `json-file` plus `grep` answers the questions at this size | Logs live on more than one machine, or an incident needs correlation that grep cannot do |
| Alerting on every dead letter | ADR 0006 dead-letters `PAYLOAD_INVALID` and `CONTRACT_UNSUPPORTED` immediately by design, so this fires on correct behaviour. Only the monetary topics carry a cost that cannot wait for morning | Dead letters become rare enough that any of them is worth a look, which would itself be evidence the classification is wrong |

## Runtime shape

One signed image, three services on the production VM, one internal network:

```text
migrate   one-shot Flyway job; must exit 0 before app or worker start
app       HTTP, Camel provider routes, the two payment callback roots
worker    outbox relay, Kafka inbox consumers, order timers, scheduled jobs
```

ADR 0034's five role configurations survive as configuration; the process count
does not. What ran this shape was, until wave 58, four scattered switches this
record's own Implementation status had already found stale: three guarded one
`@Scheduled` class each and the fourth guarded a `@KafkaListener`, while
twenty-plus schedulers added since carried none of the four and simply ran
wherever the process started. The mechanism is now `horecaos.runtime.role`
(`app` | `worker` | `both`, default `both`, `RuntimeRole` in
`uz.horecaos.platform.configuration`): it gates `SchedulingConfiguration`'s one
`@EnableScheduling` directly, so `app` removes the
`ScheduledAnnotationBeanPostProcessor` from the context and every `@Scheduled`
method on every module stops uniformly — including a job added after this
paragraph was written — regardless of that job's own per-feature switch. It
additionally gates the two ADR 0006 inbox `@KafkaListener`s
(`TenancyEventListener`, `FulfillmentCommandListener`) alongside their existing
`horecaos.messaging.inbox.listener.enabled` switch, which is the fourth switch
this record named as uncovered. The other three named switches
(`horecaos.messaging.outbox.enabled`, `horecaos.ordering.workers.enabled`,
`horecaos.api.idempotency.purge.enabled`) are unchanged: they still exist as
independent operational kill-switches and now compose with the role (both must
allow a job to run) rather than substitute for it.

**The compose wiring this leaves for whoever settles `deploy/` vs.
`platform/infra/production/` (wave 55) is exactly two environment variables and
a routing decision, nothing more:** an `app` service keeps `HORECAOS_RUNTIME_ROLE`
unset (or `both`) and stays behind the proxy exactly as today; a `worker`
service sets `HORECAOS_RUNTIME_ROLE=worker`, carries no `ports:` mapping, and
receives no proxied traffic, per the runtime shape above. Nothing else in
either compose file needs to change for the split to take effect.

**Two scheduled jobs are not worker-shaped, and the gate is all-or-nothing, so
neither has a clean answer yet.** `PosOrderExportTrigger.dispatchPending` drains
an in-process queue that only the process which served the confirming HTTP
request ever fills; `RealtimeStreamMaintenance.tick` and its `onGrantChanged`
listener drive SSE connections that exist only on whichever process is holding
them open. Setting the `app` container's role to `app` — the strict split this
runtime shape describes — would silently stop POS ticket dispatch and SSE
stream maintenance for every request `app` itself served: no error, no failing
health check, just tickets that never reach a till and dashboards that never
update. Leaving `app` at `both` avoids that regression but runs every other
scheduler in `app` too, which gives up the resource separation the split
exists for. Neither is a real fix; the actual fix is a redesign onto a durable,
cross-process handoff (an outbox-driven POS export queue; realtime delivery
that does not depend on which process holds the socket), which is out of this
record's scope. Until that redesign lands, treat `app: app` / `worker: worker`
as **not yet safe to deploy** — the split is proven correct for every other
scheduled job in this build, and incorrect for exactly these two.

`scheduler` and `integration` remain profiles rather than
containers, so ADR 0028 can still issue an identity per role and grant
`migration` no provider credentials.

No application container runs Flyway at startup. `migrate` is a separate service
run first, and a non-zero exit stops the deploy. Expand-migrate-contract still
governs schema change: every migration is backward compatible with the running
image, contraction happens a release later, and applied migrations roll forward
rather than back. That compatibility is what makes ADR 0034's rollback row —
"none, if the migration was backward-compatible as required" — true rather than
hopeful.

Deploy follows ADR 0034's protocol: run `migrate`, recreate, inside the
maintenance window after 23:30 Asia/Tashkent, under 90 seconds, measured every
time rather than assumed. Rollback is the previous image tag. The Compose
ceiling and the triggers that end this arrangement are ADR 0034's table and are
not restated here.

## Observability sized for one person

### The three questions, and the smallest set of signals that answers them

```text
is it down?             an external probe reaching the platform from outside the box
is it losing money?     payment callbacks failing, order flow stalled, a provider's
                        circuit stuck open
is it losing data?      the outbox not draining, a monetary dead letter, a backup that
                        did not happen, a disk about to stop accepting writes
```

Everything measured serves one of those three, or serves the diagnosis after an
alert has already fired. Nothing is measured because it is measurable.

### What is measured

The counters that exist today — `horecaos.outbox.publications`,
`horecaos.inbox.records`, `horecaos.notifications.provider.calls`,
`horecaos.authorization.shadow` — plus the gauges this record requires and which do
not exist yet: outbox and inbox oldest-pending age, dead-letter counts by
`FailureCategory`, circuit-breaker state per provider, orders by state with the
age of the oldest, free space on the data volume, and during cutover the count
of `TargetWritesFencedException` by capability and the replicator's lag.

Label rules are unchanged and non-negotiable: bounded label sets, never a tenant
identifier, and no personal data in any metric, log, trace, or dead-letter
summary under ADR 0029. Tenant, brand, location, and correlation identifiers are
searchable log and trace fields, which is a different thing.

Traces are OpenTelemetry carrying the correlation identifier the log pattern
already emits, sampled for a machine whose trace store shares a disk with the
database: 5% of successful requests, 100% of errors, and 100% of
`/providers/**`, because a payment callback that went wrong is the one request
whose whole story is always worth the bytes.

Logs are structured JSON on the `json-file` driver **with `max-size` and
`max-file` set**. ADR 0034 names a disk filled by logs as the most likely
self-inflicted night outage on this topology; the driver's default is unbounded,
so this is the line of configuration that prevents it.

### One dashboard

One page, called "is it working": external check status, request rate and 5xx by
route group, orders by state, outbox and inbox oldest age, dead letters by
category, breaker state per provider, free disk, backup age, and — during
cutover only — the ownership table from `migration.scopes` and the replicator's
lag. One page, because a solo operator will keep one dashboard honest and will
not keep nine.

### The alerts

Three tiers. **Night** wakes the operator at any hour and is capped at three by
ADR 0034. **Trading hours** is loud between 09:00 and 23:30 Asia/Tashkent and
falls back to the digest outside them. **Morning** is a Telegram message with no
sound, read at the start of the next working day.

| Alert | Condition | Tier | Why this threshold |
|---|---|---|---|
| Platform unreachable | The external uptime check fails twice consecutively, roughly two minutes | night | One failed probe is a blip on a link into Uzbekistan. Two consecutive is longer than any restart this platform performs, including the 90-second deploy window, so it is never a deploy |
| PostgreSQL down while the host is up | The database is unreachable while the machine answers | night | It is the availability anchor: nothing degrades gracefully without it. It also subsumes the full-disk case, because a data volume at 100% presents as PostgreSQL refusing writes, which is why 100% needs no night alert of its own |
| Order flow stalled | Oldest `PENDING` outbox row, oldest pending inbox row, or Kafka consumer lag above threshold for 15 minutes | night | The relay polls every second and backs off to at most 5 minutes across 10 attempts, so nothing healthy is 15 minutes old. Past that, orders are not reaching the POS and ADR 0006's per-aggregate ordering means every later event for that key is stuck behind the first one |
| Payment callback failing | More than 3 non-200 responses from `/providers/payme/*` or `/providers/click/*/{prepare,complete}` in 5 minutes | trading | Both providers retry a non-200 until the payment reaches their manual investigation queue. Three in five minutes separates one malformed arrival from an endpoint that is down. Not a night alert because nobody is paying at 3am, and the retry windows outlast the night |
| Secrets manager sealed or unreachable | OpenBao reports sealed, or three consecutive health probes fail | trading | OpenBao comes back **sealed** after a reboot. ADR 0028's bounded cache hides that for one TTL, after which every provider call fails while HTTP still reports healthy — the failure most likely to look fine on a dashboard and be an outage in the restaurant. A 3am reboot is therefore found in the morning, which is affordable only because trading has ended |
| Provider circuit stuck open | A payment or POS breaker open continuously for 10 minutes | trading | The breakers half-open automatically after 30 seconds, so 10 minutes means roughly twenty probes have failed and the provider is genuinely down. The action is to tell the restaurant to take cash, which is worth doing at noon and pointless at 3am |
| Monetary dead letter | Any outbox or inbox row entering `DEAD_LETTER` on an ordering or payments topic | trading | Reaching dead letter already means about half an hour of automatic retry failed, so this fires only after self-healing lost. Restricted to monetary topics because those are where waiting costs a customer their money; every other dead letter is a morning item |
| Backup did not run | No dead-man's-switch ping in 26 hours | morning | Daily schedule plus two hours of grace for a slow dump. ADR 0034 requires alerting on a backup that did not run rather than only on one that failed, because a job that never fires produces no failure to observe |
| Data volume above 85% | Free space below 15% | morning | PostgreSQL, Kafka segments, audit partitions, and the trace store all grow monotonically, and the slope is usually days. The recovery order — expire segments, prune backups, drop rehearsal databases, extend the volume — needs a working day, which 15% buys |
| TLS certificate expiring | Fewer than 7 days remain | morning | Renewal is automated; this catches the automation failing. A silently failed renewal is a total outage on a date known weeks in advance, which makes it the least excusable outage available |
| Ownership fence burst (cutover only) | More than 10 `TargetWritesFencedException` in 5 minutes for one capability | trading | One fenced write is the ADR 0024 gate working correctly. A burst means routing and ownership disagree — writes are arriving at a platform that believes legacy owns the capability — and every one of them is a customer action that did not happen |

### What is deliberately not an alert

Single request failures. Individual retries. Latency percentile regressions. CPU
and memory. Consumer lag below the age threshold. Cache hit rate. A breaker
opening, as opposed to staying open. Non-monetary dead letters. Each is on the
dashboard, and each as a page would be an interruption nobody can act on, which
is the mechanism by which a pager stops being read.

### Runbooks

ADR 0034 sets the form — how to tell whether it is the right runbook,
copy-pasteable commands with absolute paths, where the credentials live, how to
tell it worked, and what to do when OpenBao is the thing that is down — and
names the minimum set. This record adds the platform-specific ones and two
rules:

- **Every alert links to exactly one runbook section**, and that section's first
  line is a command rather than an explanation.
- **A runbook that has never been executed is a draft.** Each is exercised once
  as a game day or during a real incident before it counts, and its header
  carries the date it was last executed.

The platform-specific set, beyond ADR 0034's: the outbox is not draining; a dead
letter needs a decision; a provider circuit is stuck open; a migration scope is
fencing writes; the laptop is lost.

## What self-heals, and what does not

Restart policies and health checks cover a crashed process. They cover very
little else, and the second column is the point of this table.

| Failure | Recovers itself | Mechanism, or what the runbook does |
|---|---|---|
| Application process crash or OOM | yes | `restart: unless-stopped` plus ADR 0034's watchdog, which recreates the container after three consecutive failed health checks with backoff and pages only if the restart did not fix it. The process holds no business state: outbox leases expire, `SKIP LOCKED` claims release, and the Kafka offset was never acknowledged |
| Host reboot or power loss | partly | Containers return with the Docker daemon. **OpenBao does not** — it comes back sealed and needs the unseal material by hand. This is the gap that makes unattended reboot recovery a claim this record does not make |
| Worker dying mid-batch | yes | The outbox lease (5 minutes) expires and the row is reclaimed; the unacknowledged Kafka record is redelivered; order timers are claimed `FOR UPDATE SKIP LOCKED` and pass to the next claimant |
| Transient broker or provider failure | yes | ADR 0006's exponential backoff with jitter, 1 second to 5 minutes across 10 attempts, driven by the shared `FailureCategory` classification rather than by each handler's judgement |
| One provider down | contained, not fixed | Breakers are per provider rather than per route, deliberately, so Click's bad afternoon does not stop Payme and a Noor outage does not stop Yandex. The circuit isolates; it does not restore. Then the runbook's action is commercial — take cash, tell the tenant — not technical |
| Poisoned message | no, by design | `PAYLOAD_INVALID` and `CONTRACT_UNSUPPORTED` dead-letter immediately instead of retrying, and block their own aggregate's later events. The runbook inspects it through the ADR 0006 failure API and resolves it with a reason and, where a provider may have acted, reconciliation evidence. Never with SQL: that is the practice ADR 0006 exists to eliminate |
| Stuck outbox | no | The runbook checks whether `worker` is running, then whether Kafka accepts writes, then Kafka's own disk. Throughout, the rows are safe in PostgreSQL — the platform is behind, not losing, and saying so out loud is what stops a 3am operator from reaching for the database |
| Circuit stuck open | probing continues indefinitely | It closes by itself the moment the provider returns, so there is nothing to reset and no restart to perform. What the runbook changes is the tenant's exposure while it stays open |
| Filling disk | no | Nothing frees space automatically. Fixed order: expire Kafka segments, prune backups past retention, drop leftover rehearsal databases, then extend the volume. The order matters because only the last step needs the facility |
| Secrets manager sealed | no | Unseal with the ADR 0034 escrow material. Until then, provider calls fail as caches expire while HTTP still looks healthy |
| Migration scope left holding | no, and deliberately | A `PAUSED` or `BLOCKED_RECONCILIATION` scope fences writes closed — they are refused, not queued, and `MigrationOwnershipService` fails closed on everything it cannot resolve. The runbook resolves the reconciliation or transitions the scope. It never bypasses the gate, because the gate is the only proof that exactly one writer exists |
| Certificate expiry | renewal is automatic, its failure is silent | The morning alert exists to catch the automation, not the certificate |
| Disk failure, corruption, or loss of the building | no | ADR 0034's failure table has the honest times. What this record owns is what the restore has to prove |

Two health endpoints with different jobs, because on a single box readiness has
nowhere to route away to:

- `/actuator/health/liveness` is what the watchdog acts on. It checks the
  process and **not** its dependencies, for the reason the first draft already
  gave: a slow database that restarts the only application container turns a
  degradation into an outage.
- `/actuator/health/readiness` still exists so the reverse proxy can answer 503
  with `Retry-After` during a restart instead of a connection reset.
- `/actuator/health` with details is a diagnostic surface reachable only over
  WireGuard. `show-details: when_authorized` already keeps it out of the
  unauthenticated response that ADR 0034's external probe touches. It drives the
  dashboard and never a restart.

## Security for a machine one person reaches

### How the box is reached

Key-only SSH, `PermitRootLogin no` and `PasswordAuthentication no`, and the SSH
port is **not published to the internet at all** — it listens on the WireGuard
interface. The only inbound ports on the public address are 443 and WireGuard's
UDP port.

### Revocation when the laptop is lost

The problem peculiar to a solo operator is that there is no second
administrator to revoke the first, so revocation is arranged in advance:

- A **second WireGuard peer and a second SSH key** live on a separate device
  kept apart from the laptop. Their purpose is not convenience; it is that
  losing the laptop must be a revocation rather than a lockout.
- Revocation is one runbook run from that second device: remove the peer from
  the WireGuard configuration, remove the key from `authorized_keys`, rotate the
  OpenBao credentials and re-shard the unseal material, rotate the Keycloak
  administrative credential, and rotate the backup passphrase. Old backups stay
  readable under the previous passphrase in ADR 0034's sealed copy; new ones use
  the new one. No re-encryption of history is needed, and that is precisely what
  the sealed copy buys.
- The laptop carries full-disk encryption and **no long-lived provider
  credential**. Everything resolves from OpenBao at call time under ADR 0028, so
  the laptop holds keys to the machine and not keys to the money.
- If both devices are lost, ADR 0034's escrow and the facility's out-of-band
  console are the remaining path. Rotating the escrow material is part of this
  runbook, not a separate chore, because a revocation that leaves the old unseal
  shares valid has revoked nothing.

### What is exposed and what is not

Exposed on the public address: 443, terminating at a reverse proxy on the box,
serving the ADR 0016 storefront read paths, `/api/v1/**`, the unauthenticated
health endpoint the external probe touches, and the two provider callback roots.
Nothing else.

Not exposed, on the internal network with no host port mapping at all:
PostgreSQL, Kafka, OpenBao, MinIO, Prometheus, Alertmanager, the dashboard, the
Keycloak administrative console, and every other `/actuator/**` path. **The
development `compose.yaml` publishes nearly all of these to the host**, which is
right for a laptop and would be a catastrophe on the production VM. The
production overlay therefore removes the `ports:` mappings rather than
firewalling around them, so the default is closed and a mistake presents as a
missing port instead of an open one.

### The two public payment callbacks

They cannot sit behind WireGuard or a bearer token, and ADR 0013 explains why:
Payme authenticates with a per-cashbox Basic credential verified inside the
controller so that it can answer HTTP 200 carrying `-32504`, and Click
authenticates by an MD5 over a secret-prefixed concatenation with no header at
all. Both read any non-200 as a transport failure. So the edge protects them
without ever being allowed to reject them:

- **Payme's fifteen published source addresses (`185.234.113.1–15`) are
  allowlisted at the proxy as defence in depth, never as the only check.** Click
  publishes no equivalent list, so it gets no allowlist and the signature is the
  whole of its authentication — which is why that signature is verified before
  any database is touched.
- **Request size is capped at the proxy** as well as in the controller, which
  already refuses a Payme body above 64 KiB.
- **Per-binding rate limits at the edge**, under ADR 0033's edge-first rule.
- **No WAF rule may rewrite or reject these bodies.** A callback rejected by a
  content filter becomes a retried payment and then a manual investigation at
  the provider, which is a worse outcome than whatever the filter was protecting
  against.
- The binding segment in the path is **not a secret** and must not be treated as
  one. It is guessable by design; the credential is the authentication.
- They carry the only route-specific availability alert in this record, because
  a 5xx here costs money in a way a 5xx on the storefront does not.

### Secrets reaching the process

ADR 0028 owns this and the mechanism exists: `horecaos.secrets.provider` selects
OpenBao, `SecretsProfileGuard` refuses to start a non-local profile on the
file-based resolver, and `SecretValue` redacts itself so a credential cannot
reach a log line. What this record adds is the delivery detail. Each service
authenticates with its own AppRole; the role identifier is ordinary
configuration and the secret identifier arrives as a file mounted from a `0600`
path on the host, outside the repository — never a build argument, never an
image layer, never a committed `.env`. Unsealing stays manual with the ADR 0034
escrow material, and the trading-hours seal alert exists because that manual
step is exactly why a reboot is not unattended.

Before the pilot serves real customers: an external vulnerability scan of the
exposed surface. Before the first tenant that is not the pilot: a full
penetration test. Naming both with triggers is the honest alternative to
requiring a penetration test "before launch" and then launching.

## Recovery: what the numbers are, and what proves them

**ADR 0034's failure table is the authority for recovery times**, including the
two that matter most: data loss is up to 24 hours today and at most 5 minutes
once continuous WAL archiving ships, and the rebuild after losing the disk or
the machine is measured in hours to days rather than minutes. Those numbers are
not restated here, and this record does not soften them. Two of that table's
rows deserve repeating only because they are the ones an operating model can
actually change:

- **A bad deploy is up to 8 hours if it lands late and nothing alerts.** The
  trading-hours tier above is the mitigation: a deploy inside the 23:30 window
  is followed by ten minutes of watching the one dashboard, because at that hour
  no alert tier is loud.
- **The operator being asleep costs up to 12 hours** for anything not automated,
  which is the entire argument for the self-healing column above being longer
  than the alert list.

The gate ADR 0034 sets — **no live tenant order until the off-site destination
is real and one restore from it has succeeded** — is a precondition of this
record's exit criteria and not a parallel commitment.

**What a rehearsal must prove.** ADR 0034 schedules it: weekly from the off-site
copy into the staging VM, timed, with the measured time written into the
recovery runbook and re-measured monthly. This record specifies the assertion,
because "`pg_restore` exited zero" is not proof:

- The row-count triple the script already compares — tenants, audit events,
  successful Flyway migrations — is necessary and is not sufficient. Counts match
  while money is wrong; ADR 0024 rejects count-only reconciliation for the same
  reason.
- Add a money reconciliation: **order totals summed per currency in integer
  minor units, compared exactly.** For UZS a minor unit is a whole som, so the
  comparison is integer equality with no division and no floating point
  anywhere near it.
- Record the wall clock. **A rehearsal whose time exceeds the figure in the
  recovery runbook reopens that figure** rather than producing an explanation,
  and the runbook is corrected in the same commit.
- A passing rehearsal writes an ADR 0027 audit fact, so "when did we last prove
  we could restore" is a query rather than a memory.
- A rehearsal that silently stopped running pings nothing, which is why it shares
  the dead-man's switch with the backup itself.

## The cutover period as an operating mode

For the length of ADR 0024's programme, two systems serve one business and
`MigrationOwnershipPort` decides which may write, per capability and per scope,
failing closed on everything it cannot resolve. Five specific consequences for
operations:

1. **"Is it down" has two subjects.** The customer-visible answer is whichever
   system currently owns the journey, so the external check probes both, and the
   first line of every runbook during this period is *who owns this capability
   right now*, answered from `migration.scopes` rather than from memory.
2. **A fenced write is not an incident; a burst of them is.** One
   `TargetWritesFencedException` is the gate working. Ten in five minutes for one
   capability means routing and ownership disagree, and customers are being
   refused.
3. **The replicator's lag is a data-loss signal, not a performance one.** During
   `LEGACY_WITH_TARGET_SHADOW` and `CATCHING_UP`, a stalled replicator means
   facts exist in one system and not the other — the state every reconciliation
   gate exists to prevent. It inherits the order-flow threshold and joins that
   night alert.
4. **Disk and backup pressure peak here.** Backfill runs, quarantine evidence,
   and an audit fact per import all land during cutover, which is when the 85%
   warning earns its place, and when the sealed-media copy taken immediately
   before each irreversible step stops being a precaution and becomes the
   procedure.
5. **A backfill gets a maintenance window that suppresses the digest and never
   the night tier.** An announced backfill legitimately moves several dashboard
   signals; suppressing the morning digest prevents the noise, and suppressing a
   page would remove the only protection during the phase most likely to need a
   restore.

This record does not undertake to monitor the legacy system, which has its own
operational state and owner. It undertakes to observe the two facts that decide
whether an order falls between the systems: what the gate answers, and how far
behind the replicator is.

Reporting, export, and analytics migration are ADR 0024 and ADR 0043 business.
The only operating requirement this record adds is that reporting queries run
against the primary — there is no replica on one machine — under the smaller
budget ADR 0043 already anticipates, and that an expensive tenant report is
asynchronous and quota-bounded so a report cannot become an outage.

## Consequences

### Positive

- Three alerts wake the operator, each with a stated threshold and a reason, so
  the pager stays trustworthy and therefore keeps being read.
- The self-healing set is written down, which turns "will it recover" from a
  hope into a table with two columns and a known second column.
- The rehearsal proves a restore with money rather than with an exit code, and
  a rehearsal that misses its time changes the documented number instead of
  being explained away.
- Losing the laptop is a documented revocation rather than a lockout, which is
  the failure a solo operator is otherwise least prepared for.

### Negative

- **Deliberately few alerts means some real problems are found by a customer
  telephoning before they are found by a graph.** That is the trade, stated
  plainly rather than discovered.
- **A sealed OpenBao after a 3am reboot is discovered in the morning.** The cost
  is bounded only by trading hours having ended, and a tenant that trades
  overnight invalidates that reasoning immediately.
- Non-monetary dead letters wait until morning, so a non-monetary aggregate can
  stay blocked for eight hours behind one poisoned message.
- The payment callbacks are the one surface where the platform's own
  authentication, its API conventions, and its WAF all step aside. That
  concentration of exceptions is understood and is why they get their own alert.
- Everything in ADR 0034's negative column applies underneath this one: one
  machine, hours-to-days recovery, and no second location.

### Accepted trade-offs

- **Automatic recovery was funded ahead of alert coverage.** More engineering
  goes into leases, backoff, watchdogs, and restart correctness than into
  detection, so a failure outside the self-healing set is found later than a
  well-instrumented platform would find it. At 3am with nobody to escalate to,
  that is the correct direction.
- **The night budget is a hard cap, not a target.** A fourth night alert is
  available only by removing one and saying which, which will feel wrong on the
  first night something slips through.
- **The three-container split keeps ADR 0028's per-role secret isolation and
  gives up the independent scaling the five-role model promised.** Nothing on one
  host was going to scale independently anyway.
- **Observability retention is 90 days per ADR 0029 on a disk shared with
  PostgreSQL**, which means the trace store is a claimant on the resource the
  85% alert watches, and the sampling rates above are the mechanism that keeps
  it small rather than a performance nicety.

## Implementation checklist

- [x] Write the production Compose overlay: three services, no host port mappings except the proxy, log rotation with a hard cap, `restart: unless-stopped`. — `compose.production.yaml`, with the `worker` split still outstanding below.
- [x] Split the role switches into `app` and `worker` configuration, and assert in a test that exactly one process runs each scheduler. — The application half. `horecaos.runtime.role` (`app` | `worker` | `both`, default `both`) is a plain Spring property rather than an ADR 0030 policy key: ADR 0030's own alternatives table already says deployment-time configuration is `@ConfigurationProperties`/profile territory, never a tenant-scoped `PolicyResolver` key, and a process role is exactly that case. `RuntimeRole` and `ConditionalOnWorkerRole` (`uz.horecaos.platform.configuration`) gate `SchedulingConfiguration`'s single `@EnableScheduling` directly, so role `app` removes the `ScheduledAnnotationBeanPostProcessor` from the context and every `@Scheduled` method on every module — thirty-two classes, forty-one methods today, not the twenty-one this line once counted — stops uniformly, regardless of that job's own `@ConditionalOnProperty`; role `worker`/`both`/unset changes nothing (`RuntimeRoleSchedulingTests`). Of the four switches this record names, three (`horecaos.messaging.outbox.enabled`, `horecaos.ordering.workers.enabled`, `horecaos.api.idempotency.purge.enabled`) already guarded a `@Scheduled` class and needed no change — the blanket gate now covers them for free. The fourth, `horecaos.messaging.inbox.listener.enabled`, guards `TenancyEventListener` and `FulfillmentCommandListener`, both `@KafkaListener`s outside `@EnableScheduling`'s reach; both now also carry `@ConditionalOnWorkerRole` (`WorkerRoleAnnotationCoverageTests`), closing exactly the gap this line named. **Every scheduled job in this build was audited for multi-instance safety** (the inventory is wave 58's own report, not repeated here): every mutating one already uses a database lease with a token, `FOR UPDATE ... SKIP LOCKED`, `INSERT ... ON CONFLICT DO NOTHING`, or a conditional version-checked `UPDATE`; `InboxRetryWorker.redriveOnce` is additionally guarded, in-process, against overlapping runs of itself, proven by `InboxRetryWorkerReentrancyTests` rather than newly added. **Not done**: the compose wiring — see Runtime shape for the two environment variables and the routing decision this leaves for wave 55 — and a safe way to run `PosOrderExportTrigger.dispatchPending` and `RealtimeStreamMaintenance` on a strict `app: app` / `worker: worker` split; both hold in-process state tied to the HTTP/SSE-serving process and are not yet redesigned onto a durable handoff, so that specific split is not yet safe to deploy even though the mechanism enabling it is built.
- [x] Add the missing gauges: outbox and inbox oldest age, dead letters by category, breaker state, orders by state and age, free disk, fence rejections, replicator lag. — All except two. **Outbox dead letters cannot be grouped by category**: the outbox table records only `last_error` free text, so those rows are published as `failure_category="unclassified"` and grouped by topic domain, which is what the alert actually needs. **Replicator lag has nothing to measure** — ADR 0024's replicator does not exist yet.
- [ ] Stand up Prometheus, Alertmanager, and the single dashboard on the box; deliver alerts off it. — Not done, and the alerts do not wait for it: `horecaos-probe.sh` evaluates them from a direct scrape, so the thresholds live in exactly one place. The consequence is that everything on the "deliberately not an alert" list is measured and currently unwatched, because the dashboard it was supposed to live on does not exist.
- [x] Implement the alert rules in this record at the stated thresholds and tiers, and nothing else. — `infra/observability/horecaos-probe.sh`, with one exception. **"Platform unreachable" is not evaluated on the box and cannot be**, because a script running on the machine cannot observe that the machine is unreachable; it is the off-box uptime check, specified in `infra/observability/README.md`. Every remaining threshold is overridable by an environment variable so that each can be made to fire on demand, which is how the exit criterion is met without staging an outage. The stuck-circuit alert covers the payment and POS breakers; the courier breakers publish the same gauge and do not page.
- [x] Implement the watchdog that recreates a failing container before it pages, per ADR 0034. — `autoheal` restarts on the health check; the probe pages only when the restart did not fix it, at three restarts in ten minutes.
- [x] Implement `/actuator/health/liveness` and `/actuator/health/readiness`, with no dependency check on liveness. — And a third group, `customer`, which is the only one that consults PostgreSQL and is the one the external uptime check must use. Asserted in `HealthProbeAndMetricTests`, including with the database deliberately stopped.
- [ ] Configure WireGuard, key-only SSH on the tunnel interface, and the second device's peer and key. — Host configuration, not in this repository.
- [ ] Write and rehearse the laptop-loss revocation runbook end to end, including re-sharding the escrow material. — Written (`docs/runbooks/laptop-lost.md`); never rehearsed, so still a draft.
- [ ] Configure the reverse proxy: TLS, the Payme source allowlist, body size caps, per-binding rate limits, and no content filtering on the callback roots. — TLS and the actuator surface were already in the Caddyfile. Wave 55 added the rest to both `infra/production/caddy/Caddyfile` and `deploy/infra/caddy/Caddyfile`: body caps (a global 2 MiB ceiling, 64 KiB on the two payment callbacks matching `PaymeMerchantApiController.MAX_REQUEST_BYTES` exactly, 11 MiB on the media origin matching `MediaAssetService.MAX_IMAGE_BYTES`), per-binding rate limits on `/providers/payme/*` and `/providers/click/*/{prepare,complete}` keyed on the path itself (which already carries the binding/bindingRef segment), and per-IP rate limits on the identity/verification/telegram/staff-sign-in/dine-in endpoints and the storefront's anonymous browse surface. No content filtering was added, so that sub-item holds by construction. Rate limiting needed a purpose-built image (`caddy:2.10-alpine` — the stock image used by both compose files — carries no `rate_limit` directive at all, confirmed by `caddy list-modules --packages`); `infra/production/caddy/Dockerfile` recompiles Caddy with `github.com/mholt/caddy-ratelimit`, built and validated locally (`caddy validate`, plus a live test: correct 403/413/429 behaviour against a stub upstream) but not yet proven through CI's publish job or a real deploy. **The Payme allowlist is not yet functional despite being fully wired**: `HORECAOS_PAYME_ALLOWED_IPS` is deliberately unset by this change (Payme's current published addresses were not available to verify and must not be invented) and both compose files default it to `0.0.0.0/32` — a non-routable address — so the mechanism fails closed. Deployed as shipped, `/providers/payme/*` rejects every caller, Payme included, until the owner supplies Payme's real, currently-published source addresses. That is a deliberate trade (open would mean shipping an allowlist that silently does nothing) but it is the one piece of this line an operator cannot yet use.
- [ ] Deliver OpenBao AppRole secret identifiers as `0600` file mounts and document the unseal procedure. — The unseal procedure is `docs/runbooks/openbao-sealed.md`; the mounts are ADR 0028's.
- [ ] Add the money reconciliation in integer minor units to the rehearsal, and record the wall clock as an ADR 0027 audit fact.
- [x] Write the platform runbooks: outbox not draining, dead-letter decision, circuit stuck open, scope fencing writes, laptop lost. — Plus PostgreSQL down, payment callback failing, OpenBao sealed, crash loop, and disk filling, indexed by `docs/runbooks/alerts.md`. **Every one is a draft until executed.**
- [ ] Add the cutover alert, the replicator-lag signal, and the ownership panel, and define the backfill suppression window. — The cutover alert is built and counts from `horecaos.migration.writes.fenced`. The replicator-lag signal has nothing to measure. The panel needs the dashboard. The suppression window is not implemented: the probe has no maintenance mode, so an announced backfill will currently produce digest noise.
- [ ] Run the external vulnerability scan before the pilot serves customers.

## What is not built yet

Four gaps, listed here rather than left to be discovered from the checklist.

**The off-box half.** ADR 0034 splits watching this platform between the box and
something that is not the box, and only the on-box half is in this repository. A
dead-man's switch and an external HTTP uptime check both have to be created as
accounts on external services — that is the whole point of them, and it is why
nothing here can substitute. `infra/observability/README.md` specifies exactly
what each has to be configured to do, including the two-consecutive-failure rule
and the path the uptime check must use. **Until they exist, night alert one does
not exist**, and the platform is watched by something that stops working at the
moment it is needed.

**The dashboard.** No Prometheus, no Alertmanager, no "is it working" page.
Everything this record deliberately excluded from paging — latency, CPU, cache
hit rate, a breaker opening rather than staying open — was excluded on the
grounds that it belongs on a dashboard, and that dashboard does not exist. The
metrics are published and scrapeable, so this is a build rather than a design.

**Traces.** No OpenTelemetry exporter is wired, so the sampling rates above —
5% of successes, 100% of errors, 100% of `/providers/**` — have nothing to
sample.

**Outbox dead letters by `FailureCategory`.** Stated in this record and not
implementable as written. The inbox records a classified `last_error_code` and
is grouped by it; the outbox records only free text. Adding the column is a
migration, and the alert that matters — a *monetary* dead letter — is grouped by
topic domain instead and does not need the category. This is a gap in the
diagnosis rather than in the paging.

## Exit criteria

The platform runs on the ADR 0034 machine as three services with no unnecessary
port exposed; three alerts wake the operator and each has been verified to fire;
every alert has a runbook that has been executed at least once; a restore from
the off-site copy has succeeded with a money reconciliation that matched exactly
and a wall clock inside the figure recorded in the recovery runbook; the
operator can revoke a lost laptop from a second device without losing access;
both payment callbacks remain reachable and protected under load; and during
cutover the owner of every capability is answerable from `migration.scopes`
rather than from memory.

## References

- [ADR 0006: Message retry, dead-letter, and replay operations](../partial/0006-message-retry-dead-letter-and-replay-operations.md)
- [ADR 0013: Payments, refunds, and service recovery compensation](../partial/0013-payment-refund-and-service-recovery-compensation.md)
- [ADR 0024: Legacy data migration, cutover, and retirement](../partial/0024-legacy-data-migration-cutover-and-retirement.md)
- [ADR 0027: Audit evidence and the approval model](../partial/0027-audit-evidence-and-approval-model.md)
- [ADR 0028: Secrets management and credential lifecycle](../partial/0028-secrets-management-and-credential-lifecycle.md)
- [ADR 0029: PII protection, envelope encryption, and key rotation](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md)
- [ADR 0033: Caching, rate limiting, and shared runtime state](../partial/0033-caching-rate-limiting-and-shared-runtime-state.md)
- [ADR 0034: Hosting environments, topology, and data residency](../partial/0034-hosting-environments-topology-and-data-residency.md)
