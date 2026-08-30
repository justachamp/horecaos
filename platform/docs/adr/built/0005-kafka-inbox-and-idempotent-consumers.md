# ADR 0005: Kafka inbox and idempotent consumer foundation

- Decision status: Accepted
- Implementation status: Built — `V0009` creates `integration.inbox_messages`
  keyed `(consumer_name, event_id)` (`V0010` adds `RESOLVED`);
  `EnvelopeValidator` checks envelope, headers and payload hash;
  `JdbcInboxStore` implements claim, complete, retry and dead-letter;
  `InboxHandlerRegistry` registers handlers explicitly by consumer, type and
  version and fails startup on a collision; `TenancyEventListener` is a
  `@KafkaListener` on `tenancy.events` using the `AckMode.MANUAL` container from
  `InboxListenerConfiguration` and acknowledges only after every consumer's
  inbox transaction commits. `InboxExecutor` emits `horecaos.inbox.records`
  outcome metrics. The first real consumer runs:
  `TenantSummaryProjection` (`control-plane-tenant-summary`) projects tenancy
  events into `reporting.tenant_summaries` (`V0011`) in the same transaction.
  `InboxExecutorTests` covers duplicate redelivery, rollback, payload
  collision, unknown type and version, concurrency and two consumers. The
  retention period in `Open inputs` is an operational approval, not missing
  code; no purge job is configured for `integration.inbox_messages` yet.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0004
- Supersedes / Superseded by: —
- Open inputs: Inbox retention period (product, operations) — provisional, as in ADR 0029

## Context

Qoida now commits tenancy events to PostgreSQL and relays them to Kafka with
at-least-once delivery. A producer can publish the same event more than once
when it crashes after Kafka accepts a record but before the outbox is marked
published. Kafka consumers can also receive a record again after a rebalance or
after processing succeeds but the offset commit fails. Consumer correctness
therefore cannot depend on exactly-once broker delivery.

The first durable business consumer must not be enabled until duplicate,
malformed, unsupported, and out-of-order records have defined behavior.

## Decision

Create a reusable SQL-first inbox foundation owned by `integration`. Every
consumer has a stable `consumer_name`. Before invoking business behavior, it
inserts the event into PostgreSQL under the unique key
`(consumer_name, event_id)`. The business side effect and transition to
`PROCESSED` occur in one PostgreSQL transaction whenever the side effect is in
the same database.

Kafka offsets are acknowledged only after the inbox transaction commits. A
duplicate whose inbox row is already `PROCESSED` is acknowledged without
re-running the handler. External provider calls are never performed inside the
inbox database transaction; the handler writes a provider-neutral command to
the transactional outbox instead.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Rely on the idempotent producer and Kafka exactly-once semantics | EOS protects Kafka-to-Kafka processing. It does not make a PostgreSQL write, a provider call, or an SMS send happen exactly once, which is where duplicates actually hurt | Never |
| Deduplicate in Redis/Valkey | Not transactional with the business write, and any eviction, failover, or restart silently reopens the duplicate window with no evidence that it happened | Never for correctness. A cache may front the inbox as an optimization once metrics justify it |
| Per-handler ad-hoc idempotency using natural keys | Works case by case but gives no uniform operational view, no payload-collision detection, and no replay path. Every new consumer would re-invent it | Never as the only mechanism. Natural-key uniqueness stays as a valuable second layer |
| Kafka Streams state stores for deduplication | Adds a stream-processing runtime and RocksDB state to operate, and the deduplication state would live outside the business database it must be consistent with | A genuine stream-processing use case appears, such as windowed aggregation for reporting |
| At-most-once consumption with auto-commit | Loses events on every rebalance. Losing a tenancy or payment fact is worse than processing one twice | Never |
| One shared inbox row per event across all consumers | A second consumer could never process an event the first already handled, and independent replay per consumer becomes impossible. The key is deliberately `(consumer_name, event_id)` | Never |

## Physical model

Flyway V0005 should create `integration.inbox_messages` with:

