# ADR 0147: Road distance and routing provider

- Decision status: Proposed
- Implementation status: Not started — no routing provider exists and a `ROAD`
  tariff prices as if it were a `RADIUS` one. `DeliveryRoutingConfiguration`
  registers a `RoadDistancePort` that answers empty on every call, on purpose
  ("not a stub that invents a plausible number"), so `DeliveryFeeResolver.measure`
  falls through to `straight line × road_factor_basis_points`, records
  `distance_source = RADIUS_FALLBACK` and increments
  `horecaos.delivery.distance.fallbacks`; the fee is never failed for a routing
  problem. What exists around that: `DistanceMode.ROAD` on
  `fulfillment.delivery_tariff_versions` with `road_factor_basis_points`
  (default 13000, CHECK ≥ 10000, V0025) and `routing_provider_installation_id`;
  `DeliveryTariff.activationProblems` refusing a `ROAD` tariff with no
  installation ("ROAD distance needs a routing binding (ADR 0026)"); the
  `routing_provider` column on `delivery_fee_resolutions`; `LegacyTariffImport`
  refusing to import a legacy branch as anything but `ROAD` with a routing
  installation, because the legacy measured through its map SDK; the courier
  module mapping `ROAD` to `DistanceSource.ROUTING` as evidence. What does not
  exist: a routing adapter, a `ROUTING` value in `ProviderCategory` (the enum has
  `POS`, `PAYMENT`, `DELIVERY`, `MARKETPLACE`, `NOTIFICATION`, `GEOCODING`,
  `VOICE`, `ANALYTICS`, `OTHER`), an approved routing endpoint in
  `integration.provider_environments`, a cache or timeout on the call, a duration
  in the port's answer (it returns metres and a provider name only), and any
  value in `ordering.orders.promise_travel_minutes` for a delivery order —
  `CheckoutOrderWriter` passes `null`, "travel is null rather than zero".
- Date proposed: 2026-10-01
- Date decided: —
- Deciders: proposed by Claude (wave batch 17); Ayubkhon Abbosov (platform owner)
  decides
- Depends on: ADR 0007, ADR 0014, ADR 0015, ADR 0026, ADR 0030, ADR 0033,
  ADR 0034, ADR 0037, ADR 0042, ADR 0061
- Supersedes / Superseded by: — (closes the open input "Routing provider for road
  distance and the per-city detour factor" on ADR 0037; does not reopen ADR 0037's
  distance modes, its fallback rule or its evidence requirements)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written.
  - **Whether the production VM has room for a routing engine** (engineering,
    operations). ADR 0073 (Proposed) sizes the machine at 16 GB with limits that
    already total about 13 GB. No figure here is measured: the planning ceiling of
    2 GB for a country-sized dataset is an assumption to be checked on the pilot
    machine before anything else is built. Proposed default: if it does not fit,
    this record's second choice (a hosted API) applies and the rest of the record
    is unchanged.
  - **The accuracy gate** (product, operations). Proposed default: against a
    reference of 100 trips between 1 and 10 km, the engine's median absolute
    deviation is at most 10% and its 95th percentile at most 25%. The reference is
    route distance from a second source for the same endpoints, or the GPS track
    of delivered orders; the track is evidence for the gate and never pays a
    courier (ADR 0042).
  - **The detour factor per region** (product, finance). ADR 0037: "the detour
    factor is a platform default that must be calibrated per city; Qoida has not
    measured it." Proposed default: 1.30 stays until the same 100-trip sample
    yields a measured median of road distance over straight line for Tashkent, and
    the measured value then replaces it as the region's default.
  - **Whether any tenant needs fee parity with legacy road distances** (business).
    Proposed default: no. ADR 0055 makes the launch greenfield, and
    `LegacyTariffImport` keeps its refusal for the later migration programme.
  - **Whether promises need traffic-aware durations** (product). Proposed
    default: not in this record. Durations are returned and labelled free-flow.
  - **Who publishes the routing dataset, and how often** (operations). Proposed
    default: built in CI monthly, published as a pinned image, deployed by tag.

**To accept as written:** say "accept 0147". Every open input above is then
closed on its proposed default.

