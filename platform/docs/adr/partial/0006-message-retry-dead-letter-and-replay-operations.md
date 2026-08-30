# ADR 0006: Message retry, dead-letter, and replay operations

- Decision status: Accepted
- Implementation status: Partial — `V0010` adds the `RESOLVED` terminal state
  and its evidence columns to both `integration.outbox_events` and
  `integration.inbox_messages` with lifecycle check constraints;
  `FailureCategory` is the shared classification; `FailureOperationsService`
  implements compare-and-set retry and resolve with immutable audit facts and
  routes an `UNCERTAIN_EXTERNAL_OUTCOME` resolve through the ADR 0027
  maker-checker (`SecondApproverRequiredException`);
  `FailureOperationsController` exposes list/retry/resolve at
  `/api/v1/control-plane/integration/failures` behind
  `INTEGRATION_FAILURE_READ/RETRY/RESOLVE`; `MessagingBacklogMetrics` publishes
  the backlog and dead-letter gauges and `infra/observability/horecaos-probe.sh`
  evaluates the stall and monetary-dead-letter alerts against them, with
  `docs/runbooks/outbox-not-draining.md` and
  `docs/runbooks/dead-letter-decision.md`; `RetryBackoff` gives both retry loops
  equal jitter under an injectable random source, `InboxRetryWorker` re-drives due
  and lease-expired inbox rows from PostgreSQL, and `InboxExecutor` parks a record
  behind an earlier unresolved sibling for the same aggregate rather than letting
  it overtake one. The bootstrap gap named here is closed: `JdbcAuthorizationService`
  now confers `IAM_GRANT_MANAGE` on a caller holding the `platform-admin` realm
  role, so on a fresh deployment an operator can grant themselves
  `INTEGRATION_FAILURE_READ`, `_RETRY` and `_RESOLVE` through the ordinary audited
  API and reach these endpoints. The two single-item reads this record specifies
  below now exist: `GET /outbox/{eventId}` and `GET /inbox/{consumer}/{eventId}`
  answer behind `INTEGRATION_FAILURE_READ` at platform scope with the routing,
  retry and resolution facts of one item and **without its payload** — an
  operator's authority to work the failure queue is not authority to read the
  customer record behind an item (ADR 0029), so the projection carries the
  aggregate identity instead and the business object is reached through the API
  that owns it; the inbox read is keyed on `(consumer, event)` because each
  consumer holds its own decision; an id outside the tenant the caller narrowed
  to answers 404 with a body byte-identical to an id that exists nowhere, so the
  pair of statuses is not an enumeration oracle; and both runbooks now send an
  operator to those reads rather than to `psql`. Not built: the dashboard, which
  ADR 0023 owns and deliberately replaced with a probe;
  `SecondApproverRequiredException` still has no entry in the ADR 0031 error
  handler, so a resolve that a policy sends to the maker-checker answers 500
  instead of a problem document naming the approval being waited on — the
  refusal is correct and nothing is resolved, but the operator is told nothing;
  and `last_error`, returned by both the list and the single read, is the one
  projected field ADR 0029 does not actually guarantee, being the exception
  class plus its message, bounded and stack-trace-free but unclassified.
  Classifying it belongs to `OutboxRelay` and `InboxExecutor`, where it is
  written.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), operations
- Depends on: ADR 0004, ADR 0005
- Supersedes / Superseded by: —
- Open inputs: Resolve-approval policy and `integration-operator` role definition (operations, security)

## Context

The outbox retains events that cannot be published, and the planned inbox will
retain events that a consumer cannot process. Without an explicit operational
model, dead letters can silently block later aggregate events, unsafe manual SQL
may be used to replay work, and permanent contract failures may be retried
forever.

## Decision

Use PostgreSQL as the authoritative retry and dead-letter work state. Kafka DLT
records are diagnostic notifications, not the only copy of failed work. Retry
workers use leases and bounded exponential backoff. Operations can inspect,
retry, or explicitly resolve failures through audited APIs; they never edit
outbox/inbox rows directly.

Outbox and inbox failure semantics remain separate:

- An outbox failure means Kafka has not been durably confirmed. Retrying sends
  the same immutable event ID.
