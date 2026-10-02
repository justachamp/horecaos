# ADR 0136: Composite products — combo groups and modifier depth

- Decision status: Accepted
- Implementation status: Partial — an operator can author a combo (groups, member variants, a
  price per component, min/max, nested level, hidden auto-select modifiers by fulfilment mode and
  per-product required/min/max overrides; V0443–V0447), publish it to the menu (COMBO_GROUP items,
  product comboGroupIds, option names, live component prices), sell it from the New Order composer,
  both storefronts and the cart/order API as one ordinary order line per picked component sharing
  `combo_selection_id`, and read it as its components on the order detail, the kitchen board and
  the expo screen; a hidden charge is itemised on the priced cart and the order. Not built: the
  console's add-lines amendment dialog cannot choose a combo's picks (the server accepts them); the
  fiscal receipt-line builder per component, reporting by `combo_selection_id` and the Clopos wire
  fields; storefront modifier groups below the first level, modifiers on a combo container and
  variant-level overrides are not published or enforced; the guest dine-in bill carries no lines;
  duplicating a product does not copy its combo groups; the cart and quote read combo structure
  from the live authoring rows, not the publication; the customer-facing wording that discloses a
  hidden charge is a neutral placeholder pending product and legal.
- Date proposed: 2026-09-25
- Date decided: 2026-10-01
- Deciders: proposed by Claude (wave batch 12, w2-catalog-adrs) from
  `platform/docs/operations-gap-map.md` rows `4.2a` and `4.2b`, both held for an
  ADR since batch 3; Ayubkhon Abbosov (platform owner) decides.
- Depends on: ADR 0002, ADR 0016, ADR 0018, ADR 0025, ADR 0031, ADR 0038, ADR 0041
- Supersedes / Superseded by: —
- Open inputs:
  - Whether a combo's container variant may itself be a member of another
    combo (nested combos) — this record assumes not and the physical model
    below forbids it by constraint; reverse this only on a real product
    request (product).
  - The maximum modifier-nesting depth this authoring surface exposes — this
    record proposes exactly one level (a modifier option may link a variant
    that carries its own modifier groups, and that is where it stops) and
    needs the owner's sign-off that one level is enough for the pilot's menus
    (platform owner, product).
  - Whether `HIDDEN_AUTO_SELECT` modifiers may ever carry a nonzero price
    silently added to a receipt without the customer choosing it — this
    record allows it (a delivery-box charge is exactly this) but the
    storefront/receipt copy that discloses it to the customer is not
    specified here (product, legal — an undisclosed mandatory charge is a
    consumer-protection question in some jurisdictions).
  - Legacy disposition of the Clopos/Qoida-era combo data, if any is found in
    the migration coverage register — ADR 0016's own "Legacy merchandising
    and preparation scope" section already defers this class of decision and
    this record inherits that deferral rather than closing it (product).


**Decision record, 2026-10-01.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all" over ADRs 0136–0144. Every open input above is closed on the default this record proposes for it; where a record defers an input to a named owner, that deferral stands as written and implementation proceeds without it. Implementation starts in operations batch 17 the same day.

## Context

