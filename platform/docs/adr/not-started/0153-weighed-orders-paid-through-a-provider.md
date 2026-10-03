# ADR 0153: Weighed orders paid through a provider

- Decision status: Proposed
- Implementation status: Not started — the interim rule is built, the real answer is
  not. A line sold by weight (ADR 0137) is checked out at its nominal weight and
  corrected at the scale. `CatchweightReconciliationService.reconcile` refuses a
  weight that moves the total of an order whose `payment_status_projection` is
  anything but `NOT_REQUIRED` (`PAYMENT_ALREADY_TAKEN`), and `OrderStateService`
  holds `FULFILLING` and `COMPLETED` behind `CATCHWEIGHT_NOT_RECONCILED` with no
  bypass, so a provider-paid weighed order whose weight differs from the nominal one
  could be neither weighed nor handed over. Checkout used to sell exactly that
  combination. It no longer does: `PaymentIntentPort.takesMoneyBeforeHandover`
  (`CaptureTiming.BEFORE_CONFIRMATION`: Click, Payme, Telegram, Marketplace) is asked
  of the method, `CartPaymentOptions` stops offering such a method for a basket that
  holds a weighed line (`WEIGHED_LINES_PAY_AT_HANDOVER` as a warning), and
  `CheckoutEligibilityGuard` refuses it with the same code for every surface that
  checks out (storefront, operator, bot). Nothing takes an incremental charge, gives
  back a partial refund, or holds an amount and captures a smaller one.
- Date proposed: 2026-10-03
- Date decided: —
- Deciders: proposed by Claude (fix wave 17, finding on weighed orders paid through
  a provider); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0013, ADR 0018, ADR 0039, ADR 0046, ADR 0137