```text
id uuid primary key
consumer_name varchar(128) not null
event_id uuid not null
topic varchar(249) not null
partition integer not null
record_offset bigint not null
tenant_id uuid not null
event_type varchar(128) not null
event_version integer not null
aggregate_type varchar(64) not null
aggregate_id uuid not null
correlation_id varchar(128) not null
causation_id varchar(128) null
occurred_at timestamptz not null
payload jsonb not null
payload_sha256 char(64) not null
status varchar(24) not null
attempt_count integer not null
available_at timestamptz not null
processing_token uuid null
processing_started_at timestamptz null
processed_at timestamptz null
dead_lettered_at timestamptz null
last_error_code varchar(64) null
last_error varchar(2000) null
received_at timestamptz not null
updated_at timestamptz not null
```

Required constraints and indexes:

- Unique `(consumer_name, event_id)` for semantic deduplication.
- Unique `(consumer_name, topic, partition, record_offset)` for transport
  diagnostics.
- Non-negative partition, offset, version, and attempt count.
- JSON object checks for payload and trace metadata.
- Lifecycle consistency checks for processing, processed, retry, and dead
  states.
- Due-work index on `(consumer_name, available_at)` for retry workers.
- Aggregate-order index on
  `(consumer_name, topic, aggregate_id, occurred_at, event_id)`.
- Tenant/time index for operations and audit lookup.

Initial states are:

```text
RECEIVED -> PROCESSING -> PROCESSED
                       -> RETRY_PENDING
                       -> DEAD_LETTER
```

## Implementation notes

Delivered: the inbox table, envelope and header validation, payload hashing,
the handler registry, the executor, a Kafka listener with manual
acknowledgement, and the first real consumer.

**The first consumer is `control-plane-tenant-summary`**, which projects
tenancy events into `reporting.tenant_summaries` so the control plane can
answer "what does this tenant look like" without joining three tables and
counting rows. It was chosen because its effect lands in the same database,
so it commits with the inbox transition and calls nothing external — the
safest possible shape for the first thing to consume anything.

A projection is never an authority. Losing it is a rebuild, not a data loss,
and a test drops it and rebuilds it to keep that true.

**A module boundary the build caught.** `InboxHandler` and
`ExternalEventEnvelope` originally sat beside their implementation, which meant
no other module could implement a consumer at all. They now live in
`integration.api` as a named interface, with the store, executor, validator, and
listener staying internal.

**A design flaw the database caught.** The first implementation dead-lettered
the stored row on a payload-hash collision. When the stored row was already
`PROCESSED`, the lifecycle constraint rejected that — correctly. A processed row
is true evidence that the effect happened, and rewriting it as `DEAD_LETTER` to
describe a *different* record's problem would destroy that evidence. The
collision belongs to the arriving record, so a processed row is now left intact
and the collision is reported; an unprocessed row is still quarantined.

**Claiming uses `ON CONFLICT DO NOTHING`,** not a caught duplicate-key
exception, for the same reason as ADR 0031's idempotency claim: a constraint
violation aborts the surrounding PostgreSQL transaction and the follow-up read
would fail.

## Envelope validation

Create an immutable `ExternalEventEnvelope` contract. Validate before dispatch:

- Required IDs, type, positive version, occurrence time, tenant context, and
  JSON object payload.
- Kafka key equals the envelope aggregate ID for current contracts.
- Header event ID/type/version/tenant/correlation values equal the body.
- Event time is syntactically valid; an old event is allowed but observable.
- Payload size is within a configured limit.
- The SHA-256 hash is stable for a given `(consumer_name, event_id)`.

If a duplicate event ID arrives with a different payload hash, do not treat it
as a normal duplicate. Mark it as a contract collision and route it to
operations because a producer has violated event immutability.

## Java design

Add internal integration contracts resembling:

```java
interface InboxHandler<T> {
    String consumerName();
    String eventType();
    int eventVersion();
    void handle(ExternalEventEnvelope<T> event);
}

interface InboxExecutor {
    InboxResult execute(ConsumerRecord<String, String> record);
}
```

Handlers are registered explicitly by `(eventType, eventVersion)`. Do not use
unrestricted class names from incoming JSON for polymorphic deserialization.
Payload DTOs remain immutable and version-specific. Domain modules expose
commands or application APIs; consumers never import another module's internal
repositories.

## Transaction and acknowledgement algorithm

