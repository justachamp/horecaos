# ADR 0072: Promo codes as a coupon-gated pricing input

- Decision status: Accepted
- Implementation status: Partial — the model, the pricing integration, the
  storefront apply/remove endpoints, the atomic redemption, and Operations
  authoring exist. See the implementation checklist for what each covers and
  what remains.
- Date proposed: 2026-09-05
- Date decided: 2026-09-05
- Deciders: Ayubkhon Abbosov (platform architecture), product, finance
- Depends on: ADR 0018, ADR 0019, ADR 0025, ADR 0031, ADR 0044, ADR 0046
- Supersedes / Superseded by: —
- Open inputs: Whether item-level promo-code discounts (restricted to a
  category or product) are needed for the first tenant, and the exact
  redemption-limit defaults a marketer should see pre-filled (product) —
  neither is structural; both are closeable by widening the authoring
  request's discount-shape enum later without a schema change, since the
  underlying `pricing.promotions` engine already supports item scope.

## Context

`frontend/storefront/src/app/services/ui-cart.service.ts` says, in its own
comment, that a promo code "has no platform equivalent." The storefront design
now being built shows a promo field in the cart ("Promokod" / "Qo'llash"), and
the owner has asked for it. There is no promo-code concept anywhere in the
platform today: no table, no endpoint, no authoring screen.

ADR 0018 makes pricing deterministic. `POST .../carts/{cartId}/pricing`
returns a quote bound to a cart, with a context hash covering every input the
total depends on, and checkout accepts only that quote for that cart
(`QuoteService.accept`, `CheckoutReservationStep`). A discount that changed the
total without being an input to that hash would break the property the whole
design exists for: that a client cannot present a price computed for a
different basket. So a promo code cannot be "apply the code, then subtract
some money" — it has to be a fact the quote is computed from, the same way a
price book or a delivery zone is.

The good news, on inspection, is that most of the hard part already exists —
more of it than expected. `pricing.domain.Promotion`,
`pricing.application.PromotionEvaluator`, and `PricingEngine`'s stages 3 and 4
are fully implemented and tested (`PromotionEvaluatorTests`,
`PricingEngineTests`) — condition and action types, best-one-wins stacking by
group, exclusivity, capped benefit apportionment, and, specifically, a
`requiresCoupon` flag on `Promotion` plus a `presentedCouponPromotionIds` set
on `PromotionEvaluator.PromotionContext` that is **already read into
`PricingEngine.contextHash()`**:

```java
// PricingEngine.contextHash(...)
canonical.append("|coupons=");
offers.context().presentedCouponPromotionIds().stream()
        .map(UUID::toString)
        .sorted()
        .forEach(entry -> canonical.append(entry).append(","));
```

Nobody ever populates that set, because nothing loads a promotion from the
database and nothing resolves a customer-typed code against one — and this is
truer than it first looks. `V0093__a_promotion_is_a_rule_not_a_script.sql`
already created `pricing.promotions`, `promotion_conditions`,
`promotion_actions`, `coupon_codes`, `coupon_customer_usage` and
`coupon_redemptions`, correctly implementing ADR 0018's coupon design —
`marketing-shell.ts`'s own routing comment names exactly this: "6.1 Promotions
and 6.2 Promo codes have a schema (V0093) and nothing above it (no authoring
service, no controller)." The actual gap this ADR closes is not the schema and
not the pricing engine — both already exist and neither needed to change —
but the authoring surface above the schema and the code that reads it into
the engine below. ADR 0044 also already extended ADR 0018's coupon model
once, to draw a line this decision inherits rather than re-argues (see
below).

ADR 0046 settled loyalty as points-only, redeemed as a payment tender rather
than a pricing discount, resolved and capped against the order's own total at
checkout. That total, by the time loyalty asks "how much may this order
redeem", already reflects whatever a promo code discounted — because the
discount lives inside the quote and loyalty redemption is decided after the
quote exists. The two mechanisms sit at different layers by construction, and
this ADR has to say explicitly whether that means they may combine.

