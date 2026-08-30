# Frontend rebuild and Delever parity — plan

How Qoida gets from a working backend with no user interface to two Angular
consoles, a re-pointed storefront, and feature parity with the competitor.

Companion documents: [the parity matrix](delever-parity-matrix.md) holds the
evidence, [the ADR index](adr/README.md) holds the decisions, and
[the minimum viable cutover](minimum-viable-cutover.md) holds the backend
sequencing this plan runs alongside.

## Where this starts

The backend is through stage three of the cutover plan. Tenancy, authorization,
audit, secrets, PII protection, media, catalog with versioned publication,
customers with consent, deterministic pricing, and binary inventory are built and
tested. Orders, payments, and notifications are not.

The frontend is at zero. The React workspace built against ADR 0022 was removed,
and [ADR 0035](adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md)
has since replaced that decision with Angular across every web surface.

Three inputs define what gets built:

| Input | What it establishes |
|---|---|
| Delever's documentation | The functional bar. 355 catalogued capabilities |
| The three archived applications | What this client's staff and customers already expect. 82 screens |
| The Qoida Design System | The visual and interaction contract |

## The shape of the work

Four workstreams, only the first of which is strictly sequential.

### A. Design system port and extension

**This is the critical path and the least visible work in the plan.**

The current component set — Button, Icon, DataTable, EmptyState, StatusPill,
Input, Select, Tabs, Card, PhoneFrame, IconChip — covers roughly the control
plane and roughly none of operations. Neither console can be built against it as
it stands.

Two jobs:

1. **Port the eleven primitives to Angular.** Cheap, because they are thin: every
   visual decision already lives in CSS custom properties, so `styles.css`,
   `tokens/`, and `guidelines/` are consumed verbatim and only the wrappers are
   rewritten. Days, not weeks.
2. **Author the missing components.** Not cheap. Fifteen are pilot blockers, and
   they are authored into the design project first so the system stays the source
   of truth rather than being bypassed per-application.

The pilot blockers, and what forces each:

| Component | Forced by |
|---|---|
| `MapCanvas`, `PolygonEditor`, `MapPin`/`AddressPicker` | Delivery zones, regions, branch geofences, order address pins. No map primitive exists at all |
| `LocalizedFieldGroup` | Roughly forty forms. Every user-visible string is `{ru, uz, en}` |
| `DataGrid` — inline edit, fill-down, keyboard nav, virtualisation | Bulk fiscal-code backfill across hundreds of rows; the menu editor |
| `MatrixGrid` — editable cross-tab | Channel × payment method; channel × order type; item × channel |
| `ActionMenu`, `Drawer`, `Modal`, `ConfirmDialog` | Delever's entire order UX is a row overflow menu opening a dozen dialogs |
| `Combobox` — async search, create-on-miss | Customer-by-phone-with-create; product picker over thousands |
| `MoneyInput`, `PercentInput` | UZS is large-integer with no minor units. Do not inherit cents assumptions |
| `DateRangePicker`, `TimeInput`, `DayOfWeekToggle`, `ScheduleGrid` | Venue and delivery hours; prep-time bands; every report filter |
| `MediaUploader` with aspect-ratio crop | Logos, banners, product images with per-aggregator variants |
| `ImportWizard` — dropzone, dry-run diff, row-level results | Excel catalog import; customer CSV; bulk geozone upload |
| `SecretInput` — masked, reveal-once, rotate | Provider credentials, per ADR 0028 |
| `StatusPill` overlay and dual-state variants | Lateness is an overlay on a status, never a status |
| `LockedState` and `DeniedState`, distinct from `EmptyState` | A plan lock is an upsell; a capability denial is a wall |
| `Toast`, `InlineAlert` | Every mutation in both applications |
| `DataTable` extensions — saved views, persisted filters, server pagination, selection with bulk-action bar | The order list above all. The legacy dashboard already persisted per-status filters and merchants will notice their loss |

Charts, maps, and rich text are third-party by necessity. Each is wrapped behind
a Qoida component so the library stays replaceable — the same seam the `Icon`
component already uses to substitute Lucide for Carbon.

**One console template is not enough.** Operations needs three shells: the
operator console (dense, keyboard-first), a kitchen device shell (fullscreen,
touch targets, offline banner), and a wallboard (TV-legible at distance). Carbon's
square geometry survives all three; the density and hit-target scales do not.

