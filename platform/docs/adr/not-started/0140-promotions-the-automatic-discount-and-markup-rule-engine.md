# ADR 0140: Promotions: the automatic discount and markup rule engine

- Decision status: Proposed
- Implementation status: Not started — nothing this record decides is built: no
  authoring, validation, simulation or lifecycle endpoint for an automatic
  promotion, no markup, no payment-method, channel-type, order-sequence or
  delivery-zone condition, no promotion redemption ledger, no per-promotion limit,
  no definition history and no promotion redemption fact. The substrate it builds
  on does exist: `V0093` tables (`pricing.promotions`, `promotion_conditions`,
  `promotion_actions`, `coupon_codes`, `coupon_customer_usage`,
  `coupon_redemptions`), `PromotionEvaluator` (twelve condition types and eight
  action types) inside `PricingEngine` stages 3 and 4, the promo-code authoring
  surface of ADR 0072, and a per-code redemption list. Reading the code found that
  several evaluator inputs are inert today, and that an amended order is repriced
  with none of the inputs it was placed under (see Context), so a promotion
  authored by any route other than a promo code would not behave as written.
- Date proposed: 2026-09-29
- Date decided: —
- Deciders: proposed by Claude (wave batch 14); Ayubkhon Abbosov (platform owner)
  decides. Written from `platform/docs/operations-gap-map.md` rows `6.1` and `7.9`,
  both `deferred` since batch 3 on the grounds that no promotions ADR owns the rule
  vocabulary or the redemption fact.
- Depends on: ADR 0018, ADR 0019, ADR 0025, ADR 0027, ADR 0029, ADR 0030,
  ADR 0031, ADR 0032, ADR 0036, ADR 0037, ADR 0038, ADR 0039, ADR 0043, ADR 0044,
  ADR 0046, ADR 0072
- Supersedes / Superseded by: —
- Open inputs:
  - Whether "exclusive" means instead-of or over-everything. `PromotionEvaluator`
    lets any exclusive candidate win alone regardless of value, which was harmless
    while only promo codes existed and is customer-hostile the moment an automatic
    offer is worth more than the typed code. This record makes exclusivity
    comparative (the customer receives the larger of the exclusive promotion and the
    best non-exclusive combination) and keeps ADR 0072's rule that a code never
    combines with an automatic offer. That refines evaluator behaviour ADR 0072
    describes as "suppresses every other promotion", so it needs finance and
    product to confirm (finance, product).
  - The approval thresholds above which activating a promotion needs a second
    person (ADR 0018 requires four-eyes "above configured risk thresholds"). This
    record proposes provisional ADR 0030 keys and defaults; the numbers are a
    money-at-risk judgement (finance).
  - Whether markup exists in the pilot at all, how an order-level markup (a
    service charge such as the common 10% «обслуживание») is fiscalised and taxed,
    and whether a surcharge that depends on payment method is lawful under
    consumer-protection rules. The engine change is small; the answers decide
    whether it may be switched on (finance, legal).
  - Payment method as a quote input. A condition on payment method only works if
    the cart carries the chosen method before it is priced, and switching method at
    checkout then changes the total and forces a re-quote (`PRICE_CHANGED`). This
    is friction the checkout UX has to be designed around (product).
  - Whether a cancelled order returns its slot to a promotion's redemption limit.
    This record follows ADR 0072 (no release on cancel) and reports cancellations
    in the fact so they can be excluded from counts; the first "first 100 orders"
    promotion that loses slots to cancellations will reopen it (product, finance).
  - Who adds a gift line. `FREE_ITEM` prices a line the cart already contains;
    whether the storefront auto-adds the gift or offers it for the customer to add
    is a cart-UX decision, and pricing must never invent a line (product).
  - Named-customer targeting depends on static audiences that ADR 0044 and ADR 0112
    do not yet offer (they filter on computed attributes, not on an uploaded list).
    Until one exists this record ships no named-customer condition; the owner
    confirms that is acceptable for the pilot, and product decides whether a
    birthday offer stays a campaign mechanic, as proposed (product, platform owner).
  - Whether delivery-fee bands and free-delivery thresholds are judged on the
    pre-promotion or post-promotion goods subtotal. The engine compares thresholds
    on the post-discount subtotal while the fee resolver is handed the
    pre-discount one (see Context), and the two should agree (finance, product).
  - What an amended order keeps of a promotion. This record freezes every input an
    amendment does not itself change, so an order placed inside a "lunch 12:00 to
    15:00" window keeps that promotion, on the whole order including a line added at
    15:05, and an order that holds a promotion keeps it at the definition version it
    was priced under even if the marketer has since suspended, archived or edited it
    (see Amendment). The alternative, pricing only the added line at the edit's own
    instant, gives one order two prices and has no meaning for an order-scope
    promotion. How long the customer can stretch a window this way is bounded only by
    ADR 0039's amendment cut point (`ordering.amendment_cut_point_status`, default
    `READY`); finance and product confirm that bound is short enough (finance,
    product).
  - Retention of promotion definition versions and of the redemption ledger.
    Both are evidence for orders that stay reconcilable for the financial
    retention period, so this record proposes they follow the order snapshot
    (finance, legal).
  - Who may author. `PRICING_PROMOTION_MANAGE` is held by `TENANT_OWNER` alone (ADR
    0072, "a decision to give the tenant's own money away"). The parity personas
    assume a marketer; whether a marketing job is created is out of this record and
    the owner decides it (platform owner).
  - Sequencing. Automatic promotions are not on ADR 0055's launch path
    (storefront, operations, payments, onboarding). This record does not rank
    itself against that order (platform owner).

## Context

**Two gap-map rows wait on this record.** Row `6.1` says a marketer cannot author
any automatic discount or markup at all: no order-versus-product scope, no gift
triggers, no eligibility by order type, source and payment method, no Nth-order
or first-order rule, no priority, stacking or cashback arithmetic, no date, time
or weekday window, no geozone, branch, customer or category scoping, and no
quote simulator. Its note asks for "a ruling on the three missing condition
types" (markup, geozone polygon, named customer). Row `7.9` says promotion spend
is invisible after the fact, and `operations-spec/statistics.md` §2.9 names
`reporting.fact_promotion_redemption` as *not built* with "the promotions ADR —
does not exist" as its owner. The information architecture (§6.1) lists the
feature set that both rows are graded against, including two HorecaOS additions
(usage limits and a quote simulator over a versioned policy snapshot).

**What exists, read from the source.**

- The schema is ready and generous. `pricing.promotions` (`V0093`) has scope
  (`ITEM`, `ORDER`, `DELIVERY`), `stacking_group`, `exclusive`, `priority`,
  `requires_coupon`, `maximum_discount_minor`, a validity window, a five-state
  lifecycle (`DRAFT`, `VALIDATED`, `ACTIVE`, `SUSPENDED`, `ARCHIVED`) and a
  `definition_version`. Conditions and actions are closed enumerations with
  `attributes_json` operands, and a promotion is data, never code (ADR 0018).
- `PromotionEvaluator` implements best-one-wins per stacking group, exclusivity,
  a benefit cap with largest-remainder apportionment, and an order discount clamped
  to what is left after items. `PricingEngine` stamps promotion id and definition
  version on every adjustment, and the context hash covers the promotion list and
  the presented coupon.