**Two gap-map rows have named the same blocker since batch 3 and neither can
be built without a schema decision.** Row `4.2a` — "Combo groups with a
per-variant price map" — and row `4.2b` — "Modifier depth: nested
variant-modifiers, hidden modifiers auto-selected by order type,
modifier-level fallback to group values" — are both listed `NOT BUILT`,
severity `3`, `deferred`, in `platform/docs/operations-gap-map.md`, and both
carry the same note: *"An ADR 0016 amendment."* `platform/docs/operations-spec/catalog.md`
names the same gap from the authoring-console side: tab 3 of the product
editor states plainly, *"Not built and named: hidden modifiers auto-selected
by order type (Delever's Скрытые модификаторы, used for packaging), nested
variant-modifiers, and modifier-level fallback to group values — all ADR
0016, listed in the matrix as absent. Do not fake them with a free-text
flag."* and its "Data the backend does not have yet" table repeats both rows
verbatim against the same owner. No ADR has ever picked either up; this
record is that ADR.

**What ADR 0016 already built, and where it stops.** `catalog.products`,
`catalog.variants`, `catalog.modifier_groups`, `catalog.modifier_options`,
`catalog.product_modifier_groups`, and `catalog.variant_modifier_groups` all
exist from `V0016`. A modifier option may already carry a `linked_variant_id`
— "этот модификатор — сам товар" (`catalog.md` 4.4) — so an option that is
itself a sellable dish is already representable at the leaf. What does not
exist: any table that groups several *variants* into a single sellable
container with a chosen subset and its own price (a combo); any column on
`product_modifier_groups`/`variant_modifier_groups` beyond
`(product_id|variant_id, modifier_group_id, sort_order)` for an attachment to
override or auto-apply the shared group it points at; and any reader of
`variant_modifier_groups` at all — the table has existed since `V0016` and
`grep -a -rn variant_modifier_groups platform/src/main/java` finds it only in
`JdbcCatalogStore`'s write path, never in a query, a validator rule, or a
publication projection. ADR 0016's own publication-model section says as
much: *"`variant_modifier_groups` is deliberately not projected. The table
exists here and nothing writes it, so a variant-level link would be an
always-empty list in front of every client... Publishing it is a change to
make when authoring starts writing it, not before."* This record is that
change.

**Why a combo cannot be a free-text flag or a JSONB bundle.** ADR 0016 already
rejected an EAV/JSONB product model outright — *"Unqueryable and
unvalidatable at the database level, which defeats the invariants this ADR
exists to enforce"* — and a combo is a sharper case of the same argument, for
a reason specific to fiscal law rather than to catalog hygiene. ADR 0038
requires an ИКПУ/MXIK code, a package code, a fiscal unit and a fiscal name
**per receipt line**, and `catalog.fiscal_classifications` keys a
classification to exactly one of `variant_id`, `modifier_option_id`, or
`fee_id` (`V0028`, the `priceable_type` generated column). A combo priced and
sold as one lump amount under one "Комбо №1" line has no single classification
that is honest: a burger, fries, and a drink carry three different ИКПУ codes
and, plausibly, three different VAT treatments. **The per-variant price map
this row names is not a display convenience — it is the mechanism by which a
combo can be fiscalized at all**, because pricing each component separately
is what gives each component something to attach a classification to. This is
the connection neither the gap-map row nor `catalog.md` states explicitly and
this record makes it the spine of the Decision below.

**Where a combo line has to keep behaving like every other line.** `ordering.order_lines`
(`V0022`) is flat: `(order_id, line_number, source_variant_id, quantity,
unit_amount_minor, base_amount_minor, final_amount_minor, tax_amount_minor)`,
with `ordering.order_lines.ck_order_line_amounts` and, at the order level,
`ck_order_total_reconciles` (`total = subtotal + tax + fee - discount`,
V0022/ADR 0019) summing every line to the order's own stated total. Every consumer
downstream — `KitchenTicketService`, `PosOrderExportService`, reporting's
metric layer, the receipt renderer — reads that flat list and none of them
know a hierarchy exists. A combo design that introduces a parent/child line
relationship makes every one of those readers wrong the day it ships, silently,
unless each is taught to skip parent rows or unless the reconciliation
constraint is rewritten to exclude them — exactly the blast radius ADR 0072's
own history (a single mis-modelled total blocking every checkout path with a
promo code, fixed only on 2026-09-14 in `fix-promo-subtotal-gross`) argues
against taking on casually. The Decision below is shaped to avoid that
blast radius rather than to build the cleanest possible hierarchy.

## Decision

**A combo is a product whose sellable variant is a container, and an order
for it becomes several ordinary `order_lines` rows sharing one grouping key —
never a parent line and never a nested payload.** Kitchen tickets, POS
export, reporting, and the fiscal document generator therefore need no new
code to *sum* a combo order correctly; they already sum `order_lines`
correctly today, and a combo is, to every one of those readers, just several
lines that happen to share a value in one new column. Only readers that want
to *display* the grouping — the cart, the kitchen ticket layout, the receipt —
need to know the column exists at all.

### Combo groups and the per-variant price map

```text
catalog.combo_groups
  id, tenant_id, brand_id
  container_variant_id references catalog.variants  -- the sellable "Комбо №1"
  code, minimum_selections, maximum_selections
  allow_same_component_multiple_times, sort_order, status, version, timestamps

catalog.combo_components
  id, tenant_id, brand_id, combo_group_id
  component_variant_id references catalog.variants  -- the real dish/drink
  default_quantity integer not null default 1
  sort_order, status, version

  constraint: component_variant_id must not itself be a container_variant_id
  of any catalog.combo_groups row (no nested combos — see Open inputs)
```

Shaped deliberately like `modifier_groups`/`modifier_options`
(`minimum_selections`, `maximum_selections`, a capacity check the same way
`MODIFIER_GROUP_MINIMUM_UNSATISFIABLE` already works) because an author who
has learned that screen already knows this one, and because `CatalogValidator`
gets a capacity rule for free by generalising the one it already has rather
than writing a second.

**Pricing extends `pricing.prices` with a fourth `priceable_type`,
`COMBO_COMPONENT`, keyed to `combo_components.id` rather than to
`component_variant_id`.** This is the per-variant price map the gap-map row
names, and keying it to the pairing row rather than to the variant directly
is deliberate: the same drink can sit in two different combos at two
different prices ("free with the family box", "+3,000 som in the lunch box"),
and a variant-keyed price could not express that. Every mechanism ADR 0018
already built — price-book scope (`BRAND`/`LOCATION`/`CHANNEL`), priority
resolution, the close-and-open write discipline (`ux_price_current`), the
price simulator (`catalog.md` 4.8a) — applies unchanged, because
`COMBO_COMPONENT` is a `priceable_type` like the other three, not a parallel
pricing path. A component with no active `COMBO_COMPONENT` price for the
resolved book is a new validator blocker, `COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE`,
the direct analogue of `VARIANT_HAS_NO_ACTIVE_PRICE`.

**The container variant itself is never priced or sold directly.** It exists
so `catalog.products`/`catalog.variants` has something to name, photograph,
and publish, and so `order_lines.source_variant_id` on a component line's
sibling grouping metadata can point back to it (see below). `CatalogValidator`'s
`VARIANT_HAS_NO_ACTIVE_PRICE` rule is amended to skip a variant that is a
`combo_groups.container_variant_id` — the opposite check applies instead:
`COMBO_HAS_NO_PRICED_COMPONENTS` if every one of its components lacks a
`COMBO_COMPONENT` price.

### Cart, order lines, kitchen tickets, POS export, and fiscal lines

**`ordering.order_lines` gains two nullable columns and no new table:**

```text
ordering.order_lines  (columns added)
  combo_selection_id uuid null  -- groups the components of one combo purchase
  combo_container_variant_id uuid null  -- which combo this component belongs to
  constraint: (combo_selection_id IS NULL) = (combo_container_variant_id IS NULL)
```

Ordering a combo writes one `order_lines` row **per selected component**,
each with its own `source_variant_id` (the real dish), its own
`unit_amount_minor`/`final_amount_minor` resolved from that component's
`COMBO_COMPONENT` price, its own `tax_amount_minor` (apportioned exactly as
today, since `PricingEngine`'s stage-7 tax apportionment already works per
line and needs no change), quantity multiplied by the combo's own line
quantity, and the same `combo_selection_id` (one per combo instance in the
cart) plus `combo_container_variant_id` (the combo's own variant, for display
and for the receipt header). **No row is written for the container variant
itself.** `ck_order_total_reconciles` and every existing sum-over-order_lines
reader — reporting, the receipt total, `AggregatorOrderIntakeService`'s
arithmetic check — is correct with zero changes, because a combo is
arithmetically indistinguishable from ordering its components separately;
only the grouping key is new information layered on top.

- **Cart display** groups line items by `combo_selection_id`, showing the
  container's name and photo (`catalog.media_relations` on
  `combo_container_variant_id`) as a header with the priced components
  indented beneath — matching how `catalog.md` already asks 4.1/4.2 to render
  a variant range as one row rather than several.
