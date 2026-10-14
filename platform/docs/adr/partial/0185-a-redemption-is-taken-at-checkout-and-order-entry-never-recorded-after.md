# ADR 0185: A points redemption is taken at checkout and at order entry, and never recorded after

- Decision status: Accepted — proposed by Claude (batch 21); accepted by the platform owner 2026-10-14
- Implementation status: Partial — in progress (batch 21, wave `w12-money`). The record decides a
  decline, so what it asks of a build is the producers that make the decline livable and nothing
  that records a redemption late. Written first; the producers land with this wave and the status
  line is advanced when they are proved.
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: proposed by Claude (batch 21); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0013, ADR 0019, ADR 0025, ADR 0031, ADR 0039, ADR 0046, ADR 0048, ADR 0165
- Supersedes / Superseded by: — (amends ADR 0046 without editing it. That record stays Accepted as
  written; a dated status addition there points here. It closes the one design question the
  operations gap map left on IA row 8.1, "recording a loyalty-redemption leg after checkout", and
  closes it the way ADR 0165 closed the deposit leg: by declining it. ADR 0039's closed amendment
  set is not touched and gains no command)
- Open inputs:
  - **Whether a pilot tenant asks, in writing, to give a customer points after the order was
    placed and paid** (the tenant, through the platform owner). Proposed default: nobody has, and
    the decline stands. This is the trigger, not a gap: see *Alternatives considered*. Blocks:
    nothing.
  - **How a points redemption is shown on a receipt** (finance, legal) is ADR 0046's open input on
    the VAT treatment of a merchant-funded rebate and is not reopened here.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

The `payment[]` array an operator reads on an order (IA row 8.1) lists every tender of the
settlement in sequence, with its status and what has been refunded. It is built and rendered. What
the gap map has kept the row `PARTIAL` for is one sentence: nothing records a loyalty-redemption
leg *after* checkout, and no record decides whether anything should.

The forces, stated as facts about the tree on 2026-10-14:

- ADR 0046 takes a redemption inside the checkout transaction. The points are debited by one
  conditional `UPDATE` before any external tender is initiated, so that releasing them is a local
  write while reversing a captured card payment is a provider refund with an uncertainty window.
  The money leg of the settlement and the payment intent are then told the figure the settlement
  holds (`CheckoutSettlementStep`), never the order total.
- The same record says the redemption is "known before the receipt exists": the fiscal path hangs
  its per-line discount off the redeemed amount, and an order paid in full cannot be re-fiscalised
  to say it was part-paid in points without a correction document.
- ADR 0039 closes its amendment set. It has no command that takes points, and the one primitive
  that moves a settlement's plan later, `OrderSettlementService.restateTotal`, refuses any
  settlement that already has a payment intent: an increase would need an incremental charge and
  a decrease a refund, both provider operations the seam does not perform.
- ADR 0165 established that HorecaOS holds no customer funds and that points are not money. A
  redemption recorded after an order was paid is, in effect, a payment of points against a debt
  that was already discharged in cash: the customer would be owed money back in exchange for
  points, which is the points-to-cash conversion at par that ADR 0046 exists to prevent.
- The two things a late redemption would be for already have a record. Giving a customer value
  after the fact is ADR 0048's refund-as-bookkeeping and remedy model (service-recovery credit,
  future discount), both approval-gated. Giving them the *chance to spend points on this order*
  is a question about the moment of ordering, which the console and the storefronts can answer
  better than any later correction: they had no way to ask what a cart could redeem.

That last point is the real defect behind the row. Nothing in the tree called
`PointsRedemptionPort.quote` outside tests, so no screen could learn the maximum, the refusal
reason or the spendable amount, and a customer could only redeem by a hand-made API call carrying
`redeemFromBalanceMinor`. An operator taking an order by phone could not redeem at all: the
operator checkout passed a literal zero.

## Decision

1. **Decline a redemption leg recorded after checkout (D22).** A points redemption is decided at
   checkout or at operator order entry, inside the transaction that creates the order and its
   settlement, and nowhere later. No command, endpoint or console action adds a points tender to a
   settlement that already exists. Value given to a customer after an order is placed is an
   ADR 0048 remedy, which this record does not change.

2. **The decline is only livable if redemption is offered where it is allowed.** So this record
   asks for the producers ADR 0046 never had: a read-only *offer* for a customer's cart (the
   maximum, the refusal reason, the spendable amount), the same offer for an operator composing an
   order for an identified customer, a customer's own points balance, an operator-taken order that
   carries a redemption at entry, and the New order console's control with the cash the customer
   will still owe. The storefronts' pay-with-points control and the order page's split are the same
   producers seen from the customer's side.

3. **A payment-method change keeps the redemption and moves the money.** ADR 0039's
   `CHANGE_PAYMENT_METHOD` moves a cash order to a provider. The points tender is not touched; the
   settlement's money tender is re-pointed from the old method to the new one and the provider
   intent is opened for the *money leg*, not the order total. This is not a new redemption and
   needs no new authority: it is what ADR 0046's invariant ("the tenders sum to the order total and
   at least one of them moves money") already demands of an amendment. It is recorded here only
   because the code that did not do it is the reason the `payment[]` array was untrue after an
   amendment.

4. **An operator attests the customer's agreement, once.** An operator-taken order can redeem only
   against the identified customer's own account (ADR 0046), and the customer is on the phone. The
   operator states in the request that the customer agreed to spend the points, and the order
   records one audit fact for it. This is the ADR 0039 pattern for a decision the customer makes
   and the operator relays ("Rules the matrix does not carry"); it is not a new kind of approval.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| **A settlement that records an unpaid CASH-only redemption through a new ADR 0039 command** (size M to L: a command, a re-plan of a settlement that has no intent, and a fiscal correction path) | Only works for an order nobody has paid for; for a paid one it is the points-to-cash conversion ADR 0046 forbids. It adds a command to a closed set to serve a case the offer at entry already serves. The fiscal receipt would have to be issued before the redemption was known | A pilot tenant asks, in writing, to let a customer who has not paid yet spend points on a placed cash order, and states a volume |
