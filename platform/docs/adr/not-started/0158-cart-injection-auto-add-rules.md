# ADR 0158: Cart injection: auto-add rules

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — nothing evaluates a rule that adds a line to
  a cart. There is no rule table, no evaluator, no origin on a cart or order line
  and no endpoint. What exists is the neighbourhood, and each neighbour stops short
  of the row: `frontend/operations` registers the route `/catalog/auto-add` in
  `app.routes.ts` as an honest not-built page ("no backend") and lists it in
  `catalog-shell.html`; `docs/operations-spec/orders.md` §5.5 promises that
  auto-added items "appear in the basket flagged добавлено автоматически and
  removable only where the rule allows", and the only code that prints that label
  is `order-detail-lines.html`, which prints it beside an ADR 0136 hidden charge
  (`autoSelectedCharges`), not beside a line. A hidden, order-type-scoped modifier
  group (`catalog.product_modifier_groups` / `variant_modifier_groups` with
  `visibility = 'HIDDEN_AUTO_SELECT'`, V0443-V0447; read by
  `pricing.infrastructure.catalog.JdbcCompositeProductsLookup#hiddenCharges`)
  puts a delivery box on the receipt of the products that carry it: a charge on a
  product, applied by the quote, which is not a cart line the customer can see or
  remove. ADR 0140's `FREE_ITEM` action prices a gift the cart already holds, and
  `AppliedPromotionService` offers the gift on the priced cart (`giftOffers`,
  `StorefrontOrderingController.GiftOfferResponse`) for the customer to add with
  one tap; pricing adds no line. ADR 0016 named
  `catalog.variant_packaging_requirements` (sold variant, packaging variant,
  numerator and denominator) and its body says it was never built (the "Not yet
  built" paragraph, 0016:299; its status line does not mention it); no migration
  creates it. The legacy side is on record and decided nowhere:
  `variants.package_id`, `package_volume` and `is_package` put synthetic package
  lines on a legacy order, kept out of the subtotal and rolled into
  `packaging_price` (`platform/docs/domains/legacy-profile-findings.md`,
  `legacy-schema-profile.md`). Every cart a customer or operator fills goes through
  `CartService` (`StorefrontOrderingController`, `CustomerBotOrderingAdapter`,
  `OperatorOrderingService`; a reorder is a plan the client turns into the same
  calls), so one place can see every basket; `AggregatorOrderIntakeService`
  deliberately does not use it, writing `JdbcAggregatorOrderStore` directly because
  an externally priced total is never run back through `QuoteService` (ADR 0040).
  `kitchen.stations` already has a `PACKING` role and a fallback
  station (V0030), so an injected line has somewhere to route. ADR 0137 shipped
  `catalog.variant_physical_attributes.portion_size` (V0448), which is the step a
  splittable dish is ordered in, not the number of servings one unit is worth.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0016, ADR 0017, ADR 0018, ADR 0019, ADR 0025, ADR 0027,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0036, ADR 0038, ADR 0039, ADR 0040,
  ADR 0041, ADR 0043, ADR 0047, ADR 0056, ADR 0057, ADR 0136, ADR 0137, ADR 0140,
  ADR 0169 (a mutual dependency: ADR 0169 depends back on this record for the
  `AUTO_ADD_LINE_MANDATORY` code; see the open input on amendments)
- Supersedes / Superseded by: — (amends ADR 0016 by replacing the never-built
  `catalog.variant_packaging_requirements` with the product-triggered rule kind
  below instead of a fourth model, and by proposing a default, in the last open
  input, for the legacy packaging disposition that ADR 0016's body leaves to the
  migration coverage register (its header lists no such input); amends ADR 0137 by
  adding the `portions_per_unit` fact that its sentence "portion-band becomes
  buildable the day `portion_size` exists" assumed `portion_size` already was;
  closes ADR 0140's open input on who adds a gift line, on the answer already
  shipped: ADR 0140 decided only that pricing never invents a line, and this record
  keeps that exactly, so a promotion's gift stays an offer (`giftOffers`) and an
  auto-add rule may inject any other line but is blocked from injecting a
  promotion's gift; reopens no rejected row of any of them)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written. The function in brackets is who supplies the fact; the owner
  accepts on its behalf, and no input names a person other than the owner. Each
  input says what it blocks.
  - **Whether a mandatory injected line is lawful and how it must be worded**
    (legal, product). A line the customer cannot remove is a charge the customer
    did not choose; ADR 0136 already carries the same question for a hidden
    modifier and left its copy as a neutral placeholder. Proposed default: the
    `mandatory` flag exists in the schema, and a mandatory rule is blocked at
    publication (`AUTO_ADD_MANDATORY_NOT_ALLOWED`) until the ADR 0030 key
    `catalog.auto_add_mandatory_allowed` (BOOLEAN, scope TENANT, code default
    `false`) is turned on. The key is declared without `tenantVisible()`, so the
    tenant configuration surface neither lists nor writes it (as built, a tenant
    administrator holds `tenant.configuration.write` and may write any key that is
    `tenantVisible()`), and it is written only through the platform control-plane
    configuration controller under `PLATFORM_ADMIN`, by a platform administrator on
    the owner's instruction. A mandatory rule, once allowed, needs customer text in
    the brand's default locale or publication is blocked. Blocks: only mandatory
    rules; every other rule is removable and unaffected.
  - **Whether an injected line counts toward a promotion's minimum, a gift
    trigger and the free-delivery threshold** (product, finance). Proposed default:
    it is an ordinary line for every money condition (a minimum order amount, the
    free-delivery threshold, a delivery-fee band), because making it special there
    means a second kind of line in the pricing engine; the delivery-fee threshold
    basis is ADR 0140's own open input and this record follows whichever answer it
    gets. For the quantity a promotion counts, `M` in ADR 0140's gift rule (the
    summed quantity of the lines the promotion's item conditions match, which drives
    `floor(M / triggerQuantity)`), an injected line counts only when the promotion
    has no item predicate; a promotion that names products, variants or categories
    never counts one, so a bag cannot tip a "three for two". The cart hands pricing
    each line's origin for this one purpose; pricing is still never told a rule.
    Blocks: nothing.
  - **Whether rules apply on an aggregator or POS channel** (product,
    operations). Proposed default: no, for one reason on both: this cart did not
    build the order's lines or fix its total. An aggregator order arrives with its
    lines and its total (ADR 0040, `AggregatorOrderIntakeService`), and a POS order
    is a ticket the POS keeps authoritative and keeps editing (ADR 0047, the row on
    mirroring the POS open ticket), so a line added by a rule would change a figure
    someone else already stated or is still changing. ADR 0140 lists POS among its
    promotion channel types because a discount can be computed over any priced
    basket; adding a line needs a basket this cart owns. The schema's channel set is
    the cart-built channel types only, so the answer cannot be changed by
    configuration, only by a record that amends this one. Blocks: nothing; no
    aggregator or POS order carries an injected line until such a record exists.
  - **Whether a confirmed order is re-evaluated when an operator amends it**
    (product, operations). Proposed default: no. An amendment is explicit, repriced
    and agreed (ADR 0039); a rule that fires after confirmation is a change nobody
    on the call agreed to. The amendment composer shows lines flagged "added
    automatically". How an operator removes or reduces one is decided by ADR 0169,
    not here, and no such removal exists until ADR 0169 is built
    (`AmendmentCommandType.REMOVE_LINES` is refused today), so until then an
    injected line on a confirmed order is as fixed as any other line. The two
    records depend on each other (ADR 0169 depends back on this one) and share the
    Problem Details code `AUTO_ADD_LINE_MANDATORY`: this record declares the code
    and the mandatory flag; ADR 0169 applies the refusal to a mandatory line on an
    amendment. Blocks: nothing here; removing an injected line from a confirmed order
    waits for ADR 0169.
  - **What one unit of a dish is worth in portions, and who authors it**
    (product, catalog authors). Proposed default: `portions_per_unit` is blank on
    every variant, a blank counts as zero, and a portion-band rule on a brand where
    no variant carries a value publishes with a warning and never fires. No value is
    guessed from weight, price or category. A portion means one serving of the item
    as the menu sells it. Blocks: only `PORTION_BAND` rules; `PLAIN` and
    `PRODUCT_TRIGGERED` rules are unaffected.
  - **Whether the offer mode ships with the first build** (product). Proposed
    default: the schema carries both modes; the first build ships `INJECT` only
    and `OFFER` follows as its own change, because it reuses the ADR 0140
    gift-offer response shape and needs both storefronts to draw it. Blocks: nothing
    in the first build; `OFFER` rules wait for their own change.
  - **Whether a line the customer removed is offered again if the basket changes
    later** (product). Proposed default: never, for the life of that cart. A rule
    that re-adds what the customer just took off is the failure the whole
    record is built to prevent. Blocks: nothing.
  - **How legacy packaging (`variants.package_id`, `package_volume`, `is_package`)
    is carried over** (product, migration). ADR 0016's body leaves the disposition
    to the migration coverage register and asks that retained packaging use "an
    explicit same-brand variant relationship with exact quantity semantics"; its
    header lists no such input, and nothing else decides it. Proposed default:
    retain it, as rules. Each legacy variant that has a `package_id` becomes one
    `PRODUCT_TRIGGERED` rule in `INJECT` mode, removable: the sold variant is its
    single trigger node, `trigger_threshold` is its `package_volume`, `quantity` is
    1 (the package variant is `add_variant_id`) and `repeat_per_multiple` is true.
    Historical `is_package` order lines import as ordinary order lines with
    `origin = 'AUTO_ADD'` and a null rule id, which `ordering.order_lines` allows
    because only `ordering.cart_lines` carries the origin-and-rule `CHECK`.
    Injected packaging counts toward the subtotal here; the legacy platform kept
    package lines out of the subtotal and rolled them into `packaging_price`, so
    a migrated total and a new one differ by that definition, and no migrated order
    is restated. The gap, named: a rule's step count is `floor(T / threshold)`, while
    the legacy count is `get_packages_count(quantity, package_volume)`, whose body
    the repository's schema profile does not record. If it rounds a partial group up,
    the rule adds one package fewer for a remainder; the import reads the legacy
    function before cutover and either accepts that difference or the owner amends
    this record with a rounding mode. Blocks: only the legacy import and the
    retained-packaging rules at catalog cutover; nothing in the build.

**To accept as written:** say "accept 0158". Every open input above is then closed
on its proposed default.

## Context

Row `4.9` of `platform/docs/operations-gap-map.md` is BLOCKED, severity 2, size XL:
*"Packaging, cutlery and gift items cannot be injected into a cart by a rule, so web,
bot and operator entry each get a different cart and staff add such lines by hand on
every order."* Its "Blocked by" text is the sentence this record answers: *"No ADR owns
cart injection: the IA and catalog.md both record it as unowned and needing a product
decision before design. The portion-band kind no longer waits on a portions attribute
(decimal portions exist since batch 17, `4.2c`); it waits on the same decision."*
`docs/frontend-information-architecture.md` §4.9 states the requirement: "Server-side
cart injection that applies identically to web, bot and operator entry", owning "the
three rule kinds — plain, product-triggered, and portion-band — with order types,
sources, offer-vs-silent, min/max quantity, and the removable-from-basket flag".
`docs/operations-spec/catalog.md` records the same item as "Unowned; closest ADR
0018 / ADR 0019. Needs a decision before design" and, in the Delever match table,
as "Match later". `docs/delever-parity-matrix.md` describes the three kinds the
competitor ships: a plain rule adds a configured product whenever the order matches
its context (order types, sources); a product-triggered rule fires when the cart
already holds trigger products at or above a threshold, adding a product with its own
minimum and maximum quantity and a removable flag; a portion-band rule sums the
portions across the whole order, not line count and not money, and matches the total
against bands that each name a product and a quantity.

**Three decisions are tangled in that one row, and they have different owners.**

1. *Where the rule runs.* The row's complaint is that three entry paths disagree. The
   cart (ADR 0019) is the one place all three share: `CartService` creates, edits and prices every
   cart the storefronts, the Telegram bot and the operator's New order screen build,
   and a reorder is a plan those same callers replay through it. `QuoteService` and the ADR 0140 evaluator are the wrong place:
   ADR 0018 makes a quote a price for a basket, ADR 0140 says "pricing must never
   invent a line", and a total that includes a line the customer was never shown is the
   defect ADR 0018 exists to prevent. A storefront is the wrong place for the reason
   the row gives.
2. *What a rule may look at.* A rule that can read the customer, the clock, the payment
   method or the subtotal is a promotion, and ADR 0140 already is the engine for those
   with a closed vocabulary, a simulator and an approval policy. An auto-add rule reads
   the order's context and its contents and nothing else.
3. *What the customer keeps.* The IA's flag is "removable from basket"; the task for
   this record adds the rest: the customer can always see an injected line and can
   always remove it unless the rule is explicitly mandatory, and a mandatory rule is a
   legal question before it is an engineering one.

**The portion count is a gap in a sentence another record wrote.** ADR 0137 says the
portion-band kind "reads `portion_size` from this record's table once it exists" and is
"buildable the day `portion_size` exists, with no further catalog change". As built,
`catalog.variant_physical_attributes.portion_size` (V0448) is documented as "the step a
customer may order this variant in (e.g. 0.5)" and is constrained to splittable
variants (`ck_physical_portion_needs_splittable`). It says how finely a quantity may be
cut, not how many servings a unit holds: a family pizza and a can of cola both have no
`portion_size` and are worth very different numbers of portions. The parity matrix
states the competitor's meaning plainly: portions are "a distinct decimal quantity
dimension on the product, separate from line quantity, weight and unit of measure".
Building the portion-band kind on `portion_size` would count a splittable plov and
nothing else.

**Four existing mechanisms already touch the same line, and a rule must not duplicate
any of them.**

| Mechanism | What it does | Why it is not auto-add |
|---|---|---|
| ADR 0136 hidden modifier (`HIDDEN_AUTO_SELECT`) | A charge on a product, scoped by fulfilment mode, applied by the quote and itemised on the priced cart and the order as an auto-selected modifier | It is not a cart line: the customer is never shown a line to remove, it scales with the product's own quantity, and it cannot say "one bag per order" or "one set per two portions" |
| ADR 0140 `FREE_ITEM` gift | Prices a line the cart holds as free, bounded per promotion; the storefront offers the line for one tap | Its condition is money, customer or time, and it deliberately does not add the line |
| ADR 0016 `variant_packaging_requirements` | A named relationship between a sold variant and a packaging variant with an exact ratio | Never built, and a fourth model for what the product-triggered kind already states |
| Staff adding the line by hand | Today's practice | The row |

## Decision

**Add a catalog-owned rule set that the cart evaluates, on the server, in the same
transaction as every cart edit, for every entry path that builds a cart. A rule injects
an ordinary, visible cart line with a recorded origin; the customer can always remove
it unless the rule is mandatory, and mandatory is off until the owner says otherwise.**

1. **Three rule kinds and one evaluator.** `PLAIN` adds a fixed quantity of one
   variant whenever the order's context matches. `PRODUCT_TRIGGERED` adds a fixed
   quantity of one variant per step of trigger units in the basket (one step, or one per
   multiple, as the rule says). `PORTION_BAND` totals the portions in the basket and
   adds the variant and quantity of the band that contains the total. All three are
   evaluated by one pure function, `AutoAddEvaluator`, in `ordering.domain`, over a
   `BasketFacts` value and a `PublishedAutoAddRule` value; it has no Spring, no SQL and
   no clock, so the console's simulator and the cart run the same code.
2. **What a rule may condition on is closed.** Order type (`DELIVERY`, `PICKUP`,
   `DINE_IN`), source (the ADR 0036 sales-channel system type, restricted to the
   cart-built ones: `WEB`, `IOS`, `ANDROID`, `TELEGRAM`, `KIOSK`, `QR_TABLE`,
   `CALL_CENTRE`), optionally a set of locations, and the basket's contents. A rule
   cannot read the customer, the time, the payment method, a coupon or a money
   amount; those are ADR 0140's. Chaining is impossible by construction: injected
   lines never count as trigger units or portions, so a rule cannot fire another rule.
3. **The rule set is authored in `catalog`, published with the menu, and read by the
   cart from the publication.** The cart enforces what the customer was shown, as
   `CartMenuRules` already does for modifier groups: a rule edited and not
   republished changes nothing for a customer. The published item is immutable and
   carries the rule's version, so a line can name the exact rule that put it there.
4. **An injected line is a cart line with a provenance, not a new kind of thing.** It
   is written by the cart through the same `upsertLine` a customer's edit uses, under
   the cart's version compare-and-set, with `origin = 'AUTO_ADD'`, the rule id and the
   rule version. It is priced, reserved, routed, fiscalized and reported as any line
   is. Routing sends it to the `PACKING` station or the location's fallback
   (ADR 0041). Pricing is told each line's origin (Open input 2), never a rule, and
   adds no line.
5. **The customer can always see and remove an injected line, unless the rule is
   mandatory.** Every cart read says, per injected line, which rule put it there, the
   customer text of that rule, whether it can be removed, and the bounds on its
   quantity. A removal is honoured and remembered for the life of the cart: the rule
   is never evaluated into that cart again. A customer may raise the quantity up to the
   rule's maximum and lower it to zero when the rule is removable; a later
   re-evaluation never overwrites a quantity the customer set, except to enforce the
   rule's maximum or, for a mandatory rule, to raise the line back to the computed
   quantity when the basket has grown. A mandatory line is shown with its text and
   cannot be removed or reduced below the computed quantity. There is no hidden
   injected line.
6. **A rule that cannot be honoured is skipped, never made into a refusal.** The
   reconciler runs the cart's own checks on the injected variant: on the menu at that
   location, `requireOnSaleNow`, `requireAvailable` (the stop-list and stock check of
   ADR 0017, the same two `putLine` and `price()` run on a customer's line) and
   priced. If one fails, the rule does not fire for that cart, the skip is counted,
   and the basket proceeds. A race does not turn a skip into a refusal: `price()`
   reconciles before it checks the customer's lines, so an injected line whose stock
   ran out since the last edit is dropped and the cart re-priced (the refusal that
   names a line applies only to lines the customer chose), and a reservation that
   fails for an injected line at checkout (ADR 0019) drops the line and re-prices
   with `changedByPricing`. The customer sees a changed basket through the existing
   `PRICE_CHANGED` re-quote, not a refusal that names a line they never chose. A
   stock-tracked packaging variant can therefore run out under a basket, and the
   order still goes through.
7. **Hidden modifiers and gifts keep their roles and are kept from colliding.** A
   per-product packaging charge stays a hidden modifier. A per-order or per-portion
   packaging line is an auto-add rule. The catalog validator blocks publication when a
   rule would add a variant that a hidden modifier on a product in the same modes already
   charges, and blocks an `INJECT` rule whose variant is the gift of an active
   `FREE_ITEM` promotion: a rule cannot read the money the promotion's condition
   reads, so an injected gift would be charged in full whenever the promotion does
   not fire. An `OFFER` rule for such a variant only warns, because the customer
   taps. The gift offer treats any line the cart already holds, injected or not, as
   held.
8. **`OFFER` mode suggests instead of injecting.** An offer rule writes nothing to the
   cart; it is returned on the priced cart in the shape ADR 0140 uses for a gift offer,
   the customer taps, and the line is written through the ordinary cart call with
   `origin = 'OFFER_ACCEPTED'`.
9. **Portions are a catalog fact with an honest default.** Add
   `portions_per_unit numeric(6,3)` to `catalog.variant_physical_attributes`, published
   in the same physical block; blank means "not counted". The basket's portions are the
   sum of each sold line's quantity times its variant's value, with combo components
   counted as the component lines they are.
10. **The rollout gate is a policy, not a deploy.** `ordering.auto_add_enabled`
    (BOOLEAN, scope BRAND, code default `false`, ADR 0030) turns evaluation on. With it
    off the cart neither adds nor removes anything. A shadow mode counts what would
    have fired so the owner can see it before turning it on.
11. **An amendment never re-evaluates.** Lines already on an order stay as they are;
    an operator adds or removes them explicitly, and a removal follows ADR 0169 once
    that record is built (it depends back on this one and shares the
    `AUTO_ADD_LINE_MANDATORY` code); until then no removal exists.
12. **Aggregator and POS orders are out of scope by schema.** The rule's channel set
    cannot name them.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Inject inside the quote (`QuoteService`) | ADR 0018 makes a quote a price for the basket the customer sees; ADR 0140 says pricing never invents a line. A quote that adds a line makes a total the customer cannot reconcile to their screen | ADR 0018 is amended so a quote may carry lines that the cart then displays and the customer can remove |
| Each client evaluates the rules | Three entry paths, three carts: the row's own complaint. Every client would have to be changed for every new rule kind | A client must fill a basket offline, and ships rule logic with a server-checked parity test (the same fixtures through its evaluator and `AutoAddEvaluator` on every build) |
| Hidden modifiers only (ADR 0136) | Already built and right for "this dish comes in a box". It scales with the dish, cannot express one-per-order or per-portion, and is deliberately not a line the customer can see or remove | Per-order and per-portion packaging prove unneeded; then drop `PLAIN` and `PORTION_BAND` |
| Build `catalog.variant_packaging_requirements` as ADR 0016 sketched | A fourth model for what `PRODUCT_TRIGGERED` with a repeating step states, with its own authoring screen and its own cart code. Its numerator and denominator are the rule's step and quantity | A catalog author asks to state packaging on the product page, in which case that page writes a product-triggered rule |
| Express injection as ADR 0140 promotions (`FREE_ITEM` made automatic) | A promotion's conditions are money, customer and time; a gift is free by definition. Packaging is neither. ADR 0140 decided only that pricing never invents a line and left open who adds a gift line; this record answers that for the cart on the shipped behaviour (the storefront offers a promotion's gift, and a rule may inject any other line, never a promotion's gift) | The owner decides a promotion's gift should be added without a tap; ADR 0140's open input is then reopened and a successor amends the gift blocker here |
| Count portions from `portion_size` | It is the order step of a splittable dish; most dishes carry none. A band on it counts plov and nothing else | Never as the sole source; a derived default may be added if a tenant wants it |
| Read rules live, not from the publication | A customer could be shown a basket the rules then change under them, and "what the cart enforces equals what was published" is the rule `CartMenuRules` already keeps | An urgent rule withdrawal proves too slow through a republish; add a live kill switch for rule status only |
| Let an operator waive a mandatory line | A per-order exception is an unaudited discount and a hole in the rule | Operations shows a real waiver pattern; then an ADR 0027 approval with a reason, as ADR 0039 does for decreases |
| Re-evaluate on amendment | The customer agreed to a price; a rule firing after confirmation changes it without a call | Operators report forgotten packaging on amended orders at a rate staff training does not fix |
| Scripted or free-form rules | `docs/operations-spec/couriers.md` rejects scripting for every rule engine here: it cannot be simulated, validated or explained | The platform adopts a script language for any rule engine, with a simulator, a validator and an explanation view for it; `couriers.md` is amended first |

## Consequences

### Positive

- One basket, whichever door it came through: the same fixture through the storefront,
  the bot and the operator composer injects the same lines.
- Staff stop adding packaging by hand, and the order says which lines were added by a
  rule, which the reports and the receipt reader can use.
- The customer sees every injected line and can take it off; nothing is hidden, and the
  one case where they cannot is behind a legal switch that is off.
- The three kinds share one evaluator that the simulator also runs, so what an author
  previews is what the cart does.
- A rule edit is a publication like any other menu change: versioned, validated, roll-backable.

### Negative

- The cart now writes lines the customer did not type. Every cart edit gains a
  reconciliation step, and a bug in it is a bug in every basket.
- A new column on cart and order lines, a new publication item type and a new
  `portions_per_unit` fact are added to tables other modules read; the reporting fact,
  the console's order and basket views and the cart's pricing port must each learn the
  origin.
- A rule change reaches customers only on republish. Pausing a rule fast means
  stopping the injected variant (the stop-list) or switching `ordering.auto_add_enabled`.
- A portion-band rule is only as good as its authors' portion figures, and none exist
  until someone types them.
- The validator gains cross-module checks (hidden modifiers, promotion gifts) that are
  reads of other modules' tables through ports, and each is another thing to keep true.

### Accepted trade-offs

- An injected line counts toward the money thresholds (a minimum order amount, the
  free-delivery threshold), so a bag can tip an order over a free-delivery line. That is
  simple and unsurprising to the engine and mildly surprising to a customer; ADR 0140's
  open input on the threshold basis governs it. It does not count toward an item-targeted
  promotion's matched quantity (Open input 2).
- A `FREE_ITEM` promotion activated after an `INJECT` rule that adds its gift variant is
  not caught until the next catalog publication; until then the injected line is charged
  in full whenever the promotion does not fire.
- A stock-tracked packaging line can drop out between pricing and checkout; the customer
  sees a re-quote, not a refusal.
- An order that grows through amendment does not gain more packaging. An operator adds it.
- A removed line is never re-offered in that cart, so a customer who removes the bag, then
  doubles the order, gets no bag. Predictability is chosen over completeness.
- No rule reaches an aggregator or POS order; their packaging is theirs.
- Mandatory is off at launch. A tenant that wants unremovable packaging waits for the
  owner's switch, not for engineering.

## Specification

### Physical model

All catalog tables carry `tenant_id` and `brand_id` and reference the brand's own
variants with composite keys that include both, as every ADR 0016 table does.

```text
catalog.auto_add_rules
  id uuid pk, tenant_id, brand_id
  code varchar(64) not null              -- stable, operator-chosen; unique per brand
  kind varchar(20)                       -- PLAIN | PRODUCT_TRIGGERED | PORTION_BAND
  mode varchar(8)  default 'INJECT'      -- INJECT | OFFER
  status varchar(16) default 'DRAFT'     -- DRAFT | ACTIVE | ARCHIVED
  add_variant_id uuid null               -- PLAIN, PRODUCT_TRIGGERED; null for PORTION_BAND
  quantity numeric(10,3) null            -- PLAIN: the quantity; PRODUCT_TRIGGERED: per step
  trigger_threshold numeric(10,3) null   -- PRODUCT_TRIGGERED: units that make one step
  repeat_per_multiple boolean not null default false
  minimum_quantity numeric(10,3) not null default 1
  maximum_quantity numeric(10,3) not null default 20
  mandatory boolean not null default false
  fulfillment_modes varchar(16)[] not null      -- non-empty subset of DELIVERY, PICKUP, DINE_IN
  channel_system_types varchar(16)[] not null   -- non-empty subset of the cart-built types
  sort_order integer not null default 0         -- display only; no evaluation tie-break exists
  version integer, created_at, updated_at timestamptz
  unique (tenant_id, brand_id, code)
  check kind-specific columns present or null as above
  check mandatory => mode = 'INJECT'
  check minimum_quantity > 0 and maximum_quantity >= minimum_quantity
  check channel_system_types <@ ARRAY['WEB','IOS','ANDROID','TELEGRAM','KIOSK',
        'QR_TABLE','CALL_CENTRE']     -- AGGREGATOR and POS cannot be stored

catalog.auto_add_rule_triggers          -- PRODUCT_TRIGGERED only; exactly one node each
  rule_id, tenant_id, brand_id, variant_id | product_id | category_id
  check exactly one of the three is not null (the kitchen routing-rule pattern, V0030)

catalog.auto_add_rule_bands             -- PORTION_BAND only
  id, rule_id, tenant_id, brand_id
  portions_from numeric(10,3) not null  -- inclusive
  portions_to numeric(10,3) null        -- exclusive; null = no upper bound
  add_variant_id uuid not null, quantity numeric(10,3) not null
  exclude using gist (rule_id with =, numrange(portions_from, portions_to) with &&)
     -- bands of one rule cannot overlap (btree_gist exists since V0025)

catalog.auto_add_rule_locations         -- optional; no rows means every location
  rule_id, tenant_id, brand_id, location_id
```

`catalog.translations` widens its `ck_translation_entity_type` list with
`AUTO_ADD_RULE` (the move V0443 made for `COMBO_GROUP`); a rule's customer text, shown
beside the injected line, lives there. `catalog.variant_physical_attributes` gains
`portions_per_unit numeric(6,3)` with `CHECK (portions_per_unit IS NULL OR
portions_per_unit >= 0)`; a variant with no physical row counts as zero.
`catalog.publication_items` needs no change: its `entity_type` is a free
`varchar(32)`; the new type is `AUTO_ADD_RULE`, and the physical block of a `VARIANT`
item gains `portionsPerUnit`.

Cart and order:

```text
ordering.cart_lines  (+)   origin varchar(16) not null default 'CUSTOMER'
                               -- CUSTOMER | AUTO_ADD | OFFER_ACCEPTED
                           auto_add_rule_id uuid null, auto_add_rule_version integer null
                           customer_adjusted boolean not null default false
                           check (origin = 'CUSTOMER') = (auto_add_rule_id is null)
ordering.order_lines (+)   origin, auto_add_rule_id, auto_add_rule_version, auto_add_mandatory
ordering.cart_auto_add_declines
  cart_id, tenant_id, rule_id, declined_at          pk (cart_id, rule_id)
  foreign key (cart_id, tenant_id) -> ordering.carts (id, tenant_id) on delete cascade
```

An injected line's `line_key` is `aa:` plus the rule id (39 characters, inside the
64 that `cart_lines.line_key` allows); `CartService.requireLineKeyShape` refuses a
customer-supplied key that begins `aa:`. Every new table ends its migration with an
explicit `GRANT ... TO horecaos_application` (the hygiene check requires it);
`cart_auto_add_declines` gets `SELECT, INSERT, DELETE`; `catalog.auto_add_rules` gets
`SELECT, INSERT, UPDATE` and no `DELETE`, because a rule is archived, not deleted, so
a published snapshot can always be traced to a row. Its child tables
`catalog.auto_add_rule_triggers`, `catalog.auto_add_rule_bands` and
`catalog.auto_add_rule_locations` get `SELECT, INSERT, UPDATE, DELETE`: they carry no
status, archive or validity column and a published snapshot already holds its own
immutable copy of them (Decision 3), so a child row needs no tombstone, and without
`DELETE` a `PUT` could never remove a trigger node, a band or a location. The `PUT` on a
rule replaces the three child sets inside the same transaction as the rule's version
bump and the `.updated` audit fact (ADR 0027). Row-level security follows ADR 0056 as the
`catalog` and `ordering` schemas reach the backstop; every new table carries `tenant_id`
and a tenant-bearing foreign key now so that arrival is a policy and not a migration. The next free migration number is taken at implementation time from every
active worktree, as AGENTS.md says.

### Evaluation

```text
AutoAddEvaluator.evaluate(rules, basket, context) -> [Injection | Offer | Skipped]

BasketFacts   : per sold line (combo components expanded, injected lines excluded):
                variantId, productId, categoryIds, units, portions = units * portionsPerUnit
Context       : fulfillmentMode, channelSystemType, locationId
```

For each published rule whose modes, channel types and locations contain the context:

- `PLAIN`: raw = `quantity`.
- `PRODUCT_TRIGGERED`: T = units of lines matching any trigger node. If
  `repeat_per_multiple` then steps = `floor(T / trigger_threshold)` else
  steps = `1` when `T >= trigger_threshold` else `0`; raw = `quantity * steps`.
- `PORTION_BAND`: P = sum of portions; the band with `portions_from <= P < portions_to`
  supplies the variant and raw = its `quantity`; no band means no injection.

A raw quantity of zero means the rule does not fire. Otherwise the quantity is
`clamp(raw, minimum_quantity, maximum_quantity)`. A rule the customer declined in this
cart, or whose variant fails the checks the cart runs on a customer's line (on the menu
at the location, `requireOnSaleNow`, `requireAvailable` per ADR 0017, priced), yields
`Skipped(reason)`. Those checks are the cart's own, not a second copy: on an injected
line a failure is a `Skipped` and never a `CartRefusedException`. The evaluator stays
pure; the reconciler runs the checks and hands it the result.

