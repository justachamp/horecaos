# ADR 0024: Legacy data migration, cutover, and retirement

- Decision status: Accepted
- Implementation status: Partial — V0024 builds the control plane (programs, scopes,
  runs, entity mappings, quarantine, reconciliation results, cutover decisions), the scope
  state machine in `migration.domain.ScopeStateMachine`, and the single-writer ownership
  gate `MigrationOwnershipPort`. V0044 builds the half that moves data: restartable keyset
  extraction over `migration.source_cursors`, the transformation version registry, the
  import-port contract, and the reconciliation rule library seeded with four rules at
  version 1, with `ImportContext` suppression consulted by every adapter enumerated in
  `ExternalEffect`. Five platform-admin controllers expose programs, scopes, transitions,
  suspend/resume, cutover decisions, runs, quarantine resolution and ownership lookup,
  across ninety-four Java files in `uz.horecaos.platform.migration`, with ten test classes
  and the shared `MigrationControlPlaneFixture`. Not built: any import port beyond the one
  reference port `LegacyBrandImportPort` (legacy `companies` to brands), so no wave can
  actually be migrated; the security, media, catalog and golden-cart reconciliation
  suites; wave dashboards — the Angular control-plane app routes only to an overview and a
  tenants list, and has no migration section at all; and every rehearsal, cutover and
  retirement step, all of which are operational acts rather than code.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), product, finance
- Depends on: ADR 0005–0023 and `docs/migration-plan.md`
- Supersedes / Superseded by: —
- Open inputs: Every `DECIDE` row in `docs/migration-coverage.md` (product, legal, finance)

## Context

The target product spans tenancy, Keycloak, customer identity, brand catalogs,
S3 media, inventory, pricing, orders, payments, POS, delivery, notifications,
SaaS entitlements, and new frontends. Final success requires moving historical
and in-flight facts without losing tenant boundaries, money, customer access,
or provider references. A one-time script and big-bang switch cannot provide
restartability, reconciliation, rollback, or single-writer safety.

## Decision

Run an entity- and journey-based strangler migration controlled by a durable
migration control plane. Every capability has an explicit legacy/target reader
and writer owner. Bulk backfill, incremental change capture, shadow validation,
canary routing, cutover, rollback window, freeze, and retirement are separate
gates with signed evidence.

No legacy system is decommissioned because a copy job finished. Retirement
requires business reconciliation, in-flight process settlement, support and
security approval, retained audit/export access, and proven rollback/restore.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Big-bang cutover on a weekend | No rollback, no reconciliation, no way to isolate which capability failed, and the business stops selling if anything goes wrong | Never |
| Dual write from request handlers to legacy and target | Two authorities that diverge on the first partial failure, with no way to decide which is right afterwards | Never |
| Change data capture writing directly into target tables | Bypasses every target invariant, validation, and audit path that the last twenty ADRs exist to enforce. Change capture is retained as a source of change events feeding import ports, not as a writer | Never as a direct writer |
| Migrate table by table rather than by journey | Produces half-migrated journeys with two writers and no coherent rollback unit. Ownership transfers per capability, not per table | Never |
| Keep the legacy system running indefinitely as a read-only fallback | Ongoing cost, an unpatched security surface, and reference data that drifts away from the target over time | Never as a permanent state; the rollback window is bounded |
| Trust row counts as reconciliation evidence | Counts match while money, ancestry, and status are wrong. Reconciliation compares keys, totals by currency and provider, checksums, and invariants | Never |
| Transfer in-flight orders to the target writer by default | An order mid-payment or mid-booking has uncertain external state. Draining under the legacy owner is safer and usually possible | Only where draining is impossible, with a signed per-state handoff |
| Delete legacy data once cutover succeeds | Retirement requires retained audit and export access, and deletion is a separate approved action with a recoverability statement | Never as an automatic step |

## Migration control model