- An inbox failure means a named consumer has not completed its side effect.
  Retrying reuses the same inbox ID and event ID.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Kafka dead-letter topics as the authoritative store of failed work | Topic retention expires, records cannot be queried by tenant or error class, and an operator would need Kafka tooling to make a business decision. PostgreSQL keeps the work item queryable and auditable | Never as the authority. DLT records remain useful as diagnostic notifications |
| Spring Kafka `DefaultErrorHandler` and retryable topics alone | Excellent transport-level retry, and it is retained as the mechanism, but it provides no operator inspection, no approval workflow, no audit evidence, and no reconciliation before a monetary replay | Never as the complete answer |
| Infinite retry with backoff | A permanent contract failure never surfaces; it just consumes capacity forever while the business problem stays invisible | Never |
| Engineers replaying with manual SQL | No audit trail, no maker-checker, no idempotency guarantee, and no way to prove afterwards that a refund was not sent twice. This is the specific practice the ADR exists to eliminate | Never |
| A managed queue with a built-in dead-letter console | Would fragment the event backbone across two technologies and lose the partitioned ordering the outbox depends on | Never |
| Auto-resolve failures older than a threshold | Silently discards business facts, including monetary ones. Resolution must be an explicit, evidenced human decision | Never |

## Implementation notes

Delivered: the `RESOLVED` terminal state on both the outbox and the inbox, the
shared failure classification, and compare-and-set retry and resolve commands
that record ADR 0027 audit facts.

`RESOLVED` is deliberately distinct from `PUBLISHED` and `PROCESSED`. An
operator deciding that no further processing is required is a different fact
from the platform completing the work, and collapsing them would make every
operational override invisible in counts and dashboards afterwards. The database
lifecycle constraint enforces that a resolved row carries a resolver and a
reason.

Retry returns the same immutable work to a pending state and never mints a new
event id, because the provider idempotency key derives from it: a new id would
defeat the deduplication the retry depends on. A test asserts the id survives.

Resolving requires a reason always, and reconciliation evidence when the
category means a provider may already have acted. Declaring an uncertain
provider outcome resolved without evidence is precisely how a duplicate charge
gets blessed.

**The approval policy, decided 2026-08-20.** Retrying is safe and repeatable and
never requires a second approver — gating it would only slow an incident.
Resolving is irreversible, so an `UNCERTAIN_EXTERNAL_OUTCOME` requires a second
approver through the ADR 0027 maker-checker, and the approval is bound to that
exact item and category so it cannot be reused for another uncertain payment.
Every other category resolves with one approver plus a reason and an audit fact.
Friction goes where money is, and nowhere else.

Control-plane endpoints now exist behind `integration.failure.read`,
`integration.failure.retry`, and `integration.failure.resolve`. A no-op is
reported as `no_change` rather than an error, because during an incident it
usually means a colleague acted first.

**Jitter, and why equal rather than full.** `OutboxRelay.backoff()` was pure
exponential, which makes the delay a function of the attempt count alone: every
replica that failed against one broker outage held the same count, computed the
same delay, and woke in the same millisecond — a burst aimed at a dependency
that had just proved it could not take one, re-forming on every attempt because
the delays stayed identical. `RetryBackoff` is shared by both loops. It uses
equal jitter — half the computed ceiling as a floor, the other half randomised —
rather than full jitter, whose lower bound is zero: after a ten-minute outage
some caller would retry almost immediately and the backoff it computed would
have protected nothing. The random source is injectable so the calculation is
deterministic in a test without asserting on a distribution.

**The aggregate blocker, and why the offset is still acknowledged.**
`hasEarlierUnresolvedForAggregate` existed with no caller, so the ordering rule
below was written down and not enforced: once an earlier event dead-lettered its
offset was acknowledged, and the next event for the same aggregate applied on top
of a transition that never happened. `InboxExecutor` now checks it before taking
the processing lease. A blocked record is parked as `RETRY_PENDING` with the
error code `BLOCKED_BY_EARLIER_EVENT` and **does not** spend retry budget —
arriving second is not a failed attempt, and charging it would eventually
dead-letter a perfectly good event and leave an operator two items to judge
instead of one. Its Kafka offset is acknowledged, which looks wrong until you
count what the alternative stalls: a partition carries many aggregates, and
withholding the offset would hold every one of them behind a single dead letter,
against this ADR's own rule that other aggregates continue.