`AutoAddReconciler` (`ordering.application`) applies the result to the cart: it
inserts a missing injected line; changes the quantity or variant of one the customer has
not adjusted; removes one whose rule no longer fires; leaves a customer-adjusted line
alone unless it now exceeds the maximum or, for a mandatory rule, has fallen below the
computed quantity, in which case it is lowered to the maximum or raised to the computed
quantity; and does nothing at all when `ordering.auto_add_enabled` is false. It is called from `putLine`, `removeLine`,
`setDestination`, `rebuildAtLocation` and, as a verification, from `price()`. In
`price()` the reconciler runs before the checks over the customer's lines, so an
injected line whose stock ran out is dropped rather than refused; a difference advances
the cart version and the priced response carries the new version and a
`changedByPricing` flag, so the client redraws instead of paying a total for a basket it
has not seen. A reservation that fails for an injected line at checkout (ADR 0019) is
handled the same way: the line is dropped, the cart re-priced, and the customer meets
the existing `PRICE_CHANGED` re-quote, never a refusal that names the packaging. A removal of an injected line by the customer writes the
decline row and then reconciles.

Rules are read through a port `CartAutoAddRules` in `ordering.application`, implemented
by `JdbcCartAutoAddRules` in `ordering.infrastructure.catalog` from the live publication
of the cart's channel, as `JdbcCartMenuRules` reads modifier rules. Which publication
carries which rule: each channel's publication contains the rules whose
`channel_system_types` include that channel's system type.

