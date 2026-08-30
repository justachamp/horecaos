# ADR 0034: Hosting environments, topology, and data residency

- Decision status: Accepted
- Implementation status: Partial — the topology exists as code. `compose.production.yaml`
  defines the whole colocated stack behind Caddy with per-container log caps, and
  `infra/production` holds `bootstrap.sh`, `deploy.sh`, `run-backup.sh`, `heartbeat.sh`,
  the Caddyfile, Postgres init, grant audit and an `ops/` image whose `backup-job.sh`
  resolves every backup credential — including the passphrase — from OpenBao through
  `bao-get.sh`. `infra/backup` has `backup.sh`, `restore.sh` and
  `rehearse-restore.sh`, encrypting and shipping to `QOIDA_BACKUP_OFFSITE_*` and refusing
  when the off-site endpoint equals the primary. `infra/observability/qoida-probe.sh`
  evaluates the alert table on a one-minute cron and pings a dead-man's switch, and
  `docs/runbooks/` holds thirteen runbooks plus the one-to-one alert map and an index.
  Not built: every gate in the checklist below is still open. `rehearse-restore.sh`
  defaults its off-site target to a second local MinIO and
  `QOIDA_BACKUP_OFFSITE_ENDPOINT` is blank in `production.env.example`, so nothing has
  been proved against a real remote bucket with versioning and object lock; there is no
  WAL archiving anywhere in `infra/`, so worst-case loss remains 24 hours; the media
  bucket is not mirrored; the passphrase has a defined OpenBao path and a reader but no
  sealed offline copy and no credential escrow; there is no RAID mirror; the off-box half
  — external uptime check and dead-man's-switch receiver — is configured outside this
  repository and is not verified; eleven of the thirteen runbooks record "Last executed:
  never" and `deploy.md` and `restore.md` carry no such line at all; and there is no
  staging VM, no blue/green swap script, no processor register and no sanitized-staging
  procedure.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20; platform, orchestrator, and off-site backups settled 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture), operations, legal
- Depends on: ADR 0023, ADR 0029
- Supersedes / Superseded by: —
- Open inputs: none
- Closed inputs: Data residency does not constrain Qoida (business, 2026-08-20); hosting is a colocated server in Uzbekistan (business, 2026-08-23); the orchestrator is Docker Compose on VMs (engineering, 2026-08-23); the platform is operated by one person (business, 2026-08-23); off-site backups ship before the pilot rather than after the migration (operations, 2026-08-23, reversing the 2026-08-21 deferral); Clopos is the pilot tenant's POS (product, 2026-08-23)

## Context

ADR 0023 defers this decision explicitly: "Choose the concrete
orchestrator/cloud/managed services in an environment ADR after capacity,
residency, team skills, cost, and provider-region discovery." That ADR was never
written, so five later decisions have no ground to stand on. ADR 0028 cannot
choose between a managed secrets service and a self-hosted one. ADR 0029 cannot
say where encrypted personal data physically lives. ADR 0033 cannot cost a
shared cache. ADR 0010 cannot select a CDN. ADR 0024 cannot plan a cutover
window without knowing what it is cutting over to.

The first draft of this ADR treated data residency as the binding constraint, on
the basis that Uzbekistan's Law on Personal Data (ZRU-547, 2 July 2019) added
Article 27-1 requiring personal data of Uzbek citizens to be stored on databases
located in Uzbekistan, and that the March 2026 amendments eased this for most
data while retaining a domestic mandate for sensitive categories. **The business
has determined that no in-country storage obligation binds this platform**, so
residency is not what decides hosting. It turns out not to matter: the hosting
choice made on operational and commercial grounds keeps personal data in
Uzbekistan anyway, which is a different and better position than a promise.

Three facts settle the rest, and all three are now closed.

**The hardware already exists.** Qoida owns a server in a colocation facility in
Tashkent. In cash terms this topology is nearly free, and international contracting
and payment from Uzbekistan is a real obstacle rather than a preference.

**One person operates this platform.** Not a small team — one. That inverts most
of the received advice in this document set. A rota, an escalation path, and a
second pair of eyes at 3am do not exist and cannot be conjured by writing that
they should. Every operational decision below is made for an operator who is
alone, sometimes asleep, and occasionally on a plane.

**The pilot is one tenant on Clopos.** One POS vendor, one brand, service hours
that end before midnight. That is the load this topology has to carry, and it is
small. Sizing for a platform that does not exist yet would buy complexity now to
avoid a decision later, which is the trade ADR 0001 already refused.

