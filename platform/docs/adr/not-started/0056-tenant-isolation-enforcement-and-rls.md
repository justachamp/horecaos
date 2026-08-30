# ADR 0056: Application-enforced tenant isolation, with RLS as a production backstop

- Decision status: Accepted
- Implementation status: Not started — the application-enforced mechanism this record
  ratifies is built and test-verified; the RLS backstop it schedules does not exist and
  is deliberately bound to the pre-production hardening phase, not to dev/test.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (via the founding review queue), Claude
- Depends on: 0001, 0002, 0025, 0055
- Supersedes / Superseded by: —
- Open inputs: none

## Context

Tenant isolation is this platform's primary security boundary, and today it is enforced
entirely above the database: every tenant-owned query carries a tenant predicate,
composite keys carry `tenant_id`, `TenantScopedReferenceCatalogTests` interrogates
`pg_constraint` for tenant-blind foreign keys (its allowlist stands at zero and may only
shrink), and `DatabasePrivilegeTests` replays every GRANT as the application role. What
does not exist is a database-level net: a single missing `WHERE tenant_id = ?` in a new
query is a cross-tenant leak that only review or an attacker finds. The founding review
called this position defensible but undeclared. This record declares it — and prices it.

## Decision

- Application-enforced isolation, verified by the catalog test genre, **is** the
  mechanism. It is what dev/test trusts.
- PostgreSQL Row-Level Security enters as a **backstop, not a replacement**, as part of
  pre-production hardening (ADR 0055's production-deployment phase): `ENABLE ROW LEVEL
  SECURITY` with a `tenant_id = current_setting('horecaos.tenant_id')::uuid` policy on
  tenant-owned tables, the setting bound per transaction (`SET LOCAL`) by the
  transaction template, and a separate policy-exempt role for the legitimate
  cross-tenant paths (control plane, outbox relay, reporting projections, migrations).
- No production deployment ships without the backstop. Dev/test does not wait for it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| RLS now, before dev/test | Retrofitting ~300 tables and every cross-tenant path while the launch surfaces are being built front-loads the riskiest plumbing at the moment of least capacity; the application mechanism plus catalog tests already hold in dev | Never — the schedule, not the decision, is the variable |
| Application enforcement only, forever | One missed predicate in one future change is a tenant leak with no net, in a system whose whole pitch is isolation | — (rejected outright) |
| Database-per-tenant / schema-per-tenant | Rejected long ago for operability at SaaS scale (ADR 0002/0003 lineage); nothing has changed | A regulated tenant demands physical isolation |

## Consequences

### Positive

- The current position stops being implicit; reviewers cite this record, not folklore.
- Production gets defense in depth without slowing the launch build.

### Negative

- Between now and hardening, dev/test carries the single-net risk this record names.
- The backstop, when it lands, must carve out every legitimate cross-tenant reader —
  enumerating them is real work and finding one late means a production incident of the
  opposite kind (a false denial).

### Accepted trade-offs

`SET LOCAL` per transaction adds a round-trip per request path and couples correctness
to transaction discipline — acceptable because the outbox and JDBC conventions already
demand that discipline.

## Specification

Deferred to the implementing change: the policy template, the exempt role and its
GRANT surface, the transaction-template hook, and a catalog test that asserts every
tenant-owned table has exactly one policy (same genre as the reference catalog tests).

## Rollout and rollback

Rollout inside the pre-production hardening phase, schema by schema behind the catalog
test. Rollback per schema is `DISABLE ROW LEVEL SECURITY` — policies are additive and
carry no data.

## Implementation checklist

- [ ] Enumerate legitimate cross-tenant readers and writers
- [ ] Policy + exempt-role migration template; transaction-template `SET LOCAL`
- [ ] Catalog test: every tenant-owned table carries exactly one tenant policy
- [ ] Enable schema-by-schema; full suite green after each

## Exit criteria

A deliberately tenant-blind query executed as the application role in a test returns
zero rows instead of another tenant's data, and the catalog test fails any new
tenant-owned table created without a policy.

## References

- [Founding review](../../../../docs/qoida-review.md) — weakness 5
- ADR 0025 — capability model; ADR 0055 — launch phases
