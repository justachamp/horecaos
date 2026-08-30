# Qoida Platform

**Architecture, domain, and migration rules live in [AGENTS.md](AGENTS.md). Read it before
any change that touches schema, module boundaries, integration, or migration.** This file
holds only what Claude needs in every session.

## Commands

```bash
make verify           # full build + all tests — the definition of "done"
make lint             # this repo's own rules — under a minute, no JVM. Run it often.
make test             # tests only
make arch             # Spring Modulith boundary + architecture tests
make up / make down   # local Postgres, Kafka, Keycloak
make run              # start the API on :8080
make eval             # agent-configuration regression suite (evals/README.md)

tools/mvn-serial …    # Maven behind a lock. Several agents share one target/;
                      # running mvn directly corrupts it. Waiting is not a hang.
tools/file-adrs       # re-file ADRs into docs/adr/<status>/ and fix every link
tools/adr-index       # regenerate the status summary in docs/adr/README.md
```

`make verify` must exit zero before a change is offered for review. Run it yourself; do
not ask a human to check.

## Stack

Java 25, Spring Boot 4.1, Spring Modulith 2.1, Apache Camel 4.22, PostgreSQL 18 (Flyway +
Spring JDBC with explicit SQL — **no ORM**), Kafka 4.3, Keycloak 26.7, S3-compatible storage.

## Conventions Claude gets wrong

- **Never edit an applied Flyway migration.** `src/main/resources/db/migration/` is
  append-only. Add `V00NN__<snake_case>.sql` with the next free number. Every new table
  needs a `GRANT` for the application role — nine migrations forgot, see V0035.
- **A foreign key must reference a unique constraint on exactly its own columns.** A
  three-column unique does not satisfy a two-column reference; V0046 had to add one.
- **Never edit an `Accepted` ADR to change its decision.** Write a new ADR, set
  `Superseded by` on the old one. Both status fields (decision + implementation) are real,
  and a hook enforces this — advancing `Implementation status` is allowed, rewriting the
  argument is not.
- **ADRs are filed by implementation status**, under `docs/adr/built/`, `partial/`,
  `not-started/` and `meta/`. The directory is derived from the record's own status line,
  so change the line and run `tools/file-adrs`; never move a record by hand, because the
  links between records are relative and there are over two hundred of them. The status
  line is machine-read: it begins with `Built`, `Partial`, `Not started` or
  `Not applicable`, then an em dash, then what exists and what does not.
- **`Built` means an operator could use the whole feature today.** A module that exists but
  that nothing calls is `Partial`, and its status line has to say so. Most records are
  `Partial` — the generated summary in docs/adr/README.md has the current counts; never
  write counts by hand, here or anywhere. Read the status line of the record you are
  touching before assuming its subject works.
- **Every tenant-owned row carries a non-null `tenant_id`**, and unique/foreign keys
  include it. Cache keys, S3 object keys, Kafka envelopes, and logs are tenant-scoped too.
- **Every mutating endpoint declares a capability** (ADR 0025). Organization membership
  alone authorizes nothing.
- **Money is integer minor units + ISO currency code.** Never floating point. Instants are
  UTC `timestamptz`; a location's IANA timezone is a separate column.
- **Domain code imports no Spring MVC, Camel, Kafka, S3, or provider SDK types.** Provider
  DTOs stay inside their adapter. No `if (provider == CLICK)` in core.
- **No direct Kafka publish inside a business transaction.** Write an outbox row in the
  same transaction; the relay publishes it.
- **Secrets are references, not values** (ADR 0028). PII is envelope-encrypted (ADR 0029)
  and never reaches an event, log, trace, metric, or dead-letter summary.
- Prefer the existing shared models (approval, audit, provider binding, policy resolution)
  over a module-local reinvention. AGENTS.md lists which ADR owns each.
- **A green test is evidence about the test, not about the code.** The audit of
  2026-08-26 found twenty-odd defects under a passing suite, and they failed the same
  way every time: the guard that existed was checking the adjacent quantity.
  `balance == SUM(lots.remaining)` held through every loyalty money bug while
  `balance - SUM(entries)` was the one that broke; `atMostOneVersionIsInForce`
  asserted *at most* and never *at least*; the GRANT lint checked a role the
  application did not connect as; the ADR link checker matched only markdown targets.
  When you write an assertion, say out loud what would still be true if the code were
  broken — and when a test passes, check it can fail.
- **A fixture that does in setup what production never does proves nothing.**
  `RefundAndRemedyTests` planned its own settlement, so it passed for a year while
  every real refund threw. `theCourierCollectsTheRightCash` settled a tender by hand
  in a sequence production cannot produce. And the clock: a fixture's clock is the
  test's clock, so a lifetime, lease, expiry or sweep asserted without advancing it is
  asserted against an instant, not a duration.

## Definition of done

Tests, migration impact, observability, tenant isolation, rollback behavior, and docs are
all addressed — and `make verify` passes. See [docs/sdlc.md](docs/sdlc.md) for how a
change moves from `intent.md` through `spec.md`, `plan.md`, review, and release.