- **Kitchen tickets** (`KitchenTicketService`, ADR 0041) group ticket items
  the same way: a combo prints as one header line with each component as a
  normal, independently routable ticket item — a burger station and a drinks
  station each still see only their own component, which is what "routing"
  already means in ADR 0041 and what would be lost if a combo were one opaque
  ticket line.
- **POS export** (`PosOrderExportService`) needs no schema change to keep
  working: it already exports `order_lines` flat, and a POS/fiscal terminal
  that understands `combo_selection_id` can print a grouped receipt; one that
  does not simply prints N ordinary lines, which is correct, not degraded,
  because that is what the ИКПУ-per-line requirement already demands the
  receipt look like.
- **Fiscal lines** need nothing new either: each component line resolves its
  own `catalog.fiscal_classifications` row through `source_variant_id`
  exactly as a non-combo line does today. The classification workbench
  (`catalog.md` 4.12) already asks an author to classify every variant; a
  component variant is just a variant, classified once, usable inside any
  number of combos.

### Publication and what a channel must render

`catalog.publications`/`publication_items` (ADR 0016) carries a `PRODUCT`
item's `modifierGroupIds`; it needs a fourth entity kind, `COMBO_GROUP`, whose
`immutable_content_json` snapshots `combo_components` and each component's
**resolved `COMBO_COMPONENT` price** at publish time — never a live join, for
the same reason a published menu never joins to a live price today. A
`PRODUCT` item for a combo container carries `comboGroupIds` the way it
already carries `modifierGroupIds`. Any channel serving a published menu
(storefront, Telegram bot, and — once ADR 0138 exists — a marketplace
projection) therefore renders a combo choice screen from the snapshot alone,
with no reach-back into authoring, which is ADR 0016's own invariant and this
record does not weaken it.