| **Shrink an existing payment intent and renegotiate it with the provider** | Neither Click nor Payme exposes a partial void of an uncaptured transaction; the amount is on the customer's redirect. A shrink mid-flight is a refund with an uncertainty window, which is what the points-first ordering exists to avoid | A provider documents amending an open invoice |
| **A points-funded future discount instead of a redemption** | Already available as ADR 0048's remedy and as a promo code (ADR 0072); needs no new mechanism, and is the honest shape of "give them value later" | Product asks for a points-denominated discount that the customer, not the operator, applies |
| **Take the redemption at checkout only; give the operator no entry path** | Leaves the phone order, which is a large share of this market's orders, unable to use the feature the customer has points for, and keeps the operator checkout passing a literal zero | none: a redemption at order entry is the decision |

## Consequences

### Positive

- The decline costs nothing that exists. No code path was recording a late redemption, no test
  asserts one, and the gap map's open design question closes without a migration.
- The producers it asks for are read-only or sit inside the transaction ADR 0046 already designed,
  so the points hold, its release on every terminal route, and the refund ceiling per tender are
  untouched.
- The offer is the one place the refusal reason can be said in words ("this brand has not enabled
  points", "this promotion blocks them", "the minimum order is not met"), where today a customer
  meets the checkout's refusal.

### Negative

- A customer who forgets to tick the control cannot ask for points to be applied to an order that
  was already placed. They can ask for a remedy, which is a person's decision and takes longer.
- An operator who forgot to ask on the phone cannot add the redemption afterwards; they cancel and
  re-take the order (while it is still cancellable) or refund and credit.
- The amendment path that moves a cash order to a provider now reads the settlement, and refuses
  an order whose settlement it cannot re-point (an intent already attached to the money tender),
  where it previously asked the provider for the whole total and left the array wrong.

### Accepted trade-offs

- The offer is a snapshot. Between the offer and the checkout another tab can spend the same
  points; the checkout's conditional debit is the gate (ADR 0046), and the loser meets
  `INSUFFICIENT_BALANCE`, as it always did.
- A customer's balance read is a staff-grade fact shown to the customer who owns it. It names the
  brand because a multi-brand customer holds several and cannot spend one at another.

## Specification

- **Offer, customer side.** `GET /api/v1/storefront/cart/orders/redemption-offer` style read keyed
  by the customer's own cart: `PointsRedemptionPort.quote` over the cart's priced total, delivery
  fee, currency and channel. Ownership comes from `CurrentCustomer`; there is no account id in the
  URL, so one customer cannot read another's.
- **Offer, operations twin.** The same quote for an identified customer and a priced draft of an
  operator order, under a capability that already guards reading a customer's loyalty balance.
- **Balance, customer side.** `GET /api/v1/storefront/loyalty/balance` returns the signed-in
  customer's own account at the tenant's brand, via ownership rather than `LOYALTY_READ`.
- **Operator redemption.** The operations order-create body gains a boxed `redeemFromBalanceMinor`
  and an `operatorAttestsCustomerAgreement` flag; the flag is required when the amount is above
  zero, the guard refuses a guest and a customer other than the one on the order, and one
  `ordering.order.redemption_attested` audit fact records who attested.
- **Money-leg re-plan.** `OrderSettlementPort.replanMoneyLeg` re-points the one planned money
  tender to the new method's registry row and returns the amount due; `applyPaymentMethodChange`
  opens the intent for that figure.
- **Receipt lines for a provider-paid order** are the fiscal line builder's (wave `w11`); this wave
  builds against the current bridge.

## Rollout and rollback

Every producer is additive. The offer and balance reads change no row. The operator field is
optional and defaults to no redemption. The re-plan changes one tender row's method, inside the
amendment's own transaction, and a settlement it cannot re-point is refused before any provider is
called. Rolling back is removing the endpoints; no migration is needed.

## Implementation checklist

- [x] The decision is written first, as a decline with its trigger.
- [ ] The money leg follows a payment-method change (`CHANGE_PAYMENT_METHOD` to a provider).
- [ ] The customer's cart offer and its operations twin.
- [ ] A customer reads their own points balance.
- [ ] An operator-taken order carries a redemption at order entry.
- [ ] The New order console's points control and cash-to-collect preview.
- [ ] Both storefronts' pay-with-points control and the split on the order page.

## Exit criteria

A customer with points, placing an order on either storefront, is shown what they can spend and
what they will still pay, and the order page shows the split; an operator taking the same order by
phone sees the same figures, records that the customer agreed, and reads the cash to collect; an
order that was moved from cash to a provider after checkout asks the provider for the money leg and
its `payment[]` says the method it now uses; and no endpoint exists that adds a points tender to a
settlement after it was planned.

## References

- [ADR 0039](../partial/0039-operator-assisted-ordering-and-order-amendment.md) — the closed amendment set, and the operator-attestation pattern this reuses.
- [ADR 0046](../partial/0046-loyalty-points-and-split-tender.md) — where a redemption is taken, why, and the invariants that make it points rather than money.
- [ADR 0048](../partial/0048-refunds-as-bookkeeping-and-the-order-remedy-model.md) — what value after checkout is, instead.
- [ADR 0165](../partial/0165-customer-stored-value-horecaos-holds-no-customer-funds.md) — HorecaOS holds no customer funds; the reason a late redemption would be a conversion.