### APIs (ADR 0031)

```text
GET  /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog/auto-add-rules
POST /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog/auto-add-rules          Idempotency-Key
GET  .../auto-add-rules/{ruleId}
PUT  .../auto-add-rules/{ruleId}                                                                 If-Match version
POST .../auto-add-rules/{ruleId}/archive                                                         Idempotency-Key, If-Match
POST .../auto-add-rules/simulations     non-mutating POST; body is a hypothetical basket
```

Capabilities, per ADR 0025, are the codes that already exist: `catalog.read` (BRAND)
for the reads and the simulation, `catalog.author` for create, update and archive,
`catalog.publish` for the republish that makes a rule live. No new capability. The
OpenAPI document group is the control-plane surface (ADR 0057), under the existing
catalog path prefix.

Storefront and operator cart reads gain, per line, `origin` and
`autoAdd { ruleCode, customerText, mandatory, removable, minimumQuantity,
maximumQuantity }`, and on the priced cart `autoAddOffers[]` in the shape of ADR 0140's
gift offers. Removing an injected line is the ordinary cart line `DELETE`; removing a
mandatory one answers a Problem Details `409` with the stable code
`AUTO_ADD_LINE_MANDATORY`; changing its quantity outside the bounds answers
`AUTO_ADD_QUANTITY_OUT_OF_BOUNDS`. The storefront image carries no rule logic.