- Only one route authors a promotion: `PromoCodeAuthoringService` (ADR 0072)
  writes three action shapes (order percentage, order fixed, free delivery), three
  condition fields (minimum basket, channels, locations), always `exclusive` and
  always `requires_coupon`, moves `DRAFT` straight to `ACTIVE` without a validator,
  and is gated by `PRICING_PROMOTION_MANAGE`, held by `TENANT_OWNER` alone.
- Redemptions exist for coupons only. `GET .../promo-codes/{couponId}/redemptions`
  lists reserve, redeem and release rows (customer account id, order id, amount);
  nothing aggregates them and nothing exists for an automatic promotion.
  `BenefitGrantService#mint` (`V0265`) mints a per-recipient code, but no cart or
  quote path presents it: `BenefitGrantService#redeem` has no caller in
  `src/main`.

**Reading the evaluator's inputs and its callers found six things that are wrong or
inert today.** Items 1 to 5 are not live defects, because no automatic promotion can
be authored; each becomes one the day this record's authoring surface exists, so each
is a test to write before the surface ships. Item 6 is a live defect for promo
codes already.

1. *Product and category conditions can never match.* `QuoteService` builds
   `PromotionInputs` with an empty membership map (`Map.of()`), so
   `BasketLine.productId` is null and `categoryIds` empty for every line.
   `MenuMembershipLookup` and `JdbcMenuMembershipLookup` exist and nothing calls
   them. Only the `VARIANT` condition works. The lookup also reads authoring
   state, not the immutable publication, and returns direct categories only, both
   of which its own Javadoc states.
2. *Four condition types read neutral values.* `resolvePromotionInputs` passes
   `firstOrder = false`, an empty `customerSegments`, and a day of week and minute
   of day derived from **UTC**, not from the location's IANA timezone (the comment
   there says exactly this and defers it "the day either of those conditions is
   authored"). A "lunch 12:00 to 15:00" rule in Tashkent (UTC+5) would fire at
   the wrong hours, `FIRST_ORDER` would never be true, and `CUSTOMER_SEGMENT`
   never matches. The fulfilment mode is inferred as `DELIVERY` if a destination
   exists and `PICKUP` otherwise, so a dine-in order is priced as pickup.
3. *The context hash omits what those conditions read.* `PricingEngine#contextHash`
   covers promotion ids and definition versions and the presented coupon, and
   not `firstOrder`, segments, the local day and minute, fulfilment mode or the
   membership map. Two carts that price differently by those facts would share a
   hash.
4. *Exclusivity is not comparative.* `select` returns the single best exclusive
   candidate whenever any candidate is exclusive, however small its benefit next
   to the non-exclusive set it displaces. Item discounts from different stacking
   groups accumulate per line with no clamp to the line's gross (60% and 60% on
   one line), and no test in `PromotionEvaluatorTests` covers that case. The
   evaluator does not compare a promotion's currency with the quote's.
5. *Stage 3 and stage 4 interplay is approximate across a shared group.* Order and
   delivery candidates are evaluated against the subtotal reduced by the item
   promotions chosen in stage 3, then all candidates are re-selected together. If
   an order promotion beats an item promotion in one stacking group, its benefit
   was computed on a subtotal that the losing item promotion had already reduced.
   Separately, `QuoteService#goodsSubtotal` hands the fee resolver the
   pre-discount subtotal while `DeliveryFeeQuery.goodsSubtotalMinor` is documented
   as post-discount and the engine's threshold check uses the post-discount one.
   Whether any resolver decision reads it beyond passing it through needs a failing
   test before it is called a defect.
6. *An amended order is repriced with the edit's inputs, not the order's.*
   `OrderAmendmentService#repriceFor` reprices the whole basket through
   `CartPricingPort.priceCart`, and `PricingCommand` has no field for a service
   instant, a payment method or a fulfilment mode, so `QuoteService#quote` resolves
   every one of them from the clock at the moment of the edit (`clock.instant()`)
   and from neutral defaults. Four consequences follow by reading, none covered by a
   test. A time-window condition is judged at the edit's instant, so a "lunch 12:00
   to 15:00" promotion that priced an order at 12:30 is gone from the whole order
   when a line is added at 15:05. A payment-method condition finds no method. A
   `FIRST_ORDER` or `ORDER_SEQUENCE` condition, resolved by counting the account's
   orders, would count the order being amended, because that order already exists.
   And `repriceFor` passes `null` as `presentedCouponCode`; a promo code lives only
   on `ordering.carts.applied_coupon_code` (`V0171`), which nothing in the amendment
   path reads, so an order that was placed with a code loses the code's discount the
   moment a line is added (no amendment test presents a code, and this is the live
   defect). Two more gaps sit beside these: `FinancialIntent#needsReprice` is true
   only for added lines, changed quantities and a new address, so a
   `CHANGE_PAYMENT_METHOD` on its own never reprices and a payment-method promotion
   would outlive the method that earned it; and `ordering.orders` records no payment
   method and no service instant (the method lives on the payment intent), so the
   order cannot supply what the reprice needs. Last, the ledger this record draws
   is keyed on the quote (`unique (tenant_id, promotion_id, quote_id)`), while an
   amendment that reprices gives the order a new quote at every revision
   (`order_revisions.pricing_quote_id`), so a per-quote key admits a second row for
   the same order and promotion and 7.9 double-counts the redemption.

**What the vocabulary lacks** (parity matrix line 77, IA §6.1): markup; payment
method, sales-channel type and order-sequence conditions; a geozone condition;
customer targeting that works; excluded products; gift multiplicity; per-promotion
usage limits; a validator, a simulator, an approval step and version history;
loyalty compatibility flags; and a redemption fact. Two IA items are outside any
pricing engine and are ruled out below: free-form aggregator discounts and
operator-applied discretionary discounts.

**The constraint that makes this non-obvious** is ADR 0018's promise that the same
context and clock always produce the same total, from stored evidence, with no
interpreter on the pricing path. Every feature above widens what a total depends
on, so each one has to arrive as a value resolved before the engine runs, a term
in the context hash, and a line in the evidence a quote records, or it breaks the
one property the design exists to keep.

## Decision

**Extend the existing rule model. Do not build a second one.** A promotion stays a
`pricing.promotions` row with AND-only conditions and typed actions, in the closed
vocabulary below, authored through a validator and a lifecycle, priced by the same
pure `PricingEngine`, and explained by the same adjustment evidence. A promo code
remains the coupon-gated face of a promotion (ADR 0072, unchanged); an automatic
promotion is the same row with `requires_coupon = false`.

**Rule vocabulary (closed; every addition is a migration, a code branch and a
test together).**

