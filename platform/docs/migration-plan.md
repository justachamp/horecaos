# HorecaOS Platform Migration Plan

## 1. Purpose

This plan describes the incremental migration from the legacy FastAPI platform
in `../milliy` to the Java-based HorecaOS SaaS platform.

It covers:

- SaaS tenancy and onboarding
- Target data model redesign
- Java modular-monolith adoption
- Full frontend application replacement
- Keycloak authentication and authorization adoption
- Apache Camel ESB and integration-route adoption
- PostgreSQL data migration
- Redis Pub/Sub replacement with Kafka
- Filesystem media migration to S3
- Provider adapter migration
- Validation, cutover, rollback, and retirement

This is a living plan. Detailed DDL and field transformations must not be
implemented until business invariants and production data profiles are
approved.

## 2. Migration principles

1. **Model first.** Define business concepts, ownership, invariants, and state
   transitions before writing persistence models, SQL, or Flyway migrations.
2. **Incremental replacement.** Route capabilities to Java one at a time while
   the legacy application remains operational.
3. **One authoritative writer.** Python and Java must never independently write
   the same domain records.
4. **No unsafe dual writes.** Synchronize through an outbox, CDC, or an explicit
   migration process rather than best-effort writes to two systems.
5. **Restartable data work.** Backfills and media copies are idempotent,
   checkpointed, observable, and safe to resume.
6. **Reconcile before cutover.** Counts alone are insufficient; compare keys,
   totals, statuses, checksums, and domain invariants.
7. **Rollback is designed.** Each cutover has measurable rollback conditions
   and a bounded rollback window.
8. **Preserve legacy evidence.** Do not delete legacy rows, files, identifiers,
   or raw provider payloads during initial migration.
9. **Tenant isolation by construction.** All new data, jobs, events, objects,
   logs, and caches carry tenant context.
10. **Security first.** Legacy secrets and tokens are rotated, not copied.
11. **Identity is centralized.** Keycloak is the only new credential, session,
    and token authority; legacy sessions are retired rather than translated.
12. **The ESB stays at the boundary.** Camel owns mediation and integration
    patterns, not order, payment, inventory, shipment, or onboarding decisions.
13. **Frontends migrate by journey.** New and legacy applications may coexist,
    but each routed user journey has one active implementation and a rollback
    path.

## 3. Target state

### 3.1 Application

- Java modular monolith using Spring Boot conventions
- Business modules enforced through package/build boundaries and Spring
  Modulith tests
- Hexagonal ports for persistence, Kafka, S3, payment providers, delivery
  partners, notifications, and maps
- Separate API, worker, scheduler, and migration process types from the same
  repository where practical
- Planned dedicated `commercial` and `recovery` module/schema boundaries after
  ADRs 0021 and 0013 are accepted for implementation

### 3.2 Data

- PostgreSQL remains the system of record
- New module-owned schemas coexist with legacy `public` tables during migration
- Tenant-owned data includes a non-null `tenant_id`
- Stable business constraints are enforced in both domain logic and the
  database
- Raw legacy IDs are retained through `legacy_id` fields or mapping tables
- Migration control, mapping, quarantine, reconciliation, and ownership
  decisions are durable target records rather than one-off script logs

### 3.3 Messaging

- Kafka replaces Redis Pub/Sub for durable asynchronous work and domain events
- Transactional outbox publication
- Inbox/deduplication for consumers and webhooks
- Versioned event envelopes and contracts
- Retry policies, poison-message handling, and dead-letter topics

### 3.4 Media

- Private S3 buckets with environment separation
- CDN with origin access control for public assets
- Signed access for private assets
- `media_asset` metadata in PostgreSQL
- Immutable tenant-aware object keys
- Checksum-based migration and verification

### 3.5 Identity and authorization

- Keycloak provides OpenID Connect, OAuth 2.0, SSO, MFA, identity brokering,
  sessions, organization membership, and coarse-grained application roles.
- The working B2B model uses one platform realm per environment and maps one
  Keycloak Organization to one HorecaOS tenant.
- A separate customer realm remains an option when customer and workforce
  identity lifecycle or isolation requires it.
- New frontends use Authorization Code with PKCE and separate public clients.
- Java APIs validate Keycloak tokens as resource servers.
- Camel routes and other machine clients use dedicated service accounts.
- HorecaOS domain services enforce tenant, brand, location, plan, entitlement,
  and resource-level authorization using verified identity claims and
  application-owned relationships.
- Legacy bearer and refresh tokens are not migrated.

### 3.6 Apache Camel ESB

- Camel is the integration runtime for HTTP, Kafka, files, payment providers,
  delivery partners, POS/ERP systems, messaging providers, and protocol
  conversion.
- Camel routes implement routing, transformation, throttling, timeouts,
  retries, circuit breaking, idempotency, and dead-letter patterns.
- Domain modules expose provider-neutral ports; Camel route and provider DTOs
  stay inside the integration boundary.
- Kafka remains the durable domain-event backbone. Camel may consume and
  produce Kafka messages but does not replace transactional outbox publication.
- Routes may run inside an integration module initially and move into
  separately scalable Camel runtimes when operational needs justify it.

### 3.7 Frontend applications

- All active Angular and React applications are migration sources, not target
  implementations.
- Target surfaces include platform administration, tenant onboarding and
  administration, brand/location operations, customer storefront, and courier
  workflows.
- A shared frontend platform provides a design system, localization, runtime
  tenant/brand theming, Keycloak integration, generated API clients,
  observability, and common testing infrastructure.
- The target web/mobile framework and monorepo strategy require an architecture
  decision before scaffolding.
- New and legacy applications coexist behind controlled routing until complete
  user journeys pass functional, accessibility, performance, and security
  acceptance criteria.

## 4. Workstreams and phases

The phases overlap only where dependencies and ownership are explicit.

## Phase 0: Governance and production discovery

### Objectives

- Establish the authoritative legacy environment and stakeholders.
- Inventory behavior, data, integrations, infrastructure, and media.
- Make unresolved product decisions visible before schema design.

### Tasks

- Identify production and staging PostgreSQL instances, deployment topology,
  Redis, background consumers, mounted media volumes, domains, and providers.
- Obtain a schema-only production database dump and anonymized representative
  data.
- Inventory row counts, table sizes, indexes, constraints, migration heads, and
  extension usage.
- Inventory filesystem media by path, byte size, type, owner, checksum, and
  last modification time.
- Enumerate all payment, SMS, FCM, Telegram, map, tax, and delivery integrations.
- Inventory every Camel candidate flow: source, destination, protocol,
  transformation, credentials, timeout, retry behavior, volume, owner, and
  business side effects.