1. Poll a Kafka record with auto-commit disabled.
2. Parse and validate the envelope and headers.
3. Begin a PostgreSQL transaction.
4. Insert the inbox row with `ON CONFLICT DO NOTHING`.
5. If the existing row is `PROCESSED`, commit and acknowledge the record.
6. If the row is already actively processing under a valid lease, do not run a
   second handler.
7. Claim the row with a processing token.
8. Invoke the registered application handler.
9. Commit the business effect and `PROCESSED` inbox state together.
10. Acknowledge the Kafka offset only after commit.

An unsupported event version is a permanent contract failure, not an infinite
retry. A temporary database outage leaves the Kafka record unacknowledged.

## Tenancy and security

- Every inbox row carries non-null `tenant_id`.
- A handler must use the envelope tenant ID as verified context, not a payload
  field or arbitrary header.
- Logs may include event, tenant, aggregate, correlation, topic, partition, and
  offset, but not raw sensitive payloads.
- Payload retention and access must follow the source domain's classification.
- Operations queries are platform-admin only initially; tenant-scoped views
  require a later explicit data-exposure decision.

## Observability

Add counters for received, processed, duplicate, collision, retry, permanent
failure, and dead-letter outcomes. Add handler duration, inbox processing age,
and Kafka lag metrics tagged by bounded consumer/event labels. Never tag by
tenant ID, event ID, or aggregate ID in metrics.

## Testing

- Duplicate delivery runs the business handler exactly once.
- Offset redelivery after commit is acknowledged without another effect.
- Same event ID with different payload hash is quarantined.
- Transaction rollback leaves both inbox processing and business state
  uncommitted.
- Unsupported type/version follows permanent-failure policy.
- Cross-tenant payload references are rejected by domain/database constraints.
- Multiple consumer names may independently process the same event.
- A Testcontainers Kafka/PostgreSQL test covers poll-to-commit-to-ack behavior.

## Rollout and rollback

Introduce the table and library with no production listener enabled. Enable one
test consumer group, observe duplicates and lag, then enable one bounded
business consumer. Rollback disables the listener; committed inbox evidence is
retained and Kafka offsets are not manually advanced.

## Consequences

### Positive

- A duplicate Kafka record cannot duplicate a durable business effect, which is
  the precondition for enabling any real consumer.
- Every consumed event leaves queryable evidence with tenant, offset, and
  outcome, so "did we process this?" is answerable without reading topics.
- Payload-hash collision detection turns a producer contract violation into a
  visible incident instead of silent data corruption.

### Negative

- Every consumed event costs an extra insert and an extra update, so consumer
  throughput is bounded by PostgreSQL write capacity rather than broker speed.
- The inbox table grows with traffic and needs a retention and partitioning
  plan before high-volume consumers such as inventory are enabled.
- Manual acknowledgement means a handler bug can stall a partition, which is
  the correct behavior but requires alerting to be noticed.

### Accepted trade-offs

- Business handlers must not perform external calls, so any consumer needing a
  provider becomes a two-hop flow through the outbox. This is more code than a
  direct call and is deliberate.
- The same event processed by several consumers is stored once per consumer.
  Storage is duplicated in exchange for independent replay.

## Implementation checklist

- [ ] Approve the inbox retention period (the lifecycle is implemented).
- [x] Add the inbox migration and constraint tests (`V0009`).
- [x] Implement envelope and header validation and payload hashing (`EnvelopeValidator`).
- [x] Implement `JdbcInboxStore` claim, complete, retry, and dead-letter operations.
- [x] Implement the explicit handler registry, the Kafka listener with manual acknowledgement, and the acknowledge-only-after-commit contract.
- [x] Add `horecaos.inbox.records` outcome metrics and structured logging. Health information lands with the listener.
- [x] Add duplicate, rollback, collision, concurrency, and multi-consumer tests.
- [x] Document the first real consumer and its owner: `control-plane-tenant-summary`, owned by the tenancy control plane, projecting tenancy events into `reporting.tenant_summaries`.

## Exit criteria

A duplicate Kafka record cannot duplicate a durable business effect, and an
integration test proves that the offset is acknowledged only after the inbox
and business transaction commit.