| Need (IA §6.1) | Answer |
|---|---|
| Type: discount, markup, promo code | New `kind` (`DISCOUNT` or `MARKUP`); promo code is `requires_coupon` |
| Scope: order or product | Existing `scope`: `ITEM` (product), `ORDER`, `DELIVERY`; markup is `ITEM` or `ORDER` only |
| Fixed, percentage, delivery, gift | Existing eight discount actions. Gift triggers and multiplicity: `QUANTITY_AT_LEAST` gains an `exact` operand ("equal" as well as "at least"), and `FREE_ITEM` gains `triggerQuantity` and `mode` (`ONCE` or `PER_MULTIPLE`), still bounded |
| Free-form aggregator discount | Not a promotion. An order with `pricing_authority = EXTERNAL` bypasses the quote engine entirely (ADR 0040: no quote, no promotion evaluation) and carries the aggregator's figure in `order_external_pricing`; 7.9 counts those apart, never in the reproducible fact |
| Manual vs automatic activation | Automatic (engine selects), coupon-gated (customer presents a code). Operator-applied discretionary discounts have no ADR and are not decided here |
| Order type | Existing `FULFILLMENT_MODE`, with the context finally carrying `DELIVERY`, `PICKUP` or `DINE_IN` from the cart |
| Source | Existing `CHANNEL` (codes) and new `CHANNEL_TYPE` (`tenant.sales_channels.system_type`: WEB, IOS, ANDROID, TELEGRAM, KIOSK, QR_TABLE, CALL_CENTRE, POS), so "all app orders" needs no code list |
| Payment method | New `PAYMENT_METHOD` (codes from `tenant.channel_payment_methods`), matching the cart's selected money method, or on an amended order the method recorded on its quote (see Amendment); loyalty points are not a method for this purpose; an unselected method never matches |
| First order, Nth order, first order by source | `FIRST_ORDER` (kept, now resolved from real data) and new `ORDER_SEQUENCE {mode: FIRST\|NTH\|EVERY_NTH, n, basis: BRAND\|CHANNEL}`; a guest never matches |
| Date range, intra-day window, weekdays, 24/7 | Existing `valid_from`/`valid_until`, `TIME_OF_DAY`, `DAY_OF_WEEK`; no time condition is 24/7. Evaluated in the location's IANA timezone at the service instant |
| Geozone polygon | New `DELIVERY_ZONE {zoneIds}`, matching `ResolvedDeliveryCharge.zoneId` (ADR 0037 owns geometry; a promotion never carries a polygon) |
| Branch | Existing `LOCATION` |
| Customer, named customers | Existing `CUSTOMER_SEGMENT`, now resolved from ADR 0044 audience snapshots. No id list inside a rule (see Alternatives); named customers wait on a static audience |
| Category, product, excluded products | Existing `CATEGORY`, `PRODUCT`, `VARIANT`, each gaining an optional `exclude` operand (set difference) |
| Birthday-only | Not a condition. A birthday offer is an ADR 0044 automation minting a per-recipient benefit grant, which keeps a decrypted date of birth off the pricing path |
| Priority with a defined tie-break | Benefit, then `priority`, then promotion id (existing total order, stated once) |
| Stackable and cashback-compatible | `stacking_group` and `exclusive` (below) plus two loyalty flags: `loyalty_accrual` (`ACCRUE`, `SUPPRESS`) and `loyalty_redemption` (`ALLOW`, `BLOCK`) |
| Usage limits | New per-promotion total and per-customer limits with an atomic ledger, for automatic promotions |
| Pre-order re-validation | Window conditions read the service instant; a scheduled order is re-quoted at each ADR 0019 checkpoint (scheduled orders themselves are still open in ADR 0019). An amended order keeps the instant it was placed under (see Amendment) |
| Quote simulator over a versioned policy snapshot | `POST .../promotions/simulate` over the real engine, plus immutable definition versions |

**Markup is a price-plane step before any discount, and an order-level markup is
a fee line.** An `ITEM` markup adds a per-unit uplift (a fixed amount, or a
percentage of the unit price rounded HALF_UP to whole som per unit, so unit price
times quantity stays an integer) to matching lines at a new stage 2b, recorded as
an `ITEM_MARKUP` adjustment. Discounts then see the marked-up price. An `ORDER`
markup is a `SERVICE_CHARGE` line beside the `DELIVERY_FEE` line, computed on the
post-discount goods subtotal and reported inside `fee_minor`, recorded as an
`ORDER_MARKUP` adjustment. Both keep `total = subtotal + tax + fee − discount`
and leave `discount_minor >= 0` and `ck_order_total_reconciles` alone, which
matters because the last change to `subtotal`'s meaning (ADR 0072, 2026-09-14) was
expensive. Markups are never suppressed by exclusivity and never compared with
discounts. Within one stacking group the highest priority wins (then id), because
"best for the customer" would make a markup defeat itself; markups in different
groups add. The order-level markup is not built until the fiscal and tax
treatment of a service line is answered (Open inputs).

**Composition is a fixed procedure, and it is the only one.**

1. Resolve every input as a value before the engine runs (below), including the
   service instant in the location's timezone.
2. Stage 1 and 2 are unchanged: price book, base price, modifiers.
3. Stage 2b: select item markups (per group, priority order) and uplift the lines.
4. Stage 3: for each item-scope stacking group, the candidate worth most to the
   customer wins (then higher priority, then lower id). Each candidate is computed
   on the line's marked-up gross. Across groups the discounts add, then a
   **per-line clamp** scales them down by largest-remainder apportionment so a line
   never goes below zero.
5. Stage 4: order and delivery candidates are computed on the subtotal reduced by
   the stage 3 result. `ORDER` and `DELIVERY` scope may share a group ("free
   delivery or 10% off"); **`ITEM` may not share a group with either**, which
   removes the cross-scope contest that item 5 in Context describes. The validator
   refuses a mixed-scope group.
6. Exclusive promotions are evaluated as scenarios, each as if it were the only
   discount in the cart. The customer receives whichever is larger: the best
   exclusive scenario or the combination built by steps 4 and 5. A tie goes to the
   exclusive promotion, so behaviour is unchanged when an exclusive one is at least
   as good. A promo code (always exclusive, ADR 0072) therefore still never combines
   with an automatic offer, but no longer erases a better one.
7. Stage 5 and 6 (delivery, free-delivery threshold and delivery-scope benefits)
   and the order-level markup, then stage 7 (tax, on the discounted amount) and
   stage 8 (rounding once), as today.
8. Every step records an adjustment naming the promotion id and definition
   version. `CALCULATION_VERSION` becomes 3 with steps 3, 4 and 6; a quote priced
   under 2 is never re-derived.

Promotions never touch a price book. `ITEM_FIXED_PRICE` only ever reduces a price
(the new unit price is a ceiling, not a replacement), so a price book still owns
"what this channel and location charge", and a promotion owns "a conditional
change to that, with a ledger, limits and a report". An order whose
`pricing_authority` is `EXTERNAL` never reaches the engine (ADR 0040).

**Every condition input is a value in the context and a term in the hash.** The
context gains `fulfillmentMode` (from the cart), `channelType`, `paymentMethodCode`,
`deliveryZoneId`, `orderSequence` (per basis), the resolved `firstOrder`, the
segments the account belongs to, the local day and minute computed from the
location's IANA timezone at the service instant, and the membership map (with
category ancestors). All of them enter `contextHash`. A test enumerates the
context record's components and fails the build when one is missing from the
canonical string, so the next condition cannot repeat item 3 in Context.

