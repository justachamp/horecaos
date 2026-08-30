# ADR 0041: Kitchen execution, production routing, and kitchen release

- Decision status: Accepted
- Implementation status: Partial — rollout steps 1 to 3 less throughput ceilings,
  with the ADR 0019 proposal now reaching ordering.
  V0030 creates schema `kitchen` with `stations`, `brand_routing_rules`,
  `location_routing_rules`, `tickets`, `ticket_items` and `ticket_events`;
  `JdbcKitchenStore.resolveStation` implements all five routing levels in one
  statement with the fallback station and a per-line `ROUTING_UNRESOLVED` event;
  `KitchenTicketOpener` opens a ticket on `OrderConfirmed` before commit,
  `KitchenTicketService` holds the aggregate, per-station readiness and the
  roll-up, `KitchenReleaseWorker` runs the release modes on a timer with `FOR
  UPDATE SKIP LOCKED`, and `KitchenStationController` and `KitchenBoardController`
  carry the station and board surfaces. Tests are
  `src/test/java/uz/horecaos/platform/kitchen/KitchenExecutionTests.java`. The Implementation status of the ADR 0019 proposal has advanced: it now
  reaches ordering. `ordering.application.OrderProgressAdapter` implements
  `fulfillment.api.OrderProgressPort` over
  `OrderStateService.proposeProgress`, which weighs the proposal against
  `OrderStateMachine` — including the fulfilment-mode split at `READY` — records
  the transition as `KITCHEN_PROGRESS`, and answers a replay from V0087's
  `ordering.order_progress_proposals` ledger rather than from a status
  comparison. V0087 also adds the `ORDER_PROPOSAL` ticket event, so a refusal is
  on the ticket the branch reads rather than only in a log.
  `KitchenOrderProgressConfiguration`'s stand-in stays behind
  `@ConditionalOnMissingBean` for a context without the adapter — a slice test,
  or the rollback this ADR describes. Not built: devices and enrolment, expo and
  handover — so nothing yet proposes the pickup `COMPLETED` that the port and the
  adapter both support — branch suspension, station capacity, the ADR 0017
  modifier-option stock and expiring stops, and the external event contracts.
- Date proposed: 2026-08-21
- Date decided: 2026-08-21
- Deciders: Ayubkhon Abbosov (platform architecture), operations (station layout and role bundles), product (entitlement)
- Depends on: ADR 0014, ADR 0016, ADR 0017, ADR 0019, ADR 0025, ADR 0033, ADR 0035, ADR 0040
- Supersedes / Superseded by: —
- Open inputs: Whether the kitchen surface is a metered per-location entitlement under ADR 0021 (product, finance). Handover verification protocols of aggregators other than Yandex Eats are an open input of ADR 0040, which owns the challenge, not of this ADR

## Context

ADR 0019 fixed one canonical order machine with a single `PREPARING` → `READY`
pair. That is right for the commercial order and insufficient for a kitchen. Both
systems Qoida is measured against run a second machine beside the order: Delever's
KDS has a queue split by fulfilment type, an undocumented buffer, a VDU wall
display and a `Раздача` expo station, and the legacy dashboard tracks
`cooking_status` (new / cooking / ready / cancelled) independently of order status.
A kitchen needs facts the order does not carry: which station owns which line,
whether the grill is done while the cold line is not, and when the ticket should
hit the pass rather than when the customer was promised the food.

Three sub-decisions have real alternatives, and each is expensive to unwind.

**Production station.** Delever's `Отдел` is a catalog attribute of the product;
the legacy `kitchens` table is location-owned. ADR 0016 leaves the disposition of
those rows an open input, saying only that retained kitchens "become
location-owned preparation stations/routing or approved preparation metadata". A
chain where one branch has a grill and a bar and another has a single hot line
cannot be described by a brand attribute alone, and a purely location-owned
mapping means re-assigning a 400-item menu by hand at every new branch.