- Supersedes / Superseded by: —
- Open inputs:
  - **Whether the pilot wants to sell weighed goods for an online payment at all**
    (product). Proposed default: no, until a path below is built; the interim rule is
    the answer for the tenants that sell cakes by weight today, all of whom take cash.
  - **What Payme and Click can do with an amount that is not final** (provider facts,
    payments). A hold of a ceiling followed by a capture of a smaller amount, a second
    charge against the same customer, a partial refund through the merchant API: each
    is a capability this record cannot assume. Proposed default: unknown, to be read
    from the providers' merchant documentation before option A or B is chosen.
  - **The tolerance** (product), only for option A: how far above the nominal weight a
    held ceiling goes. Proposed default: none chosen; a number nobody agreed to is a
    price nobody agreed to.
  - **The fiscal consequence** (legal, ADR 0038's owner). A receipt is built for the
    amount that was taken; one corrected afterwards is a correction document. Proposed
    default: out of scope here, as ADR 0137 left it.

## Context

ADR 0137 accepts that a catchweight total "is not necessarily the total they pay" and
reconciles it at pick or handover, the shape ADR 0038 gave marking codes. That works
where the money has not moved: a cash order's settlement is restated through
`OrderSettlementPort.restateTotal`, so the courier is told to collect the weighed
amount. It does not work where a provider already holds the money, because the
amount the provider holds is the amount of the quote. An increase needs a second charge
and a decrease a partial refund, and ADR 0039's amendments give the same refusal for the
same reason (`PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND`).

What made this a defect rather than a limit is that the refusal came after the sale. A
customer could order 1 kg of lamb sold by weight, pay by Click at checkout, and the kitchen
would weigh 1,040 g: the weighing answered 422 `PAYMENT_ALREADY_TAKEN`, the handover
answered 409 `CATCHWEIGHT_NOT_RECONCILED`, and the only exit was cancelling and refunding
an order whose food was waiting. Whatever this record decides, that sequence must not be
reachable.

## Decision

1. **A basket with a line sold by weight is sold only for a method that settles at
   handover.** Built (see the implementation status). The check lives where the
   method is chosen and where it is enforced, and both read the same two facts:
   `CartService.holdsWeighedLine`, which reads the published physical block the
   customer was shown, and `PaymentIntentPort.takesMoneyBeforeHandover`, which reads the
   method's capture timing.
2. **A weighed order paid through a provider stays unsupported until one of the paths
   below is chosen and built.** The weighing keeps refusing `PAYMENT_ALREADY_TAKEN`
   for an order that reached the pass that way (an order placed before the rule, or a
   method added later), because a total that disagrees with what the provider holds is
   worse than a refusal an operator can see. That refusal is not given a bypass: an
   operator who could clear `CATCHWEIGHT_NOT_RECONCILED` without a weight would be
   handing over food at an amount nobody computed.
3. **The path, when it is built, is one of these, chosen by the owner:**
   - **A. Hold and capture.** Authorise a ceiling at checkout, capture the weighed amount
     at handover, release the rest. The customer sees one charge; needs a provider hold
     and a ceiling policy.
   - **B. Charge the nominal amount, settle the difference.** A decrease is a partial
     refund through the remedy machinery; an increase is a second payment the customer
     confirms (ADR 0039's confirmation shape) or cash at the door.
   - **C. Take the money after the weighing.** A weighed order is confirmed without
     payment, as cash is, and the operator presents the payment for the weighed
     amount at the pass. No hold, no refund; needs a capture timing between "before
     confirmation" and "on handover" and a presentation the pass can start.
4. **Proposed default: C**, because it needs no provider capability this record cannot
   see, and because the amount is known when the money is asked for. It is a default to
   investigate, not a design: whether the existing presentation of an order's payment
   (`ordersWithPresentablePayment`) can start from an order that has no intent yet is
   not established here.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Never sell weighed goods online (the interim rule, kept as the floor) | It is correct and built, but it turns a catalogue feature into a cash-only one for every tenant that sells by weight and wants online payment | A tenant asks for online payment of weighed goods |
| A supervised bypass of `CATCHWEIGHT_NOT_RECONCILED` that leaves the provisional amount | Hands food over at an amount nobody computed, and leaves the order's total disagreeing with its lines for ever | Never as a blocker bypass; a weight below the nominal one could be tolerated by option B alone |
| Restate the order total and leave the provider's amount | A total that disagrees with what the provider holds is a refund or a dispute nobody can see | Never |
| Charge the ceiling and refund the difference | A customer charged more than the quote shows and refunded afterwards: the silent charge of a difference that quote acceptance (ADR 0018) exists to refuse | A provider hold makes the ceiling a hold and not a charge (option A) |

## Consequences

### Positive

- No customer can pay for a weighed basket in a way the kitchen cannot complete.
- The refusal and the offer cannot disagree: both read one rule.

### Negative

- A tenant that sells by weight takes cash for those baskets until this is built.
- Orders placed through a provider before the rule, if any, still need an operator to
  cancel and refund them when the weight differs.

### Accepted trade-offs

- The interim rule is about the basket and the method, not about the amount: a weighed
  basket that happens to weigh exactly its nominal weight would have been completable, and
  is refused all the same.

## Specification

Left to the path the owner chooses. Whatever it is: money is integer minor units with a
currency; a mutating endpoint declares its capability and follows ADR 0031; the order
revision that records the weight keeps source `CATCHWEIGHT`; the audit fact names the
before and after of the amount taken; nothing about the customer or the card reaches a log
or an event (ADR 0029).

## Rollout and rollback

The interim rule is a refusal at checkout and a shorter list at the payment step, with no
schema change. Rolling it back restores the stuck order, so it rolls back only together
with a path that completes one.

## Implementation checklist

- [x] The interim rule: `PaymentIntentPort.takesMoneyBeforeHandover`,
  `CartService.holdsWeighedLine`, `CartPaymentOptions`, `CheckoutEligibilityGuard`
- [ ] The owner's choice of path, and the provider facts it rests on
- [ ] The path itself, with its capture timing, its remedy and its fiscal correction

## Exit criteria

A customer can pay for a weighed basket online, the kitchen weighs it at a different
weight, and the order reaches the pass with the amount taken equal to the weighed total,
with no operator action beyond the weighing.

## References

- ADR 0013: Payment, refund, and service-recovery compensation (capture timing)
- ADR 0018: Deterministic pricing, promotions, taxes, and quotes
- ADR 0038: Legal entities, fiscal receipts, and fiscal product classification
- ADR 0039: Operator-assisted ordering, order amendment, and terminal outcome accounting
  (the same refusal for a provider-paid order)
- ADR 0046: Loyalty points and split tender (the order settlement)
- ADR 0137: Physical and nutritional product attributes
