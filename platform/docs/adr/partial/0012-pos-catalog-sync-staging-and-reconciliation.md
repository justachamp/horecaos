# ADR 0012: POS catalog synchronization staging and reconciliation

- Decision status: Accepted
- Implementation status: Partial — V0037 carries all twelve tables; `pos`
  stages a Clopos read (`CloposAdapter`, `CloposCatalogNormalizer`),
  `PosCatalogSyncService.run` walks fetch → stage → absence quorum →
  `DifferenceEngine` under `FieldAuthorityPolicy.INITIAL`, and
  `PosSyncRunController` exposes the manual dry run, the difference report,
  review decisions, apply, and resume. The durable scheduler now fires:
  `PosSyncScheduler` claims due `pos_sync_schedules` rows with `FOR UPDATE SKIP
  LOCKED`, computes the next fire in the branch's own zone, and emits
  `PosSyncRequested` through the outbox. Raw provider snapshots are written —
  `PosCatalogSyncService` calls `PosRawSnapshotWriter` off the critical path and
  `raw_object_key` is populated, so a bad import can be read back from the
  provider's own bytes. Still not built: the separate stop-list cadence
  (`pos_live_availability`, V0190, has a table and no poller writing it, so the
  fast feed is still unserved) and any retention or expiry policy over the
  written snapshots. 2026-09-08: `gov_code` is confirmed as an MXIK
  candidate (Q15, `docs/providers/clopos-api.md` §12) and the staged field is
  renamed `CatalogSnapshot.Product#mxikCode` accordingly; `FieldAuthorityPolicy`
  still resolves `product.mxikCode` to `REVIEWED_IMPORT`, unchanged by the
  answer — a provider confirming what a field means is not this platform
  deciding to auto-apply its values. `JdbcPosTargetCatalog`'s target-catalog
  read for that field was also fixed: it had been selecting `catalog.products
  .tax_category_code`, a column V0028 dropped, and would have thrown against a
  real database; nothing exercised the query until this wave's
  `JdbcPosTargetCatalogTests` did. 2026-09-09: the durable scheduler is built —
  `PosSyncScheduler` polls `integration.pos_sync_schedules` every thirty
  seconds; `PosSyncSchedulingService.claimAndDispatch` claims one due row under
  `FOR UPDATE SKIP LOCKED`, advances `next_run_at` (computed in the branch's own
  IANA zone by the pure `ScheduleCadence.nextOccurrenceAfter`, which is correct
  across a daylight-saving transition — see its own tests), and appends
  `PosSyncRequested` to the outbox, all in one transaction, so two replicas
  racing the same due row settle to one claiming it and one finding nothing due
  — `PosSyncSchedulingServiceTests` proves this with two threads racing a real
  row. The inbox handler, `PosSyncRequestedHandler`, now implements
  `ExternalWorkInboxHandler` rather than the plain form the first draft used: the
  plain form runs inside the transaction that marks the inbox row processed, and
  a provider fetch in there would have held a pooled connection for the whole of
  the catalog read — exactly the failure mode `ExternalCallTransactionBoundaryTests`
  exists to catch elsewhere in the platform. Review-decision, apply, and resume
  are wired on `PosSyncRunController`, gated by `POS_SYNC_APPLY` as the ADR
  always specified; the "apply endpoint is not implemented" rollout position
  below is superseded by this — see the Rollout section for what replaces it.
  Resuming a run interrupted mid-apply now genuinely resumes: it executes only
  the apply items still `PLANNED` and never re-touches one already `APPLIED` —
  `PosApplyServiceResumeTests` seeds exactly that half-finished state and
  proves it. Resuming a run interrupted before `REVIEW_REQUIRED` still cannot
  resume mid-fetch, unchanged from before — see the Exit criteria section.
  `raw_object_key` is now written: `PosRawSnapshotWriter` bundles every staged
  entity's own raw payload into one document and stores it through `media`'s
  already-proven `ObjectStorage` port (the same S3-compatible store, a POS-
  scoped key prefix, no new client or credential), best-effort — a storage
  failure is logged and does not fail the run, because the object is diagnostic
  evidence and not itself part of what makes an import correct. Retention by
  classification is not built: nothing expires or reclassifies these objects
  yet. No endpoint creates or edits a schedule row; `JdbcPosScheduleStore
  .upsert` is the seam a future one would call, and today a schedule is seeded
  directly. Tenant-isolation tests for the new endpoints are not written beyond
  the tenant-scoped `WHERE` clauses every store method already carries — see
  restart/scale, which is proven, versus isolation, which is asserted only by
  construction here.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Date revised: 2026-08-23 (Clopos contract read; staging and difference engine
  implemented)
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0010, ADR 0011, ADR 0016, ADR 0029, ADR 0030
- Supersedes / Superseded by: —
- Open inputs: Versioned field-authority policy and review roles (product);
  Clopos answers to Q3, Q4, Q5 and Q6
  ([`docs/providers/clopos-api.md`](../../providers/clopos-api.md) §12)

