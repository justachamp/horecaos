# ADR 0032: Event contract governance and topic policy

- Decision status: Accepted
- Implementation status: Built — governance is enforced at build time. `EventCatalog`
  and `EventContract` refuse a publish without a catalogue entry, and its eighteen
  contracts are matched one-for-one by eighteen JSON schemas under
  `src/main/resources/events/`: eight for `tenancy.events` — the five onboarding and
  lifecycle events (`TenantOnboardingStarted`, `TenantOnboardingStepCompleted`,
  `TenantOnboardingFailed`, `TenantActivated`, `TenantReady`) joined the original three
  today — six for `ordering.events`, two for `fulfillment.events`, one for
  `media.events`, and one for `realtime.signals`. They are held against
  frozen copies in `src/test/resources/events-baseline`
  by `EventSchemaCompatibilityTests`, with `EventCatalogCompletenessTests`,
  `EventSchemaValidationTests` and `EventPayloadClassificationTests` (which consumes ADR
  0029's `ClassificationScanner` rather than the interim name heuristic alone).
  `KafkaTopicCatalog` and `KafkaTopicConfiguration` declare and provision the six current
  topics with explicit partitions, replication factor, retention and `delete` cleanup;
  `KafkaTopicCatalogTests` ensures no published topic escapes that list, and
  `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` is set in `compose.production.yaml`.
  The Apicurio adoption trigger and migration path are documented below.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0004, ADR 0005, ADR 0029
- Supersedes / Superseded by: —
- Open inputs: none

## Context

Fourteen ADRs define events. Together they name well over a hundred event types
across tenancy, media, POS, payments, recovery, delivery, customers, catalog,
inventory, pricing, ordering, notifications, and commercial metering.

What none of them define is the governance around those events. Most name an
event without naming its topic. Nothing states how a topic is named, how many
partitions it has, how long it is retained, who may consume it, how a payload
schema is expressed, or how compatibility is checked before a producer ships a
change. ADR 0005 requires version-specific payload DTOs and rejects duplicate
event identifiers whose payload hash differs — which is a producer contract
violation it can detect but not prevent.

`docs/domains/events.md` documents the three implemented tenancy events well.
That format is the right one; it needs a rule for how the other hundred arrive
without diverging.

## Decision

### Topic naming and ownership

```text
{domain}.{kind}         tenancy.events, orders.events, payments.events,
                        fulfillment.events, catalog.events, inventory.events,
                        pricing.events, customers.events, media.events,
                        integration.events, commercial.events
{domain}.commands       notifications.commands, integration.commands
{domain}.failures       integration.failures
```

- Topics are domain-oriented and shared across tenants. A topic name never
  implies tenant scope, and consumers isolate using the signed envelope.
- Each topic has exactly one producing module, recorded in the event catalogue.
- Events are facts in the past tense; commands are instructions in the
  imperative and are addressed to one owner.
- Topics are provisioned as code with explicit partition count, replication
  factor, retention, and cleanup policy. Auto-creation is disabled in every
  environment above local.

### Envelope

The version-1 envelope in `docs/domains/events.md` is the contract for all
topics, not only tenancy. Required fields: `eventId`, `eventType`,
`eventVersion`, `tenantId`, `aggregateType`, `aggregateId`, `correlationId`,
`causationId`, `occurredAt`, `trace`, `payload`. Headers repeat `eventId`,
`eventType`, `eventVersion`, `tenantId`, and `correlationId`. The record key is
the aggregate identifier whose ordering must be preserved.

### Schema and compatibility

- Payload schemas are **JSON Schema documents versioned in the repository**
  under `src/main/resources/events/{topic}/{EventType}.v{n}.schema.json`, and
  they are the contract of record.
- Producers serialize from a version-specific immutable DTO and validate against
  the schema in tests. Consumers deserialize into their own version-specific DTO
  and never bind polymorphically on a type name from the payload.
- **CI enforces compatibility.** A schema change is compared against the
  previous release. Adding an optional field is allowed within a version.
  Removing a field, renaming, narrowing a type, changing semantics, or making a
  field required is a new `eventVersion`.
- **No schema registry service initially.** Compatibility is a build-time gate
  because every producer and consumer currently lives in one repository and one
  deployment. When an external consumer appears, Apicurio Registry is the
  chosen runtime registry, because it is Apache-2.0, supports JSON Schema, and
  can store schemas in PostgreSQL or Kafka. Confluent Schema Registry is
  excluded by its Community Licence, which restricts a company distributing a
  competing platform.
- An event version is retired only after every consumer has migrated and the
  retention window for records carrying it has passed.

### Apicurio adoption and migration

The repository schema files remain the contract of record until either (a) an
external consumer needs to retrieve a schema without this source tree, or (b) a
producer or consumer is deployed independently of the platform release. Either
condition is a release blocker for that integration until Apicurio is operating;
it is not a later scale optimization.

The migration is additive and reversible:

1. Deploy Apicurio with its PostgreSQL storage, access controls and backup in
   the same environment as Kafka. Create one artifact per `{topic}/{eventType}`
   with JSON Schema and `BACKWARD` compatibility.
2. Import every checked-in versioned schema. CI compares each imported artifact
   digest and version with `src/main/resources/events/`; a registry row never
   becomes an alternate hand-edited contract.
3. Add the registry artifact id and version as Kafka headers while producers
   continue emitting the unchanged JSON envelope. Existing consumers keep using
   the repository schemas; registry-aware consumers resolve and validate the
   same payload through Apicurio.
4. Move consumers one at a time, then require every new independently deployed
   consumer to pin an artifact version and pass the registry compatibility gate.
   The build-time schema check remains enabled as defence in depth.

Rollback removes registry-aware consumers and ignores the two optional headers;
the JSON envelope, outbox rows, topic names, repository schemas and existing
consumers are unchanged. No event version is reformatted, re-keyed or republished
during the migration.

### Payload rules

- Events carry identifiers, scopes, states, versions, and the minimum facts a
  consumer needs. They do not carry full aggregates.
- **No personal, sensitive, or financial data on any topic**, enforced by the
  structural check in ADR 0029: a build-time test asserts that no field
  classified `PERSONAL`, `PERSONAL_SENSITIVE`, or `FINANCIAL` is reachable from
  an event payload type. This is the single most repeated rule across the ADR
  set and it now has one enforcement point.
- A consumer needing protected detail calls an authorized API with the
  identifier from the event.
- Payload size is bounded; large evidence goes to object storage with a
  reference.

### Catalogue

`docs/domains/events.md` is the catalogue and every event must appear there
before its producer ships, recording: event type, version, producing module,
topic, partition key, payload summary, schema path, expected consumers,
retention, and PII classification. A test asserts that every registered producer
event type has a catalogue entry and a schema file.

### Retention

```text
business facts        long retention, sized for replay and reconciliation windows
commands              short retention; PostgreSQL is the durable work store
failures/diagnostics  short retention; ADR 0006 PostgreSQL rows are authoritative
```

Kafka retention is never the only copy of a business fact. The outbox, inbox,
and business tables are the evidence, per ADRs 0004 and 0006.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Avro with a runtime schema registry from the start | The strongest compatibility guarantees and the standard Kafka answer at scale. Rejected for now because it adds a registry service to operate and a code-generation step, while every producer and consumer is in one repository where a build-time gate achieves the same protection. JSON also keeps outbox rows, dead letters, and operator inspection human-readable, which matters a great deal for ADR 0006 | An external or independently deployed consumer appears, or payload volume makes JSON size a measured cost. Apicurio with JSON Schema first, Avro only if size demands it |
| Confluent Schema Registry | The de facto standard, and excluded on licensing: the Confluent Community Licence restricts building competing offerings, which is an unnecessary risk for a company distributing a commercial SaaS platform | Never while that licence applies |
| Protobuf | Compact and well-tooled, with the same registry and codegen cost as Avro plus worse human readability in the outbox and dead-letter paths | Same trigger as Avro |
| No schema documents; the DTO is the contract | Works only while producer and consumer share a codebase and compile together, which is exactly the assumption that breaks at the moment it matters most | Never |
| Tenant-per-topic or provider-per-topic | Unbounded topic growth, per-tenant partition overhead, and rebalancing pain, while the isolation benefit is already provided by the signed envelope and consumer-side authorization | Never |
| One firehose topic for all events | Destroys per-domain retention, authorization, and consumer scaling, and every consumer would filter almost everything away | Never |
| Allow Kafka topic auto-creation | Produces topics with default partitions and retention that nobody chose, discovered later during an incident | Never above local |

## Consequences

### Positive

- A producer cannot ship an incompatible payload change without CI failing.
- Every event has a documented topic, key, retention, and owner before it exists.
- The PII rule that appears in a dozen ADRs has one structural enforcement point
  instead of a dozen review conventions.

### Negative

- Schema files are another artifact to keep synchronized with DTOs, and a
  mismatch between them is a new class of build failure.
- JSON payloads are larger than Avro or Protobuf, which costs broker storage and
  network at volume.
- The catalogue requirement adds friction to introducing an event, which is
  intended and will occasionally feel bureaucratic.

### Accepted trade-offs

- Deferring a runtime registry means an external consumer cannot self-serve
  schemas today. That is acceptable while no external consumer exists, and the
  migration path to Apicurio is deliberate rather than accidental.
- Bounded payloads mean consumers make follow-up API calls for detail, trading
  network calls for a smaller and safer event surface.

## Testing

- Every producer event type has a catalogue entry and a schema file.
- Produced payloads validate against their schema.
- The compatibility gate fails on a removed or narrowed field within a version.
- No event payload type is reachable from a classified field, per ADR 0029.
- Envelope headers and body agree, as ADR 0005 requires.
- Topic provisioning code matches the catalogue's partition and retention values.

## Rollout and rollback

Apply to the three implemented tenancy events first, including schema files, a
catalogue check, and the compatibility gate, so the mechanism is proven on a
small surface. Every subsequent ADR adds its events under the same rules.
Rollback disables the gate only with a recorded decision; schemas and the
catalogue remain.

## Implementation checklist

- [x] Add schema files for `TenantCreated`, `BrandCreated`, and `LocationCreated`, and since then for the five onboarding and lifecycle events, the six `ordering.events`, two `fulfillment.events`, `MediaAssetReady`, and `RealtimeSignal` — eighteen schemas against eighteen catalogue entries.
- [x] Add the schema compatibility gate and the catalogue completeness test.
- [x] Add topic provisioning as code with explicit partitions, replication, retention and cleanup policy (`KafkaTopicCatalog`, `KafkaTopicConfiguration`).
- [x] Disable topic auto-creation above local environments.
- [x] Extend `docs/domains/events.md` with the required per-event fields.
- [x] Add the structural check over event payload types. No longer interim: `EventPayloadClassificationTests` consumes ADR 0029's `ClassificationScanner` rather than the name heuristic alone.
- [x] Document the Apicurio adoption trigger and migration path.

## Exit criteria

Every event Qoida publishes has a schema file, a catalogue entry naming its
topic, key, retention, and consumers, and a passing compatibility gate; no
classified field can reach a payload type; and no topic exists that was not
provisioned deliberately.

## References

- [Kafka event catalog](../../domains/events.md)
- [ADR 0005: Kafka inbox and idempotent consumer foundation](../built/0005-kafka-inbox-and-idempotent-consumers.md)
