# ADR 0047: Dine-in: table service, reservations, and QR ordering

- Decision status: Accepted
- Implementation status: Partial — rollout steps 1 to 3. V0034 creates the nine
  `dinein` tables — `location_settings`, `sections`, `tables`, `qr_guest_sessions`,
  `reservations`, `reservation_tables`, `table_sessions`, `session_tables` and
  `session_orders` — with `btree_gist`, three triggers and the reservation
  exclusion constraint; `FloorPlanService`, `ReservationService`, `TableSessionService` and
  `QrEntryService` implement sections, tables, archival and availability,
  reservations and the seating link, the session lifecycle with rounds, the bill
  as a `SUM` over `dinein.session_orders` joined to `ordering.orders`, force-close
  with its ADR 0027 record, and QR token issue, body-form exchange, rotation and
  rate limiting; `FloorPlanController`, `ReservationController`,
  `TableSessionController` and `QrEntryController` carry the seven ADR 0025
  capabilities at `LOCATION` scope, and `DineInTests` covers them.
  `SETTLE_OPEN_TICKET` is declared and refused at both the service and
  `ck_dinein_qr_mode` until the fiscal open input closes and a POS adapter exists.
  Not built: the two ADR 0011 ports and any adapter, ADR 0013's widened payable
  subject, the ADR 0018 service charge, ordering's cart-to-table binding — a
  round reaches a bill only through the operator's `POST
  /sessions/{sessionId}/rounds`, since nothing in `ordering` attaches a checkout
  to a session — and the external event contracts. See "What was not built, and
  why".
- Date proposed: 2026-08-21
- Date decided: 2026-08-21
- Deciders: Ayubkhon Abbosov (platform architecture), product, finance
- Depends on: ADR 0002, ADR 0011, ADR 0016, ADR 0017, ADR 0019, ADR 0025, ADR 0036
- Supersedes / Superseded by: —
- Open inputs: Who issues the fiscal receipt when a POS-owned check is settled through Qoida (finance, legal, with the planned ADR 0038); whether CLOPOS, r_keeper, or iiko expose an open-ticket read and a ticket settlement at all (integration discovery, extending ADR 0011's capability-matrix input); service-charge rate and whether a guest may decline it (product, finance). None of the three changes the model below, so this ADR is Accepted; the first keeps one QR mode disabled until it is answered

## Context

Every restaurant Qoida is being built for has a dining room, and here the room is
usually the larger half of the business. The competitor ships four things Qoida
has no model for: sections and tables as configurable entities, reservations over
a time interval, a QR menu with three distinct operating modes, and "hall"
appearing simultaneously as a delivery type and as a sales channel.

Most of it is cheap now and expensive later. `fulfillment_mode` on carts and
orders is specified in ADR 0019 and not yet built — `ordering` currently holds
only the acceptance policy. `catalog.location_offerings.fulfillment_modes` exists
in `V0016__create_catalog.sql` as a varchar defaulting to `DELIVERY,PICKUP`, and
is already the mechanism for selling draught beer in the hall and not for
delivery. A third mode costs a default value today and a migration across carts,
orders, quotes, offerings, and every report once orders ship.

The structurally hard part is not the mode. A table orders in rounds across an
evening and pays once at the end, and ADR 0019 deliberately forbids mutating a
confirmed order's lines. Something must hold the rounds together, and whatever
that is becomes a new object in the ordering picture. The second hard part is the
QR menu's middle mode: settling a check a waiter opened in the POS means reading
provider state Qoida does not own, and ADR 0011 has no port for it.

This work is scheduled `later`. It is written now because the first tenant with a
dining room asks for all of it at once, and discovering the answer then means
reopening ADR 0019.

## Decision

**Dine-in is a fulfilment mode on the existing order aggregate.** Orders gain
`fulfillment_mode = DINE_IN`. No parallel order type, no second commercial
snapshot, no second state machine. Plov eaten at table seven and the same plov
delivered to an address are one commercial object reaching the guest differently.

**A dine-in session groups the orders of one table visit and is the settlement
unit.** Each round is a normal, immutable ADR 0019 order, priced, reserved, and
fired independently. The session owns table occupancy, the running balance, and
the single act of paying. It holds no lines and no pricing logic: its total is
the sum of its member orders and is never recomputed from rules.

**Sections and tables are first-class, location-owned entities in a new `dinein`
module.** A floor plan is a physical property of a location, not brand catalog
content and not tenancy configuration.

**A reservation is its own aggregate, not part of an order.** The normal case is
a reservation that never becomes an order — a future booking, a no-show, a
cancellation — and the walk-in, which is most covers, is an order attached to no
reservation. A reservation may hold several tables, and double booking is
prevented by the database rather than by application code.

**The QR carries an opaque rotatable token, and its mode is configured per
location**: `VIEW_ONLY`, `ORDER_AND_PAY`, or `SETTLE_OPEN_TICKET`. The third is
selectable only where the bound POS declares both capabilities this ADR adds to
ADR 0011, and in it **Qoida creates no order**. The POS owns the check; Qoida
records a settlement against the external ticket, takes the payment through Click
or Payme, and reports it back. Mirroring the check into a Qoida order would give
one meal two commercial records, and every revenue figure for that venue would
count the same 300 000 som twice.

**Fulfilment mode and sales channel stay separate axes.** `DINE_IN` says the food
is eaten on the premises; the channel says how the order arrived — `QR_TABLE`
when the guest scanned, `POS` when a waiter entered it, `CALL_CENTRE` when an
operator did. The competitor's per-branch report lists "Зал" beside "Приложение"
and "Сайт", which is why it cannot answer the only interesting question about a
QR rollout: how much hall revenue now arrives without a waiter. The channel
registry belongs to ADR 0036, which owns the `system_type` vocabulary and closes
it; all three names above already exist there and this ADR adds none. An earlier
draft of this ADR used `DINE_IN_QR`, `DINE_IN_POS`, and `ADMIN` and asked ADR
0036 to add them — it must not, because a second ADR minting channel names gives
one arrival path two codes and splits every channel report down the middle.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A separate dine-in order aggregate with its own lifecycle | Duplicates the commercial snapshot, quote link, inventory reservation, state machine, audit shape, and every event contract. Reporting must union the two forever, and a union someone forgets is a revenue figure that is quietly wrong | A dining room needs a lifecycle the canonical order states genuinely cannot express. Course firing is the plausible candidate and belongs to the planned ADR 0041, not to a second order type |
| One long-lived dine-in order, mutated as courses are added | What the POS world does. Rejected because ADR 0019 forbids editing a confirmed order's lines for good reasons: the fiscal receipt, the payment amount, the inventory commitment, and the POS export have all already happened against the earlier version | The amendment model with real financial semantics, deferred by ADR 0019 and owned by the planned ADR 0039, is accepted and can restate a fiscalised total safely |
| No session: each round is an independent order the guest settles alone | Costs nothing and fits ADR 0019 exactly. Rejected because the guest pays three times in one evening and nobody — waiter, cashier, manager — can answer "what does table seven owe", which is the question a dining room asks all night | Never for full service. A counter-service venue uses `PICKUP` and needs none of this |
| A free-text table label on the order instead of floor-plan entities | Cheapest parity with "table number". Rejected because a label has no availability: it cannot be reserved, cannot be stopped from being double-booked, and cannot say which tables are free at 19:30 on Friday | Never while reservations are in scope. A venue with no sections gets one implicit default section rather than a different model |
| Mirror the POS open ticket into a Qoida order so settlement uses the normal path | Everything downstream then works unchanged. Rejected because the POS stays authoritative and keeps mutating that check: the copy is stale the moment a waiter adds a coffee, and one meal is counted twice and may be fiscalised twice | The POS exposes a ticket-closed webhook with an authoritative final total and Qoida accepts an externally-priced order, in the shape the planned ADR 0040 defines for aggregators |
| Prevent double booking with application locking or a check on read | Two hosts confirming table 12 for 20:00 in the same second is not a rare race in a busy restaurant; it is Friday. Read-then-write cannot exclude it, and holding a lock across a booking form is worse | Never. PostgreSQL exclusion constraints exist for this |

## Physical model

New schema `dinein`. Every table carries `tenant_id` and composite foreign keys
including tenant and brand ancestry, per ADR 0002.

```text
dinein.sections
  id, tenant_id, brand_id, location_id, code, sort_order
  status (ACTIVE|ARCHIVED), version, timestamps, unique(location_id, code)