- Capture current API contracts and consuming client versions.
- Inventory frontend routes, user journeys, roles, analytics, localization,
  browser/device support, deep links, custom domains, and business-critical UI
  behavior.
- Inventory every legacy identity type, credential/hash format, OTP flow,
  session/token format, role, scope, device identity, and service account.
- Establish owners for tenant, catalog, ordering, payments, fulfillment, media,
  and onboarding decisions.
- Reconcile every production table, view, function, trigger, sequence, API,
  frontend route, job, subscriber, report, provider callback, DNS route, and
  manual workflow against the migration coverage register.
- Create an architecture decision record process.

### Required product decisions

Already approved defaults are one location per brand, brand-owned catalogs,
one fulfillment location per order, and tenant-selected `TENANT_SHARED` or
`BRAND_ISOLATED` customer identity. Remaining Phase 0 decisions include:

- Whether partial capture, split payment, or stored account credit is required;
  full and partial refunds are already required
- Whether split shipment and multiple delivery partners are required at launch
- Which media classifications are public, private, or regulated
- Subscription, entitlement, quota, and usage-billing rules
- Tenant region and data-residency requirements
- Keycloak realm topology, organization mapping, administrator delegation, MFA,
  customer phone/OTP flow, external identity providers, and account recovery
- Which roles/scopes are emitted by Keycloak and which dynamic authorization
  relationships remain application-owned
- Target frontend framework, mobile/PWA strategy, supported clients, design
  system ownership, and white-label build policy
- Whether Camel routes deploy with the modular monolith or as a separate
  integration runtime in the first production release
- Whether legacy invitations/referrals, favorites, search history, ratings,
  tags/recommendations, storefront content/FAQ/social data, kitchens, incidents,
  blacklists, and internal courier groups/notes remain target product features
- Internal courier workforce, dispatch, live-location privacy, and retention
- Fiscal receipt, provider settlement, finance export, correction, and legal
  retention requirements
- Map/geocoding, address normalization, service-zone authority, and coordinate
  transformation policy
- Packaging/deposit/ancillary-item, SPIC/product classification, unit/VAT, and
  scheduled/pre-order reprice/capacity/payment/inventory/timezone policy
- Internal courier vehicle/document/referral, compensation/bonus/settlement,
  shift, and assignment-policy-group rules

### Exit criteria

- Production inventory is documented.
- Decision owners and open decisions are recorded.
- No unknown critical provider or data store remains.
- Every known source in `docs/migration-coverage.md` has an owner and proposed
  disposition; no in-scope capability reaches cutover with `DECIDE` remaining.
- Production backup and restore procedures have been tested.

## Phase 1: Legacy safety baseline

### Objectives

Reduce migration risk without attempting to redesign the legacy platform.

### Tasks

- Rotate source-controlled credentials, tokens, fixed OTPs, and documentation
  passwords.
- Remove the legacy query-string/custom documentation credential mechanism and
  rotate every exposed documentation credential; protect target API docs through
  environment policy and Keycloak/edge access rather than source constants.
- Remove sensitive token logging.
- Introduce environment validation and a secrets-management process.
- Add CI for legacy linting, migrations, backend tests, and frontend builds.
- Add dependency-aware readiness checks and worker health checks.
- Add structured correlation IDs and baseline order/payment metrics.
- Add characterization tests for current behavior:
  - authentication and OTP
  - cart calculation
  - order creation
  - payment prepare and complete callbacks
  - cancellation
  - courier assignment
  - notification publication
- Add frontend journey tests for login, browsing, cart, checkout, order
  tracking, operations workflows, courier workflows, and tenant administration.
- Capture representative frontend screenshots and API traffic only with
  sanitized test data, to support behavior comparison during replacement.
- Document existing order, cooking, shipment, payment, and onboarding states.

### Exit criteria

- Critical credentials are no longer trusted from source code.
- Critical API behavior has regression coverage.
- Production failures can be correlated across requests and provider calls.
- The legacy platform can be restored from backup.

## Phase 2: Canonical domain and data model

### Objectives

Approve the target model before creating production tables.

### Implementation status (2026-08-18)

The version-1 logical model, core invariants, ERD, state machines, business
processes, and initial Company/Vendor mapping are accepted in
[`docs/domains`](domains/README.md). The checked-in 64-table model now has a
table-level disposition baseline in the legacy mapping and
[`docs/migration-coverage.md`](migration-coverage.md); physical data
dictionaries and column/value mappings still continue capability by capability.
Production schema/data profiling and business-approved tenant grouping remain
mandatory before any backfill or cutover.

### Deliverables

- Domain glossary
- Aggregate and ownership map
- State-transition diagrams
- Target logical ER model
- Physical PostgreSQL model
- Table/column data dictionary
- Constraint and index catalog
- Legacy-to-target mapping matrix
- Data-retention and privacy classification
- Kafka event catalog and schema policy
- S3 media classification and key policy
- Keycloak realm/organization/client/role model and legacy identity mapping
- Camel route catalog and canonical integration contracts
- Frontend journey inventory, target information architecture, rollout matrix,
  design-system plan, and browser/device support policy

### Initial ownership model

```text
Tenant -> Brand -> Location
```

The initial PostgreSQL schemas are:

```text
iam, tenant, customer, catalog, inventory, pricing, ordering, payments,
fulfillment, media, notifications, integration, reporting, audit
```

Planned ADRs add `commercial` and `recovery` schemas when those module
boundaries enter implementation.

### Core transformations

| Legacy concept | Target concept | Transformation notes |
|---|---|---|
| `Company` row | `brand` | Assign to an explicitly mapped tenant. |
| `Customer.company` enum/string | tenant/brand customer association | Resolve through an approved legacy-company mapping. |
| `Vendor` | `location` | Preserve legacy UUID and link to its migrated brand and tenant. |
| Dashboard/vendor users | Keycloak user plus tenant organization membership | Hashes migrate only through an approved Keycloak mechanism; tokens never migrate. |
| Customer | Keycloak subject plus `customer_profile` | Deduplicate only under an approved identity policy and preserve an explicit identity link. |
| Customer session/device | Keycloak session plus application `device` | Require a new Keycloak session; normalize timestamps and device identity. |
| Category/product/variant | catalog aggregates | Resolve ownership, translations, external IDs, and duplicates. |
| Product vendor relation | `location_offering` plus pricing/inventory/integration facts | Offering owns sellability/schedule/preparation; pricing owns money/tax, inventory owns availability, and integration owns external mappings. |
| Variant stock and `Stock` | inventory balance/movement | Select one source through reconciliation; create opening-balance movement. |
| Cart | cart aggregate | Add tenant, brand/location, currency, version, and item uniqueness. |
| Order | order plus related aggregates | Split fulfillment, preparation, payment summary, notes, and history. |
| Order line item | immutable order item snapshot | Preserve product reference when valid but never depend on it for history. |
| Order address FK | order address snapshot | Copy the address values used at order time. |
| `FinAgent` | merchant account | Scope to tenant, brand, or location and replace secret data with a reference. |
| Payment/transaction | payment intent and attempt | Resolve duplicated order/payment statuses and provider references. |
| Shipment fields on order | shipment, assignment, and history | Reconstruct lifecycle timestamps and actor attribution where possible. |
| Redis event | versioned outbox event | Only events needed for forward operation are created; history is not invented. |
| Image/path column | media asset reference | Resolve and checksum the legacy object before attaching it. |
| Generic status lookup | domain enum or configurable state | Decide separately for each domain. |
| JSON business fields | structured table/value object | Preserve raw JSON during transition and transform approved fields. |

