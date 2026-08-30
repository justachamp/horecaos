# ADR 0037: Delivery zones, tariffs, and delivery-fee resolution

- Decision status: Accepted
- Implementation status: Partial — V0025 enables PostGIS and `btree_gist` and
  builds `fulfillment.regions`, `service_zones`, `service_zone_versions`,
  `zone_location_bindings`, `delivery_tariffs`, `delivery_tariff_versions`,
  `delivery_tariff_bands`, `delivery_tariff_time_rules`,
  `location_tariff_bindings` and `delivery_fee_resolutions`, with the branch
  coordinate V0023 added as the zone origin; **V0032 corrects the rate-table
  model** and adds `delivery_tariff_discounts` — see *What the first reading of
  the legacy got wrong* below. `ServiceZoneService` implements zone versioning,
  circle and polygon drafting and activation validation (self-intersecting rings,
  the 2 000 km² area ceiling, and the region bounding-box check that catches a
  transposed latitude); `DeliveryFeeResolver` runs the whole ordered rule set with
  full evidence written to `delivery_fee_resolutions`, deterministically (priority,
  then smallest area, then id), refusing an unlocated branch, an address outside
  every zone (`OUT_OF_ZONE`), and an address outside the branch catchment
  (`OUTSIDE_CATCHMENT`) rather than silently matching nothing;
  `pricing.QuoteService.resolveDeliveryCharge` feeds the charge into the quote and
  `PricingEngine` emits it as the `DELIVERY_FEE` adjustment line, whose fiscal
  classification node V0028 creates and `CatalogValidator` reports as
  `FISCAL_DELIVERY_FEE_UNCLASSIFIED`; the storefront quote, the control-plane
  simulator and the operations evidence read are `DeliveryFeeController`, and zone
  and tariff authoring are `ServiceZoneController` and `DeliveryTariffController`
  under ADR 0025 capabilities. `DeliveryFeeResolutionTests` and
  `LegacyDeliveryParityTests` cover the golden legacy fees, overlap and priority,
  band gaps and overlaps, the `RADIUS_FALLBACK` path, the threshold waiver, and
  cross-tenant and cross-brand isolation. The contract with ADR 0014 is now kept
  in code as well as on paper: V0054's `fulfillment.delivery_plans` carries
  `customer_delivery_fee_minor` and `delivery_fee_resolution_id`, and
  `DeliveryPlanningService` copies both off the order through
  `ordering.JdbcDeliveryOrderPort` rather than re-running the resolver at plan
  creation. Not built: any routing provider —
  `DeliveryRoutingConfiguration` registers a `RoadDistancePort` that answers empty
  on every call, so a `ROAD` tariff always prices at straight-line distance times
  its detour factor and records `RADIUS_FALLBACK`; the promise's travel component;
  serviceability *search* across branches (the resolver answers one named branch at
  a time, and nothing returns candidate branches for an address); legacy zone
  import with map rendering and shadow comparison, of which only the pure tariff
  import (`LegacyTariffImport`) exists; a geometric tiling test for hairline gaps
  between adjacent zones; and an ADR 0027 approval on zone or tariff activation,
  which is capability-gated only.