### Validator findings (`CatalogValidator`)

Errors block publication: `AUTO_ADD_TRIGGER_MISSING` (a product-triggered rule with no
live trigger node, that is, none whose product, variant or category is not `ARCHIVED`), `AUTO_ADD_VARIANT_NOT_SELLABLE` (the injected variant is not active or
has no price in scope), `AUTO_ADD_RULES_OVERLAP` (two active rules add the same variant
in overlapping modes, channel types and locations), `AUTO_ADD_DUPLICATES_HIDDEN_PACKAGING`
(the injected variant is the linked variant of a hidden modifier option attached to a
product or variant in overlapping fulfilment modes), `AUTO_ADD_MANDATORY_NOT_ALLOWED`
(mandatory while `catalog.auto_add_mandatory_allowed` is off) and
`AUTO_ADD_MANDATORY_UNDISCLOSED` (mandatory with no customer text in the brand default
locale) and `AUTO_ADD_VARIANT_IS_PROMOTION_GIFT` (an `INJECT` rule whose variant is in
an active `FREE_ITEM` promotion; the same finding is a warning for an `OFFER` rule).
Warnings do not block: `AUTO_ADD_PORTION_BAND_COUNTS_NOTHING` (no variant of the brand
carries `portions_per_unit > 0`) and `AUTO_ADD_BAND_GAP`. Fiscal classification of an
injected variant is already reported by `FISCAL_CLASSIFICATION_MISSING` for every variant
offered (ADR 0038), so it needs no new finding.

