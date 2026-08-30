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
mobile/       Flutter customer application
docs/         Monorepo-level documents (founding review, workspace plans)
```

The platform tree still carries its Qoida-era identifiers; the rename is
[ADR 0053](platform/docs/adr/not-started/0053-horecaos-identity-and-rebrand.md) and is
deliberately the first change, not the last.

## Get started

Install JDK 25, Docker, and Node 22+, then:

```bash
make up       # local PostgreSQL, Kafka, Keycloak (in platform/)
make verify   # full backend build and every test — the definition of done
make run      # API on :8080 with local fixture data
```

Per-surface commands live in each project's own README. The root Makefile only composes;
it never replaces a project's build.

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