### Data-quality profiling

At minimum, profile:

- Duplicate tenants, brands, slugs, usernames, phones, SKUs, and provider IDs
- Orphaned foreign keys and orphaned media
- Integer/UUID mismatches in customer references
- Invalid or conflicting order/payment/shipment statuses
- Orders with missing items, payment records, customer, location, or totals
- Multiple successful payments for one order
- Currency and money-unit assumptions
- Negative prices, quantities, inventory, discounts, and totals
- Naive, malformed, or string timestamps and timezone assumptions
- Mutable addresses referenced by historical orders
- Product availability and stock conflicts
- Invalid geographic coordinates and delivery-zone JSON
- Unsupported or unknown enum values
- Sensitive provider data stored in generic JSON fields
- Invitations, favorites, search histories, ratings, tags/recommendations,
  storefront content, FAQs/social links, kitchens, incidents, and blacklists
- Courier identity/PII, groups, areas, restrictions, notes, instructions, and
  live-location volume/retention
- Tax receipt status/request/response and provider settlement differences
- Production objects/runtime stores not represented in the checked-in ORM

### Exit criteria

- Product and engineering approve invariants and state machines.
- Every legacy table is mapped, retained, archived, or explicitly discarded.
- All target keys, constraints, and ownership rules are defined.
- Migration transformations have testable acceptance rules.

## Phase 3: Java, Keycloak, and Camel platform foundation

### Objectives

Create the deployable platform skeleton without moving critical business writes.

### Implementation status (2026-08-18)

The repository now contains the Java 25/Spring Boot 4.1 build, Maven Wrapper,
Spring Modulith package boundaries, Camel runtime baseline, Keycloak
resource-server configuration, health/metrics endpoints, local
PostgreSQL/Kafka/Keycloak Compose stack, module-owned logical schemas, and the
first tenant-aware control-plane API. Keycloak organization claims are matched
to immutable organization IDs on tenant records, and organization-specific
roles are kept separate for users who belong to multiple tenants. No legacy
critical business writes have moved.

The first tenant-context enforcement and Keycloak organization-link
reconciliation slice is complete, including negative cross-tenant tests. The
SQL transactional outbox, leased Kafka relay, and initial tenancy event
contracts are also complete. The remaining tasks and exit criteria below are
still open. In particular, actual Keycloak organization
provisioning/invitation, application-owned membership projections, consumer
inbox/checkpoint handling, production observability, S3-compatible testing,
and a verified sample Camel flow are not complete.

### Tasks

- Scaffold the Java build and module structure.
- Configure PostgreSQL migrations, module-owned schemas, and connection pooling.
- Add Spring Modulith boundary verification.
- Deploy Keycloak in development/test with an external PostgreSQL database,
  backup policy, health checks, TLS strategy, and reproducible realm/client
  configuration.
- Implement Keycloak issuer/audience validation, tenant context,
  resource-server security, authorization projection, and negative
  cross-tenant tests.
- Create initial public frontend clients, Java resource-server client, Camel
  service-account client, and migration service account.
- Establish immutable mappings between Keycloak organization/user identifiers
  and HorecaOS tenant/principal records.
- Scaffold the Apache Camel integration runtime and route-module conventions.
- Add Camel Kafka and HTTP foundations, error handling, idempotent repository,
  route health, trace propagation, and secrets resolution.
- Add standard API errors, validation, pagination, idempotency keys, and
  correlation IDs.
- Add OpenTelemetry-compatible logs, metrics, and traces.
- Add Testcontainers for PostgreSQL, Kafka, Keycloak-compatible integration
  testing, and S3-compatible storage where practical.
- Provision Kafka development/test infrastructure.
- Create outbox, inbox, consumer checkpoint, and dead-letter metadata.
- Create `media_asset` and media ownership contracts.
- Establish secrets-manager and provider-configuration abstractions.
- Implement the Flyway policy for backward-compatible releases.

### Exit criteria

- The platform builds and deploys through CI/CD.
- Module boundaries and tenant isolation are tested.
- Database, Kafka, and object-store dependencies have readiness checks.
- A sample transaction publishes an outbox event and an idempotent consumer
  processes it in integration tests.
- A public test frontend completes Authorization Code with PKCE, and the Java
  API rejects invalid issuer, audience, organization, and tenant scope.
- A sample Camel route consumes a Kafka message, invokes a controlled fake
  provider, propagates tracing, and demonstrates retry/dead-letter behavior.

## Phase 4: SaaS control plane and onboarding

### Objectives

Make tenant onboarding repeatable before migrating commerce capabilities.

### Implementation status (2026-08-19)

The first bounded slice persists and exposes tenants, brands, locations, and
the initial customer identity policy. A location is created under one tenant
and one brand, with ancestry protected by PostgreSQL composite constraints.
Control-plane access is derived from Keycloak organization claims and scoped
roles. Creation events now use the transactional outbox. The resumable
onboarding run, Keycloak Admin API provisioner, memberships/grants, readiness
checks, and activation workflow remain the next Phase 4 work.

### Target workflow

```text
DRAFT -> PROVISIONING -> CONFIGURING -> VALIDATING
      -> READY -> ACTIVATING -> ACTIVE
```

### Tasks

- Implement tenant, subscription, plan, and entitlement records.
- Implement brands, locations, verified domains, and themes.
- Implement tenant, brand, and location-scoped memberships.
- Provision and reconcile a Keycloak Organization for every tenant.
- Invite or link tenant administrators through Keycloak and persist immutable
  organization/user identifiers in the control plane.
- Provision tenant-aware frontend access and verified redirect origins without
  creating a realm per tenant.
- Implement configuration resolution:
  `platform -> tenant -> brand -> location`.
- Implement onboarding run, step, error, checkpoint, and artifact records.
- Make every onboarding step idempotent and independently retryable.
- Add versioned onboarding templates.
- Add imports for locations, catalogs, staff, and stock with dry-run validation.
- Store integration credentials in a secrets manager and verify connections.
- Define tenant readiness rules and an activation smoke test.
- Emit versioned onboarding events through the transactional outbox.

