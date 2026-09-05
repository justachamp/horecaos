# ADR 0073: Production runs on a rented machine in Uzbekistan, not on hardware we own

- Decision status: Proposed
- Implementation status: Not started
- Date proposed: 2026-09-05
- Date decided: —
- Deciders: platform owner (direction and the open inputs below), Claude (architecture)
- Depends on: 0001, 0010, 0023, 0028, 0029, 0033, 0056, 0057
- Supersedes / Superseded by: Supersedes ADR 0034 and ADR 0061
- Open inputs: ~~whether the provider's object storage supports S3 SigV4
  presigned PUT and GET~~ — **closed 2026-09-05 by probe against the real
  endpoint: presigned PUT and GET both work, bytes round-trip, HEAD reports
  the right size and content type. One caveat, now fixed in
  `ObjectStorageConfiguration`: the store refuses the SDK's default
  `x-amz-checksum-*` headers with HTTP 400, so the platform would have failed
  on every upload until `requestChecksumCalculation` was set to
  `WHEN_REQUIRED`**; the contract, SLA and
  support-response terms (owner); where off-provider backups land, with this
  record's own proposal below (owner); whether the colocated hardware is
  retired, kept as a warm spare, or repurposed as that backup target (owner)

## Context

ADR 0034 chose a colocated server in Tashkent, and it was right on the facts it
had. Three of them were closed at the time: the hardware **already existed and
was therefore nearly free in cash terms**; **international contracting and
payment from Uzbekistan is a real obstacle rather than a preference**; and **one
person operates this platform** — not a small team, one. ADR 0061 then committed
to owned hardware first while insisting the deployment stay portable by
construction, and built the artifacts to prove it: pinned images, a Caddy edge,
resource limits, a registry-pull tree, a bare-OS runbook.

Two things have changed since.

**A local cloud provider removes the objection that ruled out cloud.** ADR 0034
did not reject cloud hosting on latency or on law — it had already determined
that no in-country storage obligation binds this platform. It rejected it
because contracting and paying an international provider from Uzbekistan is
genuinely hard. A domestic provider, contracted and paid in UZS, is not that
transaction. It also keeps the property the colo was chosen for: the machine is
in the country its customers order from, so a kitchen screen and a POS sync are
not crossing a border twice.

**The zero price on owned hardware was a cash price, not an operating one.** A
colocated box that fails needs somebody physically present, and the same record
that chose it says there is exactly one person, "alone, sometimes asleep, and
occasionally on a plane". A rented machine replaces a hardware fault with a
support ticket. That is a materially different night.

The stack has also grown since both records: three frontend containers, an
observability overlay, an RLS backstop, and a second deployment tree that wave
55 established as authoritative. None of that changes the shape — Docker Compose
on one machine — but it does raise the floor on memory, which the sizing below
answers concretely rather than by feel.

## Decision

**Production runs on a virtual machine rented from a cloud provider inside
Uzbekistan, with the provider's object storage beside it.** In-country stays;
hardware ownership goes.

**The deployment does not change.** The `deploy/` tree — pinned images, Caddy
edge, resource limits, registry pull, no server-side build — is used as it
stands. This is ADR 0061's portability promise being *collected*, not
abandoned: a deployment designed to move is now moving, and the fact that
nothing needs rewriting is the evidence that the promise was real.

**Sizing is 8 vCPU, 16 GB RAM, 100 GB SSD**, and the SSD is not a preference.
PostgreSQL fsyncs on every order commit — roughly 10–15 ms on spinning disk
against under 1 ms on SSD — and the outbox relay and delivery sourcing each poll
**every second**, so the database is doing constant small random reads. Spinning
disk would not fail; it would make the platform feel slow in a way that is hard
to attribute later. The memory figure comes from the compose file's own declared
limits, which total 13.1 GB across all services and roughly 10.6 GB in steady
state once MinIO is gone.

