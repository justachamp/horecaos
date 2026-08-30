# ADR 0018: Deterministic pricing, promotions, taxes, and quotes

- Decision status: Accepted
- Implementation status: Partial — the quote engine and the authoring path both exist.
  V0019 holds price books, assignments, prices and tax profiles; `JdbcPricingStore` reads
  and now writes them; `PriceAuthoringService` and `PriceAuthoringController` create a
  book, assign it, price variants and modifier options, set a VAT profile and activate
  under an expected version, behind `PRICING_AUTHOR` and `PRICING_ACTIVATE`. Activation
  deliberately does not reach into an issued quote: a quote is an immutable row carrying
  its own totals, so a customer at the payment step pays what they were shown for its
  fifteen minutes, and the book version bump is what the context hash pins. V0051 makes
  one open tax profile per brand per jurisdiction and one assignment per scope a database
  rule rather than a Java pre-check. Not built: promotions, coupons and benefit grants;
  exclusive tax; and neither an ADR 0027 audit fact nor a `PriceBookActivated` event is
  emitted on activation, because no authoring path in this codebase emits either yet and
  inventing one here would have been a second style.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), finance
- Depends on: ADR 0013, ADR 0015, ADR 0016, ADR 0030
- Supersedes / Superseded by: —
- Open inputs: Stacking rules for promotions and coupons (finance, product) — not needed for the first slice, which has no promotions

## Context

Pricing must support brand catalogs, location-specific price lists, modifier
prices, promotions, coupons, taxes, delivery fees, and future-order recovery
benefits. Recomputing historical orders from mutable rules is unacceptable, and
putting prices directly on products makes overlapping schedules, currencies,
priority, and audit difficult. Concurrent coupon use also needs strong limits.

## Decision

The pricing module owns versioned price books, promotion definitions, coupons,
benefit grants, tax configuration, and deterministic quote calculation. All
money is integer minor units plus ISO currency. A quote records every input,
rule version, adjustment, rounding step, and final total. An accepted checkout
copies the quote into immutable order pricing snapshots.

No arbitrary scripting is allowed in price rules. Rules use a constrained,
versioned condition/action model with validated attributes and deterministic
precedence.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A price column on the product or variant | No schedules, no channels, no location differentiation, no history, and no way to explain an old order's total | Never |
| Scriptable rules in Groovy, JavaScript, or a rules engine | Arbitrary code on the pricing path is unreviewable, non-deterministic across versions, and a code-execution surface driven by control-plane input. A constrained condition and action model is testable and safe | A genuine need for expressive rules appears. Extend the typed schema rather than opening an interpreter |
| Recompute historical orders from current rules | Totals would change retroactively, breaking refunds, settlement, and customer trust | Never |
| Store the calculation only as a JSON document | Unqueryable for reconciliation and reporting. JSON is kept as evidence beside normalized columns | Never |
| Floating-point money | Rounding drift that eventually produces a total nobody can reproduce. Integer minor units with an explicit rounding mode instead | Never |
| A third-party pricing or promotion engine | Local tax and fiscal treatment, offline determinism, and reproducibility of historical quotes are hard requirements that general engines do not meet | Never for the calculation core |
| Apply promotions in whatever order the database returns rows | Two runs of the same cart could produce different totals. Stable rule IDs, versions, and explicit priority decide instead | Never |
| Reserve coupon usage only at order creation | The customer sees a valid discount that vanishes at checkout under concurrency. Reservation happens with the quote | Never |

## Money and tax policy

Decided 2026-08-21 by the platform owner, closing two of this ADR's open inputs.
Both were blocking rather than incidental: a quote's promise is that it is
reproducible from its context hash, so changing either afterwards would make
every historical quote and every fiscal total retroactively wrong.

**Prices are VAT-inclusive.** The menu price is what the customer pays, and tax
is extracted from it rather than added to it. This matches how Uzbek restaurants
quote prices and how the Click and Payme receipts customers already receive are
laid out. Consequences that follow and are not negotiable per-quote:

- Stage 7 of the pipeline extracts tax from a gross amount:
  `tax = round(gross × rate ÷ (10000 + rate))` in basis points.
- A discount reduces the gross amount, so it reduces tax proportionally. Applying
  a discount to a net amount and then adding tax would produce a different total
  and a different fiscal figure for the same customer-visible price.
