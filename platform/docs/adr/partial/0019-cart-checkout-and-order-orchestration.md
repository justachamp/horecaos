# ADR 0019: Cart, checkout, and order orchestration

- Decision status: Accepted
- Implementation status: Partial — V0022 (extended by V0023, V0029 and V0056)
  and the `ordering` module build the cutover-blocking subset: cart, cart
  pricing, idempotent checkout (`ordering.checkout_attempts` unique on
  `(tenant_id, idempotency_key)`), immutable order snapshots, `OrderStateMachine`,
  restaurant approval with a durable timeout (`OrderProcessWorker`, claims
  `FOR UPDATE SKIP LOCKED`), the `ORDER_INVENTORY` process manager, amendments and
  outcome reasons, and both the storefront and Operations APIs. **Two things
  that used to close this record have shipped.** V0056 adds
  `ordering.cart_fulfillment`: `CartService.setDestination` captures a delivery
  address as an ADR 0029 copy rather than a reference, `CheckoutService` refuses
  a delivery cart with `DELIVERY_DESTINATION_REQUIRED` and snapshots the
  destination onto the order, and `JdbcDeliveryOrderPort` hands it to ADR 0014
  sourcing — so delivery is no longer out of the first slice. And the storefront
  is now authorised by ownership: every `StorefrontOrderingController` route
  carries `@CustomerOwned` over `PrincipalCustomer` and declares `@Idempotent`
  where it mutates, instead of the ADR 0025 capability no customer could hold.
  Confirmation also fans out now, though not through `ordering.order_processes`:
  `PosOrderExportTrigger`, `DeliveryPlanTrigger` and `OrderNotificationTrigger`
  are transactional listeners on `OrderConfirmed`. Not built: the process-manager
  rows themselves — `ORDER_PAYMENT`, `POS_ORDER_EXPORT`, `ORDER_FULFILLMENT` and
  `ORDER_NOTIFICATION` are named in `ck_order_process_name` and enqueued by
  nothing, so only `ORDER_INVENTORY` has durable resumable state; the edge out of
  `PAYMENT_AUTHORIZING` (ADR 0013 presents a checkout link, but no callback moves
  the order on, so a card order stops there); scheduled orders, which is why
  V0056 leaves the requested-time column out; guest carts; and legacy shadow
  comparison.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), product
- Depends on: ADR 0002, ADR 0013, ADR 0014, ADR 0015, ADR 0016, ADR 0017, ADR 0018
- Supersedes / Superseded by: —
- Open inputs: Checkout payment timing, cancellation, approval timeout, and scheduled-order lead-time policy (product)

## Context

The existing order-acceptance policy defines two channels: automatic acceptance
and restaurant approve/reject. The platform still needs a coherent cart,
checkout, immutable commercial order, payment/reservation coordination, and
post-confirmation orchestration model. Checkout crosses catalog, pricing,
inventory, payments, notifications, POS export, and fulfillment; a large SQL
transaction or synchronous chain of external calls would be fragile.

## Decision

Ordering owns mutable carts, an idempotent checkout decision, immutable order
commercial snapshots, and the authoritative order state machine. The initial
checkout transaction calls only local module ports backed by the same
PostgreSQL database: revalidate publication/context, accept a pricing quote,
reserve inventory, create the order, create required local payment intent data,
and insert outbox events. No provider, Kafka, Keycloak, POS, or delivery HTTP
call occurs inside that transaction.

After commit, durable event-driven process managers coordinate payment,
restaurant approval, POS export, notification, inventory commitment, and
delivery. Each aggregate remains authoritative for its own lifecycle.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| One transaction spanning payment, POS, and delivery calls | Holds database locks across network calls, and any partial failure leaves state nobody can reconstruct | Never |
| A single saga aggregate for the whole order lifecycle | One stuck concern, such as a POS export, would block payment, notification, and fulfillment progress for that order. Per-concern process managers fail independently | Never |
| Pure choreography with no persisted process state | Nothing can answer "why is this order stuck", which is the most common operational question in food delivery | Never |
| Allow editing lines on a confirmed order in the first release | Mutating financial history, with cascading effects on payment, fiscal receipts, inventory, and POS. Replacement orders express the same intent safely | A properly modelled amendment flow with its own financial semantics is accepted in a later ADR |
| Wait for POS export before confirming to the customer | A POS outage becomes a customer-facing checkout outage, and the restaurant loses revenue for an integration problem | Never |
| Let POS or delivery status write the order status directly | Two authorities for one state machine, and a provider bug becomes a commercial fact | Never; they propose transitions |
| Idempotency by request hash instead of a client-supplied key | Two legitimately different carts can hash identically after normalization, and a retried request with a trivial difference would create a second order | Never |
| Keep the cart when the customer switches location | Catalog, availability, tax, fees, and promise all change. Silently carrying lines across would show prices that do not apply | Never; rebuild and reprice |

