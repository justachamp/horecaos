# ADR 0036: Sales channels and location serviceability

- Decision status: Accepted
- Implementation status: Partial — V0020 builds the channel registry, the
  channel/mode matrix, service schedules, service states and preparation bands;
  `tenancy`'s `SalesChannelService`, `ServiceabilityService` and their
  control-plane, operations and storefront controllers
  (`SalesChannelController`, `LocationServiceOperationsController`,
  `ServiceScheduleController`, `ServiceabilityController`) implement the
  eight-rule resolver with its reason codes and `next_available_at`, the state
  transitions with mandatory reason and expiry, and the longest-wins band;
  `JdbcPricingStore` resolves a `CHANNEL`-scoped price book through
  `price_plane_channel_id`; V0023 stores the order promise with its basis; and
  the capacity check is now wired — `CheckoutService` claims the slot inside the
  ADR 0019 checkout transaction against `tenant.location_capacity_holds` keyed by
  order id (V0022), releases it when acceptance fails, and `OrderStateService`
  releases it when the order reaches a terminal state. Not built: the travel component of the promise (ADR
  0037's routing port answers empty on every call), the foreign key from
  `tenant.channel_payment_methods.payment_method_code` to
  `payments.payment_methods` — the table landed in V0042 and the column is still
  unconstrained text — the `SalesChannelActivated` family of events, and
  `catalog.channel_offering_exclusions`, which has its table and constraints and
  no reader anywhere in the codebase
- Date proposed: 2026-08-21
- Date decided: 2026-08-21
- Deciders: Ayubkhon Abbosov (platform architecture), product (channel and serviceability semantics), finance (channel price planes)
- Depends on: ADR 0002, ADR 0016, ADR 0018, ADR 0019, ADR 0025, ADR 0030, ADR 0038, ADR 0040
- Supersedes / Superseded by: —
- Open inputs: Whether an aggregator channel's price plane may exceed the storefront price, and what disclosure that carries (finance, legal) — not structural; the price-plane mechanism is identical either way

## Context

`channel` already exists in three schemas and one method signature, and no
decision says what it holds.

| Where | What exists today |
|---|---|
| `catalog.publications.channel` | `varchar(32) NOT NULL DEFAULT 'STOREFRONT'`, part of the partial unique index keeping one live menu per brand and channel |
| `pricing.price_book_assignments` | `scope_type CHECK (... 'CHANNEL')` with an untyped `scope_id` |
| `ordering.carts` / `orders` | `channel` in the ADR 0019 model, unbuilt |
| `JdbcPricingStore.resolvePriceBook` | Took a `String channel` and never bound it. The clause was `a.scope_type = 'CHANNEL' AND a.scope_id IS NOT NULL`, so one channel-scoped price book priced *every* channel |

That last row is the shape of the problem: the query could not filter by channel
because there was no channel identity to filter by, so a feature that looked
implemented was a defect waiting for the first tenant who sells on an aggregator.

**Both halves are now closed, in two steps.** The defect was found while this ADR
was being drafted and fixed immediately by excluding the `CHANNEL` scope
altogether — honest, because a configuration that cannot be honoured correctly
must not be honoured approximately, but inert, because it meant a channel-scoped
price book silently did nothing. Implementing this ADR made the scope real:
`resolvePriceBook` now binds a channel id, a null channel matches no `CHANNEL`
assignment rather than all of them, and channel outranks location — a
location-scoped book that silently beat a channel-scoped one would make an
aggregator's agreed price plane depend on which branch happened to fulfil the
order.

Delever settles the modelling question by evidence. Its registry (Каналы продаж)
is tenant-managed CRUD — display name, a system type from a fixed list (iOS,
Android, Kiosk, Bot, Website, Admin panel, Aggregator, Hall, QR), active flag —
and tenants run several rows of one type, because Uzum Tezkor and Yandex Eda are
both `Aggregator` and must differ in price plane, payment mix and reporting.
Nearly everything else is keyed by channel: its content settings screen is four
editable matrices of channel × payment method, channel × order type, channel ×
menu item, and channel price.

Serviceability is an orphan and belongs in the same decision. ADR 0016's sketch
carries `sales_schedule_id null` on `location_offerings`; migration `V0016` never
created that column, so the pointer exists only in the decision record and aims
at a table no ADR owns. ADR 0019 requires a scheduled order to validate against
an "opening/exception schedule" and "fulfillment capacity" that likewise nobody
owns. Delever and the legacy dashboard both carry what this market expects:
weekday venue hours and separate delivery hours, dated non-working-day
overrides, time-banded preparation durations so a Friday rush quotes 45 minutes
rather than 25, a per-branch concurrent-order ceiling, and a manual switch a
manager hits when the fryer dies.

Both halves are one question asked twice: what may be sold here, right now,
through this route.

## Decision

**A sales channel is a tenant-owned registry row carrying a code-owned system
type.** The row is tenant data so a tenant registers its third aggregator
without a deploy; `system_type` is code so behaviour never keys on a name an
operator typed. Channels archive, never delete — every order carries its channel
forever, and a deleted row makes that order unattributable in every report.

**A channel is not a scope level.** ADR 0030 resolves `LOCATION -> BRAND ->
TENANT -> PLATFORM`, which is an ancestry: a location is *inside* a brand.
Channel is orthogonal, so inserting it produces a lattice where a location
override and a channel override have no defined winner. Channel availability is
explicit data — matrix rows — and where a policy must vary by channel
(auto-accept on the bot but not the call centre), the channel list lives inside
the ADR 0030 policy document, reviewable as one object.

**A channel gates four things and nothing else keys on it.**

| Gate | Mechanism |
|---|---|
| Payment methods | `channel_payment_methods` rows; absent means unavailable |
| Fulfilment modes | `channel_fulfillment_modes` rows, intersected with what the location serves |
| Catalog visibility | The channel supplies `catalog.publications.channel`; single items are suppressed by sparse exclusions |
| Price plane | `price_book_assignments` at `CHANNEL` scope, resolved through the channel's `price_plane_channel_id` |

**Dine-in is a fulfilment mode, not a channel.** Delever's Зал is both, which is
why its order-type and channel filters disagree. A QR-table order and a
waiter-entered order are both `DINE_IN` arriving through different channels.

**Serviceability is one resolver returning one typed answer.** For (location,
channel, fulfilment mode, instant) it returns availability, a stable reason code,
the next instant service resumes, and the preparation estimate. Browse, quoting
and ADR 0019 checkout all call it; there is no second implementation and no
boolean anyone can flip out of band. Hours behind it are named, reusable
schedules bound per fulfilment mode, not fixed venue and delivery fields on the
branch: fixed pairs cannot express pickup closing before dine-in, and thirty
branches on one Ramadan timetable should edit one object.

**The preparation promise is assembled in a fixed order and the longest wins:**
the time-of-day band for the order's start instant, then the maximum of that and
any line-level `preparation_duration_override`. A pizza that takes 40 minutes
does not become 20 because the quiet-hours band says so. The concurrent-order
cap is advisory at browse and authoritative at checkout, counted inside ADR
0019's transaction rather than read from a cached number.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A code-owned enum of channels | A tenant runs several aggregator-typed channels that must differ in price plane, payment matrix and reporting; an enum means a schema change and a deploy each time a tenant signs a marketplace, and hands every tenant one vocabulary none can extend | The market stops adding aggregators and every tenant sells through the same fixed six — not expected where Uzum Tezkor, Yandex Eda, Wolt and Express24 all moved within two years |
| Channel as a fifth level of the ADR 0030 scope chain | The chain is ancestry; channel is orthogonal to it. A location override and a channel override would have no defined winner and "most specific wins" would stop meaning anything | Never. Channel-varying policy goes inside the policy document |
| A boolean `is_open` on the location, flipped by a scheduled job | Cannot say when it reopens, cannot differ per fulfilment mode, and one failed job silently closes a network with a cause indistinguishable from an outage | Never |
| Copy Delever: a fixed pair of venue hours and delivery hours on the branch form | Cannot express pickup differing from dine-in, cannot share a timetable across branches, and every new fulfilment mode becomes a schema change. Named schedules cost one join | Never |
| Materialise availability for every (variant, location, channel) | Correct and unreviewable: tens of thousands of near-identical rows, and nobody can tell an intended exception from a stale one. Default-on with sparse exclusions carries the same information | A tenant genuinely authors a different assortment per channel — already served by publishing a separate catalog to that channel |

## Physical model

Channels and schedules live in the `tenancy` module, schema `tenant`. Catalog,
pricing and ordering all read them; owning them in any one of those would make
that module a dependency of the other two.

```text
tenant.sales_channels
  id, tenant_id, code, system_type, display_name, status
  price_plane_channel_id null      -- null means "use my own"
  externally_priced boolean          -- seeds orders.pricing_authority, ADR 0040
  guest_orders_allowed boolean
  provider_installation_id null    -- ADR 0026, for AGGREGATOR/TELEGRAM/KIOSK
  version, timestamps, unique(tenant_id, code)

tenant.sales_channel_locations
  tenant_id, channel_id, location_id, status, version

tenant.channel_payment_methods
  tenant_id, channel_id, payment_method_code, enabled, version

tenant.channel_fulfillment_modes
  tenant_id, channel_id, fulfillment_mode, enabled, version
```

`system_type` is the code-owned set `WEB`, `IOS`, `ANDROID`, `TELEGRAM`,
`KIOSK`, `QR_TABLE`, `CALL_CENTRE`, `AGGREGATOR`, `POS`, and **the set is closed
and owned here.** A tenant cannot add a type, and no other ADR may introduce a
channel type name of its own: an ADR that needs a type this list does not carry
amends this list, in this file, as part of being accepted. The failure that rule
prevents is the one this ADR exists to end — two vocabularies for one concept,
where `POS` and some later `DINE_IN_POS` name the same channel, behaviour keyed
on `system_type` matches one and not the other, and every report that groups by
type undercounts both halves without anything failing.

`fulfillment_mode` is `DELIVERY`, `PICKUP`, `DINE_IN`, matching ADR 0019's cart.
`display_name` is a single admin-facing string: a channel name is never shown to
a customer, so it is not translated. `payment_method_code` is a foreign key to
`payments.payment_methods`, the tenant-scoped payment-method registry ADR 0038
owns, each row of which carries the fiscal `responsibility` that 0038 validates
at activation. This ADR owns the matrix, not the registry: a channel row enables
or disables a method the tenant has already registered and can never invent one.
An earlier form of this section treated the codes as code-owned constants because
no registry existed; ADR 0038 creates it, and that fallback is withdrawn — a
channel enabling a code with no registry row would be a payment method with no
owning legal entity and no fiscal responsibility, which is exactly the gap 0038
was written to close.

Per-channel item suppression sits in `catalog`, because it references a variant
under the composite brand key ADR 0016 enforces. Default is offered; a row
removes one item from one channel, optionally at one location, and is read live
like `location_offerings` for the reason ADR 0016 already gives — hiding a dish
must take effect now, not after revalidating a whole menu.

```text
catalog.channel_offering_exclusions
  tenant_id, brand_id, location_id null, variant_id, channel_id
  reason_code, version, timestamps

tenant.service_schedules
  id, tenant_id, brand_id null, name, accepts_scheduled_orders, version

tenant.service_schedule_rules
  schedule_id, day_of_week, opens_at time, closes_at time, sequence
  -- closes_at <= opens_at means the window ends on the following day

tenant.service_schedule_exceptions
  schedule_id, exception_date, closed_all_day, opens_at null, closes_at null
  label, created_by, reason

tenant.location_service_bindings
  tenant_id, location_id, fulfillment_mode, schedule_id, version
  unique(location_id, fulfillment_mode)

tenant.location_service_state
  location_id, tenant_id, mode, reason_code, note null
  effective_until null, max_concurrent_orders null
  changed_by, changed_at, version

tenant.preparation_bands
  id, tenant_id, location_id, fulfillment_mode null, day_of_week null
  starts_at time, ends_at time, duration_minutes, priority, version
```

Times are local wall-clock resolved against `tenant.locations.timezone`, which
already exists. Uzbekistan has observed no daylight saving since 1995 and UZT is
a fixed UTC+5, so ADR 0019's DST-ambiguity handling will never fire here — the
resolver still computes through the IANA zone rather than a hardcoded offset,
because otherwise the first tenant outside Uzbekistan inherits a silent
one-hour error at every schedule boundary. The after-midnight rule is explicit
for the same class of reason: a venue open 18:00–02:00 stored as a naive range
compares as `18:00 <= t < 02:00`, which is empty, and the branch reads as shut
all evening.

## Location service state

```text
FOLLOW_SCHEDULE -> FORCE_CLOSED       (manual, reason required)
FOLLOW_SCHEDULE -> FORCE_OPEN         (manual, reason required)
FORCE_CLOSED    -> FOLLOW_SCHEDULE    (manual reopen, or effective_until elapses)
FORCE_OPEN      -> FOLLOW_SCHEDULE    (manual, or effective_until elapses)
```

A manual close is never a bare boolean. It carries an actor, a reason code, and
either an `effective_until` or an explicit "until I reopen it". The failure this
prevents is common and expensive: a branch closed at 19:00 because the fryer
failed and still closed on Saturday, because the person who closed it went home.
Operations shows a persistent banner for any location off `FOLLOW_SCHEDULE`, and
the change is an ADR 0027 audit fact.

## Serviceability resolution

Evaluated in this order, so the reason returned is the most fundamental one
rather than whichever check happened to run last:

```text
1. Channel active for the tenant                -> CHANNEL_NOT_ENABLED
2. Channel enabled at this location             -> CHANNEL_NOT_ENABLED
3. Fulfilment mode enabled on the channel       -> FULFILMENT_MODE_UNAVAILABLE
4. Service state not FORCE_CLOSED               -> MANUALLY_CLOSED
5. No dated exception closing today             -> CLOSED_BY_EXCEPTION
6. Inside a weekly window for this mode         -> OUTSIDE_SERVICE_HOURS
7. Live publication for brand + channel         -> NO_LIVE_MENU
8. Concurrent orders below the cap              -> AT_CAPACITY
```

`FORCE_OPEN` skips rules 5 and 6 and nothing else: a manager overriding hours
does not thereby override an entitlement, an empty menu, or the kitchen ceiling.
Every negative answer carries `next_available_at` where one is computable, and
each schedule carries `accepts_scheduled_orders`, so a closed location can still
take tomorrow's pre-order — closed now and cannot pre-order are different facts,
and Delever's out-of-hours branch resolution exists because merchants want the
first without the second. Reason codes are stable ADR 0031 problem codes; the
storefront maps them to customer wording and never renders the code.

Browse reads a cached resolution with a TTL of at most 30 seconds under ADR
0033, invalidated by service-state and schedule events. The checkout transaction
re-resolves from PostgreSQL and never reads that cache. The cap is a conditional
count inside the transaction: two customers checking out against the last free
slot are settled by the database, not by a number either read a second earlier.

## Corrections and extensions to existing decisions

| ADR | Change |
|---|---|
| 0016 | `catalog.publications.channel` becomes a `sales_channels.code` reference validated on publish, not free text defaulting to `STOREFRONT`. The sketched `sales_schedule_id` is withdrawn: item windows reference `tenant.service_schedules`, and the column was never created |
| 0018 | `price_book_assignments.scope_id` at `CHANNEL` scope is a `sales_channels.id`, and `resolvePriceBook` binds it — closing the defect where any channel-scoped book priced every channel. Resolution follows `price_plane_channel_id`, which is how "for QR and kiosk take the hall's prices" becomes one column instead of a duplicated price book |
| 0019 | `carts.channel` and `orders.channel` are a `channel_id` plus a `channel_code` snapshot, so an archived channel still renders on a historical order. The schedule and capacity a scheduled order validates against are the tables above |
| 0030 | Channel is not a new scope level; policy documents may carry a channel-code list, and `ordering.acceptance` gains one for auto-accept |
| 0002 | Order acceptance may vary by channel through that list, with no new resolution axis |
| 0047 | Its `DINE_IN_QR`, `DINE_IN_POS` and `ADMIN` are not added to `system_type`. Those channels are `QR_TABLE`, `POS` and `CALL_CENTRE`, the names this ADR already owns |

`externally_priced` on a channel — an aggregator setting its own customer price —
is a **default that seeds `ordering.orders.pricing_authority`** when an order is
created on that channel. It is not a second enforcement point. ADR 0040 owns
`pricing_authority (QOIDA|EXTERNAL)`, and that column on the order is the only
value the pricing path consults; the channel flag is never read again after the
order exists, and never by the pricing engine. ADR 0040 constrains the seeded
value further — `EXTERNAL` is legal only where the order's channel resolves to a
`MARKETPLACE` binding — so a tenant ticking the flag on a storefront channel does
not thereby buy itself an unpriced order.

The reason for one enforcement point rather than two is that two disagree
silently. A flag flipped after orders were placed would retroactively change how
already-booked orders are read, and an order whose channel was corrected would
answer one way in its own row and another on the channel. ADR 0018 promises a
Qoida-priced quote is reproducible from its context hash; an order stamped
`EXTERNAL` is never re-derived, so the promise holds rather than being quietly
weakened.

## APIs and events

```text
GET  /api/v1/storefront/locations/{locationId}/serviceability?channel=&mode=
POST /api/v1/control-plane/tenants/{tenantId}/sales-channels
PUT  /api/v1/control-plane/sales-channels/{channelId}/payment-methods
PUT  /api/v1/control-plane/sales-channels/{channelId}/fulfillment-modes
PUT  /api/v1/control-plane/sales-channels/{channelId}/locations/{locationId}
POST /api/v1/control-plane/brands/{brandId}/service-schedules
POST /api/v1/operations/locations/{locationId}/service-state
PUT  /api/v1/operations/locations/{locationId}/preparation-bands

SalesChannelActivated / SalesChannelArchived
ChannelAvailabilityChanged
ServiceScheduleChanged
LocationServiceStateChanged
LocationCapacityReached / LocationCapacityCleared
```

Matrix writes are whole-matrix `PUT` with an expected version, never per-cell
patches: a payment matrix edited cell by cell from two tabs produces a
combination neither operator chose. Capabilities are `channel.manage` and
`serviceability.manage` at tenant or brand scope, and
`location.service-state.change` at `LOCATION` scope, so a branch manager can
close their own branch without holding rights over the network. ADR 0021
composes on top: a channel whose `system_type` the plan excludes is `INACTIVE`,
never deleted. Events carry identifiers, versions and the reason code, never
matrix contents; consumers re-read through the port, per ADR 0032.

## Testing

- Each of the eight rules is the first to fail exactly once, and the returned
  code is that rule's.
- An 18:00–02:00 window is open at 23:00 and 01:00, closed at 03:00.
- A dated exception beats the weekly rule, `FORCE_CLOSED` beats both, and
  `FORCE_OPEN` beats hours but not entitlement, publication, or capacity.
- `effective_until` elapsing returns a location to `FOLLOW_SCHEDULE` with no
  operator action and no scheduled job computing it.
- A channel-scoped price book prices only its channel — the regression test for
  the unbound parameter in `resolvePriceBook` — and a price plane pointing at
  another channel resolves that channel's assignments without recursing.
- Concurrent checkouts against the last capacity slot settle at one; the loser
  receives `AT_CAPACITY` rather than an order.
- Flipping `externally_priced` on a channel changes how the next order is
  stamped and changes nothing about orders already placed on it; the pricing
  path reads `orders.pricing_authority` and never the channel row.
- A channel cannot enable a `payment_method_code` with no row in
  `payments.payment_methods` for that tenant; the foreign key refuses it.
- An archived channel still renders on a historical order and cannot be chosen
  on a new cart; cross-tenant channel ids and cross-brand schedule bindings fail
  at the database.

## Rollout and rollback

Seed one `WEB` channel per tenant with code `STOREFRONT` — the value
`catalog.publications.channel` already holds — so existing publications stay
valid without a rewrite. Derive each location's schedule from the legacy vendor
`start`/`finish` fields and compare a week of resolver output against them before
anything depends on it. Enable the resolver for browse first, logging what it
would have refused, then enforce at checkout per location once that log is
explainable. Rollback disables checkout enforcement and leaves browse advisory;
tables and audit remain, and no order is rewritten because channel is
snapshotted by code.

## Consequences

### Positive

- One vocabulary for channel across catalog, pricing, ordering and reporting,
  fixed before ADR 0019 writes the first order row rather than after.
- A tenant adds its next aggregator by inserting a row; code keyed on
  `system_type` needs no change.
- "Why can't I order from this branch" has one answer, one reason code and one
  resolver, for the customer, the operator and support alike.
- ADR 0016's orphan schedule pointer and ADR 0019's undefined capacity check
  both acquire a named owner.

### Negative

- Every storefront read path gains a serviceability call — a new hot dependency
  with a cache whose invalidation must be right. A manual close that takes five
  minutes to appear is worse than no manual close.
- Behaviour now depends on tenant-entered matrix rows, so one unticked cell
  presents as an outage. Support needs a resolution trace for channels the way
  ADR 0030 has one for configuration.
- Two mechanisms suppress an item on a channel: a separate publication and an
  exclusion row. A merchant who uses the first will not think to check the
  second.
- The promised time is now assembled from a band, an item override and ADR
  0019's lead time. It is the most complained-about number in food delivery and
  it has three sources.
- Shared named schedules mean editing one object changes several branches at
  once. That is the point, and also the accident.

### Accepted trade-offs

- Capacity is a concurrent-order ceiling, not a slot model, so a pre-order surge
  into one lunch hour is unprotected. A real slot model is a later decision this
  table does not block.
- Behaviour keys on `system_type`, so a tenant may name a row anything; reports
  group by type and a badly named row is only cosmetically wrong.
- Channels are per-tenant, so two tenants' `AGGREGATOR` rows are different
  objects and cross-tenant comparison must aggregate by system type.

## Implementation checklist

- [x] Add channel, matrix, schedule, state and band tables via Flyway, plus
      `channel_offering_exclusions` in `catalog` under composite brand keys.
      Migration `V0020`. `service_schedules.brand_id` is `NOT NULL` rather than
      nullable as sketched above: a NULL in a composite foreign key switches the
      whole check off, so a nullable brand would have made
      `location_service_bindings` accept any schedule id at all. The APIs this
      ADR specifies create schedules under a brand, so nothing it decides needs
      the nullable form.
- [x] Implement `SalesChannelLookup` and `ServiceabilityResolver` ports in `tenancy`.
- [ ] Point `channel_payment_methods.payment_method_code` at `payments.payment_methods` once ADR 0038 lands it, and seed `orders.pricing_authority` from `externally_priced` at order creation per ADR 0040. Both wait on their ADRs; the column and the flag exist and are unread.
- [x] Bind the channel argument in `JdbcPricingStore.resolvePriceBook` and add the regression test.
      The context section above is stale about this method: by the time this ADR
      was implemented the unbound parameter had already been removed and the
      `CHANNEL` scope excluded outright. The scope is now resolved for real, and
      through `price_plane_channel_id`.
- [x] Replace free-text `catalog.publications.channel` with a validated channel code. Foreign key in `V0020`, plus an archived-channel check on publish that the database cannot express.
- [x] Implement the eight-rule resolver, reason codes and `next_available_at`.
- [x] Implement service-state transitions with mandatory reason, expiry and ADR 0027 audit.
- [x] Implement preparation-band resolution and the longest-wins rule.
- [x] Wire the capacity check into the ADR 0019 checkout transaction. The claim is
      built and settles concurrent callers on a row lock, but ADR 0019 has no
      checkout to call it from and `ordering.orders` does not exist, so the
      counted set is an interim `tenant.location_capacity_holds` table. When 0019
      lands, the count becomes a count of open orders and that table is dropped in
      the same migration.
- [x] Add control-plane, operations and storefront APIs with ADR 0025 capabilities. Caching under ADR 0033 is **not** built: the browse endpoint carries a 30-second `Cache-Control` and nothing reads a server-side cache, so there is no invalidation that can be wrong yet.

Not built in this pass, and deliberately: the events listed above
(`SalesChannelActivated` and the rest), and `catalog.channel_offering_exclusions`
has its table and its constraints but no service or endpoint reading it — the
sparse-exclusion read path belongs with the storefront menu query rather than
with the channel registry.

## Exit criteria

Every cart, order, publication and price-book assignment references a channel
that exists as a row; a channel-scoped price book prices only that channel; a
location's availability for a channel and fulfilment mode is answered by one
resolver with one stable reason code and a next-available instant; a manager can
close a branch with a reason and an expiry and cannot leave it closed by
accident; and no module keeps a private notion of what a channel or an opening
hour is.

## References

- [ADR 0016: Brand catalog, publication, and location offerings](../partial/0016-brand-catalog-publication-and-location-offerings.md)
- [ADR 0018: Deterministic pricing, promotions, taxes, and quotes](../partial/0018-deterministic-pricing-promotions-taxes-and-quotes.md)
- [ADR 0019: Cart, checkout, and order orchestration](../partial/0019-cart-checkout-and-order-orchestration.md)
- [ADR 0038: Legal entities, fiscal receipts, and fiscal product classification](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md)
- [ADR 0040: Marketplace channel: inbound aggregator orders and the partner API](../partial/0040-marketplace-channel-and-partner-api.md)
- [Delever parity matrix](../../delever-parity-matrix.md)
