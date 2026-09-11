# ADR 0101: A region, a tariff binding, and a zone you can take back

- Decision status: Proposed
- Implementation status: Not started
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0026, ADR 0027, ADR 0031, ADR 0037, ADR 0057
- Supersedes / Superseded by: —
- Open inputs: the map/geocoder provider ADR 0015 owes and ADR 0037 inherited — until it lands a region's bounding box is typed as four numbers rather than dragged on a map, and a zone is a circle rather than a polygon (platform owner); whether `BRAND_MANAGER` should be able to author a region, which this decision says no to on the grounds that a region is tenant-wide and shared by every brand (platform owner)

## Context

ADR 0037 is built further than the console can reach, and the gap is not
symmetrical: every missing piece is a screen, except one, and that one has
never been written at any layer.

**The tariff binding is dropped on the floor.** `ServiceZoneController`'s
and `OperationsServiceZoneController`'s `DraftVersionRequest` both accept
`deliveryTariffId`; `fulfillment.service_zone_versions.delivery_tariff_id`
stores it; `DeliveryFeeResolver` reads it and prefers it over the branch
binding and the brand default. The operations console's own client type
(`delivery-zones-api.ts`'s `DraftCircleVersionRequest`) declares the field.
And `delivery-zones-page.ts`'s `submitDraft` never sets it. Every zone a
console user has ever drawn carries a null tariff, so the zone-beats-branch
precedence ADR 0037 exists to establish has never once been exercised from
the product. This is a one-line defect with a four-row blast radius.

**A "free geozone" is being asked for as a third geometry layer.** The IA's
§3.6 Owns list names "free geozone" beside "зоны доставки" and "branch
geozones", which is Delever's three-overlapping-layers model. `ZoneRole`'s
own Javadoc already refused it: «a 'free geozone' is not a third role: it is
a DELIVERY zone whose tariff resolves to zero, and expressing it as a layer
is what left three layers with no documented interaction». The mechanism is
therefore built; what is absent is the console's ability to bind *any*
tariff to a zone — which is the defect above — and any indication on the
zone list of which zones are free.

**A region can only be created by a developer.** `fulfillment.regions`
(V0025, tightened by V0088) carries the code, the tri-lingual name, the
centre and the SW/NE bounding box that constrains the geocoder and that
`ServiceZoneService.activate` checks every polygon against — the check that
catches a transposed latitude, "the geometry is valid, it is simply
somewhere else". There is no store, no service, no controller and no screen.
The only writer in this repository is test SQL. A fresh production database
has zero regions, so the bounding-box guard is inert on every zone anyone
activates, and onboarding a merchant in a second city is a hand-written
`INSERT`.

**The console can only ever add.** A zone version can be drafted and
activated and never listed, withdrawn or replaced deliberately; a branch can
be bound to a zone and never unbound. `delivery-zones-page.ts` drafts,
activates and binds inside one `submitDraft`, so a mis-typed radius is
immediately live and permanent, and a branch bound to the wrong zone stays
bound for ever. Half of ADR 0037's versioning — the half that makes
versioning useful to an operator rather than only to an auditor — has no
surface.

**Both screens call the wrong surface.** `delivery-paths.ts` builds from
`/api/v1/control-plane`, and its own doc comment says why: the operations
mirrors did not exist when it was written. They exist now
(`OperationsServiceZoneController`, `OperationsDeliveryTariffController`,
both on the `operations` OpenAPI group per ADR 0057), and they differ from
the control-plane pair in one deliberate way — the actor who drafted or
activated a version is read from the caller's authenticated identity and is
never accepted as a request field. The operations console is currently
posting `actorId: this.auth.subject()` into a fee-governing row's
`created_by` column, which is a value the client chose.

**`RADIUS_FALLBACK` is invisible.** `DistanceMode.ROAD` with no routing
installation is refused at activation (`DeliveryTariff.activationProblems`),
and a ROAD tariff whose `RoadDistancePort` answers empty falls back to the
straight line inflated by the tariff's detour factor and stamps
`DistanceSource.RADIUS_FALLBACK` on the resolution row. Three Javadoc
comments say "so nobody is misled". Nothing renders it.

## Decision

1. **A free geozone is a `DELIVERY` zone whose tariff resolves to zero, and
   the console says so.** No third geometry layer, no `FREE` zone role, no
   `isFree` column. The console binds a tariff on the zone draft — the field
   that already exists on both sides — and marks a zone whose bound tariff
   resolves to zero as «бесплатно» on the zone list. The IA's §3.6 "free
   geozone" wording is struck, citing ADR 0037.

2. **Regions get CRUD on the operations surface, under
   `DELIVERY_ZONE_MANAGE` at `TENANT` scope.** Create, update, archive and
   list, each write leaving an ADR 0027 audit fact in the same transaction.
   `TENANT` and not `BRAND` because the row has no `brand_id` and every
   brand under the tenant shares it: a grant over one brand is not authority
   over the geography the tenant's other brands geocode against. A region is
   archived, never deleted — `fulfillment.service_zone_versions.region_id`
   points at it and a months-old resolution's evidence must not dangle. A
   tenant may read the platform's own regions (`tenant_id IS NULL`, V0025's
   "Tashkent is not one tenant's fact") and may not write them.

