# ADR 0108: Courier type correction, rate-card completion, and the bonus/penalty rule catalogue

- Decision status: Proposed
- Implementation status: Partial — see [Implementation checklist](#implementation-checklist)
- Date proposed: 2026-09-12
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0027, ADR 0031, ADR 0042
- Supersedes / Superseded by: —
- Open inputs: five, see [Open inputs and who answers them](#open-inputs-and-who-answers-them)

## Context

The operations gap map verified three finishing gaps against ADR 0042's own module,
all under IA 3.4 (Courier types & rates):

**3.4a — courier types are create-only at every layer.** `courier_types.status`
has carried `ARCHIVED` since V0040, and nothing writes it: no `PUT`, no
`POST .../archival`, no service method. A mistyped code or a wrong offer TTL is
permanent. The create form also never sends `maxDistanceMeters`, even though
`CourierDispatchGate.withinBand` has enforced it since the dispatch half of
ADR 0042 landed — the value is accepted and enforced, just never sent. And the
IA names two attributes with no column anywhere: starting minute and work mode.

**3.4b — the rate-card backend is complete and the console shows a fifth of
it.** `CourierRateCardService`/`RateCardValidator`/`AccrualCalculator` already
handle all four component types, band gap/overlap validation at activation, a
second version under a narrower scope (the `(tenant_id, code, card_version)`
uniqueness and `activate`'s by-code supersede logic already support it), and
`GET rate-cards/{cardId}` already returns the full ladder. The console's own
form authors only `PER_ORDER` and `PER_SHIFT_FIXED`, hard-codes
`cardVersion: 1` and brand scope, and nothing calls the detail endpoint at
all — so a manager activates a card whose terms the console never shows him,
and cannot re-price a code whose v1 is already active.

**3.4c — there is no rule catalogue.**
`fulfillment.courier_adjustment_reasons` (V0040) carries `kind` and a closed
`outcome_basis`, and nothing else: no amount, no condition, no evaluator.
`insertAdjustmentReason` was called only by tests. `POST
.../couriers/{courierId}/adjustments` — the manual-entry endpoint ADR 0042
already built with its four-eyes branch — had zero console callers. A manager
could not record even a one-off penalty from the console, let alone define
"twenty deliveries in a shift → +50,000" once and have it apply.

**The one finding that made this more than a finishing pass:**
`CourierAdjustmentService.AdjustmentCommand` carries an `origin` — `RULE` or
`MANUAL` — and `OperationsCourierController`'s HTTP request let the caller set
it directly. `CourierAdjustmentService.request`'s own four-eyes rule is `penalty
&& (origin == MANUAL || amount > threshold)`: a penalty under the threshold with
`origin = RULE` skips approval entirely. Nothing before this wave stamped
`RULE` from anywhere but a test fixture, so the gap was latent rather than
exploited — but the moment an evaluator exists that legitimately posts `RULE`
entries, the same field that lets it do so also lets an HTTP caller forge one.
ADR 0042's own text is explicit that this is the load-bearing distinction
("a manager who can silently debit a courier's pay is a labour dispute and a
fraud vector in one instrument") and never said which layer was responsible
for keeping the two apart. This ADR settles it: the origin a ledger entry
carries must never be a value the request supplied.

## Decision

**Types gain a correction and an archive path, and two captured-but-not-yet-
enforced attributes.** `PUT /courier-types/{typeId}` rewrites every field
(including `code`) under an optimistic lock (`expectedVersion`); `POST
.../archival` archives one, never deletes it, so a rate card or courier that
still names it keeps reading. `courier_types` gains `starting_minute_offset`
(minutes after shift open before the type begins earning `PER_SHIFT_FIXED`,
0-1440, default 0) and `work_mode` (`SHIFT` | `ON_DEMAND`, default `SHIFT`) —
captured and rendered now, deliberately not wired into
`CourierAccrualService` or `CourierDispatchGate` this wave (see
[Open inputs](#open-inputs-and-who-answers them)). `docs/operations-spec/couriers.md`
previously warned against reproducing Delever's undefined `Начальная минута`/
`Режим работы` verbatim; this ADR gives each a narrow, closed definition
instead of copying Delever's open one, which is what that warning actually
asked for.

**The rate-card console catches up to the backend that already exists.** The
authoring form gets a `PER_KM_BAND` ladder editor (add/remove a band row, each
with its own from/to/amount) and a `PER_ORDER_MINIMUM` row type, alongside the
two flat components already shipped. `cardVersion` and an optional narrower
scope (a location, or a courier type) are real fields instead of hard-coded
constants. "Show components" calls the `GET rate-cards/{cardId}` endpoint that
has existed with no caller and renders the ladder and the card's effective
dates before anyone activates it. No backend change was required for any of
this — `CourierRateCardService.author`/`.activate` already accept every one of
these shapes; V0040's schema and V0042's validator already enforce them.

**The bonus/penalty registry gains an amount and a typed condition, and an
evaluator applies it.** `courier_adjustment_reasons` gains six nullable
columns — `rule_amount_minor`, `rule_currency`, `rule_comparator`,
`rule_threshold`, `rule_window`, `rule_trigger` — all six together or none,
enforced by a `CHECK`. A reason with none set is exactly what exists today:
manual-only, picked from a dropdown. A reason with all six set is evaluated by
`AdjustmentRuleEvaluator`, a new service that reads
`DELIVERED_VOLUME`/`LATE_DELIVERY`/`ON_TIME_RATE`/`GEO_UNVERIFIED_RATE` from
data this module already owns (`courier_assignment_earnings`,
`courier_ledger_entries`) and posts the configured amount when the configured
comparator holds against the configured threshold. `ORDER_UNDELIVERED` and
`ORDER_DAMAGED` — the two remaining `outcome_basis` values — name facts this
module has no reader for (they belong to `fulfillment.delivery_exceptions`,
which nothing here queries) and stay manual-only; the console's wiring
checkbox is disabled for them rather than accepting a rule that would never
fire. The manual-entry console gets its console callers: a "Record adjustment"
action on a courier's detail pane, and a registry-management section beside
the courier-types-and-rates page.

**`AdjustmentOrigin.RULE` is stamped by `AdjustmentRuleEvaluator`'s own Java
call and nowhere else.** `OperationsCourierController`'s `AdjustmentRequest`
keeps its `origin` field on the wire — see
[why below](#why-the-field-stays-on-the-wire-but-is-ignored) — but the
controller never reads it: every HTTP-originated adjustment is constructed
with `AdjustmentOrigin.MANUAL`, unconditionally, whatever the request body
says. `RULE` exists only as a value the evaluator's own
`CourierAdjustmentService.request(...)` call constructs, which is Java, not
an HTTP surface a request body can reach.

### Why the field stays on the wire but is ignored

The straightforward fix — delete `origin` from the request record — is what
this wave built first, and the platform's own `OpenApiContractTests` refused
it: `origin` has been a published required field on this endpoint since ADR
0042, and the contract test's backward-compatibility rule
(`assertSchemasCompatible`) forbids removing a published property or dropping
a published required field, precisely so that an already-generated client
does not silently break. Rather than treat that as an obstacle, this ADR
treats it as the more defensible shape: the wire contract does not change,
and the security property is behavioural rather than schematic — no value of
`origin` in a request body, including `"RULE"`, has ever been able to
influence what gets stamped, and that is now provably true by reading
`adjust()`'s body rather than by trusting that a field was removed. The two
new HTTP-level tests in `OperationsCourierControllerEndpointTests` assert
exactly this: a request that sends `"origin":"RULE"` still writes a `MANUAL`
entry.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Remove `origin` from `AdjustmentRequest` entirely | `OpenApiContractTests` refuses to drop a published required field or make one optional-then-absent; a generated client that still sends it would break | Never, unless the platform owner accepts a documented breaking change to this endpoint and bumps the contract version |
| Validate `origin` server-side (`@AssertTrue origin == MANUAL`) instead of ignoring it | Functionally equivalent for security, but returns a 400 to a client that still sends the old `RULE`-shaped value out of habit, for no operational gain — the field is inert either way and a validation error implies the client did something wrong when it did not | A future wave decides the field itself should be retired via a versioned contract bump, at which point outright removal (the option above) is the correct move, not a validation error on the old shape |
| Wire `starting_minute_offset` into `AccrualCalculator` and `work_mode` into `CourierDispatchGate` this wave | Genuinely useful, but expands the wave past "capture and render the two IA-named attributes" into changing accrual and dispatch semantics no product/operations input has specified yet (what exactly should `ON_DEMAND` change about eligibility? does starting-minute interact with `PER_SHIFT_FIXED`'s existing `minimum_paid_seconds`, or replace it?) | Product/operations names the exact behaviour each attribute should drive (see open inputs below) |
| Evaluate `SETTLEMENT_PERIOD`-window rules from `CourierSettlementService.close` this wave | `close` reads `entriesOf`/`earningsOf`/`computeTotals` and then hashes the result; evaluating after that read would silently exclude a rule-posted entry from the very statement it was meant to affect, and evaluating before it means reordering a method three other ADR 0042 tests already exercise in its current shape | A maintainer moves the evaluation call to before the read, with its own test proving the posted entry is included in the hash |
| Store `rule_threshold`'s units in a second closed enum column instead of a comment | More self-documenting, but ADR 0042's own `outcome_basis` already fixes what each threshold means (a count for `LATE_DELIVERY`, a rate for `ON_TIME_RATE`, and so on); a second column would either duplicate that mapping or contradict it if the two ever drifted | A basis is added whose unit is ambiguous even given its name |
| A visual band-gap/overlap ruler and a payout simulator on the rate-card form | `docs/operations-spec/couriers.md` describes both; neither exists on this design system and building either is a UI investment beyond "the backend already validates gaps and overlaps, expose that message" | The IA's `ConditionBuilder`/`RuleSimulator` shared component (Part 4's own gap list) is built for another wave and this one adopts it |

## Consequences

### Positive

- A mistyped courier-type code or a wrong offer TTL is now correctable
  without a database migration or a support ticket.
- A manager can see, before activating a rate card, exactly what it will pay
  — the ladder, the flat components, the effective window — which is the
  entire point of `GET rate-cards/{cardId}` existing in the first place.
- A code whose v1 is active can be re-priced: `cardVersion` and scope are now
  console-authored rather than hard-coded constants that made every second
  authoring attempt collide on the database's own uniqueness constraint.
- "Twenty deliveries in a shift → +50,000" is now a policy authored once,
  not fifty manual entries each carrying its own four-eyes approval.
- The origin field that could bypass four-eyes approval on a penalty is now
  provably inert from any HTTP request, closing a gap that would otherwise
  have gone live the moment the evaluator started posting real `RULE`
  entries.

### Negative

- `starting_minute_offset` and `work_mode` are captured and rendered without
  changing what the platform actually does — a manager who sets a non-default
  work mode expecting dispatch to behave differently will be disappointed
  until a later wave wires it, and nothing in the console currently tells
  them so beyond a form hint.
- `SETTLEMENT_PERIOD`-window rules are built and tested but not reachable
  from production; a reason authored with that window will simply never
  fire until a maintainer does the `CourierSettlementService.close`
  reordering this ADR declines to do itself.
- `rule_version` on a reason is not stamped onto the ledger entries a rule
  writes, because `courier_ledger_entries` has no column for it and adding
  one is outside this wave's reserved migration numbers. A reason's rule can
  therefore be edited after a rule fired, and the historical entry will not
  say which version of the rule produced it — the same reproducibility gap
  ADR 0042's own text asks for and does not yet have.
- The rate-card form's band editor has no visual gap/overlap indicator; a
  manager only learns about a gap when activation refuses it with a message,
  not while typing the band boundaries.
- `origin` remains a field a client can set to anything on the wire, even
  though it is ignored — a caller reading the OpenAPI contract without also
  reading this ADR or the endpoint's own description could reasonably
  believe it still does something.

### Accepted trade-offs

- Keeping `origin` on the wire rather than removing it means the endpoint's
  documented shape does not match its actual authority model as tightly as a
  clean removal would; accepted because the alternative breaks an
  already-published contract for a field that costs nothing to leave inert.
- `COURIER_ADJUSTMENT_REASON_MANAGE` is a new capability rather than reusing
  `COURIER_ADJUSTMENT_CREATE`; accepted because the two are different classes
  of decision at different tempos (defining a rule's condition and amount vs.
  posting one instance against one courier), the same split ADR 0042 already
  drew between `COURIER_TYPE_MANAGE` and `COURIER_RATECARD_MANAGE`.

## Specification

### Physical model

```text
fulfillment.courier_types                          -- V0259
  starting_minute_offset smallint NOT NULL DEFAULT 0  -- 0-1440
  work_mode              varchar(16) NOT NULL DEFAULT 'SHIFT'  -- SHIFT | ON_DEMAND

fulfillment.courier_adjustment_reasons              -- V0260
  rule_amount_minor  bigint    null   -- sign must match kind
  rule_currency      char(3)   null
  rule_comparator    varchar(3) null  -- GTE | LTE
  rule_threshold     bigint    null   -- unit depends on outcome_basis
  rule_window        varchar(24) null -- SHIFT | SETTLEMENT_PERIOD
  rule_trigger       varchar(24) null -- SHIFT_CLOSE | SETTLEMENT_PERIOD_CLOSE
  rule_version       integer   NOT NULL DEFAULT 1
  -- all six rule_* columns null, or all six non-null (ck_adjustment_reason_rule_pair)
```

No table is new; no `GRANT` changes were needed (`courier_types` and
`courier_adjustment_reasons` already carry `SELECT, INSERT, UPDATE` from
V0040).

### APIs and capabilities

```text
PUT  /api/v1/operations/tenants/{tenantId}/courier-types/{typeId}              courier.type.manage
POST /api/v1/operations/tenants/{tenantId}/courier-types/{typeId}/archival     courier.type.manage
GET  /api/v1/operations/tenants/{tenantId}/adjustment-reasons                  courier.read
POST /api/v1/operations/tenants/{tenantId}/adjustment-reasons                  courier.adjustment.reason.manage  (new)
POST /api/v1/operations/tenants/{tenantId}/adjustment-reasons/{id}/archival    courier.adjustment.reason.manage
```

`courier.adjustment.reason.manage` is held by `TENANT_OWNER`, beside
`courier.type.manage` and `courier.ratecard.manage` — the same policy tempo,
distinct from `courier.adjustment.create` (`LOCATION_MANAGER`,
`COURIER_DISPATCHER`), which posts one instance against one courier.

### `AdjustmentRuleEvaluator`

```java
evaluateShiftClose(tenantId, ShiftRow)       // wired: CourierShiftService.close (CLOSED path), .approveHours
evaluatePeriodClose(tenantId, PeriodRow, locationId)  // built, tested, NOT wired
```

Idempotency key: `"rule:" + reasonCode + ":" + windowId` (the shift id, or the
period id) — `CourierLedgerService.append`'s own idempotency guarantee means
re-evaluating a closed window posts the same entry back rather than a second
one; the evaluator adds no new idempotency mechanism of its own.

Metric computation, by `outcome_basis`:

| Basis | Unit | Source |
|---|---|---|
| `DELIVERED_VOLUME` | count | earnings recorded against the window |
| `LATE_DELIVERY` | count | earnings with `on_time_outcome = LATE` |
| `ON_TIME_RATE` | basis points (0-10000) | `ON_TIME` count / delivered count |
| `GEO_UNVERIFIED_RATE` | basis points | `geo_unverified` count / delivered count |
| `CASH_VARIANCE` | count | `CASH_VARIANCE` ledger entries (`SETTLEMENT_PERIOD` window only) |
| `ORDER_UNDELIVERED`, `ORDER_DAMAGED` | — | no reader; manual-only |

## Rollout and rollback

Migrations V0259-V0260 are additive columns with `DEFAULT` values on existing
tables; every existing row reads as `starting_minute_offset = 0`, `work_mode
= SHIFT`, and every existing adjustment reason reads as manual-only
(`hasRule = false`). No backfill is required and none is destructive to roll
back — dropping the six new `courier_adjustment_reasons` columns and the two
new `courier_types` columns loses only what this wave adds, not the create/
manual-adjustment behaviour that already existed. `AdjustmentRuleEvaluator`
firing zero rules (because no reason has ever set `rule_amount_minor`) is
indistinguishable from the pre-wave state, so shipping the evaluator wired
into shift close with no reason yet authored is safe by construction.

## Implementation checklist

- [x] Migration V0259: `courier_types.starting_minute_offset`, `.work_mode`.
- [x] Migration V0260: `courier_adjustment_reasons` rule columns and CHECKs.
- [x] `JdbcCourierStore`: `updateType`, `archiveType`, `listTypes(tenantId,
      includeArchived)`, `listAdjustmentReasons`, `ruleReasonsAt`,
      `archiveAdjustmentReason`, the `RuleConfig` record.
- [x] `OperationsCourierController`: `PUT`/archival for courier types; `GET`/
      `POST`/archival for adjustment reasons; `AdjustmentRequest` stops
      reading `origin` (keeps the field for wire compatibility).
- [x] `AdjustmentRuleEvaluator`, wired into `CourierShiftService.close`/
      `.approveHours` for the `SHIFT` window.
- [ ] **Not wired: `SETTLEMENT_PERIOD`-window evaluation from
      `CourierSettlementService.close`.** Built and tested
      (`evaluatePeriodClose`); see this ADR's alternatives table for why the
      call site needs the close method's own read-then-hash sequence
      reordered first.
- [x] Capability `COURIER_ADJUSTMENT_REASON_MANAGE`, granted to
      `TENANT_OWNER`.
- [x] Console: courier-type edit/archive, `maxDistanceMeters` on create, the
      `PER_KM_BAND` ladder editor, `PER_ORDER_MINIMUM`, card version and
      scope fields, "Show components", the adjustment-reason registry
      section, and a "Record adjustment" action on the courier detail pane.
- [x] ADR 0042's own checklist corrected — see that record's Implementation
      status line and checklist for the specific item this ADR split.
- [ ] **Not built: `starting_minute_offset`/`work_mode` read by
      `CourierAccrualService`/`CourierDispatchGate`.** Captured and rendered
      only — see open inputs.
- [ ] **Not built: `rule_version` stamped onto the ledger entries a rule
      writes.** `courier_ledger_entries` has no column for it; see open
      inputs.
- [ ] **Not built: `ORDER_UNDELIVERED`/`ORDER_DAMAGED` evaluation.** No
      reader exists for `fulfillment.delivery_exceptions`; these two bases
      stay manual-only by construction until one does.

## Exit criteria

A courier type authored with a mistyped code can be corrected without a
migration, and archiving it never removes it from a rate card or courier
still naming it. A manager authoring a rate card can add a `PER_KM_BAND`
ladder and a `PER_ORDER_MINIMUM` floor, choose a version and a narrower
scope, and see a card's full component list and effective dates before
activating it — all without a backend change, because the backend already
did this. A reason wired to a rule fires automatically at shift close and
posts the configured amount stamped `AdjustmentOrigin.RULE`, and no value an
HTTP request supplies for `origin` — including `"RULE"` — can ever produce
that stamp instead of `MANUAL`, proven by an HTTP-level test that sends
exactly that value and reads the stored ledger row back.

## Open inputs and who answers them

| Input | Owner | Why it is not structural |
|---|---|---|
| Whether `starting_minute_offset` should delay `PER_SHIFT_FIXED` accrual, interact with the existing `minimum_paid_seconds` qualifier, or mean something else entirely | product, operations | The column and its console field exist either way; only `AccrualCalculator`'s reading of it is undecided |
| Whether `work_mode = ON_DEMAND` should change `CourierDispatchGate` eligibility (dispatch without an open shift), and if so under what per-tenant policy gate | operations, product | ADR 0030's `courier.shift.enforcement` already governs shift-gating at the location scope; whether a courier-type-level override composes with it or replaces it needs a decision this wave does not make |
| Whether and how to reorder `CourierSettlementService.close` so `SETTLEMENT_PERIOD`-window rules can be wired in before the statement is read and hashed | platform architecture | `evaluatePeriodClose` and its metrics are already built and tested; only the call site and its interaction with the existing close sequence is open |
| Whether `rule_version` should be stamped onto `courier_ledger_entries` (a new column, a new migration) so a rule edited after firing does not retroactively change what a historical entry is understood to have been computed under | finance, platform architecture | ADR 0042's own text already asks for "the rule and its version" on a statement line; this wave's migration budget did not include altering the ledger table |
| The pre-existing defect this wave found in `JdbcPolicyResolver`/`tenant.policy_current`: a brand-new tenant's first resolve of any unconfigured ADR 0030 policy throws rather than falling back to defaults, because Spring's `@Cacheable` unwraps an empty `Optional` to `null` before the caller's own fallback runs, and that cache disallows nulls | platform architecture (tenancy/caching, not courier) | Outside this ADR's module; flagged in the wave's final report and filed as a follow-up task rather than fixed here |

## References

- ADR 0025 — capability model.
- ADR 0027 — approval and audit (four-eyes).
- ADR 0031 — HTTP conventions, including backward-compatible request/response
  evolution.
- ADR 0042 — courier compensation, shifts, and settlement; this ADR's own
  parent module and the record whose checklist it corrects.
- `docs/operations-gap-map.md` rows 3.4a, 3.4b, 3.4c — the verification this
  ADR responds to.
- `docs/operations-spec/couriers.md` §9-11 — the courier-types, rate-card,
  and bonus/penalty-rule screens this ADR implements against, corrected in
  place for the starting-minute/work-mode reversal.