**Nth-order counting is exact, not a projection.** `pricing.api` declares a
`CustomerOrderHistoryPort` that `ordering` implements (the same inverted direction
as `CustomerOrderActivityPort`, because `ordering` already depends on
`pricing.api`). It counts the account's orders at the brand that are not
`CANCELLED` or `REJECTED`, so two quick orders cannot both be "first", and it is
only called when an active promotion carries an `ORDER_SEQUENCE` or `FIRST_ORDER`
condition. It does not read `marketing.customer_metrics`, which is eventually
consistent. Checkout re-verifies inside the limit claim: a promotion with `FIRST`
or `NTH` must declare a per-customer limit of one, and the claim serialises
same-account checkouts. `EVERY_NTH` keeps a small race window, accepted below.

**Limits, and the ledger that makes redemptions reportable.** A promotion that does
not require a coupon may declare `maximum_redemptions` and `maximum_per_customer`.
Both are claimed at checkout with the two conditional writes ADR 0072 uses for
coupons (a conditional `UPDATE` of `consumed_count`, and an upsert into
`pricing.promotion_customer_usage`), inside `CheckoutReservationStep`'s
transaction, and written to a new ledger `pricing.promotion_redemptions`, one row
per (order, promotion), compensated to `RELEASED` if a later step fails. The row is
written for every automatic promotion an order carries, limited or not, because it
is also the source of the 7.9 fact; the two counters are touched only for a limited
one. A guest cart's per-customer cap is not enforced, as decided for coupons. A
coupon-gated promotion carries its limits on the coupon; the validator refuses
limits on both. A cancelled order does not return its slot (Open input).

**An amendment reprices the order as it was placed, plus what the amendment itself
changes.** ADR 0039 lets an operator add lines, change a quantity, change the
delivery address and change the payment method on a live order, and reprices the
basket each time (Context, item 6). Left as it is, that reprice reads the clock, the
payment method and the order count of the moment of the edit, which is the wrong
question: the customer bought one order, at one instant, by one method. Five rules
settle it, and they are the only ones.

1. *Placement inputs are recorded and inherited.* Every quote records the
   promotion inputs it was priced with in its `calculation_document` under
   `promotionInputs` (the "line in the evidence" the paragraph above requires): the
   service instant, fulfilment mode, channel type, payment method code, delivery
   zone, the resolved order-sequence position and `firstOrder`, the customer
   segments, and the promotions applied with their definition versions. An
   amendment's reprice starts from the `promotionInputs` of the quote behind the
   order's current revision (`order_revisions.pricing_quote_id`) and overrides
   exactly the inputs the amendment's own commands change: the basket (`ADD_LINES`,
   `CHANGE_LINE_QUANTITY`), the delivery point (`CHANGE_DELIVERY_ADDRESS`, whose zone
   is re-resolved under ADR 0037) and the payment method (`CHANGE_PAYMENT_METHOD`, the
   new code). The clock is never an override: the service instant stays the one the
   order was placed under, so the local day and minute, and every window condition,
   read the same value they read at checkout. `CartPricingPort.PricingCommand` gains
   a `PromotionFrame` (service instant, payment method code, fulfilment mode). On
   the cart path the method and mode come from the cart and the instant is null, which
   means the clock, as today; the amendment path always fills all three from the
   inherited inputs and the overrides. `FinancialIntent#needsReprice` becomes
   true for a `CHANGE_PAYMENT_METHOD` whenever the brand has an active promotion
   with a `PAYMENT_METHOD` condition or the order holds a promotion that has one, so
   switching to cash drops a "5% off with Click" promotion and switching back
   restores it, the way checkout behaves. The frame enters the context hash like any
   other input.
2. *Which promotions are candidates.* The candidates for an amendment are (a)
   every promotion the order already holds, evaluated at the definition version
   recorded on it (`pricing.promotion_definition_versions`), even if the marketer
   has since suspended, archived, edited or filled it, and (b) any other `ACTIVE`
   automatic promotion without limits whose conditions hold on the inherited
   inputs and the amended basket. Basket-driven conditions
   (`SUBTOTAL_AT_LEAST`, `QUANTITY_AT_LEAST`, the item predicates) are judged on the
   amended basket, so a line that carries an order past a free-delivery threshold
   earns it. A limited promotion the order does not already hold is not a
   candidate: the claim happens in checkout and an amendment adds none. The
   decision trace names it `NOT_CLAIMED_AT_PLACEMENT`. Whether a suspended
   promotion should keep applying to an order that already holds it is a money
   judgement recorded as an Open input.
3. *Limits are not tested again for a promotion the order holds.* Its own slot
   is already inside `consumed_count` and `promotion_customer_usage`, so testing
   them would refuse order 100 of 100 against itself, and claiming again would
   consume a second slot. An amendment never increments either counter.
4. *Sequence conditions never count the order itself.* The recorded
   order-sequence position and `firstOrder` are authoritative, so an amended `FIRST`
   order stays first however many orders the customer placed after it. Where no
   record exists, the `CustomerOrderHistoryPort` count takes the order id to exclude
   and the placement instant to count before
   (`countPriorOrders(tenantId, brandId, accountId, basis, placedBefore,
   excludingOrderId)`), so the order being amended is not its own predecessor.
5. *The ledger row moves in place.* The row is keyed by (order, promotion), not by
   quote: `claimed_quote_id` is the checkout quote and never changes (it keeps the
   claim idempotent), and `current_quote_id`, `discount_minor`, `markup_minor` and
   `last_revision` are rewritten when an amendment applies, in the same transaction
   that inserts the order revision and accepts the amendment's quote (a
   `pricing.api` call beside `QuoteAcceptancePort#acceptQuote` in
   `OrderAmendmentService#apply`). A promotion that newly applies inserts a row with
   both quote ids set to the amendment's. A promotion that stops applying has its
   row set to `RELEASED` and its counter left consumed, the same rule as a
   cancelled order (Open input); the 7.9 fact counts only `REDEEMED` rows. For every
   order and promotion the row's amounts equal the sum of that promotion's
   adjustments on the order's current revision, and a test asserts it. Amendments
   stop at the cut point, before an order completes, so the fact built at day close
   sees the amended row; anything that changes it afterwards is a divergence for
   `DayCloseService#recut` to report, not a silent rewrite.

The promo code follows the same rule. The redeemed coupon of an order is presented
again on every amendment reprice, read from that order's `REDEEMED` row in
`coupon_redemptions`, with no new eligibility or limit test; today's `null`
(Context, item 6) is a Slice 0 fix, not a wait for the authoring surface.

**Loyalty compatibility is two booleans, not arithmetic.** `loyalty_accrual =
SUPPRESS` means an order carrying this promotion earns no points; `loyalty_redemption
= BLOCK` means points cannot be spent on it. Across the applied promotions the most
restrictive value wins. Pricing records the two results on the quote, the order
snapshot carries them, and `loyalty` reads them: `PointsRedemptionService` refuses
a blocked redemption and `LoyaltyAccrualService` skips a suppressed accrual with a
recorded reason. Accrual's base is unchanged (money settled, net of fee and
redeemed portion, ADR 0046), which is already net of promotions, so a discounted
order earns on what was paid. A rate override such as double cashback is a
campaign-driven accrual rule (ADR 0044) and not a promotion. Promo codes keep ADR
0072's default of `ALLOW`.

