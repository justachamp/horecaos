# Kafka event catalog

This catalog defines the external domain-event contracts currently implemented
by HorecaOS Platform. Topic names are domain-oriented and shared across tenants.
Consumers must authorize and isolate using the signed envelope facts; a topic
name never implies tenant scope.

## Envelope version 1

```json
{
  "eventId": "018f6f4e-899d-7b1c-a8cf-0242ac120401",
  "eventType": "TenantCreated",
  "eventVersion": 1,
  "tenantId": "018f6f4e-899d-7b1c-a8cf-0242ac120402",
  "aggregateType": "Tenant",
  "aggregateId": "018f6f4e-899d-7b1c-a8cf-0242ac120402",
  "correlationId": "request-42",
  "causationId": null,
  "occurredAt": "2026-08-19T01:00:00Z",
  "trace": {},
  "payload": {}
}
```

Kafka records repeat `eventId`, `eventType`, `eventVersion`, `tenantId`, and
`correlationId` in `horecaos-*` headers. The record key is the aggregate ID.
Payloads evolve additively within an event version; incompatible meaning or
shape requires a new event version.

## Topic provisioning

`KafkaTopicConfiguration` provisions these topics through Spring Kafka's
`KafkaAdmin`; this table is the operator-facing mirror of its code-owned
`KafkaTopicCatalog`. The first production topology has one broker, so the
explicit replication factor is `1`, not an implicit durable-storage claim. An
existing topic is never mutated on application start: increasing partitions or
changing retention is an approved operational migration with a rollback plan.

| Topic | Partitions | Replication factor | Retention | Cleanup policy |
|---|---:|---:|---|---|
| `tenancy.events` | 3 | 1 | `PT168H` | `delete` |
| `ordering.events` | 12 | 1 | `PT168H` | `delete` |
| `media.events` | 6 | 1 | `PT168H` | `delete` |
| `fulfillment.commands` | 3 | 1 | `PT24H` | `delete` |
| `fulfillment.events` | 6 | 1 | `PT168H` | `delete` |
| `realtime.signals` | 3 | 1 | `PT1M` | `delete` |
| `voice.events` | 3 | 1 | `PT168H` | `delete` |

Business-fact retention is the seven-day operational replay window. Commands
are durable in PostgreSQL and need only outlive a consumer restart. Realtime
signals are live hints with no replay semantics, so retaining one longer than a
minute would deliver stale screen-refresh instructions after a reconnect.

## `tenancy.events`

- Producing module: `tenancy`
- Retention class: business fact
- Classification: `INTERNAL` — no personal data on this topic

| Event | Version | Key | Schema | Version-1 payload |
|---|---|---|---|---|
| `TenantCreated` | 1 | `tenantId` | [`TenantCreated.v1`](../../src/main/resources/events/tenancy.events/TenantCreated.v1.schema.json) | `tenantId`, slug, legal/display names, default currency/timezone, status, customer identity mode |
| `BrandCreated` | 1 | `brandId` | [`BrandCreated.v1`](../../src/main/resources/events/tenancy.events/BrandCreated.v1.schema.json) | `brandId`, code, slug, display name, status |
| `LocationCreated` | 1 | `locationId` | [`LocationCreated.v1`](../../src/main/resources/events/tenancy.events/LocationCreated.v1.schema.json) | `locationId`, `brandId`, code, slug, display name, timezone, status |
| `TenantOnboardingStarted` | 1 | `tenantId` | [`TenantOnboardingStarted.v1`](../../src/main/resources/events/tenancy.events/TenantOnboardingStarted.v1.schema.json) | `tenantId`, `runId`, `templateId`, template version |
| `TenantOnboardingStepCompleted` | 1 | `tenantId` | [`TenantOnboardingStepCompleted.v1`](../../src/main/resources/events/tenancy.events/TenantOnboardingStepCompleted.v1.schema.json) | `tenantId`, `runId`, step key, step version, attempt count |
| `TenantOnboardingFailed` | 1 | `tenantId` | [`TenantOnboardingFailed.v1`](../../src/main/resources/events/tenancy.events/TenantOnboardingFailed.v1.schema.json) | `tenantId`, `runId`, step key, error code |
| `TenantReady` | 1 | `tenantId` | [`TenantReady.v1`](../../src/main/resources/events/tenancy.events/TenantReady.v1.schema.json) | `tenantId`, `runId` |
| `TenantActivated` | 1 | `tenantId` | [`TenantActivated.v1`](../../src/main/resources/events/tenancy.events/TenantActivated.v1.schema.json) | `tenantId`, `runId`, status |