dinein.tables
  id, tenant_id, brand_id, location_id, section_id, code, seats, joinable
  layout_x null, layout_y null, status (ACTIVE|OUT_OF_SERVICE|ARCHIVED)
  qr_token_hash null, qr_token_rotated_at null, qr_mode null
  version, timestamps, unique(location_id, code)

dinein.reservations
  id, tenant_id, brand_id, location_id, customer_account_id null
  guest_name_encrypted, guest_phone_encrypted, secondary_phone_encrypted null
  party_size, note_encrypted null, requested_from, requested_to
  turnaround_minutes_snapshot, status, source_channel, created_by, version

dinein.reservation_tables
  reservation_id, table_id, tenant_id, held_during tstzrange
  EXCLUDE USING gist (table_id WITH =, held_during WITH &&)
    WHERE (status IN ('CONFIRMED','SEATED'))

dinein.table_sessions
  id, tenant_id, brand_id, location_id, reservation_id null, party_size null
  opened_by, opened_at, status, service_charge_rate_bp_snapshot null
  settled_total_minor null, closed_at null, close_reason_code null, version

dinein.session_tables   session_id, table_id, tenant_id, joined_at, left_at null
dinein.session_orders   session_id, order_id, tenant_id, sequence, added_at

dinein.pos_settlements
  id, tenant_id, brand_id, location_id, table_id null, provider_binding_id
  external_ticket_id, external_ticket_version null, observed_total_minor
  currency, status, payment_intent_id null, idempotency_key, version
  unique(provider_binding_id, external_ticket_id)