## Context

Gap-map row `3.7` says every authoring feature of the delivery tariff screen is
built, and that "`ROAD` distance mode still silently prices as radius, since ADR
0037's `RoadDistancePort` answers empty." The pricing half is accurate. The
"silently" half has since been answered on the screen: `delivery-tariffs-page.ts`
marks every active `ROAD` version as pricing from an inflated straight line
(`fallsBackToRadius`), and every fee row says `RADIUS_FALLBACK`. That notice is
unconditional because the port is, and it will be wrong the day an adapter
answers; making it follow what actually happens is part of this record's work.

Four things in the platform want a road figure, and they want different ones.

| Consumer | Wants | Where it sits | Failure it tolerates |
|---|---|---|---|
| The delivery fee (ADR 0037) | metres, once per quote | the customer's checkout and every cart re-quote | none visible: it falls back and says so |
| The promise's travel component (ADR 0037, ADR 0041) | seconds | `ordering.orders.promise_travel_minutes`, today null; the kitchen subtracts it from the promise to set `target_ready_at` | a missing value is "travel not modelled", not zero |
| Courier accrual (ADR 0042) | the plan's metres, as evidence | `DistanceSource.ROUTING` versus `HAVERSINE_FACTORED` | none: it is evidence only, pay reads the plan's figure |
| Dispatch grouping and ranking (ADR 0142) | a drop-to-drop distance | `FleetCandidate` ranking | straight line is adequate |

The first is the one with a row. It is also the only one on the customer's
critical path, which is why ADR 0037 insists the quote must never fail for a
routing timeout.

**The legacy measured road distance through its map SDK.** `LegacyTariffImport`
says so and refuses the shortcut: "importing a branch as RADIUS would shorten
every journey by the detour factor and drop every fee — quietly, and in the
direction nobody audits." A routing provider is therefore also a migration
prerequisite, and not for the launch (ADR 0055 is greenfield).

**What a road distance is worth in money.** A `ROAD` tariff charges per band of
distance, and ADR 0042 pays a courier on the plan's distance. Both change when the
engine, its dataset or its profile changes. So the figure must be reproducible
(the fee evidence has to say which dataset produced it), stable within a quote
(an issued quote must not re-price because a dataset was refreshed overnight),
and attributable (`routing_provider` is already stored).

**Why this is not the geocoder's decision.** ADR 0145 chooses a map and
geocoding provider on a different set of axes (address quality, display
licence, tiles). Routing differs in three ways that move the answer: the input
is two coordinates the platform already holds rather than text, the call is on the
checkout path where a foreign round trip is a cost, and the licence question
that decides geocoding — may we store results — matters little when a distance
is stored as a number on a fee row. A platform can reasonably run a hosted
geocoder and a self-hosted router, and this record assumes it might.

**The candidates.**

| Axis | OSRM, self-hosted | Yandex routing / distance matrix | 2GIS routing | Google Routes |
|---|---|---|---|---|
| Road network | OpenStreetMap; quality in Uzbekistan unmeasured (one-ways, courtyards, new districts are the risks) | Yandex's own network; the legacy's source of truth | 2GIS's own; its routing pages list Uzbekistan among truck-routing territories, car coverage to be confirmed | Google's own |
| Duration | Free-flow from profile speeds; optimistic at rush hour | Traffic-aware | Traffic-aware (product dependent) | Traffic-aware |
| Where the call terminates | Our VM, in-country | Outside | Outside | Outside |
| Latency on the checkout path | Local, single-digit to low tens of ms | A foreign round trip | A foreign round trip | A foreign round trip |
| Cost | No per-request fee; RAM, disk, and operating a stateful service | Per request; billing route from Uzbekistan unknown | Billed per route built | Per request; billing unverified |
| Reproducibility | Deterministic for a given dataset version | Changes with the vendor's map and traffic | Changes | Changes |
| Stored-result rights | Ours (ODbL attribution applies to the data) | A licence question (30-day cache on comparable Yandex terms) | Not stated on the pages read | Restrictive on comparable Google terms |
| Operational burden | A container and a monthly dataset build | None | None | None |