```text
migration.programs
  id, name, status, source_environment, target_environment
  policy_version, started_at, completed_at null

migration.scopes
  id, program_id, tenant_id, brand_id null, location_id null
  capability, source_owner, target_owner, read_mode, write_mode
  status, checkpoint, version, timestamps

migration.runs
  id, scope_id, run_type, source_watermark, target_watermark
  status, started_by, started_at, finished_at null
  scanned/created/updated/skipped/quarantined counts, checksum

migration.entity_mappings
  id, scope_id, entity_type, legacy_id, target_id
  source_version, target_version, mapping_status, timestamps
  unique(scope_id, entity_type, legacy_id)

migration.quarantine_items
  id, run_id, entity_type, legacy_id, reason_code
  sanitized_evidence_reference, status, resolution, timestamps

migration.reconciliation_results
  id, run_id, rule_code, severity, expected, actual, difference
  sample_reference, status, approved_by null, timestamps

migration.cutover_decisions
  id, scope_id, from_mode, to_mode, decision, reason
  evidence_snapshot, requested_by, approved_by, timestamps
```

Control data is isolated from business schemas. Sensitive source payloads are
not copied into generic JSON; quarantine stores a protected reference and
sanitized diagnostic evidence.

## Scope state machine

```text
DISCOVERY -> MAPPING_APPROVED -> BACKFILLING -> CATCHING_UP
          -> SHADOW_READING -> CANARY -> CUTOVER_READY -> TARGET_OWNED
TARGET_OWNED -> ROLLBACK_WINDOW -> LEGACY_READ_ONLY -> RETIRED

Any nonterminal state -> PAUSED | BLOCKED_RECONCILIATION
CANARY/TARGET_OWNED -> ROLLING_BACK when the approved gate allows it
```

Transitions use expected versions, approval policy, checkpoint evidence, and
idempotency keys. A platform-admin UI cannot skip required reconciliation or
invent a target owner.

## Ownership modes

Each capability/scope has exactly one write mode:

```text
LEGACY_ONLY
LEGACY_WITH_TARGET_SHADOW
TARGET_ONLY
```

Dual write from a request handler is forbidden. During catch-up, a controlled
replicator transforms committed legacy changes into idempotent target commands
or imports. After target cutover, any reverse feed exists only as an explicitly
approved rollback compatibility mechanism and cannot create two authorities.

Reads may be legacy, target shadow-compared, canary-target, or target. Shadow
differences are recorded without returning target data to the caller.

## Data movement pattern

For every entity family:

1. Discover source tables/files/APIs, ownership, volumes, change rate, data
   quality, retention, and tenant-key reliability.
2. Approve field mapping, defaults, transformations, stable ID policy, and
   quarantine reasons in `docs/domains/legacy-mapping.md`.
3. Extract pages using a stable key/watermark and read-only source access.
4. Transform into domain commands/import records; never bypass target invariants
   with ad hoc target SQL unless an approved import port provides equivalent
   validation/audit.
5. Upsert using source identity/version and checkpoint only after target commit.
6. Re-read target results and run structural/business reconciliation.
7. Catch up changes, shadow reads, canary, then transfer writer ownership.

Runs are restartable and safe to repeat. Transformation version is recorded so
a changed mapping creates an explicit remediation run rather than silently
mixing semantics.

## Dependency-aware migration waves

Recommended order:

```text
Wave 0  production schema/runtime/journey/provider inventory and disposition
Wave 1  tenants -> brands -> locations -> memberships -> Keycloak links
Wave 2  media -> S3; catalog/content/merchandising decisions and publications
Wave 3  customers/consent/addresses/devices plus engagement-feature dispositions
Wave 4  POS mappings/staging, prices/promotions/tax, inventory, offerings
Wave 5  historical orders/payments/fiscal receipts/recovery/delivery/courier facts
Wave 6  live cart/checkout/order/payment/POS/delivery/notification/courier journeys
Wave 7  subscriptions/usage, reports/audit/privacy operations, target frontends
Wave 8  jobs/webhooks/DNS/provider ownership freeze, archive and retirement
```

