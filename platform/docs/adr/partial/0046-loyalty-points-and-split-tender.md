# ADR 0046: Loyalty points and split tender

- Decision status: Accepted
- Implementation status: Partial — V0042 creates the `loyalty` schema with
  `SELECT`/`INSERT`-only grants on `loyalty.entries`, the append-only trigger, the
  closed `entry_type` check, lots and reservations, plus
  `payments.payment_methods`, `payments.order_settlements` and `payments.tenders`
  with `ck_tender_balance_has_no_intent`; V0048 adds the per-tender refunded
  amount, so a partial refund is bounded by what is left on a tender rather than
  by what it originally settled; the `loyalty` module implements
  reservation, release, redemption and reversal by conditional `UPDATE`, deferred
  accrual, the expiry sweep and forfeiture on a timer (`LoyaltySweeper`),
  adjustments with reason codes and ADR 0027 approval thresholds, the per-line
  `RedemptionAllocation`, and the refund cap, and `payments.OrderSettlementService`
  plans and orders the tenders, enforces all five invariants and computes
  `cash_due_minor`. The three not-money properties are enforced rather than
  asserted: no withdrawal path exists, no transfer between accounts exists, and a
  redemption can only reduce an order. **The unwind half now has a production
  caller and the settle half still does not.** ADR 0013's `OrderRemedyService`
  calls `OrderSettlementService.refund` behind `POST
  /api/v1/operations/tenants/{tenantId}/orders/{orderId}/refunds` (V0052), which
  is what would reverse a points tender in reverse settlement order — but nothing
  plans a settlement at checkout, so `refund` refuses every real order with "The
  order has no settlement", and the planning, reserving and settling path is
  reached only from `LoyaltyLedgerAndSplitTenderTests`; `CheckoutService` still
  does not use it. `LoyaltyAccrualService.accrue(CompletedOrder)` likewise has no
  caller outside that test and no listener on order completion, and
  `JdbcSettlementStore.registerMethod` has none either, so no tenant holds the
  `LOYALTY_POINTS` registry row the checklist describes as seeded per tenant. Also not built: the campaign-driven accrual rules ADR 0044
  owns; expiry warning notifications; `fiscal.fiscal_document_lines`, so the
  per-line allocation is written nowhere; the `cash_due_minor` handoff to ADR
  0014's assignment; and the tax effect on the liability report. The storefront
  balance endpoints exist but require `LOYALTY_READ` at tenant scope, a staff
  capability no customer principal can hold, so there is still no
  customer-facing balance surface.
- Date proposed: 2026-08-21
- Date decided: 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture; loyalty scope and the withdrawal of stored value, 2026-08-23), finance (VAT treatment of a merchant-funded rebate), legal (terms, expiry, offboarding), product (accrual and redemption defaults)
- Depends on: ADR 0013, ADR 0015, ADR 0018, ADR 0019, ADR 0021, ADR 0027, ADR 0029, ADR 0030, ADR 0036, ADR 0038
- Supersedes / Superseded by: Corrects the payment-intent cardinality of ADR 0013, which records the pointer back. Withdraws the `CUSTOMER_DEPOSIT` account type, payment method, and entry types that an earlier revision of this same ADR specified
- Closed inputs: **Loyalty is points only. There is no customer-funded cash balance, and none is deferred** (Ayubkhon Abbosov, 2026-08-23). Points cannot be withdrawn, cannot be transferred between people, and have no redemption value outside the platform. No Central Bank of Uzbekistan e-money question arises, because nobody holds customer funds
- Open inputs: Whether a merchant-funded points rebate reduces the VAT base in Uzbekistan, and therefore whether a points-bearing order may be fiscalized through a provider's per-line discount field (finance, legal); confirmation of the six product defaults proposed below (product, finance); whether the no-cash-value, non-transferability and per-lot expiry rules must appear in the customer terms of use to hold, and what becomes of an outstanding balance when a tenant offboards or a brand closes (legal)

**None of the remaining open inputs is structural**, which is why this ADR is
`Accepted` rather than `Proposed`. The one that looks structural is the VAT
question, and it is not: the redemption is a tender in `payments` and a per-line
discount in `fiscal` under either answer, the allocation is the same allocation,
and `fiscal.fiscal_document_lines.discount_minor` is a column ADR 0038 already
carries. What the answer decides is *which fiscal paths accept a points-bearing
order* — which is a property of ADR 0038's method registry, the same shape as
`supports_marking` — and not what this ADR stores. The product defaults are
ADR 0030 policy values by construction. The legal questions are contract text and
an offboarding runbook.

The file name retains `stored-value` so that the cross-references in ADR 0013,
ADR 0038, ADR 0043, ADR 0047, the operations spec, and the parity plan keep
resolving. The title does not, because the subject is gone.

## Context

Delever lists cashback points (Кешбэк) and a customer deposit (Депозит) in its
payment-type registry, and its order carries an array of payments rather than
one. Two things follow: part of an order can be settled from a balance and the
rest in cash, and the courier is shown the bonus-paid amount so they collect the
right cash at the door. Both are in daily use in this market; neither is
representable in Qoida.

An earlier revision of this ADR specified both halves — a points balance and a
customer-funded deposit — and left the deposit behind a kill switch pending a
legal answer about whether a restaurant may hold prepaid customer funds without a
Central Bank authorisation. That answer is no longer needed, because the question
was withdrawn rather than answered: **Qoida offers points and nothing else.**

