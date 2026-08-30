---
name: tenant-isolation
description: Use whenever writing or changing a database table, SQL query, cache key, Kafka envelope, S3 object key, log statement, background job, import, or export in Qoida Platform. Tenant isolation is the platform's primary security boundary and is easy to break silently.
---

# Tenant isolation

Qoida is `Tenant -> Brand -> Location`. A tenant is the isolation, billing, and
administration boundary. Leaking across it is the platform's worst failure mode, and
nothing in a passing test suite catches it unless the negative case was written.

## Rules

- Every tenant-owned row carries a **non-null `tenant_id`**.
- Unique constraints and foreign keys **include `tenant_id`**, so the database — not the
  service layer — rejects a cross-tenant relationship. Prefer composite keys.
- Every query filters by tenant. A `WHERE id = ?` with no tenant predicate is a finding
  even when the id is a UUID.
- Tenant context comes from **authenticated identity** — the signed `organization` claim
  matched to the tenant record — or a verified domain. Never from a request header, query
  parameter, or request body.
- Authentication is not authorization. Every mutating endpoint declares a capability
  (ADR 0025). Organization membership alone authorizes nothing, including reads.

## Everything else that is tenant-scoped

Easy to forget, and each has caused a real leak somewhere:

| Surface | Requirement |
|---|---|
| Cache keys | `tenant_id` in the key. Registered accelerators only; no correctness decision reads cache state (ADR 0033). |
| Kafka envelopes | `tenantId` on every external event. |
| S3 object keys | `tenants/{tenantId}/...`, generated, never from an uploaded filename. |
| Logs, traces, metrics | Tenant and correlation IDs present; no PII in any of them. |
| Background jobs, imports, exports | Scoped per tenant, including the failure and retry paths. |
| Dead letters | Tenant-scoped, and the summary carries no personal data. |

Use PostgreSQL row-level security as defence in depth where practical. It does not
replace authorization in application services.

## Before saying it is done

- [ ] A **negative test** proves tenant B cannot read or mutate tenant A's record, and it
      fails if the tenant predicate is removed. This is the test that matters.
- [ ] Database constraints reject the cross-tenant relationship, tested directly.
- [ ] Every new surface above is scoped, not just the table.
- [ ] `python3 tools/checks/repo_hygiene.py` passes.

## Reject

Any design where tenant scope is enforced only in application code and the schema would
happily store a cross-tenant row. If the database permits it, it will eventually happen.