## Decision

**A promo code is the redeemable face of a `pricing.promotions` row: a shared
code word, authored by a marketer, checked against limits under concurrency,
consumed exactly once per checkout.** It is not a new concept beside ADR 0018's
promotion model, and not a new table beside V0093's — it is ADR 0018's own
coupon design, already tabled by V0093, now given the one thing it was
missing: an authoring surface, and code that loads it into the engine that
already expects it.

**Relationship to ADR 0044.** ADR 0044 already closed the promo-code ambiguity
with two named concepts and this decision does not reopen it:

| Concept | Row | Issued by | This ADR |
|---|---|---|---|
| Shared code word (`OSH2026`) | `pricing.coupon_codes` | A marketer, once, from an authoring screen | **Builds this** |
| Per-recipient unique code (late-order apology) | `pricing.benefit_grants` with a code | A campaign or trigger, per message | Out of scope — marketing's `benefit_offer_id` still points at nothing, because `benefit_grants` and its coded-grant minting are unbuilt |

A tenant's marketer creates one promo code and gives it to many customers by
word of mouth or a flyer; a campaign mints one code per recipient,
automatically, single-use, tied to one account. Building the second kind means
building `pricing.benefit_grants` and campaign-side minting, which is ADR
0044's own unbuilt checklist item, not this one's.

**Discount shapes: a closed set of three, order- or delivery-scoped, never
item-scoped in this authoring surface.**

| Shape | Engine action | Scope |
|---|---|---|
| Percentage off the order | `ORDER_PERCENTAGE_DISCOUNT` | `ORDER` |
| Fixed amount off the order | `ORDER_FIXED_DISCOUNT` | `ORDER` |
| Free delivery | `FREE_DELIVERY` | `DELIVERY` |

`Promotion.Action.Type` already has five more members (`ITEM_PERCENTAGE_DISCOUNT`,
`ITEM_FIXED_DISCOUNT`, `ITEM_FIXED_PRICE`, `REDUCED_DELIVERY`, `FREE_ITEM`) and
the engine evaluates all eight identically. This ADR does not expose the other
five through `PromoCodeAuthoringService` because a promo code is typed once
against a whole cart, not against a chosen item: "20% off any pizza" needs a
product or category picker and a matching-line UI, which is materially more
authoring surface for a want nobody has asked for yet. Rejecting an
open-ended rule editor is ADR 0018's own argument and applies again here:
three named shapes are a request the authoring service can fully validate;
"any of eight action types with any of eleven condition types" is the rule
engine ADR 0018 already refused to ship. If a tenant needs an item-restricted
code, the fix is to widen the request DTO's shape enum and the validator, not
to expose the whole `Promotion` schema — the storage and the evaluator need
no change at all.

**Eligibility, expressed only through conditions the engine already has.**
`PromoCodeAuthoringService` writes exactly these `Promotion.Condition` rows
from typed request fields, never from free-form condition input:

- `minBasketMinor` → `SUBTOTAL_AT_LEAST`
- `channels` (empty = every channel) → `CHANNEL`
- `locationIds` (empty = every location in the brand) → `LOCATION`

`TIME_OF_DAY`, `DAY_OF_WEEK`, `FIRST_ORDER`, `CUSTOMER_SEGMENT`,
`QUANTITY_AT_LEAST` and the three line-matching conditions are unreachable
from this authoring surface for the same reason the action set is closed —
not because the engine cannot evaluate them (it already does, for whatever
promotions are authored some other way in the future), but because exposing
them now is scope this feature does not need and an authoring screen that
does not need to explain them.

**Limits, decided explicitly rather than left open:**

- **Total redemptions**: `coupon_codes.maximum_redemptions`, nullable (null =
  uncapped). Enforced by an atomic conditional `UPDATE` against
  `consumed_count`, not a count of rows — see Concurrency below.