- `tax_profiles.mode` still exists, because a later tenant in another
  jurisdiction may need exclusive pricing. `INCLUSIVE` is the only mode the
  first slice implements, and an `EXCLUSIVE` profile is rejected rather than
  silently mishandled.

**Money is whole som, rounded HALF_UP.** Tiyin are obsolete in practice and both
payment providers settle in whole som, so a stored minor unit would be precision
nobody can pay. `amount_minor` therefore holds whole som for UZS.

- Rounding happens once, at stage 8, on the final total and on each line's tax
  share — never repeatedly through the pipeline, which is how a total stops
  equalling the sum of its lines.
- HALF_UP rather than HALF_EVEN: banker's rounding is less biased across many
  transactions, but a merchant checking a single receipt by hand expects
  half to go up, and an unexplainable receipt costs more than the bias.
- The rounding remainder is recorded as an explicit adjustment so a total can
  always be reconciled to its lines rather than differing by an unexplained som.

**A quote is valid for 15 minutes.** Long enough to finish a checkout, short
enough that a sold-out item or a price change is caught before payment rather
than after. Expiry returns `PRICE_CHANGED` with a fresh quote; the difference is
never silently charged.

## Pricing pipeline

Apply stages in this fixed order unless a later accepted ADR changes it:

```text
1. Resolve sales context and active price book
2. Price base variants and modifier selections
3. Apply item-level promotions/benefits
4. Apply order-level promotions/coupons
5. Calculate delivery/service charges
6. Apply delivery benefits or fee discounts
7. Calculate tax according to approved inclusive/exclusive policy
8. Apply currency rounding and verify totals
```

Every stage consumes and emits an explicit calculation document. Stable rule
IDs plus versions and ordered priority settle conflicts; database row order and
wall-clock timing never decide price.

## What is built so far

Price books with scoped assignments, tax profiles, the deterministic engine, and
the quote lifecycle. Promotions, coupons, and benefit grants are absent by design
— the first slice has none, and their tables arrive with the decisions that need
them.

**The engine is a pure function.** It reads no clock, touches no database, and
consults nothing that could differ between two runs; everything variable happens
in `QuoteService` before it is called. That is what makes the context hash worth
anything: `pricingIsDeterministic` prices the same cart at two different instants
and asserts the totals are byte-identical.

**Tax is extracted, not added.** 50,000 som on the menu at 12% is 50,000 to the
customer, of which 5,357 is VAT — not 56,000. Tax is computed once on the gross
and apportioned across lines, so the line taxes always sum exactly to the total.
Rounding each line independently would leave a remainder, and a total that does
not equal the sum of its lines is what an accountant finds and nobody can explain.

**Every step is recorded.** A customer asking "why is this 110,000 som" and an
auditor asking the same get the same answer: base price, modifiers, tax, each
with the price book or tax profile that produced it. A total alone answers
neither.

**Price book resolution is fully ordered.** Scope specificity, then assignment
priority, then book priority, then id. The final tiebreak exists because without
it two equally specific books would resolve by whatever the query planner emitted
first, and the same cart could price differently on consecutive requests.

**A changed price is never charged silently.** Acceptance requires the context
hash to still match and the quote to be unexpired, and the acceptance itself is a
conditional update, so two concurrent checkouts cannot both succeed. A mismatch
returns `PRICE_CHANGED` with a fresh quote to request.

Not yet built: promotions and coupons, benefit grants from ADR 0013 recovery,
delivery and service charges (pipeline stages 3 to 6), and the scheduled sweep
that expires stale quotes — `expireStaleQuotes` exists and has no scheduler yet.

## Physical model

### Price books

```text
pricing.price_books
  id, tenant_id, brand_id, name, currency, status
  valid_from, valid_until null, priority, version, timestamps

pricing.price_book_assignments
  id, tenant_id, brand_id, price_book_id
  scope_type (BRAND|LOCATION|CHANNEL), scope_id
  valid_from, valid_until null, priority

pricing.prices
  id, tenant_id, brand_id, price_book_id
  priceable_type (VARIANT|MODIFIER_OPTION|FEE), priceable_id
  amount_minor, valid_from, valid_until null, version
```

Overlapping assignments with equal precedence are rejected during validation.
All items in one quote must resolve to one currency.

### Promotions and coupons

