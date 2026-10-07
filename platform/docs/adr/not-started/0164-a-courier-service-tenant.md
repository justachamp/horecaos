# ADR 0164: A courier-service tenant

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — the discriminator exists and nothing reads it
  for routing, and the delivery screens exist and nothing puts them first. Built:
  `tenant.tenants.business_type` with `COURIER_SERVICE` among nine kinds (V0203,
  `BusinessType`: handovers `DELIVERY` only, no kitchen display), recorded and shown in
  the control plane by `TenantProfileController` and used for one thing, the onboarding
  template a run starts from (V0207, `OnboardingTemplateService.suggestedFor`); its own
  Javadoc says it "enables and disables nothing on its own today". The sellable-module
  mechanism (`commercial.modules`, `commercial.tenant_modules`, `feature_keys`,
  V0201, ADR 0087) with a tenant's own purchase (`CommercialOperationsController
  .purchaseModule`, ADR 0127), and code-owned feature entitlements
  (`EntitlementKeys`, with `Boolean.FALSE` defaults for opt-in features such as the
  Telegram family). The console's rail is one constant, `NAVIGATION`
  (`shell/navigation.ts`), filtered by capability and nothing else; its landing is
  `redirectTo: 'today'` (`app.routes.ts`); the delivery screens are a section of it
  (`/delivery`: dispatch board, live map, zones, regions, tariffs, courier rates,
  rules). Not built: any read of the entitlement or the type by the console, a
  delivery-first landing, a profile of the rail, and a screen whose row is a delivery.
  Stale in the gap map and worth correcting: row `3.1b` says "ADR 0002 has no
  business-type axis"; ADR 0090 built one, and the second entry for the same row says
  the discriminator exists and what is missing is a decision to let it drive routing.
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0002, ADR 0014, ADR 0021, ADR 0025, ADR 0027, ADR 0030, ADR 0031,
  ADR 0035, ADR 0042, ADR 0082, ADR 0087, ADR 0090, ADR 0127, ADR 0144
- Supersedes / Superseded by: — (amends ADR 0090, which is not edited. It answers the
  parity matrix's open question "one product or several product shapes on one
  platform?". ADR 0090's rejected row "Business type drives what a tenant may do —
  two sources of permission" stands: this record gives the type no power. It reopens
  that row only in the narrow sense its "Revisit when" invites, per-type shape, and
  chooses an entitlement, not the type, to carry it.)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with that
  person and the work they block is marked.
  - **One product or several shapes** (platform owner, the row's own input). Proposed
    default: one product. A shape is a module-granted profile of the one console. It
    never forks the schema, the API, the capability model or the code path, and it never
    grants a capability.
  - **What carries the shape: the tenant kind or a module** (platform owner). Proposed
    default: a module. `business_type` stays descriptive.
  - **The module's commercial terms** (finance, ADR 0087: price is content). Proposed
    default: code `deliveries-console`, billed `PER_TENANT`, drafted at price 0 so that
    activating it invents no price, activated by a second person, available for a
    tenant's own purchase (ADR 0127), naming one feature key. Finance sets the real price
    in the console.
  - **Which sections the variant puts away** (product, operations). Proposed default:
    Kitchen, Marketing and Inbox, with a per-person «Показать все» that shows them.
  - **How a delivery enters the platform** (product). Proposed default: an operator keys
    it at a pickup-point branch through the existing New order screen. No client-facing
    API, no portal, no per-client billing is decided here.
  - **What the console's delivery record is** (engineering). Proposed default: not a new
    aggregate. A delivery is an order's plan and shipment, shown as a list whose row
    identity is the plan.
  - **Whether to build the list before a courier-service tenant exists** (platform
    owner). Proposed default: no. The profile ships now; the list ships when the first
    courier-service tenant signs.
  - **A support hint** (platform support). Proposed default: the control-plane directory
    flags a `COURIER_SERVICE` tenant without the module, and the reverse, as a read-only
    note.

**To accept as written:** say "accept 0164". Every open input above is then closed
on its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

**Row `3.1b` — "`/deliveries` variant for courier-service tenants" — is `NOT BUILT`,
size XL, tier `?`.** Its "What is missing": *"A courier-service company cannot use this
console at all: there is no delivery-first landing route and no screen that treats a
delivery (rather than an order) as the record being managed."* Its "Blocked by": *"Owner/product
decision named as an open question in platform/docs/delever-parity-matrix.md (~line 661):
'Is HorecaOS one product or several product shapes on one platform?'"* The matrix says
what Delever does: the tenant carries a business type that "changes both which routes are
available and where the user lands", a courier-service tenant lands on `/home/deliveries`,
a screen that "treats a delivery as the prim[ary record]" and is "registered as a
contextual route rather than a sidebar item". It adds that business type is a third gate
beside permission and purchased module, and that "adding one after launch is expensive".

**The platform already has the three things the question asks for, and has declined to
connect them.**

- *A tenant kind.* ADR 0090 (Built) records one of nine kinds, `COURIER_SERVICE` among
  them, with the shape each "usually takes", and says plainly that "it enables and
  disables nothing by itself": it pre-selects an onboarding template and is shown in
  the directory. Its alternatives table rejects "Business type drives what a tenant may
  do" with the reason "two sources of permission; capabilities and entitlements already
  decide" and a revisit trigger, "per-type templates are designed".
- *A way to sell a shape.* ADR 0087 (Built): a module is a priced entry with a billing
  unit and named features, drafted by one person and activated by another, its terms
  frozen at the database, one live instance per tenant, billed on the statement, ended
  without deleting. ADR 0127 lets a tenant owner buy a module on sale for itself.
  `EntitlementKeys` holds features and gives opt-in features a `FALSE` default.
- *A console that already centres deliveries for those who work on them.* The rail is
  filtered by capability (`capabilityGuard` and `Shell`), so a `COURIER_DISPATCHER`, who
  holds no kitchen or marketing capability, already sees a short rail. The delivery
  section is complete in its own terms: a dispatch board over `DispatchController`'s plan
  queue with a non-PII destination label, a live map, zones, regions, tariffs, courier
  rates and rules (rows `3.1`, `3.6`, `3.8`).

**What a role filter cannot do, and why the tenant owner is the user that matters.** A
capability filter hides sections from people who lack the capability. The courier-service
company's owner, administrator and branch manager are exactly the people who hold the
order and kitchen capabilities (`TENANT_OWNER`, `TENANT_ADMIN`, `LOCATION_MANAGER`, and
the marketing ones for the first two), so their console is the restaurant's console: they land on the live order board, they see Kitchen and Marketing,
and the first screen says nothing about deliveries. No role makes a tenant-level shape.

**What the platform cannot do for a courier service at all, found by reading the model.**
Every shipment hangs off an order (`fulfillment.shipments.order_id`, a foreign key to
`ordering.orders`, V0054) and its plan opens on `OrderConfirmed`. The pickup end of a
journey is always a branch row (`tenant.locations`); `DeliveryOrderPort` reads the
branch's address, which "sit[s] in clear" on `tenant.locations` because the merchant
publishes it, and decrypts only the customer end. A line on an order is a catalogue variant, and the order price is the ADR 0018
quote. So a courier-service tenant can run on the platform today only as a business with
branches (its depots, or one per client pickup point), a catalogue of service products it
sells, and deliveries keyed as orders. A parcel from an arbitrary address to another, per
delivery, with a sender who is not a branch, has no model. That is the real shape of the
"several product shapes" question and it is the reason this record stops where it does.

**The console reads nothing about the tenant but its capabilities.** `FeatureFlags`
(ADR 0082) reads `/feature-flags` with a tenant-wide capability, and its own ADR says the
cost: "a person whose only grant is at a location sees every flag as off". A dispatcher with grants at one brand or branch is the user of a courier-service console, so the profile cannot hang off a
tenant-scope capability read. `GET /api/v1/session/context` is the one read every
signed-in principal makes about itself, authorised by being the principal, and it already
carries `activeTenantId`.

## Decision

**HorecaOS is one product. A courier-service tenant gets a deliveries-first console
profile, carried by a module and not by its business type; the profile changes where the
console opens and what its rail shows, never what anyone may do, and a delivery stays an
order's plan and shipment.**

1. **One product, several profiles.** A shape is a presentation of the one console over
   the one API and one schema, granted commercially. It is never a fork of either, never
   a second application (ADR 0035), never a second route table, and never a source of
   capability. A person's authority is their grants (ADR 0025), a tenant's entitlement is
   its plan and modules (ADR 0021, ADR 0087), and a profile is neither: it is how the
   console opens for a tenant that bought a module. A hidden section is not a forbidden
   one: `capabilityGuard` does not consult the profile, and a direct URL works for anyone
   who holds the capability.
2. **The module is `deliveries-console`.** `commercial.modules` code `deliveries-console`,
   name "Deliveries console", billing unit `PER_TENANT`, `feature_keys =
   {delivery.deliveries_console.enabled}`, currency `UZS`, drafted at `unit_price_minor = 0`
   by one person and activated by a second (ADR 0087: content, not structure), on sale to
   a tenant's own purchase under ADR 0127. The entitlement key is added to
   `EntitlementKeys`, `delivery.deliveries_console.enabled`, owned by `fulfillment`,
   `safeDefault(Boolean.FALSE)` like the Telegram family: a feature a tenant has because a
   module says so. The module is described to the tenant in one sentence that says it
   changes how the console opens and that it can be ended.
3. **The business type does not gate it.** `COURIER_SERVICE` without the module is a
   standard console. The module without the type is the deliveries profile. The type keeps
   its two jobs, a label and an onboarding suggestion; an onboarding template for
   `COURIER_SERVICE` may *recommend* the module, never grant it. ADR 0090's rejected row
   stands untouched.
4. **The console learns its profile from one membership read.** A new
   `GET /api/v1/session/console-profile?tenantId=` (no capability, like `/session/context`;
   the caller must hold some grant in the tenant, else `404`) returns
   `{ "variant": "STANDARD" | "DELIVERIES", "landing": "/today" | "/deliveries",
   "hiddenSections": ["/kitchen", "/marketing", "/inbox"], "handovers": ["DELIVERY"] }`,
   computed by `fulfillment` from the entitlement snapshot. It carries no secret, no money
   and no person. `Shell` filters `NAVIGATION` with `hiddenSections` after its capability
   filter, renames the Orders entry «Доставки» (`shell.nav.deliveries`) and puts Delivery
   first; the root redirect goes to `landing`; the New order screen offers only the
   handovers listed. A profile that cannot be read is the standard console.
5. **What the profile changes, and the whole list.** (a) The landing route. (b) The
   rail: Delivery first with its late badge, Orders labelled as deliveries, Couriers,
   Finance, Reports, Staff, Settings; Kitchen, Marketing and Inbox put away. (c) The New
   order screen's fulfilment choice, `DELIVERY` only, and the `CALL_CENTRE` channel
   pre-selected. (d) The Statistics landing, which opens on the couriers report (`/statistics/couriers`)
   instead of the overview. Nothing else.
   «Показать все» in the account menu shows the put-away sections for that person on that
   device (a per-viewer convenience, never a setting that is relied on).
6. **`/deliveries` is the landing route, and what it shows grows in two slices.** *Slice
   one, built now:* `/deliveries` redirects to `/delivery/dispatch`, the dispatch board,
   which is already a screen whose record is a delivery (a plan with its courier, its
   non-PII destination, its promise and its state). *Slice two, built when the first
   courier-service tenant signs:* a delivery list, `GET /api/v1/operations/tenants/{tenantId}/deliveries`
   (cursor pagination, ADR 0031), over `fulfillment.delivery_plans` joined to
   `fulfillment.shipments` and the order's own non-PII facts, one row per plan, filterable
   by branch (a client's pickup point), courier, state and date, with history (the dispatch
   queue lists only open plans). Its scope follows its filters as the order board's does
   (ADR 0144), its capability is `delivery.plan.read`, and a row opens the existing order
   detail pane rather than a second detail screen.
7. **A delivery is not a new aggregate.** No `delivery_request` table, no parcel record,
   no sender and recipient entity. The courier-service company models its clients as
   branches (a pickup point per client location, under the company's own brand or one
   brand per client, its choice), its services as catalogue products, and its deliveries
   as delivery-mode orders keyed by an operator; ADR 0014's sourcing, ADR 0042's accrual
   and cash, and ADR 0043's facts all run unchanged because they are keyed on the order.
8. **What is declined, explicitly, until a tenant needs it:** a client-facing API for a
   shop's own system to create deliveries; a client portal; per-client invoicing and
   statements to the businesses the company delivers for; pickups from an arbitrary
   address per delivery; parcel-to-parcel (consumer to consumer) deliveries; a separate
   courier-service onboarding journey beyond a recommended module. Each is a record of its
   own, written when a signed tenant asks, and none is a reason to build a second aggregate
   now.
9. **The directory says when a tenant looks half-configured.** The control plane's
   tenant directory (ADR 0090) adds a read-only note: `COURIER_SERVICE` without
   `deliveries-console`, and the module on a tenant whose type is not `COURIER_SERVICE`.
   It blocks nothing and changes nothing.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Decline the row: HorecaOS serves restaurants and that is the product | The cheapest answer and a legitimate one. But the discriminator, the module mechanism, the delivery section and the courier ledger exist, the missing piece is about two hundred lines of console and one read, and "adding one after launch is expensive" is exactly the case for keeping the option open at this price | The owner decides delivery businesses are not a market, in which case the module is never drafted and nothing here costs anything |
| Let `business_type` drive the landing and the rail | The reading Delever has. ADR 0090 rejected it for a stated reason: two sources of authority. The type is a platform administrator's label set from a closed list with a reason, not a sold, priced, second-person-activated and statement-billed fact; a mis-set type would change every user's console with no commercial trail, and a tenant could not end it | Per-type onboarding templates grant the module automatically and nobody can set the type without it. The type would then suggest the module, never gate the profile |
| A feature flag (ADR 0082) per tenant | Flags stage the rollout of code. A shape a customer pays for needs a price, an activation by a second person, an entry on the statement and a clean end, which is ADR 0087's whole point; a flag has none of them | Never for a sold shape |
| A plan per shape (ADR 0021) | A plan per combination multiplies with every shape, and ADR 0087 rejected it for modules for the same reason | Never |
| A separate application for courier services | A second shell, a second sign-in, a second set of strings and a second place to fix every component. ADR 0035 and `AGENTS.md` refuse duplicate applications "merely for implementation convenience" | The variant needs a different authentication (a client portal for shops) and sharing a shell costs more than it saves |
| A new aggregate, `delivery_request`, with arbitrary pickup and drop-off, sender and recipient | The right model for a true parcel network, and the honest answer to "a delivery rather than an order". But dispatch, accrual, cash, settlement, reports and notifications are all keyed on the order, so a second aggregate doubles each of them, for a tenant that does not exist | The first courier-service tenant needs pickups at arbitrary addresses per delivery, or parcels between consumers |
| Rely on capability filtering alone (a `COURIER_DISPATCHER` already sees a short rail) | Correct for the dispatcher and silent for the owner, administrator and manager, who hold the order and kitchen capabilities and so see the restaurant's console, landing on the live order board | Never; the two compose |
| Build the deliveries list now | Speculative: no tenant has asked, and the list's filters and columns are best chosen by the first dispatcher who uses the board for a week. Slice one already makes the console usable | The first courier-service tenant signs, or a pilot lead asks |
| Put the profile in `/session/context` | One round trip fewer, but the response mirrors `CapabilityView`, which the control-plane console also reads; a tenant's commercial entitlement does not belong in a capability contract | The two consoles agree to widen `CapabilityView` |
| Read the profile from the tenant-wide entitlements endpoint | `COMMERCIAL_PLAN_READ` is held by the owner and finance only, so a dispatcher would never see the profile; the same defect ADR 0082 records for flags | Never |

## Consequences

### Positive

- The platform answers the question once: one product, with shapes that are modules.
  Later shapes (a kiosk operator, a catering-only console) take the same road without a
  decision of their own.
- A courier-service tenant's owner opens the console on its dispatch board, with the
  restaurant sections put away, in the same release that adds the entitlement.
- The seam is commercial and auditable: a profile exists because a second person
  activated a module a tenant has, and it ends when the module does.
- No schema, no new aggregate, no change to dispatch, accrual, cash or reports.
- The business type keeps its meaning; nothing that reads it changes.

### Negative

- A third axis in the console's navigation (capability, entitlement, profile). A person
  whose rail is shorter than a colleague's has no way to tell why from the rail alone;
  «Показать все» and the access-denied page are the only hints, and ADR 0127's
  "locked versus denied" distinction does not extend to "put away".
- A tenant can buy the module by mistake, since ADR 0127 makes any module on sale
  purchasable by its owner. The profile is harmless and reversible, and the module's
  description and the subscription page's "End" are the remedy.
- The first courier-service tenant will find the platform lacks what makes a delivery
  company's work its own: a client who is not a branch, arbitrary pickups, per-client
  billing. The record names them and declines them, and a tenant that needs them on day one
  is not yet served.
- Treating a client as a branch puts every client's pickup point in the branch directory,
  the zones and the staff grants, and a delivery company with two thousand clients would
  find that model strained.
- A second round trip at start-up and a console that renders once before it knows its
  profile, unless the shell waits for it; a wrong first paint of the restaurant rail is
  possible on a slow network.

### Accepted trade-offs

- The module is self-service, so any tenant may take the profile.
- A put-away section is one URL away. That is deliberate: authority is the grant.
- The deliveries list waits for a tenant. Until then the board is the delivery record,
  and its history is the order list filtered to delivery.
- Ending the module returns the console to the standard profile at the next profile read,
  not instantly.

## Specification

### Physical model

None. `delivery.deliveries_console.enabled` is a code-owned key in `EntitlementKeys`
(a release, per its own Javadoc). The module is a `commercial.modules` row, content
authored in the console by finance and activated by a second person. The tenant's profile
is resolved from the entitlement snapshot on read, and nothing is stored. Every row it
reads already carries `tenant_id`.

### APIs (ADR 0031)

```text
GET  /api/v1/session/console-profile?tenantId=       no capability (the principal reading its own console), 404 without a grant in the tenant
       -> { variant, landing, hiddenSections[], handovers[] }       ETag = entitlement snapshot version