## Cart model

```text
ordering.carts
  id, tenant_id, brand_id, location_id
  customer_account_id null, guest_reference_hash null
  channel, fulfillment_mode, currency, status
  catalog_publication_id, version, expires_at, timestamps

ordering.cart_lines
  id, tenant_id, cart_id, variant_id, quantity
  selected_modifier_snapshot, customer_note null, version, timestamps

ordering.cart_fulfillment
  cart_id, type, address_id null, requested_time null
  contact_reference null, instructions_encrypted null, version
```

One cart belongs to one tenant, brand, and location. Moving location rebuilds
and reprices a new cart because catalog, availability, tax, fee, and promise may
change. Guest-to-account claim follows ADR 0015.

Cart lifecycle:

```text
ACTIVE -> CHECKOUT_IN_PROGRESS -> CONVERTED
ACTIVE -> EXPIRED | ABANDONED
CHECKOUT_IN_PROGRESS -> ACTIVE when a recoverable validation fails
```

## Order model and immutable snapshots

```text
ordering.orders
  id, public_order_number, tenant_id, brand_id, location_id
  customer_account_id null, channel, fulfillment_mode
  acceptance_mode_snapshot, acceptance_policy_id/version
  status, payment_status_projection, fulfillment_status_projection
  currency, subtotal/tax/discount/fee/total_minor
  pricing_quote_id, catalog_publication_id
  idempotency_key, version, created_at, confirmed_at null, closed_at null

ordering.order_lines
  id, tenant_id, order_id, source_product_id, source_variant_id
  product/variant name snapshots, sku snapshot null
  quantity, unit/base/final amounts, tax snapshot, note snapshot

ordering.order_line_modifiers
  order_line_id, source_group_id, source_option_id
  names, quantity, unit/final amount snapshots

ordering.order_adjustments
  order_id, order_line_id null, sequence
  adjustment_type, source_id/version, description_code, amount_minor

ordering.order_customer_snapshots
  order_id, display/contact/address encrypted snapshots, consent-safe fields

ordering.order_state_history
  id, tenant_id, order_id, from_status, to_status
  trigger, reason_code, actor, occurred_at, correlation_id
```

Historical names, prices, taxes, modifiers, address/contact, acceptance policy,
and fulfillment promise do not change when source entities change. Sensitive
snapshots follow an order-specific retention/anonymization policy.

## Checkout transaction

`POST /api/v1/storefront/checkouts` requires a tenant-scoped idempotency key,
cart ID/version, and quote ID. In one transaction:

1. Lock the cart/idempotency record and return the prior result if completed.
2. Reauthorize tenant/brand/location and validate cart ownership/expiry.
3. Confirm active publication, offering, fulfillment, and quote context.
4. Reserve inventory through ADR 0017's local application port.
5. Atomically accept coupon/benefit reservations and the ADR 0018 quote.
6. Create order and complete immutable snapshots.
7. Create a provider-neutral payment intent when payment is required.
8. Mark cart converted and insert `OrderReceived` in the outbox.

Business rejection rolls back all steps. An unexpected failure leaves the same
idempotency record retriable. External payment initiation starts only after
commit and uses its own idempotency/reconciliation from ADR 0013.

## Authoritative order state machine

Use the canonical statuses already accepted in `docs/domains/state-machines.md`:

```text
RECEIVED -> PAYMENT_AUTHORIZING
RECEIVED -> AWAITING_APPROVAL          (offline payment, approval mode)
RECEIVED -> CONFIRMED                  (offline payment, auto-confirm)

PAYMENT_AUTHORIZING -> AWAITING_APPROVAL | CONFIRMED | PAYMENT_FAILED
AWAITING_APPROVAL -> CONFIRMED | REJECTED | EXPIRED

CONFIRMED -> PREPARING -> READY
READY -> FULFILLING -> COMPLETED       (delivery)
READY -> COMPLETED                     (pickup)

RECEIVED/AWAITING_APPROVAL -> CANCELLED
CONFIRMED -> CANCELLED when policy permits
```

Payment/cancellation commands that are still in flight and operational manual
action are process-manager states, not alternate order statuses. Exact
transition eligibility, payment capture/void/refund consequence, inventory
commit/release, and timeout action come from versioned policy snapshots. POS
status and delivery status may propose transitions but never directly overwrite
the order.

## Restaurant approval process

Both Operations UI and configured restaurant channels can approve/reject. The
first valid command wins under order version compare-and-set. Approval commands
contain order ID, action, actor/channel, decision ID, issued time, and
idempotency key. Timeouts use a durable PostgreSQL job. Late duplicate actions
return the settled outcome and do not re-run side effects.

Automatic channel confirms according to payment policy without restaurant
approval. In either channel, the customer receives confirmation from Qoida;
asynchronous POS export does not gate confirmation. Export failure creates a
visible exception and retry/reconciliation workflow.

## Process managers

Persist one process state/checkpoint per order and concern rather than one giant
saga. Initial managers:

```text
OrderPaymentProcess
RestaurantApprovalProcess
OrderInventoryProcess
PosOrderExportProcess
OrderFulfillmentProcess
OrderNotificationProcess
```

Each consumes through ADR 0005 inbox, emits through outbox, uses stable command
IDs, and records `WAITING`, `COMPLETED`, `FAILED_RETRYABLE`,
`MANUAL_ACTION_REQUIRED`, or terminal compensated status. Rebuilding a process
from history must not repeat provider effects.

## Scheduled/pre-order semantics

Legacy `vendors.pre_order`, cart delivery-time selection, and `orders.planned_time`
require an explicit target policy. A scheduled request stores the UTC instant,
location IANA timezone, submitted local representation/offset, schedule/capacity
version, preparation estimate, promise window, and calculation version. Validate
minimum/maximum lead time, opening/exception schedule, fulfillment capacity,
catalog/price validity, payment authorization lifetime, inventory reservation
strategy, and delivery sourcing horizon.

Long-lead orders normally do not hold volatile quantity or payment
authorization for the whole wait. A durable process revalidates/reprices under
the customer-approved policy and reserves/authorizes at configured checkpoints;
any customer-visible price/substitution change requires explicit acceptance or
safe cancellation. DST/offset ambiguity is rejected or resolved using a
documented policy. Import maps legacy planned times only after the source
timezone and naive-timestamp semantics are proven.

## Cancellation and modification

The first release does not mutate confirmed order lines. Before confirmation,
cancellation releases inventory and pricing reservations and voids/refunds as
required. After confirmation, cancellation creates explicit payment,
inventory, POS, and fulfillment commands based on current states. If a customer
wants changed items, create a replacement order/link or a later accepted
amendment model; never edit financial history in place.

## APIs

```text
POST   /api/v1/storefront/carts
PUT    /api/v1/storefront/carts/{cartId}/lines/{lineId}
DELETE /api/v1/storefront/carts/{cartId}/lines/{lineId}
POST   /api/v1/storefront/checkouts
GET    /api/v1/storefront/orders/{orderId}
POST   /api/v1/storefront/orders/{orderId}/cancellations

GET  /api/v1/operations/locations/{locationId}/orders
POST /api/v1/operations/orders/{orderId}/approval-decisions
POST /api/v1/operations/orders/{orderId}/state-actions
GET  /api/v1/operations/orders/{orderId}/timeline
```

Storefront order lookup verifies customer/guest proof. Operations mutations
require the relevant ADR 0025 capability at location scope, a reason, an
`Idempotency-Key`, and the expected order version, per ADR 0031.

## Events

```text
OrderReceived
OrderAwaitingApproval
OrderApproved/Rejected/Expired
OrderConfirmed
OrderPreparationStarted
OrderReady
OrderCompleted
OrderCancellationRequested/Cancelled
OrderManualActionRequired
```