## Decision

### Hosting: a colocated server in Uzbekistan

Qoida runs on **its own server in a colocation facility in Tashkent**. This is
the decision, not a phase-one placeholder with an asterisk. It buys the lowest
possible latency to customers, couriers, and the local POS and payment
providers; straightforward billing; and no dependency on international
contracting. It pays for that with everything a managed provider would otherwise
absorb: PostgreSQL, Kafka, Keycloak, MinIO, and OpenBao are self-operated on
hardware whose failure is Qoida's problem, with no managed failover, no
automatic backup verification, and nobody to escalate to.

AWS remains the stated eventual destination, and the portability rules below
exist to keep that move cheap. It is not scheduled, it is not assumed in any
sizing, and no decision in this ADR is contingent on it. The move gets its own
ADR when it is planned, covering data transfer, cutover, and rollback; it is a
migration, not a redeploy.

**Personal data therefore stays in Uzbekistan by construction.** PostgreSQL,
object storage, Keycloak, and OpenBao are all on that machine. ADR 0029's rule
that `PERSONAL_SENSITIVE` and `FINANCIAL` data stay in-country is satisfied by
where the disks are, not by a policy someone has to remember. What leaves the
country is a short list, enumerated and decided below, because each item on it
crosses a border unless it is chosen deliberately.

### Orchestrator: Docker Compose on VMs

The spike is closed. The orchestrator is **Docker Compose, on VMs on the
colocated host**. `compose.yaml` is already how this project runs on a developer
machine, which means the dev/prod gap is a set of environment variables and a
different image tag rather than a second deployment technology nobody exercises
daily. For five role configurations of one artifact, operated by one person, a
self-managed Kubernetes control plane would be a second production system to
keep alive on top of the one that earns money.

The ceiling is real, and pretending otherwise is how a small system becomes an
outage:

- **No rolling deploy.** `docker compose up -d` recreates a container; the API is
  down while it restarts. A blue/green swap behind the reverse proxy is
  scriptable and is not scripted today. The deploy protocol is therefore: run
  the ADR 0023 migration job, then recreate, inside a maintenance window after
  23:30 when the pilot's service hours have ended. Target under 90 seconds, and
  it is measured on every deploy rather than assumed. Migrations stay
  backward-compatible regardless, so the window covers the restart, not the
  schema.
- **No self-healing beyond restart policies.** Every container carries
  `restart: unless-stopped`. That covers a crash. It does not cover a process
  that is alive and wrong, and Compose will restart a crash-looping container
  forever without telling anyone. The health watchdog below exists because of
  this, not in spite of it.
- **No node failover and no rescheduling.** There is one node. This is the
  single-point-of-failure list, not a footnote to it.
- **Manual scaling, vertically.** More load means a bigger box or `--scale` on
  the same box, decided by a person reading a dashboard. There is no bin-packing
  and no autoscaler.

#### The revisit trigger

Compose is replaced when any one of these is true. Each is a number, a symptom,
or an event, and none of them is "when we grow".

| Trigger | Why this one | First response |
|---|---|---|
| A second production machine exists, for any reason | Compose across two hosts is a thing people do and should not. The moment placement is a question, something has to answer it | Two machines with a load balancer and one Compose stack each, or Nomad. Managed Kubernetes only on AWS |
| Peak-hour CPU above 60% on the host, or the PostgreSQL working set exceeding RAM, on any two days in one week | Vertical scaling has a last step, and this is the warning before it | Buy the bigger box once. If the trigger fires again after that, orchestration is no longer optional |
| The maintenance window disappears — a sales channel takes orders during the 23:30 slot, or a tenant contract states an availability number | A restart window is a business decision that stops being available the moment someone is buying at that hour | Script the blue/green swap first; it is cheaper than a scheduler and buys back the window |
| More than three live tenants | Three is the point at which one tenant's incident becomes three tenants' incident, and a manual deploy stops being a private matter | Reassess as a whole, including whether a second operator exists |
| A second operator joins | Kubernetes' operational tax needs somebody to pay it. One person cannot | Reassess. The tax may now be affordable and the benefits real |

### Operated by one person

A solo operator is a design constraint, not a staffing note. Three rules follow,
and they override the general advice in ADR 0023 wherever they conflict.