## Context

The pilot restaurant's POS is Clopos. Restaurants need daily catalog
synchronization from it, but Qoida becomes authoritative after import for
customer-facing product content, prices, and availability. Directly overwriting
live catalog tables from provider responses would destroy curated data, hide
removals/conflicts, and make provider replay unsafe.

### What Clopos established

Reading Clopos's contract in full on 2026-08-23 confirmed the shape of this ADR
and added two things it did not have.

**Change detection requires a full re-read, and there is no alternative.**
`GET /products` accepts no date range, no `updated_at` filter, no cursor and no
sort; there is no ETag, no conditional request, no change feed and no webhook.
Every filterable field is structural and not one is temporal. The staged-snapshot
design already assumed this, so nothing had to change — Clopos merely removes the
option of optimising it into an incremental fetch later. The cost is bearable:
the vendor's own sample brand is 284 products at a page size of 100, so three
requests per read against a 300-per-minute budget.

**But a single page-through is not an atomic snapshot, and that is new.** Offset
pagination over a table the restaurant is editing can *skip* rows: insert a
product while we are reading page two, page three shifts by one, and a product
that exists is never read. Downstream, a product we failed to read is
indistinguishable from a product that was deleted — so a pagination race presents
to the difference engine as a removal. See "The removal quorum" below.

**The stable identifier is an integer `id`, and nothing else.** No SKU, no
external code, no slug, no stable secondary key. `barcode` is deprecated and not
guaranteed to be populated. Names, full names, prices, cost prices, statuses,
category and station assignments are all editable in the back office. Categories
additionally carry `_lft` and `_rgt` nested-set columns that renumber on every
tree edit and are not identifiers at all. This ADR's rule against guessing a
mapping from mutable product names has no softer reading available.

**And one naming trap worth stating in the decision record**, because it is three
characters wide and would be a silent data corruption. A Clopos `modification` is
a *variant* — a size or a colour, carrying the full product schema with its own
price and stock. A `modificator` is a *modifier option* — "extra cheese" — and
attaches only to a `DISH`. Neither word appears past the normalizer; the staging
tables are named for what the things are.

## Decision

Every POS import lands in integration-owned raw evidence and normalized staging.
A deterministic reconciliation engine calculates additions, changes, removals,
mapping conflicts, and field-authority violations. Applying changes is a
separate idempotent command. New provider products may become draft Qoida
products; live authoritative values are never silently overwritten.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Upsert provider responses straight into catalog tables | Destroys curated names, descriptions, media, and prices, and provides nothing to review or reconcile when the provider sends bad data | Never |
| Last-write-wins with POS as authority | Directly contradicts ADR 0002 and makes a provider outage or export bug a storefront content incident | Never |
| Full replace on each import | Absent items become deletions, breaking historical order references and any curated entity that the provider does not know about | Never |
| Keep staged data only in Kafka | Cannot be diffed, reviewed, paginated, or resumed, and topic retention would silently destroy evidence mid-review | Never |
| Auto-apply everything and let operators undo mistakes | There is no safe undo for a menu that was live and wrong during a lunch rush | Never |
| Build the diff and apply engine before the catalog model exists | The original roadmap placed this work four steps before ADR 0016. The comparison contract is defined by the target model, so building it first guarantees rework. The roadmap now sequences this ADR after ADR 0016 | Never; if POS ingestion must start earlier, restrict it to raw evidence capture and mapping only |
| One shared external-mapping table owned by `catalog` | Mapping is integration evidence with provider lifecycle, not catalog authoring state. ADR 0026 owns it, and ADR 0016 reads it through a port | Never |

