# Qoida Platform Agent Guide

## Scope

These instructions apply to the entire `qoida-platform` repository.

Qoida Platform is the target SaaS platform that will incrementally replace the
legacy FastAPI application in `../milliy`. The legacy application is a source
of business behavior and migration data, not a template for the new design.

Read [README.md](README.md) and
[docs/migration-plan.md](docs/migration-plan.md) before making architectural,
schema, integration, or migration changes.

## Current status

No counts or gap lists here — two revisions of this section drifted far behind
the code while sounding authoritative. Current status lives where tooling or
adversarial review maintains it: the generated summary in
[docs/adr/README.md](docs/adr/README.md), each record's own status line
(filed under [`built/`](docs/adr/built/), [`partial/`](docs/adr/partial/),
[`not-started/`](docs/adr/not-started/) — verified against code, not
aspirational), and the founding review at `../docs/qoida-review.md`. Read the
status line of the record you are about to touch.

`Partial` is the normal state here and is not an invitation to finish the record
you happen to be in. What actually blocks a pilot is whichever
[minimum-viable-cutover](docs/minimum-viable-cutover.md) stage is unfinished —
work from that document's order, not from the ADR list's breadth.

Both statuses are defined in
[ADR 0000](docs/adr/meta/0000-adr-process-and-status-model.md), whose
vocabulary is one revision behind the index — see
[docs/adr/README.md](docs/adr/README.md).

## Non-negotiable architecture decisions

- Build a Java modular monolith first. Preserve boundaries that allow a module
  to become a service later, but do not introduce microservices without an
  evidence-backed operational or ownership reason.
- Prefer Spring Boot and Spring Modulith conventions for module boundaries,
  transactions, module tests, and event publication.
- Use Keycloak as the identity and access-management platform for OpenID
  Connect, OAuth 2.0, SSO, MFA, organization membership, and coarse-grained
  application roles. Do not implement a second password or token issuer in the
  application.
- Use Apache Camel as the ESB/integration runtime for protocol mediation,
  provider connectivity, transformation, routing, and Enterprise Integration
  Patterns. Do not place core business decisions or aggregate state in Camel
  routes.
- Replace the legacy frontends incrementally with tenant-aware applications
  that use Keycloak and generated API contracts. Do not preserve duplicate
  Angular/React applications merely for implementation convenience.
- Organize code by business capability, not by API audience. Customer, vendor,
  courier, and operations APIs are adapters over shared application use cases.
- Apply domain-driven and hexagonal design. Domain code must not depend on
  Spring MVC, Kafka, S3, provider SDKs, or persistence implementations.
- Use PostgreSQL as the system of record. Start with one database and logical
  schemas owned by modules.
- Use Flyway as the schema authority and Spring JDBC with explicit SQL for
  persistence adapters. Introducing an ORM requires a later evidence-backed
  architecture decision rather than an incidental dependency.
- Use Kafka for durable domain events and asynchronous integration. Never use
  Kafka as a substitute for a database transaction or ordinary synchronous
  method call.
- Use a transactional outbox for publishing and an inbox/idempotency mechanism
  for consumers and provider webhooks.
- Store media in S3-compatible object storage. Business tables reference media
  asset IDs or immutable object keys; they do not store environment-specific
  public URLs.
- Migrate incrementally. Never perform a big-bang rewrite or allow the Python
  and Java applications to write the same domain records without an explicit,
  reviewed ownership and synchronization design.

### Cross-cutting decisions that apply to every change

These were previously implied by many ADRs and are now decided. Do not
re-implement any of them locally.

- **Authorization** is capability-based per [ADR 0025](docs/adr/built/0025-fine-grained-authorization-and-capability-model.md).
  Every mutating endpoint declares a capability. Organization membership alone
  no longer authorizes reads of all tenant data.
- **Provider accounts** are installations and bindings per [ADR 0026](docs/adr/built/0026-provider-installations-bindings-and-secret-references.md).
  `provider_binding_id` always means that row. No module keeps a private
  provider credential, mapping, or configuration table.