**Authoring is a lifecycle with a validator, an approval and an immutable history.**
`DRAFT` → `VALIDATED` → `ACTIVE`, `SUSPENDED`, `ARCHIVED`, as the schema already
says. An `ACTIVE` promotion is never edited in place: the marketer suspends it,
edits it back to `DRAFT` (which bumps `definition_version`), validates and
reactivates. Every validate and every activation appends an immutable row to
`pricing.promotion_definition_versions` holding the canonical definition, so an old
quote is explainable against the rule that priced it, which V0093's comment
promises and the schema does not currently deliver (conditions are overwritten in
place). Activation needs a second person through the ADR 0027 approval mechanism
when a promotion is a markup, or its largest percentage or fixed amount exceeds a
threshold read through ADR 0030; below the thresholds it activates directly.

**A quote simulator is the real engine with a different sink.** `POST
.../promotions/simulate` takes a synthetic basket, channel, location, fulfilment
mode, payment method, service instant and synthetic customer facts (never an
account id), optionally a candidate promotion definition, and returns the full
result the quote endpoint would return plus a decision trace: for every promotion
in the brand, `APPLIED` or the reason it was not (`CONDITION_FAILED` with the
condition sequence, `OUTSIDE_WINDOW`, `COUPON_NOT_PRESENTED`, `LIMIT_REACHED`,
`LOST_TO` a named promotion, `SUPPRESSED_BY_EXCLUSIVE`, `ZERO_BENEFIT`,
`CURRENCY_MISMATCH`). It runs `PricingEngine.price` through the same input
resolution as `QuoteService`, writes no quote, no redemption and no counter, and a
test asserts that the simulator and a real quote agree on totals, adjustments and
hash for the same inputs. It may replay a stored definition version.

**A promotion redemption is a reporting fact with a stated grain.**
`reporting.fact_promotion_redemption` has one row per (order, promotion) and is
built at day close by `DayCloseService` through a `pricing.api` port, never by a
report reading `pricing` tables (ADR 0023). Its sources are the new ledger
(automatic), `coupon_redemptions` (coupon) and, later, benefit grants. Report 7.9
is metrics over that fact joined to `fact_order` in the same schema.

### Worked example

A delivery order on the WEB channel, paid by Click:
2 × Margherita (45 000 som each, category Pizza), 1 × Cola (12 000), delivery fee
15 000. Goods subtotal 102 000.

| Promotion | Scope, group | Rule | Benefit |
|---|---|---|---|
| P1, priority 10 | `ITEM`, `menu` | 10% off category Pizza | 9 000 |
| P2, priority 20 | `ITEM`, `menu` | 5 000 off each Pizza unit | 10 000 |
| P3 | `ORDER`, `payment` | 5% off, `PAYMENT_METHOD` Click | 4 600 |
| P4 | `DELIVERY`, `delivery` | free delivery, `SUBTOTAL_AT_LEAST` 90 000 | 15 000 |
| SAVE10 (code, exclusive) | `ORDER` | 10% off the order | 10 200 alone |

Stage 3: P1 and P2 share a group, P2 wins (10 000 against 9 000). The subtotal
falls to 92 000. Stage 4: P3 is 5% of 92 000 (4 600) and P4's threshold (90 000) is
met by the reduced subtotal, so free delivery applies (15 000). The combination is
worth 10 000 + 4 600 + 15 000 = 29 600. SAVE10 alone is worth 10 200, less than
29 600, so the customer keeps the combination and the trace reports SAVE10 as
`LOST_TO` the combination. Under today's evaluator SAVE10 would have won alone and
the customer would have lost 19 400 by typing a code.

### Worked example: adding a line after the window closes

A pickup order placed on the WEB channel at 12:30 in Tashkent, paid in cash: 2 ×
Margherita (45 000 som each), goods subtotal 90 000. L is an `ORDER` promotion, 10%
off, with `TIME_OF_DAY` 12:00 to 15:00; it gave 9 000, so the total is 81 000
(tax and fees left out). At 15:05 the operator adds 1 × Cola (12 000).

