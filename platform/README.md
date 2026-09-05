# HorecaOS Platform

The Java modular monolith at the center of [HorecaOS](../README.md): a multi-tenant SaaS
commerce and delivery platform for the HoReCa industry. This module also holds the ADRs,
domain docs, SDLC tooling, hygiene checks, and agent governance for the whole monorepo.

Architecture: Spring Boot on a Java modular monolith (Spring Modulith module boundaries),
Keycloak for identity, PostgreSQL as the system of record (Flyway + explicit SQL, no ORM),
Kafka for durable domain events, Apache Camel for provider integration, S3-compatible
object storage for media.

## History

HorecaOS is the successor to the Qoida platform effort, founded 2026-08-30 by importing
Qoida's groundwork into one repository. [The founding review](../docs/qoida-review.md)
is the honest account of what was inherited — strong backend, drifted documentation,
starved frontends — and why; read it before proposing a new module. Every decision since
is an ADR in [docs/adr/](docs/adr/README.md), starting with the rename
([ADR 0053](docs/adr/built/0053-horecaos-identity-and-rebrand.md)) and the current launch
scope ([ADR 0055](docs/adr/meta/0055-greenfield-launch-scope.md)). Historical documents —
the ADRs describing decisions made before the rename, the `intent/` records, the review
itself — deliberately keep the old `Qoida`/`milliy` names; that is what actually happened,
and rewriting history to match the present name would make it harder to trust.

## Technology baseline

| Component | Baseline |
|---|---|
| Language/runtime | Java 25 |
| Application | Spring Boot 4.1.0 |
| Module boundaries | Spring Modulith 2.1.0 |
| Integration runtime | Apache Camel 4.22.0 |
| API documentation | Springdoc OpenAPI 3.0.3 and Swagger UI |
| Build | Maven Wrapper 3.9.16 |
| System of record | PostgreSQL 18 |
| Persistence | Spring JDBC, explicit SQL, and Flyway migrations — no ORM |
| Durable messaging | Apache Kafka 4.3.1 |
| Identity | Keycloak 26.7.0 |
| Secrets | OpenBao (ADR 0028) |
| Object storage | S3-compatible (MinIO for local development) |

Recorded in [ADR 0001](docs/adr/built/0001-platform-foundation.md); versions verified
against `pom.xml` and `compose.yaml`.

## Get started

```bash
make up             # local PostgreSQL, Kafka, Keycloak, OpenBao, MinIO
make verify          # full build and every test — the definition of done
make run             # API on :8080, with the local-fixtures demo tenant
make seed-payments   # give that tenant a working CLICK payment setup
make format          # reformat Java sources (ADR 0054) before committing
make lint            # this repo's own rules — under a minute, no JVM
make eval            # agent-configuration regression suite
```

[Local development](docs/development.md) covers the full loop: service credentials, the
ADR 0009 Keycloak service-account step `make up` runs for you, the preset phone/OTP pair
that signs in on `local` without a real SMS gateway, and what `make seed-payments` writes.
[Local fixtures](docs/local-fixtures.md) has the demo tenant's stable IDs and ready-to-run
API requests.

Runtime API documentation, once `make run` is up:

- Swagger UI: `http://localhost:8080/swagger-ui.html` (also lists the four per-surface
  groups below)
- OpenAPI JSON/YAML: `http://localhost:8080/v3/api-docs`, `/v3/api-docs.yaml`
- Liveness/readiness: `/actuator/health/liveness`, `/actuator/health/readiness`

## Where the truth lives

This file does not carry hand-written counts or gap lists — two previous revisions
drifted behind the code and kept narrating closed gaps as open. Status lives where
tooling or adversarial review maintains it:

- [docs/adr/README.md](docs/adr/README.md) — the generated status summary. Every ADR
  carries a decision status and an implementation status; `Built` means an operator could
  use the whole feature today, and a `Partial` status line names what does not exist yet.
  Records are filed by status under [`built/`](docs/adr/built/),
  [`partial/`](docs/adr/partial/), [`not-started/`](docs/adr/not-started/).
