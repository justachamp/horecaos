# ADR 0012: POS catalog synchronization staging and reconciliation

- Decision status: Accepted
- Implementation status: Partial — V0037 carries all twelve tables; `pos`
  stages a Clopos read (`CloposAdapter`, `CloposCatalogNormalizer`),
  `PosCatalogSyncService.run` walks fetch → stage → absence quorum →
  `DifferenceEngine` under `FieldAuthorityPolicy.INITIAL`, and
  `PosSyncRunController` exposes the manual dry run and the difference report;
  not built: the durable scheduler (`integration.pos_sync_schedules` is written
  by nothing and read by nothing), the `PosSyncRequested` command, review-decision
  /apply/resume endpoints, S3 raw snapshots (`raw_object_key` is never written),
  the separate stop-list cadence, and restart/scale/isolation tests
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

Still to write: interrupted-run resume from each checkpoint, large-run pagination
performance, daily schedule across a timezone change, and target
optimistic-version staleness (which needs the apply path to exist first). Golden
fixtures exist for one provider; "all three" is not a claim this ADR can make
until a second adapter exists.

## Rollout and rollback

Start with dry-run only for one location/provider and compare reports manually.
Enable mapping/draft creation, then reviewed operational fields. Do not enable
automatic authoritative price/content/availability changes. Rollback disables
schedules/apply while retaining runs, snapshots, mappings, and evidence.

**The apply endpoint is not implemented, and that is the rollout rather than an
omission.** `POS_SYNC_APPLY` exists as a capability and nothing consumes it. This
ADR's own rollout says the first months deliver reports rather than automation,
and an apply endpoint that exists is an apply endpoint somebody will call.
Running a comparison (`POS_SYNC_EXECUTE`) and accepting what it says
(`POS_SYNC_APPLY`) are separate capabilities for the same reason: there is no
safe undo for a menu that was live and wrong during a lunch rush, so the person
who triggered the import should not be the only person who read the report.

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
- [ ] Implement the durable scheduler and the `PosSyncRequested` command. The
      schedule table exists and nothing reads it yet.
- [ ] Implement review-decision, apply, and resume APIs. Apply is deliberately
      last — see the rollout.
- [ ] Add S3 raw snapshot handling and retention. `raw_object_key` exists and is
      unwritten; raw payloads currently live in the staging rows' JSONB.
- [ ] Add the separate stop-list availability feed on its own cadence.
- [ ] Add restart, scale, and isolation tests.

## Exit criteria

A daily or manual dry run imports one location's POS catalog, produces a
deterministic reviewed difference report, resumes after failure, and cannot
silently overwrite Qoida-authoritative content, prices, or availability.

**Three of the four are met.** A manual dry run imports the catalog, the report
is deterministic, and nothing in the implemented path can write to
`catalog.*` — not because it declines to, but because no such code exists. Resume
after failure is not: the run records a checkpoint and a source cursor and
nothing yet reads them, and a failed run is re-run from the start.

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