That withdrawal is not cosmetic. It moves the ADR's hardest problem, and it moves
the fiscal answer with it. A funded deposit is money the customer already handed
over; an order settled 12 000 from deposit and 72 000 by card really was an
84 000 som sale, paid in two instalments, and the receipt says 84 000. Points are
not money and nobody handed anything over; an order settled 12 000 from points is
a 72 000 som sale with a 12 000 som merchant-funded rebate. The old ADR's
headline — "loyalty is a tender, never a discount" — was correct about the
deposit and is wrong about points. Section
[What reaches the receipt](#what-reaches-the-receipt) reverses it and says what
the reversal costs.

The payment model is why the split-tender half is decided now rather than when it
is built. ADR 0013 proposes `payments.payment_intents` keyed one per order with a
single `requested_amount_minor`. That is right for a card payment and wrong for an
order paid 12 000 som from points and 72 000 som in cash. Payments is an empty
package today — `src/main/java/uz/qoida/platform/payments` holds only
`package-info.java` — so admitting a second tender costs a schema nobody has
written yet. After ADR 0013 ships it is a migration of live financial records with
in-flight intents in them.

## Decision

**Customer-funded stored value is withdrawn from scope, not deferred.** There is
no `DEPOSIT` account type, no `CUSTOMER_DEPOSIT` payment method, no `TOPUP` or
`WITHDRAWAL` entry type, and no kill switch. A kill-switched feature is a deferred
feature that still costs its tables, its enum values, its registry row and its
test surface, all maintained while inert, and whose first defect is found by
whoever enables it two years later. The reversal conditions are stated in
[What would bring stored value back](#what-would-bring-stored-value-back), and
meeting them produces a new ADR rather than an edit to this one.

**Points are not money, and this ADR makes that structurally true rather than
merely asserting it.** Not withdrawable, not transferable between people, no cash
value outside the platform. Each of the three is a named constraint with a named
enforcement point in [Why points are not money](#why-points-are-not-money), and
each of the three has an obvious back door that the enforcement exists to close.

**An order is settled by an ordered set of tenders, not one payment intent.** This
extends ADR 0013 and corrects the cardinality it proposed. A settlement record
sits between the order and the payment intents; each tender names a payment method
row, carries its own amount and lifecycle, and the tender amounts sum exactly to
the order total. A points redemption is one such tender.

**A tender references a payment method row, not a tender-type enum of its own.**
The tenant payment-method registry is `payments.payment_methods` and ADR 0038 owns
it. This ADR adds to it one row, `LOYALTY_POINTS`, and the `settles_from_balance`
flag marking a method that discharges an amount from a Qoida-held balance rather
than moving external money. ADR 0038 currently records that this ADR contributes
*two* rows; that sentence is now stale and is listed as a cross-ADR correction in
the checklist.

**A points redemption is a tender in `payments` and a discount in `fiscal`.** The
two registers answer different questions and this ADR keeps them apart on purpose.
In `payments` the redemption discharges part of the order total, appears in the
settlement, drives the courier's cash figure, and is counted by ADR 0043's
`fact_order_tender`. In `fiscal` it is a per-line discount, because the seller
received 72 000 som and not 84 000, and because neither Click nor Payme has a
field in which platform-held value could be tendered.

**The loyalty module owns an append-only points ledger.** Movements are entries;
a balance is a cached projection and never the authority. Lots carry expiry, earn
is delayed past the refund window, and redemption is capped. This is the same
discipline ADR 0021 applies to usage metering, for the same reason: a balance
updated in place cannot be audited, cannot be recomputed after a bug, and cannot
be defended to a customer who disputes it.

**Points are whole som, one to one, and are denominated in som because that is the
unit of the discount they produce.** A points balance is not an abstract currency
with a tenant-set rate. The redemption has to land in a provider's integer discount
field on a fiscal receipt, so any other unit needs a conversion at the fiscal
boundary plus a versioned rate joined to every historical receipt to explain it.
`currency` on a loyalty account names the denomination of the discount, not a claim
on funds.

**A points account is scoped to one brand, in both identity modes.** Under
`BRAND_ISOLATED` this is forced by ADR 0015. Under `TENANT_SHARED` it is chosen,
because a brand's liability is usually a distinct legal entity's liability under
ADR 0038 and cross-brand redemption would move it between entities with no
settlement between them. See [Cross-brand scope](#cross-brand-scope).

## What would bring stored value back

Stored value returns when **either** of these is true, and it returns as a new
ADR:

- **A licence exists.** The tenant, or Qoida, holds a Central Bank of Uzbekistan
  authorisation permitting the holding of customer funds — an e-money issuer or
  payment-service authorisation, depending on what the regulator classes a
  restaurant prepayment as. Then a `DEPOSIT` account type can join the ledger
  below, with `TOPUP` and `WITHDRAWAL` entry types and a funded-balance liability
  report that finance signs monthly.
- **An acquirer holds the float.** Payme, Click, or a licensed EMI operates the
  wallet, the customer funds it there, and the licence sits with them. In that
  case it never joins this ledger at all: an acquirer-held balance is external
  money, so it is an ordinary ADR 0013 payment intent beneath an ordinary tender
  with `settles_from_balance` false, and this ADR is untouched. The rejected shape
  in the alternatives table is this one, and it is rejected as a *default* rather
  than as an impossibility.

Neither condition is met by a tenant asking for the feature, and neither is met by
a legal opinion that the risk is low. Until one holds, an order cannot be settled
from money the platform is holding, because the platform holds none.

## Why points are not money

Three properties, each with the back door it closes.

### Not withdrawable

`entry_type` is a closed set — `ACCRUAL`, `REDEMPTION`, `RELEASE`, `EXPIRY`,
`FORFEITURE`, `ADJUSTMENT`, `REVERSAL`, `WRITE_OFF` — and none of them names a
payout destination. There is no column on `loyalty.entries` that could carry one:
a debit references a `tender_id` on an order, a lot, or nothing at all. No API
converts a balance into money or into an external instrument.

The back door is the refund path, and it is the one that would actually be built
by accident. An order settled 12 000 from points and 72 000 by card, refunded in
full to the card, hands the customer 84 000 som of real money for 72 000 som of
real money spent — points converted to cash at par by an implementation nobody
reviewed as a payments change. **A refund therefore never returns more money than
money was tendered.** The refundable amount per tender is capped at that tender's
settled amount, checked inside the refund transaction, and a points tender refunds
as points.

### Not transferable between people

`loyalty.entries` has one `account_id` and no counterparty column, so a transfer
cannot be written as a pair even by an operator with database access to the
command surface. A `REDEMPTION` is valid only when the order's
`customer_account_id` equals the account's and the order's `brand_id` equals the
account's, checked inside the ADR 0019 checkout transaction. Two consequences
follow and both are intended: a guest checkout cannot redeem, and an
operator-assisted order under ADR 0039 can redeem only against the identified
customer's own account.

`ADJUSTMENT` is the obvious back door — two offsetting adjustments are a transfer
with extra steps. The adjustment command takes one account and one signed amount
and has no paired form, so a "transfer" is two separate manual acts, each with a
reason code, an actor, and ADR 0027 four-eyes approval above the configured
threshold. That does not make it impossible; it makes it visible, attributable,
and countable on a report, which is the correct treatment for something an
operator legitimately does during a support call and illegitimately does as a
favour.

The one operation that genuinely moves points between accounts is an ADR 0015
account merge, and it moves them between two records of the same person under an
evidence-backed workflow. Points move as an `ADJUSTMENT` pair with reason
`ACCOUNT_MERGE`, lots preserved at their original `expires_at`. Under
`BRAND_ISOLATED` a merge cannot cross a brand partition, so neither can the
points.

### No cash value outside the platform

Redemption's only sink is a tender on an order in the same tenant and brand.
Expiry and forfeiture destroy value with no compensating movement — that is what
`EXPIRY` and `FORFEITURE` are for, and a balance that could be cashed out instead
of expiring would make the expiry rule decorative. On account closure and on
ADR 0029 erasure the balance is forfeited with a `FORFEITURE` entry and is not paid
out; the entries are retained under the financial retention period with the
customer reference anonymised, consistent with ADR 0029's closed input.

What this ADR cannot do alone is make the position enforceable against a customer
who disputes it. Terms of use must state the no-cash-value rule, the
non-transferability rule, and per-lot expiry, and legal must confirm whether Uzbek
consumer-protection rules constrain expiry of a promotional balance. That is the
third open input, and it is contract text rather than structure.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Issue customer-funded stored value now, as Delever does | The regulator's position on a restaurant holding prepaid customer funds is unknown, and the failure is not a bug — it is operating an unlicensed payment service across every tenant at once | Either condition in [What would bring stored value back](#what-would-bring-stored-value-back) holds, as a new ADR |
| Keep the deposit specified and kill-switched, as the previous revision did | A disabled feature still costs its tables, its enum values, its registry row, its endpoints and its tests, all maintained against no user, and its first defect is found by whoever enables it. It also kept this ADR `Proposed`, which blocked the settled 90% of it | Never as a shape. The reversal conditions produce a new ADR with a live feature, not a dormant one |
| Delegate the balance to the acquirer's wallet (Payme, Click) | Puts the float and the licence where they may legally belong. Rejected as the default because a wallet at Payme is not spendable at a Click-only branch, and one customer's money splits across acquirers with no combined view | Legal rules that neither Qoida nor the tenant may hold a float and a tenant still wants prepayment. It then arrives as an external tender under ADR 0013, not in this ledger |
| Model points as an ADR 0018 benefit grant applied at quote time | Right about the fiscal half and wrong about everything else. It makes accrual circular — points earned on a price the points reduced — it fires before a tender plan exists so the courier's cash figure has to be re-derived from the quote, and it puts a per-customer liability balance inside a pricing engine that ADR 0018 defines as a pure function reading no state | Never for the ledger. The fiscal representation is already a discount, which is the part this option had right |
| Keep "loyalty is a tender, never a discount" on the receipt as well as in payments | There is no field. Click's `submit_items` splits a *Click payment* across `received_cash`/`received_card`/`received_ecash`, and those three must sum to the payment; Payme fiscalizes the Payme receipt amount. A receipt asserting 84 000 against an 82 000 provider payment does not balance and is rejected | A `TERMINAL` fiscal path exists whose register has a bonus tender key, or finance rules the base may not be reduced. Both are ADR 0038 questions |
| A `bonus_balance_minor` column on the customer account | Cannot expire part of a balance, cannot say which order created which points, cannot be reconciled to a liability figure, and turns a disputed balance into a support argument with no evidence on either side | Never |
| Keep one payment intent and express split tender as two linked orders | Duplicates the commercial record, doubles the fiscal documents, breaks ADR 0019's one-cart-one-order rule, and every report counts the meal twice | Never |
| Full double-entry accounting with a chart of accounts | Correct accounting and more machinery than is needed. Qoida is not the tenant's accounting system; a per-account append-only journal carrying `balance_after_minor` reconstructs any balance at any instant, which is what disputes and audits require | Qoida issues its own invoices, or a funded balance returns under a licence |
| Points as an abstract currency with a tenant-set conversion rate | Every rate change silently revalues every outstanding balance, and the redemption still has to reach a provider's integer som discount field, so the rate would have to be versioned and joined to every historical receipt to explain one | A tenant needs tiered earn or burn rates that 1:1 cannot express, and accepts versioning the rate onto every fiscal line |
| One tenant-wide points pool spendable across brands under `TENANT_SHARED` | A brand's points liability is usually a distinct legal entity's liability under ADR 0038, so redeeming at brand B points earned at brand A moves value between two taxpayers with no settlement between them. Qoida models no inter-entity transfer and should not invent one inside a loyalty feature | A tenant's participating brands resolve to one legal entity, and finance defines the inter-brand settlement entry. Then it is an opt-in tenant policy, not the default |
| Let points cover the whole order | An order with no money tender has no fiscal path at all — no Click payment to hang `submit_items` on, no Payme receipt, and on a cash order a courier who collects nothing while handing over food. It also makes the accrual base zero, so the order earns nothing and the customer asks why | A fiscal path exists for a zero-consideration sale. The redemption cap is a product number; the "at least one money tender" invariant is not |
| Cash out a remaining balance on account closure | Converts points into money at par at exactly the moment nobody is watching, which is the single thing the not-money constraints exist to prevent | Never, absent a licence, at which point it is a deposit and a different ADR |

## Settlement and tender model

```text
payments.order_settlements
  id, tenant_id, order_id, currency
  total_due_minor, settled_minor, status, version, timestamps
  unique(tenant_id, order_id)

payments.tenders
  id, tenant_id, settlement_id, sequence
  payment_method_id, amount_minor, currency, status
  payment_intent_id null            -- external tenders, ADR 0013
  loyalty_reservation_id null       -- the points tender, this ADR
  idempotency_key, version, timestamps

payments.payment_methods            -- ADR 0038 owns this table
  ... settles_from_balance          -- the one column this ADR adds
```

`payment_method_id` is a foreign key into `payments.payment_methods`, the
tenant-scoped registry ADR 0038 owns. This ADR does not define a `tender_type`
enum beside it. Two registries of settlement mechanisms disagree, and the damaging
disagreement is over `responsibility` — `PARTNER`, `TERMINAL`, `MARKETPLACE`,
`OPERATOR` — which decides who issues the fiscal receipt and which ADR 0038
already validates when a method is activated. ADR 0036's
`channel_payment_methods.payment_method_code` points at the same rows, so what a
channel offers, what fiscalizes an order, and what a tender records cannot drift
apart.

The one row loyalty needs is `LOYALTY_POINTS`, with `settles_from_balance` set.
The flag, not the name, is what the rules below key on: reservation ordering,
accrual net of the redeemed portion, the money-tender invariant, and
`cash_due_minor` all test `settles_from_balance`, so a balance-backed method added
later inherits them without a code change. It is not a fiscal path — see the next
section — and ADR 0038's activation checks bind `PARTNER` and `MARKETPLACE`
methods, which it is not.

| Payment method | `settles_from_balance` | Settles against |
|---|---|---|
| `CLICK`, `PAYME`, other online card or wallet methods | no | An ADR 0013 payment intent |
| `CASH` | no | Nothing until the courier or cashier confirms collection |
| Courier terminal, kiosk, and dine-in POS methods | no | Captured at the terminal; ADR 0038 responsibility `TERMINAL` |
| Aggregator-settled methods | no | Recorded, not executed — Yandex Eats or Uzum Tezkor collects |
| `LOYALTY_POINTS` | yes | A reservation against the ledger below |

Method codes are registry rows, so the table is what the pilot registers rather
than a closed set this ADR may extend.

Delever's editable payment-type registry conflates a customer-visible label with
the settlement mechanism. Qoida separates them: the registry row is the mechanism,
and the localised ru/uz-Latn/en label and icon a merchant configures are
presentation over it, resolved per channel by ADR 0036.

Invariants, enforced inside the ADR 0019 checkout transaction:

```text
sum(tenders.amount_minor) == order.total_minor
every tender amount > 0
at least one tender with settles_from_balance = false and amount_minor > 0
at most one balance tender per settlement
redeemed amount <= the redemption policy cap resolved for this order
redeemed amount <= available balance at reservation time
the redeeming account's customer_account_id == the order's customer_account_id
the redeeming account's brand_id == the order's brand_id
```

The third invariant is the structural form of "points cannot cover the whole
order". The redemption cap is a product number that can be raised to 90% without
argument; the requirement that some money changes hands is not, because a
zero-consideration order has no fiscal path and no cash for a courier to collect.

**The balance tender reserves before any external tender is initiated, and
external tenders settle last.** Releasing a points reservation is a local write;
reversing a captured card payment is a provider refund with an uncertainty window.
The other order produces a failed points debit after a successful capture — the
case where the customer has paid and the order has not.

```text
tender:      PLANNED -> RESERVED -> SETTLED -> REVERSED
             PLANNED -> RESERVED -> RELEASED
             PLANNED -> FAILED

settlement:  DRAFT -> PLANNED -> PARTIALLY_SETTLED -> SETTLED
             PLANNED -> FAILED   (reservations released, order -> PAYMENT_FAILED)
             SETTLED -> PARTIALLY_REVERSED -> REVERSED
```

A settlement never rests in `PARTIALLY_SETTLED` across a checkout boundary. If any
tender fails during checkout every reservation is released and ADR 0019 takes the
`PAYMENT_FAILED` path. Half-paid is not a state this platform has.

## What reaches the receipt

**A points redemption reaches a fiscal receipt as a per-line discount, not as a
tender.** This reverses the previous revision of this ADR, and the reversal is
forced from two directions.

The provider contracts force the representation. Click's
`payment/ofd_data/submit_items` fiscalizes one CLICK payment: it requires that
payment's `payment_id`, and its `received_cash` / `received_card` /
`received_ecash` fields describe how *that payment* was tendered, summing to it.
`docs/providers/fiscalization-via-payment-providers.md` is explicit that reading
`received_cash` as a general cash path is the most expensive misreading available
in the document, and the same applies here: there is no fourth bucket for
platform-held value, and inventing one by inflating `received_ecash` would assert
that Click moved money it did not move. Payme fiscalizes the Payme receipt amount
from a `detail` object fixed before payment, and has no tender split at all. Both
providers do carry a per-line discount — Click's `Discount` plus `Other`, Payme's
`discount`, already multiplied out by quantity — and that is the only field in
either contract into which a redemption fits.

The substance points the same way, and this is the part that changed when stored
value was withdrawn. A deposit-settled order really was an 84 000 som sale paid in
two instalments. A points-settled order is not: nobody funded the 12 000, the
seller receives 72 000, and the consideration for the supply is 72 000. Under
ADR 0018 prices are VAT-inclusive and a discount reduces the gross, so tax follows
the consideration down. That is the correct treatment of a merchant-funded rebate
and it is the treatment the provider fields express — but it is a tax
determination, so finance and legal own the confirmation and it is the first open
input.

A worked order, with 12% inclusive VAT:

```text
food lines                84 000
delivery fee              10 000
order total               94 000

tenders
  LOYALTY_POINTS          12 000     settles_from_balance = true
  CLICK                   82 000     settles_from_balance = false
                          ------
                          94 000     == order total

fiscal document (PARTNER, Click)
  lines net of discount   82 000     == the CLICK payment
  discount allocated      12 000     across the food lines only
  VAT on the food lines    7 714     round(72 000 x 1200 / 11 200)
```

Without the redemption the food lines would have carried 9 000 som of VAT
(84 000 × 1200 ÷ 11 200). The 1 286 som difference is the tax effect of the
rebate, and it is the number finance must sign off before redemption is enabled
for a brand.

Four rules follow:

- **The redemption is allocated across lines, never carried as a line of its
  own.** A receipt has no line type meaning "bonus", exactly as ADR 0038 records
  that it has none meaning "rounding". Allocation is pro rata to line value with
  the remainder to the highest-value line, which is the rule ADR 0038 already uses
  for ADR 0018's rounding remainder, so the two allocations compose instead of
  fighting.
- **Lines the redemption policy excludes carry no share of it.** The default
  policy excludes the delivery fee, so the fee line's `discount_minor` is zero and
  its own classification and VAT are untouched.
- **The allocated discount is derived from the accepted ADR 0018 quote snapshot
  and the settled tender amounts, never recomputed at fiscalization time.** This
  is ADR 0038's existing rule and it matters more here, because a recomputation
  after a partial refund would produce a discount that no longer matches the
  receipt already issued.
- **Accrual is not a fiscal event.** Earning points creates no document, changes
  no base, and appears on no receipt. It is a promise, not a supply.

Two things this does *not* change. The tender model stands: the redemption is
still a row in `payments.tenders`, still drives `cash_due_minor`, and is still
counted by ADR 0043's `fact_order_tender`. And ADR 0038's document cardinality
stands: one `SALE` document per settled tender means the `LOYALTY_POINTS` tender
produces no `SALE` document of its own, because it settles nothing externally and
has no provider path — it is already inside the money tender's receipt as the
discount. Where an order is settled points-plus-cash, the cash tender takes
ADR 0038's `NOT_APPLICABLE` with
`CASH_TENDER_NO_PROVIDER_FISCALIZATION` and the redemption goes with it,
unreceipted for the same reason the cash is.

ADR 0013's alternatives table rejects modelling service-recovery compensation as a
discount and cites this ADR as agreeing "for the same reason". That clause is now
stale, and ADR 0013's own reasoning survives without it: a recovery benefit
applies to an order whose receipt was already issued, so treating it as a discount
would require correcting a document that was correct when issued. A points
redemption is known before the receipt exists. The two cases differ in timing, not
in principle, and the checklist records the correction.

## Loyalty ledger

```text
loyalty.accounts
  id, tenant_id, brand_id, customer_account_id
  currency, status
  balance_minor, reserved_minor, version, timestamps
  unique(tenant_id, brand_id, customer_account_id)

loyalty.entries                     -- append-only; SELECT and INSERT only
  id, tenant_id, account_id, entry_type, amount_minor signed
  balance_after_minor, lot_id null
  order_id null, tender_id null, rule_id null, rule_version null
  reason_code, actor, approval_id null, idempotency_key
  occurred_at, recorded_at

loyalty.lots                        -- the expiry unit
  id, tenant_id, account_id, source_entry_id
  granted_minor, remaining_minor, earns_at, expires_at, status

loyalty.reservations
  id, tenant_id, account_id, tender_id, amount_minor
  status, expires_at, idempotency_key, version, timestamps

loyalty.accrual_rules
  id, tenant_id, brand_id, scope_type, scope_id, rate_basis_points
  max_accrual_minor null, earn_delay_hours, lot_lifetime_days
  expiry_warning_days, status, version, valid_from, valid_until null

loyalty.redemption_policies
  id, tenant_id, brand_id, max_share_basis_points, min_order_minor
  excludes_delivery_fee, allowed_channels, status, version, valid_from/until
```

There is no `account_type` column. There is one kind of account, and a column
holding one value is an invitation to add a second one without an ADR.

**Append-only is a grant, not a convention.** The application role holds `SELECT`
and `INSERT` on `loyalty.entries` and nothing else, asserted against
`information_schema` in the same way ADR 0015 asserts it for
`customer.consent_decisions`. A convention survives until the first hotfix that
needs to "just correct one row"; a missing `UPDATE` grant does not.

`entry_type` is `ACCRUAL`, `REDEMPTION`, `RELEASE`, `EXPIRY`, `FORFEITURE`,
`ADJUSTMENT`, `REVERSAL`, `WRITE_OFF`, as a check constraint rather than an
application enum. Rules are versioned and snapshotted onto the entry that used
them, so changing tomorrow's accrual rate never restates yesterday's balance.

Rules that exist to prevent a named failure:

- **Points accrue on what the customer paid with money, net of the delivery fee
  and net of the redeemed portion.** Accruing on the redeemed portion is a balance
  that never decays, which finance finds as a liability line growing without a
  matching sale.
- **Accrual is deferred until the order is `COMPLETED` plus a configured earn
  delay**, landing as a lot with `earns_at` in the future. Crediting at checkout
  means a cancelled order requires clawing back points already spent.
- **Consumption is oldest-expiry-first, then oldest-granted-first.** A balance
  that expires as one block on one date is rejected: the customer who earned
  steadily loses everything at once and complains, correctly.
- **Redemption reserves, it does not count.** Two carts in two tabs must not both
  spend the same 40 000 som, for the reason ADR 0018 reserves coupon usage.
- **`balance_minor` may never go negative.** Where a refunded order's accrual has
  already been spent, the shortfall is a `WRITE_OFF` against the tenant, visible
  on the liability report — not a negative balance the customer finds later.
- **Manual adjustment carries a reason code, an actor, and ADR 0027 four-eyes
  approval above a configured threshold.** An unbounded manual credit is a cash
  drawer that any operations console login can open.
- **Every entry stores `balance_after_minor`**, so a past balance is a row rather
  than a replay.

**Refunds unwind tenders in reverse order of settlement** — external money first,
points last — and each tender refunds at most what it settled. Returning points
first leaves the customer with points and the tenant with their cash. Reversing a
points tender restores the consumed lots at their original `expires_at`: points
three days from expiry when spent are three days from expiry when returned, and
resetting the clock is a quiet giveaway that compounds on every refund. A partial
refund reduces the money tender first and reaches the points tender only when the
money tender is exhausted, so a customer refunded 10 000 som on the worked order
above receives 10 000 som and no points back.

### Product defaults, proposed

These are proposals, not decisions, and product and finance own the confirmation.
They are stated as numbers rather than left blank because a blank becomes whatever
the first implementer types.

| Value | Proposed default | Why this number |
|---|---|---|
| Accrual rate | 300 bp (3%) of the money-settled, fee-excluded order value | A 100 000 som order returns 3 000, so a customer reaches a usable balance in four or five orders rather than twenty. It also bounds the liability at 3% of net food revenue, which is a figure finance can carry without modelling |
| Maximum accrual per order | 30 000 som | Caps the liability created by one unusually large corporate order, which is the case a percentage rate handles badly |
| Earn delay | 24 hours after the order reaches `COMPLETED` | The window in which a delivery complaint is actually raised is hours, not days — the same evening or the following morning. 24 hours covers it, and beyond that a clawback is rare enough to be a `WRITE_OFF` |
| Lot lifetime | 180 days from `earns_at` | Long enough that a customer ordering every six to eight weeks keeps accumulating rather than watching lots lapse mid-cycle; short enough that the liability turns over twice a year instead of accreting. Fixed per lot, not sliding on activity, because per-lot expiry is what makes oldest-expiry-first consumption meaningful |
| Redemption cap | 5 000 bp (50%) of the money-eligible value, delivery fee excluded, minimum order 50 000 som | Sits well inside the hard "at least one money tender" invariant, keeps a real cash figure at the door on a cash order, and keeps a fiscalizable provider payment on a card order. The minimum order stops a 12 000 som balance from producing a stream of near-free small orders |
| Expiry warning | 14 days before `expires_at`, at most one message per customer per day | Silent expiry is the loyalty behaviour customers complain about most and is entirely avoidable. Daily coalescing stops a customer with many lots receiving many messages |

## Cross-brand scope

**Points earned at one brand cannot be spent at another.** A points account is
keyed `(tenant_id, brand_id, customer_account_id)` in both identity modes.

Under `BRAND_ISOLATED` there is nothing to decide. ADR 0015 states that resolution
requires a brand and cannot return an account first created for another brand, so
the two brand identities are two unrelated people as far as the platform is
concerned, and there is no subject that holds both balances. A cross-brand pool
would require exactly the cross-partition join ADR 0015 forbids.

Under `TENANT_SHARED` there is one `CustomerAccount` with a brand profile per
brand, so a shared pool is technically reachable and is still refused. A brand's
outstanding points are a liability of the legal entity that will honour them, and
ADR 0038 establishes that one tenant routinely contains several taxpayers and that
one brand is routinely split across two companies for tax or franchise reasons.
Redeeming at brand B points accrued at brand A therefore discharges entity B's
receivable against entity A's promise, which is an inter-company transfer with a
tax character, and Qoida models no settlement between legal entities. The place to
discover that is not a customer's checkout.

What `TENANT_SHARED` does buy is presentation: one account can list its brand
balances together, with each labelled by the brand that will honour it, because
one account resolves to several brand profiles. That is a read, not a pool, and it
is the useful half of shared identity.

The reversal condition is narrow and stated: a tenant whose participating brands
all resolve to a single legal entity, plus a finance-defined entry for the
inter-brand movement. It then becomes an opt-in tenant policy with its own
liability reporting, not the default.

## What the courier sees

The courier app is shown one figure, `cash_due_minor` — the order total minus
every non-cash tender in `SETTLED` state — with a breakdown line naming the amount
settled from points. It is snapshotted onto the ADR 0014 delivery assignment at
dispatch rather than recomputed on the device, so a connectivity gap cannot
produce a number different from the console's. On the worked order settled by
points plus cash rather than points plus card, the courier collects 82 000, not
94 000.

The failure this prevents is concrete, and Delever built for it explicitly: a
courier who sees only the order total on an order where part came from a balance
collects the total at the door. The customer has paid twice, the tenant refunds,
and the customer stops using the balance.

The figure is the same whether the courier is on the in-house fleet or works for a
delivery partner, and the tender breakdown carries no customer identity. Who owes
what to that courier is ADR 0042's settlement question and touches nothing here.

## APIs, events, and data handling

```text
GET  /api/v1/storefront/loyalty/accounts/{accountId}[/entries]
POST /api/v1/storefront/checkouts                       -- accepts tenders[]

GET  /api/v1/operations/customers/{customerId}/loyalty
POST /api/v1/operations/customers/{customerId}/loyalty/adjustments
GET  /api/v1/operations/orders/{orderId}/settlement
GET  /api/v1/operations/reports/loyalty-liability

POST /api/v1/control-plane/brands/{brandId}/loyalty/accrual-rules
POST /api/v1/control-plane/brands/{brandId}/loyalty/redemption-policies
```

There is no endpoint that credits an account from a customer payment, and no
endpoint that pays an account out. That absence is part of the decision rather
than a gap in the list.

Mutations follow ADR 0031: tenant-scoped `Idempotency-Key`, expected version,
intent-named commands. Adjustment needs an ADR 0025 capability at tenant scope, a
reason, and approval evidence over threshold.

```text
LoyaltyPointsAccrued / Reserved / Released / Redeemed
LoyaltyPointsExpiring / Expired / Forfeited
LoyaltyBalanceAdjusted
OrderSettlementPlanned / Settled / Reversed
TenderSettled / TenderFailed
```

Balance-change messages reach the customer through ADR 0020 with typed variables
only — direction, amount, resulting balance, nearest expiry date — never a ledger
extract, and only to a contact point verified under ADR 0015. Under ADR 0029 no
event, log, trace, or metric carries a customer identifier beyond an opaque
account reference, and none carries a contact point.

Balances and entries are `FINANCIAL` class under ADR 0029. On erasure the balance
is forfeited, the account is detached from the customer identity, and the entries
are retained under the financial retention period with the customer reference
anonymised. Loyalty is an ADR 0021 plan feature with its own entitlement flag and
accrual-volume metering; it is not a tenant prepaid wallet, which the parity
analysis declined outright and which this ADR now declines in writing.

## Testing

- Tender amounts sum to the order total for every accepted checkout; a plan that
  does not sum is rejected before any provider call. A plan with no money tender
  is rejected, including when the redemption cap would otherwise permit it.
- Concurrent checkouts against one balance settle once; the loser sees
  `INSUFFICIENT_BALANCE`, never a negative balance.
- A failed external tender after a successful points reservation releases it and
  leaves no partially settled order.
- Golden ledger fixtures: accrual, partial redemption, expiry, refund, and
  clawback reproduce a stated balance entry by entry, with consumption
  oldest-expiry-first and reversal restoring the original `expires_at`.
- Accrual excludes the redeemed portion and the delivery fee; clawback beyond an
  available balance produces a `WRITE_OFF`; `cash_due_minor` equals total minus
  settled non-cash tenders for every row of the tender table.
- **Not-money tests, each asserting an absence.** A full refund of a
  points-settled order returns at most the money tendered. No command produces a
  money movement out of a loyalty account. A redemption against an account whose
  `customer_account_id` or `brand_id` differs from the order's is refused. Account
  closure and ADR 0029 erasure produce `FORFEITURE`, never a payout. The
  application role has no `UPDATE` or `DELETE` grant on `loyalty.entries`, and
  `entry_type` rejects `TOPUP` and `WITHDRAWAL` at the database.
- Fiscal: the discount allocated across `fiscal.fiscal_document_lines` sums to the
  points tender amount, the lines net of discount sum to the money tender amount,
  no share is allocated to a policy-excluded line, and the allocation is derived
  from the quote snapshot rather than recomputed. A points tender produces no
  `SALE` document of its own.
- A tender naming a payment method that is not registered, or not enabled on the
  order's channel, is refused at checkout; the internal-before-external ordering
  is driven by `settles_from_balance` and holds for a balance method added later.
- Adjustments over threshold without approval evidence are refused, and
  cross-tenant or cross-brand ledger reads fail at the boundary.

## Rollout and rollback

This ADR takes no migration number and ships no schema. The tables below arrive
with the implementation pass that follows it.

1. Build the settlement and tender tables inside ADR 0013's payment migration,
   before any intent row exists. This is the only step with a deadline.
2. Ship the ledger with accrual inactive and run it in shadow: compute what would
   have been credited, record nothing, and let finance review a month of it
   against sales.
3. Close the fiscal open input with finance and legal, and confirm the six product
   defaults, before any redemption is enabled anywhere.
4. Import legacy balances, where a tenant carries them, as one `ADJUSTMENT` entry
   per customer with reason `LEGACY_OPENING_BALANCE` and a policy lot lifetime. An
   import that does not reconcile to a total finance has signed is not run.
5. Enable redemption for one brand with a conservative cap, verify a real receipt
   against a real Click or Payme fiscal response, then widen.

Rollback disables accrual and redemption commands and leaves every entry in place.
Balances are a liability; they are not deleted to undo a feature.

## Consequences

### Positive

- Split tender is representable in the payment aggregate from its first migration,
  which is the one moment it is cheap.
- A balance can be explained to a customer, an operator, and an auditor from the
  same rows, at any past instant.
- The receipt is issuable. A discount is a field both providers have; a
  platform-held tender is a field neither has, and the previous decision would
  have failed at the first real `submit_items` call.
- Withdrawing stored value removes the only part of this design that could have
  constituted an unlicensed payment service, and removes it without leaving
  dormant tables behind.
- Points expire per lot with warning, so the liability decays predictably instead
  of accumulating until someone notices it.

### Negative

- **The fiscal receipt no longer matches the number the customer was shown.** The
  storefront says 94 000 and the receipt says 82 000. That is correct and it will
  be reported as a bug, repeatedly, until the checkout copy and the receipt view
  explain it in three languages.
- **Loyalty now reduces declared revenue and VAT.** A marketing decision to raise
  the accrual rate is a tax decision, and the person who makes it will not know
  that. The liability report has to show the tax effect beside the points, or
  finance learns the rate changed from a quarterly return.
- A third deterministic allocation joins ADR 0018's tax apportionment and
  ADR 0038's rounding remainder, and the three have to compose or the receipt
  lines will not sum to the receipt.
- A partial refund returns less money than the customer remembers paying, because
  the money tender refunds first. This is arithmetically right and reads as
  short-changing.
- A new module, five tables, and a second reservation mechanism beside ADR 0018's
  coupon reservation. Two things now reserve against one checkout and must release
  together or not at all.
- An outstanding balance is a real liability on the tenant's books that Qoida now
  computes, so finance inherits a monthly reconciliation that did not exist.
- Brand-scoped accounts mean a multi-brand customer holds several balances and
  will ask why one cannot pay for the other. Under `TENANT_SHARED` the answer is a
  legal-entity answer, which is not a satisfying thing to say at a checkout.
- Deferred accrual means points do not appear immediately after paying, which
  reads as broken until copy explains it.
- The tender table depends on ADR 0038's payment-method registry, so tenders
  cannot be built before at least the registry table, its `LOYALTY_POINTS` row,
  and the `settles_from_balance` column exist.

### Accepted trade-offs

- Qoida ships no equivalent of Delever's Депозит, and will lose a comparison
  against it. Accepted, because the failure mode of guessing is operating an
  unlicensed payment service across every tenant at once.
- A ledger over a counter, at several times the build cost, because the cheap
  version cannot expire, reconcile, or explain itself.
- Points are denominated in som and therefore look like money to a customer. The
  three not-money constraints exist precisely because the denomination invites the
  opposite reading, and they cost real enforcement work at every boundary.
- Brand-scoped balances over one tenant pool, trading a feature customers would
  like for an inter-entity transfer nobody has modelled.

## Implementation checklist

- [ ] Obtain the finance and legal determination on whether a merchant-funded points rebate reduces the VAT base, and record it here as a closed input.
- [ ] Confirm the six proposed product defaults as ADR 0030 policy values. They are rows in `loyalty.accrual_rules` and `loyalty.redemption_policies`, not constants: a brand with no active row neither accrues nor redeems, so confirming them is an act rather than an edit.
- [ ] Obtain the legal review of terms covering no cash value, non-transferability, per-lot expiry, and the disposition of balances on tenant offboarding or brand closure.
- [x] Correct ADR 0038, which records that this ADR contributes two payment-method rows; it contributes one, and `CUSTOMER_DEPOSIT` is withdrawn.
- [x] Correct ADR 0013's alternatives row, which cites this ADR as rejecting the discount shape "for the same reason"; the reasons now differ by timing.
- [x] Update the ADR 0046 row in `docs/adr/README.md` for the new title and `Accepted` status, and the `CUSTOMER_DEPOSIT` reference in `docs/operations-spec/settings.md`.
- [x] Add settlement and tender tables. **V0042**, not the ADR 0013 migration: V0027 had already shipped by the time this ADR was implemented, and forward-only means the tables arrive in a later file rather than in the one this ADR asked for. No intent row exists yet, so the deadline the rollout section named is still met. `payments.order_settlements` and `payments.tenders` are there, and ADR 0013's intent model is one-to-many by way of `tenders.payment_intent_id`.
- [x] Add `settles_from_balance` and the `LOYALTY_POINTS` row to ADR 0038's `payments.payment_methods`, and point `tenders.payment_method_id` at it. The registry table is created in V0042 for the same reason; the `LOYALTY_POINTS` row is seeded per tenant by the application, because the registry is tenant-scoped. `tenders` carries a snapshot of the flag tied back by composite foreign key, so the snapshot cannot disagree with the row it names, and `ck_tender_balance_has_no_intent` refuses a payment intent on a balance tender.
- [x] Add the loyalty schema with `SELECT`/`INSERT`-only grants on `loyalty.entries`, the closed `entry_type` check constraint, and lot consumption. A trigger repeats the append-only rule to the callers a `GRANT` does not reach, and `entry_type` rejects `TOPUP` and `WITHDRAWAL` at the database rather than in an application enum.
- [x] Implement reservation, release, redemption, and reversal with conditional SQL, plus deferred accrual, the expiry sweep and forfeiture. The hold is a debit taken by one conditional `UPDATE`, so two tabs are separated by PostgreSQL rather than by a read; `RELEASE` returns points whose tender never settled and `REVERSAL` returns points whose settled tender is refunded, at the lots' original `expires_at`. **Expiry warning notifications are not built**: the `expiry_warning_days` column and the nearest-expiry field on the balance read are there, and the ADR 0020 message is outstanding.
- [x] Implement adjustment with reason codes and ADR 0027 approval thresholds, and the ADR 0015 merge path as an audited `ACCOUNT_MERGE` adjustment pair. The adjustment command takes one account and one signed amount and has no paired form, which is the whole treatment of the transfer back door.
- [x] Implement the per-line discount allocation and the refund cap that stops a points tender refunding as money. The allocation is `loyalty.api.RedemptionAllocation`, a pure function over the quote snapshot's lines; **`fiscal.fiscal_document_lines` does not exist yet**, so nothing writes to it. The refund cap is enforced inside the reversing transaction against the tender's settled amount, and `OrderSettlementService.refund` unwinds money tenders first.
- [ ] Extend checkout to plan, reserve, and settle ordered tenders, enforce the money-tender invariant, and carry `cash_due_minor` onto the delivery assignment. `OrderSettlementService` does the planning, the ordering, and all five invariants, and computes `cash_due_minor`; **wiring it into the ADR 0019 checkout and onto the ADR 0014 assignment is outstanding** and belongs to those modules.
- [ ] Build the liability report including the tax effect of redemptions, and its finance reconciliation. The per-brand outstanding and held figures are built and never pooled into one tenant number; **the tax effect of redemptions is not yet on the report**, and it is the half finance actually needs, so this stays open.

## Exit criteria

An order can be settled from points plus cash or points plus card in one checkout;
the tenders sum to the order total and at least one of them moved money; the
courier is shown the exact cash to collect; the fiscal receipt balances to the
amount the provider actually settled, with the redemption allocated across its
lines as a discount that reconciles to the tender; a refund unwinds both tenders in
the correct order and returns no more money than money was tendered; no command,
endpoint, or database grant can move value out of a points account except onto an
order belonging to that account's own customer and brand; and any customer's
balance at any past instant is reconstructable from stored entries without
recomputing today's accrual rules.

## References

- [ADR 0013](../partial/0013-payment-refund-and-service-recovery-compensation.md) — payment intents, refunds, and the cardinality this ADR corrects.
- [ADR 0015](../partial/0015-customer-accounts-cross-brand-identity-and-consent.md) — `BRAND_ISOLATED` identity, which decides the cross-brand answer, and the merge workflow.
- [ADR 0018](../partial/0018-deterministic-pricing-promotions-taxes-and-quotes.md) — inclusive VAT, whole-som money, and the quote snapshot the receipt lines derive from.
- [ADR 0021](../partial/0021-saas-plans-entitlements-and-usage-metering.md) — the append-only usage ledger this ledger copies.
- [ADR 0038](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md) — the payment-method registry, `fiscal.fiscal_document_lines.discount_minor`, and one `SALE` document per settled tender.
- [Fiscalization via payment providers](../../providers/fiscalization-via-payment-providers.md) — the Click and Payme field lists that force the discount representation.