```

- `held_during` is the **effective** hold: the requested interval plus the
  location's turnaround buffer, snapshotted at confirmation. The requested
  interval is stored separately so that changing the buffer next month cannot
  retroactively release a table nobody released or overlap two confirmed
  bookings. The constraint needs `btree_gist` and is the only thing here that
  makes "this table is booked" true under concurrency.
- A table is archived, never deleted, while a reservation or session references
  it. A deleted table is a booking whose location cannot be rendered. Guest name
  and phone are PII under ADR 0029 and encrypted at rest; a booking for a guest
  with no Qoida account creates no customer record and no consent, per ADR 0015.
- External reservation identifiers live in `integration.provider_entity_mappings`
  with `entity_type = 'RESERVATION'`, owned by ADR 0026, indexed for lookup. ADR
  0016 deleted its catalog-local mapping table for the reason that applies here:
  two stores for one fact have no winner when they disagree, and support staff
  searching by the identifier a guest quotes need one place to look.

## State machines

```text
Reservation
  REQUESTED -> CONFIRMED | REJECTED
  CONFIRMED -> SEATED | CANCELLED | NO_SHOW
  SEATED    -> COMPLETED

Table session
  OPEN -> BILL_REQUESTED -> SETTLING -> CLOSED
  OPEN -> CLOSED                      (no orders; opened in error, party left)
  SETTLING -> OPEN                    (settlement failed, or one more round)
  OPEN | BILL_REQUESTED | SETTLING -> FORCE_CLOSED