### B. Control plane

Built before operations, deliberately. It is the smaller surface, nothing in it
is latency-critical during a dinner rush, and it exercises the shell,
authentication, tenant selection, and tables before operations depends on all
four being right.

Ten sections: overview, tenants, providers, integration operations, commerce,
compliance and fiscal, access and security, platform configuration, migration,
support. Twenty-three screens for the pilot.

### C. Operations

The surface that has to match or beat Delever. Eleven sections: home, orders,
kitchen, delivery, catalog, customers, marketing, reports, finance, staff,
settings. Thirty-four screens for the pilot.

Settings dominates the pilot scope, and that is correct rather than a planning
failure: you cannot take one legal order in Uzbekistan without a location with a
tax identity, a menu bound to it, a delivery zone, a tariff, a payment method
wired to Click or Payme, a fiscal path, a sales channel, and an order-status
message. Reporting depth, promotions, loyalty, reviews, and payouts are all
deferrable without blocking revenue.

### D. Storefront re-pointing

The Angular storefront keeps its interface and gets a new API layer, screen by
screen, behind unchanged markup. It adopts the shared token sheet so it cannot
drift from the platform's visual language, and it does not adopt the console
component library — `.field` and `.console` are deliberately different contracts.

Its thirty screens are the customer journey the client already has: browse,
search, category, product, cart, checkout, order status, orders, profile,
favourites, addresses, invitations, FAQ, language.

## Sequencing

```text
1.  Design system port (11 primitives)                   ── blocks everything
2.  Design system pilot-blocker components (15)          ── blocks 3 and 4
3.  Control plane shell + auth + tenant selection
4.  Control plane pilot screens (23)
5.  Operations shell + order list + order detail
6.  Operations pilot screens (34)                        ── needs ADR 0019 built
7.  Storefront API re-pointing (30 screens)
8.  Wave 2 parity — kitchen, dispatch, marketing, reports
```

Steps 5 and 6 depend on backend work this plan does not own: orders (ADR 0019),
payments (ADR 0013), and notifications (ADR 0020) are all unbuilt, and an
operations console without an order aggregate has nothing to display. Steps 1
through 4 can proceed in parallel with that backend work, which is the main
reason the control plane goes first.

## The decision programme

The research surfaced twelve capability areas that need a decision Qoida has not
made. Three block the pilot.

| ADR | Title | Status | Priority |
|---|---|---|---|
| [0036](adr/partial/0036-sales-channels-and-location-serviceability.md) | Sales channels and location serviceability | Accepted | Blocks pilot |
| [0037](adr/partial/0037-delivery-zones-tariffs-and-fee-resolution.md) | Delivery zones, tariffs, and delivery-fee resolution | Accepted | Blocks pilot |
| [0038](adr/partial/0038-legal-entities-fiscal-receipts-and-product-classification.md) | Legal entities, fiscal receipts, and fiscal product classification | Proposed | Blocks pilot |
| [0039](adr/partial/0039-operator-assisted-ordering-and-order-amendment.md) | Operator-assisted ordering, order amendment, and terminal outcome accounting | Accepted | Post-pilot |
| [0040](adr/partial/0040-marketplace-channel-and-partner-api.md) | Marketplace channel: inbound aggregator orders and the partner API | Accepted | Post-pilot |
| [0041](adr/partial/0041-kitchen-execution-and-production-routing.md) | Kitchen execution, production routing, and kitchen release | Accepted | Post-pilot |
| [0042](adr/partial/0042-courier-compensation-shifts-and-settlement.md) | Courier compensation, shifts, and settlement | Proposed | Post-pilot |
| [0043](adr/partial/0043-reporting-analytics-and-the-metric-layer.md) | Reporting, analytics, and the metric layer | Accepted | Post-pilot |
| [0044](adr/partial/0044-marketing-campaigns-audiences-and-engagement.md) | Marketing campaigns, audiences, and engagement content | Proposed | Post-pilot |
| [0045](adr/partial/0045-realtime-operational-push-and-field-telemetry.md) | Real-time operational push and field telemetry | Proposed | Post-pilot |
| [0046](adr/partial/0046-loyalty-points-and-split-tender.md) | Loyalty, stored value, and split tender | Proposed | Later |
| [0047](adr/partial/0047-dine-in-table-service-and-qr-ordering.md) | Dine-in: table service, reservations, and QR ordering | Accepted | Later |

