# ADR 0014: Scheduled delivery sourcing and partner orchestration

- Decision status: Accepted
- Implementation status: Partial — the seam is closed and the chain runs end to
  end for a partner delivery, and the in-house lane is now taken as well. V0054 creates `delivery_plans`, `shipments`,
  `delivery_quotes`, `assignment_attempts`, `delivery_sourcing_jobs` and
  `delivery_exceptions`, and turns V0040's `shipment_id` and
  `assignment_attempt_id` columns into real foreign keys. `SourcingPlanner`
  decides in-house first and partner as fallback; `QuoteScoring` is pure and
  versioned; `DeliverySourcingScheduler` claims a batch under a lease with the
  outbox's `FOR UPDATE SKIP LOCKED` idiom, and `JdbcAssignmentStore.win` is the
  single-winner compare-and-set. `DeliverySourcingService` books through
  `ShipmentBookingPort`, whose production implementation is
  `CamelShipmentBookingPort` over the ADR 0007 gateway and the Noor and Yandex
  adapters. **`ordering` now implements `fulfillment.api.DeliveryOrderPort`** —
  `JdbcDeliveryOrderPort` decrypts the ADR 0029 destination V0056 captures on
  the cart and checkout snapshots onto the order — and `DeliveryPlanTrigger`
  opens the plan and the job on `OrderConfirmed` at `BEFORE_COMMIT`, so a
  confirmed delivery order does now produce a plan, a scheduled job, quotes and
  a booked shipment. **The in-house half is now taken too**, which this
  Implementation status line previously recorded as the largest gap: the ADR
  0042 `courier` module supplies `InternalFleetPort` through
  `InternalFleetAdapter`, which enumerates the couriers on an open shift at the
  branch, takes `CourierDispatchGate`'s eligibility answer whole, counts what
  each is already carrying through `InternalFleetPort.ActiveAssignments`
  (`JdbcActiveAssignments`, over V0054's `ix_shipment_courier_open`) and ranks
  by proximity through `telemetry.api.CourierProximityPort`. A courier on shift
  is therefore offered the order, and `NO_INTERNAL_CANDIDATE` now means an empty
  rota rather than an unimplemented port — covered by `CourierDispatchPortTests`,
  which asserts the fall-through the stand-in still produces on the same
  fixture. Two qualifications. The branch-to-door
  distance is `Haversine`, a straight line, not a routed one. And a courier is
  attached to a branch only by an open shift there, so under
  `courier.shift.enforcement` of `ADVISORY` or `OFF` — where the gate forgives a
  missing shift — the fleet is still enumerated from shifts, because ADR 0042's
  roster and availability tables are not built and nothing else says which
  branch a shift-less courier belongs to. Not built: the
  operations API (no sourcing controller exists in `fulfillment.web`), partner
  tracking callbacks, and the cost-subsidy line, so no `DELIVERY_COST_SUBSIDY`
  is written and the cost test in the checklist below stays open.
- Date proposed: 2026-08-19
- Date decided: 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture), operations, legal
- Depends on: ADR 0007, ADR 0009, ADR 0011, ADR 0013, ADR 0026, ADR 0042, ADR 0045
- Supersedes / Superseded by: —
- Open inputs: Sandbox verification of Noor create idempotency (integration). Not
  structural — the platform already treats an uncertain create as uncertain and
  queries rather than retries, which is the safe behaviour under either answer;
  the sandbox result can only let that guard relax, never require it.
- Closed inputs: Yandex Delivery and Noor capability matrices verified from
  documentation 2026-08-20; Millennium deferred out of scope (business decision,
  2026-08-20); **internal-courier scope — there is an in-house fleet alongside
  Noor and Yandex** (product, 2026-08-23), which is what gave ADR 0045's telemetry
  half a subject and ADR 0042 its second settlement path; **courier PII and
  location retention** (product, legal, 2026-08-23) — customers never see a
  courier's position, so location is processed for dispatch and never disclosed,
  and ADR 0045 sets a 30-day track retention derived from the settlement dispute
  window; **courier settlement ownership** (finance, 2026-08-23) — couriers are
  registered self-employed, so ADR 0042 owns a gross-only statement and Qoida is
  not a payroll system of record.

