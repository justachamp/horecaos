# Intent: principals who are not staff

- **Originator:** surfaced by the adversarial audit of 2026-08-26 (`bb239de`); the decision is the platform owner's
- **Date:** 2026-08-26
- **Status:** Delivered
- **Delivered by:** [ADR 0049](../../docs/adr/built/0049-non-staff-principal-authorization.md)

## The problem

ADR 0025 authorises every mutating endpoint by capability, and a capability is held through
an `iam.grants` row against a principal. That model assumes the principal is a member of
staff. Three kinds of caller are not:

- **a customer** at the storefront, who has an account but no grant row;
- **an aggregator's confidential client** pushing marketplace orders, which authenticates
  with its own credential rather than as a person holding a role;
- **a courier**, who is self-employed and — per ADR 0042 — must open their own shift and
  take their own breaks, because a manager who can create shift state can create paid hours
  for somebody who was at home.

The storefront was moved onto ownership checks (`@CustomerOwned`) during the audit, which
closes the customer case. The other two are open, and six shipped endpoints declare
capabilities that no principal in the system can hold:

- `PartnerOrderController` — `MARKETPLACE_ORDER_RECEIVE`
- `CourierShiftController` — `COURIER_SHIFT_OPEN` (three handlers), `COURIER_SHIFT_BREAK` (two)

With enforcement on, each refuses the very caller it was written for. The partner API and
the courier shift app cannot work at all.

## Evidence

- `PlatformRoleTests.NO_STAFF_PRINCIPAL_HOLDS_THESE` lists eight capabilities and its own
  javadoc calls it "a recorded gap, not a decision", stating that closing it "needs a
  decision about what a non-staff principal is in ADR 0025, which is a change to the model
  rather than a bundle edit". The test exempts them so the build stays green, which is
  correct and is also why nobody has had to look at it.
- Five of the eight are now declared by no endpoint at all — `ORDER_PLACE`,
  `PAYMENT_INITIATE`, `NOTIFICATION_PREFERENCE_MANAGE`, `MARKETPLACE_ORDER_STATUS_PUSH`,
  `MARKETPLACE_MENU_READ`. They are dead capabilities left behind by the ownership move, and
  part of this question is whether they should exist.
- **ADR 0025's `Open inputs` line reads `none`.** It should not; this is an open input and
  the record does not say so. Correcting it is prose in an Accepted ADR, so it needs a human
  rather than a hook-permitted edit.

## What "solved" looks like

An aggregator can push an order and a courier can open a shift, through the ordinary
authorisation path, with the same audit trail any other authorised action gets — and ADR
0025 describes what kind of thing was holding the capability.

## Scope

- **In:** what a non-staff principal is in ADR 0025; how an aggregator's client and a
  courier are authenticated and authorised; what happens to the five dead capabilities.
- **Out:** the endpoints themselves, which exist and are written. The ownership model for
  customers, which shipped. Anything about ADR 0042's employment classification — that is
  settled and is the *reason* a courier holds these rather than a manager.

## Affected areas

`iam` (the principal and grant model), ADR 0025, ADR 0042 and ADR 0045 for the courier
half, ADR 0040 for the marketplace half, `partner` and `courier` web layers,
`PlatformRoleTests` and its exemption list.

## Constraints and open questions

- A courier and an aggregator are different problems wearing one label. A courier is a
  person the platform knows; an aggregator's client is a machine credential. A model that
  treats them as one thing may be wrong.
- ADR 0042 is explicit that a manager may not open a courier's shift. Whatever the answer
  is, it must not become "give the manager the capability".
- Two of the eight — `COURIER_TRACK_REVEAL` and `COURIER_REGISTRATION_REVEAL` — are in a
  different list for a different reason (ADR 0045: granted per person, per purpose, with an
  audit entry) and are **not** part of this. Do not sweep them in.

## Why not do nothing

The partner API and the courier shift app are built, tested, and unreachable. Every day
that stays true is a day the tests pass on code that cannot be used, which is the exact
condition this audit spent its length finding elsewhere.