**Very few alerts, every one actionable.** An operator with a noisy pager stops
reading the pager, and then the one real alert arrives in a stream he has
learned to ignore. The night-alert budget is **three**. Adding a fourth requires
removing one, deliberately, in a commit that says which.

| Alert | Fires when | Wakes him? |
|---|---|---|
| Platform unreachable | The external uptime check fails twice in a row, roughly two minutes | Yes |
| PostgreSQL down while the host is up | The database is unreachable but the machine answers | Yes |
| Order flow stalled | Outbox age or Kafka consumer lag above threshold for 15 minutes — orders are not reaching the POS, which is money | Yes |
| Backup did not run | No dead-man's-switch ping in 26 hours | No, morning |
| Data volume above 85% | PostgreSQL stops writing at 100%, and the slope is usually days | No, morning |
| TLS certificate expiring within 7 days | Renewal is automated; this catches the automation failing | No, morning |

Everything else is a dashboard entry. An alert nobody can act on at 3am is not
an alert, and a warning with no action attached is a metric.

**Automated recovery beats good paging.** At 3am there is no second person to
escalate to, so the machine has to try first. Restart policies on every
container. A health watchdog that recreates the API container after three
consecutive failed health checks, with backoff, and pages only if the restart
did not fix it. Log rotation with a hard size cap, because a disk filled by logs
is the most likely self-inflicted night outage on this topology. Automatic
certificate renewal. `backup.sh` fails loudly rather than silently truncating.
The rule this comes down to: **a page whose resolution is "restart it" is a bug
in the automation**, and it gets fixed there rather than in the runbook.

**Runbooks are written for the person who built it, having forgotten.** Tired,
alone, and not in the mood to reason from first principles at 3am. Every runbook
opens with how to tell whether it is the right runbook, carries exact
copy-pasteable commands with absolute paths, names where the credentials live,
and closes with how to tell it worked. No step says "obviously" and no step
assumes the reader remembers why. The minimum set: restore from off-site,
replace a failed disk, rebuild the platform on new hardware, rotate a leaked
credential, the pilot's POS is not receiving orders, and take the platform down
on purpose. Each one includes what to do when OpenBao is the thing that is down,
because the bootstrap problem is exactly the one a tired person has not thought
about.

### Watching a machine from somewhere other than that machine

Self-hosting monitoring on the colocated box means that when the box dies, the
service and the ability to see why die together, at the moment both are needed.
The resolution is a split, not a second data centre.

- **On the box:** metrics, logs, and traces. They answer everything that is not
  "the box is gone" — a slow query, a stuck consumer, a provider timing out —
  and that is the overwhelming majority of what will actually go wrong.
- **Off the box:** an external uptime service polls a public health endpoint, and
  `backup.sh` pings a dead-man's-switch URL on success. Both run on
  infrastructure Qoida does not own, and both alert through the vendor's own
  delivery — Telegram and SMS — so **the alert path never traverses the failed
  machine**. The uptime probe touches an unauthenticated health endpoint that
  returns a status and nothing else.

What this does not buy is honestly stated: an external ping tells the operator
that the platform is down, not why. When the box is unreachable, diagnosis
begins with the facility's remote hands and an out-of-band console, and the
recovery times below already assume that is where it starts. **Qoida accepts
having no second location.** A warm standby in a second building is the correct
answer at a size Qoida has not reached, and the trigger for reaching it is the
same trigger that retires Compose.

### Off-site backups, and where they go

A backup in the same building as the thing it protects is not a backup. Deferring
the real destination was defensible while nothing ran; it stops being defensible
the moment a real restaurant's orders are on the box. **That deferral, recorded
2026-08-21, is reversed here.**

The gate is explicit: **no live tenant order is accepted on this platform until
the off-site destination is real and one restore from it has succeeded.** Not
after the migration, and not once things settle down — the migration is the phase
most likely to need a restore, because a wrong transformation, a corrupting
backfill, and a reversed cutover are all restore events, and they cluster in
exactly the window where copies must not share a failure domain with the primary.

Three copies, each with a different job:

| Copy | Where | What it is for |
|---|---|---|
| Local | The box, beside the database | Fast recovery from a dropped table or a bad migration. Convenience, not a backup |
| Off-site bucket | An S3-compatible bucket outside the building, versioning enabled and object-lock or a retention rule that forbids deletion inside the retention window | The building burning, the disk failing, the credential being abused |
| Sealed media | Encrypted removable media the operator holds away from the server room, rotated weekly and taken immediately before every irreversible migration step | The bucket credential being compromised and every online copy deleted |