## Context

An order may require two hours of preparation. Qoida should confirm according
to order-acceptance policy, then source a courier to arrive around the predicted
ready time. Initial external partners are Yandex Delivery and Noor, whose
capabilities are verified below. Millennium is deferred out of scope for now;
the capability model exists precisely so that adding it later is an adapter
rather than a redesign. Creating live bookings with several partners at once
would cause duplicate couriers and charges.

## Proposed decision

Separate a durable `DeliveryPlan` from the physical `Shipment` and from provider
`AssignmentAttempt`s. The plan owns promised/preparation/pickup timing and
sourcing policy. A durable PostgreSQL scheduler wakes sourcing at the correct
time. Kafka carries events/commands but is not a two-hour timer.

Ask multiple providers for non-binding quotes when safe, score candidates, and
create a live booking only with the selected winner. Provider capability
interfaces prevent name-based conditionals.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Book every partner and cancel the losers | Guarantees duplicate couriers, duplicate charges, and reputational damage with partners. Only conceivable if a contract guarantees free, idempotent, immediate cancellation | A specific partner contract provides that guarantee in writing, and only for that partner |
| Use a Kafka delayed message as the two-hour pickup timer | Kafka is not a scheduler. The delay is approximate, invisible to operators, and cannot be cancelled or rescheduled per plan | Never |
| Source a courier at order confirmation regardless of preparation time | Either the courier waits unpaid at the restaurant or the food waits and arrives cold. Preparation-aware sourcing is the reason this ADR exists | Never |
| Integrate a single delivery partner | No fallback when that partner has no coverage, no capacity, or an outage, and no price competition | Never |
| Treat internal couriers as another provider adapter | The legacy system carries real workforce data: shifts, zones, restrictions, documents, and live coordinates. Hiding that behind a provider string would smuggle a workforce-management product in as a string constant and take its PII with it | Never |
| Let the Camel route choose the winning provider | Selection is a versioned business decision that must be explainable and reproducible from stored evidence. Camel performs calls; it does not decide | Never |
| Increase the customer delivery fee when the actual courier costs more | Changes the price after the customer agreed to it. Cost overruns are a subsidy decision under ADR 0013 | Never |
| Keep the shipment and the delivery plan as one aggregate | Planning, quoting, and sourcing happen before any physical shipment exists, and one plan may produce several assignment attempts | Never |

## Time model

Persist UTC instants plus the location IANA timezone and the calculation inputs:

```text
estimated_preparation_duration
estimated_ready_at
pickup_window_start
pickup_window_end
promised_delivery_start/end
source_at
latest_assignment_at
calculation_version
```

For a two-hour preparation estimate:

```text
estimated_ready_at = order confirmed/approved at + preparation estimate
source_at = pickup_window_start - provider lead time - safety buffer
```

Restaurant preparation updates recalculate under an explicit version and may
reschedule, cancel/re-source, or require operations depending on provider
capability and cancellation cost.

## Proposed physical model

### `fulfillment.delivery_plans`

```text
id, tenant_id, brand_id, location_id, order_id
delivery_address_snapshot_id
status, service_level, sourcing_mode
customer_delivery_fee_minor, currency
estimated_ready_at, pickup_window_start/end
promised_delivery_start/end, source_at, latest_assignment_at
policy_id, policy_version, version
created_at, updated_at
```

### `fulfillment.shipments`

```text
id, tenant_id, brand_id, location_id, order_id, delivery_plan_id
status, provider_type null, external_shipment_id null
pickup/delivery address snapshots
assigned_at, pickup_at, delivered_at, cancelled_at
version, created_at, updated_at
```