```

`SEATED` links a reservation to a session; only then does a booking hold a table
in the present rather than the future. A booking holds no inventory and no
pricing quote at any point — reserving a table on Friday reserves no food, and
wiring a booking into checkout would hold ADR 0017 stock for a party that has not
ordered.

`FORCE_CLOSED` is the walkout. Closing a session that still owes money requires
`dinein.session.force_close`, a reason code from the tenant's registry, and an
ADR 0027 audit record. An unpaid table that quietly disappears is how a shift's
cash shortfall becomes unattributable.

## QR modes

| Mode | What the guest gets | What Qoida creates | POS capability |
|---|---|---|---|
| `VIEW_ONLY` | The published dine-in menu, in ru, uz-Latn, or en | Nothing | None |
| `ORDER_AND_PAY` | A cart bound to the table, checkout, running bill | One `DINE_IN` order per round in the session | None |
| `SETTLE_OPEN_TICKET` | The waiter's open check, settled by Click or Payme | A `pos_settlements` row, no order | Both new ports |

The QR encodes a random 128-bit token stored hashed, as ADR 0018 stores coupon
codes — never a table id or a sequential number. A code printed on a table in a
public room is a permanent unauthenticated entry point, and a guessable one lets
anyone order to any table or read what strangers are eating. Scanning authorises
nothing by itself: it exchanges the table token for a short-lived, table-scoped
guest session token, rate-limited per table through ADR 0033, and that is what
the storefront API accepts. In `SETTLE_OPEN_TICKET` mode the guest must also
supply the check number from the pre-bill or the waiter before the ticket is
shown, because an open bill states what a group ordered and how many of them
there are. Rotation invalidates the printed code, so it requires
`dinein.qr.rotate` and is audited rather than scheduled.

**There is no "take the hall price for QR and kiosk" toggle.** The competitor
needs one because its price planes are a fixed enum. ADR 0018 already models this
as a price book assigned at `scope_type = CHANNEL`, so the dine-in book is
assigned to the `QR_TABLE` and `POS` channels of ADR 0036 and the question does
not arise. A
service charge is likewise a fee at ADR 0018 pipeline stage 5 with type
`SERVICE_CHARGE`, its rate held in location policy through ADR 0030 and
snapshotted onto the session — not a negative discount, because a charge modelled
as a discount is a total that reconciles to nothing.

## Extensions to existing decisions

| ADR | Extension |
|---|---|
| 0019 | `fulfillment_mode` gains `DINE_IN`; `ordering.cart_fulfillment` gains a nullable `dinein_table_id` beside `address_id`, exactly one set per mode; order events gain an optional session reference |
| 0016 | `location_offerings.fulfillment_modes` accepts `DINE_IN`. No schema change; the default stays `DELIVERY,PICKUP` |
| 0011 | Two new ports: `OpenTicketReadCapability` (read an open ticket by table or check number) and `TicketSettlementCapability` (apply a payment and close it). Print-to-POS is not this ADR's |
| 0013 | The payable subject becomes an order **or** a dine-in session. Cheap while payments are unbuilt, expensive afterwards — the main reason this ADR precedes its build slot. ADR 0013 now records this widening in its own superseding-relationship field, so a reader who opens only 0013 does not build a payment model that can address orders alone |
| 0025 | New capabilities `dinein.floorplan.manage`, `reservation.read`, `reservation.manage`, `dinein.session.read`, `dinein.session.manage`, `dinein.session.force_close`, `dinein.qr.rotate`. The registry is a code-owned enum, so each is a release |

## APIs and events

```text
POST /api/v1/storefront/qr/{tableToken}/sessions
GET  /api/v1/storefront/dine-in/sessions/{sessionId}
POST /api/v1/storefront/dine-in/sessions/{sessionId}/bill-requests
POST /api/v1/storefront/qr/{tableToken}/open-ticket/lookups
POST /api/v1/storefront/qr/{tableToken}/open-ticket/settlements
POST /api/v1/operations/locations/{locationId}/tables
POST /api/v1/operations/tables/{tableId}/qr-token-rotations
GET  /api/v1/operations/locations/{locationId}/table-availability
POST /api/v1/operations/locations/{locationId}/reservations
POST /api/v1/operations/reservations/{reservationId}/state-actions
POST /api/v1/operations/dine-in/sessions/{sessionId}/state-actions