Events carry order/scope IDs, state/version, policy references, timestamps, and
minimum required totals; no address, phone, customer notes, or full line payload.
Consumers query an authorized API/projection when more detail is necessary.

## Testing

- Identical concurrent checkout requests return one order; different payloads
  under one idempotency key are rejected.
- Any validation failure leaves no partial order/reservation/benefit acceptance.
- Payment timeout/uncertainty, duplicate Kafka delivery, and worker restart do
  not duplicate orders, captures, exports, or notifications.
- Approval UI/channel/timeout races yield one valid transition and consequence.
- Both AUTO and RESTAURANT_APPROVAL paths work with payment policy combinations.
- POS export failure does not unconfirm the customer order and is visible.
- Cancellation settles inventory/payment/fulfillment exactly once.
- Cross-tenant/brand/location/customer reads and foreign keys fail.

## Rollout and rollback

Replay legacy sanitized orders into new read-only snapshots, then shadow-create
new orders without external side effects and compare totals/states. Enable one
brand/location/channel behind a writer-ownership flag. Rollback stops new
checkouts on the new owner and drains/reconciles every already-created order;
those orders never switch back to a legacy writer mid-lifecycle.

## Consequences

### Positive

- A retried checkout produces exactly one order, and a failed checkout leaves no
  partial reservation, benefit consumption, or orphan payment intent.
- Both acceptance channels reach deterministic outcomes with one winner.
- Provider and POS failures become visible exceptions instead of customer-facing
  checkout errors.

### Negative

- The checkout transaction spans catalog, pricing, inventory, and payment ports
  in one database transaction. This is the tightest coupling in the platform and
  makes ordering the hardest module to ever extract into a service.
- Six process managers per order concern is a lot of state to observe, and a
  stuck process manager needs its own operational tooling.
- Immutable orders mean any correction is a new fact, so support workflows are
  wordier than editing a row.

### Accepted trade-offs

- Scheduled and pre-orders do not hold inventory or payment authorization for
  the full wait, so a long-lead order may reprice or require re-acceptance.
- Confirmed orders cannot be edited in the first release, so item changes become
  replacement orders even when a customer expects an edit.

## Implementation checklist

- [ ] Approve checkout payment timing, cancellation, timeout, and modification policies.
- [ ] Approve scheduled/pre-order lead-time, reprice, reservation/payment timing, capacity, and timezone policy.
- [x] Finalize canonical state-transition and consequence table for both channels.
- [x] Add cart, order snapshot/history, idempotency, and process-state tables.
- [x] Implement cart and order aggregates with raw-SQL optimistic repositories.
- [x] Implement transactional checkout across local module ports and outbox.
- [x] Implement durable approval timeout and first-winner command handling.
- [ ] Implement separate payment, inventory, POS, fulfillment, and notification managers.
      Only `ORDER_INVENTORY` is a process manager — `OrderInventoryProcess` is still the
      sole caller of `JdbcOrderProcessStore.enqueue`, so it alone has a checkpoint, an
      attempt ladder and a resumable claim. Three of the remaining four concerns are
      nevertheless driven now, by `BEFORE_COMMIT` transactional listeners on
      `OrderConfirmed` rather than by rows in `ordering.order_processes`:
      `PosOrderExportTrigger` (`pos`) opens the export and hands the send to a
      scheduler after commit, `DeliveryPlanTrigger` (`ordering`) calls ADR 0014's
      `DeliveryPlanner`, and `OrderNotificationTrigger` (`notifications`) writes an
      ADR 0020 intent for `OrderConfirmed` and `OrderRejected`. Each keeps its durable
      state in its own module's table, so `POS_ORDER_EXPORT`, `ORDER_FULFILLMENT` and
      `ORDER_NOTIFICATION` remain names in `ck_order_process_name` that nothing writes,
      and the work they stand for has no ordering-side attempt ladder or stuck list.
      `ORDER_PAYMENT` is driven by nothing at all, which is the same gap as the
      unhandled edge out of `PAYMENT_AUTHORIZING`.