### Exit criteria

- A tenant with multiple brands and locations can be onboarded without manual
  SQL or filesystem work.
- Failed onboarding can resume at the failed step.
- Activation is prevented until required readiness checks pass.
- Tenant owner permissions and cross-tenant isolation are verified.
- Keycloak organization provisioning, invitation, role assignment, disable,
  and re-enable operations are idempotent and reconcilable.

## Phase 5: Frontend platform and application migration

### Objectives

- Replace the legacy Angular and React applications without a big-bang UI
  launch.
- Establish shared frontend foundations for Keycloak, tenant/brand theming,
  localization, API contracts, observability, accessibility, and testing.
- Migrate complete user journeys rather than isolated pages that depend on
  unfinished backend capabilities.

### 5.1 Discovery and target decisions

- Create an application/route inventory for the customer apps, React
  dashboard, Angular dashboard, vendor workflows, and courier workflows.
- Mark each legacy route as migrate, merge, redesign, or retire, with a product
  owner and usage evidence.
- Document critical journeys, roles, API dependencies, deep links, browser and
  device support, performance baseline, localization, accessibility gaps, and
  analytics events.
- Decide and record:
  - target web framework and supported mobile/PWA approach
  - monorepo and package boundaries
  - server-side rendering or client-side rendering needs
  - BFF requirements, if any
  - shared design-system ownership and release model
  - runtime tenant/brand theme contract
  - white-label domain and optional native-build strategy
  - browser/device support and accessibility standard

### 5.2 Shared frontend foundation

- Create the shared design tokens, components, form patterns, navigation,
  localization, error states, and responsive conventions.
- Generate typed clients from versioned OpenAPI contracts and detect breaking
  changes in CI.
- Create one Keycloak client per application and environment.
- Implement Authorization Code with PKCE, login, logout, session expiry, token
  refresh, organization selection, forbidden states, and account recovery.
- Keep browser tokens in memory and configure exact redirect URIs and web
  origins. Register custom tenant domains only after domain verification.
- Implement runtime bootstrap for tenant, brand, locale, theme, entitlements,
  and feature flags.
- Add frontend logging, tracing/correlation propagation, analytics, privacy
  controls, performance measurements, and error monitoring.
- Add unit, component, visual-regression, accessibility, and end-to-end test
  infrastructure.
- Define API compatibility and gateway routing so a new frontend journey can
  use Java APIs while an unmigrated journey remains on the legacy application.

### 5.3 Identity transition

- Build an identity-link table from legacy actor IDs to immutable Keycloak
  subjects and organization IDs.
- Choose an approved migration mechanism for password users: compatible
  credential import, federation during transition, invitation, or forced reset.
- Implement the approved customer phone/OTP flow in or behind Keycloak.
- Do not migrate legacy access or refresh tokens; require a new Keycloak login.
- If a legacy API must remain behind a new frontend temporarily, add an
  explicitly bounded compatibility adapter that validates Keycloak tokens and
  maps subjects to legacy identities. Do not mint or expose legacy tokens to
  the new browser application.
- Reconcile disabled users, duplicate phones/usernames, organization
  memberships, roles, and account-recovery channels before enabling a cohort.

### 5.4 Application migration order

The default order is:

1. Platform administration and tenant onboarding
2. Tenant/brand/location administration
3. Brand/location operations and order management
4. Customer storefront, cart, checkout, and order tracking
5. Courier workflows and live operational views

The order may change by dependency, but every migration slice must contain a
complete usable journey, its Java API capability, Keycloak authorization, and
operational telemetry.

### 5.5 Rollout and cutover

- Route internal users and test tenants first.
- Roll out by tenant, brand, role, application, or controlled traffic cohort.
- Preserve stable public URLs and deep links or provide explicit redirects.
- Compare conversion, task completion, errors, latency, accessibility, and
  support contacts between legacy and new journeys.
- Keep a gateway or feature-flag rollback to the legacy journey during the
  agreed window.
- Freeze migrated legacy UI code except for critical fixes.
- Remove a legacy frontend only after every route is migrated or explicitly
  retired and all supported clients have upgraded.

### Exit criteria

- Each target application passes its critical end-to-end journeys with
  Keycloak and Java APIs.
- Tenant/brand theming and localization work without source-code forks.
- No new frontend depends on a legacy bearer token.
- Redirect URI, organization, role, tenant, and resource denial tests pass.
- Accessibility, supported-browser, performance, and error-rate objectives are
  met.
- Rollback routing has been exercised.
- Duplicate legacy frontend applications have an approved retirement date.

## Phase 6: S3 media migration

### 6.1 Target design

Use separate buckets by environment and data classification where required.
Keep buckets private. Public assets are delivered through a CDN; private assets
use short-lived signed access.

`media_asset` records:

```text
id
tenant_id
object_key
original_filename
content_type
byte_size
checksum_sha256
visibility
status
width
height
created_by
created_at
deleted_at
legacy_path
```

Use entity-specific relations such as `product_media`, `brand_media`, and
`location_media` rather than an unconstrained polymorphic owner ID.

Example key:

```text
tenants/{tenantId}/products/{productId}/{assetId}/original.jpg
```

### 6.2 Provisioning

- Create production and non-production buckets.
- Enable Block Public Access, encryption, versioning during migration, access
  logging, lifecycle policies, and appropriate retention.
- Configure CDN origin access controls and HTTPS.
- Configure CORS only for approved direct-upload clients and methods.
- Create least-privilege roles for API upload signing, migration, processing,
  and delivery.
- Configure metrics and alerts for errors, bytes, requests, and missing objects.

### 6.3 Upload lifecycle

```text
REQUESTED -> UPLOADING -> UPLOADED -> VALIDATING
          -> PROCESSING -> AVAILABLE
          -> REJECTED or FAILED
```

- The authenticated API allocates the asset and immutable key.
- The client receives a short-lived presigned upload URL.
- Upload includes content length, expected content type, and checksum.
- A finalize call or object event triggers validation.
- Workers inspect metadata, perform security/type validation, and generate
  derivatives.
- Business entities may reference only available assets unless a use case
  explicitly supports pending assets.

### 6.4 Legacy inventory and copy

1. Freeze the interpretation of legacy path formats.
2. Scan the production media volume and record path, size, MIME type, checksum,
   timestamps, and probable owner.
3. Extract every path referenced by the database.
4. Classify referenced, unreferenced, missing, duplicate, and unsafe files.
5. Build an approved legacy-path-to-tenant/owner mapping.
6. Copy files using generated immutable keys; do not overwrite on retry.
7. Upsert the migration result and checksum into a media mapping table.
8. Verify source and destination size and SHA-256 checksum.

