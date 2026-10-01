# ADR 0145: Map and geocoding provider

- Decision status: Proposed
- Implementation status: Not started — no map is drawn anywhere in the operations
  console and no geocoder is called by the platform. What exists is the schema
  and the seams, not a provider: `customer.addresses.coordinate_source`
  (`NOT_GEOCODED`, `LANDMARK_ONLY`, `GEOCODER`, `CUSTOMER_PIN`, `OPERATOR_PIN`,
  `LEGACY_UNSOURCED`, V0021) with `GEOCODER` written by nothing and refused from
  the storefront surface; `fulfillment.regions` with the SW/NE bounding box that
  is meant to constrain a geocoder (V0025, editable as four numbers, row `3.6b`);
  `fulfillment.service_zone_versions.area geography(MultiPolygon, 4326)` under
  PostGIS, which `ServiceZoneService` will accept as a polygon through the API
  while the console draws only circles; `integration.provider_installations`
  accepting the category `GEOCODING` (V0013) while
  `ProviderInstallationController.secretCategoryFor` refuses to store a secret
  for it; `GeoPoint` in `tenancy.api`; `RoadDistancePort` answering empty (the
  subject of ADR 0147, not this record). The only live third-party map in the
  repository is outside the platform's control: `frontend/storefront` loads the
  Yandex Maps 2.1 script on its address screen (`locations-add`) and calls
  `geocode-maps.yandex.ru` directly from the customer's browser
  (`GeocodingService`, `YandexMapsService`), and saves what the customer confirms
  as `CUSTOMER_PIN`; `frontend/storefront-milliy` carries the same two services
  and the same key configuration with no screen calling them yet (its checkout
  notes that creating an address "needs the geocoding/marker flow"). Both images
  ship a browser key as a default in `docker-entrypoint.sh` and `public/config.json`. The operations
  console, the control plane and the mobile app have no map at all.
- Date proposed: 2026-10-01
- Date decided: —
- Deciders: proposed by Claude (wave batch 17); Ayubkhon Abbosov (platform owner)
  decides
- Depends on: ADR 0015, ADR 0026, ADR 0028, ADR 0029, ADR 0030, ADR 0033,
  ADR 0034, ADR 0035, ADR 0037, ADR 0007