`LocationCreated` deliberately omits the location address. Events carry
identifiers; a consumer needing more calls an authorized API.

These events are business facts emitted only after the corresponding creation
use case succeeds. Keycloak organization linking is intentionally not part of
`TenantCreated`; ADR 0009 makes it an idempotent onboarding step, and its
completion is reported by `TenantOnboardingStepCompleted` rather than by a fact
of its own.

The five ADR 0008 onboarding facts key on `tenantId`, not on `runId`, because
onboarding order is tenant-scoped: keying on the run would let a consumer see
`TenantActivated` before the `TenantOnboardingStarted` that produced it.
`TenantOnboardingFailed` carries the error *code* and not the detail — a step's
detail is whatever the failing system said and may name a person — and
`TenantOnboardingStepCompleted` names the step without carrying its result, which
holds organization and subject identifiers and applied configuration.

## `ordering.events`

- Producing module: `ordering`
- Retention class: business fact
- Classification: `INTERNAL` — no personal data on this topic
- Record key: the order ID, so every fact about one order stays in order. A key
  of the location or the tenant would let a confirmation overtake the received
  event for the same order.

| Event | Version | Key | Schema | Version-1 payload |
|---|---|---|---|---|
| `OrderReceived` | 1 | `orderId` | [`OrderReceived.v1`](../../src/main/resources/events/ordering.events/OrderReceived.v1.schema.json) | `orderId`, brand/location, channel code, public number, fulfilment mode, acceptance mode and policy reference, status, version, currency, total, line count |
| `OrderAwaitingApproval` | 1 | `orderId` | [`OrderAwaitingApproval.v1`](../../src/main/resources/events/ordering.events/OrderAwaitingApproval.v1.schema.json) | `orderId`, brand/location, approval channel, deadline, timeout action, status, version |
| `OrderConfirmed` | 1 | `orderId` | [`OrderConfirmed.v1`](../../src/main/resources/events/ordering.events/OrderConfirmed.v1.schema.json) | `orderId`, brand/location, acceptance mode, decision channel, confirmed-at, currency, total, status, version |
| `OrderRejected` | 1 | `orderId` | [`OrderRejected.v1`](../../src/main/resources/events/ordering.events/OrderRejected.v1.schema.json) | `orderId`, brand/location, decision channel, reason code, status, version |
| `OrderExpired` | 1 | `orderId` | [`OrderExpired.v1`](../../src/main/resources/events/ordering.events/OrderExpired.v1.schema.json) | `orderId`, brand/location, approval deadline, status, version |
| `OrderCancelled` | 1 | `orderId` | [`OrderCancelled.v1`](../../src/main/resources/events/ordering.events/OrderCancelled.v1.schema.json) | `orderId`, brand/location, cancelling actor type, reason code, previous status, status, version |

These payloads deliberately omit the order lines, the customer, the address, the
contact details, and every customer note. ADR 0019 says events carry order and
scope identifiers, state, version, policy references, timestamps and the minimum
required totals; a consumer that needs to know *what* was ordered calls the
authorized order API with the order ID.

`reasonCode` is a stable code and never operator free text. A typed rejection
reason would be personal data on a topic and would be untranslatable in the
customer's application.

`OrderExpired` and `OrderRejected` are separate events rather than one event with
a discriminator. "The restaurant declined" and "the restaurant never looked" lead
to different customer wording, different operational follow-up, and different
quality metrics for the branch, and a single event hides the second inside the
first.

`OrderCancelled` never follows `OrderConfirmed` in this release: cancellation
after confirmation is ADR 0039's and the application refuses it.

`PREPARING`, `READY`, `FULFILLING`, and `COMPLETED` transitions are recorded in
`ordering.order_state_history` and have no event yet. They will get one with
ADR 0014 delivery and ADR 0020 notifications, rather than being published now to
a catalogue with no consumer.

## `media.events`

- Producing module: `media`
- Retention class: business fact
- Classification: `INTERNAL` — no personal data on this topic

| Event | Version | Key | Schema | Version-1 payload |
|---|---|---|---|---|
| `MediaAssetAvailable` | 1 | `assetId` | [`MediaAssetAvailable.v1`](../../src/main/resources/events/media.events/MediaAssetAvailable.v1.schema.json) | `assetId`, owner scope/id, visibility, verified content type, size, `widthPx`, `heightPx` |

The payload deliberately carries **no object key, no signed URL, and no original
filename**. A topic is read by consumers with no authorization to the bytes, so a
key on it is a read capability handed to all of them, and the filename is text a
customer typed — ADR 0029 keeps it out. The verified content type and dimensions
are the store's and the image header's own answers, never the client's claim, so
a consumer can reserve a layout box without fetching anything.