- **Per-customer redemptions**: `coupon_codes.maximum_per_customer`, **not
  nullable** — V0093's schema carries no "uncapped per customer" option, so
  this authoring surface requires a positive integer (`@Positive int`, no
  default asserted here; the operations form defaults it to 1). **A guest
  cart's per-customer cap is not enforced at all**, deliberately: V0093's own
  comment on `coupon_redemptions.customer_account_id` already decided this —
  "the per-customer cap simply does not apply to those, and pretending
  otherwise by inventing an identifier would make two strangers share a
  limit." A signed-in customer's redemptions are tracked in
  `coupon_customer_usage`, one row per `(coupon, customer)`, claimed by an
  atomic upsert (see Concurrency).
- **Validity window**: `valid_from`/`valid_until` on both the promotion and
  the coupon row (kept in lockstep by the authoring service, since one is
  meaningless without the other reachable).
- **Minimum basket**: a `SUBTOTAL_AT_LEAST` condition, so an unmet minimum is
  not a special case — the code is simply not eligible, the same as any other
  unmet condition, and the storefront sees a normal partial-quote outcome
  rather than a distinct error path.
- **Channels and locations**: `CHANNEL` and `LOCATION` conditions, both
  optional (empty = unrestricted).

**Stacking, decided rather than left implicit:**

- **A cart carries at most one applied code.** Applying a second code replaces
  the first rather than rejecting it (ordinary storefront UX: typing a new
  code and pressing "apply" should not require removing the old one first) —
  decided in `CartService.applyPromoCode`, not in pricing, because it is a
  cart-state rule, not a pricing rule.
- **Every promo-code promotion is authored `exclusive = true`.** ADR 0018's
  evaluator already resolves exclusivity: an exclusive promotion suppresses
  every other promotion on the cart, and the better of two exclusive
  candidates wins alone. This means a promo code can never silently combine
  with a future automatic (no-code) promotion once ADR 0018's own authoring
  surface for those exists — a money-leak guard installed now, before there
  is anything to leak against, because retrofitting it after automatic
  promotions exist would be a behavior change under a tenant's feet.
- **A promo code and an ADR 0046 loyalty redemption may combine.** They are
  different mechanisms at different layers by ADR 0046's own construction: the
  promo code is a pricing input inside the quote, and loyalty redemption is a
  payment tender decided at checkout against the order's already-discounted
  total (`redeemed amount <= redemption policy cap resolved for this order` —
  "this order" is the order the promo code already reduced). There is no
  double-counting to guard against: the loyalty cap is evaluated against
  whatever the final priced total already is, promo discount included, so a
  customer cannot use a code to inflate the base a points redemption is
  calculated from beyond what they are actually paying.

**Where it is validated — twice, independently, neither trusting the other:**

1. **Cart-apply time** (`POST .../carts/{cartId}/promo-code`,
   `CartService.applyPromoCode`, read-only): looks the code up, and confirms
   it is `ACTIVE`, inside its validity window, has global capacity left
   (`consumed_count < maximum_redemptions`), and — if signed in —
   has per-customer capacity left in `coupon_customer_usage`. It
   deliberately does **not** check the minimum basket, channel, or location:
   those are `Promotion.Condition`s the evaluator already re-checks on every
   price, so duplicating them here would be a second copy of logic that
   could disagree with the first about what "eligible" means.
2. **Every price** (`QuoteService.quote`, read-only, same query as above):
   resolves the presented code fresh from the database — not from what the
   apply step decided — and includes its promotion in
   `PricingEngine.PromotionInputs` only if still eligible right now. A code
   that became exhausted between apply and price simply produces a quote with
   no discount; the storefront notices the total does not reflect the code
   and can prompt the customer, without pricing refusing the rest of the
   cart over a stale coupon.
3. **Checkout** (`CheckoutReservationStep`, atomic write): the only place a
   redemption is recorded. See Concurrency.

No step reads a cached answer from an earlier step. Each is a fresh query
against current state, exactly the discipline this platform already applies
to serviceability (checked at cart-open and again at checkout) and to the
blacklist (checked at the same two points, for the same reason).