**Object storage is at least 100 GB across at least two buckets**, media and
backups never sharing one. The backup scripts write to an S3 bucket, so that
space carries encrypted database dumps as well as menu images and courier
delivery evidence; 10 GB would last months and then start failing backups, which
is the worst item on that list to run out of.

**MinIO is removed: the provider supports SigV4 presigned URLs.** Measured, not
asked — a probe ran the platform's own client and presigner against the real
endpoint on 2026-09-05 and passed every step. It also found the store answers
400 to the SDK's default flexible-checksum headers, so
`ObjectStorageConfiguration` now sets `requestChecksumCalculation` and
`responseChecksumValidation` to `WHEN_REQUIRED`; without that the platform
fails on every upload, not intermittently. The original conditional read:
ADR 0010's media flow *requires* `presignUpload` and `presignDownload` — a
browser uploads straight to storage and reads private objects through short-lived
signed URLs. "S3-compatible" is answered loosely in sales conversations; this
record wants the specific question asked. If the answer is no, MinIO stays, the
memory budget gains it back, and this record is amended rather than quietly
ignored.

**Backups go off-provider.** Storing them with the primary means one provider
incident takes the platform and its recovery path together. This record proposes
the **colocated hardware already owned** as that target: it exists, it is paid
for, it is in a different building on a different network, and repurposing it
turns a retiring asset into the off-box half ADR 0023 still lists as unbuilt.