3. **A zone version becomes reversible.** Three endpoints on both zone
   controllers' operations mirror: list a zone's versions; deactivate the
   live one; unbind a branch. Deactivation retires the `ACTIVE` version and
   activates nothing in its place, so the zone falls back to covering
   nothing — ADR 0037's stated safe direction. Unbinding closes the
   binding's validity window (`valid_until`) rather than deleting the row,
   because a resolution six weeks old names that binding.

4. **Authoring, activating and binding are three deliberate acts in the
   console, not one button.** Drafting produces a version the operator can
   see in a list and discard by drafting another; activating it is a second
   decision, which is what `DELIVERY_ZONE_ACTIVATE` being a separate
   capability from `DELIVERY_ZONE_MANAGE` already means; binding a branch is
   a third.

5. **Both delivery screens move to the operations mirror** and stop sending
   `actorId`. `delivery-paths.ts` becomes the one place that knows the
   prefix, as it already claims to be.

6. **`RADIUS_FALLBACK` and `PROVIDER_QUOTE` are rendered, not hidden.** A
   tariff in `ROAD` mode without a routing installation is shown as such on
   the tariff detail, with the refusal an activation would produce, rather
   than being offered as a mode that silently prices as radius.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A `FREE` zone role, or an `is_free` flag on the zone | Exactly the third layer `ZoneRole`'s Javadoc and ADR 0037 refuse. Two ways to express "this delivery is free" — a zero tariff and a flag — disagree at the boundary, and the resolution evidence would have to record which one won | Never, unless a free zone acquires terms a tariff cannot express (a free *window*, a free *basket threshold*) — and even then the tariff is the place for them |
| Region CRUD at `BRAND` scope, so a brand manager can add a city | The table has no `brand_id`. A brand-scoped write to a tenant-wide row is a lie the URL tells about ownership, and the bounding box it sets would gate every other brand's zone activations | A tenant asks for per-brand geography, which would be a schema change first |
| Region CRUD on the control plane only (platform staff add cities) | Makes onboarding a second city a support ticket. V0025 deliberately allows a tenant its own region; the nullable `tenant_id` exists for the platform half, not instead of the tenant half | — |
| Delete a wrong zone→branch binding instead of closing its window | `delivery_fee_resolutions` rows name the binding that applied. Deleting turns six-week-old evidence into a dangling reference, which is the same argument V0025 makes for archiving zones rather than deleting them | Never for a binding that has ever been live; a binding closed in the same microsecond it opened covered nothing and is closed, not deleted, anyway |
| A "discard draft" endpoint (`status = 'DISCARDED'`, which the check constraint already allows) | Wanted, and not this wave: a draft that is never activated costs nothing and is superseded by the next draft. Listing versions is what an operator actually lacks | An operator reports a version list long enough to be unreadable |
| Keep both screens on the control-plane prefix and add `actorId` validation server-side | The mirrors already exist and already do the right thing. Validating a client-supplied actor against the token is the same work as not accepting one | — |
| Build the map (`MapCanvas`/`PolygonEditor`/`BoundingBoxEditor`) in this wave | No provider is chosen: ADR 0015 owes the decision and ADR 0037 inherited it. The bounding box is four numbers and a circle is a centre and a radius; both are authorable without tiles | The provider decision lands (`X.4` in the gap map) |