### `fulfillment.delivery_quotes`

```text
id, tenant_id, plan_id, provider_binding_id
request_id, external_quote_id null
price_minor, currency, pickup ETA/window, delivery ETA/window
distance_meters null, dead_head_meters null
expires_at, quote_validity_source, capability_snapshot, status, received_at
unique(provider_binding_id, request_id)
```

`external_quote_id` and partner-provided expiry are nullable because neither
verified partner returns a redeemable quote object. `quote_validity_source`
records whether `expires_at` came from the partner or from Qoida policy, so a
self-imposed TTL is never mistaken for a partner guarantee.

### `fulfillment.assignment_attempts`

```text
id, tenant_id, plan_id, shipment_id, source_type
provider_binding_id null, courier_id null
sequence_number, idempotency_key, quote_id null
status, external_assignment_id null
requested_at, accepted_at, failed_at, cancelled_at
failure_code, uncertain_outcome, version
```

Exactly one source reference is present: an external provider binding or an
internal courier. This keeps the single-winner shipment invariant across both
sourcing paths.

### Durable sourcing jobs

`fulfillment.delivery_sourcing_jobs` stores due time, lease token, attempt,
status, checkpoint, and error. Unique active job per delivery plan.

## Lifecycles

Delivery plan:

```text
PLANNED -> WAITING_TO_SOURCE -> SOURCING -> BOOKING
        -> SCHEDULED or ASSIGNED -> IN_PROGRESS -> COMPLETED
SOURCING/BOOKING -> RETRY_PENDING -> SOURCING
Any pre-complete state -> MANUAL_ACTION_REQUIRED or CANCELLED
```

Shipment remains physical:

```text
PENDING -> ASSIGNED -> PICKUP_PENDING -> PICKED_UP -> DELIVERED
PENDING/ASSIGNED -> CANCELLED when provider policy permits
```

Quote and provider transport states never replace order or shipment states.

## Provider capability interfaces

```text
QuoteDelivery            non-binding price and ETA estimate
ReserveShipment          create a hold that is not yet a live booking
ConfirmShipment          promote a hold to a live booking
CreateOnDemandShipment   create a live booking immediately
ScheduleShipment         create a booking for a future pickup time
RescheduleShipment       change the pickup time of an existing booking
CancelShipment           cancel, with cost classification
QueryCancellationCost    check whether cancellation is free before cancelling
QueryShipment            poll current state
TrackShipment            obtain a customer-facing tracking reference
VerifyDeliveryWebhook    validate and normalize a pushed status update
```

`ReserveShipment`/`ConfirmShipment` and `QueryCancellationCost` were added after
verifying the partner APIs; the first draft of this ADR assumed neither existed.
Each adapter declares which of these it implements, plus advance-booking
horizon, minimum lead time, quote validity, cancellation rules, tracking mode,
idempotency behavior, service zones, and the date its contract was last
verified. Unsupported capabilities fall back to another configured strategy or
to Operations.

## Verified partner capabilities

Verified 2026-08-20 from the Yandex Logistics B2B API documentation and working
request collection, and from the Noor vendor integration documentation. Sandbox
verification is still required before enabling either adapter; the matrix below
is documentation-derived, not yet exercised.