The bucket is chosen in this order: **a second Uzbek provider in a different
building** that supports versioning and object lock, which keeps residency
trivially true; failing that, a foreign S3-compatible provider in the nearest
region, which is acceptable **because the object is AES-256 encrypted before it
leaves the machine** and the key does not travel with it. Ciphertext crossing a
border is not personal data crossing a border, and that is the whole reason
`backup.sh` encrypts before upload rather than trusting server-side encryption.

The credential written into the backup job can write and read that one bucket and
nothing else, and cannot delete. A backup an attacker can erase is not a backup.

**The passphrase is kept away from the backups, and away from the machine.** It
lives in OpenBao under `data_encryption` for the automation, and — because
OpenBao is on the box and dies with it — in a **sealed offline copy in a
different physical location from both the server and the media**. After a fire,
that sealed copy is the only path back to readable data. It never lives in the
account that holds the bucket: one compromise yielding both the ciphertext and
the key means the encryption did nothing.

Object storage is backed up too, not only PostgreSQL. Menu media and evidence
objects are referenced by orders and cannot be reconstructed from the database, so
the media bucket mirrors to the same off-site destination on the same schedule
and is covered by the same rehearsal.

**The restore is rehearsed, and the rehearsal is what makes it a backup.**
`infra/backup/rehearse-restore.sh` runs weekly, restoring **from the off-site
copy** — verifying the local one would prove nothing about the copy that survives
losing the primary — into a scratch database in the staging VM, and comparing row
counts against the source. It is timed, the measured restore time is written into
the recovery runbook, and it is re-measured monthly, because a recovery time
nobody has measured is a guess and this one grows with the data. The rehearsal
pings the dead-man's switch; a rehearsal that silently stopped running is the
failure mode that hides longest.

### Single points of failure, with honest recovery times

This is the most valuable table in this document. Every entry is a thing that can
end the service, and the times are what the operator should actually expect, not
what would be achievable with staff and spares that do not exist.

Assumptions behind the numbers, all of which should be challenged as they change:
the alert reaches the operator within two minutes; he is in Tashkent, roughly
forty minutes from the facility; the facility offers remote hands during business
hours only (to be confirmed in writing — checklist); there is no cold spare
machine and no spare disk on site today; and the current backup schedule is a
nightly dump, so today's worst-case data loss is a full day of orders.

**Two RPO numbers appear below: today's, and the one after continuous WAL
archiving ships.** Continuous archiving to the off-site bucket every five minutes
turns "up to 24 hours of orders" into "up to five minutes", is the cheapest
single improvement available anywhere in this ADR, and is gated before pilot
cutover for that reason.

| Failure | What actually happens | Service returns in | Data lost |
|---|---|---|---|
| Application container crashes | Restart policy recreates it; in-flight HTTP requests fail | Seconds, unattended | None |
| Bad deploy | Wrong behaviour, possibly not obvious. Roll back to the previous image tag by hand | 10–20 minutes if awake; **up to 8 hours if it lands late and nothing alerts** | None, if the migration was backward-compatible as required |
| PostgreSQL process crash | Restart and crash recovery | 1–5 minutes, unattended | None; committed transactions survive |
| PostgreSQL data corruption | Restore required. Detected late, usually by a query rather than an alert | 1–3 hours from local copy; 3–6 hours from off-site | Everything since the last good backup: **up to 24h today, ≤5 min after WAL archiving** |
| Data disk fails, 2am Saturday | The machine is down. No RAID today, so this is a rebuild | **12–18 hours** with a spare disk on site; **up to 36 hours** if one must be bought on a weekend | **Up to 24h today, ≤5 min after WAL archiving** |
| Motherboard, PSU, or whole machine | Same, plus sourcing hardware | **24–48 hours**; longer if the model must be ordered | As above |
| Facility lost — fire, flood, seizure | Everything in the building is gone, including the local backup copy and OpenBao. Rebuild on new hardware from versioned configuration, restore from the off-site bucket using the sealed passphrase | **8–24 hours** if hardware can be bought locally that day; **2–5 days** realistically | As above, plus anything not yet shipped off-site |
| Power feed lost | The facility's UPS and generator should cover it; the terms are not yet in writing | Facility's SLA governs; assume hours, unknown until confirmed | None on a clean shutdown; crash recovery otherwise |
| Uplink lost | Everything runs and nobody can reach it. Customers cannot order, couriers cannot be dispatched, the POS receives nothing | ISP repair time, typically 1–6 hours. **Nothing Qoida can do** | None stored, but provider webhooks that do not retry are lost, and unplaced orders are revenue |
| Kafka down | Orders keep working. The outbox buffers in PostgreSQL, consumers lag and catch up. ADR 0004 exists for this | Minutes on restart | None; PostgreSQL is authoritative |
| Keycloak down | No new logins. Existing tokens work until they expire, so an authenticated shift continues | Minutes on restart | None |
| MinIO down | Media unavailable, uploads fail. Orders unaffected | Minutes on restart | None if the mirror is current |
| OpenBao down | Nothing can resolve a secret. Running containers hold what they cached; restarts fail | Minutes on restart; **hours if the unseal path is the problem** | None, if the seal keys are recoverable |
| **The operator is asleep, ill, or on a plane** | Whatever restarts automatically, restarts. Everything else waits | **0 minutes for what is automated; otherwise until he is reachable — realistically up to 12 hours** | Bounded by whatever failed |
| **The operator is permanently unavailable** | Nobody else holds the credentials, the passphrase, or the knowledge | **Weeks, and possibly never**, without credential escrow | Potentially everything |