## Source-of-truth policy

Initial ownership:

| Field group | Authority |
|---|---|
| Qoida product name/description/translations/media | Qoida |
| Customer-facing price and promotions | Qoida |
| Effective customer availability | Qoida |
| External product/modifier/unit IDs | Mapping/reconciliation |
| Provider operational preparation metadata | Configurable reviewed import |
| Provider deletion/inactive signal | Reconciliation input, not automatic delete |

Field policy is versioned per tenant/brand/location and snapshotted on each run.

## Physical model

### `integration.pos_sync_schedules`

```text
id, tenant_id, binding_id, timezone
schedule_expression or local_time
enabled, next_run_at, last_run_at
version, created_at, updated_at
```

Use a durable PostgreSQL scheduler. Kafka carries `PosSyncRequested`; it is not
the daily timer.

### `integration.pos_sync_runs`

```text
id, tenant_id, binding_id, trigger_type
status, adapter_version, field_policy_version
started_at, fetched_at, normalized_at, compared_at, applied_at, completed_at
checkpoint, source_cursor
raw_object_key or protected_payload_reference
received_count, valid_count, invalid_count
addition_count, change_count, removal_count, conflict_count
last_error_code, last_error, version
```

The run additionally records `page_count` and `walk_kind` (`OFFSET` /
`KEYSET`). The second is not bookkeeping: it decides how much a single run's
absence is worth as evidence, and the removal quorum reads it.

### Staging and difference tables

```text
integration.pos_staged_categories
integration.pos_staged_products
integration.pos_staged_variants
integration.pos_staged_modifier_groups
integration.pos_staged_modifiers
integration.pos_staged_availability
integration.pos_absence_observations      <- new; see the removal quorum
integration.pos_sync_differences
integration.pos_sync_conflicts
integration.pos_sync_apply_items
```

Core comparable fields use typed columns. JSONB retains protected raw payload
and genuinely provider-specific metadata only.

Built as [`V0037`](../../../src/main/resources/db/migration/V0037__create_pos_catalog_staging_and_reconciliation.sql).

Money is staged as whole minor units with a currency, and for UZS a minor unit is
a whole som. Clopos types every amount as a JSON `number` and carries **no
currency field anywhere in its API** — not on an order, a product, a price list,
a receipt, or a payment method — so the currency is asserted from installation
configuration and the amount is parsed from the response's own decimal text
rather than through a double. Its examples are inconsistent about scale, showing a
receipt total of 30000 beside product prices like 8.5.

### The removal quorum

`integration.pos_absence_observations` is the table that stops a pagination race
becoming a menu removal. One row per binding and entity, carrying how many
consecutive runs have now failed to see it, cleared the moment it reappears —
because two absences with a presence between them are two coincidences and not a
pattern.

**A `REMOVAL_SIGNAL` requires two consecutive agreeing runs.** A single run's
absence produces no difference at all — not a difference with a warning on it.

The reasoning is worth keeping, because the cost looks larger than it is and the
benefit looks smaller. This ADR already refuses to physically delete on a removal
signal, so a phantom removal's direct blast radius is one review-queue item. But
a review queue that is usually wrong is a queue an operator learns to approve
without reading, and then the real removal goes through the same way. The price
is one extra catalog read before a removal becomes actionable; two independent
offset races skipping the same row is a coincidence rather than a pattern, which
is why the threshold is two and not three — and a menu item that stays sellable
for two extra days after the kitchen stopped making it has its own cost.

A walk that provably cannot skip rows short-circuits this entirely. If Clopos
turns out to be sortable by identifier (Q6), paging by `id > last_seen` makes the
walk stable under inserts and one absence becomes evidence. `walk_kind` carries
the fact rather than assuming it, so that day is a normalizer change and nothing
else.

### Availability is a separate feed, not part of the daily run

