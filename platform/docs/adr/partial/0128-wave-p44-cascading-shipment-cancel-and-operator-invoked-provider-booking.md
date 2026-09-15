# ADR 0128: Cascading shipment cancel and operator-invoked provider booking

- Decision status: Proposed
- Implementation status: Partial
- Date proposed: 2026-09-15
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0014, ADR 0025, ADR 0027
- Supersedes / Superseded by: —
- Open inputs: whether the order-cancellation HTTP call may block on the
  provider's own cancel round trip (Ayubkhon Abbosov, platform owner) —
  see Open input 1 below; whether a `PROVIDER_CANCEL_FAILED`/`PROVIDER_
  CANCEL_UNCERTAIN` exception should page anyone rather than sit on the
  dispatch board until it is next opened (operations, owner: platform owner)

## Context

ADR 0014 built the whole automated sourcing chain — planning, quoting,
booking, the single-winner compare-and-set — but left two seams open that
gap map rows `1.2g` and `1.2f` name specifically:

1. **Cancelling an order never told the courier provider.** `DeliveryGateway
   .cancelShipment` and the adapters' own `CANCEL_SHIPMENT` capability
   existed, but nothing above them ever called it: an order cancelled in
   HorecaOS left `fulfillment.shipments` however automated sourcing last
   wrote it, and a Noor or Yandex courier already en route stayed en route
   with no operator visibility at all.
2. **There was no operator-invoked provider booking.** `DeliverySourcingService
   .execute` is the only caller of `ShipmentBookingPort.book`, so there was
   no moment at which a human could see what an external partner would
   charge and choose to accept or refuse it before the booking committed —
   the "Millenium pattern" the frontend IA names by name and the only seam
   at which a merchant controls its own delivery cost against a provider
   whose price can exceed the customer's own snapshotted fee.

Both gaps sit on the same boundary this ADR has to cross: the courier
provider is an external HTTP call (Noor, Yandex), and the platform's own
rule — enforced elsewhere by `ExternalCallTransactionBoundaryTests` — is
that no pooled database connection is held across a call this codebase does
not control. An order cancellation and a manual booking both start as an
ordinary `@Transactional` write; closing either gap means deciding, for the
first time on this boundary, exactly where the transactional write ends and
the network call begins.

## Decision

**Cascade the cancel after the order transaction commits, from the
controller, never from inside the write.** `OperationsOrderController.cancel`
calls `OrderOutcomeService.cancel`/`OrderStateService.cancel` (unchanged,
still `@Transactional`) to completion first; only once that call has
returned — its transaction already committed — does the controller call
`ShipmentCancellationPort.cancelForOrder`, a plain synchronous method with
no transactional annotation of its own. This is the same shape ADR 0014's
own `DeliveryPlanner`/`DeliveryPlanTrigger` inversion uses to cross the
`fulfillment`/`ordering` module boundary without a cycle, chosen over a
`TransactionalEventListener` at `AFTER_COMMIT` (see Alternatives) because a
listener's return value cannot be threaded back into the HTTP response the
operator is waiting on, and the response is exactly where "was the courier
told" has to show up (gap map row 1.2g's own wording).

**Classify every cancel outcome into what may safely mark a shipment
cancelled, and what may not.** Only a clean provider `CANCELLED` or
`CANCELLED_WITH_COST` answer moves the shipment to `CANCELLED` and the plan
to `CANCELLED`. `UNCERTAIN`, `REJECTED` and `RETRYABLE` all leave the
shipment exactly as it was and instead raise a
`fulfillment.delivery_exceptions` row and move the plan to
`MANUAL_ACTION_REQUIRED` — the same honest "sourcing could not settle this;
a human must" contract ADR 0014 already uses for a booking it could not
confirm, applied here to a cancel it could not confirm.

**Build the operator-invoked booking as two calls, never one, and never
priced by the caller.** `quote` (side-effect-free, one partner) persists a
`fulfillment.delivery_quotes` row exactly like an automated tick; `book`
takes a `quoteId`, not a price, and re-reads the persisted figure before
booking — a client cannot accept a re-quoted increase implicitly because
the request it sends carries no price to accept. Acceptance reuses
`SourcingJournal.openPartnerAttempt`/`settlePartnerAttempt`, the exact
primitives automated sourcing wins a shipment with, and — when the accepted
price exceeds the plan's snapshotted customer fee — records the same
`DELIVERY_COST_SUBSIDY` fact an automated booking would have, so a manually
accepted price increase is counted in the same cost-subsidy reporting ADR
0014 already built rather than a silent gap next to it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A `fulfillment` listener on `OrderCancelled` | Closes `fulfillment -> ordering -> pricing -> fulfillment` into a cycle `ModularArchitectureTests` refuses — pricing already depends on fulfilment for ADR 0037's delivery fee, and ordering depends on pricing. The same reason `DeliveryPlanTrigger` lives in `ordering.application` rather than as a fulfilment listener | Never, unless the pricing-on-fulfilment dependency is removed first |
| A `TransactionalEventListener(AFTER_COMMIT)` cascade, published from inside the order-cancel transaction | Runs synchronously before the transaction's caller regains control, so it is not unsafe — but a listener has no return channel back to the publisher, so the HTTP response could not report what happened to the courier without a fragile thread-local or request-scoped handoff. The controller-orchestrated call is the same commit-ordering guarantee with a plain return value | If the response no longer needs to report the outcome (e.g. it moves entirely to the order-detail's own exception band and polling), an event listener becomes the simpler shape |
| Run the provider cancel inside the order's own `@Transactional` cancel method | Holds a pooled connection across a Noor/Yandex HTTP call — the exact anti-pattern `ExternalCallTransactionBoundaryTests` exists to catch in payments, media and onboarding. Ten connections shared across every module; one slow provider call would stall unrelated modules | Never |
| Treat any non-`CANCELLED` provider answer as cancelled anyway (optimistic) | A courier genuinely still carrying the order would read as resolved on the dispatch board, which is the exact silent gap row 1.2g exists to close | Never |
| Let the operator's `book` call accept a client-supplied price | Defeats the entire point of the confirmation seam: an operator's own stale screen, or a compromised client, could commit to a price the platform never actually quoted | Never |
| A single combined "quote-and-book" call | Removes the confirmation step entirely — there would be no moment between seeing the price and creating the live booking, which on Noor (no hold semantics) means a courier is already dispatched by the time a price is shown | Never, unless product explicitly decides the confirmation step is unwanted |