Historical import never replays customer messages, captures payments, books
couriers, exports POS orders, consumes benefits, or changes inventory. Import
ports explicitly suppress side effects while retaining historical evidence.

## Identity and tenancy reconciliation

- Establish tenant/brand/location crosswalks before child data.
- Rows without provable tenant ownership are quarantined, never assigned to a
  convenient default tenant.
- Keycloak subjects link through ADR 0015 principal links; duplicate emails or
  phones do not auto-merge people.
- Shared-versus-isolated customer mode is applied using the tenant's approved
  policy and version; conflicts enter merge review.
- User memberships are reconciled between Keycloak and PostgreSQL before route
  migration; orphan/admin privilege is a critical blocker.

## Media migration

Follow ADR 0010: inventory filesystem objects, normalize safe relative paths,
scan/classify, upload privately with content hash and metadata, verify read via
the media API, then switch references. Missing, corrupt, unsafe, or ambiguous
files are quarantined. Legacy files remain read-only through the rollback and
retention window and are deleted only under a separately approved destructive
runbook.

## Catalog, pricing, and inventory reconciliation

Structural checks include entity/mapping counts, parent links, publication
hashes, translations, offerings, media references, prices/currencies, promotion
versions, positions, and ledger baselines. Business probes render production-
shaped storefront menus and calculate golden carts in both systems.

Legacy stock baseline becomes an explicit opening movement. Catch-up changes
become later movements; the migrator never overwrites active reservations.
POS staging may continue, but only the selected catalog/inventory owner applies
changes.

## Orders, payments, and in-flight processes

Historical completed orders are imported as immutable snapshots with source IDs
and no side effects. Financial reconciliation proves, per currency/provider:

```text
order totals
authorized/captured/charged amounts
completed and pending refunds
provider external references and status
delivery fees/subsidies
```

In-flight orders are classified by state. Prefer draining them under the legacy
owner while new orders use target ownership. If live transfer is unavoidable,
define a per-state handoff with provider query/reconciliation, inventory
reservation mapping, process checkpoint, timeout ownership, and a signed
single-writer barrier. Never transfer an uncertain payment/refund/booking until
its provider outcome is reconciled.

## Frontend and API cutover

Route by complete journey and tenant/brand/location capability, as ADR 0022
requires. Preserve public URLs, redirect/SEO rules, sessions where safely
possible, analytics event definitions, and support diagnostics. Existing
browser sessions reauthenticate to Keycloak; legacy credentials are not copied
as reversible passwords.

API compatibility can route old clients through a bounded facade, but the
facade maps to the one target writer. Publish deprecation dates and observe
client usage before removing endpoints.

## Reconciliation gates

Every wave defines exact zero-tolerance and approved-tolerance rules. Mandatory
examples:

- zero cross-tenant or invalid ancestry references;
- exact count/checksum for authoritative IDs and external mappings;
- exact money totals by currency/provider/status;
- exact active membership/admin scope after Keycloak reconciliation;
- no missing active catalog media or invalid publication reference;
- no unexplained in-flight order, payment, refund, POS export, or shipment;
- bounded documented differences for derived analytics only.

A dashboard summary is not approval evidence. Store query/rule versions,
watermarks, counts, hashes, sampled discrepancies, resolutions, approvers, and
timestamps. Critical differences block cutover.

## Rollout and rollback

### Cutover runbook

For each scoped journey:

1. Announce window, owners, stop conditions, and support escalation.
2. Verify dependency/SLO/security/backup/restore and provider readiness.
3. Capture source/target watermarks and final incremental catch-up.
4. Run mandatory reconciliation and obtain recorded approval.
5. Fence legacy writes at the capability boundary.
6. Change target ownership atomically and verify write/read canaries.
7. Observe business and technical metrics through the agreed soak period.
8. Keep legacy read-only and preserve rollback compatibility/evidence.

The decision record contains exact commands/API actions and expected outputs;
free-form manual database updates are not a cutover mechanism.

### Rollback