GET  /api/v1/operations/tenants/{tenantId}/deliveries?brandId=&locationId=&status=&courierId=&from=&to=&cursor=     (slice two)
       capability delivery.plan.read, scope resolved from the filters (ADR 0144); cursor pagination; no PII in a row
```

The delivery row carries the plan id, the order reference, the pickup point's branch
identity, the non-PII destination label, the plan and shipment states, the promise window,
`deliveredAt`, the courier reference or the partner, the cost with its basis (`ACCRUED`,
`INVOICED`, `SETTLED`, never summed across bases, ADR 0042) and the cash to collect. It
never carries a recipient's name, phone or address; the order detail pane's existing
authorised reveal does (ADR 0029).

### Capabilities, events, audit, PII, observability

- **Capabilities (ADR 0025):** none new for the profile. Slice two reuses
  `delivery.plan.read`. A module purchase and end are already `commercial.subscription
  .manage` at tenant scope for the owner and finance.
- **Events (ADR 0032):** none. A module change is an ADR 0087 audited commercial fact.
- **Audit (ADR 0027):** nothing new; the profile read is not audited (no personal data,
  no money). The module's activation, purchase and end already are.
- **PII (ADR 0029):** none in the profile. The list shows a non-PII label only.
- **Observability:** a counter `console.profile` by `variant`, bounded; no tenant label.

### Testing

- The key is off by default; a tenant given the module resolves `DELIVERIES`; ending the
  module or suspending the subscription resolves `STANDARD` (ADR 0087's lapse rule).
- `COURIER_SERVICE` without the module resolves `STANDARD`; the module on a `RESTAURANT`
  resolves `DELIVERIES`.
- A principal whose only grants are at one branch or brand reads its tenant's profile (the
  case the flags endpoint fails); a principal with no grant in the tenant gets `404`; another
  tenant's id never answers.
- Console: `Shell` removes exactly the listed sections after the capability filter and
  keeps a section the profile lists but the capability filter already removed removed;
  `capabilityGuard` is unchanged and a put-away URL still opens for a holder; the root
  redirect follows `landing`; the New order screen offers `DELIVERY` only; an unreadable
  profile gives the standard console; «Показать все» restores the rail without a request.
- The directory note appears for each mismatch and for neither match.
- Slice two, when built: a row per plan with history; filters; cursor paging; a
  location-scoped dispatcher sees only their branch; no recipient field in the payload.

## Rollout and rollback

Add the entitlement key and the profile read first; both are inert while no tenant has the
module. Draft the module at price 0 in the console and have a second person activate it,
which is content. Then the console profile on `Shell`, the redirect, the labels, the New
order defaults and the directory note, with the module granted to one internal tenant by
platform support (ADR 0087's add route) before it is offered. Slice two waits for its
trigger. Rollback is ending the module for a tenant (immediate for new sessions) or
retiring it from sale (ADR 0087: retiring stops new sales and leaves holders as they are);
nothing is stored, so nothing is unwound.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] `EntitlementKeys.DELIVERY_DELIVERIES_CONSOLE_ENABLED` and the control-plane catalogue
      listing; the module drafted and activated through the existing screens.
- [ ] `ConsoleProfileController` and its service in `fulfillment`; the session read
      authorised by membership; the response contract and its OpenAPI group.
- [ ] `Shell`: profile load, `hiddenSections`, the Orders label, Delivery first, the root
      redirect, `/deliveries` route to the dispatch board; New order handovers; «Показать
      все»; ru / uz-latn / en strings.
- [ ] Statistics landing on the couriers report for the profile.
- [ ] Control-plane directory note for the two mismatches.
- [ ] A recommended-module line in the `COURIER_SERVICE` onboarding template (no grant).
- [ ] Update ADR 0090's status line to say a module, not the type, carries a console shape.
- [ ] Tests listed under Testing, each seen failing first.
- [ ] Slice two (the deliveries list), only after the first courier-service tenant signs.

## Exit criteria

A tenant given the `deliveries-console` module has an owner who signs in and lands on the
dispatch board with Kitchen, Marketing and Inbox put away, Orders labelled as deliveries,
and a New order screen that offers delivery only; a location dispatcher of the same tenant
sees the same; a restaurant tenant sees the console it always saw; ending the module
returns the owner to it; a `COURIER_SERVICE` tenant without the module is a restaurant
console with a note in the control-plane directory. The parity matrix's open question has an
answer on record. Gap-map row `3.1b` can be marked `PARTIAL` (landing and profile built,
the list waiting for its tenant), or `BUILT` when slice two ships.

## References

- ADR 0002 (tenant, brand, location), ADR 0014, ADR 0021 (entitlements), ADR 0025, ADR 0027,
  ADR 0030 (entitlements and configuration compose, never merge), ADR 0031, ADR 0035,
  ADR 0042, ADR 0082 (flags, and the location-scope limit), ADR 0087 (modules), ADR 0090
  (business type), ADR 0127 (a tenant buys its own modules), ADR 0144 (scope follows the
  filter)
- `platform/docs/operations-gap-map.md` rows `3.1`, `3.1b`, `3.6`, `3.8`
- `platform/docs/frontend-information-architecture.md` 3.1, §9
- `platform/docs/delever-parity-matrix.md` ("Business-type-driven navigation", "Deliveries
  module", and the open question near line 661)
- `V0054`, `V0201`, `V0203`, `V0207`, `V0442`; `BusinessType`, `TenantProfileController`,
  `OnboardingTemplateService`, `EntitlementKeys`, `ModuleCatalogService`,
  `CommercialOperationsController`, `DispatchController`, `DeliveryOrderPort`;
  `frontend/operations/src/app/shell/navigation.ts`, `.../app.routes.ts`,
  `.../core/feature-flags.ts`, `.../core/auth/capability.guard.ts`,
  `.../features/delivery/delivery-shell.html`