## Consequences

### Positive

- An operator cancelling a dispatched order now finds out, in the same
  response, whether the provider was told, whether it will cost anything,
  or whether a human needs to check by hand — closing the exact silent gap
  gap map row `1.2g` names.
- The platform has its first seam at which a merchant can refuse a delivery
  price increase before it commits, and a refusal or an acceptance both
  leave an audited trail.
- Both seams reuse ADR 0014's existing tables and primitives entirely — no
  new migration, no new state machine, no second implementation of the
  single-winner compare-and-set.

### Negative

- The order-cancellation HTTP call can now block for as long as the
  provider's own cancel round trip takes (`DeliveryGateway`'s default
  20-second timeout), where it previously returned as soon as the order row
  moved. An operator's Cancel click can visibly hang on a slow or degraded
  partner.
- `ShipmentCancellationPort.cancelForOrder` is a best-effort sequence of
  independently-committed statements, not one transaction — a crash between
  the provider call succeeding and the local `CANCELLED` write leaves a
  shipment a human must notice and correct by hand, exactly as
  `DeliverySourcingService.recordSubsidyIfAny`'s own best-effort contract
  already accepts elsewhere in this ADR's implementation.
- `PROVIDER_CANCEL_FAILED`/`PROVIDER_CANCEL_UNCERTAIN` exceptions surface
  only on the dispatch board and the order detail's own exception band —
  nothing pages anyone, so a cancelled order with an uncancelled courier is
  visible only to an operator who opens the right screen.

### Accepted trade-offs

- A blocking cancel call is accepted over an asynchronous one because the
  alternative — reporting "cancelled" before the provider is known to agree
  — is the exact failure mode this ADR exists to close. Open input 1 records
  that the owner may still prefer an async/polling shape once real provider
  latency is measured in production.

## Specification

See `uz.horecaos.platform.fulfillment.api.ShipmentCancellationPort`,
`uz.horecaos.platform.fulfillment.application.ShipmentCancellationService`,
`uz.horecaos.platform.fulfillment.application.ManualExternalBookingService`,
and the `ShipmentBookingPort.cancel`/`CancelCommand`/`CancellationReceipt`
addition in `uz.horecaos.platform.fulfillment.api.ShipmentBookingPort` for
the full shapes. Endpoints: `DispatchController`'s
`POST .../shipments/{shipmentId}/cancel` (`Capability.SHIPMENT_CANCEL`),
`GET .../plans/{planId}/external-partners`,
`POST .../plans/{planId}/external-quote` and
`POST .../plans/{planId}/external-book` (both `Capability
.DELIVERY_MANUAL_ASSIGN`); `OperationsOrderController`'s
`POST .../orders/{orderId}/cancellations` now returns a
`deliveryCancellation` outcome alongside the existing decision fields.

## Rollout and rollback

Both seams are additive: no existing endpoint's behavior changes except
`.../cancellations`' response, which only gains fields (kept backward
compatible per `OpenApiContractTests`). Rollback is deleting the two new
services and the `cancelForOrder` call site; the order cancellation itself
is unaffected either way, since the cascade is a side effect of an
already-committed decision, never a precondition for it.

## Implementation checklist

- [x] `ShipmentBookingPort.cancel` and `CamelShipmentBookingPort`'s implementation over the existing `CANCEL_SHIPMENT` capability.
- [x] `ShipmentCancellationPort`/`ShipmentCancellationService`: cascade from order cancellation, and the dedicated `Capability.SHIPMENT_CANCEL` endpoint.
- [x] `UNCERTAIN`/`REJECTED`/`RETRYABLE` provider answers raise `fulfillment.delivery_exceptions` and move the plan to `MANUAL_ACTION_REQUIRED` rather than guess.
- [x] `ManualExternalBookingService`: quote against the customer fee, and accept/abandon booking at the persisted quote price only.
- [x] Accept records the same `DELIVERY_COST_SUBSIDY` fact an automated booking would.
- [x] Java tests against the migrated schema: cascade on an already-picked-up shipment, an UNCERTAIN cancel landing in `fulfillment.delivery_exceptions`, accept and abandon on a re-quote.
- [ ] Console: the cancel dialog's own provider-cancellation outcome, a delivery-exception band on order detail, and the "call an external courier" action with its quote-delta dialog (frontend; see this wave's report for what shipped).
- [ ] Sandbox-verify Yandex's `cancel-info` cost classification and Noor's own cancel response shape against real provider traffic (still simulated by the fake partner harness everywhere else in ADR 0014).

## Exit criteria

An operator cancelling a dispatched order sees, without leaving the order,
whether the courier provider confirmed the cancellation, whether it is
chargeable, or that a human must check by hand — and an operator calling an
external courier sees the quoted price against the customer's own delivery
fee before any live booking commits, with no path by which a re-quoted
increase is accepted without that screen.

## References

- `docs/operations-gap-map.md` rows `1.2f`, `1.2g`
- ADR 0014 (Implementation status, 2026-09-15 addition)
