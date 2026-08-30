# Spec: <short title>

- **Intent:** [`intent.md`](intent.md)
- **Author:** <name or "Claude, reviewed by <name>">
- **Date:** <YYYY-MM-DD>
- **Status:** Draft | Approved | Rejected
- **Approver:** <product owner, or technical lead for higher-risk changes>

## Summary

What is being built, in one paragraph, and how it satisfies the intent.

## Requirements

Numbered and testable. Each one should be something stage 4 can prove.

1. …

## Non-goals

What this deliberately does not do, and where that work belongs instead.

## Design

### Domain impact

Aggregates, invariants, and state transitions touched. Reconcile against
[docs/domains](../../docs/domains/README.md) — a table or aggregate absent from the
approved model is a finding, not a design choice.

### Data

New or changed tables, tenant-scoping columns and constraints, the Flyway migration
number, and the GRANT for the application role. Migrations are append-only.

### API

Endpoints, capabilities each mutation declares (ADR 0025), Problem Details codes,
idempotency and expected-version behaviour (ADR 0031).

### Events

Topics produced or consumed, envelope fields, partition key, schema file and catalogue
entry (ADR 0032). Both exist before a producer ships.

### Integration

Camel routes, provider capabilities, provider bindings (ADR 0026). Providers are ports
and adapters; the core learns no provider names.

## Policy review

Filled in against `.claude/skills/`. Mark each **Satisfied**, **Not applicable**, or
**Needs decision** — and never leave one blank.

| Area | Verdict | Note |
|---|---|---|
| Tenant isolation | | |
| Authorization capability (ADR 0025) | | |
| Secrets and PII (ADR 0028, 0029) | | |
| HTTP conventions (ADR 0031) | | |
| Event contracts (ADR 0032) | | |
| Migration safety | | |
| Observability and audit (ADR 0027) | | |

## ADR impact

Does this need a new ADR, or does it implement an accepted one? Name it. If it
contradicts an accepted ADR, that is a superseding ADR, never an in-place edit.

## Rollout and rollback

Feature flag or cohort, migration phase (expand / migrate / verify / cut over / contract),
and how to get back. A change with no rollback route is not approved.

## Acceptance criteria

What stage 4 runs to prove each requirement. Name the tests.

## Open questions

Blocking items with a named owner. Stage 3 does not start on a blocking open question.

| Question | Owner | Blocking? |
|---|---|---|