### 6.5 Dual-read and cutover

1. Deploy S3-aware reads with legacy filesystem fallback.
2. Send all new uploads to S3.
3. Backfill legacy media in resumable batches.
4. Backfill business `media_asset_id` references.
5. Compare database references, object counts, bytes, and checksums.
6. Monitor missing-object and CDN error metrics.
7. Freeze legacy writes and execute a final delta copy.
8. Switch reads to S3/CDN as authoritative.
9. Retain legacy files and fallback capability for the rollback window.
10. Archive the filesystem only after sign-off; delete it under a separate,
    approved retention procedure.

### Rollback triggers

- Material increase in missing or inaccessible assets
- Checksum mismatch
- Authorization exposure of private media
- CDN or S3 error rate above the agreed threshold
- Application latency or cost outside the agreed envelope

## Phase 7: Kafka migration

### Implementation status (2026-08-19)

Migration V0004 creates the tenant-aware PostgreSQL outbox. Tenant, brand, and
location creation emits version-1 events to `tenancy.events`. Multiple relay
replicas claim work through bounded `FOR UPDATE SKIP LOCKED` leases; retry
exhaustion is retained as visible database dead-letter state, and a blocked
aggregate cannot publish later events out of order. PostgreSQL transaction,
lease, retry/dead-letter, full service-to-outbox, and real Kafka envelope/header
tests are implemented. Consumer inbox/deduplication, replay operations,
production topic provisioning/ACLs, and Redis migration remain open.

### 7.1 Event catalog

Define producer, consumer, schema, partition key, retention, security
classification, retry, dead-letter behavior, owner, and Camel route involvement
for each event.

Initial domain events include:

```text
TenantCreated
TenantActivated
BrandCreated
LocationCreated
CatalogImported
OrderReceived
OrderConfirmed
OrderCancelled
PaymentInitiated
PaymentAuthorized
PaymentFailed
RefundCompleted
ShipmentRequested
ShipmentAssigned
ShipmentStatusChanged
NotificationRequested
MediaAssetAvailable
```

Do not reproduce every Redis message automatically. Publish stable business
facts rather than UI- or provider-specific payloads.

### 7.2 Event envelope

```json
{
  "eventId": "uuid",
  "eventType": "PaymentAuthorized",
  "eventVersion": 1,
  "tenantId": "uuid",
  "aggregateId": "payment-uuid",
  "correlationId": "order-uuid",
  "occurredAt": "UTC timestamp",
  "trace": {},
  "payload": {}
}
```

### 7.3 Redis Pub/Sub replacement

1. Catalog current publishers, subscribers, payloads, and side effects.
2. Introduce a legacy-compatible PostgreSQL outbox.
3. Write the business change and outbox record atomically where possible.
4. Deploy an outbox relay to Kafka.
5. Implement Java notification consumers and their Camel-mediated external
   delivery routes first.
6. Deduplicate with `eventId` and persist consumer outcomes.
7. Add bounded retries and dead-letter handling.
8. Shadow side effects where safe; never send duplicate customer messages.
9. Move one event family at a time.
10. Disable the corresponding Redis subscriber after reconciliation.
11. Remove Redis Pub/Sub only after no publisher or subscriber remains.

Camel Kafka routes must use the same event contracts, idempotency keys, trace
context, and retry/dead-letter policy as ordinary Java consumers. A Camel route
must not acknowledge a message before its required durable side effect or
idempotency record is complete.

Redis may remain for caching, rate limiting, and short-lived coordination.

### Exit criteria

- Broker outages do not lose committed business events.
- Consumers resume from durable positions.
- Duplicate deliveries do not duplicate provider calls or notifications.
- Dead-letter messages are visible and operationally actionable.

## Phase 8: Capability migration sequence

The ADR roadmap is the implementation dependency order. The sequence below is
the production ownership-transfer order; foundations can be implemented earlier
without becoming authoritative. Change it only through an explicit dependency
and writer-ownership review.

### 8.1 Messaging transport and notifications

- Complete inbox, retry, dead-letter, replay, and fake-provider foundations.
- Inventory every Redis channel/publisher/subscriber and distinguish Pub/Sub
  from cache/rate-limit keys.
- Consume versioned Kafka commands/events in Java and normalize FCM, SMS,
  Telegram/messaging, and email adapters behind Camel where appropriate.
- Transform notification preferences into explicit purpose/class/channel
  semantics and migrate delivery evidence without resending it.
- Run one side-effect owner, shadow only records/decisions, and disable the
  corresponding Redis subscriber after count/status/idempotency reconciliation.

### 8.2 Media and object delivery

- Move upload, validation, derivative, access, and retention contracts to media.
- Inventory/checksum/classify filesystem objects and all database/frontend
  references.
- Complete S3 backfill, dual read, reference switch, CDN/signed access, and
  rollback retention before removing ORM filesystem methods.

### 8.3 SaaS control plane, IAM, and commercial baseline

- Make Java authoritative for tenants, brands, locations, verified domains,
  memberships/grants, configuration, and resumable onboarding.
- Make Keycloak authoritative for credentials, sessions, organizations, and
  approved coarse roles; keep resource relationships in HorecaOS.
- Transform approved `configs`/lookup values into typed scoped configuration.
- Assign a versioned trial/plan/subscription and shadow usage/entitlements before
  enabling limits. Reconcile resource counts before hard enforcement.
- Provide bounded legacy read/identity adapters while old clients remain.

### 8.4 Catalog, merchandising, storefront content, and search reads

- Decide migrate/transform/archive/retire for tags, recommendations, storefront
  UI elements/offers, FAQ/social content, kitchens, and search history.
- Backfill brand products/variants, catalogs, categories, translations, media,
  location offerings, and any approved preparation/content/merchandising facts.
- Build immutable publications and shadow-render complete menus/search/filter/
  content responses for production-shaped locations/locales.
- Route selected reads only after publication, price, availability, media, and
  product mapping versions reconcile. Derived search/cache state is rebuildable.

### 8.5 POS installations, mappings, staging, and authority

- Verify current CLOPOS, r_keeper, and iiko contracts/sandboxes/capabilities.
- Create tenant installations, secret references, location bindings, external
  mappings, sync schedules, staging, differences, review, and reconciliation.
- Import daily into staging; use catalog/inventory/pricing commands for approved
  apply and never mutate an active publication directly.
- Enable approval and order-export capabilities separately with inbox,
  idempotency, uncertainty, manual fallback, and one webhook/export owner.

### 8.6 Inventory and location availability

- Reconcile duplicated stock fields/tables and create explicit opening
  movements rather than summing or overwriting sources.