- **Audit** is an append-only fact written in the same transaction as the change
  it describes, per [ADR 0027](docs/adr/partial/0027-audit-evidence-and-approval-model.md).
  Maker-checker uses the one shared approval model.
- **Secrets** live only in the ADR 0028 manager. The database stores a reference.
- **Personal data** is classified and encrypted per [ADR 0029](docs/adr/partial/0029-pii-protection-envelope-encryption-and-key-rotation.md),
  and never appears in an event, log, trace, metric, or dead-letter summary.
- **Scoped behavior** resolves through [ADR 0030](docs/adr/partial/0030-configuration-and-policy-resolution.md).
  A durable decision persists the policy id and version it used.
- **HTTP** follows [ADR 0031](docs/adr/built/0031-http-api-conventions.md): Problem
  Details with stable codes, `Idempotency-Key` on effectful mutations, expected
  version on aggregate mutations, cursor pagination, money as an object.
- **Events** follow [ADR 0032](docs/adr/built/0032-event-contract-governance-and-topic-policy.md):
  a schema file and a catalogue entry exist before a producer ships.
- **Caches** are registered accelerators only. No correctness decision reads
  cache state, per [ADR 0033](docs/adr/partial/0033-caching-rate-limiting-and-shared-runtime-state.md).

## SaaS tenancy model

The hierarchy is:

```text
Tenant -> Brand -> Location
```

- A tenant is the legal/commercial SaaS customer and primary isolation,
  subscription, billing, and administration boundary.
- A tenant may operate multiple customer-facing brands.
- A brand may operate multiple physical or virtual locations.
- The legacy `Company` concept generally maps to a brand.
- The legacy `Vendor` concept generally maps to a location.
- Grouping legacy companies into tenants requires an approved migration map;
  it must not be guessed from names or slugs.

Every tenant-owned record must carry a non-null `tenant_id`. Prefer composite
foreign keys and unique constraints that include `tenant_id` so cross-tenant
relationships are rejected by the database. Tenant context must be derived
from authenticated identity or a verified domain, never trusted from an
arbitrary request header.

All of the following must be tenant-aware:

- Database queries and constraints
- Cache keys
- Kafka event envelopes
- S3 object keys
- Logs, traces, metrics, and audit events
- Background jobs, imports, and exports

Use PostgreSQL row-level security as defense in depth where practical. It does
not replace authorization in application use cases.

## Domain boundaries

Use these initial modules:

- `iam`: Keycloak identity links, application principals, organization/tenant
  mappings, authorization projections, and scoped access enforcement
- `tenancy`: tenants, brands, locations, domains, onboarding, and configuration
  inheritance
- `customers`: tenant customer accounts, brand profiles, addresses, consent,
  devices, and customer identity-policy enforcement
- `catalog`: catalogs, categories, products, variants, and location offerings
- `inventory`: balances, reservations, movements, and availability
- `pricing`: prices, promotions, coupons, adjustments, and redemption
- `ordering`: carts, orders, immutable order snapshots, transitions, and notes
- `payments`: payment intents, attempts, refunds, merchant accounts, provider
  webhooks, reconciliation, and tax receipts
- `fulfillment`: shipments, internal couriers, external delivery partners,
  assignments, tracking, and delivery zones
- `media`: media assets, upload lifecycle, derivatives, access policy, and
  retention
- `notifications`: preferences, templates, notifications, and delivery attempts
- `integration`: Apache Camel routes, provider installations, inbox messages,
  outbox events, protocol mediation, and external synchronization
- `reporting`: read models and operational reporting
- `audit`: immutable security and business audit records

ADRs 0013 and 0021 propose dedicated `recovery` and `commercial` modules for
service-recovery decisions/remedies and SaaS plans/subscriptions/usage. Add
their schemas and module declarations only when those ADRs move to `In
Progress`; do not hide either capability inside an unrelated aggregate in the
meantime.