## Consequences

### Positive

- The zone-beats-branch tariff precedence ADR 0037 specifies becomes
  reachable from the product for the first time, and with it free delivery
  per zone.
- A merchant in a second city can be onboarded by an operator instead of by
  a developer, and the bounding-box guard that catches a transposed latitude
  starts being armed on real tenants rather than only in tests.
- A wrong radius, a wrong zone and a wrong branch binding all become
  recoverable without SQL.
- The operations console stops choosing the identity written into
  `created_by`/`activated_by` on a fee-governing row.

### Negative

- Three clicks where there was one. An operator who wants a circle live now
  drafts, activates and binds. This is the cost of the separation
  `DELIVERY_ZONE_ACTIVATE` already asserted and the console was quietly
  collapsing.
- `BRAND_MANAGER` — who may draw and bind zones — cannot author a region.
  A brand manager onboarding a second city has to ask a tenant admin. That
  follows from the row being tenant-wide and is a real reduction in reach
  relative to "anyone who can draw a zone".
- The zone list gains a tariff column that must resolve a tariff *lineage*
  id to a name, which is a second read on a screen that had one. Resolved by
  listing the brand's tariffs once and joining in the client; a brand with
  hundreds of tariffs would want a server-side join instead.
- «бесплатно» is computed from the bound tariff's *active* version. A zone
  bound to a tariff whose next version is not free will silently stop being
  free when that version is activated, and the zone's own version does not
  change. That is correct — the tariff is the lineage, per V0025's column
  comment — and it is surprising, so the marker is derived and rendered, not
  stored.

### Accepted trade-offs

- Regions are authored as four typed numbers. A transposed pair of
  coordinates in the *region* itself is caught only by
  `ck_region_bbox_oriented` and `ck_region_centre_within_bbox`, not by
  looking at a map. Until the provider decision lands this is the whole of
  the defence, and it is better than no region at all.
- The version list is unpaginated. A zone accumulates one version per edit
  and the realistic count is single digits; a cursor here would be
  machinery for a shape nobody has.

## Specification

### Regions — `/api/v1/operations/tenants/{tenantId}/regions`

| Method | Path | Capability | Scope |
|---|---|---|---|
| `GET` | `` | `DELIVERY_ZONE_READ` | `TENANT` |
| `POST` | `` | `DELIVERY_ZONE_MANAGE` | `TENANT` |
| `PUT` | `/{regionId}` | `DELIVERY_ZONE_MANAGE` | `TENANT` |
| `POST` | `/{regionId}/archive` | `DELIVERY_ZONE_MANAGE` | `TENANT` |

`GET` answers the tenant's own regions **and** the platform's, each carrying
`platform: true|false`; a write addressed to a platform region is refused
`RESOURCE_NOT_FOUND`, not `FORBIDDEN`, so the endpoint cannot be used to
enumerate the platform's rows. The body carries `code`, the three display
names, `centreLat`/`centreLon` and the four bounding-box numbers; the
database's own `ck_region_bbox_oriented`, `ck_region_centre_within_bbox` and
`ck_region_coordinates` remain the authority and the service returns their
refusals as `VALIDATION_FAILED` with every problem at once, the shape
`ZoneActivationRefusedException` already established.

Audit facts, `AuditClass.BUSINESS`, `ResourceScope.tenant(tenantId)`,
target `Region`: `delivery.region.created`, `delivery.region.updated`,
`delivery.region.archived`. `changed` carries `code` and the box, which are
geography and not personal data.