**Redemption is recorded once, atomically, at checkout — as `REDEEMED`
directly, never as a separate `RESERVED` row created at quote time.** V0093's
own `coupon_redemptions.quote_id` comment states ADR 0018's original intent
literally: "usage is reserved with the quote and never first at order
creation." This decision deliberately does not implement that literally.
`RESERVED` is one of the three statuses the schema already carries, and using
it as V0093 intends would mean inserting a row at every
`QuoteService.quote()` call — which fires on every cart edit, since a line
change, a destination change, and now a promo-code change all clear the
attached quote (`touchAndInvalidatePricing`) and force a re-quote. A customer
editing their basket five times before checking out would attempt five
reservations for one eventual order, exhausting a low-total-limit code on
abandoned carts and re-quotes rather than on completed purchases — exactly
the cost ADR 0018's own alternatives table weighs against reserving too
early, just at the opposite end of the checkout flow. The actual risk that
literal quote-time reservation exists to close — "the customer sees a valid
discount that vanishes at checkout under concurrency" — is closed instead by
resolving eligibility, read-only, at every price (above): a customer is never
shown a total that already assumes an exhausted code, without ever writing a
row for a cart that will not become an order. The write that does happen
lands where every other checkout-time resource claim already lands — inside
`CheckoutReservationStep`, in the same transaction as the inventory hold and
the quote acceptance, going straight to `REDEEMED` and compensated to
`RELEASED` the same way inventory is released if a later step fails.
`RESERVED` is therefore a reachable status this decision never writes; a
retried `.../pricing` call before checkout produces no coupon-table row of
any kind, only a repriced quote.

**How the discount enters the quote and its context hash.** Nothing new is
needed here — this is the part that already existed and was unused:

```java
// PricingEngine.contextHash(request, inputs)
canonical.append("|promotions=");
offers.promotions().stream()
        .map(promotion -> promotion.promotionId() + ":" + promotion.definitionVersion())
        .sorted()
        .forEach(entry -> canonical.append(entry).append(","));
canonical.append("|coupons=");
offers.context().presentedCouponPromotionIds().stream()
        .map(UUID::toString)
        .sorted()
        .forEach(entry -> canonical.append(entry).append(","));
```