`GET /products/stop-list` is the only endpoint in the Clopos API carrying a
per-row change timestamp — in milliseconds, while the rest of the API uses
seconds or `YYYY-MM-DD HH:mm:ss` strings — and it happens to cover the
fastest-moving data. It is small, and it is the one read where staleness has an
immediate consequence for a customer: selling a dish that ran out.

So it runs on its own cadence, roughly every thirty to sixty seconds, while the
catalog structure runs daily as the reviewed run. These are different pipelines
with different latencies and different authority, and collapsing them into one
daily run makes the stop list useless.

One reading has to be got right and is easy to invert: **absence from the stop
list means unconstrained, not unavailable.** A product not in the response has no
limit. Inverting this empties the entire menu.

### Fields Clopos gives us that nothing can resolve

Staged as raw evidence, deliberately not modelled as though they were understood.

- **`unit_id`.** An integer on every product, and there is no units endpoint
  anywhere in the API. A product is "three of unit 1" and nothing can say what
  unit 1 is. Staged as `external_unit_reference`; a `unit_code` column here would
  be a translation nobody can perform. (Q5)
- **`gov_code`.** Nullable, no format, no example, no validation, and the only
  tax-classification field in the API. ADR 0038 needs an ИКПУ/MXIK; whether this
  holds one is a question about the vendor's market — its own examples are
  Azerbaijani — and a wrong code would look exactly as convincing as a right one.
  Staged as `government_code` under `REVIEWED_IMPORT` authority, never
  auto-applied. (Q15)
- **`product.venues`.** Documented in full as "venue-specific availability and
  pricing overrides", with the element shape given in no schema, no field
  reference, and no example — every sample is `[]`. This is per-location
  availability and per-location price, which ADR 0016 and this ADR both want. We
  cannot model what we cannot see. (Q4)
- **Price lists.** Clopos's own index describes a price list as applicable to
  specific venues or sales channels, and **no field in either the `PriceList` or
  the `Price` schema expresses that application** — no `venue_id`, no
  `sale_type_id`, no channel — and no endpoint resolves the effective price of a
  product at a venue for a sale type. This is the worst-documented thing in the
  API and it sits directly on the authority table below. It is survivable only
  because Qoida is authoritative for customer-facing price anyway: prices are
  imported as evidence for review, which makes the ambiguity a reporting nuisance
  rather than a pricing incident. Do not build venue resolution on a guess. (Q3)

## Run lifecycle

```text
REQUESTED -> FETCHING -> STAGED -> VALIDATING -> COMPARING
          -> REVIEW_REQUIRED -> APPLYING -> RECONCILING -> COMPLETED
Any active state -> FAILED
FAILED -> prior safe checkpoint on resume
```

Every stage is idempotent under `(run_id, external_entity_type,
external_entity_id)`. The run snapshots adapter and policy versions so a resume
does not reinterpret earlier data under new code without an explicit restart.

## Normalization and mapping

- Normalize provider data to canonical staging DTOs before catalog comparison.
- Resolve external IDs through `provider_entity_mappings` from ADR 0011.
- New unmapped entities create proposed mapping and draft target candidates.
- Duplicate external IDs, multiple candidate target IDs, missing parent, and
  cross-brand references become conflicts.
- Never guess mapping from mutable product names alone.
- Modifier structure and variant units retain enough raw evidence to diagnose
  lossy provider models.

## Difference engine

Each difference records entity, field, current Qoida value/hash, imported value/
hash, authority, severity, recommended action, and review outcome. Categories:

```text
ADDITION
AUTHORIZED_CHANGE
PROTECTED_FIELD_CHANGE
REMOVAL_SIGNAL
MAPPING_CONFLICT
INVALID_SOURCE
NO_CHANGE
```

Comparison is deterministic and independently unit-tested. Re-running against
the same snapshots produces the same differences, in the same order — the engine
sorts its output before returning it, because a review queue whose contents
shuffle between runs cannot be reviewed incrementally.

`DifferenceEngine` is pure: no database, no HTTP, no Spring, and
`PosModuleBoundaryTests` enforces it. A database call inside it would make every
assertion about it a question about what was in the database at the time.

Three rules do most of the work.

