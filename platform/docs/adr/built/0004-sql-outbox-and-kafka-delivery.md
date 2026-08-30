# ADR 0004: SQL transactional outbox and Kafka delivery

- Decision status: Accepted
- Implementation status: Built — `V0004` creates `integration.outbox_events`
  with the `PENDING`/`PUBLISHING`/`PUBLISHED`/`DEAD_LETTER` lifecycle enforced
  by check constraints (`RESOLVED` added by `V0010`); `JdbcOutboxStore` claims
  with `FOR UPDATE SKIP LOCKED`, a bounded batch and a lease token, and refuses
  a candidate whose partition key has an earlier `PENDING`/`PUBLISHING`/
  `DEAD_LETTER` row, so a dead letter blocks its own aggregate;
  `markPublished`/`markFailed` are compare-and-set on the lease token.
  `TenancyOutboxEventListener` and `OrderingOutboxEventListener` persist typed
  events on `TransactionPhase.BEFORE_COMMIT`. `OutboxRelay` polls on a
  `@Scheduled` fixed delay outside any open transaction, retries with bounded
  exponential backoff to `qoida.messaging.outbox.max-attempts` and then dead
  letters, and emits `qoida.outbox.publications`.
  `KafkaOutboxPublisher` keys records by aggregate id and carries the event id
  in envelope and headers. Persistence is JdbcClient with Flyway and no JPA.
  Covered by `JdbcOutboxStoreTests` and `KafkaOutboxPublisherTests`.
- Date proposed: 2026-08-19
- Date decided: 2026-08-19
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0001
- Supersedes / Superseded by: —
- Open inputs: none

## Context

Committed tenant lifecycle changes must reach Kafka even when the broker is
temporarily unavailable. Publishing directly inside a database transaction can
lose an event or expose an event for rolled-back business state. The platform
also needs multiple relay replicas without duplicate concurrent claims.

## Decision

- Keep Flyway and explicit PostgreSQL SQL as the persistence authority; use
  Spring JDBC rather than JPA/Hibernate for the platform persistence adapters.
- Insert an `integration.outbox_events` row in the same PostgreSQL transaction
  as its business change.
- Publish typed tenancy application events in-process and persist them with a
  `BEFORE_COMMIT` transactional listener owned by the integration module.
- Claim due events in short transactions with `FOR UPDATE SKIP LOCKED`, a
  bounded batch, and an expiring lease token. Only the oldest unresolved event
  for a topic and partition key is claimable, so retries cannot reorder one
  aggregate's event stream.
- Publish to domain-oriented Kafka topics with the aggregate ID as the
  partition key and the event ID in both the envelope and message headers.
- Mark an event published only when its current lease token still matches.
- Use bounded exponential retries and retain exhausted events as operational
  `DEAD_LETTER` rows in PostgreSQL. A dead-lettered event blocks later events
  for that same partition key until an operator explicitly resolves it.
- Treat Kafka delivery as at-least-once. Every future consumer must persist and
  deduplicate the event ID before performing a durable side effect.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Publish to Kafka inside the business transaction | The classic dual write. A broker failure loses a committed business fact, and a rolled-back transaction can still publish an event that never happened | Never |
| Debezium change-data-capture with the outbox event router | Removes the relay code, but adds a Kafka Connect cluster and Debezium to operate as a second failure domain, and moves ordering, retry, and dead-letter policy out of code we own into connector configuration | Event volume makes the polling relay a measured bottleneck, or Kafka Connect is already being operated for ADR 0024 migration change capture |
| Kafka transactions and exactly-once semantics | EOS covers Kafka-to-Kafka processing, not PostgreSQL-to-Kafka handoff. Consumers would still need idempotency, so the inbox in ADR 0005 is required either way | Never as a substitute; useful later inside stream processing |
| JPA/Hibernate for platform persistence | Hides SQL exactly where this platform needs it visible: composite tenant constraints, `FOR UPDATE SKIP LOCKED`, partial unique indexes, and upserts. Lazy loading and dirty checking add failure modes inside relay and worker loops | Never for platform adapters |
| jOOQ instead of hand-written SQL | Genuinely attractive: typed SQL, near-JDBC overhead, and Apache-2.0 licensing when used with PostgreSQL. Not adopted now only because it adds a code-generation step to the build while the SQL surface is still small | Hand-written statements grow past roughly fifty, or a schema rename breaks queries without a compile error. This is the most likely future amendment to this ADR |
| PostgreSQL `LISTEN`/`NOTIFY` or `pg_cron` instead of Kafka | No consumer groups, no replay, no retention, and every consumer becomes coupled to the primary database | Never for the event backbone |
| RabbitMQ or SQS instead of Kafka | Simpler operationally, but loses partitioned per-aggregate ordering and replayable retention, both of which later ADRs depend on for reconciliation | Never; the ordering guarantee is load-bearing |

## Consequences

- A broker outage cannot remove a committed business event; the outbox remains
  queryable and retryable.
- A process crash after Kafka accepts a record but before PostgreSQL records
  success may deliver that event again. This is intentional and requires
  consumer inbox/idempotency handling.
- The relay does not hold a database transaction open while waiting for Kafka.
- Lease duration, publish timeout, retry limit, and batch size are operational
  configuration and require monitoring.
- The first topic is `tenancy.events`; topics remain shared across tenants and
  events always carry tenant context.

## References

- [Core provider-command and Kafka processes](../../domains/processes.md#provider-command-reliability)
- [Migration plan Kafka phase](../../migration-plan.md#phase-7-kafka-migration)