**The retry worker exists because the inbox had no retry loop at all.** A
`RETRY_PENDING` row waited on a Kafka redelivery, which arrives only because the
offset was withheld, and only while the consumer stays assigned to that
partition. A rebalance, a restart, or a deploy moved past the record and the row
then waited on nothing. Two states had no owner whatsoever: the row parked behind
an earlier sibling, whose offset is deliberately acknowledged, and the row
abandoned mid-flight by a worker that died holding its lease.
`InboxRetryWorker` re-drives both from PostgreSQL rather than from Kafka —
by the time an item is due its record may be past the broker's retention, and the
inbox row has been the authoritative copy of the work since ADR 0005. It shares
`InboxExecutor`'s driving path with the listener, so an item cannot acquire
different blocking or lease semantics depending on which one picked it up.

## Failure classification

Define stable error categories:

| Category | Examples | Action |
|---|---|---|
| `TRANSIENT_INFRASTRUCTURE` | timeout, broker/database unavailable | retry |
| `TRANSIENT_PROVIDER` | provider 429/5xx, temporary circuit open | retry under route policy |
| `CONTRACT_UNSUPPORTED` | unknown event version/type | dead-letter immediately |
| `PAYLOAD_INVALID` | missing required fact, hash collision | dead-letter immediately |
| `DOMAIN_REJECTED` | invariant or stale transition | record terminal rejection; normally no retry |
| `AUTHORIZATION_REJECTED` | invalid tenant/scope/service identity | dead-letter and security alert |
| `UNCERTAIN_EXTERNAL_OUTCOME` | provider may have accepted a command | reconcile before retry |

Store a bounded safe message plus an error code. Full stack traces belong in
secured logs/traces, not business tables.

## Retry policy

The default is exponential backoff with jitter and a maximum attempt count.
Each consumer/route overrides timeout, initial delay, maximum delay, and retry
budget through reviewed configuration. Invalid data is never retried by a
timer.

Preserve ordering per aggregate:

- A retrying or dead-lettered outbox event blocks later events for the same
  topic/partition key.
- A retrying inbox item blocks later state-changing events for the same
  consumer and aggregate unless that consumer explicitly documents commutative
  processing.
- Other aggregates continue processing.

## Replay governance

Provide platform control-plane endpoints under
`/api/v1/control-plane/integration/failures`:

```text
GET  /outbox?status=DEAD_LETTER&tenantId=&eventType=
GET  /outbox/{eventId}
POST /outbox/{eventId}/retry
POST /outbox/{eventId}/resolve
GET  /inbox?consumer=&status=DEAD_LETTER&tenantId=
GET  /inbox/{consumer}/{eventId}
POST /inbox/{consumer}/{eventId}/retry
POST /inbox/{consumer}/{eventId}/resolve
```

`retry` returns the same immutable work to `PENDING`/`RETRY_PENDING`; it never
creates a new event ID. `resolve` means an authorized operator has established
that no further processing is required. It requires a reason and, for monetary
or provider side effects, reconciliation evidence. Call the terminal state
`RESOLVED`, not `PUBLISHED` or `PROCESSED`, so operational override remains
visible.

## Authorization and audit

- Reads and retries require the `integration.failure.read` and
  `integration.failure.retry` capabilities from ADR 0025; initially these are
  granted only to `platform-admin`.
- Resolution requires a separate future `integration-operator` or
  `platform-admin` approval policy before production.
- Monetary, payment, refund, and provider-uncertain failures require maker-
  checker approval above configured thresholds.
- Every action records actor subject, tenant, reason, prior/new state,
  correlation ID, time, and evidence reference as an ADR 0027 audit fact.
- Resolution above configured thresholds uses the ADR 0027 approval model rather
  than a retry-specific maker-checker implementation.
- Raw payload display is redacted by classification; credentials and tokens are
  never exposed.

## Concurrency and idempotency

Replay APIs use optimistic compare-and-set from the expected terminal state.
A lease token prevents a stale worker from completing work after replay. Two
operators retrying simultaneously produce one state change and one audit fact.
Provider commands retain the original idempotency key.

## Kafka diagnostic DLT

Optionally publish a sanitized diagnostic envelope through the ordinary
outbox to a consumer-owned topic such as
`integration.failures.events`. Do not copy credentials, full provider payloads,
or regulated customer data. A DLT notification contains failure ID, event ID,
tenant ID, consumer/route, error code, attempt count, and timestamps.