TableReservationRequested / Confirmed / Rejected / Cancelled / NoShow / Seated
DineInSessionOpened / BillRequested / Settled / Closed / ForceClosed
PosTicketSettlementRecorded / SettlementFailed / QrTableTokenRotated
```

Operations mutations require the ADR 0025 capability at location scope, a reason,
an `Idempotency-Key`, and the expected version, per ADR 0031. The QR endpoints
are unauthenticated in the Keycloak sense, rate-limited per token and source, and
never accept a table id. Events carry scope, table, section, status, and version,
and never a guest name, phone number, reservation note, or line detail, per ADR
0032.

## Testing

- Two concurrent confirmations of overlapping intervals on one table: exactly one
  succeeds, and the loser sees a stable conflict code, not a leaked constraint
  violation.
- A booking for four tables that can hold three holds none.
- Changing the turnaround buffer alters no existing hold.
- A session with three orders settles once; each member order's payment
  projection derives from the session, not from a second payment.
- A rotated QR token stops working immediately and the old one cannot be replayed.
- `SETTLE_OPEN_TICKET` is refused at configuration time when the bound POS
  declares neither capability, per ADR 0011's rule that an unsupported capability
  may never be the sole business path.
- Cross-tenant reads of sections, tables, reservations, and sessions fail.

## Rollout

Ship the floor plan and reservations first, with no ordering: a host stand that
only books tables is useful alone and exercises the exclusion constraint under
real Friday load before money depends on it. Enable `VIEW_ONLY` next — a
published menu behind a token, no commercial risk. Then `ORDER_AND_PAY` per
location behind the ADR 0024 writer-ownership flag. Leave `SETTLE_OPEN_TICKET`
disabled until the fiscal open input is closed and one POS adapter passes
contract tests for both ports. Rollback stops new sessions at a location and
drains the open ones; a session already carrying orders never moves mid-service.

## Consequences

### Positive

- Dine-in reuses the order aggregate, so pricing, inventory, fiscal treatment,
  audit, and reporting work on day one with no parallel implementation.
- A booking cannot be double-sold, and the guarantee lives in the database rather
  than in whichever code path happens to check first.
- A QR rollout is measurable, because how the order arrived is recorded
  independently of where the food went.
- POS-settled dine-in is modelled honestly as a settlement against someone else's
  check, so no revenue figure counts one meal twice.

### Negative

- The session is a third object in the ordering picture, and every process
  manager, list view, and report that assumed one order and one payment must
  learn it.
- Payments must accept a session as a payable subject. That changes ADR 0013's
  shape and will be argued about, correctly: refunding one round of a settled
  session is not a whole-payment reversal.
- `btree_gist` and a range exclusion constraint are new schema knowledge, and a
  developer meeting one for the first time will find the failure opaque.
- A photographed or leaked QR token is remediated only by reprinting a physical
  code — slow, and visible to guests.
- `SETTLE_OPEN_TICKET` may prove unbuildable against all three initial POS
  providers, in which case a documented mode ships permanently disabled.
- A session open past midnight puts one evening's takings on two calendar days
  unless reporting keys on the session's business date. That rule belongs to the
  planned ADR 0043, and nobody notices it until a manager compares two totals.

### Accepted trade-offs

- Rounds are separate orders, so a guest who ordered four times has four order
  numbers and one bill. Operators will find that confusing; mutating a confirmed,
  fiscalised order is worse.
- No visual floor-plan editor in the first build. Tables carry optional layout
  coordinates so one can be added without a migration, but a drag-and-drop plan
  builder is a product in itself and seats nobody faster.
- Tips are not modelled. A tip is not revenue and has payroll and tax
  consequences here that nobody has decided; modelling it badly is worse than
  leaving it where it already lives.

## Implementation checklist

- [ ] Close the fiscal open input before any settle-mode work starts. **Still
      open**, and it is the gate that scoped `SETTLE_OPEN_TICKET` out of this
      build entirely — see "What was not built" below.
- [x] Add `DINE_IN` to fulfilment modes in ordering and catalog offerings. It was
      already there: `FulfillmentMode.DINE_IN` is a code constant and V0022's
      `ck_cart_fulfillment_mode` and `ck_order_fulfillment_mode` both carry it.
      Nothing was added; the concept existed and had nowhere to sit.
- [x] Create the `dinein` module and its Flyway migration, including
      `btree_gist`, in **V0034**. Eight tables, three triggers, and one exclusion
      constraint. No `pos_settlements` table, as decided below.
- [x] Sections, tables, archival, availability, reservations with the exclusion
      constraint, and the seating link
      (`FloorPlanService`, `ReservationService`, `TableSessionService.open`).
      The copied booking status on `dinein.reservation_tables` is written only by
      trigger, because an exclusion predicate can read no row but its own and a
      column two writers can set is a column that drifts.
- [x] The session lifecycle, force-close, and its audit records. The bill is a
      `SUM` over `dinein.session_orders` joined to `ordering.orders` and is never
      a stored column; `settled_total_minor` is written once, at settlement.
      Force-close carries the unsettled amount into the ADR 0027 record.
- [x] The seven ADR 0025 capabilities and the operations endpoints
      (`FloorPlanController`, `ReservationController`, `TableSessionController`),
      all at `LOCATION` scope, all mutations carrying a reason, an
      `Idempotency-Key`, and `If-Match`.
- [x] QR token issue, exchange, rotation, and rate limiting (`BearerToken`,
      `QrEntryService`, `QrEntryController`). **One deviation from the API sketch
      above, deliberate**: the printed token travels in the request body of
      `POST /api/v1/storefront/dine-in/qr/token-exchanges`, not in a path
      segment. A URL path is written to every access log, every reverse proxy,
      and every `Referer` the page emits afterwards, and a permanent bearer
      credential printed on card in a public room is the one value that must not
      land in all three. The `{tableToken}` path form in "APIs and events" above
      is superseded by that body form.
- [ ] Add the two ADR 0011 ports and one adapter, or a stub declaring them
      unsupported; extend ADR 0013's payable subject to the session. **Not done,
      and both are correctly somebody else's schema** — see below.

## What was not built, and why

**`SETTLE_OPEN_TICKET`, `dinein.pos_settlements`, and the two ADR 0011 ports.**
The first checklist item gates all of it on the fiscal open input, and the
rollout section gates it again on one POS adapter passing contract tests for both
ports. Neither has happened. The mode is therefore declared and refused twice
over: `QrMode.SETTLE_OPEN_TICKET` exists, is marked unselectable, and explains
itself when somebody asks for it, and V0034's `ck_dinein_qr_mode` refuses the
value at the database so a hand-written row cannot enable a mode with no adapter
behind it. No settlement table was created, for the reason V0022 gave for
`ordering.cart_fulfillment` and V0030 gave for `kitchen.devices`: schema nothing
writes reads as a capability that exists.

**ADR 0013's payable subject.** `payments.payment_intents.order_id` is `NOT
NULL`, so no intent can currently address a session. Widening it is payments'
table and payments' migration; a nullable `payment_intent_id` here with a foreign
key nothing could satisfy would be a column that permanently reads "unpaid". The
session is nonetheless shaped so the widening is additive rather than a redesign:
money is attached to the set of orders the session names, not to a total baked
into one column, which is also what keeps ADR 0046's split tender a feature.

**`ordering.cart_fulfillment.dinein_table_id`.** ADR 0019's cart fulfilment table
does not exist yet — V0022 says why — and it is ordering's to create. A dine-in
order reaches its session through `dinein.session_orders`, which needs no change
in ordering at all. The consequence is that `ORDER_AND_PAY` currently attaches an
already-placed order to a session rather than binding a cart to a table at
checkout, which is the same fact recorded one step later.

**The service charge itself.** `dinein.location_settings.service_charge_rate_bp`
is the rate's home and every session pins it, but nothing computes a charge from
it. The charge is an ADR 0018 pipeline stage-5 fee of type `SERVICE_CHARGE`, in
pricing's stage and pricing's tables, and modelling it here would be the second
authority this ADR spent a paragraph refusing.

**External event contracts.** No schemas were written for
`TableReservationRequested`, `DineInSessionOpened`, or the rest. Nothing
subscribes to them yet, and ADR 0032's envelope and versioning rules make an
unsubscribed contract a compatibility obligation bought for nothing. Every
decision that matters is recorded as an ADR 0027 audit fact in the meantime.

**A visual floor-plan editor.** Accepted as out of scope by the ADR itself.
`layout_x` and `layout_y` are carried so one can be added without a migration.

**Legacy import.** There is none to do, and this is a fact rather than a
deferral: `app/shared/enums/order.py` declares five order types — delivery,
express, external, takeaway, on_time — and there is no hall among them, no
`tables` model, and no `reservations` model anywhere in the legacy estate. Every
floor plan has to be authored per branch before a code is printed. V0034 records
this, and it is why no table here carries a `legacy_*` identifier.

## Exit criteria

A host can book three tables for one party on Friday at 19:30 and no second
booking can take any of them; a table can order four rounds across an evening and
pay once, each round independently priced, fired, and fiscally correct; a guest
scanning a table's code sees the dine-in menu in their own language and cannot
reach another table's bill; and every som taken in the dining room appears
exactly once in the same reports as delivery revenue, tagged with the channel it
arrived through.