### Events, audit, PII, policy

- **Events (ADR 0032).** None added. A rule reaches a customer through a publication, whose
  events are ADR 0016's; `OrderReceived` carries `lineCount` and no line payload, so it is
  unchanged. Order-line origin travels in the order read and in the ADR 0043
  `reporting.fact_order_line`, which gains `line_origin` and `auto_add_rule_id` as
  additive columns.
- **Audit (ADR 0027).** `catalog.autoAddRule.created`, `.updated`, `.activated` and
  `.archived`, written in the same transaction as the change, with the rule id, kind, mode,
  the before and after versions and no customer data. An injection is not an audited fact: it
  is a derived cart line; the order line's recorded origin and rule version are its evidence.
- **PII (ADR 0029).** A rule holds no personal data. A decline row holds a cart id, which
  carries none. The cart retention sweeper deletes it with the cart.
- **Policy (ADR 0030).** `ordering.auto_add_enabled` (BOOLEAN, BRAND, default `false`) and
  `catalog.auto_add_mandatory_allowed` (BOOLEAN, TENANT, default `false`, declared without
  `tenantVisible()` and written only under `PLATFORM_ADMIN`; see the first open input), each
  declared once in the module's configuration keys and mirrored in the tenancy registry the way
  `OrderingConfigurationKeys` documents. A decision that used a rule persists the rule id
  and version on the line, which is the ADR 0030 requirement for a durable decision.