```text
pricing.promotions
  id, tenant_id, brand_id, name, status, priority
  stacking_group, exclusive, valid_from/until, definition_version, timestamps

pricing.promotion_conditions
  promotion_id, sequence, condition_type, attributes_json

pricing.promotion_actions
  promotion_id, sequence, action_type, attributes_json

pricing.coupon_codes
  id, tenant_id, brand_id, promotion_id, normalized_code_hash
  status, total_limit null, per_customer_limit null, version, timestamps

pricing.coupon_redemptions
  id, tenant_id, coupon_id, customer_account_id null, order_id null
  quote_id, status, idempotency_key, reserved_until null, timestamps
```

Coupon values are stored encrypted where retrieval is needed and hashed for
lookup. Reservation and redemption use conditional SQL so limits remain true
under concurrency.

### Tax and benefit grants

```text
pricing.tax_profiles
  id, tenant_id, brand_id, jurisdiction_code, mode, rate_basis_points
  valid_from/until, version

pricing.benefit_grants
  id, tenant_id, brand_id null, customer_account_id
  benefit_type, value, currency null, scope, status
  source_recovery_case_id, valid_from, expires_at, version

pricing.benefit_usages
  id, tenant_id, benefit_grant_id, quote_id, order_id null
  reserved_value, consumed_value null, status, idempotency_key, timestamps
```

Recovery decisions from ADR 0013 create grants through an idempotent pricing
command. They are not promotion rows and cannot mutate the original order.

### Quotes and calculation evidence

```text
pricing.quotes
  id, tenant_id, brand_id, location_id, customer_account_id null
  currency, status, catalog_publication_id
  calculation_version, context_hash, subtotal/tax/fee/discount/total_minor
  expires_at, idempotency_key, created_at, accepted_at null

pricing.quote_lines
  quote_id, line_id, source_variant_id, quantity
  description_snapshot, unit/base/final amounts, tax snapshot

pricing.quote_adjustments
  quote_id, line_id null, sequence, adjustment_type
  source_type, source_id, source_version, amount_minor, description_code
```

Store the canonical calculation input/output document in JSONB only as evidence
alongside normalized query fields; JSON is not the primary business model.

## Quote lifecycle and checkout contract

```text
ACTIVE -> ACCEPTED
ACTIVE -> EXPIRED
ACTIVE -> SUPERSEDED
```

A quote has a short approved TTL and a context hash covering catalog
publication, variants/modifiers/quantities, location, channel, customer/guest,
coupon, fulfillment selection, and relevant projection versions. Checkout
accepts only an active quote with identical context and revalidates inventory.
If it expires or price inputs change, return a new quote and a stable
`PRICE_CHANGED` response; never silently charge the difference.

## Rule semantics

Supported initial conditions should be deliberately small: scope, channel,
location, customer segment, product/category, quantity, subtotal threshold,
day/time, first-order flag, and coupon/benefit eligibility. Initial actions:
fixed/percentage item discount, fixed/percentage order discount, fixed price,
free item with bounded quantity, and free/reduced delivery.

Maximum discount, eligible items, allocation, rounding, stacking group, and
exclusion are explicit. Percentage math uses integer rational arithmetic and an
approved rounding mode. Negative line/order totals are rejected.

## APIs

```text
POST /api/v1/storefront/pricing/quotes
GET  /api/v1/storefront/pricing/quotes/{quoteId}

POST /api/v1/control-plane/brands/{brandId}/price-books
POST /api/v1/control-plane/brands/{brandId}/promotions
POST /api/v1/control-plane/promotions/{promotionId}/validate
POST /api/v1/control-plane/promotions/{promotionId}/activate
GET  /api/v1/operations/orders/{orderId}/pricing-evidence
```

Rule activation requires validation, ADR 0027 four-eyes approval above
configured risk thresholds resolved through ADR 0030, and ADR 0027 audit. Preview endpoints run the same calculator with a fixed
clock and never reserve coupon/benefit usage.

## Events

```text
PriceBookActivated
PromotionActivated
PromotionSuspended
PricingQuoteCreated
PricingQuoteAccepted
CouponUsageReserved/Released/Redeemed
BenefitGranted/Reserved/Consumed/Expired
```

Pricing definitions are invalidated by versioned events. Quote events do not
publish coupon codes or customer PII.

## Testing

- Golden tests fix clock, currency, inputs, and calculation version, then assert
  exact lines, adjustment order, allocation, tax, rounding, and totals.
- Property tests assert totals never go negative and allocations sum exactly.
- Concurrent redemptions cannot exceed global or per-customer limits.
- Quote acceptance/expiry/reprice races settle once and release reservations.
- Tenant/brand/location ancestry and currency mismatch fail at boundaries.
- Old calculation versions can render and reconcile accepted order evidence.
- Performance tests price production-shaped carts within the agreed SLO.