### Zones — added to `/api/v1/operations/…/service-zones`

| Method | Path | Capability |
|---|---|---|
| `GET` | `/{zoneId}/versions` | `DELIVERY_ZONE_READ` |
| `POST` | `/{zoneId}/versions/{version}/deactivate` | `DELIVERY_ZONE_ACTIVATE` |
| `DELETE` | `/{zoneId}/locations/{locationId}` | `DELIVERY_ZONE_MANAGE` |

Deactivation refuses anything but the `ACTIVE` version and writes
`delivery.zone.version.deactivated`. Unbinding sets `valid_until` to the
later of now and one microsecond after `valid_from`, so
`ck_zone_binding_window` holds for a binding opened and closed in the same
instant, and writes `delivery.zone.location.unbound`.

### Console

- `delivery-paths.ts` builds from `/api/v1/operations`, and gains
  `regionPaths`, `zoneVersions`, `zoneVersionDeactivate` and
  `zoneLocation(locationId)`.
- The zone draft form carries the tariff (a select over the brand's
  tariffs, plus «нет»), the role (`DELIVERY`/`CATCHMENT`, with the tariff
  and threshold fields hidden for `CATCHMENT` — the constraint
  `ck_zone_version_catchment_is_not_priced` refuses them), the region, and
  three separate display names.
- The zone list gains a «бесплатно» marker on a zone whose bound tariff's
  active version prices to zero — `minFeeMinor = 0`, a single base band of
  zero with no per-km component, and no surcharging time rule — and a
  version list, a deactivate and an unbind behind the row.
- The tariff form authors many bands, time rules and discounts, `minFee`,
  `maxFee`, the rounding step and rule, the fee source and the distance
  mode, and binds a tariff to a branch. The detail panel renders distance
  mode, reach, rounding, every time rule and every discount, and says when
  a `ROAD` tariff has no routing installation.

## Rollout and rollback

No migration, no schema change, no new capability, no new scheduled job.
Every endpoint is additive; the control-plane controllers are untouched and
keep their `actorId` fields, so any existing control-plane caller is
unaffected. Rollback is reverting the branch.

## Implementation checklist

- [ ] `JdbcRegionStore`, `RegionService`, `OperationsRegionController`
- [ ] Zone version list, deactivate, unbind on the operations mirror
- [ ] `delivery-paths.ts` on `/api/v1/operations`, `actorId` removed from both clients
- [ ] Zone page: tariff binding, role, per-locale names, region, split draft/activate/bind, version list, deactivate, unbind, «бесплатно»
- [ ] Region page
- [ ] Tariff page: bands, time rules, discounts, min/max, rounding, branch binding, full detail render
- [ ] IA §3.6 "free geozone" wording struck, ADR 0037 cited

## Exit criteria

- A console zone drawn through the UI has a non-null `delivery_tariff_id`,
  and `DeliveryFeeResolver` prices an address inside it from that tariff
  rather than from the branch binding or the brand default.
- A zone bound to a zero-resolving tariff renders «бесплатно» on the list
  without the operator opening the tariff.
- A region created from the console constrains a subsequent zone activation:
  a circle drawn outside its box is refused with the transposed-latitude
  message.
- The active version of a zone can be deactivated and a branch unbound from
  the console, and the zone then resolves `OUT_OF_ZONE` for an address it
  previously covered.
- A `ROAD` tariff with no routing installation is visibly refused rather
  than silently priced as radius.

## References

- [ADR 0037](../partial/0037-delivery-zones-tariffs-and-fee-resolution.md) — zones, tariffs and fee resolution
- [ADR 0057](../built/0057-openapi-per-surface-document-groups.md) — the operations surface group
- [ADR 0027](../built/0027-audit-evidence-and-approval-model.md) — the audit fact every region write leaves
- `docs/operations-gap-map.md` PART C wave `P20`, rows `3.6`, `3.6b`, `3.6d`, `3.7`
