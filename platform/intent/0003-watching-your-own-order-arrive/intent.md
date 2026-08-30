# Intent: watching your own order arrive

- **Originator:** surfaced while scoping the storefront against the legacy client, 2026-08-28; the decision is the platform owner's
- **Date:** 2026-08-28
- **Status:** Draft
- **Delivered by:** —

## The problem

A customer who has paid for a delivery cannot see where it is. The legacy client had
`orders/pages/track-order-page` and `orders/pages/driver-information-page`; the new storefront
has neither, and there is no endpoint behind them.

What they do instead today is what people always do: they phone the branch. That is a support
cost the platform is choosing to pay, and it is worst exactly when the delivery is late, which
is when the branch is busiest.

The platform already knows where the courier is. `telemetry` holds live positions and stored
tracks (V0041), `TrackRetentionSweeper` expires them under ADR 0029, and
`OperationsCourierPositionController` draws them on a dispatcher's map. So this is not a
question of collecting anything new. It is a question of who may see what, and that question is
genuinely hard, which is why this is an intent rather than a ticket.

## Evidence

- `frontend/milliy/client/src/app/orders/pages/track-order-page` and `driver-information-page`
  existed in the system this replaces. Customers had this and no longer do.
- `telemetry/web/` holds `CourierDutyController`, `OperationsCourierPositionController` and
  `OperationsStreamController` — all operations surfaces. Nothing customer-facing.
- ADR 0045 requires every reveal of a stored track to be granted **per person, for a declared
  purpose, with an ADR 0027 audit entry**. `COURIER_TRACK_REVEAL` is in
  `PlatformRoleTests.GRANTED_ONLY_PER_PERSON` and is excluded even from the superuser bundle —
  `PLATFORM_ADMIN` is `EnumSet.complementOf(EnumSet.of(COURIER_TRACK_REVEAL))`, and the test
  `aSuperuserDoesNotSilentlyHoldTheTrackReveal` exists to keep it that way. That is the most
  carefully guarded capability on the platform and this intent proposes to let customers see
  something adjacent to it.

## What "solved" looks like

A customer with a delivery in flight can tell, without phoning anyone, that it is coming and
roughly how far away it is — and a courier's movements are no more legible to that customer
than they were before the order existed.

Both halves are the outcome. A tracking screen that leaks a self-employed person's movement
history is a worse result than no tracking screen.

## Scope

- **In:** what a customer may see about the courier bringing their own order, for how long,
  and at what resolution; how it is authorised, given that they hold no grant; what happens
  after delivery.
- **Out:** the dispatcher's map, which exists. The stored-track reveal under ADR 0045, which is
  a different act with a different audit trail. Rating the driver — that is
  [0004](../0004-rating-what-happened/intent.md). Chat between customer and courier.

## Affected areas

`telemetry` (the read and its rules), `fulfillment` (which order is in flight and whose it is),
the storefront's order-detail screen, ADR 0045, ADR 0042, ADR 0029 and ADR 0025's
`@CustomerOwned` model.

## Constraints and open questions

- **A courier is a self-employed person, not a parcel.** ADR 0042 is built on that and it is
  not a formality — it is why a manager may not open a courier's shift. A customer watching a
  dot is watching a named individual work.
- **There is a precedent for a narrower answer already in the tree, and it may be the whole
  design.** `telemetry.api.CourierProximityPort`, added 2026-08-26, answers *how far a courier
  is from a branch in metres, and never a coordinate* — "a circle, not a pin" — so dispatch can
  prefer the nearer courier without a position leaving ADR 0045's location-scoped capability.
  The same move may work here: a distance and an estimate rather than a live pin. Whether a
  customer needs a map at all, or only "eight minutes away", is the first question to answer,
  and it is a product question before it is a privacy one.
- **The window.** In flight is not the same as forever. When does it open — at pickup from the
  branch, or at assignment — and when does it close? What does the screen say afterwards?
- **Authorisation.** The customer holds no `iam.grants` row; this is `@CustomerOwned` territory,
  and the check is a row comparison — this order, mine, still out for delivery. ADR 0049 is the
  precedent for what a non-staff principal may do.
- **`LivePositionRules.freshEnoughForTheMap` and `drawable` already exist** and decide what a
  dispatcher may see. Whether the customer's rules are the same rules or deliberately coarser
  is an open question; if they are the same, say so on purpose rather than by reuse.
- **Retention.** Whatever the customer sees must not create a second store of positions with
  its own lifetime. `TrackRetentionSweeper` owns the ADR 0029 window.

## Why not do nothing

The cost is paid by the branch, in phone calls, during the busiest hour. It is also the single
legacy screen customers are most likely to notice missing, because it is the one they opened
while waiting rather than while ordering.

Doing nothing is nevertheless a legitimate answer if the honest conclusion is that no reveal is
narrow enough to be safe. If so, that conclusion should be written down as a decision rather
than left as a gap somebody re-proposes every quarter.