## Observability and alerts

- Gauge pending, retrying, processing, dead-letter, and resolved counts.
- Alert on oldest pending age, retry exhaustion, lease recovery, hash
  collision, and authorization failure.
- Dashboard by bounded topic/consumer/event/error labels.
- Provide runbook links from alerts and the operations API response.

## Testing

- Backoff and maximum-attempt calculations are deterministic under a fixed
  clock/random source.
- A dead letter blocks only its own aggregate ordering key.
- Replay preserves event and provider idempotency keys.
- Two concurrent replay requests result in one accepted transition.
- Unauthorized and cross-tenant inspection is denied.
- Resolve requires reason/evidence and produces immutable audit data.
- Broker outage during a replay does not lose the PostgreSQL work item.

## Rollout and rollback

Ship read-only failure views first, then retry, and add resolve last after the
approval policy is accepted. Rollback disables mutations but retains failure
records and dashboards. Never delete dead-letter evidence during rollback.

## Consequences

### Positive

- Failed work is queryable, attributable, and replayable without direct SQL.
- A monetary replay carries reason, evidence, and approver, so a refund can be
  defended months later.
- Error classification is shared, so retry behavior is consistent across
  consumers and routes instead of being decided per handler.

### Negative

- Dead-letter blocking preserves per-aggregate ordering but means one unresolved
  event stalls every later event for that key until an operator acts. Without
  alerting on oldest-pending age this is a silent backlog.
- Approval workflows add operational latency to incident recovery, precisely
  when speed feels most valuable.
- Another set of control-plane endpoints, roles, and audit surface to secure.

### Accepted trade-offs

- Operators cannot fix data quickly by hand. That friction is the control, and
  it will occasionally be frustrating during an incident.
- `RESOLVED` is deliberately distinct from `PUBLISHED` and `PROCESSED`, so
  metrics must treat operator overrides as their own category forever.

## Implementation checklist

- [x] Add `RESOLVED` lifecycle support to both the outbox and the inbox (`V0010`).
- [x] Implement the shared failure classification (`FailureCategory`).
- [x] Implement retry workers with jitter, leases, and aggregate blockers. — `RetryBackoff` (equal jitter, injectable random source) is used by `OutboxRelay` and `InboxExecutor`; `InboxRetryWorker` re-drives due and lease-expired inbox rows; `JdbcInboxStore.hasEarlierUnresolvedForAggregate` is now called before every processing lease and parks the later event without spending its retry budget. The outbox has had its per-partition-key blocker in `claimBatch` since `V0008`.
- [x] Add control-plane read, retry, and resolve endpoints behind ADR 0025 capabilities.
      — All eight in the Replay governance list exist, including the two
      single-item reads. Those two return no payload: ADR 0029 keeps personal
      data out of a dead-letter summary, and `integration.failure.read` is a
      cross-tenant capability that `platform-support` holds without
      `customer.pii.reveal`, so rendering the row would hand every operator
      every tenant's personal data with no purpose recorded. A cross-tenant id
      is answered as absent, not forbidden, so the status cannot be used to
      confirm which ids are real.
- [x] Add compare-and-set retry and resolve commands with immutable audit facts.
- [x] Define the approval policy: retry never needs a second approver; resolving an `UNCERTAIN_EXTERNAL_OUTCOME` does, through the ADR 0027 maker-checker.
- [x] Add alert rules, dashboards, and operator runbooks. — Alerts and runbooks are built: `infra/observability/horecaos-probe.sh` evaluates the order-flow stall and monetary dead-letter rules against `MessagingBacklogMetrics`'s gauges, and links `docs/runbooks/outbox-not-draining.md` and `docs/runbooks/dead-letter-decision.md`. The dashboard is deliberately absent here. ADR 0023 owns the observability stack, chose a direct-scrape probe and a dead-man's switch over Prometheus and a dashboard, and carries the dashboard on its own checklist; building one under this record would be a second answer to a question another record has already decided.
- [x] Add concurrency, replay, and evidence tests. Authorization tests land with the endpoints.

## Exit criteria

Operations can identify, safely retry, and audit every exhausted outbox/inbox
item without direct SQL, and no replay can duplicate a confirmed provider side
effect.