The public OSRM demo server is out of the question for production: its own
usage page restricts it to "reasonable, non-commercial use-cases", one request per
second, and no guarantee of uptime.

## Decision

**Self-host OSRM as the first road-distance adapter, behind a port that returns
distance and duration with the dataset that produced them; keep a hosted routing
API as the second adapter, to be built when a measured trigger fires.**

1. **The port grows an answer, not a method.** `RoadDistancePort` keeps its shape
   and `RoadDistance` becomes `RoadRoute(meters, seconds, provider, datasetVersion)`,
   where `seconds` is documented as a free-flow estimate. An empty answer still
   means "routing did not answer" and still falls back; the resolver is not
   changed. The courier module, the fee evidence and the promise all read the same
   value and none of them is told it is more than it is.

2. **Distance for pricing, duration for the promise, and they are calibrated
   separately.** The fee uses metres. The promise's travel component uses
   `seconds` scaled by a policy-resolved factor (ADR 0030) whose default is 1.0
   and which is expected to be raised from measurement; wiring that into
   `promise_travel_minutes` is the remaining ADR 0037 item and is not decided
   here beyond "the duration exists to be used".

3. **A routing engine is an ADR 0026 installation of a platform-approved
   environment, not a hidden service.** A `ROUTING` value joins
   `ProviderCategory` and both CHECK constraints (the V0145 precedent restates
   them in full); an approved environment `osrm_internal` carries the internal
   base URL and `provider_type = OSRM`. ADR 0037's rule — a `ROAD` tariff needs an
   installation — is kept unchanged: choosing `ROAD` in the tariff editor offers
   "use platform routing", which creates the keyless installation for the tenant in
   the same action. No credential screen, no secret, no new rule.

4. **The call is bounded and the engine is breakable.** A total timeout of 500 ms
   on the checkout path, a circuit breaker (unlike the SMS route, the fallback here
   is graceful, so an open breaker costs a slightly wrong fee, not a failed
   sign-in), and a cache (ADR 0033) keyed by installation, branch, destination
   rounded to four decimal places (about 11 m) and the dataset version, with a
   24-hour TTL that a new dataset version invalidates. The cache is a performance
   aid and never an authority: the fee row stores the metres it used.

5. **The dataset is built off the host and shipped pinned.** The `deploy/` tree
   (ADR 0061) runs pinned images pulled from a registry with no server-side
   build, and the dataset follows the same rule. The Uzbekistan extract is
   preprocessed (MLD, car profile) in CI monthly and published as an image tagged
   with its extract date, deployed by tag exactly as the application is. The tag
   is the `datasetVersion`. The engine image is pinned by digest.

6. **A quote keeps the fee it was issued with.** `QuoteService` already puts the
   resolved delivery charge in the quote's context hash, and acceptance compares
   that hash, so a dataset refresh changes what the *next* quote measures and
   never what an issued quote or an open order says. Nothing is added to the hash;
   the distance and dataset version behind the charge live on the
   `delivery_fee_resolutions` row the charge points at.

7. **A measured gate decides whether the engine is good enough, and it also
   measures the detour factor.** The same 100-trip reference sample that tests the
   engine yields a median road-over-straight-line ratio for Tashkent, which
   replaces the unmeasured 1.30 as the region's default for the fallback path.

8. **The hosted API is the second adapter, not the first, and has named
   triggers.** It is built when (a) a promise needs traffic-aware durations, or
   (b) the OpenStreetMap network fails the gate for a part of the city in a way
   that matters to fees, or (c) the VM cannot hold the engine. It would be the same
   `ROUTING` category with another `provider_type`, and would need its own
   contract read and route descriptor.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A hosted routing API first (Yandex, 2GIS or Google) | Traffic-aware and no service to run, but it puts a foreign round trip and a per-request fee on every quote, sends a customer's coordinates out of the country, makes fees drift with the vendor's map, and has a billing problem ADR 0073 already names | The VM cannot hold an engine, a promise needs traffic, or the gate fails |
