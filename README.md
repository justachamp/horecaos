# HorecaOS

HorecaOS is a multi-tenant SaaS commerce and delivery platform for the HoReCa industry
(hotels, restaurants, cafés). It is the successor to the Qoida platform effort, founded
2026-08-30 by importing Qoida's groundwork into one repository — the review that led to
this structure is in [docs/qoida-review.md](docs/qoida-review.md), and the decision to be
a monorepo is [ADR 0052](platform/docs/adr/partial/0052-one-repository-for-the-whole-platform.md).

## Layout

```text
platform/     Java 25 modular monolith — Spring Boot 4.1, Spring Modulith, PostgreSQL,
              Kafka, Keycloak, Apache Camel. Also home of the ADRs, domain docs, SDLC
              tooling, hygiene checks, and agent governance. `make verify` runs here.
frontend/
  design-tokens/   THE canonical tokens.css — every app consumes this one file
  control-plane/   Angular — platform administration
  operations/      Angular — brand and location operations
  storefront/      Angular — customer storefront
mobile/       Flutter customer application — on hold for launch (ADR 0055); Angular
              storefront is the customer surface until it resumes
docs/         Monorepo-level documents — the founding review
```

The platform's code identity is HorecaOS throughout — package root `uz.horecaos`,
domain `horecaos.uz` — renamed in one mechanical commit as
[ADR 0053](platform/docs/adr/built/0053-horecaos-identity-and-rebrand.md) records.
Historical documents and legacy artifact names deliberately keep the old name; the
frontend apps' own identifiers are the next pass.

## Get started

Install JDK 25, Docker, and Node 22+, then:

```bash
make up       # local PostgreSQL, Kafka, Keycloak (in platform/)
make verify   # full backend build and every test — the definition of done
make run      # API on :8080 with local fixture data
```

Per-surface commands live in each project's own README. The root Makefile only composes;
it never replaces a project's build — `platform/Makefile` has the rest, including
`make format` and `make seed-payments` (run from `platform/`, or `make -C platform ...`
from here).

## Current phase

HorecaOS launches greenfield: the first production tenants are onboarded natively, with
no legacy data, identity, or traffic in scope
([ADR 0055](platform/docs/adr/meta/0055-greenfield-launch-scope.md)). Migrating existing
restaurants off the legacy system is a separate program that starts only once production
exists for greenfield tenants. The launch path — storefront, then the Operations
application, then payments, then tenant onboarding, proven in dev/test before production
is even planned — is ADR 0055's own specification; read it rather than a restatement
here, since a copy is exactly what goes stale.

Build-time quality gates ([ADR 0054](platform/docs/adr/built/0054-build-time-quality-gates.md)),
the per-surface OpenAPI contract ([ADR 0057](platform/docs/adr/built/0057-openapi-per-surface-document-groups.md)),
and the tenant-isolation stance — application-enforced now, PostgreSQL row-level security
as a pre-production backstop
([ADR 0056](platform/docs/adr/not-started/0056-tenant-isolation-enforcement-and-rls.md)) —
are the other three decisions from this phase worth knowing before touching the backend.

## Where the truth lives

- **What is decided and how much of it exists:**
  [platform/docs/adr/README.md](platform/docs/adr/README.md) — every record carries a
  decision status *and* an implementation status, and `Built` means an operator could use
  the whole feature today. Trust the status lines; they are maintained adversarially.
- **What ships first:**
  [platform/docs/minimum-viable-cutover.md](platform/docs/minimum-viable-cutover.md) —
  the smallest slice that takes a real paid order. This document outranks the breadth of
  the ADR list; if a change does not serve a stage in it, ask why it is being made.
- **How a change happens:** [platform/docs/sdlc.md](platform/docs/sdlc.md) —
  intent → spec → plan → build → verify, with human gates.
- **Why the predecessor stalled:** [docs/qoida-review.md](docs/qoida-review.md) — read it
  before proposing a new module.
