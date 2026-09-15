# ADR 0125: Wiring the delivery-accrual path across the courier/fulfillment/ordering boundary

- Decision status: Proposed
- Implementation status: Not started — this record is written before the
  code it describes lands in the same wave (`wave139-t11`), per the
  platform's own ADR discipline; both should move to Built together once a
  human has reviewed the shape below rather than only the code.
- Date proposed: 2026-09-14
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction
  of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0042, ADR 0043, ADR 0014, ADR 0019
- Supersedes / Superseded by: —
- Open inputs: whether the cash-to-collect figure should also subtract a
  settled loyalty-balance tender (today it does not — see Consequences)
  (platform owner); whether `reporting.fact_delivery` should eventually carry
  `brand_id` once `fulfillment.courier_assignment_earnings` gains one
  (platform owner, tracked against ADR 0042)

## Context

`CourierAccrualService.recordDelivery` (ADR 0042) has no production caller —
only `CourierCompensationTests` reaches it. Nothing in `fulfillment` ever
writes `fulfillment.shipments.status = 'DELIVERED'` either: the physical
delivery lifecycle the V0054 schema models (`ASSIGNED` → `PICKUP_PENDING` →
`PICKED_UP` → `DELIVERED`) is a shell with no write path past `ASSIGNED`. The
practical consequence: `courier_assignment_earnings` and the `CASH_COLLECTED`
ledger entry are never written on a real tenant, Finance's cash worklist is
permanently empty, and `/statistics/couriers` has no fact to report from.

The platform has no separate courier-facing "mark picked up / mark
delivered" application yet. The one place today's console learns a delivery
finished is an operator (or an automated rule) moving the order from
`FULFILLING` to `COMPLETED` — `OrderStateService.advance`, which already
publishes `OrderCompleted` (ADR 0019) for exactly this reason (ADR 0075's
rating prompt reads it). Wiring the accrual there is therefore not a
shortcut; it is where "delivered" is actually known today.

Two of ADR 0042's package-level rules make the wiring non-obvious rather than
mechanical:

1. `courier`'s own `package-info.java` states "the two [`courier` and
   `fulfillment`] must never read each other" — the mechanism that keeps a
   courier's earnings from ever being derived from the customer's delivery
   charge. `fulfillment.shipments`/`assignment_attempts`/`delivery_plans`
   hold exactly the facts `recordDelivery` needs (distance, accepted-at,
   promise), and `ordering.orders` holds the payment-status fact needed for
   `cashToCollectMinor`. Reading either schema directly from `courier` would
   contradict a rule the module's own header states as central.
2. `CourierAccrualService.recordDelivery` is `@Transactional` (`REQUIRED`).
   Spring marks a transaction rollback-only the moment an `@Transactional`
   method it joined throws, even if the caller catches the exception
   afterwards — a caught `ApiException` from a missing rate card cannot be
   un-thrown once the interceptor has seen it. Joining `recordDelivery` into
   the *same* transaction as `OrderStateService.advance` (the way
   `loyalty.OrderCompletionAccrualTrigger` joins loyalty accrual to it) would
   mean an unconfigured courier rate card — plausible on a pilot tenant
   still mid-onboarding — silently breaks the *unrelated* ability to
   complete any delivery order at that branch.

## Decision