`MediaAssetAvailable` announces a fact; it does not commission the derivative
render. That work is owed by a `media.derivative_jobs` row written in the same
transaction as the availability transition, because rendering is the media
module's own obligation and must survive a broker being down. See ADR 0010 and
`MediaDerivativeWorker` for why the render cannot be an inbox handler.

ADR 0010 names five further media facts — `MediaUploadRequested`,
`MediaAssetUploaded`, `MediaAssetRejected`, `MediaAssetDeletionRequested`,
`MediaAssetDeleted`. None is published yet, and none is catalogued here: a
contract with no producer is a promise this repository has not made.

## `realtime.signals`

- Producing module: `telemetry`
- Retention class: **signal** — seconds, no replay, no business meaning
- Classification: `INTERNAL` — identifiers and a scope key, never state
- Key: the scope key, so one branch's signals land on one partition in order

| Event | Version | Key | Schema | Version-1 payload |
|---|---|---|---|---|
| `RealtimeSignal` | 1 | `scope` | [`RealtimeSignal.v1`](../../src/main/resources/events/realtime.signals/RealtimeSignal.v1.schema.json) | `signalId`, `tenantId`, `channel`, `scope`, `resourceType`, `resourceId`, `version`, `occurredAt` |

[ADR 0045](../adr/partial/0045-realtime-operational-push-and-field-telemetry.md) adds this
as a fourth topic class beside business facts, commands, and diagnostics, and
`realtime.signals` is its only member. Everything about it is the opposite of the
other three, deliberately:

- **Consumed with `assign()` and seek-to-end, with no consumer group.** A group
  would give each record to one replica, and every replica needs every record
  because each holds different browser connections. Committed offsets would make
  a restarting replica replay stale hints into a live map.
- **Never written through the ADR 0004 outbox.** A signal is not a fact anybody
  reconciles against; giving it the outbox's durability would cost the outbox's
  budget on the one machine ADR 0034 provides, to guarantee delivery of a hint
  that heals by itself at the next resync.
- **A frame carries a signal, not state.** The client is told that something in
  its subscribed scope changed and re-reads it through the ordinary authorized
  API. That is what stops the stream leaking what the API would not return: a
  stream opened while a principal held a location grant cannot keep emitting
  order contents after the grant is revoked, because it never emitted them.

The two channels that carry a payload inline — `COUNTERS` and
`COURIER_POSITIONS` — do so in the **HTTP response**, not on this topic. That
distinction is what lets a courier snapshot carry a coordinate at all: the frame
is an authorized ADR 0025 response to a subscriber holding
`courier.position.read` at that location, while the record here carries a courier
id, a scope key, and a time.

## `fulfillment.commands`

- Producing module: `integration`
- Retention class: **command** — short lived, because PostgreSQL and not Kafka is
  the durable store of work still owed (ADR 0004)
- Classification: `INTERNAL` — identifiers and a classification code, never a
  contact, an address, or a provider body
- Key: `operationCommandId`, so two reconciliations for one courier call stay in
  order on one partition

| Event | Version | Key | Schema | Version-1 payload |
|---|---|---|---|---|
| `ShipmentReconciliationRequested` | 1 | `operationCommandId` | [`ShipmentReconciliationRequested.v1`](../../src/main/resources/events/fulfillment.commands/ShipmentReconciliationRequested.v1.schema.json) | `operationCommandId`, `bindingId`, brand/location, provider type, capability, `externalReference`, uncertain error code |

This is the first **command** topic, and the first production instance of
[ADR 0007](../adr/partial/0007-camel-route-foundation-and-provider-contract-testing.md)'s
inbox → route → outbox shape: the record is consumed through the ADR 0005 inbox,
the ADR 0007 delivery route performs a status query against the courier partner,
and the answer is written to the ADR 0004 outbox in the same transaction as the
inbox `PROCESSED` transition. Nothing is returned to a caller, because by the
time this runs there is no caller left.

Two rules follow from it being a command rather than a fact.

- **The record is a request, not an authority.** Its `bindingId` names a courier
  account, and the tenant on the envelope is producer-controlled. The consumer
  re-resolves the candidate bindings for the envelope's tenant, brand and
  location and refuses a `bindingId` that is not among them, so a command
  claiming tenant B cannot reach tenant A's partner account.
- **Replaying it is not free.** A status query has no side effect, so a
  duplicate is harmless — but the inbox deduplicates on `eventId` anyway, and the
  original courier call is never repeated by this path. Reconciliation and retry
  are different operations, and collapsing them is how a second courier gets
  booked.