Modules expose application APIs and domain events. Do not import another
module's internal entities or repositories.

## Java and OOP rules

- Favor composition, small interfaces, value objects, and explicit application
  services over deep inheritance hierarchies.
- Model provider integrations as ports and adapters. The core must not contain
  provider checks such as `if provider == CLICK`.
- Apply interface segregation. A provider implements only the capabilities it
  supports, such as initiation, capture, refund, cancellation, quoting,
  shipment creation, or tracking.
- Keep provider DTOs and SDK types inside the provider adapter module. Map them
  to canonical domain commands and results at the boundary.
- Provider credentials belong in a secrets manager. Store only a secret
  reference and non-sensitive configuration in PostgreSQL.
- Use explicit domain state-transition methods. Do not expose public setters
  that permit arbitrary changes to order, payment, shipment, or onboarding
  state.
- Use optimistic locking for aggregates that can be changed concurrently.

## Keycloak identity and authorization rules

- Use standards-based OpenID Connect and OAuth 2.0 integrations. Frontends use
  Authorization Code with PKCE; APIs operate as resource servers and validate
  issuer, audience, signature, expiry, and required claims.
- Keep browser and mobile clients public. Never embed a client secret in a
  frontend bundle or mobile application.
- The working default is one platform realm per environment with Keycloak
  Organizations used for B2B tenant membership. A separate customer realm may
  be used when workforce and customer identity lifecycles require isolation.
  Final realm topology requires an architecture decision record.
- Map one Keycloak organization to one Qoida tenant. Keep the immutable
  Keycloak organization ID on the tenant record; do not join by display name.
- Keycloak owns credentials, authentication flows, MFA, external identity
  brokering, sessions, organization membership, and coarse roles. Qoida owns
  tenant, brand, location, plan, entitlement, and resource relationships.
- Java application services must still enforce tenant, brand, location, and
  resource authorization. Authentication success alone never authorizes a
  domain operation.
- Avoid placing unbounded brand/location membership lists in access tokens.
  Use stable organization and role claims plus a tenant-aware authorization
  projection/cache when fine-grained scope is too large or too dynamic.
- Treat Keycloak Admin API operations as idempotent onboarding steps. Store
  external IDs and reconcile them; do not assume a create request ran once.
- Use separate Keycloak clients for each frontend, the Java resource server,
  Camel service accounts, and other machine clients.
- Never migrate legacy bearer or refresh tokens. Link identities explicitly
  and require a new Keycloak session after cutover.
- Phone/OTP customer login requires a reviewed Keycloak authenticator,
  brokered identity provider, or supported authentication flow. Do not embed
  OTP business logic in a frontend.

## Apache Camel ESB rules

- Camel is an integration layer around domain ports, Kafka, HTTP, files, and
  provider protocols. Domain modules must not import Camel APIs.
- Organize routes by bounded integration context and owner. Avoid one global
  route package or a single unversioned canonical message shared by every
  domain.
- Use canonical domain commands/results at the domain boundary and
  provider-specific messages only inside adapters.
- Prefer Kafka for durable asynchronous domain communication. Camel may
  consume and produce Kafka records, but it does not replace the transactional
  outbox or Kafka's durability model.
- Use Camel for routing, transformation, protocol conversion, throttling,
  retries, circuit breaking, idempotent consumption, and dead-letter handling.
- Keep order, payment, shipment, inventory, and onboarding state transitions
  in application/domain services, never in route DSL conditions.
- Give every route an owner, contract version, input/output schema, timeout,
  retry policy, idempotency strategy, dead-letter destination, trace context,
  and health indicator.
- Route credentials come from the secrets manager. Camel service-to-service
  calls use dedicated Keycloak service accounts with least-privilege scopes.
- Package and scale integration routes independently when their reliability or
  throughput differs, even if they remain in the same repository.

## Frontend rules

- Treat frontend replacement as product migration, not a visual reskin.
  Inventory and preserve approved user journeys while removing duplicated and
  obsolete implementations.