1. One narrow port, `fulfillment.api.DeliveryCompletionPort`. `courier` is
   the caller (`courier.application.DeliveryAccrualOrderCompletionTrigger`
   invokes it), which by the "consumer declares the interface" convention
   `fulfillment.api.InternalFleetPort` already established would put this in
   `courier.api` instead, with `fulfillment` implementing it. That shape was
   tried first and `ModularArchitectureTests.verifiesModuleBoundaries`
   rejected it: `courier` already depends on `ordering.api` (for `OrderCompleted`,
   the event the trigger listens for) and `ordering` already depends on
   `fulfillment.api` (`JdbcDeliveryOrderPort` implements `DeliveryOrderPort`),
   so a `fulfillment`-implements-`courier.api` edge on top of those two
   closes the triangle `courier → ordering → fulfillment → courier` — a
   module cycle Spring Modulith refuses to allow, caught immediately by the
   arch test rather than discovered later. Declaring the interface in
   `fulfillment.api` instead, with `courier` depending on it, reuses the
   direction `courier.infrastructure.dispatch.InternalFleetAdapter` already
   establishes for `InternalFleetPort` itself — `courier` depending on
   `fulfillment.api` is an existing, safe edge nothing depends back on.

   `JdbcDeliveryCompletionAdapter` (`fulfillment.infrastructure.sourcing`)
   implements its own module's interface — no cross-module edge from that
   half at all. Given a tenant and an order, it closes out the order's live,
   non-cancelled `INTERNAL` shipment (a compare-and-set to `DELIVERED`,
   idempotent — a second call is a no-op) and returns the facts
   `recordDelivery` needs: courier, shipment, assignment-attempt, distance,
   accepted-at, promise, and whether the order is prepaid. Empty for a
   pickup order, a partner-sourced shipment, or one already closed out —
   every one of those is "nothing for courier to accrue" and the port does
   not distinguish them, the same non-distinguishing answer
   `DeliveryOrderPort` already gives for its own four empty cases.

   The prepaid flag is answered by joining `ordering.orders.payment_status_projection`
   with a plain SQL join inside the same query, rather than a second port
   `ordering` would implement — that shape was tried too (an
   `OrderPrepaymentPort` in `courier.api`) and rejected for the identical
   cycle reason: it would have made `ordering` depend on `courier.api`
   directly, a two-module cycle even before `fulfillment` entered the
   picture. A raw SQL join, with no Java-level import of an `ordering` type,
   creates no dependency edge Spring Modulith's bytecode-based graph can see
   — the same shape `JdbcDeliveryOrderPort` already uses in the opposite
   direction, reading `fulfillment.delivery_fee_resolutions` by plain SQL
   rather than through a second port. `courier` still never reads `ordering`
   or `fulfillment` directly — it depends on one `fulfillment.api` interface
   and nothing else, and `fulfillment` gained one more column on a query it
   already ran.

   Neither the port nor its one boolean lets `courier` see `ordering`'s or
   `fulfillment`'s money: the only money value crossing the port is
   `orderCompleted.totalMinor()` (already public on the event, and already a
   dependency the trigger has) used to compute what a *cash* order still
   owes, never the customer's *delivery* charge — `recordDelivery` still
   computes what the courier is owed from the rate card alone, exactly as
   ADR 0042 requires.

2. `courier.application.DeliveryAccrualOrderCompletionTrigger`, an
   `@TransactionalEventListener(phase = AFTER_COMMIT)` on `OrderCompleted` —
   deliberately **not** `BEFORE_COMMIT`, unlike
   `loyalty.OrderCompletionAccrualTrigger`, for the rollback-only reason
   above. The order's completion is durable before this runs; a missing rate
   card, an unresolvable courier type, or any other accrual failure is
   caught, logged, and never revisits the order. `recordDelivery` keeps its
   own four-write transaction (earning, ledger entry, cost line, cash entry)
   exactly as documented; it is simply not also the order's transaction.

3. `reporting.fact_delivery` (V0337) is a new grain, one row per
   `courier_assignment_earnings` row, joined at close time
   (`DayCloseService`) against `fulfillment.assignment_attempts` for
   `accepted_at` — a column `courier_assignment_earnings` does not carry —
   so `7.4`'s "transit hours" has an honest start instant. The `COURIER`
   scope of `agg_sla_bucket_day` (already allowed by `ck_agg_sla_scope_kind`,
   unused until now) buckets the same `accepted_at → delivered_at` interval
   through the existing six-bucket `SlaBucketSet`, reusing
   `JdbcReportingStore.insertSlaBucket` and `clearDay` unchanged.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| `courier` reads `fulfillment.shipments`/`assignment_attempts` and `ordering.orders` directly via its own SQL | Directly contradicts `courier`'s own package-level rule ("the two must never read each other"); a courier-owned query over fulfillment's schema is a second place the shipment/assignment invariants live, the exact failure mode V0040's own comments warn about for `InternalFleetPort.ActiveAssignments` | Never, without first revising ADR 0042's boundary statement itself |
