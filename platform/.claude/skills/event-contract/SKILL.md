---
name: event-contract
description: Use whenever producing or consuming a Kafka event, adding a topic, changing an event payload, writing an outbox or inbox path, or handling a provider webhook in Qoida Platform. Encodes ADR 0032, ADR 0004, and ADR 0005.
---

# Event contracts

Kafka carries durable domain events and asynchronous integration. It is never a
substitute for a database transaction or an ordinary synchronous method call.

## Before a producer ships

Per ADR 0032, both of these exist first — not in the same PR if that is easier, but
before the producer runs:

1. A **schema file** under `src/main/resources/events/`
2. A **catalogue entry**

## Publishing

**Never publish to Kafka inside a business transaction.** Write an outbox row in the same
transaction as the state change; the relay publishes it afterwards, at least once, with
bounded retry and dead-letter retention (ADR 0004).

Topics are named by **business domain** — `orders.events`, `payments.events`,
`fulfillment.events`, `notifications.commands` — never by tenant or provider.

Partition by the aggregate whose ordering must hold: `orderId`, `paymentId`, `shipmentId`.

## Envelope

Every external event carries `eventId`, `eventType`, `eventVersion`, `tenantId`,
`aggregateId`, `correlationId`, `occurredAt`, trace metadata, and payload.

**No personal data in a payload, ever** (ADR 0029). Carry an identifier the consumer can
resolve through an authorized call. This includes dead-letter summaries, which get read
by people who are not authorized to see the record.

## Consuming

Delivery is at-least-once, so **every consumer is idempotent** — inbox or dedup key, per
ADR 0005. Provider webhooks are the same: validate the signature, deduplicate, and handle
out-of-order arrival, because it happens.

Per-consumer retry and dead-letter behaviour is explicit. Invalid data is never retried
indefinitely; it goes to a dead letter with an operator decision path
(`docs/runbooks/dead-letter-decision.md`).

## Versioning

Additive only. Never silently change what a published field means — consumers you do not
control have already read it. A breaking change is a new `eventVersion`.

## Before saying it is done

- [ ] Schema file and catalogue entry exist
- [ ] Outbox used; no publish inside a transaction
- [ ] Partition key preserves the ordering that matters
- [ ] Envelope complete, `tenantId` present, no PII anywhere in it
- [ ] Consumer idempotency tested with a **duplicate and an out-of-order** delivery
- [ ] Retry, dead-letter, and replay paths tested