| Capability | Yandex Delivery | Noor |
|---|---|---|
| Base | `b2b.taxi.yandex.net/b2b/cargo/integration/v2` | `/api/v1` (vendor host) |
| Auth | Bearer token | Static token in `X-Auth` header |
| Quote | `POST /check-price` (non-Russia), `offers/calculate` | `POST /orders/eval` |
| Quote object | Price only; no quote ID, no TTL | Price, ETAs, distances; no quote ID, no TTL |
| Reserve (hold) | **Yes** — `POST /claims/create` produces an unaccepted claim | No — create is immediately live |
| Confirm | `POST /claims/accept` with `{version}` | n/a |
| On-demand create | create + accept | `POST /orders` with `delivery.type=EXPRESS` |
| Scheduled create | `due` on create | `delivery.type=DELAYED` + `delivery.time` (UTC) |
| Reschedule | **Not available** | **Not available** |
| Cancellation cost check | **Yes** — `POST /claims/cancel-info` | No pre-check endpoint |
| Cancel | `POST /claims/cancel` with `{cancel_state, version}` | `PATCH /orders/{id}/cancel` |
| Free cancellation | Until courier arrives at pickup | Not documented; paid-cancel states exist |
| Query state | `POST /claims/info` | `GET /orders/{id}` |
| Status delivery | Polling | **Webhook push** on every stage change |
| Tracking | `GET /claims/tracking-links` (URL) | `send_link` flag; state includes ETAs |
| Idempotency | `request_id` query param, documented as an idempotency key | `vendor_order_id` in body; **not documented as idempotent — must verify** |
| Optimistic concurrency | `version` on accept and cancel | None exposed |
| Pickup points | Multiple route points | **Exactly one origin** |
| Currency | Per contract | UZS (sums) |
| Timezone | Offsets in payloads | UTC everywhere |

### What this confirms

- **Advance booking is real on both partners**, so `ADVANCE_BOOKING` and
  `JUST_IN_TIME` are both implementable rather than aspirational. The two-hour
  preparation scenario that motivates this ADR is supported.
- **Yandex's two-phase create-then-accept is stronger than the "cancellable
  booking" this ADR's alternatives table contemplated.** An unaccepted claim is
  not a live booking at all, so a Yandex hold can be taken while another partner
  is still being evaluated. The revisit trigger recorded in that table is
  therefore met for Yandex specifically — and only for Yandex.
- **Noor's create is immediately live.** The single-winner rule stands unchanged
  for Noor: never create a Noor order speculatively.
- Both partners return rich terminal failure reasons that map onto ADR 0006
  categories. Noor exposes an explicit stage taxonomy including
  `PerformerNotFound`, `CancelledOutOfZone`, `CancelledOutOfRange`, and
  `EstimatingFailed`, which are business rejections rather than transport
  failures and must not be retried as infrastructure errors.

### What is built so far

The provider-neutral contract and both partner adapters exist:
`DeliveryPartner` with a `DeliveryCapability` set per adapter,
`YandexDeliveryAdapter`, `NoorDeliveryAdapter`, and `DeliveryGateway`, routed
through `delivery.operation.v1` (ADR 0007).

Each adapter declares only what its partner documented, so the differences
recorded in the matrix above are now enforced rather than remembered:

- Yandex declares `RESERVE_SHIPMENT` and `CONFIRM_SHIPMENT`; Noor declares
  neither, and `NoorDeliveryAdapter.confirmShipment` returns `REJECTED` rather
  than quietly reporting success for a phase that does not exist.
- Noor declares no `QUERY_CANCELLATION_COST`, and asking anyway returns
  `UNCERTAIN` — the honest answer — instead of an assumed zero.
- Neither declares `RESCHEDULE_SHIPMENT`.
- `product_paid` is asserted in both directions in `NoorDeliveryAdapterTests`,
  so the double-charge invariant fails the build rather than a customer.

**The Noor stage table is deliberately incomplete.** Four stage names are
confirmed from the partner collection and recorded above; the rest of the enum
has not been verified. `NoorStage` maps what is confirmed and sends everything
else to `UNKNOWN` with a warning, rather than guessing from the name — a wrong
guess reads a failed delivery as delivered. **This table must be completed from
partner traffic before Noor carries production orders.**

Not yet built: the `fulfillment` schema (`delivery_plans`, `shipments`,
`delivery_quotes`, `assignment_attempts`), the sourcing policy that chooses
between partners, and the scheduler that books ahead of a preparation window.

### What this corrects in this ADR