- Build binary/untracked/quantity positions, reservations, movements, expiry,
  and availability projections.
- Shadow reservation conflicts before enforcing checkout; move product/location
  operational writes to Java only after POS and legacy writers are fenced.

### 8.7 Customer identity, engagement decisions, devices, and carts

- Apply the approved `TENANT_SHARED` or `BRAND_ISOLATED` identity policy and
  build immutable Keycloak/principal/customer mappings.
- Reissue sessions through Keycloak; implement approved customer phone/OTP,
  recovery, abuse controls, addresses, consent, devices, and notification
  endpoints.
- Decide and migrate/archive/retire invitations/referrals, favorites, search
  history, ratings, incidents, and blacklists under their privacy/risk rules.
- Transform only eligible active carts with explicit tenant/brand/location/
  currency/publication ownership, then reprice/revalidate before checkout.

### 8.8 Pricing, promotions, coupons, tax, and benefits

- Implement deterministic, versioned price/promotion/tax/fee/rounding rules.
- Reconcile legacy offers, eligibility, usage associations, and denormalized
  counters before creating coupon/promotion redemptions.
- Store accepted quote/order adjustments as immutable evidence.
- Compare target and legacy golden carts across locations, times, customers,
  coupons, delivery modes, and rounding boundaries; explain every difference.

### 8.9 Payments, fiscal receipts, settlement, and service recovery

- Implement provider-neutral intents, attempts, immutable transactions,
  refunds, merchant installations, webhook inboxes, and Click/Payme adapters.
- Rotate secrets and coordinate callback/IP allowlist ownership; do not copy
  provider credentials from source data.
- Reconcile database facts against provider settlement by merchant, currency,
  status, capture, refund, external ID, and business date.
- Approve and implement or archive the fiscal receipt lifecycle; historical
  import cannot issue/correct a receipt.
- Transform trustworthy incidents into recovery cases and benefits/remedies;
  never execute historical compensation during import.
- Cut over one tenant/location/provider capability at a time.

### 8.10 Internal courier and external fulfillment

- Split delivery plan, shipment, assignment, tracking, preparation, and cost
  allocation from order state.
- Decide and transform internal courier identity, shifts/availability, groups/
  pools, areas/PostGIS zones, blocks/restrictions, notes/instructions, devices,
  and privacy-bounded location history.
- Implement Keycloak courier access and internal single-winner dispatch.
- Verify and implement Noor, Yandex Delivery, and Millennium quote/schedule/
  create/reschedule/cancel/query/tracking capabilities through Camel adapters.
- Reconstruct only evidenced shipment history; reconcile uncertain assignments
  before fallback and keep Operations manual assignment available.

### 8.11 Ordering and complete frontend journeys

- Migrate ordering writes last because checkout coordinates identity, catalog,
  inventory, pricing, payment, POS, fulfillment, notifications, and recovery.
- Create immutable order/item/modifier/customer/address/policy/amount snapshots,
  explicit transitions, process checkpoints, and idempotent checkout.
- Import historical completed orders without side effects; drain legacy in-
  flight orders where possible and use per-state handoff only when unavoidable.
- Shadow checkout/order rendering and both `AUTO_CONFIRM` and
  `RESTAURANT_APPROVAL` paths.
- Route complete control-plane, Operations, storefront, and courier journeys by
  cohort with one backend writer and an exercised route rollback.

### 8.12 Reporting, audit, privacy operations, and derived state

- Inventory every dashboard metric, finance/tax export, support lookup,
  scheduled report, analytics event, spreadsheet handoff, and consumer.
- Reconcile retained target read models/exports to approved source definitions;
  archive/retire the rest explicitly.
- Transform only trustworthy `action_logs` into audit facts and archive ordinary
  logs under operational/security retention.
- Implement tenant/customer export, correction/anonymization, consent evidence,
  retention expiry, legal hold, deletion proof, and projection/archive handling.
- Rebuild and compare cache/search/reporting projections before retiring their
  legacy producer or store.

## Phase 9: Database backfill and synchronization mechanics

### Backfill requirements

- Read stable key ranges rather than unbounded offset pagination.
- Persist checkpoints, run ID, code version, start/end time, counts, and errors.
- Upsert by stable legacy identity.
- Separate transformation errors from infrastructure retries.
- Quarantine invalid rows with enough context for remediation.
- Avoid long transactions and production table locks.
- Rate-limit migration load and monitor replica/primary pressure.

### ID mapping

Use explicit mapping tables when IDs cannot be preserved:

```text
migration.entity_map:
    migration_run_id
    entity_type
    legacy_id
    target_id
    tenant_id
    source_updated_at
    migrated_at
```

Identity migration also maintains an explicit map containing legacy actor type
and ID, Keycloak realm, subject ID, organization ID, HorecaOS tenant ID, link
status, and reconciliation timestamp. Never infer identity by mutable username,
email, phone, or display name after the link is approved.

### Change synchronization

For a domain still written by Python:

- Backfill the historical baseline.
- Capture subsequent changes through an approved outbox or CDC stream.
- Apply changes idempotently to the target.
- Measure synchronization lag.
- Stop legacy writes at cutover.
- Drain and verify the synchronization stream.
- Enable Java writes only after the target is caught up.

Application-level uncontrolled dual writing is prohibited.

### Non-row and external-control state

Database rows are only part of migration state. For each scoped capability:

- preserve or explicitly remap sequences/public order-number generators,
  watermarks, file offsets, consumer positions, idempotency keys, leases, and
  scheduled due work;
- assign exactly one owner for every cron/scheduler, Redis subscriber, Kafka
  consumer, webhook endpoint, provider callback, retry/dead-letter replay, and
  filesystem watcher;
- inventory cache/search/reporting projections, compare them to their source,
  and rebuild rather than treating them as authoritative data;
- coordinate provider credentials, merchant/installations, callback URLs,
  signatures, IP allowlists, outbound egress, DNS/TLS/gateway routes, sender
  identities, and push projects; and
- fence the legacy producer/receiver before enabling its target equivalent.

In-flight due work is drained, imported with an explicit checkpoint, or
cancelled under a business rule. It is never left runnable in both systems.

### Reconciliation

Run both generic and domain-specific checks:

- Row counts and key coverage
- Missing and duplicate mappings
- Foreign-key completeness
- Status distribution
- Monetary totals by tenant/day/provider/status
- Order item quantities and totals
- Inventory opening balance and movements
- Payment success/refund totals
- Shipment lifecycle consistency
- Media reference/object/checksum coverage
- Tenant and brand ownership
- Keycloak subject/organization/membership/grant coverage and denied orphan roles
- Fiscal receipt and provider settlement coverage
- POS mappings/sync differences/export/approval status
- Courier identity/pool/zone/assignment and privacy-bounded location coverage
- Notification preference/delivery status without replayed sends
- Reports/audit/privacy-retention outputs and derived projection checksums
- Outstanding jobs, leases, inbox/outbox/dead letters, webhooks, and provider
  uncertain outcomes