- [x] Build storefront and Operations APIs, timeline, audit, metrics, and alerts.
      `StorefrontOrderingController`, `OperationsOrderController` and
      `OrderOutcomeReasonController`, with the timeline and audit facts; ADR 0023's
      `OrderFlowMetrics` now gauges orders by state and the age of the oldest one.
      No ordering alert rule exists, deliberately — ADR 0023 refuses to page on a
      state count at one pilot tenant.
- [ ] Build legacy shadow comparison and per-location ownership controls.
- [ ] Add transaction, race, restart, duplicate, state, isolation, and load tests.
      `CartCheckoutAndOrderTests`, `OrderAmendmentAndOutcomeTests`,
      `OrderAcceptancePolicyServiceTests`, `OrderPromiseTests`,
      `OpenOrderIndexAgreementTests` and `OrderInventoryProcessTests` — the last
      covering the process manager's failure behaviour on a fixed clock: a checkpoint
      this version cannot run is quarantined as `FAILED_RETRYABLE` with its
      instruction intact instead of failing the batch and rolling the claim back, the
      eight-attempt ladder ends at `MANUAL_ACTION_REQUIRED` rather than looping, and a
      replayed `RELEASE` against a hold that is already gone settles as `COMPLETED`.
      All but load tests.

## Exit criteria

A retried checkout creates exactly one correctly priced and reserved immutable
order; both automatic and restaurant-approval channels reach deterministic
outcomes; provider/POS failures are reconciled asynchronously; and every state,
financial consequence, and manual intervention is explainable without mutating
historical commercial facts.

## Implementation notes

Written after the first implementation, and normative where it contradicts the
text above: the text was written before the code existed, and three of its
statements turn out to be wrong about the platform that now exists.

### What was built

Migration **V0022** and the `ordering` module implement the subset
`docs/minimum-viable-cutover.md` names as blocking: cart, checkout, order
snapshots, the state machine, restaurant approval, and the inventory process
manager.

The seams are the point, and each is settled in PostgreSQL rather than in
application code: `ordering.checkout_attempts` has a unique key on
`(tenant_id, idempotency_key)`, `ordering.orders` has one on
`(tenant_id, pricing_quote_id)` and another on `(tenant_id, cart_id)`, and
`ordering.approval_decisions` has a partial unique index on `effective`. Every
status change is a conditional `UPDATE` naming the status it expects, and the row
count decides the winner.

### Where the built code departs from the decision above

**"Business rejection rolls back all steps" is not implemented as a rollback.**
It cannot be. A rollback would also roll back the idempotency record, and the
ADR's own requirement that a retry return the same rejection would then fail: the
retry would run again against a cart that has since changed. `CheckoutService`
instead performs every read-only validation before it mutates anything and
compensates explicitly for the two mutations that can precede a refusal — it
releases the inventory hold and the capacity slot. The observable property the
ADR asks for holds: a refused checkout leaves no order, no accepted quote, no
committed reservation, and no capacity slot.

**The step order differs, and had to.** The ADR reserves inventory (step 4) and
accepts the quote (step 5) before creating the order (step 6), which leaves no
point at which the ADR 0036 concurrent-order ceiling can be claimed against an
order id. The implementation claims the kitchen slot under a pre-generated order
id *before* accepting the quote, so an `AT_CAPACITY` refusal cannot strand an
already-accepted quote that nothing can now use.

**The API paths differ.** The ADR's `POST /api/v1/storefront/carts` has nowhere
to put the tenant, and both the ADR 0025 capability interceptor and the ADR 0031
idempotency interceptor derive their scope from path variables. Storefront
ordering therefore sits under
`/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/…`, matching the shape
`StorefrontCatalogController` already set. A `POST …/carts/{cartId}/pricing`
endpoint was added, which the ADR does not list: the ADR has checkout take a
quote id from the client, and nothing in it binds that quote to the cart. Pricing
through the cart is what makes the binding checkable, so a client cannot present
a quote priced for a different, cheaper basket.

**Two capabilities were added to the ADR 0025 registry.** There was no capability
for placing an order and none for moving a confirmed order along the kitchen path;
`ORDER_PLACE` and `ORDER_ADVANCE` fill both gaps. `ORDER_ADVANCE` is deliberately
separate from `ORDER_STATE_OVERRIDE`: every line cook needs the first and almost
nobody should have the second.