The last row is the one that is uncomfortable to write and the most important to
plan for. A platform with all keys in one person's head is an existential risk to
the tenant, not a scheduling risk to Qoida. The mitigation is not a second
engineer, which does not exist; it is **credential escrow**: a sealed record of
the OpenBao unseal material, the backup passphrase, the registrar and facility
accounts, held by a named person with legal standing to open it, reviewed
whenever any of them rotates. Second, at least one other person — the tenant's
owner will do — must be able to power-cycle the machine and read the "platform is
down" runbook. Neither is heroic. Both convert "never" into "days".

The uplink row is the other honest one: it is the most likely multi-hour outage
in this table, it has the highest business cost, and Qoida cannot fix it. A
second uplink from a different ISP is the only real answer and is a facility
question, on the checklist rather than in the decision.

### Four environments, and what is actually isolated

Four environments exist, and only two of them share hardware. That distinction is
what makes the claim survivable.

```text
local       developer machines, compose, synthetic data only        off the box
ci          ephemeral, real dependencies via Testcontainers          off the box
staging     production-shaped, sanitized data, provider sandboxes    staging VM
production  real data, real providers, restricted access             production VM
```

Staging and production are **separate VMs on the one host**, not two Compose
projects side by side. That buys a kernel boundary, separate filesystems,
separate networks, separate Docker daemons, and hard CPU, memory, and disk quotas
so that a staging load test cannot starve production. Containers sharing one
daemon would buy none of it.

What is genuinely isolated: Keycloak realms, clients, and signing keys;
PostgreSQL clusters, roles, and credentials; object storage buckets and their
credentials; Kafka clusters; OpenBao instances and policies, with no policy in
staging that names a production path; and the operating system user each stack
runs as.

What merely looks isolated, and is not: the hardware, the hypervisor, the power
feed, the uplink, the physical disks, the backup schedule, and the operator. The
consequences are worth stating plainly, because each has bitten somebody:

- **Staging cannot validate recovery.** It shares the failure domain it would be
  testing. A failover exercise on this host proves the script runs, not that the
  platform survives the building.
- **A hypervisor or host-kernel compromise crosses the boundary.** The VM
  boundary is strong against noisy neighbours and weak against a determined
  attacker who already has the host.
- **Disk pressure is shared unless quotas are enforced**, which is why the quotas
  above are a requirement and not a suggestion.
- **Production personal data is never copied downward.** Staging uses sanitized
  or synthetic datasets that preserve distribution and edge cases, and production
  credentials exist only in production's OpenBao.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| AWS from the start | The right long-term answer, and the stated destination: managed PostgreSQL, KMS, S3, and Kafka would remove most of the burden this ADR accepts. Not chosen because international contracting and payment from Uzbekistan is a practical obstacle, latency from the nearest region is materially worse for POS and courier traffic, and the colocated hardware exists and is paid for | Contracting access changes, or the operational load of self-management outweighs the hardware already owned. The portability rules exist to keep the move cheap |