1. **Identity comes from the mapping, never from a name.** An unmapped entity is
   an addition or a conflict; it is never matched by resemblance.
2. **A Qoida-authoritative field never produces an applicable action.** The
   difference is recorded so an operator sees the disagreement, and recommended
   `IGNORE` — not `REVIEW` — so nobody is offered a button that overwrites
   curated content.
3. **Absence is not removal until the quorum says so.**

Conflict kinds, and the one Clopos produces most:

```text
DUPLICATE_EXTERNAL_ID     AMBIGUOUS_TARGET     MISSING_PARENT
CROSS_BRAND_REFERENCE     UNREPRESENTABLE_STRUCTURE
```

`UNREPRESENTABLE_STRUCTURE` is the Clopos one. Its modifiers attach only to a
`DISH`, so a modifier group on a `GOODS` product is a structure the two catalogs
cannot both express — and dropping it silently leaves a customer unable to order
something the restaurant sells, with no record anywhere of why.

`DUPLICATE_EXTERNAL_ID` is almost always a paging fault rather than provider
corruption: an offset walk over a catalog being edited returns the same row on
two pages as readily as it skips one. Either way the snapshot is not a consistent
picture and the engine refuses to diff that entity against it.

## Apply policy

- New products may be created as `DRAFT` under the same brand.
- Mappings and explicitly POS-owned operational metadata may auto-apply.
- Protected content/price/availability changes require review or are ignored
  with visible evidence.
- Removals never physically delete a Qoida product. An approved action may
  suspend a mapping/offering.
- Apply items use stable idempotency keys and optimistic target versions.
- If the target changed after comparison, return the item to review rather than
  overwriting it.

## APIs

```text
POST /api/v1/control-plane/.../pos-sync-runs?dryRun=true
GET  /api/v1/control-plane/.../pos-sync-runs
GET  /api/v1/control-plane/.../pos-sync-runs/{runId}
GET  /api/v1/control-plane/.../pos-sync-runs/{runId}/differences
GET  /api/v1/control-plane/.../pos-sync-runs/{runId}/conflicts
POST /api/v1/control-plane/.../pos-sync-runs/{runId}/review-decisions
POST /api/v1/control-plane/.../pos-sync-runs/{runId}/apply
POST /api/v1/control-plane/.../pos-sync-runs/{runId}/resume
```

Pagination and filters are mandatory for large catalogs. Dry run performs every
step except target mutation.

## Events

```text
PosCatalogSyncRequested
PosCatalogStaged
PosCatalogReviewRequired
PosCatalogApplied
PosCatalogSyncCompleted
PosCatalogSyncFailed
CatalogDraftCreatedFromPos
```

Partition run events by run ID and catalog mutation events by target aggregate.

## Security and retention

- Binding location/brand ancestry is verified on every run/query.
- Raw snapshots are private and may contain provider metadata; store in S3 when
  large and retain by classification.
- Operator review actions are audited with before/imported/decision hashes.
- Provider credentials never enter snapshots.
- Tenant exports cannot retrieve another tenant's run, raw data, or mapping.

## Testing

Written (`DifferenceEngineTests`, `CloposCatalogNormalizerTests`):

- The same snapshot compared twice produces the same report in the same order.
- A curated name disagreeing with the provider is recorded and recommended
  `IGNORE`.
- A first absence on an offset walk is `NO_CHANGE` with a stated reason; a second
  agreeing absence is a `REMOVAL_SIGNAL` and still only a review; a stable walk
  needs one.
- A duplicated identifier stops the entity rather than diffing it twice.
- A modifier on a non-dish surfaces as `UNREPRESENTABLE_STRUCTURE`.
- An ingredient is never proposed as a customer-facing product — Clopos's own
  example response returns a tomato and an onion from `/products`.
- A `modification` becomes a variant and a `modificator` becomes a modifier
  option, and neither crosses.
- A stop-list timestamp is read as milliseconds; read as seconds it lands in the
  year 57000.
- A price of `8.5` becomes 9 whole som through `BigDecimal`, never a double.
- A product type the OpenAPI enum does not contain is `UNKNOWN` rather than
  guessed — `MODIFIER` appears in the prose field reference and not in the schema
  (Q11), and guessing would hide the discrepancy.