- Define separate application surfaces for platform administration, tenant
  administration/onboarding, brand/location operations, customer storefront,
  and courier workflows. Combine surfaces only through an explicit product
  decision.
- Select the target web/mobile framework through an architecture decision.
  Share a design system, localization, telemetry, Keycloak integration, and
  generated API clients where the platforms allow it.
- Resolve tenant and brand theme/configuration at runtime. Do not create a
  separate source fork for every tenant or brand.
- Generate typed clients from versioned OpenAPI contracts. Do not hand-copy API
  DTOs across applications.
- Use strict redirect URI and web-origin configuration. A tenant custom domain
  must be verified before it is registered with Keycloak or the gateway.
- Keep tokens in memory where the platform permits; never persist access or
  refresh tokens in local storage.
- Implement loading, empty, error, expired-session, forbidden, degraded, and
  retry states for every migrated user journey.
- Migrate by vertical feature slice behind controlled routing or feature flags.
  Do not switch all frontends in one release.
- Add accessibility, responsive behavior, localization, analytics, error
  monitoring, and end-to-end tests to the definition of done.

## Data modeling rules

- Model business facts before writing domain records, explicit SQL, or Flyway
  migrations.
- An order is an immutable commercial record. Store customer, address, item,
  tax, price, discount, and currency snapshots needed to render it historically.
- Payment, kitchen preparation, and fulfillment have distinct lifecycles. Do
  not flatten their states into the order table.
- A payment intent may have multiple attempts and refunds.
- An order may have multiple shipments and shipment assignments.
- Inventory has one source of truth. Use balances, reservations, and an
  adjustment/movement ledger instead of duplicated stock columns.
- Store money as integer minor units with an ISO currency code. Never use
  floating-point money.
- Store instants as UTC `timestamptz`; store a location's IANA timezone
  separately. Do not persist formatted timestamps or naive local datetimes.
- Use stable public aggregate IDs consistently. Preserve legacy IDs in explicit
  `legacy_id` columns or mapping tables during migration.
- Use JSONB only for raw provider payloads, event payloads, and genuinely
  flexible metadata. Do not hide searchable core business state in JSONB.
- Use PostGIS types for points, zones, and geographic queries when the
  fulfillment design is implemented.
- Add database constraints for invariants, including tenant scope, positive
  quantities, uniqueness, valid ranges, and mutually exclusive fields.

## Kafka and event rules

- Name topics by business domain, not by tenant or provider. Initial topic
  families are `orders.events`, `payments.events`, `fulfillment.events`, and
  `notifications.commands`.
- Include `eventId`, `eventType`, `eventVersion`, `tenantId`, `aggregateId`,
  `correlationId`, `occurredAt`, trace metadata, and payload in every external
  event envelope.
- Partition by the aggregate whose order must be preserved, such as `orderId`,
  `paymentId`, or `shipmentId`.
- Treat delivery as at-least-once. Every consumer must be idempotent.
- Version event contracts additively. Do not silently change the meaning of a
  published field.
- Define retry and dead-letter behavior per consumer. Never retry invalid data
  indefinitely.
- Do not publish to Kafka directly inside a database business transaction.
  Persist an outbox event in the same transaction and relay it afterward.

## S3 and media rules

- Keep buckets private and use a CDN with origin access controls for public
  assets. Use short-lived signed URLs for private assets.
- Use immutable, generated object keys, for example:
  `tenants/{tenantId}/products/{productId}/{assetId}/original.jpg`.
- Never use an untrusted original filename as an object key.
- Record content type, byte size, checksum, visibility, status, and ownership
  in `media_asset`.
- Validate upload size, type, checksum, and authorization before marking an
  asset available.
- Generate thumbnails and optimized formats asynchronously.
- Use delayed garbage collection for deleted or replaced objects. Do not delete
  an object immediately when a database reference changes.
- During migration, verify source and destination checksums before switching a
  reference. Preserve the legacy filesystem through the rollback window.