| A managed local provider instead of own colocation | Would have absorbed backup verification, hardware replacement, and some failover — most of the SPOF table above. Not chosen because the server already exists, which makes this topology nearly free in cash terms | The first hardware failure costs more in downtime than a year of managed hosting, which the table above makes calculable rather than rhetorical |
| Self-managed Kubernetes, k3s or similar | A control plane to operate on top of five stateful dependencies, for five role configurations of one artifact and one operator. Rolling deploys and self-healing are real benefits bought at a price nobody is available to pay | A second machine or a second operator arrives, at which point it competes with Nomad and with two Compose stacks behind a load balancer |
| Nomad | Genuinely lighter than Kubernetes and a real answer for multi-host scheduling. Rejected for now because it is a second deployment technology to learn and exercise for a benefit that starts at machine number two | The second machine trigger fires |
| systemd units instead of Compose | Fewer moving parts and better integration with the host. Rejected because the project's local development already runs Compose, and having production differ from the daily-driver setup is exactly the dev/prod gap this decision is protecting | Compose itself becomes the problem, which it has not |
| A warm standby in a second building | The correct answer to most of the SPOF table. Rejected now on cost and on operator attention: a standby nobody rehearses failing over to is a second machine to patch, not a recovery capability | The Compose revisit triggers fire, or a tenant contract states an availability number |
| RAID mirroring dismissed as unnecessary | It is not dismissed; it is the cheapest single line in the failure table and turns a 12–18 hour rebuild into a disk swap at leisure. It is on the checklist. It does not replace off-site backups, because a mirror faithfully replicates a `DROP TABLE` | Never; both, not either |
| Yandex Cloud | Regionally close, real managed services, workable billing. Not chosen because it adds counterparty and sanctions exposure for a platform handling payments, and it is not the stated destination, so it would mean two migrations instead of one | Local hosting proves inadequate before AWS is reachable |
| European budget VPS | Cheapest and easy to pay, but no managed services, no managed KMS for ADR 0029 key material, and it gives up the latency advantage that justifies local hosting | Never; local hosting dominates it on every axis that matters here |
| Deferring off-site backups until after the migration | Defensible while nothing ran; indefensible with a real tenant's orders on the box, and worst precisely during the migration, when restores are most likely and the only copies would share a failure domain with the primary | Never. This is the reversal recorded above |
| Three environments, dropping staging | Honest, and briefly tempting: staging on the same host cannot prove recovery. Rejected because staging still catches migration failures, provider sandbox mismatches, and configuration errors before they reach a paying tenant, which is most of what it is for | Staging stops being run, at which point deleting it beats pretending |
| Managed Kubernetes today | Local providers rarely offer it, so it would mean self-managing a control plane on top of five stateful services | AWS, later |
| Serverless or scale-to-zero | The platform runs durable schedulers, Kafka consumers, and an outbox relay that must run continuously. ADR 0001 already rejected optimising for startup time | Never for worker and scheduler roles |
| Defer the environment decision further | Five ADRs were blocked on it | Never |

## Consequences

### Positive

- Five blocked decisions can proceed, and ADR 0023's deferral is closed. ADRs
  0028, 0029, and 0033 have a concrete environment to name.
- The dev/prod gap is small by construction. The deployment technology in
  production is the one exercised on a developer machine every day, which is the
  single largest defence a solo operator has against a deploy nobody understands.
- The recovery position is written down with numbers, so it can be argued with,
  costed, and improved. The two cheapest improvements — WAL archiving and a RAID
  mirror — became obvious only once the times were written honestly.
- Personal data stays in Uzbekistan without a policy anyone has to remember, and
  the short list of things that cross a border is enumerated rather than
  discovered.

### Negative

- Five stateful dependencies are self-operated by one person, on hardware whose
  failure is Qoida's problem. That capacity does not go into the capability ADRs.
- Every recovery time in the failure table is hours, not minutes, and several are
  days. A single machine cannot do better, and no amount of documentation
  changes it.
- Compose gives up rolling deploys, self-healing beyond restarts, and automatic
  scaling. Each will be missed at a predictable moment, and the revisit triggers
  exist so that moment is recognised rather than endured.
- Staging shares a host with production, so it can never validate recovery. The
  only honest rehearsal of losing the building is one that actually rebuilds
  elsewhere.
- Restore rehearsals, escrow reviews, and runbook maintenance consume real time
  and produce no features, and the person who skips them is the same person who
  needs them at 3am.

### Accepted trade-offs

- **No second location, stated as an accepted risk.** When the box is
  unreachable, an external check says so and diagnosis begins with the facility's
  remote hands. Qoida accepts multi-hour outages of this shape at this size.