Written since (`ScheduleCadenceTests`, `PosSyncSchedulingServiceTests`,
`PosApplyServiceResumeTests`, `PosSyncOutboxTests`):

- The next occurrence keeps the branch's own wall-clock time across a
  spring-forward and a fall-back — the UTC gap is twenty-three or twenty-five
  hours, never a naive twenty-four, and a `LocalTime` inside a spring-forward
  gap resolves forward rather than being silently unreachable.
- Two replicas racing the one due schedule row claim it exactly once; the
  loser's attempt claims nothing and produces no second command.
- A schedule that is not yet due is never claimed, by either replica.
- A run interrupted mid-apply resumes exactly the items still `PLANNED` and
  never re-touches — same status, same `applied_at` — the one already
  `APPLIED`; resuming an already-completed run refuses rather than re-running
  anything.
- `PosSyncRequested` is appended in the caller's own transaction, on the
  binding's own partition key, and rolls back with a rolled-back business
  transaction — never a bare publish.

Still to write: large-run pagination performance, target optimistic-version
staleness under a genuine concurrent edit (the apply path exists now, but
nothing yet drives two writers at the target row the way
`PosSyncSchedulingServiceTests` drives two replicas at a schedule), and an
explicit cross-tenant negative case for the review-decision/apply/resume
endpoints. Golden fixtures exist for one provider; "all three" is not a claim
this ADR can make until a second adapter exists.

## Rollout and rollback

Start with dry-run only for one location/provider and compare reports manually.
Enable mapping/draft creation, then reviewed operational fields. Do not enable
automatic authoritative price/content/availability changes. Rollback disables
schedules/apply while retaining runs, snapshots, mappings, and evidence.

**2026-09-09: the apply endpoint now exists, on the same capability separation
this section always argued for.** Until this wave, `POS_SYNC_APPLY` was a
capability nothing consumed, deliberately, because the first months were meant
to deliver reports rather than automation. `PosSyncRunController` now exposes
`POST .../review-decisions`, `POST .../apply`, and `POST .../resume`, each
gated by `POS_SYNC_APPLY` — a different capability from the `POS_SYNC_EXECUTE`
that starts a run — so the person who triggered the import is still never, by
itself, the person authorized to accept what it found. What has not changed is
the *production* rollout posture this section describes: nothing in this wave
grants `POS_SYNC_APPLY` to anyone, enables a schedule, or turns on auto-apply
for a live tenant. The gate that matters — who actually holds the capability —
is an operational decision for the pilot, not a code change, and rollback is
unchanged: revoke the grant (or disable the schedule) and every run, snapshot,
mapping, and piece of evidence already produced stays exactly where it is.
Running a comparison (`POS_SYNC_EXECUTE`) and accepting what it says
(`POS_SYNC_APPLY`) remain separate capabilities for the same reason: there is no
safe undo for a menu that was live and wrong during a lunch rush.

## Consequences

### Positive

- POS data can never silently overwrite curated content, prices, or
  availability.
- A daily sync produces a reviewable, deterministic, resumable report instead of
  an unexplained catalog change.
- Provider quirks stay in normalization, so the difference engine is testable
  with golden fixtures.

### Negative

- Review is human work. A large catalog with many daily provider changes will
  produce a queue somebody must own, and an unattended queue means the menu
  stops tracking reality.
- Staging, differences, conflicts, and apply items add substantial storage and
  several tables to maintain per provider run.
- The dry-run-first rollout means the first months deliver reports rather than
  automation.
- The removal quorum adds a day's latency to every genuine removal on a provider
  that pages by offset, and a discontinued dish stays sellable for that day.

### Accepted trade-offs

- Deliberately slower than direct synchronization, in exchange for never losing
  curated data to a provider bug.
- Auto-apply is limited to mappings and explicitly POS-owned operational fields,
  so some genuinely safe changes still wait for review until policy widens.

## Implementation checklist

- [x] Add schedule, run, staging, absence, difference, conflict, and apply
      tables (V0037).
