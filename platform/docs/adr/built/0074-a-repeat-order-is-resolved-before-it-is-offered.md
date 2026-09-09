# ADR 0074: A repeat order is resolved against the live menu before the button is offered

- Decision status: Accepted
- Implementation status: Built — `ReorderPlanService` resolves a repeat
  against the live menu (`GET .../orders/{orderId}/reorder`) and every
  customer-facing channel now offers it from that plan: both first-party
  storefronts (`frontend/storefront-milliy`'s original wiring, and
  `frontend/storefront`'s `order-detail.component.ts`, whose `canRepeat`/
  `repeat()` gate on the plan's verdict), and ADR 0075's Telegram customer
  bot, whose `CustomerBotOrderingAdapter` calls the same
  `ReorderPlanService` behind its own `TELEGRAM_CUSTOMER_INLINE_ACTIONS_ENABLED`
  entitlement gate. Not built, out of this record's own scope:
  `StorefrontCatalogQuery.menuFor` does not yet consult the 86 list for
  ordinary menu browsing — a separate future change, not a repeat-order gap.
- Date proposed: 2026-09-06
- Date decided: 2026-09-06
- Deciders: Ayubkhon Abbosov (platform owner)
- Depends on: ADR 0016 (published menu), ADR 0018 (deterministic pricing),
  ADR 0019 (cart and checkout), ADR 0031 (HTTP conventions), ADR 0036 (channel
  supplies publication and price plane), ADR 0039 (order revisions),
  ADR 0070 (a storefront is a client of a published contract)
- Supersedes / Superseded by: —
- Open inputs:
  - Whether a `PARTIAL` plan offers the button with a count ("3 of 4 available")
    or hides it as `UNAVAILABLE` does — owner (Ayubkhon Abbosov). Decided for
    now as **hide**, per the instruction that gave rise to this record; the
    contract carries all three verdicts so the policy can move without a
    server change.
  - Whether the Telegram bot repeats in one round trip (a write endpoint that
    builds the cart server-side) or replays the plan line by line — owner,
    to be closed by the bot inline-actions ADR.

## Context

`frontend/storefront-milliy` shipped a "repeat order" button that matched each
historical line's **product name** against the current menu and added the first
orderable variant it found. That was the honest thing to build against the
contract as published, and it is wrong in three ways a customer can feel: a
renamed dish silently fails to match, a dish sold under two variants gets
whichever one is listed first, and the original modifiers are lost outright.

The cause was believed to be missing data. It is not. `ordering.order_lines`
has carried `source_variant_id NOT NULL` and `source_product_id` since V0022,
and `ordering.order_line_modifiers` has carried `source_option_id NOT NULL`
beside it. Neither has a foreign key into `catalog` — deliberately, because a
publication is a copy and catalog rows are archived and republished underneath
it. `ix_orders_customer (tenant_id, customer_account_id, created_at DESC)`
already makes "this customer's last order" a single index seek.

So the snapshot exists, holds the ids, and is unconstrained by catalog. What is
missing sits one layer up: `StorefrontOrderingController.OrderLineResponse`
drops those ids on the way to the wire, and **nothing anywhere resolves a
historical line against the menu as it stands now**. A client cannot tell
whether repeating an order would work, so it either offers a button that fails
at pricing or guesses by name.

Resolution is not a client's job to do. It needs the live publication for the
order's own channel, the location's offering rows, the published modifier
groups, the active price book, **and the 86 list** — five reads, behind two
modules' rules. Every storefront would have to reimplement them identically,
and the Telegram bot has no menu in hand at all.

The 86 list is the one that is easy to miss, and missing it would defeat the
whole endpoint. "Available" is two records with two writers: the tenant's own
`catalog.location_offerings.status`, set from the menu editor, and the
kitchen's `inventory.positions.binary_available`, flipped from the bot's `/86`
or the operations stop list. `StorefrontCatalogQuery.menuFor` — the customer
menu — reads only the first. A repeat resolved the same way would offer a
button on a dish sold out ten minutes ago, which is precisely the failure this
record exists to prevent.

## Decision

**Resolve the repeat server-side and publish the verdict.**

Add one read endpoint, `GET .../orders/{orderId}/reorder`, which takes each
line of the caller's own order, resolves its stored `source_variant_id` and
`source_option_id`s against the menu as it stands **now** for that order's own
location and channel — the published menu, the location's offerings, *and* the
kitchen's 86 list — and returns a plan: a per-line status, the ids needed to
rebuild the cart, the price today, and one overall verdict —
`READY`, `PARTIAL`, or `UNAVAILABLE`.

A client shows the repeat button on the verdict. It does not inspect the menu,
does not match names, and does not decide what "available" means.

The plan is a **read**. Applying it is the ordinary cart path — `POST /carts`
then `PUT /carts/{cartId}/lines/{lineKey}` with the ids the plan supplied — so
a repeat is priced, quoted and checked out by exactly the code every other
basket travels. There is no second way to build a cart.

Additively, `OrderLineResponse` gains `productId`, `variantId` and
`modifierOptionIds`. The existing free-text `productName` / `variantName` /
`modifiers` stay: they are the snapshot of what was bought and outrank whatever
the menu says today.

**No new table and no new view.** The projection the plan reads *is*
`ordering.order_lines`.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A `ordering.customer_last_order` projection table holding the last order's ids | It duplicates state that already exists in `ordering.order_lines`, with no foreign keys, already indexed for "latest by customer". Worse, order lines are revisioned (`revision_from`/`revision_to`, ADR 0039), so the copy would have to be resynced on every amendment — a second place for "the last order" to disagree with `ordering.orders`, and no way to tell which is right | A profiled read shows the two-seek lookup is the bottleneck, or a bot start-up path needs the answer without touching `ordering` at all |
| A SQL view over the last order joined to catalog | The publication stores variants inside `immutable_content_json`, so the join is JSONB extraction; and the availability rules (publication for the channel, offering status, price book precedence) are ADR 0036 logic that a view would fork away from the code that serves the menu. This repo has already been bitten by exactly that fork — `StorefrontCatalogQuery` carried a hardcoded `'STOREFRONT'` channel that `CatalogPricingContext` had already had corrected | The availability rules stop changing and a materialized view is needed for read volume |
| Client-side resolution: publish the ids and let each storefront match them against the menu it already loaded | Every storefront reimplements four reads and ADR 0036's precedence, identically and eventually divergently; the Telegram bot has no menu loaded and would have to fetch a whole publication to answer one yes/no. ADR 0070 makes the contract the product — pushing this to clients pushes the hardest part of it out of the contract | Never, while ADR 0070 stands |
| A write endpoint, `POST .../orders/{orderId}/reorder`, that builds the cart and returns it | Hides the failure. A customer whose favourite is sold out gets a silently different cart rather than a button that was never offered — and it introduces a second cart-construction path beside `PUT /lines`, which is how the two drift | The bot ADR closes its round-trip question in favour of one call; it would then be a thin server-side loop over this same plan, never a separate builder |
| Return only a boolean "repeatable" | Cannot render "Lavash sold out" — and the bot in particular has to say *why* in text, having no screen to grey out | — |

## Consequences

### Positive

- The repeat that is offered is a repeat that works: same variant, same
  modifiers, same quantity, checked against the menu a second before the tap.
- One resolver, three clients. `frontend/storefront`,
  `frontend/storefront-milliy` and the Telegram bot ask the same question and
  get the same answer, which is what ADR 0070 promises a third-party storefront.
- A withdrawn dish, a sold-out dish and an unpriced dish are three different
  answers, so a client can explain rather than fail.
- No new state. Nothing to backfill, nothing to resync, nothing to reconcile.
- The plan reports today's price beside what was paid, which is the honest way
  to show a repeat whose total has moved.

### Negative

- The plan is a snapshot with a lifetime measured in seconds. A dish can be
  86'd between the plan and the `PUT`, and then the cart holds a line the
  customer cannot check out. The plan narrows the window; it does not close it,
  and pricing stays the authority that refuses.
- Four reads per plan request, on a path a bot may call at every `/start`. No
  cache is introduced here.
- `OrderLineResponse` grows three fields, so the storefront OpenAPI baselines
  and every generated client change.
- The verdict encodes a product policy the platform now owns. A tenant cannot
  say "always offer the button and let the customer sort it out".

### Accepted trade-offs

- **Modifiers are all-or-nothing per line.** A line whose product still sells
  but whose "extra cheese" option has been withdrawn is reported
  `MODIFIERS_WITHDRAWN`, not `AVAILABLE`-minus-the-cheese. Repeating an order
  and getting a plainer dish is not repeating an order.
- **The plan resolves against the order's own location.** A customer whose
  usual branch has closed gets `UNAVAILABLE` rather than a silent substitution
  to the nearest open one. Choosing a different restaurant is a decision, not a
  detail.
- **Guest orders are excluded.** The endpoint is `@CustomerOwned` and resolves
  through the account, so an order placed against a `guest_reference_hash` has
  no plan. Repeat is an account feature.

## Specification

### The port

`ordering.application.ReorderMenu`, declared by ordering and implemented in
`ordering.infrastructure.catalog` beside `JdbcCartMenuRules` — the same shape
and for the same reason: ordering needs the answer the published menu gives,
not a menu model it has no business reading.

```java
Snapshot at(UUID tenantId, UUID brandId, UUID locationId, String channelCode, Set<UUID> variantIds);

record Snapshot(Map<UUID, VariantOffer> offers)
record VariantOffer(UUID productId, boolean soldOut, Set<UUID> offeredOptionIds)
```

Absent from `offers` means **withdrawn**: not in the live publication for that
channel, or not offered at that location, or offered as `HIDDEN`. Present with
`soldOut` means the location shows it and cannot sell it — `OfferingStatus.
UNAVAILABLE`, the kitchen's own 86. The distinction is the same one
`StorefrontCatalogQuery.variantsOf` already draws for the menu itself.

Four queries regardless of line count: the active publication for the channel;
the `PRODUCT` items whose published `variants` array contains any of the ids;
those products' `MODIFIER_GROUP` items; and `catalog.location_offerings` for
the location. Prices come from `catalog.api.MenuPriceLookup`, which pricing
implements, so ADR 0036's channel-over-location precedence is honoured rather
than restated.

### The 86 list

Read through `inventory.api.InventoryReservationPort#checkAvailability` — the
same check `reserveForQuote` takes atomically with its hold, so a plan cannot
say yes where checkout says no. **Through the port and never by a join**: V0162
put `inventory.*` behind row-level security on the explicit strength of no
module outside inventory reading those tables directly, and a join here would
both break that invariant and silently return nothing under RLS.

`SOLD_OUT` maps to `SOLD_OUT`. `NOT_STOCKED_AT_LOCATION` maps to `WITHDRAWN`:
ADR 0017 is explicit that an unlisted variant is never orderable, so to a
customer it is the same fact as a dish the menu no longer carries, not a
sold-out one that returns this evening.

A `QUANTITY`-tracked variant makes `checkAvailability` throw, and the plan lets
it. That mode is unimplemented and makes `reserveForQuote` throw identically —
a plan that smoothed it over would offer a button that fails at reservation.

### Line statuses

| Status | Means | Green |
|---|---|---|
| `AVAILABLE` | published, offered here, orderable, priced, every modifier option still offered | yes |
| `SOLD_OUT` | offered here, `OfferingStatus.UNAVAILABLE` — comes back | no |
| `WITHDRAWN` | not on the live publication, or not offered at this location | no |
| `UNPRICED` | orderable but no active price resolves for this location and channel | no |
| `MODIFIERS_WITHDRAWN` | the variant is orderable; one or more of the line's option ids is not | no |

### The verdict

`READY` when every line is `AVAILABLE`. `UNAVAILABLE` when none is.
`PARTIAL` otherwise. An order with no lines is `UNAVAILABLE` — there is nothing
to repeat.

`LOCATION_UNAVAILABLE` is not a verdict; a location that no longer offers
anything yields every line `WITHDRAWN` and therefore `UNAVAILABLE`, which is
the same answer without a second vocabulary.

### HTTP

```
GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/orders/{orderId}/reorder
```

`@CustomerOwned`, no capability — the same authorization as
`GET /orders/{orderId}`, whose ownership predicate lives inside the query.
404 when the order is not the caller's, identically to a non-existent one.

Response: `orderId`, `publicOrderNumber`, `locationId`, `channelCode`,
`verdict`, `currency`, and `lines[]` of `lineNumber`, `productName`,
`variantName`, `productId`, `variantId`, `quantity`, `modifierOptionIds`,
`status`, `unitAmountMinor` (today, null unless resolvable) and
`originalUnitAmountMinor` (what was paid).

No `ETag`. A plan is not an aggregate and has no version to match on.

### Testing

In `CartCheckoutAndOrderTests`, which already seeds a publication, offerings,
price books and places real orders through the production services:

- a placed order plans `READY` and every line carries the original variant id
- setting the offering to `UNAVAILABLE` turns the plan `PARTIAL` and that line
  `SOLD_OUT`
- 86'ing through inventory, with the offering still `AVAILABLE`, turns the line
  `SOLD_OUT` too — the test that would have caught the menu's own blind spot
- hiding the offering turns the line `WITHDRAWN`
- republishing without the product turns the line `WITHDRAWN`
- withdrawing a modifier option turns the line `MODIFIERS_WITHDRAWN` while the
  variant stays orderable
- an order whose every line is gone plans `UNAVAILABLE`
- the plan's ids rebuild a cart that prices to the same total when nothing
  changed — the proof that a repeat is a real repeat and not a near miss

## Rollout and rollback

Additive throughout: one new path, three new response fields, no migration and
no data change. Rollback is deleting the endpoint; clients fall back to hiding
the button, which is the safe direction.

## Implementation checklist

- [x] `ReorderMenu` port and its `ordering.infrastructure.catalog` adapter
- [x] `ReorderPlanService` and its verdict
- [x] `GET .../orders/{orderId}/reorder`
- [x] `productId`, `variantId`, `modifierOptionIds` on `OrderLineResponse`
- [x] OpenAPI baselines regenerated, new path in the `storefront` group
- [x] `frontend/storefront-milliy` repeats from the plan and gates the button
- [x] `frontend/storefront` repeats from the plan too, via `order-detail.component.ts`'s `reorderPlan`/`canRepeat`/`repeat()`
- [x] Telegram bot inline repeat — `CustomerBotOrderingAdapter` calls `ReorderPlanService`, gated behind `TELEGRAM_CUSTOMER_INLINE_ACTIONS_ENABLED`
- [ ] `StorefrontCatalogQuery.menuFor` reads the 86 list too. Out of scope
      here — this record makes the repeat button correct, and leaves the menu
      itself showing a sold-out dish as orderable until checkout refuses it.
      Its own change, with its own blast radius across every menu read

## Exit criteria

A customer whose last order contains a dish the kitchen has just 86'd sees no
repeat button; the same customer sees it again the moment the kitchen puts the
dish back, and tapping it produces a cart holding the same variants, the same
modifiers and the same quantities as the order it repeats.

## References

- V0022 `ordering.order_lines`, `ordering.order_line_modifiers`
- `catalog.application.StorefrontCatalogQuery#variantsOf` — the offering
  semantics this record reuses
- `ordering.infrastructure.catalog.JdbcCartMenuRules` — the port pattern