- **The uplink and the power feed are somebody else's** and cannot be made
  redundant from here. Their failure is an outage Qoida can only wait out.
- **One operator is a single point of failure that credential escrow bounds but
  does not remove.** Escrow converts "possibly never" into "days"; nothing
  available at this size converts it into "minutes".
- Residency is treated as closed on a business determination. If that changes,
  the response is a per-tenant deployment decision under a new ADR, not a
  re-architecture — and this topology already satisfies the strictest reading.

## Portability rules

Every ADR port that exists to hide a provider — `ObjectStorage` in ADR 0010,
`SecretResolver` in ADR 0028, `RateLimiter` in ADR 0033 — is load-bearing,
because each will one day be re-implemented against an AWS service. The rules
that follow:

- Object storage is **S3-compatible only**. MinIO here, S3 later. Never code to a
  provider's URL conventions, as ADR 0010 already requires.
- No provider-specific managed service enters the design that has no self-hosted
  equivalent to fall back to.
- Envelope encryption in ADR 0029 is what makes the eventual key migration
  tractable: moving to a KMS re-wraps data keys under a new key-encryption key
  rather than decrypting and re-encrypting every record.
- Infrastructure is reproducible from versioned code. The recovery times above
  assume a rebuild is a checkout and a script, not an archaeology exercise.

## Consequences for other ADRs

- **ADR 0028 secrets**: OpenBao, self-hosted on the box. Colocation offers no
  managed secrets service, so this is settled rather than preferred. The
  `SecretResolver` port keeps a later switch a deployment change.
- **ADR 0029 encryption**: key-encryption keys live in OpenBao. They are inside
  the failure domain, which is why the unseal material is part of the credential
  escrow above rather than an operational detail.
- **ADR 0033 caching**: self-hosted Valkey when the measured need arrives, on the
  same host, competing for the same RAM.
- **ADR 0008 workflow**: Temporal Cloud stays rejected. It was rejected on
  self-hosting burden and residency; the burden argument is stronger on this
  topology, not weaker, and Cloud would put an external dependency inside tenant
  provisioning. The recorded trigger — more than roughly five durable workflows —
  remains the right test.
- **ADR 0023 recovery**: its restore rehearsals, failover exercises, and backup
  verification matter more here than they would on managed infrastructure,
  because nobody else is doing them. Its open RPO/RTO inputs now have concrete
  candidate numbers in the failure table above to approve or reject.
- **ADR 0043 reporting**: there is no PostgreSQL streaming replica on one
  machine. The reporting query path runs against the primary with the smaller
  budget that ADR 0043 already anticipates.

## Data placement and what crosses a border

Placement is driven by operational fit. Classification from ADR 0029 still
governs who and what may access each store.

```text
PostgreSQL (all business data)        colocated, self-operated, PITR target, restore-rehearsed
Object storage (media, evidence)      colocated MinIO, private, versioned, mirrored off-site
Kafka                                 colocated, single broker; PostgreSQL remains authoritative
Keycloak                              colocated, own database
Secrets and key material              colocated OpenBao; unseal material in escrow off-site
Backups and archives                  encrypted before upload, off-site bucket plus sealed media
Logs, traces, metrics                 colocated, PII-free by ADR 0029, rotated with a hard cap
```

Everything above is in Uzbekistan. The following leave, and each was chosen
rather than defaulted:

| What leaves | What it carries | Why it is acceptable |
|---|---|---|
| Off-site backup, if the destination is foreign | AES-256 ciphertext only | The key never travels with it and is not held in the destination's account. A domestic bucket is preferred first for exactly this reason |
| External uptime check | A URL, a status code, a response time | The probe hits an unauthenticated health endpoint that returns status and nothing else. No tenant, customer, or order data exists in the request or the response |
| Alert delivery (Telegram, SMS) | Service name and alert name | No order, customer, or amount ever appears in an alert body. SMS falls back to a domestic provider, so the last-resort path stays in-country |
| Managed error tracking | Stack traces and request context | Adopted only with personal data scrubbed before send: no request bodies, no query strings, no headers. Error tracking is a log sink and inherits ADR 0029's rule unchanged; nothing new is permitted because it is a vendor rather than a file |
| CDN edge | Public menu media and storefront assets | No personal data cached, and authenticated traffic is not terminated at the edge. Where an edge provider would terminate TLS for authenticated traffic, it becomes a processor and is assessed as one |
| POS, payment, and SMS providers | Order and contact data as each integration requires | Clopos, Click, Payme, and Eskiz are domestic. A second POS vendor later does not change this ADR |