- [x] Implement canonical staging contracts and the first provider normalizer.
- [x] Implement the deterministic difference engine and the removal quorum.
- [x] Implement the dry-run and difference-report APIs.
- [x] Add golden fixtures for the first provider's traps.
- [ ] Approve the versioned field-authority policy and review roles. The shipped
      `FieldAuthorityPolicy.INITIAL` is version 1 and is code; the run already
      records which version it used, so authoring becomes a lookup.
- [x] Implement the durable scheduler and the `PosSyncRequested` command.
      `PosSyncScheduler`/`PosSyncSchedulingService` claim a due
      `pos_sync_schedules` row under `FOR UPDATE SKIP LOCKED`, compute the next
      occurrence in the branch's own IANA zone (`ScheduleCadence`, correct
      across a DST transition), and append the command to the outbox, all in
      one transaction. No management endpoint creates a schedule yet —
      `JdbcPosScheduleStore.upsert` is the seam, unused by any controller today.
- [x] Implement review-decision, apply, and resume APIs. No longer deliberately
      last — see the rollout section for what changed and what did not.
- [x] Add S3 raw snapshot handling. `PosRawSnapshotWriter` writes
      `raw_object_key` through `media`'s `ObjectStorage` port, best-effort.
      Retention by classification is **not** built — nothing expires or
      reclassifies these objects, and that is still open.
- [ ] Add the separate stop-list availability feed on its own cadence.
      `integration.pos_live_availability` exists (V0190) with nothing that
      writes to it — the table is built, the poller is not.
- [x] Add restart and scale tests. `PosSyncSchedulingServiceTests` proves two
      replicas racing one due schedule claim it at most once;
      `PosApplyServiceResumeTests` proves a run interrupted mid-apply resumes
      the unfinished items without re-touching the finished ones. Isolation
      tests (an explicit cross-tenant negative case for the new endpoints) are
      still not written — every store method scopes its `WHERE` clause by
      `tenant_id`, but nothing exercises a second tenant failing to reach the
      first's run the way, say, `RowLevelSecurityBackstopTests` does elsewhere.

## Exit criteria

A daily or manual dry run imports one location's POS catalog, produces a
deterministic reviewed difference report, resumes after failure, and cannot
silently overwrite Qoida-authoritative content, prices, or availability.

**Three of the four are fully met, and the fourth is half true.** A manual dry
run imports the catalog, the report is deterministic, and nothing in the
implemented path can write to `catalog.*` — not because it declines to, but
because no such code exists. "Resumes after failure" depends on which failure:
a run interrupted before `REVIEW_REQUIRED` still cannot resume mid-fetch — the
run records a checkpoint and a source cursor and nothing reads them, because
the first provider offers no incremental read to resume with, so
`PosApplyService.resume` asks for a fresh `PosSyncRequested(RESUMED)` run
instead of pretending to continue one (see its own class doc for why these are
two different things). A run interrupted mid-`APPLYING`, by contrast, now
genuinely resumes: every apply item has a stable idempotency key, so resume
executes exactly the ones still `PLANNED` and never re-touches one already
`APPLIED` — `PosApplyServiceResumeTests` proves it. So resume is real for half
of the run lifecycle and, for the other half, is honestly still "start over,"
which is safe because a fresh full fetch is what this provider always required
anyway.

### Open, and what each one would change

| | Question | What it changes |
|---|---|---|
| **Q6** | Is `GET /products` sortable, and does it return a `sorts` array? Can it filter or sort on `updated_at`? Is a change feed planned? | The two agreeing reads. A keyset walk makes one absence evidence and retires the quorum; an `updated_at` filter would make the whole sync incremental. |
| **Q3** | How does a price list bind to a venue or a sale type? | Whether an imported price can be attributed at all. Until then, evidence for review only. |
| **Q4** | What is the element shape of `product.venues`? | Per-location availability and price, which ADR 0016's offerings want. |
| **Q5** | How is `unit_id` resolved? There is no units endpoint. | Whether a staged variant can carry a unit rather than an unresolvable integer. |

None of these blocks the reviewed run, and that is the whole benefit of having
decided to stage and review rather than to synchronize. A provider that answers
none of them still produces a report somebody can read.