- **Quotes are estimates, not reservable objects.** Neither partner returns a
  redeemable quote identifier or an expiry. In `fulfillment.delivery_quotes`,
  `external_quote_id` and `expires_at` are nullable, and quote validity is a
  Qoida-side policy (a short TTL we impose) rather than a partner guarantee. The
  unique key remains `(provider_binding_id, request_id)` on our own request ID.
- **`RescheduleShipment` is unsupported by both partners.** The preparation-change
  path is therefore cancel-and-re-source in practice, governed by the
  cancellation cost policy. Yandex's `cancel-info` makes that decision
  informed; for Noor the cost must be assumed unknown and treated
  conservatively. Keep the capability defined so a future partner can implement it.
- **Status arrives by two different mechanisms.** Noor pushes a webhook whose
  body matches the create-order response on every stage change; Yandex is polled.
  The adapter contract must support both, and a partner supporting neither push
  nor cheap polling would need a dedicated reconciliation schedule.
- **Noor can collect money from the end customer.** `payment_type` supports
  `CASH` and `PAYME`, and `delivery.product_paid=false` instructs Noor to
  collect the product price from the recipient. When Qoida has already collected
  payment, `product_paid` **must** be `true`, or the customer is charged twice.
  This is a financial invariant, not a configuration preference, and it is
  asserted in tests and in the adapter rather than left to configuration.
- **Noor supports exactly one pickup point**, which is compatible with
  single-location restaurant fulfillment and forecloses multi-pickup batching on
  that partner.
- **Idempotency is verified for Yandex and unverified for Noor.** Until a
  sandbox test proves Noor deduplicates on `vendor_order_id`, treat a Noor
  create timeout as `UNCERTAIN` and reconcile by querying orders before any
  retry. Never retry a Noor create blindly.

## Sourcing modes

- `ADVANCE_BOOKING`: reserve now for the future pickup window when supported.
- `JUST_IN_TIME`: persist `source_at`; quote/assign near readiness.
- `MANUAL`: Operations selects/records courier assignment.
- `INTERNAL_COURIER`: use tenant-owned courier capability.

Order confirmation does not wait for asynchronous courier acceptance unless a
future explicit product policy says otherwise. Restaurant-approval orders begin
booking only after approval; safe non-binding prequotes may occur earlier if
configured and side-effect-free.

## Internal courier boundary and legacy migration

The legacy system has active courier identities, areas, groups, blocks,
instructions, client notes, and location history. `INTERNAL_COURIER` therefore
requires a real workforce/dispatch model; it cannot be implemented as a string
provider or by copying those JSON structures.

Proposed minimum model in `fulfillment`:

```text
courier_profiles
  id, tenant_id, principal_id, status, courier_type
  protected_identity_reference, vehicle_summary null
  rating_projection null, version, timestamps

courier_availability
  id, tenant_id, courier_id, status, available_from/until
  current_zone_id null, version, timestamps

dispatch_pools
  id, tenant_id, name, status, policy_version, timestamps

dispatch_pool_couriers / dispatch_pool_locations / dispatch_pool_zones
  tenant_id, pool_id, scoped_id, valid_from, valid_until null

courier_restrictions
  id, tenant_id, courier_id, restriction_type, reason_code
  starts_at, ends_at null, decided_by, status, timestamps

courier_location_observations
  id, tenant_id, courier_id, captured_at, point, accuracy null
  source_device_id, retention_class
```

Couriers authenticate through an approved Keycloak flow and receive only their
assigned work. Dispatch pools and PostGIS zones filter eligible internal
candidates; a versioned policy scores availability, zone, distance, capacity,
shift/restriction status, and fairness. Assignment uses the same database
single-winner compare-and-set as external booking. A rejected/expired internal
offer may fall back to another courier or an external partner without creating
two active assignments.