- **Observability.** `horecaos.autoadd.rules{kind,outcome}` with outcome `fired`, `skipped`,
  `declined`, `shadow`, and `horecaos.autoadd.skipped{reason}`; no identifier or name in a
  label. An alert when the skip rate for one rule exceeds a threshold, because a rule that
  never fires and a rule that is always skipped look the same from outside.
- **Providers (ADR 0026/0007).** None. No provider adapter is involved.

### Front-end contract

`/catalog/auto-add` becomes the authoring screen: the shared rule list with priority
display order, a condition builder limited to the closed vocabulary above, and the
simulator the IA names, which posts a hypothetical basket to the simulation endpoint and
draws the injected lines and the skips. The storefronts and the New order composer draw
an injected line with its customer text and a remove control driven by `removable`, never
by their own knowledge of rules; the console's order detail prints "added automatically"
beside a line with `origin = 'AUTO_ADD'` as it already does beside a hidden charge.
Strings in ru, uz-Latn and en.

### Testing

- `AutoAddEvaluatorTests`: each kind, the boundaries of a band, a step at the threshold
  and one unit below it, `repeat_per_multiple`, clamping, a zero raw quantity, combos
  counted as components, and the proof that an injected line never triggers a rule.
- One cart fixture driven through the storefront controller, the bot adapter and
  `OperatorOrderingService`, asserting the same injected lines (the row's own claim).
- Removal sticks: remove, edit the basket three ways, the line does not return.
  Mandatory: the removal is a `409`; a customer-adjusted quantity above the computed one
  survives a re-evaluation, and one that the basket's growth left below it is raised.
- Skips: stopped variant, outside sale window, unpriced variant, none of which refuses the cart.
- Races: an injected variant runs out between the last edit and `price()`, and its
  reservation fails at checkout; each drops the line, re-prices with `changedByPricing`
  and refuses nothing for the packaging.
- A `PUT` that removes a trigger node, a band and a location leaves exactly the new sets,
  with the version bump and the `.updated` audit fact in one transaction.
- The legacy import: a variant with `package_id` and `package_volume` becomes one
  product-triggered rule, and an `is_package` line imports with `origin = 'AUTO_ADD'` and
  a null rule id.
- `price()` after a republish changes the injected set and advances the version.
- Validator tests for every finding, including the hidden-modifier collision and the
  promotion-gift blocker for `INJECT` (and its warning for `OFFER`).
- An aggregator-intake order carries no injected line, and the schema rejects `AGGREGATOR`.
- Migration tests: the exclusion constraint, the channel `CHECK`, the line-origin `CHECK`.

## Rollout and rollback

Build the schema, the evaluator, the publication item and the authoring API behind
`ordering.auto_add_enabled = false`, so nothing changes for any cart. Turn on shadow
counting for one pilot brand and read the numbers against what staff add by hand. Enable
`INJECT` for that brand, then the others. `OFFER` ships later and `mandatory` stays off until
a platform administrator turns the tenant key on the owner's instruction. Rollback is the
key: set it false and the cart stops adding and removing; lines already injected are on
carts and orders as ordinary lines and the customer or operator removes them by the usual
means (on a confirmed order, as ADR 0169 provides). The authoring tables and the published
items stay; no rule row is deleted, and a `PUT` replaces only a rule's trigger, band and
location rows, which every published item already copies.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Migration: rule, trigger, band and location tables; `portions_per_unit`; the
      translation entity type; cart and order line origin; decline table; explicit grants.
- [ ] `AutoAddEvaluator`, `BasketFacts`, `PublishedAutoAddRule` in `ordering.domain`, pure.
- [ ] `CartAutoAddRules` port and `JdbcCartAutoAddRules`; `AutoAddReconciler`; wiring in
      `putLine`, `removeLine`, `setDestination`, `rebuildAtLocation` and `price()`; the
      reserved `aa:` key prefix.
- [ ] Catalog authoring service and controller; audit facts; publication item; validator findings.
- [ ] Configuration keys and their mirrored registry declarations.
- [ ] Storefront and operator cart responses; `409` codes; order line copy at checkout;
      reporting fact columns.
- [ ] Console screen with simulator; storefront and composer line rendering; i18n.
- [ ] Legacy packaging import: one rule per legacy variant with a `package_id`, `is_package`
      lines as `AUTO_ADD` order lines, and the rounding check against the legacy function.
- [ ] Metrics and the shadow counter.
- [ ] Tests listed above; `ModularArchitectureTests` green.
- [ ] ADR 0016 and ADR 0137 status lines updated to point here; gap-map row `4.9` re-audited.

## Exit criteria

An operator takes a three-portion delivery order at the call centre and sees one line of
cutlery already in the basket, flagged "added automatically", with a remove control; the
same basket through the web storefront and the Telegram bot shows the same line. The customer
removes it and it does not come back. Publishing two overlapping rules is refused by name. An
aggregator order carries none, and the order detail and the reporting fact say which lines a
rule added. Switching `ordering.auto_add_enabled` off stops all of it without a deploy.

## References

- ADR 0016 (publication, `variant_packaging_requirements`), ADR 0017 (availability of the
  injected variant), ADR 0018 (quotes), ADR 0019 (cart and checkout), ADR 0025, ADR 0027,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0036 (sales-channel system types), ADR 0038
  (classification of an injected variant), ADR 0039 (amendment), ADR 0040 (aggregator
  intake), ADR 0041 (station routing), ADR 0043, ADR 0047 (the POS ticket stays
  authoritative), ADR 0056, ADR 0057 (control-plane OpenAPI group), ADR 0136 (hidden
  modifiers, combos), ADR 0137 (`portion_size`), ADR 0140 (`FREE_ITEM`, gift offers),
  ADR 0169 (removal after confirmation; depends back on this record)
- `platform/docs/domains/legacy-profile-findings.md` and `legacy-schema-profile.md` (legacy
  `package_id`, `package_volume`, `is_package`, `packaging_price`)
- `platform/docs/operations-gap-map.md` row `4.9` and the open-decision table that lists it
- `platform/docs/frontend-information-architecture.md` §4.9; `platform/docs/operations-spec/catalog.md`
  (Delever match table, "Data the backend does not have yet"); `platform/docs/operations-spec/orders.md` §5.5;
  `platform/docs/delever-parity-matrix.md` (auto-add types 1 to 3, the portions question)
- `CartService`, `CartMenuRules`, `JdbcCartMenuRules`, `StorefrontOrderingController`,
  `CustomerBotOrderingAdapter`, `OperatorOrderingService`,
  `ReorderPlanService`, `AggregatorOrderIntakeService`, `JdbcCompositeProductsLookup`, `AppliedPromotionService`,
  `CatalogValidator`; `V0016`, `V0020`, `V0022`, `V0030`, `V0396`, `V0443`, `V0446`, `V0448`, `V0449`
- `frontend/operations/src/app/app.routes.ts` (`auto-add`), `features/catalog/catalog-shell.html`,
  `features/orders/order-detail-lines.html`