**This is still one machine, and this record does not claim otherwise.** A
rented VM is not more available than an owned box — it is faster to replace. High
availability needs more than one replica, and more than one replica is blocked
until the two process-affinity jobs ADR 0023's Runtime shape names are moved onto
a durable handoff.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep the colocated server (ADR 0034's decision) | The cash price was zero but the operating price is not: a hardware fault needs a person in a building, and there is one person. No resize path without buying metal | The provider's terms or reliability prove worse than owning; the hardware is retained as the backup target either way |
| AWS (EC2, or ECS/EKS) | Latency from Tashkent to the nearest region (~90–110 ms) on every POS sync and kitchen refresh; the international payment obstacle ADR 0034 recorded; ~$90–140/mo for EC2 against ~$50–90 locally, and ~$300–400 for ECS/EKS once MSK and RDS are priced in | Expanding beyond Uzbekistan, or needing a second region for disaster recovery |
| DigitalOcean | Cheaper and far simpler than AWS, and Spaces is a drop-in for MinIO — but the nearest region is Frankfurt or Bangalore, so it loses the in-country latency that is the point, and keeps the payment obstacle | Same trigger as AWS: a reason to be outside the country |
| Container orchestration (ECS, EKS, Kubernetes anywhere) | Buys replicas the platform cannot yet run: two scheduled jobs hold state tied to the HTTP-serving process, so a second replica silently stops POS dispatch and SSE upkeep. It is also a second product to operate, for one operator | Those two jobs move to a durable handoff **and** load genuinely exceeds one machine |
| Managed PostgreSQL and Kafka | A managed Kafka's monthly floor alone exceeds the whole machine; managed Postgres roughly doubles it. Both are proven self-hosted here, with backup and restore scripts already written | Operating the database becomes the binding constraint on the operator's time |

## Consequences

### Positive

- A hardware fault becomes a support ticket rather than a drive across Tashkent.
- Resizing is a control-panel action, so the sizing above can be wrong without
  being expensive.
- In-country latency is kept, which is why the colo was chosen in the first place.
- Contracting and payment are domestic, in UZS — the specific obstacle ADR 0034
  raised against cloud does not apply to a local provider.
- Provider object storage removes a stateful container from the box, and with it
  the arrangement where media shares a disk with the database that indexes it.
- ADR 0061's portability claim gets tested for real, on the first move.

### Negative

- A recurring bill replaces hardware that was already paid for. This decision
  costs money that the previous one did not.
- The platform depends on one provider's availability, and there is no walking
  into their building.
- Provider object storage is a **hard dependency on presigned URL support**. If
  it is absent, part of this decision reverses.
- A single local provider is a smaller operation than a hyperscaler, with less
  public track record on durability and incident communication.
- Nothing here improves availability. One machine remains one machine.
- Owned hardware still needs a decision — retired, spare, or backup target — and
  until that is made it is an asset drawing cost with no defined role.

### Accepted trade-offs

- **Recurring cost for operational simplicity**, deliberately, on a platform run
  by one person.
- **One provider, in-country**, over multi-region resilience that this pilot
  neither needs nor can operate.
- **Single node**, until the process-affinity work makes a second replica
  meaningful. Buying orchestration before then would buy nothing.

## Specification

**Machine.** 8 vCPU, 16 GB RAM, 100 GB SSD. The compose stack from `deploy/`,
unchanged. Memory limits set explicitly, because every consumer is env-capped and
a larger machine otherwise changes nothing:

```
HORECAOS_APP_MEMORY_LIMIT=3G          HORECAOS_APP_CPU_LIMIT=4.0
HORECAOS_DB_MEMORY_LIMIT=4G           HORECAOS_DB_CPU_LIMIT=4.0
HORECAOS_DB_SHARED_BUFFERS=2GB        HORECAOS_DB_EFFECTIVE_CACHE_SIZE=6GB
HORECAOS_DB_WORK_MEM=32MB
```

That budgets roughly 12.5 GB of limits and leaves the remainder to OS page cache,
which is what PostgreSQL actually reads through. The application container sets
no JVM heap flags today and inherits the JVM's container default of about 25% of
its limit; raising it is a separate, measured change and not assumed here.

**Object storage.** At least 100 GB. Two buckets minimum: one for ADR 0010 media,
one for ADR 0023 backups. Credentials are ADR 0028 references, never values.
Switching the platform is four settings — endpoint, region, bucket, and the two
credential references — because `media.api.ObjectStorage` admits no provider SDK
type across it.

**Backups.** `backup.sh` stages a `pg_dump` locally, verifies it with
`pg_restore --list`, encrypts, uploads and cleans up, so the disk needs transient
headroom of one dump. The destination is off-provider per the Decision.

**Disk alerting is required before cutover**, not after. A full disk stops
PostgreSQL writing, which stops orders, and it is the most preventable outage on
ADR 0023's list.

## Rollout and rollback

Provision, deploy from `deploy/` unchanged, and run the dev/test proving run
against the new host before any tenant traffic. **Keep the colocated server
running until a restore rehearsal — including ADR 0023's money reconciliation —
has passed on the new machine.** Cut over only then. Rollback is redeploying on
the colo from the same artifacts, which is the portability property this record
inherits from ADR 0061; if that rollback turns out not to work, the portability
claim was false and this record's premise fails with it.

## Implementation checklist

- [ ] Confirm SigV4 presigned PUT/GET with the provider, in writing
- [ ] Provision the machine and object storage; two buckets
- [ ] Deploy `deploy/` unchanged; set the memory and CPU limits above
- [ ] Point media at provider storage; remove MinIO if presigning is confirmed
- [ ] Point backups off-provider; decide the colo hardware's role
- [ ] Disk-space alerting wired before cutover
- [ ] Proving run against the new host
- [ ] Restore rehearsal with money reconciliation, on the new host
- [ ] Retire or repurpose the colocated server, per the owner's decision

## Exit criteria

The pilot tenant is served from the rented machine; a restore rehearsal has
completed on that machine with its money reconciliation checked; backups land
with a provider that is not hosting the platform; and — the portability claim
made concrete — the same artifacts redeploy on the colocated server without
modification, demonstrated at least once rather than asserted.

## References

- Supersedes ADR 0034 (hosting, topology, residency) and ADR 0061 (owned
  hardware first, portable by construction). The residency determination in ADR
  0034 stands: no in-country storage obligation binds this platform, which makes
  in-country hosting a choice rather than a requirement.
- ADR 0010 (media and object storage), ADR 0023 (production operating model,
  backups, alerting), ADR 0028 (secret references), ADR 0056 (RLS backstop),
  ADR 0057 (per-surface contracts)