Store reconciliation results as versioned migration artifacts.

## Phase 10: Cutover procedure

Each capability cutover requires a written runbook containing:

1. Scope: tenant, brand, location, endpoint, frontend journey, Keycloak client,
   Camel route, provider, or traffic cohort.
2. Required software and schema versions.
3. Pre-cutover backup and restore point.
4. Backfill checkpoint and synchronization lag.
5. Reconciliation results and accepted exceptions.
6. Feature flags and routing changes.
7. Observability dashboard and alert thresholds.
8. Smoke-test transactions.
9. Rollback triggers and decision owner.
10. Communication and support contacts.
11. Legacy/target writer, reader, scheduler, consumer, webhook, provider-effect,
    and report ownership matrix.
12. Provider callback/DNS/TLS/IP/credential changes and exact fencing order.
13. In-flight state classification and maximum rollback decision time.
14. Privacy, retention, legal-hold, fiscal, and audit evidence requirements.

Frontend cutovers additionally record application version, route/deep-link
ownership, supported browsers/devices, Keycloak redirect URIs, analytics
baseline, and UI rollback target. Integration cutovers additionally record the
Camel route version, service account, provider contract, idempotency store,
retry/dead-letter destination, and replay procedure.

### Generic cutover sequence

1. Confirm entry criteria and backups.
2. Pause affected legacy writes if required.
3. Drain and reconcile outstanding changes.
4. Enable Java reads in shadow mode.
5. Enable Java authoritative writes for the bounded scope.
6. Route reads to Java.
7. Route the bounded frontend journey and Camel integration flow to the target
   implementation where applicable.
8. Run Keycloak login/authorization, synthetic, and real smoke checks.
9. Monitor errors, latency, business totals, frontend journeys, Keycloak health,
   Camel route health, Kafka lag, and provider outcomes.
10. Verify no legacy timer/subscriber/webhook can repeat target side effects and
    no target callback still reaches the legacy owner.
11. Keep legacy data and rollback routing intact for the agreed window.
12. Record sign-off or execute rollback.

## Phase 11: Legacy contraction and retirement

Contract only after the rollback window and business sign-off:

- Disable migrated legacy routes and workers.
- Retire duplicated Angular/React applications after their route inventories
  are fully migrated or explicitly retired.
- Disable legacy login, session, and token issuance after all supported actors
  use Keycloak.
- Remove temporary identity compatibility adapters and revoke their service
  accounts.
- Remove obsolete Redis Pub/Sub publishers and subscribers.
- Disable every legacy scheduler/cron, retry worker, webhook receiver, provider
  callback, report/export job, and one-off operational script in the retired
  scope; retain an inventory and proof of inactivity.
- Remove temporary Camel legacy bridges after the destination system is
  authoritative and the replay/rollback window closes.
- Remove local media write and read paths.
- Retire or archive legacy favorites/referrals/search history/ratings/content/
  kitchens/risk/courier behavior according to their approved dispositions.
- Remove old DNS/gateway/CDN routes, certificates, sender identities, callback
  URLs, provider allowlists, push projects, and analytics/error-monitoring
  sources only after traffic and rollback windows close.
- Revoke unused credentials and infrastructure permissions.
- Archive legacy tables and media according to retention policy.
- Remove compatibility columns only through separate reviewed migrations.
- Preserve required financial, tax, audit, and migration evidence.
- Update operating procedures and disaster recovery documentation.

The final deletion of legacy data or infrastructure is a separate destructive
change and requires explicit approval.

## 5. Testing strategy

### Domain tests

- Tenant activation and configuration resolution
- Tenant, organization, brand, location, role, and resource authorization
- Money, quantity, tax, and discount rules
- Order, payment, shipment, and onboarding transitions
- Inventory reservation and release
- Provider capability selection
- Customer identity partition/merge, consent, benefit, and retained engagement rules
- Fiscal receipt, recovery remedy, internal courier dispatch, and retention policies

### Integration tests

- PostgreSQL constraints and transactions
- Keycloak OIDC login, PKCE, logout, refresh, MFA, organization claims, role
  mapping, service accounts, account disablement, and key rotation
- Camel route transformation, timeout, retry, circuit breaker, idempotency,
  trace propagation, Kafka acknowledgment, and dead-letter behavior
- Outbox relay and Kafka consumers
- Inbox/webhook deduplication
- S3-compatible upload, checksum, and access policy
- Tenant row isolation
- Provider sandboxes or verified fakes
- Fiscal receipt/settlement import and internal courier location/dispatch privacy

### Frontend tests

- Unit and component behavior
- Design-system visual regression
- Accessibility automation plus manual keyboard/screen-reader review
- End-to-end Keycloak and critical business journeys
- Tenant/brand runtime theming and localization
- Supported browser, viewport, deep-link, and session-expiry behavior
- Contract compatibility against generated OpenAPI clients

### Migration tests

- Repeat the same backfill without duplicate target rows
- Resume from a checkpoint after failure
- Handle invalid, missing, and duplicate legacy records
- Compare legacy and target order/payment totals
- Verify media checksums and access classification
- Test rollback routing and synchronization drain
- Reconcile legacy identities to Keycloak subjects and organizations
- Exercise frontend journey rollback without losing cart or operation state
- Verify Camel replay does not duplicate external side effects
- Assert the production schema/runtime inventory has no undisposed source and no
  in-scope `DECIDE` at cutover
- Reconcile timers/jobs/webhooks/provider callbacks and prove exactly one side-
  effect owner before and after rollback

### Performance tests

- Catalog read and search load
- Checkout contention and inventory reservation
- Kafka consumer throughput and lag recovery
- Courier location ingestion
- Bulk import and migration load
- Presigned upload and CDN delivery
- Keycloak login/token validation and organization-heavy tenant scenarios
- Camel route throughput, backpressure, provider latency, and recovery
- Frontend startup, navigation, interaction, and Core Web Vitals targets
- Internal courier location ingestion/dispatch and report/export backfills

## 6. Operational acceptance criteria

Final thresholds must be quantified before each cutover. At minimum:

- No known cross-tenant data exposure
- No unexplained missing target records
- No unexplained monetary reconciliation difference
- No duplicate provider charge or refund from redelivery
- No committed outbox event permanently lost
- Consumer lag recovers within the agreed objective
- Media references resolve with verified access policy
- Keycloak is the only active new token issuer, and cross-tenant authorization
  denial tests pass
- Camel route failures are observable, bounded, replayable, and do not corrupt
  domain state
