# ADR 0137: Physical and nutritional product attributes

- Decision status: Accepted
- Implementation status: Partial — the backend and both customer and operator
  surfaces are built (operations batch 17, steps 1 and 2): V0448
  `catalog.variant_physical_attributes` with `GET`/`PUT
  .../variants/{variantId}/physical-attributes` under `catalog.author`, published
  inside the variant's publication payload and served on the storefront menu; V0449
  widens the line quantity to `numeric(10,3)` through the cart, quote, order, kitchen
  ticket and reporting facts, with every reader of it carrying the decimal; V0450
  snapshots catchweight facts on quote and order lines, and
  `PUT .../orders/{orderId}/lines/{lineId}/actual-weight` (`order.advance`) reconciles
  the charge at pick/handover with an order revision of source `CATCHWEIGHT`, priced
  as the order was bought (its fulfilment mode, delivery point and the promotion inputs
  recorded on its current quote, a combo as the combo, the options the server applied
  left to pricing) with the promotion ledger and loyalty flags restated against the new
  quote;
  `PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING` blocks publication and
  `CATCHWEIGHT_NOT_RECONCILED` blocks handover. The operator console has a Weight and
  nutrition tab in the product editor (weight or volume, catchweight with its quantum
  and estimated weight, splittable with a portion size, КБЖУ, the marking warning),
  New Order steps a splittable line by its portion size and prices a weighed line per
  quantum as an estimate, the order detail and the pass carry a scale panel that
  records the weighed total of a line and hold handover until it is recorded, and the
  ticket queue, pass, wallboard and tablet board show estimated or weighed weight and
  write a portion as `0,5`; both storefronts show weight, КБЖУ per 100 g and per
  serving, the price per quantum with the estimate and the "final weight is determined
  at handover" notice, and order by the portion. Not built: the till is told the
  nominal-weight amount at confirmation and never the weighed one; an order already
  paid through a provider refuses a weight that moves its total, because an
  incremental charge or partial refund is not performed, so a basket holding a weighed
  line is sold only for a method that settles at handover (the payment step does not
  offer the others and checkout refuses them, `WEIGHED_LINES_PAY_AT_HANDOVER`; the
  way to sell one for an online payment is ADR 0153, Proposed); fiscal receipt lines are not
  yet built from order lines, so Payme/Click integer counts are untouched; a
  fractional stock reservation stays out of scope as the record says; an amendment
  still changes whole units; no КБЖУ accuracy disclaimer is worded, since the record
  leaves that to legal and product.
- Date proposed: 2026-09-25
- Date decided: 2026-10-01
- Deciders: proposed by Claude (wave batch 12, w2-catalog-adrs) from
  `platform/docs/operations-gap-map.md` row `4.2c`, held for an ADR since
  batch 3; Ayubkhon Abbosov (platform owner) decides.