| Yandex specifically, for parity with the legacy's road distances | Legacy tariffs were calibrated on Yandex figures; matching them removes the fee shift a migrating tenant would see. But the launch is greenfield and parity is a migration-programme concern | A tenant is migrated with a `ROAD` tariff and parity is required |
| Valhalla or GraphHopper self-hosted | Also OpenStreetMap-based and richer (time-dependent costing, isochrones). More to run for a need that is point-to-point distance | An isochrone or time-of-day routing requirement appears |
| Straight line × a factor, permanently | Free and already works. It is wrong across the city's canals, rail lines and one-way systems, and the evidence row says so on every fee | Never as the `ROAD` mode; it remains the `RADIUS` mode and the fallback |
| A precomputed distance grid or zone-to-zone table | Deterministic and cheap, but only for branch-to-zone figures; an address's distance is what the band prices | Tariffs move to per-zone flat fees |
| The public OSRM demo server | Its own policy forbids production commercial use and promises nothing | Never |
| Compute road distance in the customer's browser through the map SDK, as the legacy did | Puts a money figure in client code and a vendor in the price path | Never |

## Consequences

### Positive

- A `ROAD` tariff charges for the road, and the row `3.7` gap closes in the
  console with one new button and a fallback notice that tells the truth.
- Checkout latency and availability stay inside the platform; the call has a
  fallback that is already tested and already recorded on every fee.
- Fees are reproducible: dataset version in the evidence, distance in the quote.
- Customers' coordinates never leave the country for routing, which is ADR 0034's
  position kept rather than argued.
- The detour factor, unmeasured since ADR 0037, gets a number.

### Negative

- One more stateful service on a single-operator, memory-tight machine, and one
  more artifact pipeline (extract, preprocess, publish, deploy) that can break
  quietly.
- OpenStreetMap errors become fee errors. A missing one-way or a courtyard that is
  not a road changes a distance by hundreds of metres, and a band edge turns that
  into a price. The gate bounds the average, not the worst street.
- Free-flow durations flatter the kitchen's promise at rush hour. Until a factor
  is calibrated or a traffic source exists, the travel component is optimistic and
  says nothing about it.
- A monthly refresh moves fees slightly without anyone editing a tariff. The quote
  rule contains the customer-visible effect; a tenant comparing two weeks of fee
  reports will still see it, and the dataset version on the evidence is what
  explains it.

### Accepted trade-offs

- The engine may be replaced by a hosted API after the first measurement shows
  OpenStreetMap is not good enough here; the port and the installation model make
  that an adapter.
- Fees may differ from what a migrating tenant's legacy system charged, by design.
- Memory spent on routing is memory not given to PostgreSQL's cache; the open
  input exists to make that a measured choice.

## Specification

### Port

```text
RoadDistancePort.route(origin: GeoPoint, destination: GeoPoint, installationId?) -> Optional<RoadRoute>
RoadRoute(meters: int, seconds: int, provider: String, datasetVersion: String)
```

Empty means no answer in time or no route; it is never a fabricated number. The
resolver's fallback, metric and evidence are unchanged.

### Adapter and route

`OsrmRoadDistanceAdapter` calls the engine's `route` service for one origin and one
destination with `overview=false` and no geometry, through `ProviderHttpClient`
and the ADR 0007 machinery, with the timeout and breaker of Decision 4. Provider
JSON never leaves the adapter. A route descriptor under `docs/routes/` states the
timeout, the absence of retries on the checkout path, the cache, and that no
personal data is sent: two coordinates and no identifier.

### Persistence (additive; numbers reserved by the wave that builds it)

```text
integration.provider_environments / installations   CHECK restated with 'ROUTING' (V0145 pattern)
integration.provider_environments                   row: osrm_internal, ROUTING, OSRM, internal URL
fulfillment.delivery_fee_resolutions                routing_dataset_version varchar(32) null,
                                                    routing_seconds integer null
```

Grants as `V0035`.

### Deployment

```text
deploy/compose.production.yml   service `osrm`, image pinned by digest, dataset image tagged
                                with the extract date, memory limit set from the measurement,
                                internal network only, no published port
CI                              monthly job: fetch the extract, preprocess, run the sample
                                gate on a fixture, publish the dataset image
```