`QuoteService.quote()` now populates `PricingEngine.PromotionInputs` with the
brand's `ACTIVE` promotions and, when a code is presented and eligible, that
promotion's id in `presentedCouponPromotionIds`. Presenting a code, removing
it, or the code becoming ineligible between two prices all change this
string, so they all change the hash — a stale quote whose code stopped being
valid is caught by the same `PRICE_CHANGED` machinery that catches a changed
price book, because from the hash's point of view it is the same kind of
event: an input changed under the customer.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Apply the discount client-side or as a post-quote subtraction | Exactly the property ADR 0018 exists to prevent: a client presents a total for a basket the server never priced. Rejected outright, not weighed | Never |
| A `promo_codes` table local to `ordering` or to a new module | `pricing.coupon_codes` and `pricing.promotions` already exist (V0093), already implement ADR 0018's coupon design, and the engine already reads a `Promotion` and a `requiresCoupon` flag. Building a second, module-local table — not noticing V0093 was the actual risk this decision had to check for, the same lesson V0012 exists to teach ordering (AGENTS.md's "prefer the existing shared model") — would leave two places deciding what a discount is | Never |
| Reuse V0093's `RESERVED → REDEEMED` lifecycle literally, reserving at every `QuoteService.quote()` call | This is what V0093's own schema comment describes as ADR 0018's intent, and was the starting design here. Rejected on inspection of what actually calls `quote()`: every cart edit re-quotes, so reservation-at-quote-time reserves a slot per edit, not per order — see the Decision section's own reconciliation of this | A future checkout flow spans multiple pages with a real gap between price and pay, where read-only re-checking at price time stops being tight enough and an explicit hold becomes worth its cost |
| Mint a `pricing.benefit_grants` coded grant per code, reusing ADR 0044's per-recipient mechanism | Wrong cardinality: a shared code has one row and many redeemers, and ADR 0044's own alternatives table already rejected "one row per recipient" for the opposite direction (a campaign minting one row per person). Forcing a shared code through the per-recipient table would need a not-really-unique code and a redemption-counting scheme `coupon_codes` already has for free | Never; ADR 0044 already made the split |
| Expose the full `Promotion` condition/action schema in the authoring screen (a general rule editor) | ADR 0018 already refused this for pricing rules generally: arbitrary combinations are unreviewable and untestable as a set, and "an open-ended rule engine is not wanted" is this feature's own instruction. A closed three-shape, three-condition-field form is fully specifiable and fully testable | A tenant demonstrably needs item-level or time-windowed promo codes; extend the authoring DTO's enum, not the model |
| Allow two promo codes to stack on one cart | No product request for it, and it multiplies the money-leak surface (two independently-authored discounts interacting) for no stated benefit. A cart already supports at most one applied code at the UI/state level, so stacking two codes would need a second cart field and a second slot in every limit check | A marketer asks for combinable codes (e.g., a sitewide code plus a first-order code); revisit as a second stacking group rather than lifting exclusivity generally |
| Let a promo code and a loyalty redemption be mutually exclusive | Simpler to reason about in isolation, but ADR 0046 already resolves loyalty against the order's final total, so there is no arithmetic reason to forbid combining them, and forbidding it would be a customer-visible restriction with no money-leak justification behind it | ADR 0046's redemption cap changes to be computed against a *pre-discount* total, which would reopen the analysis above |
| Store the code in the clear, since a shared marketing code is meant to be public | Was this ADR's first instinct, on the (mistaken) premise that no coupon schema existed yet to have decided otherwise. V0093 already stores only `normalized_code_hash` plus a four-character `code_hint`, correctly implementing ADR 0018's own text ("hashed for lookup"). Overturning an already-built, already-correct column would be re-deciding a settled schema for a marginal authoring convenience, not a real hole — the plaintext is returned once, in the draft response, which is the one moment a marketer needs to see the string they just typed | Never; V0093's hashing is the accepted design and this ADR conforms to it |
| Enforce a per-customer limit against a guest cart by keying usage on a device or session identifier | Rejected for the same reason V0093's own comment already gives for leaving `coupon_redemptions.customer_account_id` null on a guest row: inventing an identity for a guest "would make two strangers share a limit" (or let one customer dodge it by clearing cookies), which is worse than the cap simply not applying | A durable, privacy-reviewed guest identity exists elsewhere in the platform and this ADR can key off it instead of inventing one |

## Consequences

### Positive

- The pricing engine needed zero changes: `PricingEngine`, `PromotionEvaluator`,
  and the context hash formula already expected exactly this input and are
  already covered by `PricingEngineTests` and `PromotionEvaluatorTests`.
- A promo code cannot silently change a total outside the hash — the same
  proof that already holds for a price book or a delivery zone now holds for
  a discount, by construction rather than by a new check.
- Two concurrent checkouts contending for the last redemption of a
  single-use code resolve to exactly one winner, proven by a Testcontainers
  test that fails without the guard (see Testing).
- Authoring follows the same draft → activate → retire idiom as
  `LoyaltyPolicyAuthoringService`, so a tenant that has already learned that
  screen's shape does not learn a second one.

### Negative

- The closed discount-shape and condition set means a marketer who wants
  "20% off any pizza with this code" cannot get it from this screen today —
  they need an item-level authoring surface this ADR deliberately does not
  build. That request is common in the competitive analysis this platform is
  measured against and will likely surface soon.
- A promo code presented and shown as valid in the cart can still fail at
  checkout if another customer consumes the last redemption in the interval
  between the last price and the checkout call — an unavoidable consequence
  of checking eligibility read-only ahead of the atomic write, traded
  deliberately against reserving on every re-quote (see Alternatives). The
  storefront must handle this refusal and re-quote rather than treat it as
  unexpected.