Under today's reprice the clock says 15:05, L fails, the discount is 0 and the
order total becomes 102 000: 21 000 more for a 12 000 drink. Under this record the
reprice inherits the service instant 12:30 from the order's quote, L still holds,
and it is 10% of the amended subtotal of 102 000, so 10 200; the total is 91 800
and the delta the customer agrees to is 10 800. The ledger row for (order, L) keeps
its `claimed_quote_id`, takes the amendment's quote as `current_quote_id`, and its
`discount_minor` moves from 9 000 to 10 200; report 7.9 still counts one redemption.
If the customer then switches from cash to Click, a "5% off with Click" promotion P
newly applies (it is unlimited and its condition now holds); had P been limited to
the first hundred orders it would not, because the amendment cannot claim a slot.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Priority-ordered first-match rules (a rule with higher priority wins outright), as the parity notes describe | It lets a low-value, high-priority rule suppress a better one, so the customer sees a worse price than the menu could give; "best for the customer, priority breaks ties" is order-independent and easy to explain | A tenant needs a deliberately non-optimal outcome that `exclusive` cannot express, such as a member price that must beat everything |
| A general condition tree with OR and NOT | ADR 0018 refused an expressive rule model: an operator-authored boolean tree is the start of a scripting language. OR is expressed by two promotions in one stacking group | The number of promotions that differ only in one condition becomes a support burden, measured from the definition history |
| Model markups and payment-method pricing only as price books | A price book resolves before the payment method, the customer or the order sequence is known, and cannot hold a percentage of the cart, a limit or a ledger. Time and channel planes stay in price books, which already do them well | Markups turn out to be only channel- or time-driven, in which case the markup actions can be dropped |
| Apply a payment-method discount after the quote, at payment time | A post-quote change to the total is what ADR 0018 and ADR 0072 rejected outright: the client would pay a total the server did not price | Never |
| Sequential compounding (each discount applies to the already-reduced price) | Order-dependent, so the same cart could price two ways by tie-break, and harder to explain on a receipt. Additive-on-gross with a clamp is order-independent | Finance shows a marketplace or supplier agreement that requires compounding |
| A polygon on the promotion (Delever's geozone) | A second geometry definition drifts from the delivery zones ADR 0037 owns, and puts geometry in a promotion row. Referencing zone ids reuses the resolved charge | A promotion needs an area that is not a delivery zone, such as a pickup radius |
| An account-id list inside a rule for named customers | It stores a per-customer entitlement list in a definition table with no consent, suppression or erasure hook (ADR 0029). Audience snapshots already have those | Static audiences are never built and the need is pressing, in which case the id list ships with an erasure hook |
| Birthday as a promotion condition | It puts a decrypted date of birth on the pricing hot path for every quote. A campaign minting a single-use grant does the job with a consent gate | Product insists on a self-serve birthday discount without a campaign, then a boolean computed by `customers` is passed in as a value |
| Let a stacking group mix item and order scope (the evaluator's comment allows it) | The contest between an item offer and an order offer in one group has no well-defined benefit comparison, because the order offer's value depends on which item offer wins. The validator refuses it; no authoring surface exists that needs it | A real "item offer or order offer, not both" need appears, and a defined fixed-point rule is written for it |
| Reprice an amendment with the edit's own clock, payment method and order count (what the code does today), so an amended order is priced as if placed now | A promotion vanishes from the whole order when a line is added after its window, a `FIRST` promotion stops matching because the order counts itself, and the total jumps by more than the added line is worth. It also makes the answer depend on when the operator clicked | Never for the instant and the sequence; the payment method is the one input an amendment legitimately changes and is handled as an override |
| Price only the added line at the edit's instant and keep the original lines' benefits | An `ORDER` or `DELIVERY` promotion has no per-line meaning, a threshold promotion would be judged on two baskets, and one order would carry two prices for the same window | Finance shows customers stretching a window by adding a token line at the end; the cut point is the lever first |
| A new ledger row per quote (the draft's `unique (tenant_id, promotion_id, quote_id)`) | An amended order carries a new quote per revision, so one redemption becomes several rows and 7.9 double-counts discount given; the sum of an order's rows would not equal its adjustments | Never; the checkout quote stays as the idempotent claim key |
| Return the slot when an order is cancelled | Consistent with ADR 0072 not to, simple to report around, and no evidence yet that it matters | The first limited promotion visibly loses slots to cancellations |
| Let reports read `pricing` tables live, as the per-code list does | ADR 0023 forbids a report reading a module schema; the per-code list is a bounded operator drill-down, not a report. The fact is built once at close and reused | Never for a report |
| Activate without a second person, as promo codes do today | ADR 0018 requires four-eyes above thresholds, and a markup or a large discount is a decision to change what customers pay. Low-value promotions still activate directly | Finance sets thresholds that make approval a bottleneck |

## Consequences

### Positive

- One rule model, one engine, one evidence trail. The marketer's screen, the
  simulator and the checkout all run the same pure function over the same
  inputs, so a simulation cannot disagree with a quote.
- The six problems in Context become named tests before any automatic promotion
  can reach a customer, instead of surprises after (item 6 already costs a
  promo-code order its discount at amendment, so that one is a fix, not only a guard).
- Every condition input entering the hash, enforced by a test that enumerates the
  context, closes the class of bug that item 3 in Context describes for good.
- A promo code and an automatic offer can coexist without a money leak and
  without punishing the customer: the code either beats the offers or steps aside
  and says why.
- Row `7.9` gets a defined grain and a counterfactual (average check with and
  without a promotion in the same period) that the legacy dashboard and Delever
  lack.
- Loyalty compatibility is expressible without a rate model in pricing.

### Negative

- **The vocabulary is still smaller than a tree with OR.** Marketers will
  duplicate promotions to express OR, and the simulator's trace is what keeps
  that survivable.
- **Each new condition or action costs a migration, a code branch, a hash term
  and a golden test, forever.** `CALCULATION_VERSION` 3 adds another version whose
  golden fixtures can never be deleted while orders reference it.
- **Comparative exclusivity changes what a customer gets.** A customer who types a
  code may keep an automatic offer instead and not see the code applied. The
  storefront needs a message for "your offers are already better" (a new
  outcome beside `CODE_NOT_FOUND` and `CODE_EXPIRED`), and support will get the
  question.
- **Payment method as a price input adds checkout friction.** Switching method
  after a quote changes the total and forces a re-quote.
- **The ledger adds a write to the checkout transaction** for each limited
  promotion, and the Nth-order count adds a query per quote for signed-in
  customers whenever such a promotion is active.
- **`EVERY_NTH` has a race window.** Two orders quoted at the same count can both
  be the fifth. `FIRST` and `NTH` are closed by the required per-customer limit;
  `EVERY_NTH` cannot be, and a customer would have to submit two checkouts inside
  one quote lifetime to use it.
- **Suspending to edit means a running promotion pauses while it is changed.** No
  live/pending pair of definitions exists, on purpose.
- **Slots are not returned on cancellation**, so a limited promotion can run out
  through orders that never completed.
- **The average-check comparison is not causal.** Customers who use a promotion
  differ from those who do not; the report calls the figure a comparison, not an
  uplift.
- **Markup carries legal and fiscal risk** the engine cannot decide, and an
  order-level markup depends on an answer that may be no.
- **An amended order keeps a promotion its window has since closed.** That is the
  intended price of one order at one instant, and it can be stretched until the
  amendment cut point; a suspended promotion also keeps applying to the orders that
  already hold it, so suspending stops new orders and not amendments to old ones.
- **Every quote now records its promotion inputs**, which grows
  `calculation_document`, and an order priced before calculation version 3 has none
  to inherit (its amendment resolves them from the order's own fields and the
  history port).
- **Membership is authoring state.** Until `publication_items` carry product and
  category membership, moving a dish between categories changes which promotions
  match it before the menu is republished, as the lookup's own Javadoc says.

### Accepted trade-offs

- Priority breaks ties and never overrides benefit, trading marketer control for a
  price the customer can predict.
- Additive discounts on gross with a clamp, rather than compounding, trade exact
  fidelity to some external agreements for order independence.
- The simulator refuses customer identifiers and takes synthetic facts, trading
  "what would this customer get" for keeping personal history off a read-only
  screen.
- Redemption facts are built at day close, so the 7.9 report lags by up to a
  business day, in exchange for one definition of a redemption.

## Specification

### Physical model (additive; numbers reserved by the wave that builds it)

```text
pricing.promotions  (columns added)
  kind varchar(8) not null default 'DISCOUNT'          -- DISCOUNT | MARKUP; markup scope in (ITEM, ORDER)
  maximum_redemptions integer null, maximum_per_customer integer null
  consumed_count integer not null default 0            -- check: within maximum_redemptions
  loyalty_accrual varchar(8) not null default 'ACCRUE'         -- ACCRUE | SUPPRESS
  loyalty_redemption varchar(8) not null default 'ALLOW'       -- ALLOW | BLOCK
  activated_by varchar(255) null, approval_id uuid null

pricing.promotion_conditions.condition_type  (check replaced; V0093 is not edited)
  + PAYMENT_METHOD, CHANNEL_TYPE, ORDER_SEQUENCE, DELIVERY_ZONE
pricing.promotion_actions.action_type  (check replaced)
  + ITEM_PERCENTAGE_MARKUP, ITEM_FIXED_MARKUP, ORDER_PERCENTAGE_MARKUP, ORDER_FIXED_MARKUP
pricing.quote_adjustments / ordering.order_adjustments .adjustment_type  (checks replaced)
  + ITEM_MARKUP, ORDER_MARKUP
pricing.quote_lines.line_type  (check replaced; the order carries fees as fee_minor and adjustments)
  + SERVICE_CHARGE
pricing.quotes  + loyalty_accrual_allowed boolean not null default true
                + loyalty_redemption_allowed boolean not null default true
ordering.orders + the same two columns, copied from the accepted quote
ordering.carts  + payment_method_code varchar(32) null

pricing.promotion_definition_versions
  promotion_id, definition_version, tenant_id, brand_id,
  definition jsonb not null (canonical scalars, conditions, actions),
  recorded_at, recorded_by, reason        -- append-only; primary key (promotion_id, definition_version)

pricing.promotion_customer_usage
  promotion_id, tenant_id, customer_account_id, consumed_count, maximum_per_customer
  -- mirrors coupon_customer_usage, same conditional upsert

pricing.promotion_redemptions               -- one row per (order, promotion), moved in place by an amendment
  id, tenant_id, brand_id, promotion_id, definition_version
  claimed_quote_id uuid not null                -- the checkout quote; never rewritten; idempotent claim key
  current_quote_id uuid not null                -- the quote behind the order's current revision
  last_revision integer not null                -- the order revision that last wrote the amounts
  order_id uuid not null, customer_account_id null
  discount_minor, markup_minor, currency        -- equal to the promotion's adjustments on the current revision
  status (REDEEMED | RELEASED), redeemed_at, released_at
  unique (tenant_id, promotion_id, claimed_quote_id)
  unique (tenant_id, order_id, promotion_id)

pricing.quotes.calculation_document  + promotionInputs
  -- service instant, fulfilment mode, channel type, payment method code, delivery zone id,
  -- order-sequence position, firstOrder, segments, applied promotions with definition versions

reporting.fact_promotion_redemption
  tenant_id, redemption_id, business_date, boundary_version, metric_calculation_version
  brand_id, promotion_id, promotion_code, definition_version
  source_kind (AUTOMATIC | COUPON | GRANT), coupon_id null
  order_id, customer_subject_hash null           -- ADR 0029 keyed hash, never an account id
  discount_minor, markup_minor, currency, redeemed_at
  primary key (tenant_id, redemption_id)
```

Every new table is granted to `horecaos_application` in its own migration
(V0035's lesson). No new table holds a name, phone, address or code word. Coupon
code words stay hashed as in `V0093`; the fact carries `coupon_id` and never the
hint.

### Vocabulary operands (attributes_json, validated per type)

```text
PAYMENT_METHOD  {paymentMethodCodes: [..]}
CHANNEL_TYPE    {channelTypes: [WEB|IOS|ANDROID|TELEGRAM|KIOSK|QR_TABLE|CALL_CENTRE|POS]}
                -- AGGREGATOR is not offered: those orders are externally priced (ADR 0040)
QUANTITY_AT_LEAST {quantity, exact: false}      -- exact = true reads "equal"; the name stays for existing rows
ORDER_SEQUENCE  {mode: FIRST|NTH|EVERY_NTH, n: int >= 2 for NTH/EVERY_NTH, basis: BRAND|CHANNEL}
DELIVERY_ZONE   {zoneIds: [..]}                  -- zones of this brand's tenant, checked at validation
PRODUCT|CATEGORY|VARIANT  {..Ids: [..], exclude: false}
FREE_ITEM       {variantIds, quantity (bound), triggerQuantity: int >= 1 (default 1),
                 mode: ONCE|PER_MULTIPLE}         -- PER_MULTIPLE = floor(matched / triggerQuantity), still bounded
ITEM_*_MARKUP   {basisPoints|amountMinor}        -- per unit, HALF_UP to whole som
ORDER_*_MARKUP  {basisPoints|amountMinor}
```

### Validator (refusals, each a stable code; a promotion cannot leave `DRAFT` with one)

`NO_ACTION`; `ACTION_SCOPE_MISMATCH` (item action on an `ORDER` promotion and so on);
`MARKUP_SCOPE_INVALID` (delivery-scope markup); `STACKING_GROUP_MIXES_SCOPES`;
`PERCENTAGE_OVER_100`; `FREE_ITEM_UNBOUNDED`; `UNKNOWN_REFERENCE` (a product,
category, variant, zone, channel or location not of this brand and tenant);
`WINDOW_INVERTED`; `WEEKDAYS_EMPTY`; `CURRENCY_MISMATCH` (against the brand's price
book currency); `LIMIT_ON_COUPON_PROMOTION`; `SEQUENCE_NEEDS_CUSTOMER_LIMIT`
(`FIRST` and `NTH` without a per-customer limit of one); `EXCLUSIVE_WITH_MARKUP`.
Warnings, not refusals: an uncapped percentage discount (V0093 already says a null
`maximum_discount_minor` is "a mistake waiting to happen for a percentage one"),
and a promotion that no active price book variant can ever match.

### Capability placement (ADR 0025)

`PRICING_PROMOTION_MANAGE` at `BRAND` scope, unchanged, holds create, edit,
validate, suspend, resume and archive; activation additionally passes the ADR 0027
approval above thresholds and is refused to the approver's own drafts. `PRICING_READ`
at `BRAND` scope holds the list, the detail, the redemptions drill-down and the
simulator (which reads pricing configuration the caller can already read and
returns nothing about any customer). Storefront apply and remove endpoints for
codes are unchanged (`@CustomerOwned`).

### APIs (ADR 0031; operations surface, following ADR 0072 and not ADR 0018's control-plane sketch)

```text
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/promotions
GET  .../promotions/{id}                      (with definition versions)
POST .../promotions                           draft; Idempotency-Key
PUT  .../promotions/{id}                      If-Match; DRAFT only
POST .../promotions/{id}/validate | activate | suspend | resume | archive
PUT  .../promotions/priority                  reorder within a brand and stacking group
POST .../promotions/simulate                  no writes
GET  .../promotions/{id}/redemptions          bounded, ids only
GET  /api/v1/tenants/{tenantId}/reporting/promotions/summary
GET  /api/v1/tenants/{tenantId}/reporting/promotions/redemptions
```

The two reporting endpoints sit with the other reporting queries
(`ReportingController`'s `/api/v1/tenants/{tenantId}/reporting` base) under
`REPORTING_READ`; they mask the customer to the pseudonym the fact holds.
Whether a manager may open the customer from a redemption row needs a customer id
in the fact, which ADR 0029's reporting rule forbids; the report shows the
pseudonym and that is the honest limit (product, privacy).

### Configuration (ADR 0030; provisional, finance to confirm)

```text
pricing.promotion.approval.percentage_over_bp      default 3000   (30%)
pricing.promotion.approval.amount_over_minor       default 100000 (100 000 som)
pricing.promotion.approval.always_for_markup       default true
pricing.promotion.simulate.max_lines               default 50
```

### Events and audit

`PromotionActivated` and `PromotionSuspended`, the two events ADR 0018 already
names, are published through the ADR 0032 outbox on the existing `pricing.events`
topic with a JSON schema and a catalogue entry; they carry ids, the definition
version, scope, kind and window, and nothing else. No consumer is specified here.
Audit facts (ADR 0027, `ChangeDocuments.diff` and `created`): `pricing.promotion.drafted`,
`.updated`, `.validated`, `.activated`, `.suspended`, `.resumed`, `.archived`, and
the approval request and decision. A redemption is not an audit fact; it is the
ledger row.

### Reporting metrics (ADR 0043)

Registered in `reporting.metric_definitions` with a definition version, provisional
until signed (`MetricSigningService`): `promo.redemptions` (rows whose order is not
cancelled), `promo.unique_customers` (distinct `customer_subject_hash`),
`promo.discount_som`, `promo.markup_som`, `promo.revenue_with` and
`promo.avg_check_with` (over the joined `fact_order` rows), and
`promo.avg_check_without` (the same brand and period, completed orders with no
redemption row). Cancelled orders are counted in the log and excluded from the
summary.

### Testing

- Each of items 1 to 5 in Context is written as a failing test first: product and category
  conditions match through `QuoteService` (they cannot today), a Tashkent lunch
  window fires at local lunchtime and not at UTC lunchtime, a dine-in order is not
  priced as pickup, `FIRST_ORDER` becomes true for a new account, two item
  discounts in different groups clamp at the line's gross, an exclusive code
  smaller than the automatic set steps aside, and a currency mismatch skips the
  promotion.
- Amendment, each written failing first against today's `repriceFor`: **amend after
  the window closes** (the worked example above: place at 12:30, add a line at 15:05,
  the promotion still applies to the whole order and the total is 91 800, not
  102 000); **amend a `FIRST` order** (add a line and the first-order promotion
  stays, including when the customer placed a second order in between); amend a
  Click-paid order with an added line (the payment-method promotion stays),
  `CHANGE_PAYMENT_METHOD` to cash on its own reprices and drops it, and back to Click
  restores it; **order 100 of 100 amended** (the promotion holds, `consumed_count`
  stays 100, order 101 is still refused); a limited promotion the order does not hold
  is not applied by an amendment and the trace says `NOT_CLAIMED_AT_PLACEMENT`; an
  order holding a since-suspended or since-edited promotion keeps it at its recorded
  definition version while a new order does not; a promo-code order amended with an
  added line keeps the code's discount (the live defect in Context, item 6); and the
  ledger after an amendment has exactly one row per (order, promotion), an unchanged
  `claimed_quote_id`, the amendment's quote as `current_quote_id`, amounts equal to the
  sum of that promotion's adjustments on the current revision, and one redemption in
  the 7.9 fact.
- Property tests over generated baskets and promotion sets: the result is
  independent of the order promotions are supplied in; no line, subtotal or fee is
  negative; adjustments sum to `discount_minor` and `fee_minor` exactly; the
  comparator is a total order.
- Golden fixtures for `CALCULATION_VERSION` 3, with the worked example above as one,
  and a fixture proving a version 2 quote still renders.
- The context-hash enumeration test, and the simulator-parity test.
- A concurrency test for the last slot of a limited automatic promotion and for two
  same-account checkouts of a `FIRST` promotion (single winner).
- Tenant isolation: a promotion, ledger row or definition version never resolves
  across a tenant or brand; a zone id of another tenant is refused by the validator.

## Rollout and rollback

No promotion exists outside promo codes, so there is no data to migrate. Slice in
this order, each releasable alone. **Slice 0:** the Context fixes with no new
authoring (membership wired, timezone-correct clock, fulfilment mode, hash terms,
per-line clamp, currency guard, comparative exclusivity, and the amendment frame with
the redeemed coupon carried across a reprice), shipped behind the
`CALCULATION_VERSION` 3 bump. **Slice 1:** validator, lifecycle, approval,
definition versions, authoring endpoints, events. **Slice 2:** simulator and the
trace. **Slice 3:** ledger, limits, `fact_promotion_redemption` and report 7.9.
**Slice 4:** payment-method, channel-type, order-sequence and zone conditions,
audience segments, and the loyalty flags. **Slice 5:** item markup. **Slice 6:**
order-level markup, only after the fiscal answer. Rollback is suspending the
promotions concerned (archived rows are history, never deleted) and, for slice 0,
routing new quotes to calculation version 2; orders already accepted keep their
immutable snapshot and their adjustments.

## Implementation checklist

- [ ] Slice 0 tests-first for the six Context items, then the fixes; bump
      `CALCULATION_VERSION` to 3; keep a version 2 golden fixture.
- [ ] Amendment: `PromotionFrame` on `CartPricingPort.PricingCommand`, the recorded
      `promotionInputs` on the quote, `OrderAmendmentService#repriceFor` filling the
      frame and presenting the order's redeemed coupon, `FinancialIntent#needsReprice`
      for a payment-method change, and the amendment tests above.
- [ ] Context hash enumeration test.
- [ ] Flyway: `pricing.promotions` columns, replaced checks, three new tables,
      cart payment method, quote and order loyalty flags, `GRANT`s (numbers reserved
      by the wave that picks this up; check every active worktree).
- [ ] `PromotionAuthoringService`, validator, lifecycle, approval wiring, definition
      versions, controller and the capability declarations.
- [ ] `PromotionEvaluator` decision trace and the simulator endpoint, with the
      parity test against `QuoteService`.
- [ ] `pricing.api` ports: `CustomerOrderHistoryPort` (implemented by `ordering`),
      `AudienceMembershipPort` (implemented by `marketing`),
      `PromotionRedemptionSource` (implemented by `pricing`, read by `reporting`).
- [ ] Ledger and limits in `CheckoutReservationStep`, compensated on failure;
      concurrency tests.
- [ ] `DayCloseService` builds `fact_promotion_redemption`; metrics registered;
      the two reporting endpoints; the console report tabs.
- [ ] New conditions, `FREE_ITEM` trigger and multiplicity, `exclude` operand.
- [ ] Loyalty flags through quote, order and `loyalty` (redemption refusal,
      accrual skip with a recorded reason).
- [ ] Item markup (stage 2b, `ITEM_MARKUP`); order-level markup (`SERVICE_CHARGE`
      line) only after the Open input closes.
- [ ] Outbox events `PromotionActivated` and `PromotionSuspended` with schemas and
      catalogue entries.
- [ ] Console: Marketing > Promotions rule list with priority reorder, the
      condition builder over the closed vocabulary, date range, weekday toggle and
      money-or-percent inputs, and the simulator panel; ru, uz-latn and en keys with
      the parity spec green; lazy-loaded.

## Exit criteria

A marketer authors "5% off when paying by Click" and "free delivery over 90 000",
sees both explained in the simulator against a real cart, activates them (a
second person is asked only if either crosses the configured thresholds), and a
storefront cart shows the discount.
The order carries the promotion id and definition version on its adjustments. A
promotion limited to the first hundred customers stops at one hundred under
concurrent checkouts. The 7.9 report shows redemptions, unique customers, discount
given and the average check with and without the promotion for the same period.
Typing a smaller promo code does not remove a better automatic offer, and the
storefront says why. Adding a line to a live order keeps every promotion the order
was placed with, even after its window has closed, without a second ledger row.

## References

- `platform/docs/operations-gap-map.md` rows `6.1` and `7.9`
- `platform/docs/frontend-information-architecture.md` §6.1, and the component
  list naming ConditionBuilder, RuleList and RuleSimulator
- `platform/docs/delever-parity-matrix.md` line 77 (missing condition and action
  types)
- `platform/docs/operations-spec/statistics.md` §2.9 (report 7.9) and the blocking
  table (`fact_promotion_redemption`)
- ADR 0018 (pipeline, rule semantics, approval requirement), ADR 0072 (promo codes,
  exclusivity, no reserve at quote time), ADR 0046 (accrual base, redemption as a
  tender), ADR 0044 and ADR 0112 (benefit grants, audiences, offers referencing
  promotions), ADR 0036 and ADR 0037 (channels, zones, resolved charge), ADR 0038
  (fiscal treatment of fee lines), ADR 0043 (metric layer, facts), ADR 0040
  (externally priced orders), ADR 0019 (scheduled orders), ADR 0039 (order
  amendment and its cut point)
- `V0093` (promotions, coupons, redemptions), `V0019` and `V0022` (quote and order
  adjustments), `V0025` (`DELIVERY_FEE` line type), `V0265` (benefit grants),
  `PromotionEvaluator`, `PricingEngine`, `QuoteService`, `MenuMembershipLookup`,
  `OrderAmendmentService#repriceFor` and `#apply`, `CartPricingPort`,
  `QuoteAcceptancePort`, `V0171` (the cart's applied code)