| Join `recordDelivery` into the same transaction as `OrderStateService.advance`, `BEFORE_COMMIT`, matching `loyalty`'s trigger | `recordDelivery`'s internal rate-card resolution can throw for an ordinary, plausible tenant-configuration gap (no rate card yet), and Spring's rollback-only marking on a joined `@Transactional` method cannot be caught away by the caller — a courier compensation gap would silently take down order completion for every delivery order at that branch | If `recordDelivery` is refactored to never throw for a missing/absent rate card (e.g. returns an explicit "unaccrued, no card" result instead) |
| Build a real courier-facing "picked up" / "delivered" endpoint first, and drive the shipment lifecycle from there instead of from order completion | The correct long-term shape, but a materially larger build (auth for a courier-facing surface, geo capture, offline handling) than this wave's 2-day allocation, and no such surface exists anywhere in the codebase to extend | When a dedicated courier mobile/PWA surface is scoped — tracked as a gap-map row of its own, not created here |
| Derive `cashToCollectMinor` by also subtracting a settled loyalty-balance tender, matching `OrderCompletionAccrualTrigger`'s own `moneySettledMinor` computation exactly | Requires a third cross-module read (into `payments`'/`loyalty`'s settled-tender rows) for a refinement that affects only the `CASH_COLLECTED` ledger entry's exact figure on an order that was *both* paid partly by cash *and* partly by points — no row in this wave's brief depends on that precision | If Finance's cash worklist (fed by this ledger entry, itself out of this wave's four rows) is found to be off by a customer's points redemption on a mixed-tender order |

## Consequences

### Positive

- `courier_assignment_earnings` and the `CASH_COLLECTED` ledger entry are
  finally produced by real traffic, not only by a unit test — unblocking
  Finance's cash worklist as a side effect, and giving `7.4`/`7.4a` a real
  fact to report from.
- The courier/fulfillment boundary ADR 0042 states in prose now has two
  concrete, narrow, one-directional ports instead of a rule nothing had yet
  tested.
- A missing courier rate card can no longer block order completion — the
  `AFTER_COMMIT` placement makes that failure mode structurally impossible
  rather than merely unlikely.

### Negative

- The accrual now runs slightly later than the order's own commit (one
  extra, very short window in which an order shows `COMPLETED` before its
  courier earning row exists). A concurrent read of the courier's ledger in
  that window sees nothing yet.
- `kitchen_handover_at` on the earning row is always `NULL` from this path:
  there is still no real "picked up" capture, so `OnTimeEvaluator`'s
  `LATE_EXCUSED` branch (a late delivery excused by a late kitchen handover)
  can never fire until a real pickup event exists. Every late delivery
  routed through this trigger reads as plain `LATE`.
- `geoUnverified` is unconditionally `true` on every earning this path
  writes: no geo-confirmation capture exists yet, and claiming a
  verification that did not happen would be worse than stating the gap.
- `cashToCollectMinor` does not subtract a settled loyalty-balance tender
  (see Alternatives) — on an order paid partly from points and partly cash,
  the `CASH_COLLECTED` entry slightly overstates what the courier is
  physically holding.

### Accepted trade-offs

Accrual correctness now depends on `OrderStateService` continuing to publish
`OrderCompleted` for every delivery completion and on nothing else ever
transitioning `fulfillment.shipments` to `DELIVERED` through a different
path (there is none today). The day a real courier-facing "delivered"
capture ships, this trigger's shipment-closing half becomes redundant with
it and should be revisited.

## Specification

See `fulfillment/api/DeliveryCompletionPort.java`,
`courier/application/DeliveryAccrualOrderCompletionTrigger.java`,
`fulfillment/infrastructure/sourcing/JdbcDeliveryCompletionAdapter.java`, and
`db/migration/V0337__reporting_fact_delivery.sql`.

## Rollout and rollback

Additive only: a new listener, one new port/adapter pair, one new table. No
existing endpoint, event contract, or migrated column changes shape.
Rollback is deleting the listener bean and the new table; no data written by
this wave is read by anything else yet.

## Implementation checklist

- [x] `DeliveryCompletionPort` + `JdbcDeliveryCompletionAdapter` (including
      the prepaid flag, read by plain SQL join rather than a second port —
      see Decision)
- [x] `DeliveryAccrualOrderCompletionTrigger` (`AFTER_COMMIT`, defensive catch)
- [x] `reporting.fact_delivery` + close-time projector
- [x] `agg_sla_bucket_day` `COURIER` scope population
- [ ] Real pickup-confirmation capture (closes the `kitchen_handover_at` /
      `LATE_EXCUSED` gap noted above) — future wave

## Exit criteria

A delivery order moved to `COMPLETED` through the ordinary operations
console produces exactly one `fulfillment.courier_assignment_earnings` row,
one `DELIVERY_EARNING` and (on a cash order) one `CASH_COLLECTED` ledger
entry, and its shipment reads `DELIVERED`. A tenant with no rate card
configured for the branch can still complete the order. `reporting.fact_delivery`
and the `COURIER` scope of `agg_sla_bucket_day` gain rows the next time
`DayCloseService.close` runs over a day that produced deliveries.

## References

- ADR 0042 (courier compensation)
- ADR 0014 (delivery sourcing)
- ADR 0043 (reporting)
- `docs/operations-gap-map.md` rows 7.4, 7.4a, 7.4b, 7.4c