- [docs/minimum-viable-cutover.md](docs/minimum-viable-cutover.md) — the smallest slice
  that takes a real paid order, reread as a greenfield launch per its 2026-08-30 scope
  note. This document outranks the breadth of the ADR list.
- [The founding review](../docs/qoida-review.md) — the state of the whole platform,
  backend and frontends, as of the HorecaOS import.

The frontends live in [`../frontend`](../frontend) and [`../mobile`](../mobile), a
decision recorded in [ADR 0052](docs/adr/partial/0052-one-repository-for-the-whole-platform.md);
their own READMEs carry their state.

## Module map

Each package directly under `uz.horecaos.platform` is a Spring Modulith
`@ApplicationModule` — verified against `src/main/java` and each module's own
`package-info.java`, not hand-maintained here:

```text
iam             Keycloak identity links, principals, tenant-scoped authorization
tenancy         tenants, plans, entitlements, brands, locations, resumable onboarding
customers       customer accounts, brand profiles, addresses, consent, devices
catalog         catalogs, categories, products, variants, location offerings
inventory       balances, reservations, movements, availability
pricing         prices, promotions, coupons, adjustments, redemption
ordering        carts, orders, immutable snapshots, transitions, notes
payments        payment intents, attempts, refunds, merchant accounts, reconciliation
fulfillment     shipments, couriers, delivery partners, assignments, tracking, zones
courier         courier compensation, shifts, and settlement (ADR 0042)
kitchen         production stations, routing, tickets, and release (ADR 0041)
dinein          floor plan, reservations, table session, QR entry (ADR 0047)
fiscal          the order's fiscal obligation: whether a receipt exists (ADR 0038)
loyalty         points ledger, expiring lots, split-tender redemption (ADR 0046)
pos             POS installations, capabilities, catalog sync, order export
partner         the marketplace channel and the partner API (ADR 0040)
commercial      plans, subscriptions, entitlements, append-only usage ledger (ADR 0021)
media           media assets, upload lifecycle, derivatives, retention
notifications   preferences, templates, notifications, delivery attempts
marketing       audiences, campaigns, suppression, customer metric projection
helpcenter      a brand's own FAQ and support contact points
telemetry       real-time operational push (SSE) and in-house courier field telemetry
integration     Camel routes, provider adapters, inbox/outbox, external sync
migration       the migration control plane: programs, scopes, runs, quarantine (dormant — ADR 0024/0055)
reporting       tenant-aware read models and operational reporting
audit           immutable security and business audit records
observability   what the platform measures about itself, and its probes (ADR 0023)
```

`web` (HTTP cross-cutting: correlation IDs, idempotency, authorization glue) and
`configuration` (Spring wiring, the OpenAPI surface configuration) are shared
infrastructure, not `@ApplicationModule`s of their own.

## Product model

The primary ownership hierarchy:

```text
Tenant
├── Brand A
│   ├── Location A1
│   └── Location A2
└── Brand B
    └── Location B1
```

- **Tenant:** the legal/commercial SaaS customer and isolation, billing, subscription,
  and administration boundary.
- **Brand:** a customer-facing identity operated by a tenant.
- **Location:** a physical or virtual fulfillment point operated by a brand.

The legacy `Company` concept generally maps to a brand, `Vendor` to a location; grouping
legacy companies into tenants requires an approved mapping, never a guess from names.
Full model in [ADR 0002](docs/adr/partial/0002-saas-domain-model.md) and
[docs/domains](docs/domains/README.md).

## Contract documents