- Depends on: ADR 0016, ADR 0018, ADR 0025, ADR 0031, ADR 0038
- Supersedes / Superseded by: —
- Open inputs:
  - Whether `order_lines.quantity` (integer, `ck_order_line_quantity CHECK
    (quantity > 0)`, `V0022`) is widened to a decimal for a splittable/portion
    variant, or a variant with decimal portions instead uses a separate
    accounting unit this record does not yet name. This is the one structural
    fork in this record with a blast radius beyond `catalog` — every reader of
    `order_lines.quantity` (kitchen tickets, POS export, reporting's per-item
    counts, `AggregatorOrderIntakeService`'s line-arithmetic check) currently
    assumes an integer, and this record proposes widening it (see Decision)
    but flags the choice as needing the owner's sign-off before a migration
    reserves a number (platform owner).
  - The catchweight reconciliation moment — whether the actual weighed amount
    is captured at `PICK`/`HANDOVER` the way ADR 0038 already captures marking
    codes (this record's working assumption) or whether it needs a dedicated
    kitchen-scale integration this record does not scope (product, ADR 0041's
    owner).
  - КБЖУ (calories/protein/fat/carbs) source and accuracy responsibility — an
    author-typed figure with no verification path, unlike ИКПУ which ADR 0038
    at least has a reference list to check against. Whether the console
    should carry any disclaimer or accuracy warning is a legal question this
    record does not answer (legal, product).
  - Whether `splittable` interacts with inventory reservation (ADR 0017) —
    e.g. a splittable cake reserving a fractional unit against a whole-unit
    stock position — is out of scope here and left to whichever record next
    touches `inventory.positions`/`inventory.movements` (platform owner).


**Decision record, 2026-10-01.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all" over ADRs 0136–0144. Every open input above is closed on the default this record proposes for it; where a record defers an input to a named owner, that deferral stands as written and implementation proceeds without it. Implementation starts in operations batch 17 the same day.

## Context

**Gap-map row `4.2c` — "Physical & nutritional attributes: weight, measure,
catchweight + quantum, splittable, portions as a decimal, КБЖУ" — has sat
`NOT BUILT`, severity `3`, `deferred`, since batch 3**, with the note: *"A
weighed or splittable dish cannot be described at all, so catchweight items
cannot be priced correctly and the portion-band rules in 4.9 have no
attribute to band on."* `platform/docs/operations-spec/catalog.md`'s tab-1
table for the product editor lists `Отдел кухни` and other fields but carries
no row for weight, portions, catchweight, or nutrition at all — the console
spec was written before this ADR existed to describe what those fields even
are, and its "What Delever has that we should match" table records: *"Product
physical/nutritional attributes, catchweight, splittable, portions — Match
later — ADR 0016. `splittable` and marking interact (ADR 0038 forbids
splittable on marked goods), so build them together."* auto-add rule type 3
("portion-band") in the same document is explicitly *"unimplementable without
portions as a product attribute — ADR 0016 first."*

**What exists today that this record must not duplicate or contradict.**
`catalog.variants.unit_code` (`V0016`, default `PIECE`) is a display/ordering
unit — "Ед. изм." on the variant grid (`catalog.md` 4.2 tab 2) — and is
**distinct from** `catalog.fiscal_classifications.fiscal_unit_code`
(`V0028`), the ИКПУ measurement unit a receipt line must carry. Neither is a
weight, and neither is what this record adds. `catalog.fiscal_classifications`
already carries `marking_required`, `marking_scheme`, `excisable`,
`alcohol_by_volume_bp`, and `age_restriction_years` (`V0028`) — ADR 0038's own
domain — and already states the interaction this record must respect:
*"`marking_required` forces integer quantity and forbids splittable or
catch-weight semantics"* (ADR 0038, "Marked goods, age gating, and the
payment-method constraint"). A marked good and a catchweight/splittable good
are mutually exclusive by ADR 0038's own accepted decision; this record does
not reopen that, it enforces it from the other side.

**Why catchweight is a pricing question, not only a labelling one.** A
catchweight item — a cake sold "per piece, nominally 1.2&nbsp;kg" whose actual
weight varies — cannot be priced the way `pricing.prices.amount_minor` prices
everything else: a fixed amount per unit. It needs a **price per weight
quantum** (per 100&nbsp;g, say) and an **actual weight known only at
fulfilment**, mirroring exactly the shape ADR 0038 already accepted for
marking: a fact captured at `PICK`/`HANDOVER`, never at cart time, because
"the customer cannot know which physical [item] they will receive" (ADR
0038's own words, said there about marks, true here about weight). This
record reuses that shape rather than inventing a second one.

**Why decimal portions is a schema fork and not just a new column.**
`ordering.order_lines.quantity` is `integer`, checked `> 0` (`V0022`).
"Portions as a decimal" — half a portion of plov, 0.5&nbsp;kg of salad sold by
weight — cannot be expressed in that column without either widening it or
adding a second, parallel quantity column that only decimal-eligible variants
use. This is the one place this record's scope crosses out of `catalog` into
`ordering`, and it is flagged as an Open input for exactly that reason: every
reader of `order_lines.quantity` today assumes a whole number, and a decision
here binds all of them.

## Decision

**`catalog.variants` gains one attribute row, one-to-one, carrying every fact
this record adds — not four separate tables, because these facts are read and
written together (one form, one publication item) and none of them is shared
across variants the way a modifier group is:**

```text
catalog.variant_physical_attributes
  variant_id primary key references catalog.variants
  tenant_id, brand_id

  -- Weight and measure
  net_weight_grams integer null           -- the sellable unit's own weight
  net_volume_millilitres integer null     -- mutually exclusive with weight;
                                           -- a variant is weighed or measured
                                           -- by volume, never both

  -- Catchweight
  is_catchweight boolean not null default false
  catchweight_quantum_grams integer null  -- price is per this many grams
  catchweight_nominal_grams integer null  -- the label/menu estimate

  -- Splittable and portions
  is_splittable boolean not null default false
  portion_size numeric(6,3) null          -- decimal portions, e.g. 0.5

  -- КБЖУ, per 100 g (or per 100 mL for a volume-measured variant)
  calories_kcal_per_100 numeric(6,1) null
  protein_grams_per_100 numeric(5,2) null
  fat_grams_per_100 numeric(5,2) null
  carbohydrates_grams_per_100 numeric(5,2) null

  version, timestamps

  constraint ck_physical_weight_xor_volume check (
    net_weight_grams is null or net_volume_millilitres is null)
  constraint ck_catchweight_needs_quantum check (
    not is_catchweight or catchweight_quantum_grams is not null)
  constraint ck_catchweight_needs_weight check (
    not is_catchweight or net_weight_grams is not null
                       or catchweight_nominal_grams is not null)
```

A row is optional — most variants (a can of soda, a fixed-recipe burger) carry
none of this and the product editor's tab renders empty fields rather than a
row that has to exist to be absent. This matches how `catalog.fiscal_classifications`
is already optional-per-variant rather than a mandatory extension of
`catalog.variants`.

**The catchweight/marking exclusion is enforced where the two facts meet, not
by a database trigger across schemas.** `catalog.fiscal_classifications` and
`catalog.variant_physical_attributes` are independently-owned, independently-timed
authoring facts about the same variant — exactly the situation `CatalogValidator`
already exists to reconcile (it already resolves media, pricing, and offering
facts the same way). A new rule, `PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING`,
fires as a blocker when a variant's `fiscal_classifications.marking_required`
is true and its `variant_physical_attributes` row has `is_catchweight` or
`is_splittable` true — restating ADR 0038's own accepted sentence as a
publish-time check rather than a schema constraint that would need to reach
across two tables authored by two different screens at two different times.

**Catchweight pricing: the price is per quantum, the charged amount is
resolved at the same moment marking codes are.** `pricing.prices` needs no new
`priceable_type` — a catchweight variant's price row is an ordinary `VARIANT`
price, but its `amount_minor` is defined by this record to mean "price per
`catchweight_quantum_grams`," not "price per unit," whenever
`variant_physical_attributes.is_catchweight` is true. A quote for a
catchweight item is provisional, computed against `catchweight_nominal_grams`
(so the customer sees a real number to decide with), and is **not** what the
order charges: `ordering.order_lines` gains a nullable `actual_weight_grams`,
written at the same fulfilment step ADR 0038 already captures marks
(`PICK`/`HANDOVER`), and the line's `final_amount_minor` is corrected against
it before the order reaches a terminal fiscal state — the same "captured late,
reconciled before the receipt is final" shape ADR 0038 already accepted, not a
second one. A line still carrying its nominal, unreconciled weight past
`HANDOVER` is a new blocker on order completion, `CATCHWEIGHT_NOT_RECONCILED`,
the direct analogue of ADR 0038's `MARKS_INCOMPLETE`.

**Decimal portions: `order_lines.quantity` is widened from `integer` to
`numeric(10,3)`, gated by the variant's own attribute row.** This is the
structural fork named in Context and Open inputs, and this record's proposal
— pending the owner's sign-off flagged there — is to widen the shared column
rather than add a parallel one, on the same reasoning ADR 0072 already used
when choosing which side of a reconciling pair to correct: a second,
sometimes-used quantity column is a second thing every future reader has to
remember to check, exactly the failure mode `ck_order_total_reconciles`'s
history (ADR 0072) demonstrates once already. `ck_order_line_quantity` becomes
`CHECK (quantity > 0)` over the widened type (still excludes zero and
negative, now admits a fraction); a variant with no `variant_physical_attributes`
row or `portion_size IS NULL` is validated at cart time to require an integer
quantity exactly as today — the widened *type* does not by itself invite
fractional orders of a can of soda. `PricingEngine`'s stage-2 unit pricing
already multiplies `unit_amount_minor × quantity`; `BigDecimal` quantity
changes the arithmetic's type, not its shape, and every consumer named in the
Open input (kitchen tickets, POS export, reporting counts,
`AggregatorOrderIntakeService`'s arithmetic check) needs to accept a decimal
rather than being redesigned.

**КБЖУ is customer-facing display data with no pricing or validation
consequence.** It renders on the product page (storefront) and the product
editor (tab 1, alongside the new physical fields) and participates in no
constraint, no pricing rule, and no fiscal document. It is per-100-g (or
per-100-mL for a volume-measured variant) because that is the label
convention this market already uses, and a portion-scaled figure is a
presentation computation the client makes from the stored per-100 figures and
the variant's own `net_weight_grams`/`portion_size`, not a second stored
value that could drift from the first.

**What this unblocks in 4.9's auto-add rules.** `catalog.md`'s portion-band
auto-add type reads `portion_size` from this record's table once it exists;
this ADR does not itself decide auto-add rule semantics (`platform/docs/operations-gap-map.md`
already lists that as a separate, still-unowned decision, closest to ADR
0018/0019) — it only supplies the attribute those rules were blocked on.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Four separate tables (weight, catchweight, splittable, nutrition) instead of one attribute row | These facts are authored on one tab, published as one publication-item payload, and read together everywhere they are read; splitting them multiplies joins and migrations for facts that share a lifecycle and an owner | A future fact in this family has a genuinely different lifecycle (e.g. nutrition sourced from an external database on a schedule) — split that one fact out then |
| A `variant_attributes` EAV table (attribute name/value pairs) instead of typed columns | ADR 0016 already rejected EAV for catalog state generally — *"Unqueryable and unvalidatable at the database level"* — and this record's own constraints (`ck_catchweight_needs_quantum`, the weight/volume exclusion) need typed columns to express at all | Never for these named, closed-set attributes; a genuinely open-ended attribute vocabulary (spec-sheet style, `catalog.md` 4.13's still-undecided "Атрибуты" row) is a different, unrelated decision |
| A second `order_lines.portion_quantity numeric` column, leaving `quantity integer` alone | Avoids widening a column every existing reader assumes is a whole number, at the cost of a permanent two-quantity model every future reader of `order_lines` must learn to check both of, and a reconciliation question (which one does `ck_order_line_amounts` multiply against?) this option does not answer better than widening does | The owner decides the blast radius of widening `quantity` is unacceptable for the pilot; this becomes the fallback rather than the default |
| Compute the catchweight charge entirely client-side from a displayed per-kg price, never storing `actual_weight_grams` | Exactly the property ADR 0018 exists to prevent — *"a client presents a total for a basket the server never priced"* — restated for weight instead of a promo code | Never |
| Require exact integer weight in grams with no quantum, pricing linearly per gram | Loses the merchant's own pricing convention (a market prices "per 100 g," not "per gram," and rounding per gram versus per 100 g produces materially different totals at typical portion sizes) and gives `CatalogValidator`/the price simulator nothing to display that reads like a real menu price | A tenant is found that genuinely prices per-gram; add a second quantum value rather than replacing this one |
| Fold `marking_required`/catchweight/splittable exclusion into a single database constraint across `catalog.fiscal_classifications` and `catalog.variant_physical_attributes` | Postgres cannot express a cross-table `CHECK`, and a trigger duplicates logic the validator already centralises for every other cross-domain rule in this module (media, pricing, offering) — a second enforcement point that could disagree with `CatalogValidator` about what "conflict" means | Never; `CatalogValidator` is the accepted mechanism for exactly this class of rule |

## Consequences

### Positive

- Catchweight pricing reuses ADR 0038's already-accepted "captured late,
  reconciled before the receipt is final" shape instead of inventing a second
  reconciliation mechanism the fiscal document generator would need to learn.
- The marking/catchweight exclusion is enforced by the validator every other
  cross-domain catalog rule already goes through, so a reader who understands
  `CatalogValidator` today understands this rule with no new mental model.
- КБЖУ costs nothing beyond storage and a render — no pricing, fiscal, or
  reservation consequence — so it can ship independently of every other part
  of this record if the owner wants to sequence it first.
- 4.9's portion-band auto-add type, `catalog.md`'s own stated blocker, becomes
  buildable the day `portion_size` exists, with no further catalog change.

### Negative

- Widening `order_lines.quantity` to `numeric` is a change with real blast
  radius: every existing query, report, and export that reads that column
  assumes a whole number, and each must be checked rather than assumed safe —
  this is exactly the class of change ADR 0072's own history shows costs a
  full pricing-schema decision cycle to get right, and this record proposes
  taking it on rather than avoiding it.
- Catchweight introduces a charge that is provisional at quote time and
  corrected after fulfilment — a customer-facing UX cost (the total they see
  at checkout is not necessarily the total they pay) that this record does
  not design a storefront affordance for.
- КБЖУ figures are author-typed with no reference list to validate against,
  unlike ИКПУ (ADR 0038 at least has an official code list, even if not yet
  imported) — a wrong calorie count has no structural check catching it,
  only editorial review.
- `variant_physical_attributes` is one more optional per-variant table an
  author can forget to fill in, with no validator blocker forcing them to
  (this record adds no `PHYSICAL_ATTRIBUTES_MISSING` warning, deliberately,
  since most variants legitimately carry none of this).

### Accepted trade-offs

- A catchweight variant's displayed price is always an estimate until
  fulfilment; this record accepts that the storefront must communicate "final
  weight determined at pickup/delivery" rather than solving it by pricing
  catchweight items differently.
- Nutrition is per-100-g/mL only; a variant sold as "1 piece, no weight
  recorded" cannot show a per-portion calorie count unless an author also
  fills in `net_weight_grams`, which this record does not require.

## Specification

### Capability placement

Authored through the existing `CatalogAuthoringController` surface under
`catalog.author` at `BRAND` scope — the same placement as every other
variant-level fact (ИКПУ, price, media). No new capability: this is ordinary
catalog authoring, not a currency decision the way ADR 0072's promo-code
authoring was.

### APIs (additive)

```text
PUT /api/v1/control-plane/variants/{variantId}/physical-attributes
```

One endpoint, matching the shape of `PUT …/variants/{id}/fiscal-classification`
— a single upsert of the whole attribute row under an `If-Match` expected
version, not a field-by-field PATCH, since these facts are authored together.

```text
PUT /api/v1/control-plane/order-lines/{orderLineId}/actual-weight
```

Capability `INVENTORY_ADJUST`-adjacent — placed beside whatever endpoint ADR
0038 already uses to capture a mark at `PICK`/`HANDOVER`, since this is the
same authoring moment for a different fact; the exact controller is whichever
one implements ADR 0038's mark-capture flow, not a new module.

### Validator additions (`CatalogValidator`)

```text
PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING   -- blocker
COMBO... n/a here
CATCHWEIGHT_NOT_RECONCILED                  -- blocker on order completion,
                                             -- not on catalog publication
```

### Events

`CatalogDraftChanged` (ADR 0016, ADR 0032) already covers any authoring write
to the brand's catalog; no new topic. `actual_weight_grams` capture is an
ordering-side fact and rides whatever event already fires at `HANDOVER`
(ADR 0038's mark-capture path), not a new one.

## Rollout and rollback

No existing data — no variant carries any of these facts today.
`catalog.variant_physical_attributes` is opt-in per variant, so a brand that
authors none sees no behaviour change. `order_lines.quantity`'s widening from
`integer` to `numeric(10,3)` is the one migration that touches every existing
row; it is a type widening with no data loss (every existing integer value is
representable exactly), and every existing `quantity` value continues to read
and compare identically. Rollback of the catchweight/decimal-portion behaviour
is refusing new writes to `variant_physical_attributes` and to non-integer
`order_lines.quantity` values at the application layer; the widened column
type itself is not rolled back once live orders may hold a fractional value.

## Implementation checklist

- [x] `catalog.variant_physical_attributes` (Flyway; number reserved by the
      wave that picks this up).
- [x] `ordering.order_lines.quantity` widened to `numeric(10,3)` and
      `ordering.order_lines.actual_weight_grams` added (pending the Open
      input's sign-off).
- [x] `PUT …/variants/{id}/physical-attributes` on `CatalogAuthoringController`.
- [x] Mark-capture-adjacent endpoint for `actual_weight_grams` (no mark-capture endpoint exists
      yet, so it sits with the other `order.advance` writes:
      `PUT .../orders/{orderId}/lines/{lineId}/actual-weight`).
- [x] `CatalogValidator` rules listed in Specification.
- [x] `PricingEngine` catchweight quantum resolution and post-fulfilment
      correction of `final_amount_minor`.
- [x] Every reader of `order_lines.quantity` (kitchen tickets, POS export,
      reporting counts, `AggregatorOrderIntakeService`) reviewed for the
      widened type.
- [x] Product editor fields: weight/volume, catchweight, splittable, portion size,
      КБЖУ — on their own per-variant tab beside the fiscal one rather than tab 1,
      because every field is a fact about a variant and tab 1 is the product; storefront
      product-page rendering (both storefronts), the operator's New Order and the
      weighing at the pass.
- [x] Domain, PostgreSQL, and API tests: the weight/volume exclusion, the
      marking/catchweight exclusion, catchweight quote-vs-reconciled-total,
      decimal-quantity pricing arithmetic, and a golden test that an
      integer-quantity order's total is byte-identical before and after the
      `quantity` type widening.

## Exit criteria

An author can mark a variant catchweight with a price-per-quantum and a
nominal weight, and the product cannot also be marked `marking_required`. A
customer sees a provisional total for a catchweight item and the order's
final charge reflects the weight captured at handover. An author can mark a
variant splittable with a decimal portion size and a customer can order 0.5 of
it. КБЖУ figures entered by an author render on the storefront product page.
4.9's portion-band auto-add rule type has an attribute to band on.

## References

- ADR 0016: Brand catalog, publication, and location offerings
- ADR 0018: Deterministic pricing, promotions, taxes, and quotes
- ADR 0038: Legal entities, fiscal receipts, and fiscal product
  classification — the marking/catchweight exclusion and the
  captured-at-handover shape this record reuses
- ADR 0072: Promo codes as a coupon-gated pricing input — the reconciling-column
  history that motivates widening `order_lines.quantity` rather than adding a
  parallel column
- `platform/docs/operations-spec/catalog.md` §4.2 tab 1, "What Delever has
  that we should match", "Data the backend does not have yet"
- `platform/docs/operations-gap-map.md` row `4.2c`