## `fulfillment.events`

- Producing module: `integration`
- Retention class: **business fact** — retained for replay and reconciliation
- Classification: `INTERNAL`
- Key: `operationCommandId`

| Event | Version | Key | Schema | Version-1 payload |
|---|---|---|---|---|
| `ShipmentOutcomeReconciled` | 1 | `operationCommandId` | [`ShipmentOutcomeReconciled.v1`](../../src/main/resources/events/fulfillment.events/ShipmentOutcomeReconciled.v1.schema.json) | `operationCommandId`, `bindingId`, provider type, capability, `externalReference`, resolution, provider status, error code, attempts, `reconciledAt` |

`resolution` carries the only three answers this path can honestly give.
`CONFIRMED` means the partner holds the shipment, so the uncertain call did take
effect. `ABSENT` means the partner has no record of it, so it did not, and
re-issuing the original command is safe. `UNRESOLVED` means the reconciliation
budget is spent and nobody established which — it is emitted so that an
unanswerable case is a fact somebody can act on rather than a log line, and it
must never be read as `ABSENT`.

## `voice.events`

- Producing module: `voice`
- Retention class: **business fact** — retained for replay and reconciliation
- Classification: `INTERNAL` — no caller number, encrypted or not, ever appears here
- Key: `callCorrelationId`

| Event | Version | Key | Schema | Version-1 payload |
|---|---|---|---|---|
| `VoiceCallEventRecorded` | 1 | `callCorrelationId` | [`VoiceCallEventRecorded.v1`](../../src/main/resources/events/voice.events/VoiceCallEventRecorded.v1.schema.json) | `callEventId`, `installationId`, `providerCallId`, `brandId`, `locationId`, `callEventType` (OFFERED/ANSWERED/ENDED/MISSED/TRANSFERRED), `direction`, `lineDid`, `resolvedCustomerAccountId`, `operatorPrincipalId`, `durationSeconds` |

One event type covers all five ADR 0064 vocabulary words, discriminated by
`callEventType`, rather than five near-identical schemas ahead of any consumer
needing the difference — see the schema's own description and `VoiceEvent`'s
Javadoc for the reasoning; splitting them out remains additive. `callCorrelationId`
(the topic's partition key, computed deterministically from `tenantId`,
`installationId`, and `providerCallId` — see `VoiceEvent.callCorrelationId()`)
is not itself a payload field: every event in one call's lifecycle keys the
same way so a call's own events cannot overtake each other, even though each
is a distinct `callEventId` row in `voice.call_events`.

## Delivery and ordering guarantees

- Business state and the outbox event commit in one PostgreSQL transaction.
- Kafka delivery is at-least-once; `eventId` is the consumer deduplication key.
- The relay claims the oldest unresolved event per topic and partition key.
- A retrying or dead-lettered event blocks later events for that key, preventing
  aggregate reordering.
- A lease token prevents a stale relay worker from marking a reclaimed event as
  complete.
- Retry exhaustion preserves the event and sanitized error as `DEAD_LETTER` in
  PostgreSQL. Replay/skip requires an explicit future operations use case.

Consumer inbox contracts will be added before the first durable business
consumer is enabled, per [ADR 0005](../adr/built/0005-kafka-inbox-and-idempotent-consumers.md).

## Catalogue rules

[ADR 0032](../adr/built/0032-event-contract-governance-and-topic-policy.md) governs
this catalogue, and the rules below are enforced by tests rather than by review.

Every event needs three things in the same commit, or the build fails:

1. an entry in `EventCatalog` (`uz.horecaos.platform.integration.events`);
2. a JSON Schema at `src/main/resources/events/{topic}/{EventType}.v{n}.schema.json`;
3. a row in this document.

Enforcement:

| Rule | Test |
|---|---|
| Every publishable event is registered, has a schema, and is documented here | `EventCatalogCompletenessTests` |
| A producer's serialized payload satisfies its schema | `EventSchemaValidationTests` |
| A schema stays backward compatible within its `eventVersion` | `EventSchemaCompatibilityTests` |
| No protected value is reachable from a payload type | `EventPayloadClassificationTests` |

`EventCatalog.require` also runs at publication time, so an unregistered event
cannot reach the outbox even if a test were skipped.

Removing a property, narrowing its type, removing an enum value, or adding a
required property is a new `eventVersion`. The frozen baseline under
`src/test/resources/events-baseline` is the last released shape and is updated
deliberately when a new version is introduced, never to silence the gate.