Legacy `couriers`, `areas`, `courier_groups`, group join tables, blocks, notes,
instructions, OTPs, and locations follow the migration coverage register.
Identity-card/JSHIR/emergency contact/free text and live coordinates are
restricted PII. Product/legal must approve purpose, precision, frequency,
access, consent, and retention; old coordinate history is not migrated merely
because it exists. Group semantics and area JSON require production profiling
before they become dispatch pools/zones.

Driving licence, vehicle registration/plate/fuel data, work schedules,
referrals, and courier/device images need typed protected fields or constrained
media relations if retained; access/refresh tokens and OTPs never migrate.
Legacy shipment bonus and assignment-policy-group fields require a finance/
operations decision. A retained bonus becomes a separate courier compensation
ledger/settlement fact with approval and reconciliation, not a mutation of
customer delivery fee or shipment state.

## Provider selection

Filter candidates by binding status, service zone, capability, pickup window,
quote validity, currency, maximum price/subsidy, and circuit health. Then score
using a versioned snapshotted policy:

```text
on-time probability
pickup/delivery window fit
total provider cost
historical acceptance/cancellation/late rates
tenant/location priority
tracking/reschedule capability
```

Use deterministic tie-breaking. Keep all quote/score evidence. The selection
service returns a decision; Camel performs calls but does not choose the winner.

## Single-winner and uncertainty rules

- Non-binding quotes may run in parallel against every eligible partner.
- **A hold is not a booking.** Where a partner supports `ReserveShipment`
  without confirmation — verified for Yandex, whose created-but-unaccepted claim
  is not live — a hold may be taken in parallel with evaluating others. Every
  hold that does not win must be explicitly cancelled and its cancellation
  confirmed; an abandoned hold is an operational exception, not a no-op.
- **Where a partner has no hold semantics, creation is booking.** Verified for
  Noor. Never create speculatively on such a partner.
- Booking uses a PostgreSQL compare-and-set so one plan has one active winner,
  regardless of which mechanism produced it.
- A timeout after booking is `UNCERTAIN`; query by idempotency/external reference
  before trying another partner.
- Do not book a fallback while the first provider may have accepted unless the
  contract guarantees safe idempotent cancellation/reconciliation.

## Customer fee and cost allocation

The customer delivery fee is snapshotted at checkout and never silently
increased after confirmation. If actual provider cost is higher, apply the
versioned policy from ADR 0013:

```text
tenant absorbs
brand absorbs
location absorbs
platform absorbs
manual approval required
```

Record a `DELIVERY_COST_SUBSIDY`, not a discount or mutation of the order. If
the original service fails, a recovery case may refund the delivery fee or issue
a future free-delivery benefit.

## Preparation changes and exceptions

- Small change within provider tolerance updates the plan only.
- If a partner ever supports reschedule, issue an idempotent reschedule with a
  new desired window. Neither Yandex nor Noor does today.
- Otherwise cancel, reconcile, and re-source under the cancellation cost policy.
  Query the cancellation cost first where the partner exposes it (Yandex
  `cancel-info`); where it does not (Noor), treat the cost as unknown and apply
  the conservative branch of the policy.
- If no provider can meet `latest_assignment_at`, create
  `MANUAL_ACTION_REQUIRED`, notify Operations, and retain the confirmed order.
- Late restaurant, early/late courier, cancellation, no-show, and address issue
  are explicit exception reasons and possible recovery-case triggers.

## APIs

```text
GET  /api/v1/operations/orders/{orderId}/delivery-plan
POST /api/v1/operations/delivery-plans/{planId}/source
POST /api/v1/operations/delivery-plans/{planId}/reschedule
POST /api/v1/operations/delivery-plans/{planId}/manual-assign
POST /api/v1/operations/shipments/{shipmentId}/cancel
POST /api/v1/operations/shipments/{shipmentId}/reconcile
GET  /api/v1/operations/shipments/{shipmentId}/tracking
```

Mutations require idempotency keys, scoped location authorization, reason, and
optimistic version.

## Events and commands