Every one is written now, including those built later, because the brief asked
for it and because the expensive part of a decision is discovering it late. An
accepted ADR with `Not started` is a normal state under
[ADR 0000](adr/meta/0000-adr-process-and-status-model.md): the design is settled and
may be built without reopening the argument.

Five are `Proposed` rather than `Accepted`, which under ADR 0000 means their
structure could still change because an open input is unresolved. Each is waiting
on an answer only a person can give:

| ADR | Waiting on | Owner |
|---|---|---|
| 0038 | Which party is the legal fiscal agent per settlement path | Legal, finance |
| ~~0040~~ | ~~Who issues the fiscal receipt for an aggregator-collected payment~~ — closed by ADR 0038 on 2026-08-22: the restaurant's legal entity is the seller and the legal principal | Legal, finance |
| 0042 | Courier employment classification | Legal, finance |
| 0045 | Lawful basis for continuous courier location telemetry | Legal |
| 0046 | Whether Qoida may hold customer prepaid balances at all | Legal |

Four of those five are the same question wearing different clothes: who is the
legal principal when money or a tax obligation moves. That is one conversation,
not five.

### Reconciling the set

The twelve were authored independently and each is internally coherent. Together
they decided seven things twice or three times, which a consistency review caught
and the following rulings resolve:

| # | Contradiction | Ruling |
|---|---|---|
| R1 | Three mechanisms for "priced by someone else" | `ordering.orders.pricing_authority` (ADR 0040) is the single enforcement point. The channel flag seeds it; ADR 0037's `EXTERNAL_CHANNEL` fee source is withdrawn |
| R2 | Handover verification modelled twice, in two tables | One table, `ordering.order_handover_challenges`, owned by ADR 0040. The expo station verifies against it rather than owning its own |
| R3 | Payment-method registry assumed by three ADRs, owned by none | ADR 0038 owns `payments.payment_methods`. ADR 0036 references it per channel; ADR 0046 extends it with balance-settling methods rather than a parallel enum |
| R4 | Two disagreeing channel vocabularies | ADR 0036's `system_type` set is closed and code-owned. ADR 0047 uses `QR_TABLE`, `POS`, `CALL_CENTRE` |
| R5 | Whether GPS pays a courier | It does not. Earnings come from the routing distance quoted at assignment (ADR 0042). ADR 0045's track summary is reconciliation and dispute evidence, never payroll input |
| R6 | The fact model could not express three sibling decisions | `fact_order` gains a legal entity, a tender grain, and the stock disposition |
| R7 | ADR 0013 restructured by three later ADRs, recording none of it | ADR 0013 now carries the supersession pointers |

That seven contradictions appeared is expected when twelve decisions are drafted
in parallel and is exactly what a consistency pass is for. It is recorded here
rather than quietly fixed, because the same failure mode will recur the next time
several ADRs are written at once.

### The three that block the pilot, and why

**Sales channels** (0036) — `channel` already appears as a column on carts,
orders, catalog publications, and price-book assignments, and no decision says
what it is. It gates payment-method availability, fulfilment modes, catalog
visibility, and price plane. Retrofitting that vocabulary across four modules
after the pilot ships is the expensive path.

**Delivery fee resolution** (0037) — ADR 0018's pricing pipeline reserves stages
five and six for delivery charges and leaves both unowned, while ADR 0014 says
the customer fee is "snapshotted at checkout" without saying who computed it. The
fee can legitimately come from four places, and Delever's own documentation never
states the precedence — which is exactly how a quote stops being reproducible.

**Fiscalization** (0038) — the largest correction this research produced. ADR
0013 closed an open input on the basis that the payment partners fiscalize. That
is true for online card payments and false for cash on delivery, courier
terminals, kiosk, and POS-settled dine-in. A per-branch tax identity also means
one tenant can contain several legal entities, which ADR 0002 currently cannot
express.

### What existing ADRs need extending

Twenty-six areas map onto an existing decision only partially. The parity matrix
lists each gap. The ones that are cheap now and expensive later:

- **ADR 0015 address schema** is unspecified, and this market needs подъезд,
  этаж, and ориентир as structured fields plus a legitimate no-coordinates case.
  Customers are being built now.
- **ADR 0016 fiscal classification** on catalog nodes is listed as an open input.
  In practice these are mandatory per dish and aggregators reject menus without
  them — a blocker dressed as a deferral.