- Supersedes / Superseded by: — (closes the open input "Geocoder/map provider
  selection (product)" on ADR 0015 and the one it passes to ADR 0037; reopens
  neither record's decision)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **What the Yandex licence actually allows the platform to store, display and
    resell** (legal, finance). The published terms read on 2026-10-01 say the
    standard licence lets results be cached for up to 30 days, an extended
    licence lets them be stored for the licence term, both carry a minimum fee,
    and results may be made available only through the licensee's software on
    its own platform. Whether a licence held by HorecaOS may serve the customers
    of many tenants is not answered by those pages. Proposed default: the
    platform holds one licence, buys the extended tier only if it will persist a
    geocoder-sourced point (Decision 5 persists none at launch), and nothing in
    this record's build depends on the answer. Blocks: only Decision 5's
    optional backfill.
  - **How a licence is paid for from Uzbekistan** (finance). ADR 0073 records
    international contracting and payment as "a real obstacle rather than a
    preference". Unanswered for Yandex, 2GIS and Google alike. Proposed default:
    none; this is the input most likely to decide the provider, and the bake-off
    (Decision 3) is run in parallel with asking it, not after.
  - **The acceptance thresholds of the bake-off** (product, operations).
    Proposed default: at least 90% of a 200-address set resolves within 100 m of
    the surveyed point, 100% of results outside the region's bounding box are
    marked `LOW_CONFIDENCE`, and no result moves a customer's address to another
    city. The set is built from branches, public landmarks and operator-supplied
    surveyed doors, not from customers' saved addresses (ADR 0029).
  - **Whose account the committed storefront browser key belongs to** and
    whether it is referrer-restricted (platform owner). It is shipped as a default
    in two images and, being in git, is shared by every deployment. Proposed
    default: treat it as compromised in the sense that matters (anyone can spend
    its quota), restrict or rotate it when Decision 4 lands, and remove the
    defaults.
  - **Whether geocoding is metered and priced per tenant** (finance, ADR 0021).
    Proposed default: metered from the first call as a usage counter, not priced
    until the licence cost is known.
  - **Attribution on a customer-facing map** (product). Every candidate requires
    some. Proposed default: the provider's own attribution, unmodified, bottom
    corner, as each licence says.

**To accept as written:** say "accept 0145". Every open input above is then
closed on its proposed default.

## Context

Nine gap-map rows are blocked on one unmade decision: `X.4` (MapCanvas,
PolygonEditor, BoundingBoxEditor, MapPin and AddressPicker — the design-system
primitive the rows below consume), `1.3b` (New order's address pane has a
structured address and no pin or search), `3.2` (the live courier map shows
decimal coordinates in a table), `3.6` (a delivery zone is a radius typed into a
form), `3.6c` (bulk geozone upload exists, and activating an imported zone is
deliberately gated behind a map where it can be looked at, per ADR 0037),
`5.2c` (an operator cannot create a saved address with real coordinates),
`10.2b` (a branch pin can be kept or lost, never moved) and `7.10`/`7.10a` (an
order-density view over the zones, and today's orders as pins). Every one of
their "What is missing" notes ends in the same sentence: no provider is chosen,
so there are no tiles, no geocoder and no credential. `3.1`'s dispatch board
also lists "a map of points and routes" as its last gap.

**The notes are right about the platform and wrong about the repository.** The
platform has no provider. The customer-facing storefronts have one, inherited
from the legacy application they were ported from, and it is Yandex (live in
`frontend/storefront`, carried and unused in `frontend/storefront-milliy`). The
storefront's own `GeocodingService` says so in a comment worth repeating: the
legacy backend proxied geocoding at `/customers/addresses/action/geocode`, "the
platform has no equivalent and is not going to grow one by accident", so the
browser calls Yandex's HTTP geocoder directly. Three consequences follow, and
none was decided.

- **The customer's typed address and pin go to a third party from the browser**,
  outside every control the platform has built for personal data. ADR 0034 lists
  what leaves the country and under what rule (a processors table keyed by ADR
  0029 data class); geocoding is not on that list.
- **The key is a default in git.** `docker-entrypoint.sh` supplies a literal key
  when the environment sets none, and `public/config.json` carries the same one.
  A browser key is public by construction (the storefront's own comment is
  correct about that), but this one is also *shared*: every deployment spends
  one account's quota, and rotating it is a code change.
- **Two screens that must agree cannot.** A customer places a pin on a Yandex
  map; an operator, with a different provider's map or none, sees a number. The
  zone the fee resolver (ADR 0037) uses is PostGIS; the picture of that zone is
  whatever the console draws.

**What the platform already decided and this record must not reopen.** ADR 0015
fixed the shape: a provider-neutral `GeocodeAddress` / `ReverseGeocodePoint` port
returning "point, normalized components, provider reference, confidence,
precision, and calculation/version time"; "map SDK/DTO and raw response stay in
the integration adapter"; "a provider cannot overwrite the customer's address
silently"; low-confidence, conflicting or out-of-zone results "require
correction/manual confirmation". It left four things to product: the provider,
the locality and address format, the coordinate-source precedence, and "what
happens when the provider is unavailable". ADR 0037 fixed the region as a
bounding box that marks results outside it `LOW_CONFIDENCE` — "an unconstrained
geocoder asked for a Tashkent street name returns a plausible street of the same
name in another country" — and fixed the zone as an immutable, versioned
PostGIS polygon. ADR 0021 meters usage; ADR 0026 says a tenant names a provider
from a platform-owned catalogue; ADR 0028 says a secret is a reference. This
record chooses within those.

**Four axes decide the provider, and they pull apart.**

| Axis | Yandex Maps | 2GIS | OpenStreetMap, self-hosted (tiles, Nominatim, Photon) | Google Maps Platform |
|---|---|---|---|---|
| Uzbekistan coverage | Published coverage includes Uzbekistan; in production use by both storefronts today. House-level quality in Tashkent is expected to be strong and is *unmeasured here* | Publishes Uzbekistan among its countries and Uzbek-language search; local press reported a Tashkent map launching in 2019. House-level quality unmeasured | Volunteer data. Roads and landmarks are workable; house numbers are the weak point, and a delivery address is a house number. Unmeasured | Maps are good; address search against Uzbek-Latin, Russian-Cyrillic and mixed input is unmeasured |
| Licence on stored results | Geocoder results: 30-day cache on the standard licence, storage for the term on an extended one, minimum fee on both; results shown only through the licensee's software | Not stated on the public pages read; a contract question | ODbL: attribution is required; whether a stored geocode is a derivative database is a question for counsel, and OSMF publishes a geocoding guideline | No caching or storage of results except the place id; results shown on a Google map or with attribution |
| Where requests terminate | Outside Uzbekistan (exact infrastructure to be confirmed). Address text and coordinates leave the country | Outside Uzbekistan (to be confirmed; the company has local presence) | Inside, on our own VM, by construction (ADR 0034's position) | Outside |
| Cost and who carries it | Paid tiers with a minimum fee; billing route from Uzbekistan unknown | Quoted per contract; demo keys | No licence fee. Costs RAM and disk on a VM that ADR 0073 (still Proposed) sizes at 16 GB with about 13 GB of limits already budgeted, and the time of the one person who operates it | Pay per use; billing from Uzbekistan unverified |
| Operational burden | None | None | Real: an extract import, a geocoder index, tile serving or a vector-tile source, refresh jobs | None |
| Cost of switching from where the code is today | Lowest: it is what the storefronts run | An adapter and a front-end SDK | Highest | An adapter and a front-end SDK |

The table is the honest result of what could be established from published pages
on 2026-10-01. It deliberately contains no price: none could be sourced, and an
invented number would decide the record by accident. It also contains no
accuracy figure, because none was measured. Decision 3 exists to measure it.

## Decision

**Choose the map and geocoding provider by a measured bake-off, with Yandex Maps
as the proposed first adapter, 2GIS as the pre-qualified second, and
self-hosted OpenStreetMap as the designated exit; and build every screen against
provider-neutral seams so that the choice is an adapter swap.**

1. **Two seams, one on each side of the network.**
   - *Server side:* a provider-neutral geo port declared where `GeoPoint`
     already lives, with the operations ADR 0015 named (`geocode`,
     `reverseGeocode`) plus `suggest`, each returning point, normalized
     components, provider reference, confidence, precision and calculation time,
     and each taking the region's bounding box as a constraint. The adapter runs
     through the ADR 0007 route machinery, so it inherits timeouts, the outcome
     model, the contract-test suite and a route descriptor. Raw provider JSON
     never leaves the adapter.
   - *Client side:* a `MapProvider` token in the shared UI library
     (ADR 0101), which `q-map-canvas`, `q-map-pin`, `q-polygon-editor`,
     `q-bbox-editor` and `q-address-picker` call; the vendor SDK is loaded from
     the vendor's own origin the first time a map screen opens, never bundled.
     `frontend/operations` has an initial-bundle budget of 825 kB with about 54 kB
     of headroom, so an SDK in the initial chunk is not an option and a lazy
     route is the only shape that fits.

2. **The platform calls the geocoder; the browser draws the map.** Suggest,
   geocode and reverse geocode go through a platform endpoint for the operations
   console and, from the next storefront change, for the storefronts: the key
   stays server-side, the call is rate limited and metered per tenant (ADR 0033,
   ADR 0021), the address text crosses a boundary the platform controls and can
   audit, and the provider can be changed without shipping a front end. Tiles,
   pan, zoom and pin dragging are the browser's job against the vendor SDK with a
   public, referrer-restricted browser key.

3. **The bake-off decides, and the thresholds are proposed in advance.** A
   harness under `tools/` (provider-neutral, no key in the repository) runs a
   200-address golden set through each candidate's geocoder and records distance
   to the surveyed point, whether the result lies inside the region's bounding
   box, and what the provider says its own confidence is. Proposed pass: 90% of
   addresses within 100 m, every out-of-box result flagged, none in the wrong
   city. If Yandex passes and its licence terms are acceptable, Yandex is built
   first: it is the lowest switching cost, it is what customers already use, and
   the IA's own sentence is that Yandex is the market expectation. If it fails or
   cannot be paid for, 2GIS runs the same gate. The self-hosted OpenStreetMap
   stack is built only if both fail or a licence change makes a hosted provider
   unaffordable, because it is the only option that adds an operational
   system for one person to run.

4. **One platform licence, not one per tenant.** At the pilot a tenant does not
   bring its own map key. The geocoding credential is platform configuration
   with an ADR 0028 secret reference — it is *not* an ADR 0026 tenant
   installation, whose model assumes a tenant names a provider and holds the
   relationship. `ProviderCategory.GEOCODING` stays in the enum for the day a
   chain wants its own contract. The browser key is delivered by an API read
   (`GET .../map-config`: provider code, browser key, allowed features, attribution
   text), so it can be rotated without a build; the defaults in both storefront
   images and `public/config.json` are removed in the same change.

5. **What is persisted is a confirmed pin, not a geocoder answer.** The
   storefront already does this: a customer drops or accepts a pin and the point
   is saved as `CUSTOMER_PIN`; the server refuses `GEOCODER` from that surface.
   The operations console saves `OPERATOR_PIN` the same way. A geocoder result is
   a suggestion a person confirms by seeing it on the map, so the stored value is
   the person's, and the platform keeps no provider output beyond a response
   cache whose lifetime is set to the narrowest licence limit (30 days on
   Yandex's standard terms). `GEOCODER` as a source — an automatic fill for
   `NOT_GEOCODED` rows with nobody looking — is built only if a licence allows
   storage and the owner wants the backfill; it is not required by any row.

6. **Outages degrade in a fixed order and never price an address at zero.**
   (a) Suggestions fail, the map loads: the address field becomes plain text and
   the person drops a pin. (b) The map also fails: the address saves
   `NOT_GEOCODED` with its structured fields and landmark, checkout offers pickup
   and the operator path, and the operator sees an address without a point,
   because `DeliveryFeeResolver` refuses an address it cannot place rather than
   matching nothing. (c) A provider outage is a metric and an alert, not a
   checkout failure. Answers ADR 0015's "what happens when the provider is
   unavailable".

7. **Regions constrain every call.** The region's SW/NE box is sent as the
   provider's bias or bounding parameter and is also checked on the way back; a
   result outside it is `LOW_CONFIDENCE` whatever the provider claims. The
   pilot region is Tashkent; a second region is a row, not a release.

8. **The console map has four jobs and one new geographic fact.** Zone drawing
   and review (3.6, 3.6c), pin placement and correction (1.3b, 5.2c, 10.2b),
   courier and order positions (3.2, 3.1, 7.10a) and the zone-density view
   (7.10). The density view is a choropleth over zones: ADR 0037 deliberately
   keeps coordinates out of `DeliveryFeeResolved` ("the heat-map consumers need
   the zone, not the doorstep"), so row `7.10` needs a *zone* dimension in the
   reporting facts, not a coordinate. Row `7.10a` (orders as pins) does need
   doorsteps and is therefore a dispatcher-scope read with an ADR 0027 audited
   purpose, never a reporting fact.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Yandex Maps, committed now, no bake-off | Continuity is real, but the one input that could veto it (licence terms for stored or resold results, and payment from Uzbekistan) is unanswered, and the record would encode a provider on a market expectation | Never as a skip: a bake-off costs a day and removes the guess |
| 2GIS as the first provider | Publishes Uzbekistan coverage and a geocoder, but nothing in the repository uses it, the public pages state no licence terms, and the switching cost is highest of the hosted options | The bake-off ranks it above Yandex, or Yandex's licence is unaffordable or unobtainable |
| Self-hosted OpenStreetMap first | Best on residency and on licence cost, and the only option with no per-request fee. Worst on house-number quality, which is the thing a delivery address is, and it adds a geocoder, an index and a tile source to a single-operator stack with about 3 GB of page-cache slack | Both hosted providers fail the gate, or their licences make a hosted provider unaffordable, or a data-residency determination changes (ADR 0034 records that none currently binds) |
| Google Maps Platform | Its terms forbid caching or storing results beyond the place id and require a Google map, which collides with ADR 0015's stored coordinate and ADR 0037's zone evidence; billing from Uzbekistan is unverified | Google's terms change, or a tenant brings its own key and its own risk |
| Keep calling the geocoder from the browser | Works today. But it sends personal data to a third party outside ADR 0029's controls, ships a shared key in git, cannot be metered per tenant, and ties every front end to one vendor's response shape | Never for new code; the storefront migrates at its next address change |
| Bring-your-own-key per tenant at launch | Puts a vendor contract and a billing relationship on every restaurant owner, most of whom cannot pay a foreign vendor either | A chain asks for its own contract: `ProviderCategory.GEOCODING` is kept for it |
| Persist geocoder output as `GEOCODER` points | No row needs it, and it is the one choice that makes the storage-licence question load-bearing | An automatic backfill is wanted *and* the licence allows storage |
| No provider yet: keep the coordinate table and the four-number bounding box | Costs nothing and ships nothing. Nine rows stay `PARTIAL` or `NOT BUILT`, a zone stays a radius typed into a form, and the storefront keeps sending addresses to a vendor nobody chose | Never; the cheapest part of this record is the part that stops the accident |

## Consequences

### Positive

- Nine rows stop waiting on a decision, and the order they ship in is fixed by
  dependency: the primitive (`X.4`) first, then the consumers.
- The customer's address stops reaching a third party from the browser by
  default, and starts being metered, rate limited and auditable.
- A provider change is an adapter and a catalogue row; the bake-off harness is
  the same gate the next candidate runs.
- The committed shared key leaves git.

### Negative

- A core flow depends on a foreign vendor, with a licence, a minimum fee and a
  billing problem that are not yet solved.
- The server-side geocoder is a new synchronous dependency in the address path.
  Suggest-as-you-type through the platform adds a hop per keystroke; it needs
  debouncing, a per-session budget and a cache, or it becomes the most expensive
  endpoint in the product.
- Two keys now exist (browser, server) with different exposure, and only one of
  them is a secret.
- The map SDK is third-party script on operator and customer pages. This
  repository ships no Content-Security-Policy header today; adopting one later
  has to allow the provider's origins, and a provider outage must not blank a
  console page that merely contains a map.
- The golden set is work, and a set built from non-customer addresses
  under-represents new districts and courtyards, which is exactly where
  geocoders fail.

### Accepted trade-offs

- Yandex may be chosen on an unmeasured assumption about house-level quality
  that the bake-off then contradicts; the record accepts that outcome and the
  rework, because the seams make it an adapter.
- A confirmed-pin rule means a customer who never looks at the map never gets
  a point, so delivery to them is operator-confirmed. That is slower than an
  automatic geocode and cheaper than a wrongly placed courier.
- Metering without pricing means the first months of geocoding cost are
  absorbed.

## Specification

### Port and endpoints

```text
GeocodePort            suggest(text, region, near?)        -> [Suggestion]
                       geocode(text, region)               -> [GeocodeResult]
                       reverseGeocode(point, region)       -> ReverseResult?
GeocodeResult          point, components, providerReference,
                       confidence (HIGH|MEDIUM|LOW_CONFIDENCE), precision, resolvedAt
```

```text
GET  /api/v1/{surface}/tenants/{tenantId}/brands/{brandId}/map-config
POST /api/v1/{surface}/tenants/{tenantId}/brands/{brandId}/geocode/suggestions   non-mutating; body is large
POST /api/v1/{surface}/tenants/{tenantId}/brands/{brandId}/geocode/resolutions
POST /api/v1/{surface}/tenants/{tenantId}/brands/{brandId}/geocode/reverse-resolutions
```

The three geocode calls are non-mutating `POST`s (ADR 0031: the body carries
address text, which must never be in a query string, ADR 0029) and need no
`Idempotency-Key`. Each declares a capability the caller already holds for the
task the lookup serves — `order.place` at the branch for New order's address
pane, `delivery.zone.read` for the zone and region editors, `location.write` for
the branch pin — and where one endpoint is shared by several of them the build
adds a single code-owned `geo.lookup` capability (ADR 0025) instead of picking
the widest; `EndpointCapabilityDeclarationTests` decides which at build time.
`map-config` is readable by any authenticated staff principal and by the
storefront session. Request and response bodies are never logged, and the metric
labels are provider, operation and outcome only.

### Persistence

No new table for provider output. One usage counter (`geocode.requests`) in the
ADR 0021 metering path. The response cache is an ADR 0033 cache keyed by a
keyed hash of the normalized query plus the region id, with a TTL equal to the
narrowest licence limit in force; a cache entry is personal data (ADR 0029) and
is held to the same rules as the address it came from.

### Front-end contract

`MapProvider` exposes `load()`, `createMap(el, options)`, `addPin`, `addPolygon`,
`fitBounds`, `onPinMoved`, `destroy`. Components never import a vendor type.
`q-address-picker` composes the suggest field, the pin and the structured address
(entrance, floor, flat, landmark — ADR 0015) and emits `{point, coordinateSource,
components}`; it never emits `GEOCODER`.

### Testing

- Adapter contract tests against a controlled fake (ADR 0007), including the
  accepted-then-lost reply, a timeout, a malformed body, and a result outside the
  region box.
- A test that the storefront surface still refuses `GEOCODER`.
- The bake-off harness has its own tests over a recorded fixture, not the network.
- A test that a provider body containing an address never reaches a log appender
  (the ADR 0029 canary pattern).
- Front-end: the `MapProvider` fake drives the components; the initial-bundle
  budget stays green because the SDK is not in it.

## Rollout and rollback

Run the bake-off and ask the two licence questions first; neither needs code in
the product. Then ship the seams with a fake adapter behind the real endpoints,
so the console components and the storefront change can be built and tested
before a key exists. Then the first real adapter, enabled per environment by
configuration. Then migrate the storefront off its direct calls, then remove the
committed key. Rollback at any step is disabling the adapter: suggestions
degrade to text, the map falls back to the coordinates table the console
shows today, and checkout continues on `NOT_GEOCODED`.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Golden set and bake-off harness; recorded results kept with the ADR's
      follow-up note.
- [ ] Geo port in the shared geo vocabulary, with `suggest` added to ADR 0015's
      two operations; `ModularArchitectureTests` green.
- [ ] First adapter on the ADR 0007 route machinery, with a route descriptor
      under `docs/routes/` and a controlled fake.
- [ ] Platform configuration and ADR 0028 secret reference for the server key;
      `map-config` endpoint; metering counter.
- [ ] The three geocode endpoints, rate limited, with the region box applied and
      checked; capability declarations settled (see Specification).
- [ ] `MapProvider`, `q-map-canvas`, `q-map-pin`, `q-polygon-editor`,
      `q-bbox-editor`, `q-address-picker` in `frontend/operations`, lazy-loaded;
      ru / uz-latn / en strings; bundle budget unchanged.
- [ ] Consumers, each its own change: `3.6`/`3.6c` zones and activation review,
      `1.3b`/`5.2c` address pane, `10.2b` branch pin, `3.2`/`3.1` positions,
      `7.10` zone density (with a zone dimension in the reporting facts),
      `7.10a` pins (dispatcher scope, audited).
- [ ] Storefront moves to the platform endpoints; the key defaults leave
      `docker-entrypoint.sh` and `public/config.json` in both storefronts.
- [ ] ADR 0015 and ADR 0037 status lines updated to say the provider is chosen.
- [ ] Add the geocoder to ADR 0034's processor register under the classes it may
      see.

## Exit criteria

An operator draws a polygon zone on a map, previews it against a tariff, and
activates it; places and corrects an order's address pin with a suggested
address; and watches couriers move on the same map. A customer picks an address
on the storefront without their browser calling the vendor's geocoder, and the
saved row is `CUSTOMER_PIN`. The 200-address result is on record. No map key
remains in git.

## References

- ADR 0007, ADR 0015 (address and geocoding boundary, coordinate sources),
  ADR 0021, ADR 0026, ADR 0028, ADR 0029, ADR 0033, ADR 0034 (processors that
  leave), ADR 0035, ADR 0037 (regions, zones, heat-map note), ADR 0073 (VM
  sizing, payment obstacle), ADR 0101
- `platform/docs/operations-gap-map.md` rows `X.4`, `1.3b`, `3.1`, `3.2`, `3.6`,
  `3.6b`, `3.6c`, `5.2c`, `7.10`, `7.10a`, `10.2b`
- `platform/docs/frontend-information-architecture.md` PART 4 (MapCanvas and
  siblings; "Yandex Maps is the market expectation")
- `frontend/storefront/src/app/services/geocoding.service.ts`,
  `frontend/storefront/src/app/services/yandex-maps.service.ts`,
  `frontend/storefront/src/app/services/address-book.service.ts`,
  `frontend/storefront/docker-entrypoint.sh`, `frontend/storefront/public/config.json`
  and the same files in `frontend/storefront-milliy`
- `V0013`, `V0021`, `V0025`; `ServiceZoneService`, `DeliveryFeeResolver`,
  `ProviderInstallationController`, `RoadDistancePort`
- Provider terms as read on 2026-10-01 (the contract, not these summaries,
  binds): Yandex Geocoder terms of use (yandex.com/dev/tariffs/doc/en/geocoder/terms),
  Yandex MapKit terms, 2GIS API catalogue (dev.2gis.com/catalog), OSMF Nominatim
  usage policy (operations.osmfoundation.org/policies/nominatim), Google Geocoding
  API policies (developers.google.com/maps/documentation/geocoding/policies)