**Kitchen release.** The instant the kitchen starts is neither the confirmation nor
the promise; Delever exposes it as `kitchenSentTime` with an editable fire time for
preorders. Without a separate instant there is no buffer at all: a preorder placed
at 11:00 for 20:00 prints on the line at 11:00 and the food is thrown away.

**The capability surface.** From a kitchen screen a line cook can, in Delever, take
a dish off sale across every channel, close the branch to new orders, dispatch a
paid third-party courier, and change an order's payment type — four blast radii
behind one screen.

Nothing kitchen-related is built. `ordering` holds only the ADR 0002 acceptance
policy; `fulfillment` is an empty package.

## Decision

**Kitchen execution is its own aggregate — a production ticket — and the order
state machine is unchanged.** One ticket per order per location, one item per order
line per routed station, per-item readiness rolling up to ticket readiness. The
ticket *proposes* order transitions through the ADR 0019 command path exactly as
POS and delivery do; it never writes `ordering.orders`. Kitchen progress with no
order-level meaning — the grill finished, a ticket was recalled — stays inside the
kitchen aggregate and never becomes a commercial fact.

**Production routing is two layers, brand and location, most-specific first.** The
brand assigns a catalog node to a closed *station role*; the location owns the
actual stations and may override any node to a specific station. An unroutable line
goes to the location's fallback station and raises `KitchenRoutingUnresolved` —
never silently dropped, because a line on no screen is a dish nobody cooks and a
customer who waits for it. This closes the `kitchens` half of ADR 0016's open
input: legacy kitchens are TRANSFORMed into `kitchen.stations` at location scope.

**Kitchen release is a first-class instant on the ticket**, distinct from
`confirmed_at` and from the promise. The buffer is not a screen; it is the set of
tickets in `HELD`. Release is automatic on confirmation, scheduled from the
promise, or manually held, and a durable PostgreSQL scheduler with leases fires it,
as ADR 0014 does for sourcing and ADR 0017 for expiry. Kafka is not the timer.