Rollback criteria include money/state discrepancy, authorization leak, sustained
SLO violation, provider duplication, or inability to operate the journey. It:

1. Stops new target commands for the scope.
2. Drains or pauses workers without abandoning uncertain provider operations.
3. Reconciles target-created facts since the ownership watermark.
4. Uses the approved reverse compatibility path or keeps those facts under the
   target until terminal—never blindly copies them into legacy.
5. Restores legacy routing only after one writer is proven.
6. Records incident, mappings, and required forward remediation.

Schema/data backfills are not destructively undone. Rollback is an ownership and
traffic operation with financial/process reconciliation.

## Legacy retirement

Retirement requires:

- every capability target-owned through its rollback/soak period;
- no active client, job, webhook, credential, DNS route, or operator workflow
  depends on legacy;
- required data/audit exports verified and retention/legal holds recorded;
- legacy databases/files backed up and restore/read procedures tested;
- secrets, provider credentials, service accounts, network paths, and scheduled
  jobs revoked;
- monitoring proves no traffic for the approved observation period;
- product, engineering, operations, finance, security, and support signoff.

Actual deletion is a separately approved, inventory-specific action with a
recoverability statement. This ADR does not authorize broad file/database
deletion.

## Testing and rehearsals

- Rehearse full waves against production-shaped sanitized snapshots at least
  twice, including a changed record during every page/checkpoint boundary.
- Kill/restart migrators and prove idempotent mappings/counts.
- Inject malformed tenant keys, duplicates, missing media, price mismatches,
  provider uncertainty, and out-of-order change events.
- Run canary and rollback rehearsals for both order channels and every external
  provider class using fakes/sandboxes.
- Restore legacy and target backups in isolation and execute reconciliation.
- Load-test catch-up so migration cannot exhaust production database/provider
  capacity.

## Consequences

### Positive

- Every capability has exactly one writer at every moment, provable from the
  scope table.
- Cutover and rollback are ownership operations with evidence, not database
  surgery.
- No legacy source can be forgotten, because the coverage register blocks
  cutover while any in-scope row remains undecided.

### Negative

- The migration control plane is itself a substantial system to build, test, and
  operate, and it produces no customer-visible value.
- Rehearsing every wave twice is expensive and will be the first thing proposed
  for removal under schedule pressure.
- Running legacy and target together extends the period of double
  infrastructure, double monitoring, and double on-call.

### Accepted trade-offs

- Draining in-flight orders under the legacy owner is slower than transferring
  them, and is the only approach that keeps provider outcomes unambiguous.
- Legacy data is retained well past cutover, so storage and access controls
  continue to cost after the system stops serving traffic.

## Implementation checklist

- [ ] Complete source inventory, classification, volume/change-rate, ownership, and retention.
- [ ] Reconcile every production source against `docs/migration-coverage.md`; no in-scope `DECIDE` remains.
- [ ] Approve mappings/defaults/quarantine and update canonical legacy-mapping docs.
- [x] Add migration program/scope/run/mapping/quarantine/reconciliation/decision tables (V0024).
- [x] Implement paged extraction, versioned transformation, idempotent import, and checkpoints
      (V0044). Extraction is keyset paging on a stable key, never an offset, with the
      position in `migration.source_cursors` rather than on the run — a run is one
      execution and the source position outlives every one of them. The cursor advances in
      the same transaction as the target writes it covers: the control plane and the target
      are two schemas of one PostgreSQL, so "checkpoint only after a target commit" holds in
      its strongest form and no page is ever imported and unrecorded. `migration.programs`
      gained `source_time_zone`, nullable with no default, and extraction refuses to start
      without it — every legacy timestamp is naive and its zone is the legacy server's, so a
      default of UTC would shift every historical order across the business-date boundary
      the daily order number depends on. `migration.transformations` holds a digest over
      each version's declared mapping rules, so a changed mapping is refused at startup and
      becomes a remediation run instead of two semantics under one version number.
      Idempotence is the existing crosswalk upsert plus `ImportPort.importOne` converging on
      `existingTargetId`.
