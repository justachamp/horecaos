# ADR 0056: Application-enforced tenant isolation, with RLS as a production backstop

- Decision status: Accepted
- Implementation status: Partial — the application-enforced mechanism this record
  ratifies is built and test-verified; the RLS backstop now exists and is proven end to
  end on one schema. `inventory` (5 tables: `stock_items`, `positions`, `movements`,
  `reservations`, `reservation_lines`) enforces row-level security behind the session
  GUC, the `uz.horecaos.platform.configuration.rls` transaction-template binder, the
  `horecaos_platform_bypass` exempt role, and a catalog test in that same genre
  (`RowLevelSecurityBackstopTests`). 274 of the platform's 279 tenant-owned tables, and
  nearly all of its ~30 scheduled cross-tenant jobs, are not yet migrated — see
  Specification for the inventory it now has and Rollout for what "schema by schema"
  means for the waves after this one.
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

Built by wave 56, on `inventory`:

- **The GUC** is `horecaos.tenant_id`. A dotted name is a PostgreSQL "placeholder"
  setting — any session may `SET`/`set_config` it with no `shared_preload_libraries`
  registration, and `current_setting(name, true)` answers rather than raises for a
  session that has never touched it.
- **The policy template** is `platform.enable_tenant_row_level_security(target
  regclass)` (V0161): `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` plus one policy,
  `USING (tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid)`.
  Owned by `horecaos_migrator`, callable only from a migration — never `SECURITY
  DEFINER`, never granted to the application, for the reason V0075/V0070 already cost
  this platform once. **Not `FORCE ROW LEVEL SECURITY`**: PostgreSQL already exempts a
  table's owner from its own policies, and the migrator owns every table this template
  is ever pointed at, so the exemption a maintenance path needs is free by construction
  — FORCE would only take it away again.
  - Non-obvious and worth recording: a reverted `SET LOCAL` does not read back as SQL
    `NULL`. The first `SET`/`set_config` of a placeholder creates it for the session,
    and reverting at `COMMIT` restores whatever the *session-level* value was, which
    for a placeholder nothing has bound at session scope is the empty string. The
    `NULLIF(..., '')` above is load-bearing, not defensive decoration —
    `RowLevelSecurityBackstopTests.theBoundTenantDoesNotLeakAcrossPooledTransactions`
    caught this the first time the test was written to assert literal `NULL`.
- **The transaction-template hook** is `uz.horecaos.platform.configuration.rls`:
  `TenantRlsSession` (`bindTenant(UUID)`, `bindPlatform()`) and its implementation
  `JdbcTenantRlsSession`, which issues `SELECT set_config('horecaos.tenant_id', ?,
  true)` / `SET LOCAL ROLE horecaos_platform_bypass` through the same `JdbcClient` — and
  therefore, inside a Spring-managed transaction, the same pooled connection — the
  rest of the method's repository calls use. **Deliberately not a global interceptor or
  a custom `TransactionManager`**: a Spring `@Transactional` proxy begins the
  transaction before the method body runs, so nothing outside the method can know
  which tenant to bind. `InventoryService`'s five `@Transactional` methods each call
  `rls.bindTenant(tenantId)` as their first statement, using the `tenantId` their
  caller already validated (a path variable checked against the actor's capability by
  `CapabilityEnforcementInterceptor` before the controller method ever runs).
  `expireStaleReservations()` — inventory's one cross-tenant statement, a single
  `UPDATE` that sweeps every tenant's stale holds — calls `rls.bindPlatform()` instead.
- **The exempt role** is `horecaos_platform_bypass`: `NOLOGIN BYPASSRLS`, a member of
  `horecaos_application` (inherits its table grants), granted to `horecaos_app` `WITH
  INHERIT FALSE`. `BYPASSRLS` is all-or-nothing — PostgreSQL has no per-table bypass
  grant — so the narrowing is in who may become the role and for how long, not in what
  it is granted on: holding the membership changes nothing about an ordinary connection,
  and `SET LOCAL ROLE horecaos_platform_bypass` (`bindPlatform()`) is scoped to one
  transaction exactly like the tenant GUC, for the identical pooled-connection reason.
  `horecaos_app` cannot be named from inside a Flyway migration — it is created only by
  `infra/production/postgres-init/10-application-role.sh`, which never runs against a
  Testcontainers database — so `horecaos_platform_bypass` is created in both places
  (guarded, mirroring `horecaos_application`/`horecaos_reporting_read`) and the grant
  that needs `horecaos_app` to exist lives only in the script, next to the identical
  grant it already makes for `horecaos_application`.
- **The catalog test** is `RowLevelSecurityBackstopTests.everyInventoryTableCarriesExactlyOnePolicy`,
  reading `pg_class.relrowsecurity`/`relforcerowsecurity` and `pg_policy` directly —
  same genre and same "asked of PostgreSQL, not of the migration source" argument as
  `TenantScopedReferenceCatalogTests`. Its table list (`RLS_PROTECTED_INVENTORY_TABLES`)
  is a positive inventory rather than a negative allowlist of the 274 tables not yet
  covered — the shape that would need is a 274-entry list on day one of a schema-by-
  schema rollout, which is the opposite of what an allowlist is for. This list may only
  **grow**; each future wave's migrated schema is added to it, never removed.

**Legitimate cross-tenant readers and writers**, enumerated by reading the scheduled
jobs rather than guessing (`grep -rl '@Scheduled' src/main/java`, ~30 classes across
nearly every module) — not yet retrofitted outside inventory, and left here as the
worked inventory the next wave starts from:

- **Single cross-tenant statement per pass** (need `bindPlatform()`): `OutboxRelay`
  (claims a batch of outbox events across every tenant in one lease query),
  `InboxRetryWorker`, `AuditPartitionManager` / `ReportingPartitionManager` /
  `TrackRetentionSweeper` (partition maintenance, already `SECURITY DEFINER`-fenced —
  see V0075/V0080 — and so already privilege-narrow in a different way), `IdempotencyPurgeJob`,
  the various `*Sweeper` classes (`ApprovalExpirySweeper`, `ConversationRetentionSweeper`,
  `CustomerSessionSweeper`, `VerificationChallengeSweeper`, `LoyaltySweeper`, and more).
- **Already loop per tenant** (need only `bindTenant(tenantId)` inside the loop, no
  bypass role at all): `DayCloseScheduler` — its own doc comment already says "per-tenant,
  on the tenant's own business-day clock ... polls all of them on one timer rather than
  enumerating cron expressions per tenant."
- **Genuinely platform-level, not cross-tenant-by-omission**: `OnboardingScheduler`,
  `IdentityDriftReporter` (compares Keycloak against `tenant.tenants` itself, a table
  this record does not scope), `RealtimeStreamMaintenance`.

None of the above is migrated yet. Each carries the same choice inventory's one sweep
did — loop and bind, or bypass — and the choice is a property of the query, not a
platform-wide default.

**Deliberately not tenant-scoped**, confirmed against the schema rather than assumed:
`tenant.tenants` (the tenant identity table itself — `id` *is* the tenant, there is no
separate `tenant_id` to scope by), `commercial.plans`/`plan_versions`/`plan_entitlements`
(the SaaS plan catalogue, shared across tenants by design), `catalog.mxik_reference`
(government tax classification, platform-level), `iam.capability_registry_snapshot` /
`iam.role_capabilities` (the code-owned capability registry, replaced wholesale on
startup, not tenant data), `iam.principals`, `integration.provider_environments` /
`pos_provider_capabilities`, `tenant.onboarding_templates`, `tenant.service_schedule_rules`
/ `service_schedule_exceptions` (tenant-scoped through their parent `tenant.service_schedules`
row, not a direct column — a real gap in the *column* sense `TenantScopedReferenceCatalogTests`
already tracks, orthogonal to this record), `reporting.metric_definitions`, `ordering.order_reject_reasons`
/ `order_reject_reason_texts` / `order_outcome_reason_texts` (curated platform reason
catalogues), and the dormant `migration.*` control-plane tables (ADR 0055 — the migration
program has not started).

**A shape this template does not cover**: `iam.roles` and `iam.grants` carry a
*nullable* `tenant_id` — `NULL` for a platform-defined role/grant, non-null for a
tenant-defined one, by `ck_role_ownership`'s own constraint. `platform.enable_tenant_row_level_security`
would hide the platform rows from every tenant context, which is wrong: a tenant has to
see the platform-defined roles it can grant, not only its own. These two need
`tenant_id = :guc OR tenant_id IS NULL` and must not be pointed at the shared function
unmodified — noted in the function's own `COMMENT ON`, not yet built.

## Rollout and rollback

Rollout inside the pre-production hardening phase, schema by schema behind the catalog
test, as specified. `inventory` is schema one of several: chosen because grepping
`src/main/java` found no module outside `uz.horecaos.platform.inventory` reading or
writing an `inventory.*` table directly (every other module reaches it through
`InventoryReservationPort`/`StockAvailabilityPort`), and because it has exactly one
cross-tenant statement to retrofit rather than several — a schema to prove the
mechanism on, not a hard case to prove it against. V0161 (role and template) and V0162
(the five `ENABLE ROW LEVEL SECURITY` statements) are separate migrations on purpose:
V0161 changes nothing observable by itself, so the schema that actually turns
enforcement on is reviewed for that alone.

Rollback per schema is `DISABLE ROW LEVEL SECURITY` — policies are additive and carry
no data, so a forward migration issuing five `ALTER TABLE ... DISABLE ROW LEVEL
SECURITY` statements is all `inventory`'s rollback would need. Not exercised in this
wave (nothing forced it), but the same property the ADR's rollout section already
claims: nothing about V0161 or V0162 altered a grant, a column, or a row, so disabling
is symmetric with enabling.

## Implementation checklist

- [x] Enumerate legitimate cross-tenant readers and writers — inventory's one
      (`expireStaleReservations`) is retrofitted; ~30 more across the rest of the
      platform are catalogued in Specification, by category, not yet retrofitted
- [x] Policy + exempt-role migration template; transaction-template `SET LOCAL` —
      `platform.enable_tenant_row_level_security` (V0161), `horecaos_platform_bypass`
      (V0161 + the init script), `uz.horecaos.platform.configuration.rls`
- [x] Catalog test: every tenant-owned table carries exactly one tenant policy — built,
      in the "positive list that may only grow" shape, and true for every table it
      currently names (5 of 279)
- [ ] Enable schema-by-schema; full suite green after each — one schema
      (`inventory`) done; 274 tenant-owned tables across roughly twenty more schemas
      remain, each its own wave

## Exit criteria

Met for `inventory`: `RowLevelSecurityBackstopTests` runs a deliberately tenant-blind
query as the application role and gets zero rows for another tenant's data, and the
catalog test fails if a policy is missing or malformed on any of the five tables it
names. Not yet met platform-wide — that is what the unchecked box above tracks.

## References

- [Founding review](../../../../docs/qoida-review.md) — weakness 5
- ADR 0025 — capability model; ADR 0055 — launch phases