### Modifier depth: three named gaps, three targeted changes

**1. Nested variant-modifiers.** A modifier option with a `linked_variant_id`
is already a full variant, and that variant may already carry its own
`variant_modifier_groups`. The actual gap is that nothing resolves a second
level: today's cart and pricing pipeline treat a chosen option as final. This
record fixes exactly the one level `catalog.md` and the gap-map row both name
— no general recursion, matching this codebase's repeated refusal of
open-ended rule systems (ADR 0018 refused an open pricing-rule editor; ADR
0072 refused an open condition/action set for promo codes). `order_line_modifiers`
gains a self-referencing nullable `parent_order_line_modifier_id`: a normal
modifier selection has it null; a selection made *because* the customer
picked a linked-variant option one level up carries the parent selection's
id. `CatalogValidator`/`QuoteService` refuse to resolve a third level —
a `variant_modifier_groups` row reachable only through two nested
`linked_variant_id` hops is a new blocker, `MODIFIER_NESTING_DEPTH_EXCEEDED`,
so an author who tries to build a three-deep chain gets a publish-time error
instead of a system that silently stops one level short of what they built.

**2. Hidden modifiers auto-selected by order type.** `product_modifier_groups`
and `variant_modifier_groups` each gain two columns:

```text
visibility varchar(16) not null default 'VISIBLE'  -- VISIBLE | HIDDEN_AUTO_SELECT
applicable_fulfillment_modes text[] null  -- null = every mode; else a subset
                                           -- of {DELIVERY, PICKUP, DINE_IN},
                                           -- location_offerings' own vocabulary
```

At checkout, `QuoteService` auto-applies every `HIDDEN_AUTO_SELECT` group
attached to a line's variant/product whose `applicable_fulfillment_modes`
contains the order's fulfilment mode, selecting its option server-side with
no customer interaction and no storefront rendering of the group at all —
"a delivery box never reaches the receipt" (`catalog.md`) becomes "a delivery
box always reaches the receipt when the order is `DELIVERY`." Because there is
no customer gesture to resolve an ambiguous choice, a `HIDDEN_AUTO_SELECT`
group must be unambiguous by construction: `CatalogValidator` gains
`HIDDEN_MODIFIER_GROUP_AMBIGUOUS_DEFAULT`, a blocker when
`required <> true` or the group has more than one *active* option, since
"auto-selected" only means something when there is exactly one thing to
select.