```text
DeliveryPlanCreated
PreparationEstimateChanged
DeliverySourcingDue
DeliveryQuoteRequested/Received
DeliveryProviderSelected
ShipmentBookingRequested
ShipmentAssigned
ShipmentBookingFailed
ShipmentRescheduleRequested
ShipmentStatusChanged
DeliveryManualActionRequired
```

Partition plan decisions by plan ID and shipment lifecycle by shipment ID.
Provider webhooks pass signature/replay validation and inbox deduplication.

## Security and privacy

- Delivery addresses and phone data are restricted; Kafka events contain only
  minimum necessary snapshots/references.
- Provider credentials remain secret references.
- Provider endpoints use fixed allowlisted base URLs and TLS.
- Tenant/location ancestry protects bindings, plans, quotes, and shipments.
- Manual assignment, cancellation, subsidy, and reconciliation are audited.

## Testing

- Fixed-clock tests calculate two-hour ready/source/pickup windows correctly.
- Advance-booking and just-in-time capabilities select the correct path.
- Parallel quotes create only one live booking.
- Uncertain booking reconciles before fallback.
- Prep-time changes exercise update/reschedule/cancel/re-source paths.
- Duplicate commands/webhooks do not duplicate shipments/transitions.
- Higher courier price never changes customer fee; subsidy policy is applied.
- No-provider/late-assignment creates a visible operational exception.
- Contract tests for Yandex Delivery and Noor follow the same canonical
  capability suite.
- A Yandex claim created but not accepted is never treated as a live booking,
  and an abandoned hold is explicitly cancelled.
- A Noor order is never created speculatively, and a create timeout reconciles
  by query before any retry.
- A Noor order for a Qoida-paid basket always sends `product_paid=true`; a test
  fails the build if any code path can send `false` for a prepaid order.
- Noor webhook stage transitions and Yandex polled states normalize to the same
  canonical shipment states, including the out-of-zone and performer-not-found
  terminal cases.
- Durable scheduler restarts and lease recovery are tested.
- Internal offer acceptance and external booking races still produce one active
  shipment assignment.
- Courier scope, restriction, zone, location-retention, and PII denial tests pass.

## Rollout and rollback

Start with a fake partner and shadow quotes, then one provider/location in manual
selection mode, then automated just-in-time sourcing, and finally advance
booking. Rollback disables automated sourcing and returns to Operations/manual
courier assignment while preserving plans, quotes, attempts, provider IDs, and
reconciliation evidence.

## Consequences

### Positive

- A two-hour preparation order gets a courier sourced near readiness instead of
  at confirmation, which is the actual business requirement.
- Exactly one active assignment exists per plan across internal and external
  sourcing, enforced in the database rather than by convention.
- Partner cost overruns become explicit subsidy decisions instead of silent
  margin loss or a changed customer price.

### Negative

- This is the largest and least certain ADR in the set: it depends on three
  partner capability matrices that have not been verified, and a partner without
  advance booking invalidates a whole sourcing mode for that partner.
- The internal courier model is effectively a workforce-management subsystem,
  including live location data with serious privacy obligations.
- Preparation-time changes create reschedule, cancel, and re-source paths that
  multiply the states operations must understand.

### Accepted trade-offs

- Refusing to book a fallback while a first provider may have accepted means
  some orders wait longer for a courier. Two couriers arriving is worse.
- Quoting several partners costs API calls and latency that a single-partner
  integration would not spend.

## Implementation checklist