- [x] **Wire `ImportContext.isImporting()` into every adapter with an external effect,
      before the first import port ships.** Done, across twelve call sites in six modules,
      and enumerated as `ExternalEffect` so the wiring is checkable rather than believed —
      `MigrationImportSuppressionTests` fails if any effect loses its consumer or any named
      adapter stops consulting the flag. Each effect declares one of two suppressions.
      *Skipped*, where not producing the effect leaves the caller a truthful answer: the
      ADR 0004 outbox append in both listeners (after the ADR 0032 catalogue check, so an
      import cannot smuggle an uncatalogued contract past it), the ADR 0020 notification
      intent in `OrderNotificationTrigger`, the ADR 0011 POS export row, and the ADR 0021
      usage movement. *Refused*, where skipping would mean inventing the result: ADR 0013
      intent creation and attempt open/present, ADR 0014 courier calls, the outbound POS
      call, and ADR 0017 quote reservation, commit and release. The notification, payment,
      POS and delivery gateways carry the same refusal as a tripwire at the outermost
      boundary. Two placements are load-bearing and not interchangeable: the outbox is
      suppressed at the append and not in `OutboxRelay`, and the notification at intent
      creation and not at delivery, because both of those run on scheduler threads where
      the `ScopedValue` binding does not exist and a row that reached them would be
      indistinguishable from a real one.
- [x] Implement single-writer/read routing gates and auditable transitions.
      `MigrationOwnershipPort` resolves location, then brand, then tenant, answers from a
      row locked `FOR SHARE` so a concurrent cutover waits rather than races, and fences
      on everything unknown. Ownership moves only through an approved cutover decision;
      there is no endpoint that sets a write mode.
- [ ] Build exact structural/business/financial/security reconciliation suites. The rule
      library exists (V0044) with the four mandatory rules seeded at version 1 in
      `migration.reconciliation_rules`: `AUTHORITATIVE_ID_COUNT` and
      `AUTHORITATIVE_ID_CHECKSUM` — one rule in two parts, because a run that dropped one
      row and duplicated another has the right count and the wrong set;
      `MONEY_TOTAL_BY_CURRENCY_AND_STATUS`, sliced rather than summed, taken from the legacy
      writer's own `order_price + delivery_price + packaging_price` and never reconstructed
      from line items; and `CROSS_TENANT_ANCESTRY`, three zero-expected counts for a foreign
      tenant, a foreign brand and a foreign location. Severity and tolerance belong to the
      rule *version*, so loosening a rule cannot make a past approval look stricter than it
      was, and every CRITICAL rule is zero-tolerance by constraint. Still open: the security
      suite, the media and catalog structural probes, and the business probes that render
      production-shaped menus and price golden carts in both systems.
- [ ] Build wave dashboards, pause/resume, quarantine resolution, and approval workflows. Pause/resume (`MigrationScopeController`'s `/{scopeId}/suspensions` and `/{scopeId}/resumptions`), quarantine resolution (`MigrationQuarantineController`'s `/quarantine-items/{itemId}/resolution`), and the cutover decision endpoint (`/{scopeId}/cutover`) are built; wave dashboards are not — the Angular control-plane app has no migration section.
- [ ] Rehearse every wave, canary, provider uncertainty path, and rollback twice.
- [ ] Execute tenant/brand/location journey cutovers with soak and support signoff.
- [ ] Freeze/archive legacy, revoke all access/integrations, and complete retirement review.

## Exit criteria

All tenants and journeys are served by target frontends and one target backend
writer; data, identity, money, fiscal evidence, media, catalog, inventory,
courier/location privacy, reporting, audit, scheduled jobs, and in-flight
processes meet signed reconciliation rules; rollback and restore have been
rehearsed; legacy is read-only then decommissioned with retained audit/restore
access; and no unresolved table, journey, dependency, credential, timer,
webhook, route, report, or provider effect remains.