**3. Modifier-level fallback to group values.** Today `product_modifier_groups`/
`variant_modifier_groups` carry no override columns at all — `(product_id|
variant_id, modifier_group_id, sort_order)` and nothing else — so there is
nothing to fall back *from*; every attachment always uses the shared group's
own `is_required`/`minimum_selections`/`maximum_selections` wholesale. This
record adds three nullable overrides to each attachment table:
`required_override`, `minimum_selections_override`,
`maximum_selections_override` — null means "use the group's own value" (the
fallback this row names), a set value means "this one product's use of the
shared group is stricter or looser than the group's own default." This does
**not** reopen `catalog.md`'s own stated design rule against editing a shared
group from one product's screen — *"editing one here would silently change
another product, and that is the mistake to design out"* — because the
override lives on the join row scoped to exactly one `(product_id|variant_id,
modifier_group_id)` pair, never on `modifier_groups` itself; a second product
attaching the same group is completely unaffected. The product/variant editor
(`catalog.md` 4.2 tab 3) needs an explicit second affordance — "override for
this product" — distinct from the existing read-only display of the group's
own values, so an author cannot mistake one for the other.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| One `order_lines` row per combo, with a JSONB `component_breakdown` column | Exactly the model ADR 0016 already rejected for catalog authoring, for the same reason and a sharper one here: a JSONB blob cannot carry a `source_variant_id` a fiscal classification can key to, so ADR 0038's per-line ИКПУ requirement cannot be satisfied from it. Unqueryable by reporting, too | Never |
| A parent `order_lines` row (the container, priced at the combo total) with child rows underneath | Every existing sum-over-`order_lines` reader — `ck_order_total_reconciles`, reporting's metric layer, the receipt renderer, `AggregatorOrderIntakeService`'s arithmetic check — double-counts the moment a parent row exists, and each must be taught to skip it. The flat-plus-grouping-key design gets correct sums for free from code that already exists | A reporting need arises that cannot be answered by grouping on `combo_selection_id` after the fact — not observed yet |
| Price the container variant once and apportion its price across components at checkout time (a "split the total" step) | Reintroduces exactly the ambiguity ADR 0038's per-line ИКПУ requirement exists to remove: an apportioned amount is derived, not authored, so a marketer changing a combo's price cannot state which component absorbed the change, and two components taxed at different VAT rates would need the apportionment to also carry tax-rate weighting nobody asked this feature to compute | A tenant needs the combo's *total* price to be the single authored fact with components auto-splitting proportionally to their standalone prices — a real but different product, not this one |
| Model a combo as a modifier group whose options are priced variants (reuse `modifier_groups`/`modifier_options` with `linked_variant_id`, skip new tables) | Closest existing shape, and tempting for that reason. Rejected because a modifier option's price is a flat `pricing.prices` row on the option itself (one price, not one price per (group, option) pairing), which cannot express "this drink is free in the family box and 3,000 som in the lunch box" without either a second option row per combo (defeating "reusable" modifier groups) or a new price-map concept anyway — arriving at this record's `COMBO_COMPONENT` type by a worse path | Never; the container/component split is the smaller change once the price map is admitted to be necessary either way |
| Nested variant-modifiers with unlimited recursion | Matches what the schema could technically support (a linked variant's modifier groups could themselves link variants, indefinitely) and open-ended in the same shape this codebase has repeatedly refused elsewhere (ADR 0018's rule engine, ADR 0072's condition set). An author-facing feature with no depth limit is untestable as a set and unrenderable predictably in a fixed-width cart UI | A real menu is found that needs a second level of nesting beyond what this record allows; widen the depth limit, do not remove it |
| Hidden modifiers keyed by a free-text tag an operator types (e.g. a `packaging` string flag on the group) | This is precisely what `catalog.md` names and refuses — *"Do not fake them with a free-text flag"* — because a typed tag cannot be validated, cannot drive `applicable_fulfillment_modes` deterministically, and silently diverges in spelling across brands | Never |

## Consequences

### Positive

- The per-variant price map reuses every mechanism ADR 0018 already built
  (price books, scope resolution, priority, the price simulator) rather than
  inventing combo-specific pricing, so `PricingEngine`, its context hash, and
  its test suite need only a fourth `priceable_type` branch, not a parallel
  code path.
- A combo order is, to every downstream reader that does not care about
  combos, indistinguishable from ordering the same items separately —
  `ck_order_total_reconciles`, reporting, the receipt renderer, and
  `AggregatorOrderIntakeService`'s external-total arithmetic check all keep
  working with zero code changes.
- Each combo component keeps its own `catalog.fiscal_classifications` row, so
  a combo is fiscalizable per ADR 0038's per-line requirement by construction,
  not by a special case bolted onto the fiscal document generator.
- Kitchen routing (ADR 0041) needs no new concept: a combo's components are
  ordinary ticket items that happen to share a grouping key for display, so a
  burger station and a drinks station each still see exactly their own item.

### Negative

- Authoring a combo is now a second screen with its own capacity rule (like
  modifier groups) plus a price to set per component per price book — more
  authoring surface than a single "combo price" field, and slower for a
  marketer building their first combo than the naive model would have been.
- The container variant is a real row in `catalog.variants` that is never
  priced and never sold on its own, which is a variant that breaks the
  otherwise-universal expectation ("every active variant needs an active
  price," `VARIANT_HAS_NO_ACTIVE_PRICE`) and needs its own carve-out in the
  validator, documented above — a reader of the validator's rules who misses
  the carve-out will misread a correctly-configured combo as broken.
- `order_lines.combo_selection_id`/`combo_container_variant_id` are nullable
  columns that mean nothing on the vast majority of rows (every non-combo
  line, forever), which is the ordinary cost of adding an optional feature to
  a shared table and is accepted rather than solved.
- The one-level nesting limit is a real product constraint, not just an
  implementation shortcut: a tenant that wants "choose a burger, then choose
  that burger's own sauce, then that sauce's own spice level" cannot get a
  third level from this authoring surface.

### Accepted trade-offs

- A combo's fiscal and reporting identity lives entirely in its components;
  the container variant is authoring/display metadata only and carries no
  price, tax, or classification of its own. A report that wants "how many
  Комбо №1 were sold" must count distinct `combo_selection_id`s grouped by
  `combo_container_variant_id`, not sum a line item that does not exist.
- `HIDDEN_AUTO_SELECT` modifiers can add a charge to a receipt with no
  customer-facing selection step. This is the feature working as designed
  (packaging charges are supposed to be silent), but it puts weight on the
  storefront/receipt copy to disclose the charge clearly, which this record
  does not itself specify (see Open inputs).

## Specification

### Capability placement

New writes need a capability under ADR 0025. `catalog.combo_groups`/
`combo_components` authoring and the two attachment-table override columns
are authored through the existing `CatalogAuthoringController` surface under
the existing `catalog.author` capability at `BRAND` scope — no new capability,
because a combo group is exactly as brand-scoped and exactly as
consequential as a modifier group, which already sits behind that capability.

### APIs (additive to `CatalogAuthoringController`)

```text
POST   /api/v1/control-plane/brands/{brandId}/combo-groups
PUT    /api/v1/control-plane/combo-groups/{comboGroupId}
POST   /api/v1/control-plane/combo-groups/{comboGroupId}/components
PUT    /api/v1/control-plane/combo-components/{componentId}
PUT    /api/v1/control-plane/combo-components/{componentId}/price
PUT    /api/v1/control-plane/products/{productId}/modifier-groups/{groupId}/overrides
```

Every mutation carries an `If-Match` expected version and an
`Idempotency-Key` on creation, matching every neighbouring `CatalogAuthoringController`
endpoint (ADR 0031).

### Validator additions (`CatalogValidator`)

```text
COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE     -- blocker, per component
COMBO_HAS_NO_PRICED_COMPONENTS          -- blocker, per combo group
COMBO_GROUP_MINIMUM_UNSATISFIABLE       -- blocker, the modifier-group analogue
HIDDEN_MODIFIER_GROUP_AMBIGUOUS_DEFAULT -- blocker, per hidden group
MODIFIER_NESTING_DEPTH_EXCEEDED         -- blocker, per over-nested option
```

### Events

No new Kafka topic. `CatalogDraftChanged` (ADR 0016, ADR 0032) already covers
any authoring write to the brand's catalog; combo and override writes are one
more source of that event, not a new contract.

## Rollout and rollback

No existing data to migrate — no combo or nested-modifier row exists anywhere
in the platform today. A tenant that authors no combo groups and no
`HIDDEN_AUTO_SELECT` modifiers sees no behaviour change: the new columns are
null, the new validator rules never fire, and `PricingEngine` never resolves
a `COMBO_COMPONENT` price. Rollback is refusing new writes to the new tables
and columns; nothing about an existing order, publication, or quote depends
on them existing.

## Implementation checklist

- [ ] `catalog.combo_groups`, `catalog.combo_components` (Flyway; brand of the
      wave that picks this up — no number reserved here).
- [ ] `pricing.prices.priceable_type` gains `COMBO_COMPONENT`; `pricing.prices`
      check constraint and `PricingVariantLookup`'s consumer-side port widened.
- [ ] `ordering.order_lines` gains `combo_selection_id`,
      `combo_container_variant_id` and the paired-nullability constraint.
- [ ] `catalog.product_modifier_groups` / `variant_modifier_groups` gain
      `visibility`, `applicable_fulfillment_modes`, `required_override`,
      `minimum_selections_override`, `maximum_selections_override`.
- [ ] `ordering.order_line_modifiers` gains `parent_order_line_modifier_id`.
- [ ] `CatalogValidator` rules listed in Specification.
- [ ] `PricingEngine` stage 2 resolves combo component selections and applies
      `HIDDEN_AUTO_SELECT` groups server-side.
- [ ] `catalog.publications`/`publication_items` carry a `COMBO_GROUP` entity
      kind; `PRODUCT` items carry `comboGroupIds`.
- [ ] `KitchenTicketService` groups ticket items by `combo_selection_id` for
      display without changing per-item routing.
- [ ] Operations authoring screens: combo-group editor, hidden-modifier and
      override affordances on `catalog.md` 4.2 tab 3, i18n.
- [ ] Domain, PostgreSQL, and API tests for every rule above, plus a
      Testcontainers proof that a combo order's `order_lines` sum reconciles
      under `ck_order_total_reconciles` with no changes to that constraint.

## Exit criteria

An author can build a combo of two or more components, price each component
independently per price book, and publish it; a customer can order it on any
channel serving the published menu and receive a receipt with one fiscal line
per component; a kitchen ticket routes each component to its own station
while displaying the combo as one grouped entry; an author can attach a
packaging modifier that appears on every `DELIVERY` order's receipt without
ever being shown to the customer at checkout; and an author can attach a
modifier option that is itself a variant carrying its own modifier groups, one
level deep, with a third level refused at publish time rather than silently
dropped.

## References

- ADR 0016: Brand catalog, publication, and location offerings
- ADR 0018: Deterministic pricing, promotions, taxes, and quotes
- ADR 0019: Cart, checkout, and order orchestration — `V0022`'s order model
  and `ck_order_total_reconciles`, the constraint this record's flat-line
  design keeps satisfying
- ADR 0038: Legal entities, fiscal receipts, and fiscal product classification
- ADR 0041: Kitchen execution, production routing, and kitchen release
- ADR 0072: Promo codes as a coupon-gated pricing input — the
  `ck_order_total_reconciles` history this record's flat-line design avoids
  repeating
- `platform/docs/operations-spec/catalog.md` §4.2, §4.4, "Data the backend
  does not have yet"
- `platform/docs/operations-gap-map.md` rows `4.2a`, `4.2b`