- [x] Obtain capability matrices for Yandex Delivery and Noor (documentation-verified 2026-08-20).
- [ ] Verify Noor create idempotency and Yandex callback support in sandbox.
- [ ] Approve preparation estimate, pickup window, promise, scoring, and subsidy policies.
- [ ] Approve internal courier identity, dispatch pool/zone, shift, restriction, and location privacy rules. Identity, shift, restriction and location privacy are settled and in the schema (`fulfillment.couriers`, `courier_engagements`, `courier_shifts`, `courier_location_tracks` with its 30-day retention, V0040/V0041); the dispatch pool and zone rules have no representation anywhere.
- [ ] Decide courier document/vehicle/referral and bonus/settlement migration rules. Vehicle class and its dispatch ceilings exist on `fulfillment.courier_types`, and ADR 0042's settlement periods and statements are built; courier documents, referral and the legacy bonus migration rules are undecided and unrepresented.
- [x] Add plan, shipment, quote, attempt, sourcing-job, and exception tables. All six in V0054, whose section 7 also makes V0040's `shipment_id` and `assignment_attempt_id` real foreign keys.
- [x] Implement time value objects, plan/shipment states, scheduler, and leases. `PickupPlan`, `PlanStatus`/`ShipmentStatus`/`AttemptStatus`, `DeliverySourcingScheduler` and `JdbcSourcingJobStore`'s lease.
- [x] Implement provider-neutral capabilities and controlled fake partner. `integration.api.delivery.DeliveryPartner` with `DeliveryOperation`, and `RecordingPartnerServer` as the controlled fake both adapter test classes and `DeliveryRouteTests` drive.
- [x] Implement quote filtering/scoring and single-winner compare-and-set. `QuoteScoring` is pure and versioned; the compare-and-set is `JdbcAssignmentStore.win`.
- [ ] Implement or explicitly defer the internal courier model and legacy courier disposition. The courier model is built by ADR 0042/0045 (V0040, V0041, the `courier` and `telemetry` modules) and the seam is now closed: `courier.infrastructure.dispatch.InternalFleetAdapter` implements `fulfillment.api.InternalFleetPort`, so `SourcingPlanner`'s in-house branch is taken in production and a courier on shift is offered the order before any partner is called. What remains open under this box is the fleet's reach — a courier is enumerated only through an open shift at the branch, since ADR 0042's roster and availability tables are not built — and the legacy courier disposition, still neither built nor explicitly deferred.
- [x] Implement first real partner adapter with uncertainty reconciliation. `NoorDeliveryAdapter` and `YandexDeliveryAdapter` classify a request that reached the partner as `UNCERTAIN` and resolve by query rather than retry. Production code now reaches both: `DeliveryPlanTrigger` opens the plan, `DeliverySourcingScheduler` claims the job, and `DeliverySourcingService` books through `CamelShipmentBookingPort`, against an `integration.bindings` row `ProviderInstallationController` can author.
- [ ] Implement Operations APIs, tracking, recovery triggers, audit, metrics, and alerts. `ProviderCircuitMetrics` and ADR 0045's courier tracking endpoints exist, and a failed sourcing pass opens one `fulfillment.delivery_exceptions` row per plan; `fulfillment.web` holds only the ADR 0037 tariff, fee and zone controllers, so there is no sourcing Operations API to read that exception from, no partner tracking callback, and no sourcing alert.
- [ ] Add timing, duplicate, uncertainty, cost, fallback, restart, and isolation tests. All but cost. `DeliverySourcingTests` covers timing (a job before its due time is not claimed; a revised estimate moves it), duplicate (a replayed tick does not book twice; two bookings produce one shipment; an answered attempt is never resent), fallback (the cheapest quoting partner wins; a partner that refuses a quote is not booked), restart (a dead worker loses its lease and a lost lease cannot finish somebody else's job) and isolation (a plan is not readable by another tenant); uncertainty and gateway classification are covered by the adapter tests. Cost cannot be tested until a subsidy line exists to write.

## Exit criteria

For a confirmed order with a two-hour preparation estimate, Qoida creates a
durable pickup plan, sources at the correct time, assigns exactly one capable
internal courier or external partner, reconciles uncertainty before fallback,
preserves the customer fee, and exposes a safe manual path when automation
cannot meet the promise. Any retained legacy courier workflow has an approved,
privacy-safe target or explicit retirement path.