[api/README.md](api/README.md) is the authoritative index: the full v1 OpenAPI baseline
plus four per-surface groups (`storefront`, `control-plane`, `operations`, `providers`),
each with its own checked-in baseline and generated TypeScript client, per
[ADR 0057](docs/adr/built/0057-openapi-per-surface-document-groups.md). `make
openapi-baseline` refreshes all five together; `make openapi-client-check` (what CI runs)
regenerates all five and fails on undocumented drift. No frontend imports a generated
client yet — tracked by [ADR 0035](docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md).

## Frontend platform

[ADR 0035](docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md)
standardizes on Angular for every web surface (`control-plane`, `operations`,
`storefront`) and Flutter for the customer mobile app (`../mobile`), superseding ADR
0022's earlier React/Next.js choice before any of it shipped. The Flutter app is on hold
for the current launch — the Angular storefront is the customer surface
([ADR 0055](docs/adr/meta/0055-greenfield-launch-scope.md)). See
[`../frontend/README.md`](../frontend/README.md) and
[`../mobile/README.md`](../mobile/README.md) for what each app actually does today.

## Integration, events, and identity — where each is decided

Rather than restate ADR content here (the predecessor's drift started exactly there):

| Concern | ADR |
|---|---|
| Keycloak tenant authorization, organizations | [0003](docs/adr/built/0003-keycloak-tenant-authorization.md) |
| Fine-grained authorization and capabilities | [0025](docs/adr/built/0025-fine-grained-authorization-and-capability-model.md) |
| Transactional outbox and Kafka delivery | [0004](docs/adr/built/0004-sql-outbox-and-kafka-delivery.md) |
| Kafka inbox and idempotent consumers | [0005](docs/adr/built/0005-kafka-inbox-and-idempotent-consumers.md) |
| Event contract governance and topics | [0032](docs/adr/built/0032-event-contract-governance-and-topic-policy.md) |
| Camel route foundation, provider contracts | [0007](docs/adr/partial/0007-camel-route-foundation-and-provider-contract-testing.md) |
| Provider installations, bindings, secrets | [0026](docs/adr/built/0026-provider-installations-bindings-and-secret-references.md), [0028](docs/adr/partial/0028-secrets-management-and-credential-lifecycle.md) |
| S3 media lifecycle | [0010](docs/adr/partial/0010-s3-media-lifecycle-and-filesystem-migration.md), [0029](docs/adr/partial/0029-pii-protection-envelope-encryption-and-key-rotation.md) |
| Tenant isolation enforcement, RLS backstop | [0056](docs/adr/partial/0056-tenant-isolation-enforcement-and-rls.md) |
| Build-time quality gates | [0054](docs/adr/built/0054-build-time-quality-gates.md) |

## The legacy migration program

Migrating existing restaurants off the legacy `milliy` system is a separate program that
starts once production exists for greenfield tenants
([ADR 0055](docs/adr/meta/0055-greenfield-launch-scope.md)). It is not in scope for the
current build and gates nothing in it. When it starts:
[docs/migration-plan.md](docs/migration-plan.md) is the workstream sequence,
[docs/migration-coverage.md](docs/migration-coverage.md) is the legacy-source readiness
register, and [ADR 0024](docs/adr/partial/0024-legacy-data-migration-cutover-and-retirement.md)
is the decision record. Both documents carry a banner dating their last verification.

## Documentation

- [Architecture decisions, status model, and roadmap](docs/adr/README.md)
- [Minimum viable cutover](docs/minimum-viable-cutover.md) — the smallest slice that can
  take a real order, reread as a greenfield launch
- [Domain model, ERD, state machines, and processes](docs/domains/README.md)
- [Local development](docs/development.md)
- [Agent and implementation rules](AGENTS.md)
- [The HorecaOS SDLC](docs/sdlc.md) — how a change moves from `intent.md` through
  `spec.md`, `plan.md`, review, and release, and where a person decides
- [Review policy](REVIEW.md) — what every pull request is reviewed against
- [Runbooks](docs/runbooks/README.md) — operational procedures