- New frontend critical journeys meet approved accessibility, browser,
  performance, and error-rate objectives
- Backups and rollback have been rehearsed
- Error rate and latency stay within approved bounds
- Support and operations can identify tenant, order, payment, shipment, event,
  and migration run from observability data
- No in-scope legacy table, route, frontend journey, job, webhook, report,
  provider account, or manual workflow lacks an approved disposition
- No legacy process can repeat a target payment, refund, receipt, POS export,
  shipment/assignment, notification, or scheduled transition
- Fiscal/settlement evidence, internal courier/location privacy, audit/reporting,
  and retention/privacy operations meet their approved reconciliation rules

## 7. Major risks and mitigations

| Risk | Mitigation |
|---|---|
| Hidden legacy business behavior | Characterization tests, API capture, product review, and shadow comparison. |
| Incorrect tenant grouping | Business-approved company-to-tenant/brand mapping and reconciliation. |
| Cross-tenant access | Mandatory tenant keys, composite constraints, authorization tests, and row-level security. |
| Keycloak organization or role drift | Idempotent provisioning, immutable external IDs, reconciliation jobs, audited admin changes, and application denial by default. |
| Keycloak outage blocks users | HA deployment, tested backups/upgrades, local JWT validation, readiness isolation, and a documented recovery plan. |
| Phone/OTP migration breaks customer access | Prototype the Keycloak flow early, migrate by cohort, preserve identity links, monitor login funnels, and provide recovery support. |
| Camel becomes a smart central bottleneck | Keep domain logic outside routes, organize routes by owner, version contracts, scale runtimes independently, and avoid synchronous chains. |
| Duplicate external side effects from route replay | End-to-end idempotency keys, inbox records, provider reconciliation, and controlled dead-letter replay. |
| Frontend big-bang regression | Journey inventory, vertical slices, generated contracts, cohort rollout, telemetry comparison, and gateway rollback. |
| Partial or duplicate payments | Payment intents/attempts, idempotency, webhook inbox, and provider reconciliation. |
| Lost asynchronous work | Transactional outbox, Kafka durability, idempotent consumers, and dead letters. |
| Inconsistent inventory | Select one source of truth, create opening movements, and enforce reservations. |
| Broken historical orders | Immutable snapshots and legacy raw-data retention. |
| Missing or exposed media | Checksums, classification, private buckets, signed access, dual read, and rollback retention. |
| Big-bang delivery pressure | Capability-by-capability ownership transfer and bounded tenant cohorts. |
| Migration load impacts production | Rate limits, key-range batches, monitoring, and controlled execution windows. |
| A less-visible legacy feature disappears | Production-derived coverage register; every table, route, journey, report, and manual workflow requires an approved disposition. |
| Legacy and target timers/webhooks both act | Ownership matrix, fencing order, drain/checkpoint, provider callback coordination, and duplicate-effect canaries. |
| Provider account/callback cutover is incomplete | Inventory merchant/sender/push/map/POS/delivery accounts, credentials, allowlists, DNS, contacts, and rollback constraints. |
| Courier location or notes expose PII | Purpose/consent, least privilege, encryption, precision/frequency limits, short retention, and audited access. |
| Fiscal or reporting history does not reconcile | External settlement/fiscal evidence, versioned report definitions, exact money checks, protected archive, and finance signoff. |

## 8. Immediate next deliverables

The dependency-ordered implementation design is in the
[ADR roadmap](adr/README.md), which is the authoritative execution sequence and
is organized as parallel tracks rather than a single line. ADRs 0005 through
0034 are unimplemented; an `Accepted` decision status means the design is
settled, not that code exists. Their checklists and exit criteria drive one
vertical slice at a time. For first-release scope, see
[minimum-viable-cutover.md](minimum-viable-cutover.md).

Before each capability becomes a production implementation or authoritative
writer, produce and approve its applicable items below. Pure domain work and
incremental schema slices may begin after their own model, invariants, mapping,
and transitions are accepted:

1. Domain glossary and business invariants
2. Tenant/brand/location legacy mapping
3. Customer identity policy across brands
4. Catalog ownership and location-offering policy
5. Order, payment, fulfillment, and onboarding state machines
6. Target logical ER diagram and physical schema draft
7. Production data-quality and media inventory report
8. Legacy-to-target table/field mapping matrix
9. Kafka event catalog
10. Keycloak realm, organization, client, role, customer OTP, and identity-link
    architecture decision
11. Camel route catalog, deployment topology, canonical contracts, and route
    ownership policy
12. Frontend target architecture, route/journey migration matrix, design-system
    plan, and Keycloak client map
13. S3 classification, bucket, key, lifecycle, and access policy
14. First capability and frontend-journey cutover runbook
15. Production-versus-checked-in schema/runtime difference report
16. Complete capability disposition register with no in-scope `DECIDE`
17. Scheduled-job, subscriber, webhook, provider-effect, DNS, credential, and
    report ownership matrix
18. Provider account/capability/contract/sandbox/callback/allowlist inventory
19. Privacy, consent, retention, legal-hold, fiscal, and audit policy pack
20. Numeric SLO/RPO/RTO/capacity, rehearsal, restore, rollback, support, and
    incident-ownership evidence

Only after those documents are accepted should the implementation roadmap be
converted into delivery milestones and engineering estimates.

## 9. Migration completeness gate

The planning surface is considered complete when the
[migration coverage register](migration-coverage.md) has been reconciled against
the production environment and every applicable readiness-pack artifact is
approved. That does not mean the migration has been executed. It means there is
no unowned or undisposed source, journey, external effect, or operational
dependency hidden outside the delivery plan.

For every cohort/capability, `CUTOVER_READY` requires:

- no in-scope `DECIDE`, unknown production object, or unmapped value;
- accepted domain/ADR/physical model and tested transformation;
- exact source/target/provider reconciliation at captured watermarks;
- one proven writer, reader, timer, consumer, webhook, and provider-effect owner;
- rehearsed cutover/rollback/restore with quantified stop conditions;
- security/privacy/finance/product/operations/support approvals where relevant;
- a legacy archive/retention/retirement plan with no destructive action implied.

If any condition is false, the capability remains in discovery, backfill,
shadow, canary, or blocked reconciliation. A schedule cannot waive the gate.

## 10. Reference documentation

- [Keycloak Server Administration and Organizations](https://www.keycloak.org/docs/latest/server_admin/)
- [Keycloak JavaScript adapter and PKCE](https://www.keycloak.org/securing-apps/javascript-adapter)
- [Keycloak application security overview](https://www.keycloak.org/securing-apps/overview)
- [Apache Camel](https://camel.apache.org/)
- [Apache Camel Kafka component](https://camel.apache.org/components/4.18.x/kafka-component.html)