## Migration rules

- Read [docs/migration-coverage.md](docs/migration-coverage.md) alongside the
  migration plan. Every legacy table, field/value family, API operation,
  frontend route, job, subscriber, provider callback, report, and manual
  workflow must be migrated, transformed, archived, or explicitly retired.
- An unresolved `DECIDE` disposition blocks cutover only for its affected
  capability/scope, but it may never be silently treated as deletion.
- Use expand, migrate, verify, cut over, and contract phases.
- Make backfills restartable and idempotent. Record checkpoints and outcomes.
- Profile production data before finalizing transformations. The ORM model is
  not authoritative evidence of production data quality.
- Maintain an explicit legacy-to-target mapping matrix for every table and
  field.
- For each migrated capability, declare which system owns reads and writes.
- For each migrated frontend journey, declare the active UI, API version,
  Keycloak client, rollout cohort, telemetry, and rollback route.
- Prefer shadow reads and reconciliation reports before moving writes.
- Define measurable entry, exit, and rollback criteria for every cutover.
- Never delete legacy data or media as part of the initial cutover.
- Payments and ordering migrate late, after provider idempotency, reconciliation,
  state machines, and regression coverage are proven.
- Keycloak identity linking precedes frontend cutover. Legacy sessions are not
  silently converted into Keycloak sessions.
- Transfer scheduled-job, webhook, DNS/gateway, provider-account, and credential
  ownership explicitly. A data writer cutover is incomplete while a legacy
  timer or callback can still produce the same external side effect.

## Testing requirements

- Write unit tests for value objects, policies, calculations, and state
  transitions.
- Write module integration tests for application use cases and boundaries.
- Use Testcontainers for PostgreSQL, Kafka, and compatible infrastructure.
- Maintain contract tests for each payment and delivery adapter.
- Test Camel routes for transformation, timeouts, redelivery, idempotency,
  circuit breaking, and dead-letter behavior.
- Test Keycloak login, logout, token refresh, organization selection, MFA,
  service accounts, role mapping, and tenant-scoped denial cases.
- Maintain end-to-end tests for each new frontend's critical journeys across
  supported viewport classes and browsers.
- Test webhook signature validation, deduplication, retries, and out-of-order
  events.
- Test tenant isolation with explicit negative cross-tenant cases.
- Test migrations with anonymized production-shaped fixtures and reconciliation
  assertions.
- Critical flows include authentication, catalog availability, price
  calculation, checkout, payment callbacks, refunds, shipment assignment,
  cancellation, and tenant activation.

## Security and observability

- Never copy credentials, tokens, fixed OTPs, or secrets from the legacy source.
- Never log access tokens, refresh tokens, payment credentials, OTPs, or raw
  sensitive provider payloads.
- Apply least-privilege IAM and secrets access.
- Produce structured logs with tenant, correlation, aggregate, and request IDs.
- Add traces across HTTP, database, Kafka, and provider calls.
- Expose liveness separately from dependency-aware readiness.
- Audit tenant administration, access changes, payment operations, refunds,
  catalog publication, and migration actions.

## Documentation and decision discipline

- Update architecture and migration documents when behavior or ownership
  changes.
- Record consequential choices as architecture decision records before
  implementation, using [docs/adr/TEMPLATE.md](docs/adr/TEMPLATE.md).
- Every new ADR must carry both status fields, an `Open inputs` list, an
  `## Alternatives considered` table with revisit triggers, and a
  `## Consequences` section that includes negative consequences. An ADR
  presenting one option as if it were the only one is incomplete.
- Never edit an `Accepted` decision in place to change it. Write a new ADR and
  set `Superseded by` on the old one.
- Clearly label assumptions that still require product confirmation, and name
  the owner in `Open inputs`.
- Do not introduce infrastructure or patterns only for hypothetical scale.
- A change is complete only when its tests, migration impact, observability,
  tenant isolation, rollback behavior, and documentation are addressed.