A processor register keyed by ADR 0029 data class records which classes each of
these may see. It is a privacy and access-control artifact, not a residency one.

## Environment isolation rules

- Separate Keycloak realms, clients, and signing keys per environment.
- Separate object storage buckets and credentials, with no cross-environment
  access grant.
- Separate Kafka clusters; staging never shares production's.
- Separate OpenBao instances, and no staging policy that names a production path.
- Production database credentials exist only in production's secrets manager.
- Staging and production VMs carry hard CPU, memory, and disk quotas so neither
  can starve the other.
- Provider sandbox and production bindings are structurally distinct ADR 0026
  installations and cannot be selected by a user-supplied URL.
- Production personal data is never copied downward.

## Implementation checklist

- [x] Choose the hosting platform: a colocated server in Uzbekistan (2026-08-23).
- [x] Choose the orchestrator: Docker Compose on VMs, with the ceiling and the
      revisit triggers recorded above (2026-08-23).
- [x] Build and prove the backup and restore path end to end, verified by a
      rehearsal against seeded data (`infra/backup`). Proved against a second
      local MinIO, which models the topology but not a real remote destination.
- [x] Write the single-point-of-failure list with an honest recovery time for
      each entry.
- [ ] Point `QOIDA_BACKUP_OFFSITE_*` at a genuine off-site bucket with versioning
      and object lock, using a credential scoped to that bucket alone, and
      perform one restore from it. **Gate: no live tenant order before this.**
- [ ] Ship continuous WAL archiving to the off-site bucket, moving worst-case
      loss from 24 hours to 5 minutes. **Gate: before pilot cutover.**
- [ ] Mirror the media bucket off-site and include it in the rehearsal.
- [ ] Store the backup passphrase in OpenBao and seal an offline copy held away
      from both the server room and the backup account.
- [ ] Establish credential escrow — OpenBao unseal material, backup passphrase,
      registrar and facility accounts — with a named holder and a review on every
      rotation.
- [ ] Add the RAID mirror, and keep a spare disk at the facility.
- [ ] Stand up the external uptime check and the backup dead-man's switch, and
      verify an alert arrives with the box powered off.
- [ ] Implement the six alerts above and nothing else; implement the health
      watchdog, log rotation caps, and certificate renewal.
- [ ] Write the six runbooks, and test each by following it literally.
- [ ] Document the colocated server: hardware, network, power, remote access, and
      the facility's support, remote-hands, and escalation terms in writing.
      Confirm whether a second uplink is available.
- [ ] Provision the staging and production VMs with quotas, and the four
      environments with isolated identities, keys, buckets, topics, and realms.
- [ ] Script the blue/green swap behind the reverse proxy before the maintenance
      window is needed rather than after.
- [ ] Establish the processor register keyed by ADR 0029 data class.
- [ ] Define the sanitized staging dataset generation procedure.
- [ ] Measure and record the restore time, and re-measure monthly.

## Exit criteria

The platform runs on the colocated server under Docker Compose on separate
staging and production VMs; a restore has succeeded **from the off-site
destination** and its duration is recorded; worst-case data loss is five minutes
rather than a day; the six alerts exist and no others page at night; an alert
demonstrably arrives while the machine is powered off; the credential escrow
exists with a named holder; every processor in use is recorded against the ADR
0029 data classes it may see; and ADRs 0028, 0029, and 0033 have had their
deferred choices resolved.

## References

Background on the residency regime that this ADR determined does not bind Qoida,
retained so the reasoning is reconstructable if the position changes:

- [Uzbekistan: New Requirements for Uzbek Citizens' Personal Data Localization Enter into Force (Library of Congress)](https://www.loc.gov/item/global-legal-monitor/2021-05-07/uzbekistan-new-requirements-for-uzbek-citizens-personal-data-localization-enter-into-force/)
- [Uzbekistan amends personal data law to facilitate global payment systems (Kun.uz, March 2026)](https://kun.uz/en/news/2026/03/27/uzbekistan-amends-personal-data-law-to-facilitate-global-payment-systems)
- [ADR 0023: Production operating model, observability, security, and recovery](../partial/0023-production-operating-model-observability-security-and-recovery.md)
- [`infra/backup/README.md`](../../../infra/backup/README.md) — the backup path, its configuration, and what the rehearsal does and does not prove