**`tenant.location_capacity_holds` was not dropped.** V0020's comment says it
should be, and that the count should become a count of open orders once
`ordering.orders` exists. That is wrong about the dependency direction: tenancy is
read by catalog, pricing, inventory and ordering, and making `ServiceabilityService`
count rows in `ordering.orders` would put a business module underneath the module
every other one depends on. The table stays and ordering owns its lifecycle
instead — the hold id is now the order id, claimed inside the checkout transaction
and released when the order stops occupying the kitchen. There is still exactly
one counted set, which is the property the comment was protecting.

### Corrections to built code that this work found

`InventoryService.reserveForQuote` treated *any* existing reservation row for a
quote as a live hold, including one that had been released or swept as expired —
the uniqueness constraint is on the owner regardless of status. Checkout would
have believed it held stock, created an order, and the later commit would have
returned false with nothing reserved. A lapsed hold is now a refusal
(`RESERVATION_NO_LONGER_HELD`), and the customer re-prices rather than being sold
a promise inventory never made.

### Deliberately not built

POS export, delivery sourcing, scheduled and pre-orders, cancellation or
amendment after confirmation, and notifications. Payment is the only one of these
that checkout has to have an opinion about, so it is a declared port —
`ordering.api.PaymentIntentPort` — following the house pattern set by
`CatalogPricingConfiguration`. The stand-in that reports itself unwired is still
there behind `@ConditionalOnMissingBean`, and a deployment assembled without
payments still carries `PAYMENT_INTENT_NOT_WIRED` on every checkout result and
every order read, takes the `PAYMENT_AUTHORIZING` branch for nothing, and books
every order `NOT_REQUIRED` rather than `PENDING`.

**Amended once ADR 0013 shipped.** `payments.application.PaymentIntentService`
implements the port, so a complete build no longer takes the stand-in. Step 7
creates a real intent, and three things follow from the capture timing the port
answers with:

- Cash is confirmed by checkout exactly as before and collected at handover. Its
  intent is real and so is the ADR 0038 `NOT_APPLICABLE` fiscal document beside
  it, but the order is booked `NOT_REQUIRED`, because nothing will ever capture a
  cash intent and a `PENDING` cash order would sit on the operations list for
  ever looking like a stalled provider.
- Click and Payme leave checkout in `PAYMENT_AUTHORIZING`, booked `PENDING`. The
  restaurant is not asked and the kitchen is not started until the money arrives.
- A method whose merchant account does not resolve is refused among the read-only
  validations, as `PAYMENT_METHOD_UNAVAILABLE`, rather than after a kitchen slot
  and a quote have been spent on an order that could never be paid. While ADR
  0038's legal entity is unbuilt no seller resolves, so this is every provider
  method on every channel — which is what `PaymentLegalEntityConfiguration`
  already declares, now enforced at the one place a customer meets it.

What is still missing is the far half of the edge out of `PAYMENT_AUTHORIZING`.
ADR 0013's V0045 work now opens an attempt after commit and presents a checkout
link — `POST .../orders/{orderId}/payment-sessions` — but **nothing moves the
order on when the provider credits it**: `ClickCallbackProcessor` and
`PaymeMerchantApi` capture the attempt and neither calls back into ordering, so a
paid card order stays in `PAYMENT_AUTHORIZING`. Until that ships, a provider
method is in practice refused at serviceability by the absence of a merchant
binding — nothing writes `payments.merchant_bindings` — rather than reaching the
branch.

The five process managers other than `ORDER_INVENTORY` are named in the
`ck_order_process_name` constraint and written by nothing. A row for a process
whose ADR has not landed is refused rather than silently accepted.

`ordering.cart_fulfillment` from the cart model above is **not** created. Nothing
in this release captures a delivery address — the storefront collects a location
and a basket, and ADR 0014 delivery sourcing is outside the first slice — so the
table would be schema with no writer, reading as a capability that exists. It
arrives with the address capture it exists to hold. The practical consequence is
that a `DELIVERY` cart can be opened and priced but carries no address, so the
first slice takes pickup and dine-in orders.

Guest carts are supported by the schema and refused by the storefront controller:
ADR 0015's guest claim is outside the first slice, and a guest reference this
release invented would have no path to becoming an account later.