## Rollout and rollback

Import legacy price lists/promotions into inactive versions and run shadow quotes
against captured sanitized carts. Explain every mismatch before enabling a
brand/location. Rollback routes new quote creation to the legacy calculator;
orders already accepted retain and use the new immutable snapshot. Activated
definitions are suspended, not deleted.

## Consequences

### Positive

- The same context and clock always produce the same total, and every total is
  explainable from stored evidence.
- Coupon and benefit limits hold under concurrency because usage is reserved,
  not counted.
- Historical orders reconcile without executing today's rules.

### Negative

- The constrained rule model will eventually be too small for some marketing
  request, and extending it is a schema and calculator change rather than a
  configuration change.
- Quote TTL and context hashing mean customers occasionally see a
  `PRICE_CHANGED` response and must re-confirm, which is friction at checkout.
- Golden test suites must be maintained per calculation version, and old
  versions can never be deleted while orders reference them.

### Accepted trade-offs

- Deterministic staged calculation is chosen over expressive rules. Marketing
  flexibility is deliberately traded for reproducibility and auditability.
- Preview endpoints run the full calculator without reserving usage, which
  duplicates some execution cost for accuracy.

## Implementation checklist

- [ ] Approve currency, tax, fee, rounding, allocation, and stacking policies.
- [ ] Define and version the initial condition/action schema. No promotion rule schema exists; `PricingEngine` leaves stages 3 and 4 out because there is nothing to apply.
- [ ] Add price, promotion, coupon, tax, benefit, and quote tables via Flyway. V0019 adds price, tax and quote tables and V0051 constrains them; there is no promotion or coupon table in any of the fifty-eight migrations. The nearest thing to a benefit is V0052's `payments.remedy_entitlements`, which ADR 0013 owns and which this module neither reads nor grants.
- [x] Implement money types and a pure deterministic staged calculator. `pricing.domain.Money` and `PricingEngine.price`, a pure staged function of `(QuoteRequest, PricingInputs, Instant)` with a canonical fingerprint over its inputs, covered by `PricingEngineTests`.
- [ ] Implement rule validation, activation, simulation, and audit. Authoring and activation are built: `PriceAuthoringService` and `PriceAuthoringController` create a book, assign it to a brand, a location or a channel, price a variant and a modifier option, set a VAT profile, and activate under an expected version behind `pricing.author` and `pricing.activate`, with V0051 making one open profile per scope a database rule. There is no rule validation, because there is no rule schema to validate; there is no simulation endpoint; and activation emits neither an ADR 0027 audit fact nor a `PriceBookActivated` event.
- [ ] Implement atomic coupon/benefit reservation, release, and consumption. No coupon or benefit exists to reserve.
- [ ] Integrate catalog/inventory context and ADR 0013 recovery grants. Catalog context is wired (`JdbcCatalogPricingContext`, and `PricingVariantLookup` answers catalog's publication validator) and inventory reserves against the quote id; ADR 0013 has no recovery grant to integrate.
- [ ] Implement storefront quote and Operations evidence APIs. A customer can now reach a quote, but not through this module's own surface: `POST /api/v1/storefront/.../carts/{cartId}/pricing` on `StorefrontOrderingController` prices a cart under `@CustomerOwned` through `pricing.api.CartPricingPort`. `QuoteController` itself still issues and accepts quotes under `pricing.read`, a staff capability on a `/api/v1/tenants/...` path, and there is still no quote-evidence read for Operations.
- [ ] Build legacy import, shadow comparison, mismatch classification, and dashboards. Nothing compares a Qoida quote to a legacy total.
- [ ] Add golden, property, concurrency, isolation, and performance tests. `PricingEngineTests` and `QuoteAndReservationTests` cover determinism and the quote/reservation pairing, and `PriceAuthoringTests` adds concurrency and isolation against PostgreSQL — concurrent activations settle once, a stale expected version loses, an exactly tied price book is refused at activation, and a price cannot be written for another brand's variant. Property and performance tests are not written.

## Exit criteria

Given the same versioned context and clock, Qoida always produces the same
explainable amount; checkout cannot accept stale or altered pricing; coupon and
benefit limits hold under concurrency; and every order total can be reconstructed
from immutable snapshots without executing today's mutable rules.