### Observability

`horecaos.routing.calls{outcome}` (`ok`, `timeout`, `no_route`, `breaker_open`),
`horecaos.routing.cache{result}`, `horecaos.routing.dataset.age_days`, and the
existing `horecaos.delivery.distance.fallbacks`. Alert when fallbacks exceed 5% of
`ROAD` quotes over fifteen minutes, or when the dataset is older than 60 days.

### Testing

- Adapter against a recorded engine response and against the real engine in a
  container with a tiny fixture extract: a known pair returns the known metres;
  an unreachable pair returns empty; a timeout returns empty.
- `DeliveryFeeResolutionTests`: a `ROAD` tariff with a working port records
  `distance_source = ROAD`, the provider and the dataset version; with the port
  empty it records `RADIUS_FALLBACK` as today.
- Cache: same pair twice is one engine call; a new dataset version is a miss; the
  fee row stores metres, not a cache key.
- A quote issued before a dataset change is accepted at the fee it was issued
  with, and the next quote measures against the new dataset.
- Breaker: after repeated timeouts the next call returns empty without waiting.
- Installation: choosing `ROAD` offers "use platform routing" and creates one
  keyless installation; a second request is idempotent.

## Rollout and rollback

Measure the machine and run the gate first, with no product change. Then add the
`ROUTING` category, the approved environment and the adapter behind the port, with
the engine disabled by configuration so nothing changes. Enable it for one pilot
tenant's `ROAD` tariff and compare a week of fees with their `RADIUS_FALLBACK`
shadow. Rollback is disabling the installation: the port answers empty, the
resolver falls back, and every fee says so.

## Implementation checklist

- [ ] Owner accepts the record; the VM measurement and the 100-trip reference
      sample are taken before any build.
- [ ] Flyway: `ROUTING` category (both CHECKs, restated in full), the approved
      environment, the two evidence columns; granted.
- [ ] `RoadRoute` and the port; `DeliveryFeeResolver` stores seconds and dataset
      version; evidence read endpoints expose them.
- [ ] `OsrmRoadDistanceAdapter` with timeout, breaker and cache; route
      descriptor.
- [ ] Tariff editor: "use platform routing"; the unconditional `fallsBackToRadius`
      notice on an active `ROAD` version becomes conditional on the installation
      actually falling back (a recent-fallback read, not the distance mode alone).
- [ ] CI dataset job; `deploy/` service definition; digest pin; memory limit.
- [ ] Metrics and alerts above.
- [ ] Measured detour factor recorded as the region default.
- [ ] Update ADR 0037's status line (routing provider chosen and built).
- [ ] Tests listed under Testing, each seen failing first.

## Exit criteria

A tenant's `ROAD` tariff produces fees with `distance_source = ROAD`, a provider
name and a dataset version on the evidence; the tariff screen's fallback notice
appears only when a `ROAD` tariff is in fact falling back; the gate result and the
measured detour factor are on record; and switching the engine off returns every
fee to `RADIUS_FALLBACK` with no failed checkout.

## References

- ADR 0007, ADR 0014, ADR 0015, ADR 0018 (quote context hash), ADR 0026, ADR 0030,
  ADR 0033, ADR 0034, ADR 0037 (distance modes, fallback, detour factor), ADR 0041
  (promise and `target_ready_at`), ADR 0042, ADR 0055, ADR 0061, ADR 0073, ADR 0142
  (grouping), ADR 0145
- `platform/docs/operations-gap-map.md` row `3.7`
- `RoadDistancePort`, `DeliveryRoutingConfiguration`, `DeliveryFeeResolver`,
  `DeliveryTariff`, `LegacyTariffImport`, `DeliveryAccrualOrderCompletionTrigger`,
  `CheckoutOrderWriter`, `ProviderCategory`; `V0025`, `V0145`
- OSRM demo server usage restrictions (github.com/Project-OSRM/osrm-backend/wiki/Demo-server),
  2GIS Routing API overview (docs.2gis.com/en/api/navigation/routing/overview),
  as read on 2026-10-01; the contract, not these summaries, binds