- `PromotionEvaluator.PromotionContext.localDayOfWeek`/`localMinuteOfDay` are
  populated from UTC rather than the location's own IANA timezone, because no
  promo code this authoring surface creates ever reads them (`DAY_OF_WEEK`
  and `TIME_OF_DAY` are not in the closed condition set) and building a
  cross-module location-timezone lookup for two currently-inert fields is
  scope this feature does not need. This becomes a real defect the day
  anything — this authoring surface widened, or a different one — creates a
  promotion that uses either condition.

### Accepted trade-offs

- Eligibility is checked three times (apply, price, checkout) rather than
  once and trusted — deliberately, per the platform's existing serviceability
  and blacklist discipline, at the cost of three independent code paths that
  must agree about what "eligible" means rather than one.
- A guest cart's per-customer limit is not enforced at all, so a single guest
  could in principle redeem a "one per customer" code many times across
  separate guest carts. Accepted because V0093 already made this trade-off
  (see the Alternatives table entry on inventing a guest identity) and total
  redemptions still cap the aggregate exposure.
- `RESERVED` is a reachable `coupon_redemptions.status` this decision never
  produces — every row this ADR writes starts at `REDEEMED` and only ever
  moves to `RELEASED`. A report or a future reader of this table that expects
  every row to have passed through `RESERVED` first, per V0093's original
  intent, will not find one that has.

## Specification

### Physical model

Already built, by `V0093__a_promotion_is_a_rule_not_a_script.sql`, and
unchanged by this decision — reproduced here as the reference this
implementation reads and writes, not as something newly designed:

```text
pricing.promotions
  id, tenant_id, brand_id, code, name, scope (ITEM|ORDER|DELIVERY)
  stacking_group, exclusive, priority, requires_coupon
  maximum_discount_minor null, currency
  valid_from, valid_until null
  status (DRAFT|VALIDATED|ACTIVE|SUSPENDED|ARCHIVED)
  definition_version, version, validated_at null, activated_at null, timestamps

pricing.promotion_conditions / promotion_actions
  promotion_id, sequence, tenant_id, brand_id
  condition_type|action_type, attributes_json jsonb

pricing.coupon_codes
  id, tenant_id, brand_id, promotion_id
  normalized_code_hash (sha-256, hex), code_hint (last 4 chars)
  status (ACTIVE|SUSPENDED|EXHAUSTED|ARCHIVED)  -- no DRAFT of its own
  maximum_redemptions null, maximum_per_customer (not null, >= 1), consumed_count
  valid_from, valid_until null, version, timestamps

pricing.coupon_customer_usage
  coupon_id, tenant_id, customer_account_id, consumed_count, maximum_per_customer

pricing.coupon_redemptions
  id, tenant_id, brand_id, coupon_id, promotion_id
  customer_account_id null, quote_id, order_id null
  status (RESERVED|REDEEMED|RELEASED)  -- this ADR only ever writes REDEEMED, then RELEASED
  amount_minor, currency, reserved_at, redeemed_at null, released_at null
```

`normalized_code_hash` is what a lookup queries; the plaintext exists only in
the request that created it and the one response that echoes it back
(`PromoCodeAuthoringRow.plaintextCode`, populated only immediately after
`draft`). `code_hint` — the last four characters — is what every later read
shows instead. `promotions.code` is the operator-facing handle V0093 already
gives every promotion (`uq_promotion_code UNIQUE (tenant_id, brand_id, code)`,
plaintext, unrelated to the customer-facing coupon secret); this authoring
surface writes the same string into both, since a promo code's whole point
is that the two are one thing.

`ux_redemption_quote ON (coupon_id, quote_id) WHERE status <> 'RELEASED'`
already gives "one live redemption per coupon per quote" for free: a retried
checkout under the same quote id cannot consume a second slot regardless of
the coupon's own limits.