- Date proposed: 2026-08-21
- Date decided: 2026-08-21
- Date revised: 2026-08-23 — rate-table model corrected against the legacy writer and reader (V0032)
- Deciders: Ayubkhon Abbosov (platform architecture), product (zone and serviceability policy), finance (fee source, caps, subsidy exposure)
- Depends on: ADR 0002, ADR 0014, ADR 0015, ADR 0016, ADR 0018, ADR 0030, ADR 0040
- Supersedes / Superseded by: —
- Open inputs: Routing provider for road distance and the per-city detour factor (product; inherits ADR 0015's geocoder input). Fiscal classification code and VAT treatment of the delivery-fee line (finance, legal; lands in the fiscalization ADR). Commercial approval of offering `PROVIDER_QUOTE` mode to tenants at all (finance). None is structural: `RADIUS` and `TARIFF` are the defaults, the routing port is provider-neutral, and the fee is a quote line either way.

## Context

ADR 0018 fixed a deterministic eight-stage pricing pipeline and reserved stages 5
and 6 for delivery charges and delivery benefits. Both are unbuilt, and
`PricingEngine` says so in a comment rather than stubbing them. ADR 0014 states
that the customer delivery fee is "snapshotted at checkout" and never says which
component computed it. Between the two there is a number on every delivery order
that nothing owns.

That number can legitimately come from four places: a tariff on the location, a
tariff on the delivery zone containing the address which in Delever outranks it, a
live quote from Yandex Delivery or Noor, or a promotion and per-zone threshold
granting free delivery. Delever's documentation never states the precedence in one
place, which is exactly how a quote stops being reproducible — two code paths
compute two plausible fees and nobody can say which was right.

The zone model is a second unowned decision. ADR 0015 defers eligibility to
"approved PostGIS zones" that do not exist. Delever runs three overlapping
geometry layers — branch geozone, delivery zone, and an undocumented "free
geozone" — and never explains which wins. The legacy Qoida dashboard stores zones
as raw JSON typed `circle`, `polygon`, or `city`, so containment needs three code
paths that disagree at the boundary.

The revenue/cost split must be decided here too. If the customer pays a tenant
tariff while the courier is billed a dynamic provider price, the two rate tables
diverge invisibly until a monthly reconciliation. Delever ships a report whose
only purpose is putting *Сумма доставки* beside the provider's invoice, which
tells you the payout disputes were frequent enough to need a screen. Meanwhile
`fulfillment` currently contains only `package-info.java`: no zone table, no
tariff table, no PostGIS, and no distance calculation anywhere in the codebase.

## Decision

**One zone entity with a typed role, evaluated in PostGIS, versioned like a
policy.** Not three geometry layers, and not JSON shapes with a type discriminator.

**The customer fee is a tenant tariff by default; a provider quote is cost, not
price.** `fee_source` is an explicit per-tariff choice, and the gap between what
the customer paid and what the provider billed is an ADR 0014
`DELIVERY_COST_SUBSIDY`, never a change to the fee.

**Fee resolution has one total order, written once, here** — pricing authority,
serviceability, zone selection, rate table with zone outranking location,
computation, waivers — each step recording the entity and version that decided it.

**The fee is a quote line, not an adjustment.** ADR 0018 already carries
`priceable_type = FEE`; a line can hold a fiscal classification code and a tax
share, and this market requires the delivery charge on its own receipt line.

**Fulfillment owns zones and tariffs; pricing consumes a resolved charge through a
port.** Distance, zone, band, and time rule are resolved by `QuoteService` before
the engine runs and hashed into the quote context, so `PricingEngine` stays a pure
function that reads no clock and no database.

**An address outside every zone of the chosen location is refused there, never
re-homed to another location silently.**

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Copy Delever's three geometry layers | Three layers with no documented interaction is the ambiguity, not a fix for it. One entity with a `zone_role` covers every case, and a "free geozone" is a delivery zone whose tariff resolves to zero | A geometry is needed that is neither catchment nor delivery area — a courier staging boundary, a no-go area — at which point add a role, not a table |
| Keep zones as JSON `circle`/`polygon`/`city` as the legacy dashboard does | Three containment implementations that disagree at the boundary, no spatial index, and a full scan of every zone on the checkout path. A circle is authored as a circle and stored as a polygon, so exactly one predicate ever answers "is this point inside" | Never. The authoring shape is kept beside the geometry so the editor still round-trips a circle |
| Compute containment in Java from lat/lon, no PostGIS | Avoids a database extension. Rejected because point-in-polygon over hundreds of multipolygons per quote needs a spatial index to stay in the checkout latency budget, and a hand-written ray-cast is a correctness liability on shared borders. PostgreSQL is self-hosted under ADR 0034, so the extension costs an install line | The platform moves to a managed PostgreSQL without PostGIS, forcing H3 cell indexing instead |
| Charge the live provider quote by default | The price then depends on Yandex surge at the instant of checkout, two customers in one building pay different fees, and the tenant cannot publish a delivery price. It also breaks ADR 0018's promise that a quote is reproducible from its context hash | A tenant explicitly wants pass-through and accepts the variability. That is what `fee_source = PROVIDER_QUOTE` is for, capped, per tariff |
| Fall back to the nearest location that covers the address | Substituting a branch changes the menu, the prices, the preparation time, and — once per-branch INN exists — the legal entity issuing the receipt. The customer gets a confirmation for a restaurant they did not choose | Never as a silent step. Serviceability search returns candidates and the customer or operator picks, which re-prices the cart |
| Express per-zone values through ADR 0030 configuration | ADR 0030 resolves strictly `LOCATION → BRAND → TENANT → PLATFORM`. A zone is not one of those scopes, so this needs a fifth level and makes the precedence rule untrue everywhere else | Never. Per-zone values live on the zone row; per-location switches stay in ADR 0030 |

## Zones and regions

| Role | Decides | Carries a tariff |
|---|---|---|
| `DELIVERY` | Whether an address may be delivered to, and at what price | Yes |
| `CATCHMENT` | Which locations are candidates, and the branch containment guard | No |

A zone is `(tenant, brand, role, code)` with versions moving `DRAFT -> ACTIVE ->
RETIRED` (or `DRAFT -> DISCARDED`). Editing geometry, priority, tariff binding, or
threshold creates a new version and never mutates the active one; a quote pins
`(zone_id, zone_version)`, because a payout dispute six weeks later asks whether
*that* address was inside *that* zone and today's polygon cannot answer it. One
`ACTIVE` version per zone is enforced by a partial unique index — the pattern
`tenant.policy_current` already uses. Activation is an ADR 0027 audited fact
needing approval above the configured risk threshold, because activating a bad
polygon stops sales in a district.

Geometry is `geography(MultiPolygon, 4326)` with a GiST index. Circles are
buffered into polygons at authoring time, with centre and radius kept in
`authoring_shape`. Activation rejects self-intersecting rings, geometry outside
the brand's regions, and area above a configured maximum — which is what stops an
operator drawing a polygon around the whole country by accident.

**Overlap is legal and resolved deterministically.** A premium inner-city zone
inside a wider city zone is normal. Candidates containing the point rank by
`priority` descending, then smaller area first, then `zone_id` ascending. The
final tiebreak exists for the same reason ADR 0018's price-book resolution has
one: without it two equally ranked zones resolve by whatever the query planner
emitted first, and the same address prices differently on consecutive requests.
Losing candidates are recorded as evidence.

A **region** is a code, multilingual name, centre point, and explicit SW/NE
bounding box. It constrains the ADR 0015 `GeocodeAddress` port and marks results
outside the box `LOW_CONFIDENCE`, preventing a specific failure: an unconstrained
geocoder asked for a Tashkent street name returns a plausible street of the same
name in another country, the address is accepted, and the error surfaces only when
a courier is standing somewhere else entirely. A region carries no pricing, no
configuration, and no permissions, so it does not reintroduce the `Tenant -> Brand
-> Region -> Location` tier ADR 0002 rejected.

## What the first reading of the legacy got wrong

This section exists because the mistake is worth more than the fix.

A profiling pass read the JSON **keys** of the legacy `vendors.delivery` column,
matched them against the model above, and concluded that the two were the same
shape — that the delivery-zone migration was "a field mapping rather than a
redesign". The keys did line up. `distance`, `distance_price`, `prices_per_km`,
`max_distance`, `peak_hours` all have obvious counterparts here.

A later audit read the writer, `apps/dashboard/schemas/company.py`, and then the
reader, `apps/customer/services/cart/calculate_delivery_price.py`. **A field's
meaning is what the reader does with it, and a key name carries none of that.**
Five things were wrong, four of them about money:

1. **`prices_per_km` is a stepped tariff, not a rate.** It is a list of bands,
   each charging only its own width, and the reader accumulates across all of
   them. V0025 modelled a rate table that read the single band containing the
   distance, which forces every band's base to be authored as the cumulative
   charge for reaching it. That cannot hold the fraction a step contributes
   (`width × price / 1000` is not always a whole som) and it silently stops adding
   up the moment an earlier band is edited.
2. **`peak_hours` replaces the base band wholesale.** The reader substitutes
   `distance`, `distance_price` and `prices_per_km` together. V0025 modelled a
   multiplier and a flat surcharge on the base rate. Adding a surcharge to a base
   rate computes a different number from swapping the rate out — the two can be
   made to agree at one distance and disagree at every other, which is the failure
   mode that never announces itself.
3. **`discount` is a required field, not an optional extra.** It carries a value, a
   mode that is `amount` **or `distance`**, its own `min_order_price`, and its own
   time windows, unrelated to the peak windows. The fifteen branch rows without one
   were not written by this schema at all; they predate it or come from another
   writer. That is a fact about the population's history, not about the key being
   optional. This ADR had a threshold waiver (which asks about the basket) and a
   promotion benefit (which arrives from ADR 0018), and neither asks about the
   clock or the distance, so neither could express any discount the legacy carries.
4. **Fees are rounded to the nearest 500 so'm, with Python's `round`, which is
   half to even.** This ADR assumed whole minor units and half up. On a 500 step
   with round rates a great many fees land exactly on a half-step, and there the
   two rules differ by 500 so'm — 1,250 becomes 1,000 in the legacy and would have
   become 1,500 here.
5. **Top-level `min_order_price` is read by no code**, in the reader or anywhere
   else in that backend. Neither is the one inside `discount`. Migrating either
   into this ADR's step 7 minimum — which *is* enforced — would impose a refusal on
   branches that have never been subject to it.

There is also a second radius. `vendors.visibility_distance` decides whether a
branch is offered at all, and `delivery.max_distance` decides whether it will
deliver; only the second is this tariff's reach. Folding them together is how a
branch quietly stops being listed.

**What survives unchanged.** Deterministic zone selection — priority, then
smallest area, then id — was right and is untouched. So is the refusal to build a
zone from a branch with no coordinate, including the (0, 0) case. So is every
refusal outcome, the evidence row, the separation of customer fee from provider
cost, and the rule that a missing rate table and free delivery must never look
alike. The correction is confined to the arithmetic inside a rate table.

**The correction is V0032, forward-only.** V0025 is committed and pushed and stays
as it is. A rewrite would leave a schema that looks as though it was always right,
and the most useful thing recorded here is that it was not.

## Tariffs, bands, and distance

A tariff is a named, versioned rate table bound to zones and to locations. It
holds one or more **band sets**. `BASE` is in force unless a time rule names
another. Each set must tile `[0, max_distance_meters)` on its own with no gap and
no overlap, checked at activation — a gap is what makes 4,700 m unpriceable while
4,600 m and 4,800 m both price fine, and nobody finds that until a customer
reports it. A peak set with a hole is the same fault confined to a four-hour
window, which makes it harder to find rather than less serious.

**Bands accumulate.** `base_minor` is the flat charge for *entering* a band, not
the cumulative charge for reaching it.

```text
rule     = highest-priority matching time rule, or none
set      = rule.band_set, or BASE
gross    = Σ over every band of `set` the journey enters, of
             band.base_minor + charge(metres covered inside that band, band.per_km_minor)
charge   = ceil(metres / 1000) * per_km          when accrual = STARTED_KILOMETRE
         = metres * per_km / 1000                when accrual = PRORATED_METRE
fee      = round_half_up(gross * rule.multiplier_bp / 10000) + rule.surcharge_minor
fee      = round_to_step(fee, tariff.fee_rounding_step_minor, tariff.fee_rounding_mode)
fee      = clamp(fee, tariff.min_fee_minor, tariff.max_fee_minor)
discount = highest-priority matching tariff discount, rounded on its own,
           capped at fee
```

Four orderings are stated because two implementations that disagree on any of them
produce two defensible fees for one address. The multiplier applies **before** the
surcharge — a peak surcharge is a flat addition, not something the multiplier
compounds. The rounding step applies **after** the rule and **before** the clamp,
so a cap is exactly the cap rather than a number the step could push past it. The
discount is computed from the gross band charge, rounded on its own and then
capped, so the fee and the discount shown to the customer still add up to the
total. And intermediate amounts are carried in thousandths of a minor unit, never
a double, because a tariff that rounds each band separately charges a different
total from one that rounds once.

**The accrual and the rounding step are per tariff, not per codebase.**
`STARTED_KILOMETRE` — a customer 3,100 m away paying for a whole further kilometre
is explainable at the door, a fraction of a som is not — remains the default and
the recommendation for a tariff authored today. `PRORATED_METRE` with a 500 so'm
`HALF_EVEN` step is what a migrated legacy branch gets, because that is what it has
been charging. At 3,100 m on a 2,000 so'm rate the two differ by 1,800 so'm, which
is why this is a column and not a constant.

**A time rule may replace the rate table, surcharge it, or both.** `band_set` puts
a whole set in force for the window; the multiplier and surcharge adjust whatever
set is in force. Rules carry a day-of-week mask and a local-time window evaluated
in the location's IANA timezone at quote creation. Windows are half-open and do
not wrap midnight; a wrapping window is two rules.

**A tariff may carry its own discounts**, each with a day mask and a window, and
each either an `AMOUNT` off or a `DISTANCE_ALLOWANCE` — "the first N metres are
free", priced by whichever band set is in force, so an allowance keeps its value
during a peak window. It is capped at the fee, always: two independent reductions
that can each exceed the fee sum below zero. This is not the step 8 waiver and not
the step 9 benefit; those ask about the basket and this asks about the clock and
the distance, which is why it resolves in `fulfillment` with the fee rather than in
the pipeline.

An illustrative Tashkent tariff: 10,000 so'm to 3 km, 2,000 so'm per further
kilometre, a 5,000 so'm surcharge from 18:00 to 22:00, capped at 40,000 so'm.

**`max_distance_meters` is half-open**, like the bands: the tariff prices
`[0, max_distance_meters)` and refuses at and past it. An inclusive reach over
half-open bands leaves exactly one unpriceable metre at the boundary, which is the
fault the tiling rule exists to forbid everywhere else. A legacy branch whose
`max_distance` was inclusive imports as that value plus one, so nothing it used to
serve stops being served.

**Distance mode is per tariff.** `RADIUS` is haversine from the location point and
needs no provider. `ROAD` requires a routing binding under ADR 0026 and is refused
at activation if none exists. On a routing timeout the resolver falls back to
`RADIUS × road_factor_basis_points`, records `distance_source = RADIUS_FALLBACK`,
and increments a metric — it never fails the quote and never silently charges road
prices for straight-line distance. The detour factor is a platform default that
must be calibrated per city; Qoida has not measured it. Distance, mode, source,
and provider are all stored, because a fee that cannot be re-derived from a
recorded distance is a fee nobody can defend to a tenant.

## Fee resolution order

1. **Pricing authority.** An order whose `ordering.orders.pricing_authority` is
   `EXTERNAL` — ADR 0040's column, seeded when the order is created from ADR 0036's
   `sales_channels.externally_priced` default — does not enter fee resolution at
   all. Uzum Tezkor sets its own delivery price, and that price arrives inside the
   totals the partner supplied, as a fee line Qoida stores verbatim and never
   resolves. This ADR originally spent a third fee source, `fee_source =
   EXTERNAL_CHANNEL`, on the same case; **it is withdrawn.** A tariff column is the
   wrong place for the gate, because it makes the answer depend on which tariff the
   zone happened to resolve to: an externally-priced order reaching a zone whose
   tariff says `TARIFF` would be charged a Qoida-computed fee on top of the fee the
   aggregator already collected, and the order would still reconcile against its own
   stated total. The authority flag is on the order, is checked before resolution
   starts, and is the only thing consulted.
2. **Serviceability.** The address point must fall inside an `ACTIVE` `DELIVERY`
   zone bound to the chosen location, or `OUT_OF_ZONE`.
3. **Zone selection.** Rank the containing candidates as above.
4. **Rate table.** The zone's tariff, else the location's, else the brand default,
   else `NO_TARIFF` and the quote is refused. No implicit zero: a missing tariff
   and free delivery must never look alike.
5. **Distance gate.** Beyond `max_distance_meters`, `BEYOND_MAX_DISTANCE`, even
   inside the polygon. A generously drawn district polygon always contains a house
   no courier will serve at the district price.
6. **Computation.** The formula above, or under `PROVIDER_QUOTE` the non-binding
   ADR 0014 pre-quote clamped by the same min and max. With no provider quote
   available, fall back to the tariff and record it — never to zero. The tariff's
   own discount is resolved here too, capped at the fee, and reported beside it
   rather than subtracted into it: a fee stored net cannot be told apart from a
   cheaper tariff, and six weeks later that is the whole question.
7. **Minimum basket.** Below the zone minimum, checkout is refused with
   `BASKET_BELOW_DELIVERY_MINIMUM` and the quote still returns the shortfall so
   the storefront can say how much more is needed.
8. **Threshold waiver.** The zone's `free_delivery_from_minor`, as a
   `DELIVERY_FEE_WAIVER` adjustment at stage 6.
9. **Promotion benefit.** After the waiver, capped at the remaining fee, so two
   waivers never sum below zero. A free-delivery grant landing on an
   already-waived fee is released rather than consumed and recorded
   `NOT_APPLIED`, so the customer keeps a grant they got no value from.

Steps 7 and 8 both compare against the post-discount goods subtotal, excluding the
delivery fee and any service charge. Comparing against a total that includes the
fee makes the fee oscillate: adding it crosses the threshold, which removes it,
which uncrosses the threshold. The waiver is an adjustment rather than a fee
computed as zero at stage 5, because a zero with no adjustment cannot be told
apart from a broken tariff lookup. Steps 1 to 6 run in `fulfillment`; steps 7 to 9
run inside ADR 0018's pipeline. The tariff discount is decided at step 6, with the
fee, and applied on the delivery line beside the waiver — the waiver reduces what
the discount left, never the gross, or two reductions that each know only the gross
charge sum past it.

A failure at step 2 consults `delivery.out_of_zone_policy` from ADR 0030:
`REJECT` (default) refuses delivery for this location with the reason code;
`OFFER_PICKUP` refuses and offers pickup at the same location; `MANUAL_REVIEW`
holds the cart so Operations may approve a manual fee with a reason, audited.
`CATCHMENT` zones add the equivalent of Delever's *не принимать заказы из других
зон доставки*: when enabled, an address outside the location's catchment is
refused even if a shared city-wide delivery zone covers it — without it, one
branch accepts an order from the far side of Tashkent. Serviceability search
("which of this brand's locations can deliver here") is a separate explicit query
returning candidates with their fees. It is a screen, not a resolution step.

## Physical model

```text
fulfillment.service_zones
  id, tenant_id, brand_id, zone_role (DELIVERY|CATCHMENT)
  code, display_name_ru/uz/en, status, timestamps
  unique(tenant_id, brand_id, code)

fulfillment.service_zone_versions
  id, tenant_id, zone_id, version, status
  area geography(MultiPolygon,4326), authoring_shape jsonb
  priority, area_sq_meters, currency
  delivery_tariff_id null, free_delivery_from_minor null, min_basket_minor null
  activated_by, activated_at, retired_at null
  unique(zone_id, version); partial unique(zone_id) where status='ACTIVE'
  GiST index on area

fulfillment.zone_location_bindings
  tenant_id, brand_id, zone_id, location_id, valid_from, valid_until null

fulfillment.regions
  id, tenant_id null, code, display_name_ru/uz/en, centre_lat, centre_lon
  bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon, status, version

fulfillment.delivery_tariffs
  id, tenant_id, brand_id, code, name, is_brand_default, status

fulfillment.delivery_tariff_versions
  id, tenant_id, tariff_id, version, status, currency
  fee_source (TARIFF|PROVIDER_QUOTE)
  distance_mode (RADIUS|ROAD), road_factor_basis_points, routing_provider_installation_id
  max_distance_meters (half-open), min_fee_minor, max_fee_minor null
  distance_accrual (STARTED_KILOMETRE|PRORATED_METRE)          -- V0032
  fee_rounding_step_minor null, fee_rounding_mode null          -- V0032, paired

fulfillment.delivery_tariff_bands
  tariff_id, tariff_version, band_set, sequence                 -- band_set: V0032
  from_meters, to_meters, base_minor (this band's own), per_km_minor

fulfillment.delivery_tariff_time_rules
  tariff_id, tariff_version, sequence, priority, day_of_week_mask
  local_from_time, local_to_time
  band_set null                                                 -- V0032: substitution
  multiplier_basis_points, surcharge_minor

fulfillment.delivery_tariff_discounts                           -- V0032
  tariff_id, tariff_version, sequence, priority
  discount_kind (AMOUNT|DISTANCE_ALLOWANCE)
  amount_minor null, allowance_meters null (each paired to the kind)
  day_of_week_mask, local_from_time, local_to_time

fulfillment.delivery_fee_resolutions
  id, tenant_id, quote_id, location_id, resolution_version, outcome, reason_code
  zone_id, zone_version, tariff_id, tariff_version (all null on refusal)
  band_sequence, time_rule_sequence, discount_sequence null
  distance_meters, distance_mode, distance_source, routing_provider null
  provider_quote_minor null, computed_fee_minor, final_fee_minor
  tariff_discount_minor null (0 <= it <= final_fee_minor)       -- V0032
  losing_zone_ids uuid[], evidence jsonb, created_at
```

Bands, time rules and discounts hang off a *version*, not off the tariff. ADR
0037's first physical sketch keyed them by `(tariff_id, sequence)`, which cannot
meet this ADR's own exit criterion: reconstructing a months-old fee "without
executing today's rates" is impossible if editing a band rewrites the rows the old
resolution points at.

Composite foreign keys carry tenant and brand ancestry, so a zone cannot bind a
location outside its brand. `delivery_fee_resolutions` is the delivery-side twin
of `pricing.quote_adjustments`: normalized columns for reconciliation, JSONB
beside them as evidence, never JSONB as the model.

## Contract with ADR 0018 and ADR 0014

This extends ADR 0018 rather than changing it. Stage 5 consumes a
`ResolvedDeliveryCharge` produced before the engine runs; stage 6 applies the
waiver and benefit. Zone version, tariff version, band, time rule, and distance
all enter the quote context hash, so a zone edit or a peak-window boundary
invalidates an in-flight quote with `PRICE_CHANGED` exactly as a price-book edit
already does.

It corrects one gap in ADR 0014: the fee is **copied** into
`fulfillment.delivery_plans.customer_delivery_fee_minor` from the accepted quote
and never recomputed at plan creation, because recomputing there is precisely how
the quoted and planned fees diverge. It is also part of the single ADR 0013
payment intent covering the order total, never a second charge — two intents mean
two refunds and an order that reconciles to neither.

## APIs and events

```text
GET  /api/v1/storefront/delivery/serviceability?locationId&lat&lon
POST /api/v1/control-plane/brands/{brandId}/service-zones
POST /api/v1/control-plane/service-zones/{zoneId}/versions
POST /api/v1/control-plane/service-zones/{zoneId}/versions/{version}/activate
POST /api/v1/control-plane/brands/{brandId}/delivery-tariffs
POST /api/v1/control-plane/delivery-tariffs/{tariffId}/simulate
GET  /api/v1/operations/quotes/{quoteId}/delivery-fee-evidence
POST /api/v1/operations/carts/{cartId}/manual-delivery-fee

ServiceZoneVersionActivated / Retired · DeliveryTariffActivated
DeliveryFeeResolved · DeliveryAddressRejectedOutOfZone
```

`simulate` runs the full resolver against a supplied point and basket with a fixed
clock and writes nothing — the control plane must answer "what would this cost
from Chilonzor at 19:00" before activation, not after a customer finds out.
`manual-delivery-fee` requires a reason and is audited. `DeliveryFeeResolved`
carries zone, tariff, band, distance, and outcome, and no address text or
coordinates: the reconciliation and heat-map consumers need the zone, not the
doorstep.

## Testing

- **Legacy parity is the load-bearing test.** A line-by-line transcription of
  `calculate_delivery_price.py` — its floating-point arithmetic, its half-to-even
  500 so'm rounding, its free tail when the step list runs out, and the off-by-one
  in its distance-discount branch — is the oracle. Every migrated configuration is
  swept across every servable distance and every quarter hour of the day and must
  agree with it to the som. A golden test at three distances passes against a model
  that is wrong everywhere in between, and "wrong everywhere in between" is exactly
  what a surcharge standing in for a substituted rate table looks like. The same
  comparison runs once end to end: legacy JSON through the importer, through the
  database, through the resolver, checked against the oracle at the distance the
  resolver measured.
- One deliberate divergence, asserted rather than left implicit: the legacy's
  distance discount uses `> base_distance` where its fee path uses `> 0`, so an
  allowance between one and two base distances loses its per-kilometre part. Qoida
  computes the allowance properly, which discounts more. It is a bug in the legacy,
  it moves in the customer's favour, and reproducing it would mean carrying an
  off-by-one in a rate table forever.
- A discount with no time windows imports as no discount, because the legacy reader
  only ever applies one inside its window loop; and `min_order_price` imports as
  nothing at all.
- Golden tests fix clock, point, basket, and tariff version and assert the exact
  band, time rule, distance, and fee.
- A peak window's band set prices the fee, and the evidence row names which set was
  in force — otherwise "why was this 19,000 when the bands say 15,000" has no answer
  on the row.
- Overlapping zones of equal priority and equal area resolve identically across a
  thousand runs and across a `VACUUM FULL`.
- Band tiling validation rejects a gap and an overlap at activation, once per band
  set, and rejects a band set no time rule can put in force.
- A routing timeout produces a `RADIUS_FALLBACK` fee, not a failed quote, and the
  fallback is visible in the evidence row.
- A basket one som below the threshold pays the fee and does not oscillate, and a
  free-delivery benefit on an already-waived fee is released, not consumed.
- An address outside every zone of the chosen location is refused, and no code
  path substitutes another location.
- `PROVIDER_QUOTE` above `max_fee_minor` charges the cap and records the balance
  as a subsidy candidate, never as a higher customer fee.
- An order with `pricing_authority = EXTERNAL` never invokes fee resolution, and
  no tariff configuration can reintroduce it — the check runs before a zone or a
  tariff is looked up.
- Cross-tenant zone-to-location bindings fail at the database.

## Rollout and rollback

Import legacy zone JSON into `DRAFT` versions and render every one on a map beside
its source before activation, because a coordinate-order error produces a polygon
in the wrong hemisphere that no containment test complains about. Import each
branch's `vendors.delivery` through the one pure mapping function, so the migration
wave can run it over every row and diff the answer against the legacy reader before
a single tariff is activated. Run the resolver in shadow against captured
historical orders and explain every fee mismatch before enabling a location. Roll out `RADIUS` first everywhere, `ROAD` only once a
routing binding is verified, and `PROVIDER_QUOTE` only for a tenant that asked for
it in writing. Rollback retires zone versions rather than deleting them and pins
resolution to a flat per-location tariff; accepted quotes keep their fee.

## Consequences

### Positive

- One place answers "why was this delivery 18,000 so'm", with a zone version, a
  tariff version, a band set, a band, a time rule, a discount and a distance.
- A migrated branch charges what it charged, provably, at every distance and every
  hour rather than at the three points a golden test happens to pin.
- Charged fee and provider cost are separated by construction, so the
  reconciliation Delever needed a report for becomes a join.
- A zone edit cannot retroactively change what a past order was charged.
- Stages 5 and 6 of the ADR 0018 pipeline stop being a comment in
  `PricingEngine`, and `PricingEngine` stays a pure function.

### Negative

- PostGIS is a new extension and a new operational dependency on a self-hosted
  PostgreSQL that ADR 0034 already describes as under-resourced. Backups,
  restores, and version upgrades now include a spatial extension.
- Every geometry edit is an approval and an audit fact. Operators who expect to
  nudge a polygon and save will find that annoying, and will occasionally leave a
  corrected zone sitting in `DRAFT`.
- `ROAD` mode puts an external routing call on the checkout path, with a cache, a
  timeout, a fallback, and a metric someone has to watch.
- The control-plane surface is large: polygon drawing, a band table, a time-rule
  table, and a simulator. ADR 0035 lists `MapCanvas` and `PolygonEditor` as
  components the design system does not have at all.
- Two fee sources, a waiver, a benefit and now a tariff discount give the delivery
  fee more branches than any other number in a quote, and every branch needs a
  golden test.
- The rate table is materially larger than the one first designed: a set of bands
  per peak window, an accrual mode, a rounding step and mode, and a discount table.
  A control-plane editor has to render all of it, and an operator authoring a
  tariff by hand can now choose a rounding rule that exists only to reproduce a
  Python built-in.
- `HALF_EVEN` and `PRORATED_METRE` are legacy-compatibility settings living in the
  same columns operators author new tariffs with. Nothing stops somebody picking
  them for a new branch, where they are simply worse; only the column comments say
  so.

### Accepted trade-offs

- Tariff pricing over pass-through means the tenant carries surge risk as a
  subsidy. A published, stable delivery price is worth more to conversion than
  perfect cost recovery on the worst-priced orders.
- Refusing to substitute a covering location loses some orders a silent fallback
  would have captured. A confirmation naming a restaurant the customer did not
  choose costs more.
- A circle is stored twice, as geometry and as authoring shape. The alternative is
  an editor that turns every circle into an unmovable polygon on first save.
- Legacy quirks are carried where they are policy and dropped where they are bugs,
  and the line between the two is a judgement this ADR makes rather than a rule it
  derives. A 500 so'm half-to-even rounding is what a branch has been charging, so
  it is carried; a discount that loses its per-kilometre part inside one range is a
  defect, so it is not. Both decisions are pinned by a test that names them.

## Implementation checklist

- [x] Enable PostGIS; add zone, region, tariff, band, time-rule, and resolution tables via Flyway.
- [ ] Implement zone versioning, activation approval, and geometry validation.
- [x] Implement `DeliveryFeeResolver` with the total order above and full evidence capture.
- [x] Implement `RADIUS` distance and the provider-neutral routing port with fallback.
- [x] Extend `QuoteService` to resolve the charge and feed stages 5 and 6.
- [x] Add the delivery-fee quote line with a fiscal classification placeholder.
- [ ] Implement serviceability search, out-of-zone policy, and catchment guard.
- [x] Implement control-plane zone and tariff APIs and the simulator.
- [x] Correct the rate-table model against the legacy writer and reader (V0032):
      accumulating bands, band sets a time rule can substitute, per-tariff accrual
      and rounding, and the tariff's own discount.
- [x] Build the pure legacy-configuration import and prove it against a
      transcription of the legacy reader, in memory and through the database.
- [ ] Build legacy zone import with map rendering and shadow comparison.
- [x] Add golden, tiling, overlap, fallback, waiver, and isolation tests.

## Exit criteria

For any address and basket, Qoida returns one delivery fee, refuses to return one
when the address is not serviceable, and can reconstruct that fee months later
from a pinned zone version, a pinned tariff version, a recorded distance, and a
recorded distance source, without executing today's geometry or today's rates. The
fee the customer agreed to is the fee on the delivery plan, and any gap between it
and the provider's invoice exists as a subsidy record rather than a discrepancy.
And a branch that moves from the legacy system to Qoida charges its customers the
same number on the day of the move, at every distance it serves and every hour it
is open, with the one exception this ADR names and defends.