- **ADR 0026 external references** need to be indexed and multi-provider, because
  support staff find an order by the identifier the aggregator quotes.
- **ADR 0006 integration failures** need a per-tenant, per-location,
  per-provider last-successful watermark. Cheap now, impossible to backfill.

## What we are deliberately not building

Twelve Delever capabilities are declined, with reasons, in the parity matrix. The
four worth stating here because they will be asked about:

- **Tenant-configurable order statuses.** Delever stores status as a UUID row and
  lets tenants reorder the machine. That makes event contracts, process managers,
  and cross-tenant reporting ungovernable, because the same status name would mean
  different things in two tenants. Offered instead: configurable display labels
  and named acceptance policies.
- **Multi-branch orders.** Delever's `steps[]` lets one order span several
  branches with per-step money and status. Two orders is the honest model.
- **Ingredient BOM and recipe costing.** Delever does not have it either; the
  brief assumed it from two page titles that mean something else.
- **Telephony.** An entire integration category sharing nothing with the others,
  plus call recording with its own consent regime. Caller-ID lookup against the
  customer record is the useful ten percent.

Also recorded as explicit non-goals: the security anti-patterns visible in both
Delever and the legacy code — `base64(login:password)` as a partner secret,
registrar credentials handed to a support engineer, a passport serial number as a
courier's initial password, API keys in client bundles. These will be proposed as
"how it worked before", and they are not trade-offs worth revisiting.

## Integrations

The brief is explicit: build only the delivery integrations, Noor and Yandex,
which are already built under ADR 0014. Write decisions for everything else.

Delever exposes seven integration categories. ADR 0026 already owns the
installation, binding, and credential model that all of them plug into, so what
remains is per-category capability decisions rather than new architecture:

| Category | Qoida position |
|---|---|
| Delivery services | **Built.** Noor and Yandex adapters, ADR 0014 |
| POS systems | ADRs 0011 and 0012 exist, unbuilt. Two capabilities missing from the port list: print-to-POS and read-and-settle an open ticket |
| Payment systems | ADR 0013, unbuilt. Click and Payme first |
| Order aggregators | New ADR 0040. Yandex Eats, Uzum Tezkor, Wolt |
| Sales channels | New ADR 0036 |
| SMS providers | ADR 0020, unbuilt. Eskiz and Playmobile template preapproval is an unmodelled workflow |
| Analytics services | New ADR 0043 |

## Open questions for the product owner

These block design rather than engineering, and each is named in a proposed ADR:

1. **Fiscalization path for cash orders.** Refuse cash until a path exists,
   integrate a fiscal operator directly, or require a fiscal-capable POS and make
   it a precondition for accepting cash?
2. **Courier compensation direction.** A liability the platform owes the courier
   and settles periodically, or a prepaid float the courier tops up and the
   platform debits per delivery? Delever supports both and reconciles neither.
3. **Analytics store.** Materialised views in PostgreSQL, a read replica, or a
   separate columnar store? Cohort heatmaps and per-day × per-branch × per-channel
   matrices are not workloads for transactional tables, and Delever runs
   ClickHouse for exactly this.
4. **Behavioural event stream.** Emitting product-analytics events from day one is
   cheap; retrofitting them is impossible for history. Decide before the pilot.
5. **Loyalty and stored value.** Partly a regulatory question in Uzbekistan, not
   only a modelling one. Deferring is defensible; leaving it undecided while ADR
   0013 hardens is not, because retrofitting split tender into a single-intent
   payment model is expensive.
6. **Legacy merchandising disposition.** ADR 0016 needs a MIGRATE / TRANSFORM /
   ARCHIVE / RETIRE call on tags, recommendations, attributes, and kitchens.
   Kitchens matter most: they are the production-routing key any kitchen surface
   needs.

## Surfaces this plan does not yet place

ADR 0035 names four web applications. Three more exist in this market and have no
home:

- **Telegram bot and Mini App.** Delever's highest-volume channel in this market.
- **Kitchen display client.** Different authentication, different offline
  tolerance, and a mid-service connectivity failure mode nobody has designed for.
- **Self-service kiosk.** Recognised as a sales channel from day one because that
  costs a table row; the hardware integration waits for a paying tenant.

Each is a surface decision rather than a screen, and each needs a place in ADR
0035 or an ADR of its own before it is designed.