`ordering.carts` gains one nullable column, `applied_coupon_code` (V0171,
this decision's only migration): the raw code the customer typed, normalized.
Nothing else about the code is stored on the cart — not the promotion id, not
an eligibility verdict — because every consumer re-resolves the code from
`pricing.coupon_codes` itself, which is the whole point of not trusting an
earlier answer.

### Concurrency: the last redemption

`PromoCodeRedemptionService.reserveForQuote` runs one conditional `UPDATE`
against the coupon's own row:

```sql
UPDATE pricing.coupon_codes
SET consumed_count = consumed_count + 1, updated_at = :now
WHERE id = :couponId AND tenant_id = :tenantId AND status = 'ACTIVE'
  AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)
  AND (maximum_redemptions IS NULL OR consumed_count < maximum_redemptions)
```

Zero rows affected means the code is no longer eligible right now — refused,
nothing else happens. One row affected means this transaction now holds the
row lock on that `coupon_codes` row until it commits or rolls back, so a
second concurrent transaction attempting the same `UPDATE` against the same
row blocks behind it rather than racing it — this is what makes "exactly one
winner" true without a separate advisory lock. Only for a signed-in customer,
the per-customer slot is then claimed by its own atomic upsert:

```sql
INSERT INTO pricing.coupon_customer_usage (coupon_id, tenant_id, customer_account_id, consumed_count, maximum_per_customer)
VALUES (:couponId, :tenantId, :customerId, 1, :maxPerCustomer)
ON CONFLICT (coupon_id, customer_account_id) DO UPDATE
SET consumed_count = pricing.coupon_customer_usage.consumed_count + 1
WHERE pricing.coupon_customer_usage.consumed_count < pricing.coupon_customer_usage.maximum_per_customer
```

An `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE` either inserts the first
row for this customer, updates an existing one whose count still has room, or
— when the `WHERE` fails — silently touches nothing and affects zero rows,
which this code reads as a refusal. If it refuses, the coupon-level increment
above is compensated (`consumed_count - 1`) before the attempt is reported
refused, so the coupon's own limit is never left looking consumed by an
attempt that did not actually redeem it. Only once both claims succeed is the
`coupon_redemptions` row inserted, directly as `REDEEMED`.
`release(tenantId, quoteId)` is the compensation `CheckoutReservationStep`
calls when a later step in the same checkout transaction fails: it flips the
redemption to `RELEASED` and gives both slots back.

### APIs

```text
POST   /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/carts/{cartId}/promo-code
DELETE /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/carts/{cartId}/promo-code

GET    /api/v1/operations/tenants/{tenantId}/brands/{brandId}/promo-codes
POST   /api/v1/operations/tenants/{tenantId}/brands/{brandId}/promo-codes
POST   /api/v1/operations/tenants/{tenantId}/brands/{brandId}/promo-codes/{id}/activate
POST   /api/v1/operations/tenants/{tenantId}/brands/{brandId}/promo-codes/{id}/retire
```

The storefront apply endpoint is `@CustomerOwned`, matching every other cart
mutation on `StorefrontOrderingController` — no capability, because a
customer applying a code to their own cart is not exercising delegated
staff authority. The operations endpoints declare `PRICING_READ` for the
listing and a new capability, `PRICING_PROMOTION_MANAGE`, for every mutation.

### Capability placement

`PRICING_PROMOTION_MANAGE` is granted to `TENANT_OWNER` alone, at `BRAND`
scope — the same placement as `LOYALTY_POLICY_MANAGE` and
`REFERRAL_POLICY_MANAGE`, and for the same reason those two give: this is not
an operational action but a decision to give the tenant's own money away,
"a currency decision rather than an operational one." `PRICING_AUTHOR` and
`PRICING_ACTIVATE` (price books, VAT profiles) are held more broadly, by
`TENANT_ADMIN`, `TENANT_FINANCE`, and `BRAND_MANAGER` as well — but those
capabilities author *prices*, which a tenant charges, not *discounts*, which a
tenant gives away, and the platform already treats those as different classes
of authority for loyalty and referrals. A promo code follows the narrower
precedent, not the broader one.

### What is NOT built

- Item-level or time/day-windowed promo codes (see the closed discount-shape
  and condition-field decisions above).
- Per-recipient unique codes minted by a campaign or trigger (ADR 0044's
  `pricing.benefit_grants` coded-grant path remains unbuilt).
- A `Promotions` rule-engine authoring screen (IA §6.1) for automatic,
  no-code promotions — only the coupon-gated path (IA §6.2) exists.
- A `promo redemptions` sub-view on the Customer detail screen (IA §5.2) — the
  redemption rows exist and are queryable, but no operations screen renders
  them per-customer yet.
- Correct local day-of-week/minute-of-day resolution for `DAY_OF_WEEK`/
  `TIME_OF_DAY` conditions (see Consequences, Negative).
- A scheduled sweep of anything here — there is nothing to sweep: a
  redemption is written once, atomically, at checkout, and never left in an
  intermediate state that could go stale.

## Rollout and rollback

No migration of existing data — there are no existing promo codes anywhere in
the platform. A tenant that authors no promo codes sees no change: an empty
`ACTIVE` promotion list resolves to `PromotionInputs` with an empty list, the
same "no promotions" branch `PricingEngine` already takes for every quote
today. Rollback is deleting the authored rows (`RETIRED` status, not a delete,
preserves the redemption evidence on any order already placed) and removing
the storefront apply/remove calls; nothing about checkout, inventory, or
quote acceptance changes shape if promo codes are switched off.

## Implementation checklist

- [x] `pricing.promotions`, `promotion_conditions`, `promotion_actions`
      (V0171), `pricing.coupon_codes`, `pricing.coupon_redemptions` (V0172),
      `ordering.carts.applied_coupon_code` (V0173).
- [x] `PromoCodeAuthoringService` / `PromoCodeController`: draft, activate,
      retire, behind `PRICING_PROMOTION_MANAGE` at `BRAND` scope, held by
      `TENANT_OWNER`.
- [x] `QuoteService.quote()` loads the brand's `ACTIVE` promotions and
      resolves a presented code into `PricingEngine.PromotionInputs`.
- [x] `PromoCodeQueryPort` (read-only eligibility, shared by the cart-apply
      endpoint and, module-internally, by `QuoteService`).
- [x] `PromoCodeRedemptionPort` / `JdbcPromoCodeRedemptionAdapter`: the atomic
      reserve, wired into `CheckoutReservationStep` beside the inventory hold.
- [x] Storefront `POST`/`DELETE .../carts/{cartId}/promo-code`,
      `CartService.applyPromoCode`/`removePromoCode`.
- [x] Concurrency test proving exactly one winner for the last redemption of
      a single-use code under two concurrent checkouts.
- [x] Operations authoring screen (Marketing → Promo codes), i18n, component
      tests.
- [ ] Item-level and time-windowed promo codes.
- [ ] Per-recipient coded grants (ADR 0044).
- [ ] Customer-detail promo-redemption sub-view (IA §5.2).

## Exit criteria

A marketer can create, activate, and retire a promo code from Operations. A
customer can type that code into a cart, see it accepted or refused with a
reason, see the discount reflected in the quote and its total, and have it
survive a repriced cart until they remove it or it expires. Two customers
racing to redeem the last use of a single-use code never both succeed. No
promo-code discount reaches an order total without being inside the quote's
own context hash.

## References

- ADR 0018: Deterministic pricing, promotions, taxes, and quotes
- ADR 0044: Marketing campaigns, audiences, and engagement content — the
  shared-code / per-instance-grant split this ADR inherits
- ADR 0046: Loyalty points and split tender — the tender-vs-discount boundary
  that makes stacking with a promo code safe
- `platform/docs/frontend-information-architecture.md` §5.2, §6.2