**The kitchen surface carries seven capabilities, not one**, and borrows three more
from the ADRs that own them. **Kitchen devices authenticate as devices**, with a
location-scoped grant and a bound station filter: a tablet left on a counter signed
in as the branch manager is an unrevocable credential carrying a manager's
capability set.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Kitchen state as a projection of the order status | A projection has nowhere to put per-station readiness, a hold-and-fire time, or a recall from the pass. Recalling a dish would have to move the order backwards from `READY`, which ADR 0019 correctly forbids, so the projection would either lie or block the kitchen | Per-station readiness and hold-and-fire are both dropped from scope. That is a product retreat, not a technical one |
| Station as a single layer — a catalog attribute (Delever's `Отдел`) or a location-owned mapping (the legacy `kitchens` table) | Catalog-only cannot describe two branches of one brand with different layouts, the normal case for a growing chain in Tashkent. Location-only makes every new branch re-map the whole menu by hand: 400 items across 12 branches is 4,800 assignments, and the ones nobody does pile onto the fallback screen | A tenant is proven single-layout across every branch and stays that way, at which point the location layer is dead weight but harmless |
| Kitchen release equals order confirmation | Removes an entity and a scheduler, and the buffer with them. A preorder for 20:00 confirmed at 11:00 fires at 11:00, a throughput ceiling has nothing to shift, and the editable fire time both competitors ship has no home | Never while preorders exist |
| One `kitchen` role covering the whole surface | A line cook could stop a dish across every sales channel, close the branch to new orders, and flip a card-paid order to cash so the cashier collects money the platform already took. These are not one job | Never. A tenant wanting one person to do all of it grants all seven capabilities, which is a deliberate act with an audit record |
| Let the integrated POS own kitchen state and read it back | iiko and r_keeper ship real KDS products and a tenant running one does not want a second screen. Rejected as the platform model because ADR 0011's capability set has no kitchen state, most target tenants have no POS KDS, and a POS outage would then stop the kitchen rather than only the export | A tenant runs POS KDS on every line and wants Qoida to observe. Model it as a POS capability that suppresses local tickets for that location, never as a second writer |

## Stations and routing

Station roles are a closed, code-owned enum — `HOT`, `COLD`, `GRILL`, `BAR`,
`BAKERY`, `PACKING`, `EXPO` — for the reason ADR 0025 keeps capabilities in code:
free text means one branch spells the role `гриль`, another `Grill`, and routing
silently misses. Stations are location-owned rows with a role, a name in
ru / uz-Latn / en, and a sort order. Resolution for one order line, first match
wins:

```text
1. location override on the variant
2. location override on the product
3. location override on the category
4. brand station role on variant, then product, then category
     -> the location's station carrying that role
5. the location's fallback station, plus KitchenRoutingUnresolved
```

The resolved `routing_version` is stored on the ticket; changing rules later does
not re-route tickets already fired, because a cook does not want a dish moving off
their screen mid-service. One order line routes to exactly one station — a line is
one sellable variant plus its modifiers, and a combo needing two stations is two
lines under ADR 0016's product model. Modifier options never route independently.

## Ticket model and state machine

```text
kitchen.tickets
  id, tenant_id, brand_id, location_id, order_id
  sequence_label, fulfilment_mode, channel
  status, release_mode, release_at null, released_at null
  prep_estimate_seconds, target_ready_at
  started_at null, ready_at null, handed_over_at null
  kitchen_note_snapshot null, routing_version, version, timestamps
  unique(tenant_id, order_id)

kitchen.ticket_items
  id, tenant_id, ticket_id, order_line_id, station_id
  quantity, name_snapshot_i18n, modifier_snapshot, note_snapshot
  status, started_at null, ready_at null, cancelled_at null, version
  unique(tenant_id, order_line_id, station_id)

kitchen.ticket_events
  id, tenant_id, ticket_id, ticket_item_id null
  from_status, to_status, trigger, actor_type, actor_id null
  device_id null, reason_code null, occurred_at, correlation_id
```

`sequence_label` is the short number the pass calls out — `A-014`, reset per
location per business day. A UUID is not something a cook shouts across a kitchen.

```text
Ticket:  HELD -> FIRED -> IN_PRODUCTION -> READY -> HANDED_OVER
         HELD | FIRED | IN_PRODUCTION | READY -> VOIDED
         READY -> IN_PRODUCTION            (recall, bounded and audited)

Item:    QUEUED -> STARTED -> READY
         QUEUED | STARTED -> CANCELLED
         READY -> STARTED                  (recall)
```

The first item reaching `STARTED` moves the ticket to `IN_PRODUCTION`; every
non-cancelled item reaching `READY` moves the ticket to `READY`. Proposals into
ADR 0019 go through `POST /orders/{id}/state-actions` with an idempotency key and
expected version:

| Kitchen fact | Proposed order transition |
|---|---|
| Ticket `IN_PRODUCTION` | `CONFIRMED` → `PREPARING` |
| Ticket `READY` | `PREPARING` → `READY` |
| Handover, pickup order | `READY` → `COMPLETED` |
| Handover, delivery order | none — ADR 0014 moves the shipment on its own evidence |

**A recall never moves the order backwards.** It is permitted only while the ticket
is `READY` with no handover recorded on it; after that it emits `KitchenRecallAfterReady` and
an operational exception instead. The failure prevented is concrete: a courier
dispatched against a `READY` order arriving where the dish is back on the grill,
with the order still reading `READY` to the customer.

## Kitchen release, the buffer, and throughput

```text
AUTO_ON_CONFIRM   fire as soon as the order is CONFIRMED
SCHEDULED         fire at release_at, computed from the promise
MANUAL_HOLD       fire only on an explicit release command

release_at = target_ready_at - prep_estimate - station_queue_offset
```

`target_ready_at` is ADR 0014's `estimated_ready_at`. `prep_estimate` resolves
through ADR 0030 per location with a time-of-day band, because a branch that cooks
a plov in twelve minutes at 15:00 does not do it in twelve minutes at 19:30.
Throughput ceilings are `kitchen.station_capacity` rows — station, weekday, local
time window, portions per hour.

A ceiling **shifts `release_at`; it never rejects an order.** Refusing an order
because the grill is full is serviceability and belongs to ADR 0036 at the point of
sale, where the customer can still pick another branch or another time. If shifting
would push the ticket past `target_ready_at - prep_estimate`, release happens
anyway and raises an operational exception: a kitchen that quietly holds a ticket
to protect its own throughput number produces a late order nobody was warned about.
A manual fire-time change is bounded by the same rule — setting `release_at` later
requires `kitchen.ticket.release.override`, a reason, and an ADR 0027 audit fact.

## Displays and devices

One aggregate, four read models over it.

| Surface | Read model | Interaction |
|---|---|---|
| Kitchen orders | Fired tickets for the device's stations, partitioned by fulfilment mode (pickup / delivery / aggregator / hall) | Start, ready, recall |
| Buffer | Tickets in `HELD`, ordered by `release_at` | Release now, hold, edit fire time |
| VDU | The same fired tickets, no controls, TV-legible | None |
| Expo | Tickets in `READY`, plus handover lookup | Handover, verification |

VDU is a projection with a station filter and a device registration, not a state.
Tickets render in the location's kitchen locale, not the customer's: a cook on a
Cyrillic line reading an English dish name mis-plates.

A `kitchen.devices` row carries a device class (`KDS`, `VDU`, `EXPO`), a station
filter, its own principal with a `LOCATION`-scoped grant, an enrolment record, and
a `revoked_at` — so a lost tablet is revoked in one row, and every audit fact names
the device beside the human actor where one is present. The client reads through a
cache and queues station advances by idempotency key while offline, so a mid-service
connectivity failure costs latency and not work. The push transport — refresh
interval, socket, reconnect policy — belongs to ADR 0045. ADR 0035 already names a
kitchen device shell as one of its three console shells and lists the kitchen client
as an unplaced surface; this ADR places it.

## Handover and verification

**This ADR does not own handover verification and creates no table for it.** ADR
0040 owns `ordering.order_handover_challenges` — one challenge per order, typed
`CODE|QR|SIGNATURE|NONE`, with a peppered `expected_value_hash`, `max_attempts`,
and a `PENDING|VERIFIED|BYPASSED|FAILED|EXPIRED` status. An earlier draft of this
ADR created a second table, `kitchen.handovers`, carrying its own
`verification_mode` and reference hash; that table is withdrawn. Two verification
records for one handover means the expo screen and the partner API can disagree
about whether a 420,000 som bag was released, with neither row authoritative and no
way to tell which courier took which order.

The expo station verifies against ADR 0040's table. It resolves the challenge for
the order, submits the entered value under 0040's rules, and only on `VERIFIED` or
`BYPASSED` records handover on the ticket — `handed_over_at`, actor, device, and
`KitchenHandoverCompleted` — and proposes the order transition in the table above.
The challenge type resolves from the channel binding, never from a provider name, so
a protocol Uzum Tezkor or Express24 turns out to use is a new enum value in ADR 0040
rather than a redesign here. Codes are hashed at rest under ADR 0029 and never
appear in kitchen events.

The capability split follows the same line. `kitchen.handover.complete` releases the
goods and closes a pickup order from the expo screen. Overriding an exhausted or
unanswerable challenge is ADR 0040's `marketplace.handover.bypass`, which no kitchen
bundle holds and which writes an ADR 0027 audit fact: a cook who cannot read a
courier's code should not also be the person deciding the code does not matter.

**Interim behaviour.** ADR 0040 is now Accepted (decided 2026-08-23) and its
challenge table was built in V0038, so the sequencing risk this section was
written against did not occur: this ADR's own expo and handover work has not
shipped yet (see the checklist below), and it will verify against ADR 0040's
table from the start rather than recording unverified handovers first. No
kitchen-owned substitute is built in the meantime, because a temporary second
table is how two verification records become permanent.

## Capabilities

| Capability | Default bundle | Why separate |
|---|---|---|
| `kitchen.ticket.read` | line, expo, manager | — |
| `kitchen.ticket.advance` | line, expo, manager | Start and ready, restricted to the principal's stations |
| `kitchen.ticket.recall` | expo, manager | Undoes a readiness the pass already acted on |
| `kitchen.ticket.release` | expo, manager | Hold and fire; changes when food is cooked |
| `kitchen.ticket.release.override` | manager | Fire later than the promise permits |
| `kitchen.handover.complete` | expo, manager | Releases goods and closes a pickup order |
| `location.serviceability.suspend` | manager | Closes a revenue channel |

Three powers Delever puts on the kitchen screen are deliberately **not** new
capabilities:

- **The stop list is ADR 0017's `inventory.adjust` at `LOCATION` scope**, invoked
  through the existing availability endpoint; a second write path for one
  availability fact is what ADR 0017 forbids. A stop covering every branch of a
  brand needs `inventory.adjust` at `BRAND` scope, which no kitchen bundle holds:
  one branch out of lamb is not the chain out of lamb.
- **Courier assignment from KDS is ADR 0014's `delivery.manual_assign`**;
  dispatching a paid third party additionally requires
  `delivery.dispatch.external`, because it spends the tenant's money.
- **Changing an order's payment type is ADR 0039's**, invoked from the expo screen
  under that ADR's capability and never in the line-cook bundle.

A suspension raised from a kitchen device (`kitchen.branch_suspensions`: location,
reason code, suspended channels, `starts_at`, `ends_at`, actor, device) **must carry
a duration and a reason, and auto-expires.** Permanently closing a location stays
with `location.write` in the control plane. The failure prevented is one every
restaurant operator has seen: a branch toggled closed at 21:00 during an overload,
never toggled back, discovered at noon after a morning of invisible lost revenue.
Suspension emits `KitchenBranchSuspended` for ADR 0036 serviceability to consume;
the kitchen never edits the location record.

## Extensions to dependencies

- **ADR 0016.** The `kitchens` portion of its open input is closed: TRANSFORM to
  location-owned stations plus a brand-level station role. Tags, recommendations,
  and content dispositions are untouched.
- **ADR 0017.** Modifier options become an addressable stock-item type, because "no
  ice cream" is a real stop while the dessert stays on sale and the KDS stop list is
  where staff will try to set it. And a stop set from a kitchen device carries a
  required `expires_at`, defaulting to the location's business-day end through
  ADR 0030: an indefinite stop set by a night cook removes a dish for weeks and
  nobody can tell whether that was deliberate. Expiry writes an un-stop movement
  into the ledger like any other transition.
- **ADR 0033.** Ticket state is never read from cache for a correctness decision.
  The VDU projection may be cached with event invalidation; a station advance reads
  and writes PostgreSQL.

## APIs and events

```text
GET  /api/v1/kitchen/locations/{locationId}/tickets?stream=pickup|delivery|buffer
POST /api/v1/kitchen/tickets/{ticketId}/release | /hold | /handover
PUT  /api/v1/kitchen/tickets/{ticketId}/release-schedule
POST /api/v1/kitchen/ticket-items/{itemId}/start | /ready | /recall
GET  /api/v1/kitchen/locations/{locationId}/expo/order-lookup
PUT  /api/v1/kitchen/locations/{locationId}/suspension
POST /api/v1/control-plane/locations/{locationId}/kitchen/stations | /devices

KitchenTicketCreated / Held / Released
KitchenTicketProductionStarted
KitchenTicketItemReady / KitchenTicketReady / KitchenTicketRecalled
KitchenHandoverCompleted
KitchenRoutingUnresolved
KitchenBranchSuspended / KitchenBranchResumed
```

Every mutation carries `Idempotency-Key` and an expected version per ADR 0031;
station advances are retried blind by an offline client and must settle once. Events
carry ticket, order, location, station, and version references only — no dish names,
no customer name, no address, no phone, no pickup code. A display resolves names
through an authorized read against the ADR 0019 order snapshot.

## Testing

- Two devices marking the same item ready settle once, and the loser sees the
  settled state rather than an error a cook must interpret; an offline client
  replaying twelve queued advances produces twelve transitions, not twenty-four.
- Three stations finishing in the same second propose exactly one order `READY`.
- A recall after handover is refused; before handover it succeeds and emits the
  exception rather than reversing the order.
- Expo handover writes no verification record of its own: with ADR 0040's challenge
  present, completion is refused unless the challenge is `VERIFIED` or `BYPASSED`,
  and an expo principal cannot bypass without `marketplace.handover.bypass`; with
  the table absent, completion succeeds and is recorded as unverified.
- A capacity ceiling shifts release and never rejects; a shift past the promise
  raises the exception.
- Routing resolves through all five levels, and an unmapped variant lands on the
  fallback station with the event, never nowhere.
- A location-scoped device principal is denied at a sibling location and at brand
  scope, at both the application and SQL boundary.
- A stop set from a device expires at business-day end and writes an ADR 0017
  un-stop movement.

## Rollout

1. Stations, routing rules, and the resolver, with tickets created and readable but
   no order proposals — one branch runs the screen beside paper.
2. Station advances and order proposals for that location, with the operations order
   list as the fallback control.
3. Buffer, scheduled release, and capacity shifting.
4. Expo and handover recording, then branch suspension and dispatch from the device.
   Challenge verification is added when ADR 0040 lands its table, and is not a
   precondition for the rest of this step.

Rollback disables ticket-driven proposals and returns `PREPARING` and `READY` to
manual operator action. Tickets, items, and events are retained; they are the
evidence for whatever went wrong.

## Consequences

### Positive

- A dish, a station, and an order each have somewhere true to live, and the
  commercial order stops being asked to mean four things at once.
- Hold-and-fire exists, so a preorder for 20:00 is cooked at 19:30 and a branch
  absorbs a rush by staging release instead of refusing orders.
- The four dangerous powers on a kitchen screen are four grants with four audit
  trails, so "the kitchen closed the branch" names a person and a reason.
- A device is revocable in one row, which a shared manager login never was.

### Negative

- A second state machine per order is a second thing that can get stuck, and "why is
  this ticket still on the pass" needs its own tooling on top of ADR 0019's six
  process managers.
- Routing has five resolution levels — four more places to look when a dish appears
  on the wrong screen — and the fallback station accumulates every unmapped item.
- The release scheduler is a third durable timer after ADR 0014 sourcing and
  ADR 0017 expiry. They should share a leasing implementation and will be tempting
  to write three times.
- Seven capabilities plus three borrowed ones is a lot of grant administration for a
  branch with four staff, and tenants will ask for the single `kitchen` role this
  ADR refused.
- The expo station depends on a table ADR 0040 has not built yet, so handovers
  recorded before then are unverified and the expo screen changes once more when the
  challenge check is added.

### Accepted trade-offs

- Kitchen progress with no order-level meaning is invisible to every existing order
  report and event consumer. That is the point of the separation, and it means
  kitchen analytics is a new read model rather than a new column.
- A capacity ceiling that cannot be met produces a late order and an exception
  rather than a refused order. Refusal belongs at the point of sale, and this ADR
  declines to take that decision away from ADR 0036.

## Implementation checklist

- [x] Station roles are approved and closed in code (`StationRole`). Default role
      bundles and the KDS entitlement question are **still open**: no bundle is
      seeded, and the six capabilities below are granted individually until
      product answers both.
- [x] Station, routing-rule, ticket, item, and event tables added in **V0030**,
      creating schema `kitchen`. No handover table, as decided. **No capacity,
      device, or suspension table** — see "What was not built" below.
- [x] The routing resolver, all five levels, in one statement
      (`JdbcKitchenStore.resolveStation`), with the fallback station and a
      per-line `ROUTING_UNRESOLVED` event.
- [x] The ticket aggregate, the roll-up, and the ADR 0019 proposal command —
      through `OrderProgressPort`, now implemented by
      `ordering.application.OrderProgressAdapter`, so a ticket advancing moves
      the order and a proposal ADR 0019 does not permit is refused as a value
      rather than thrown. Implementation status only; the decision is unchanged.
- [x] Release modes and the release scheduler (`KitchenReleaseWorker`), polling
      with `FOR UPDATE SKIP LOCKED` like ADR 0019's timers. Not a shared leasing
      abstraction: see the note under "Decisions taken here".
- [ ] Extend ADR 0017 with modifier-option stock items and expiring stops. **Not
      done.** It is ADR 0017's schema and its module, and this ADR has no claim on
      either.
- [ ] Device enrolment, revocation, station-filtered reads, and expo handover.
      **Not done** — rollout step 4.
- [x] Concurrency, replay, routing, capability-shape and isolation tests, in
      `src/test/java/uz/horecaos/platform/kitchen/KitchenExecutionTests.java`.

### What the legacy `kitchens` table turned out to be

Both this ADR's context ("the legacy `kitchens` table is location-owned") and
`docs/domains/legacy-profile-findings.md` section 4 ("a preparation-station
classification shared across the estate, which is precisely ADR 0041's production
routing") were checked against `milliy/backend/app/models/product.py` before V0030
was written, and neither survives.

`Kitchen` has six columns — i18n name, i18n description, status, priority,
nullable image — and no `vendor_id`, `company_id` or branch reference of any kind.
It is therefore **not location-owned**; that sentence in the context above is
wrong. It is also not a station classification: `Category` in the same file has
exactly the same six columns, the two share the same dashboard CRUD, both are
embedded side by side in `ProductSchema`, and no code anywhere routes, filters or
groups by kitchen. A preparation station has no marketing description and no
photograph. `kitchens` is a **second estate-wide catalogue taxonomy beside
`categories`** — cuisine, "кухня" as in узбекская / европейская. The seed makes
the point: all six seeded products share one `kitchen_id`, which is byte-for-byte
the same UUID as a category id.

The consequence is a scope fact. **There is no station data in the legacy estate
to TRANSFORM**, and this ADR's claim to close the `kitchens` half of ADR 0016's
open input does not hold: production routing is greenfield, and every station and
every rule has to be authored per location before a branch can run a screen.
Seeding stations from three cuisine rows would give twelve branches an identical
three-station layout matching no kitchen any of them has, and the first symptom
would be dishes on the wrong pass during service. The disposition of the
`kitchens` rows themselves is a catalogue-taxonomy question and returns to
ADR 0016.

### Decisions taken here that the ADR left open

- **`target_ready_at` is the stored promise less its travel component.** ADR 0041
  names ADR 0014's `estimated_ready_at`, which does not exist. V0023's
  `ordering.orders.promised_at` does, it is what the customer was actually told,
  and it is decided once and never recomputed. Travel comes off it because the
  promise is when the customer eats and the target is when the kitchen finishes. A
  null travel component means travel was not modelled, so nothing is subtracted:
  guessing a road time would produce a target the branch is measured against and
  nobody chose.
- **`prep_estimate` is the promise's own preparation component**, not a second
  resolution through ADR 0036's bands. Re-resolving keys on a different instant and
  can disagree with the number the customer was quoted, and a kitchen working to a
  different estimate than the promise is a branch late against a target it never
  saw.
- **`sequence_label` is a copy of `ordering.orders.public_order_number`, not a new
  counter.** ADR 0041's "reset per location per business day" describes the
  counter V0022 already has. A second one drifts the moment an order exists
  without a ticket or a ticket is voided, and the cook is then shouting a number
  that is not on the customer's receipt.
- **Ticket items carry no dish name and no note**, against the ADR's sketch of the
  table. The name has one authority in `ordering.order_lines`, and the note is the
  customer's own words under ADR 0029 envelope encryption — a plaintext copy in
  the kitchen schema would put personal data outside the envelope, which ADR 0029
  forbids outright. A display resolves both through an authorized read against the
  order, which is what the ADR already requires of its events.
- **`ticket_events` carries no `device_id`**, because devices are not built. A
  nullable reference to a table that does not exist is not evidence, and a device
  id nothing writes reads as "no device" for every row that had one.
- **Routing rules address a catalogue node through three real foreign keys**, one
  populated, rather than a `node_type` discriminator over an untyped `node_id`. The
  discriminator has no referential integrity: a rule would survive the deletion of
  the dish it routes.
- **An override pointing at an archived station falls through to the next level.**
  An archived station resolves to a screen nobody watches, which is
  indistinguishable from losing the dish.
- **At most one active station per role per location**, as a partial unique index.
  The brand layer resolves a role to "the location's station carrying it", and with
  two grills that question has no answer.
- **The endpoints nest under
  `/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen`**, not the
  flat `/api/v1/kitchen/locations/{locationId}` the ADR writes. ADR 0025's scope
  resolution reads all three identifiers from path variables, so the flat path
  cannot express `LOCATION` scope at all and the ADR 0025 build gate refuses it.
- **`kitchen.station.manage` was added**, which the ADR does not name. Station
  layout is the head chef's knowledge of their own kitchen; `location.write` opens
  and closes branches; and a routing rule decides where every future order of a
  dish appears, so a line cook must not hold it.
- **The release scheduler is a third `SKIP LOCKED` poller, not a shared leasing
  abstraction.** The ADR predicted it would be written three times. It has been:
  extracting the shared shape from one example fixes an API against a single case,
  and the extraction is worth doing once ADR 0017's expiry timer exists to extract
  it alongside.

### What was not built, and why it is absent rather than partial

- **`kitchen.branch_suspensions` must not be built.** `tenant.location_service_state`
  from V0020 already carries `FORCE_CLOSED` with a mandatory `reason_code` and an
  `effective_until` — the same fact, with the same auto-expiry, under ADR 0036's
  `location.service-state.change`. A second suspension record is the mistake this
  ADR refuses twice already, once for the stop list and once for the handover
  challenge. The capability `location.serviceability.suspend` in the table above is
  therefore **not added**: the kitchen device calls the existing serviceability
  endpoint under the capability that already exists. The table above should be
  amended.
- **`kitchen.station_capacity` and throughput shifting.** Configuration no code
  reads is worse than no configuration: an operator would set a ceiling that
  silently does nothing. `release_at` is computed from the promise and the prep
  estimate with no queue offset.
- **`kitchen.devices`, the VDU projection, and station-filtered device reads.** A
  device row with no principal behind it grants nothing and revokes nothing, which
  is exactly the shared-manager-login problem the row exists to solve.
- **Expo, handover, and `kitchen.handover.complete`.** Rollout step 4. Nothing
  records `handed_over_at` today except the state machine that forbids a recall
  after it.
- **External event contracts.** `KitchenTicketCreated`, `KitchenTicketReady` and
  the rest are recorded in `kitchen.ticket_events` and not published to Kafka. ADR
  0032 requires a contract, a schema file and a row in `docs/domains/events.md` per
  event, and there is no consumer for any of them in this slice — the same call
  ADR 0019 made for `PREPARING` and `READY`.
- **The ordering-side implementation of `OrderProgressPort`.** ADR 0019's command
  path is `ordering.application`, which is module-internal, so the adapter belongs
  to ordering and is a single class there. *Implementation status: this now
  exists — `OrderProgressAdapter` over `OrderStateService.proposeProgress`. The
  stand-in remains for a context without it, and a board served by the stand-in
  still carries `ORDER_PROGRESS_NOT_WIRED` so a branch running the screen knows
  it must advance orders by hand.*

## Exit criteria

A branch runs a full service from screens with no paper tickets: every line reaches
the right station, a preorder fires at its own instant rather than at confirmation,
per-station readiness rolls up to exactly one order-level `READY`, a handover is
recorded against ADR 0040's challenge where that table exists and without one until
it does, and every stop, suspension, dispatch, and payment change made
from the kitchen names the capability, the person, and the device that made it.
