# Operations specification — Couriers

**Section:** the delivery fleet. Who is working, who has what, and what they are owed.
**Audience of these screens:** the restaurant's own dispatcher, shift supervisor, branch
manager, branch cashier and finance clerk. Not Qoida staff.
**IA coverage:** Part 2 §3.1 Dispatch board, §3.2 Live map, §3.3 Couriers, §3.4 Courier
types & rates, §3.5 Shifts & attendance, §3.9 Courier policy, §7.4 Courier reports,
§8.3 Cash reconciliation, §8.5 Courier payouts.
**Owning decisions:** ADR 0014 (sourcing and the internal-courier boundary, Proposed /
in progress), ADR 0042 (compensation, shifts, settlement, Proposed), ADR 0045 (live
position and telemetry, Proposed), ADR 0037 (zones and customer-facing tariffs,
Proposed), ADR 0039 (bulk action semantics), ADR 0027 (approvals), ADR 0025
(capabilities), ADR 0029 (PII), ADR 0030 (policy resolution), ADR 0043 (reporting).

---

## 0. The one thing that decides the shape of this section

An operator on a Friday evening does not think in terms of "our courier" and "Yandex".
They think: *this order is late, who has it, and who can I give it to.* Everything here
follows from that.

**There is one dispatch surface.** A delivery carried by an in-house courier and a
delivery carried by Yandex or Noor sit in the same queue, in the same sort order, with
the same columns. The difference is one cell — *Carried by* — which resolves to a
courier name or a partner name, and one detail block that shows either a shift and a
phone number or an external reference and a partner state. The discriminator already
exists in the model: `fulfillment.assignment_attempts.source_type` with exactly one of
`provider_binding_id` or `courier_id` populated (ADR 0014). The console must never make
the operator choose a tab before they can see their work.

The rest of the section — records, rosters, rate cards, ledgers, statements — is not
service-time work. It belongs behind the dispatch board, not beside it.

---

## 1. What exists today, precisely

This matters because half of this section is buildable now against real tables and half
is a decision away, and a spec that blurs the two produces screens that cannot ship.

| Thing | State | Where |
|---|---|---|
| Provider-neutral delivery contract | **Built** | `integration/api/delivery/DeliveryPartner.java`, `DeliveryCapability.java` |
| Yandex adapter (quote, reserve, confirm, cancel-cost, cancel, query, track) | **Built** | `integration/camel/delivery/yandex/YandexDeliveryAdapter.java` |
| Noor adapter (quote, one-phase create, cancel, query, webhook) | **Built** | `integration/camel/delivery/noor/NoorDeliveryAdapter.java` |
| Camel route, circuit breakers, gateway | **Built** | `integration/camel/delivery/`, `docs/routes/delivery-operation.md` |
| Provider installations and bindings per branch | **Built** | `integration.installations`, `integration.bindings`, `integration.binding_capabilities` |
| Branch registry | **Built** | `tenant.locations` (`id`, `code`, `display_name`, `timezone`, `status`) |
| Orders, lines, totals, state history, timers | **Built** | `ordering.orders`, `ordering.order_state_history`, `ordering.order_timers` |
| Policy and configuration resolution | **Built** | `tenant.policies`, `tenant.policy_current`, `tenant.configuration_values` |
| Approvals and audit | **Built** | `audit.approval_policies`, `audit.approval_requests`, `audit.audit_events` |
| `fulfillment` schema | **Created and empty** | `V0001__create_module_schemas.sql` line 8 — the schema exists, it has zero tables |
| Any courier identity, shift, assignment, ledger or position | **Not built** | see §16 |

`ordering.orders` has **no courier column and no shipment reference**. Today the
platform cannot answer "who is carrying order 1042". Every screen below that names a
courier against an order is blocked on ADR 0014's `fulfillment.shipments` and
`fulfillment.assignment_attempts` landing.

The honest consequence: **§3 (dispatch board) and §4 (live map) cannot ship before ADR
0014's fulfillment schema.** The Delever-parity gap is not the UI; it is that the
platform does not yet record a delivery as a physical object.

---

## 2. Vocabulary, and the two status axes

Every courier has **two independent statuses**, and conflating them is the single most
common mistake in this domain. Delever has one. The Togora prototype has two and is
right.

| Axis | Field | Values | Who changes it |
|---|---|---|---|
| **Account lifecycle** | `fulfillment.courier_profiles.status` (ADR 0014) | `PENDING_ACTIVATION`, `ACTIVE`, `SUSPENDED`, `ARCHIVED` | manager, deliberately, with a reason |
| **Live work state** | derived, see below | `OFF_SHIFT`, `IDLE`, `OFFERED`, `CARRYING`, `AT_BRANCH`, `RESTRICTED`, `STALE` | the courier's own actions and the clock |

The live work state is **derived, never stored as a fourth copy of the truth**:

```
RESTRICTED   an active row in fulfillment.courier_restrictions covering now
OFF_SHIFT    no fulfillment.courier_shifts row in status OPEN
OFFERED      an assignment_attempt in status OFFERED that has not expired
CARRYING     >=1 shipment assigned to this courier not yet DELIVERED/CANCELLED
AT_BRANCH    CARRYING and the live position is inside the pickup radius
IDLE         shift OPEN, no active assignment
STALE        shift OPEN but courier_positions_live.captured_at older than 5 minutes
```

`STALE` outranks `IDLE` and `CARRYING` in display, because a courier whose phone stopped
reporting is the operator's problem before anything else is. `RESTRICTED` outranks
everything.

Every work-state chip carries a **free-text live line beneath it** — the Togora pattern,
and the reason it works is that the code is filterable while the text is human:
`Offer expires 00:41`, `Late 12 min`, `On shift 6h 20m`, `No signal 9 min`,
`Suspended — documents expired`.

**Never use a green dot alone.** A dot says "something", a chip plus a countdown says
what and how long.

---

## 3. Dispatch board — `/delivery/dispatch`

### What it is for
Answer, in one screen and without scrolling, *which delivery needs a human right now and
who can take it* — for in-house couriers and partner couriers together.

### Layout
**Two columns: a severity-sorted queue on the left (fluid), a fleet rail on the right
(fixed 320 px, its own scroll).** Not a kanban board: a kanban forces the operator to
scan five columns to find the one late order, and the state that matters here
(*lateness*) is not a column, it is a property that cuts across all of them. Not a map
first: a map answers "where", the dispatcher's question is "who and when". The map is
§4, one keystroke away.

Below 1200 px the rail collapses to a drawer behind a `Fleet (12)` button; in-row
assignment keeps working, so a tablet on a pass loses nothing but the drag affordance.

### 3.1 Fleet rail (right column)

One card per assignable unit. **In-house couriers and partners are in the same list**,
sorted `assignable first`.

**Courier card fields**

| Field | Type | Source |
|---|---|---|
| Name | text | `fulfillment.courier_profiles` → `iam` principal display name (ADR 0014) |
| Work-state chip + live line | derived enum + text | §2 |
| Courier type | text | `fulfillment.courier_types.name` (ADR 0042) |
| Load | `n / max` + three squares | count of active shipments / `courier_types.max_concurrent_assignments` |
| On shift for | duration | `now − fulfillment.courier_shifts.opened_at` |
| Delivered today | integer | count of `courier_assignment_earnings` rows for the business date |
| Battery | percent + charging glyph | `fulfillment.courier_positions_live.battery_percent`, `device_charging` (ADR 0045) |
| Position age | duration, shown only when > 2 min | `now − courier_positions_live.captured_at` |
| Phone | mono, `tel:` link | `courier_profiles.protected_identity_reference` → ADR 0029 reveal, capability-gated |
| Cash on hand | UZS, shown only when > 0 | running sum of the open shift's `CASH_COLLECTED` less `CASH_HANDED_OVER` from `fulfillment.courier_ledger_entries` |

*Cash on hand belongs on the dispatch card, not only at shift close.* A courier carrying
4 000 000 som of the tenant's money is a supervision fact during service, not an
accounting fact at 23:00.

**Partner card fields**

| Field | Type | Source |
|---|---|---|
| Partner name | text | `integration.installations.provider_type` (`yandex-delivery`, `noor-delivery`) |
| Binding health | chip: healthy / degraded / open circuit | `DeliveryCircuitBreakers` state for `delivery.operation.v1` |
| In flight | integer | shipments with this `provider_binding_id` not terminal |
| Last quote | price + age | most recent `fulfillment.delivery_quotes.price_minor`, `received_at` |
| Acceptance today | percent | accepted / requested `assignment_attempts` for the business date |
| Capabilities | small caps list | `integration.binding_capabilities` — shows `Reschedule: no` for both partners today, which is the fact that decides whether an operator can move a pickup or must cancel and re-source |
| Coverage | "in zone" / "out of zone" for the selected row | last quote's failure code (`CancelledOutOfZone`, `CancelledOutOfRange` for Noor) |

**Card ordering:** assignable in-house couriers with free capacity → in-house at
capacity → partners with a healthy binding → `STALE` couriers → `OFF_SHIFT` couriers →
`RESTRICTED`/suspended → partners with an open circuit.

Off-shift couriers **stay in the list, disabled, with the reason**. "Where is Shoxrux"
is a question the rail should answer rather than dodge; removing the row makes the
dispatcher phone him to find out.

**Rail actions:** click a card to filter the queue to that courier (toggles);
`Message` (ADR 0020 push to the courier app, not built); `End shift` (supervisor
capability, opens §7); `Restrict` (opens a reason-coded restriction dialog, confirm
required).

### 3.2 Queue (left column)

**Columns**

| # | Column | Type | Source |
|---|---|---|---|
| 1 | Select | checkbox | client |
| 2 | Order | mono short id + channel caption | `ordering.orders.public_order_number`, `channel_code_snapshot` |
| 3 | Pipelines | two dot-strips: kitchen and logistics | kitchen from `ordering.order_state_history`; logistics from `fulfillment.delivery_plans.status` + `shipments.status`. Stage label under every stage, timestamp under every completed stage as visible text |
| 4 | Timing | countdown or "late by N min", severity-toned | `fulfillment.delivery_plans.promised_delivery_end` vs now; `pickup_window_start/end`, `latest_assignment_at` drive the amber cases |
| 5 | Branch | text | `tenant.locations.display_name` |
| 6 | Customer | name + mono phone | `ordering.order_customer_snapshots` |
| 7 | Address | one line + zone chip | order delivery address snapshot; zone from `fulfillment.service_zone_versions` (ADR 0037) |
| 8 | Distance | km, one decimal | `fulfillment.delivery_quotes.distance_meters`, or the routing distance stamped on the assignment |
| 9 | Carried by | courier name + vehicle, **or** partner name + external ref, **or** the assign control | `assignment_attempts.source_type` / `courier_id` / `provider_binding_id` / `external_assignment_id` |
| 10 | Payment | method + "collect X som" when cash | `ordering.orders.payment_status_projection`; amount = `total_minor` less captured less loyalty applied |
| 11 | Total | UZS, right-aligned, tabular | `ordering.orders.total_minor` |

Column 3 is the widget worth building properly. **Production and delivery are two clocks
that can disagree**, and that disagreement is exactly the difference between "the kitchen
is late" and "the courier is late" — which in turn decides `LATE` vs `LATE_EXCUSED` on
the courier's pay (ADR 0042). A single linear status bar destroys the distinction the
whole compensation model rests on.

**Row severity — three channels, strict precedence** (background tint, 3 px left rule,
reason caption under the order id). Precedence: `problem` outranks `late` outranks
`at risk`; the lower caption is suppressed when a higher one is present. Normal rows get
a transparent left border so alignment holds.

- **problem** (error tint): plan `MANUAL_ACTION_REQUIRED`; `assignment_attempts.uncertain_outcome = true`; `RETRY_PENDING` past its second attempt; sourcing exhausted with no candidate.
- **late** (error tint, lower weight): `now > promised_delivery_end` and not delivered.
- **at risk** (warning tint): `now > latest_assignment_at` with no active assignment; offer expiring in < 60 s; kitchen `READY` for > 5 min with no courier at branch; courier `STALE` while carrying.

Reason caption is the real text, not a badge: *"Noor create timed out — state unknown,
query before retrying"*, *"Ready 8 min, no courier assigned"*, *"Offer expires in 22 s"*.

### Sort order

Severity, then promise. Never by creation time — a queue sorted by time makes the
operator find the emergency instead of showing it to them.

```
0  problem: manual action required, or uncertain provider outcome
1  late and undelivered, worst first
2  past latest_assignment_at, unassigned
3  kitchen READY, unassigned
4  offer outstanding, expiring soonest first
5  unassigned, source_at passed
6  assigned / picked up, on the road
7  scheduled for later today
tie-break: promised_delivery_end ascending, then public_order_number
```

Rank 0 above rank 1 is deliberate. A late order is bad; an order where the platform does
not know whether a courier was booked is *two couriers or none* and a double charge.

### Filters

- **Status tabs with live counts, computed before filtering** (so a count never collapses
  as the selection narrows): `Needs a courier (7)` · `Offered (2)` · `On the way to
  branch (3)` · `On the road (11)` · `Problem (1)` · `All (24)`. `Problem` renders in the
  error tone whenever its count > 0.
- **Branch** — dropdown, multi-select, default: every branch the user has a grant for.
  Trigger shows the applied value, and shows that it is filtering.
- **Carried by** — combobox with async search over couriers and partners, grouped
  (`In-house` / `Partners` / `Unassigned`). `Cannot be assigned` group holds off-shift
  and restricted couriers, disabled.
- **Zone** — dropdown from `fulfillment.service_zones` where `zone_role = 'DELIVERY'`.
- **Source** — segmented: `Any` / `Our couriers` / `Partners`.
- **Date range** — defaults to **today**, in the branch timezone (`tenant.locations.timezone`).
  Tomorrow is one click, because pre-orders are the reason ADR 0014 exists.

Filters live in query params so they survive a round trip into an order and back.

### Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Assign to courier | Creates an internal `assignment_attempt` and offers it | `courier.assign` capability; a candidate with an open shift under `ENFORCED`, free capacity, no active restriction, courier type matching the distance band | The row already has an active winner; the courier is at `max_concurrent_assignments`; the courier has no `OPEN` shift and `courier.shift.enforcement = ENFORCED` | No — reversible |
| Reassign | Cancels the current internal assignment and offers to another | as above + `courier.reassign` | The shipment is `PICKED_UP` (the food is in the bag; this becomes a return, not a reassign) | Yes — names both couriers |
| Unassign | Returns the plan to `WAITING_TO_SOURCE` | `courier.assign` | Shipment `PICKED_UP` or later | Yes |
| Call an external courier | Runs quote → score → single-winner booking through `delivery.operation.v1` | `delivery.source` capability; a bound partner covering the zone | No partner binding covers the branch; every candidate circuit is open | Only when a quote is already shown and the new quote differs — then the **quote-delta confirmation**: old price, new price, delta, accept or abandon |
| Re-source | Cancels the losing hold, re-runs sourcing | `delivery.source` | Plan is `COMPLETED`/`CANCELLED` | Yes when a cancellation fee is possible |
| Check cancellation cost | Calls `QueryCancellationCost` | `delivery.source` | Partner lacks `QUERY_CANCELLATION_COST` — for Noor this returns **UNCERTAIN, not zero**, and the dialog must say "Noor cannot tell us what cancelling costs" | No |
| Cancel the shipment | Cancels at the partner with a cost classification | `delivery.cancel` | Provider policy forbids it | Yes — states the cost or that the cost is unknown |
| Open order | Navigates to order detail (§1.2 IA) | `order.read` | — | No |
| Call courier / call customer | `tel:` link | phone reveal capability (ADR 0029) | Phone is not revealed to this role | No |
| Mark distance manually | Overrides `distance_meters` with `distance_source = MANUAL` and a reason | `courier.distance.override` | Earning already computed | Yes — states that this changes what the courier is paid |

**Drag a queue row onto a fleet card assigns it.** Delever's dispatcher module is right
that this is fast, and it should exist. But it is an *accelerant, never the only path*:
the authoritative control is the in-row combobox, because a dispatcher does this
mid-phone-call with one hand, because drag is unreachable by keyboard, and because a drop
onto a full or off-shift card must fail loudly rather than silently. A drop target that
cannot accept renders a red hairline and does not accept the drop.

### Bulk actions

Checkbox column, select-all-in-filter with an explicit "all 24 in this filter" affordance.
**An action appears only when it is valid for every selected row** — a rule that is easier
to hold than a per-row error report, and matches ADR 0039's semantics.

| Bulk action | Offered when every selected row… | Notes |
|---|---|---|
| Assign to one courier | is a delivery, has no active assignment or is reassignable, and is at the same branch | The courier picker excludes anyone whose remaining capacity < selection size |
| Call an external courier | has no active assignment and a common partner covers all their zones | Runs as N independent bookings, never one |
| Print | has a POS binding with the print capability | |
| Cancel shipment | has an active, cancellable shipment | Confirmation lists the total possible cancellation cost, and says "unknown" for Noor rows |

Execution follows ADR 0039: N independent commands under one `bulk_operation_id`, each
idempotent on `{bulkKey}:{orderId}`, `202` with a progress row. **Never one transaction** —
a lock convoy during the peak that produced the bulk action, and one already-cancelled
order failing the other 199, so the operator re-runs it and double-assigns every courier
that succeeded. The result panel reports `197 applied, 3 problems`, each problem naming
the order and the reason, with a `Retry the 3` button that re-runs under the same key.

### States

| State | Rendering |
|---|---|
| Loading | Skeleton rows keeping the table frame and header. Counts render as `—`, never `0` |
| Empty (no deliveries) | One full-width row inside the frame: *"No deliveries in flight. 6 couriers on shift."* |
| Empty (filter) | *"No deliveries match this filter"* + a clear-filters action |
| Denied | The board renders; rows the user may not act on show no action affordances at all (omitted, not disabled) driven by the server `actions[]` array. If `order.read` is absent entirely: a single panel naming the missing capability and who grants it |
| Error | Inline banner above the table, the table keeps the last good data with a `stale as of 18:42` marker. Never blank the queue on a failed refresh |
| Live stream down | Amber strip: *"Live updates lost — refreshing every 15 s"*. The polling path must work; push is an accelerator (ADR 0045) |
| No courier on shift anywhere | Warning panel above the queue: *"No courier has an open shift. Orders can still be sent to Yandex or Noor."* with a link to §6 |
| Every partner circuit open | Error panel naming each partner and its next probe time |
| Order is cash and the courier's cash on hand exceeds the configured ceiling | Warning caption on the row: *"Courier holding 4 200 000 som — consider a handover"* |

### Keyboard

Speed matters here more than anywhere else in the console.

```
/          focus search             j / k      previous / next row
Enter      open the order           Space      peek card (order summary, no navigation)
a          focus the row's assign combobox
u          unassign (confirm)       r          reassign
e          call an external courier
x          toggle row selection     Shift+X    extend selection
1..6       switch status tab        g then f   focus the fleet rail
m          switch to the map (§4)   Esc        clear selection, then close, then clear filters
```

Rows are focusable (`tabIndex=0`, Enter opens) with a visible focus outline. Escape closes
modals; focus is trapped in a modal and restored on close.

---

## 4. Live map — `/delivery/map`

### What it is for
See where the fleet actually is, so the dispatcher can pick the courier who is *near*,
not merely the one who is free.

### Layout
Full-bleed map with a left overlay list (the same fleet cards as §3.1, narrower) and a
selected-pin popover. **This is a mode of the dispatch board, not a different screen:**
the same filters apply, `m` toggles between them, and the assign control in the popover
is the same control as the row's. Delever puts dispatch inside the map page; that is
backwards — the map is the minority view, and it must not be the only place assignment
lives.

### What is drawn

| Layer | Content | Source |
|---|---|---|
| Base | Real tiles, Tashkent default centre | map provider |
| Zones | `DELIVERY`-role polygons, hairline, optional | `fulfillment.service_zone_versions.area` (ADR 0037) |
| Branches | Square pins | `tenant.locations` |
| Courier pins | Avatar-free square pin, 3 px border in the work-state colour, name chip beneath, load badge | `fulfillment.courier_positions_live.position` |
| Route | Two-colour split at the current position: origin → now in one tone, now → destination in another | live position + pickup/delivery points |
| Drop pins | Undelivered destinations, tinted by severity | order address snapshots |

**Accuracy floor:** an observation worse than 100 m accuracy is stored in the track but
**never drawn** (ADR 0045). A 900 m accuracy circle rendered as a confident pin is a lie
that sends a courier to the wrong street.

**Staleness:** a position older than 10 minutes never updates the pin; the card shows
`No signal 14 min` and the pin renders hollow at the last known point. Otherwise a
courier's pin jumps across the city when their phone reconnects.

**Partner couriers**: Yandex and Noor do not expose live coordinates to us. Partner
shipments render as a **destination pin only**, with a caption
*"Yandex — tracking link"* opening the partner's own tracking URL from
`TrackShipment`. Do not draw a fake partner courier position. Say what is not known.

### Filters
Branch, zone, work state (`On shift` / `Carrying` / `Idle` / `No signal`), and a
`Show drops` toggle. Inherited from the board through query params.

### Actions
Click a pin → popover (clamped to stay on canvas) with the courier card fields, their
current deliveries as a short list, `Call`, `Assign selected order`, `Open courier`.
Click a drop pin → the queue peek card, with `Open order`.

### States
Loading: map renders, pins fade in, list shows skeletons. Empty: *"No courier has an open
duty session. Positions are only collected while a courier is on shift."* — which is the
correct explanation, not an error. Denied: `courier.location.read` missing → the map
renders branches and drops, no courier pins, with a one-line note.

### Privacy, stated on the screen
A live map of named employees is the most sensitive surface in the console. The page
carries a persistent caption: *"Positions are collected only while a shift is open and
are deleted one hour after it closes."* Bulk historical reveal of one courier's track is
an ADR 0029 audited operation and is **not** available from this screen.

---

## 5. Courier list — `/delivery/couriers`

### What it is for
Find a courier, see whether they can work today, and open their record.

### Layout
List with a search-first header. Not master-detail: the record (§6) has seven tabs and
deserves its own route so a manager can link a colleague to a courier's ledger.

### Columns

| Column | Type | Source |
|---|---|---|
| Name | text, emphasis | `courier_profiles` → principal display name |
| Work state | chip + live line | §2 |
| Account status | chip: Active / Suspended / Pending / Archived | `courier_profiles.status` |
| Type | text | `courier_types.name` (vehicle class) |
| Branches | chips, `+2` overflow | `dispatch_pool_locations` via `dispatch_pool_couriers` (ADR 0014) |
| Phone | mono, revealed per capability | ADR 0029 protected value |
| Today | `n delivered · k km · h:mm on shift` | `courier_assignment_earnings`, `courier_shifts` |
| On-time (30 d) | percent, tabular | `ON_TIME` count / (`ON_TIME` + `LATE`) over `courier_assignment_earnings`. `LATE_EXCUSED` and `UNKNOWN` are excluded from both sides and shown in the tooltip — a courier must never be scored down for a late kitchen |
| Balance | UZS signed, right-aligned, `.q-tnum` | sum of `courier_ledger_entries.amount_minor` in the open settlement period |
| Documents | chip only when something expires within 30 days or has expired | licence/registration expiry on the protected profile |

Balance sign convention, stated in a caption under the column: **positive means the
tenant owes the courier; negative means the courier holds the tenant's cash.** One
balance, not two (ADR 0042). Money is `--q-ink`, right-aligned, never green or red —
except that a negative balance beyond the configured cash ceiling gets a warning caption
line, not a coloured number.

### Filters
- **Tabs with counts:** `On shift (6)` · `Available (2)` · `Carrying (4)` · `Off shift (9)` · `Suspended (1)` · `All (16)`
- **Branch** dropdown, **Type** dropdown, **Search** (debounced 400 ms, matches name and
  phone; the legacy dashboard's `couriers/list?search=` behaviour, which staff use daily)
- **Documents** dropdown: `Any` / `Expiring soon` / `Expired`

### Sort
`Suspended` and `Documents expired` first (they block work), then `On shift` by load
descending, then `Off shift` alphabetically. A manager opening this list at 17:00 wants
the blockers, not the alphabet.

### Actions
`Add courier` (§6.1), row → open record, row menu: `Open shift on their behalf`
(supervisor capability, audited, reason required), `Suspend` (reason from a managed
registry, confirm), `Restrict from dispatch until…` (reason + expiry, confirm),
`Reset courier app access` (triggers a Keycloak credential reset — **never** sets a
password, see §17), `Archive` (confirm, only when balance is zero and no open shift).

### Bulk
`Assign to branch` and `Add to dispatch pool` — offered only when every selected courier
is `ACTIVE`. No bulk suspend: suspension needs a per-person reason.

### States
Empty: *"No couriers yet. Add one, or dispatch through Yandex and Noor."* with both
links. Denied: `courier.read` missing → capability panel. Suspended row: muted ink,
account-status chip in warning tone, reason as the caption, no action affordances except
`Open` and `Reinstate`.

---

## 6. Courier record — `/delivery/couriers/:id`

### What it is for
Everything about one person: who they are, what they may drive, what they did, and what
they are owed.

### Layout
Header block (identity line + two status chips + the four numbers that matter) then
**seven tabs** — the observed ceiling from the Togora review is nine; seven is enough.

Header numbers: `Delivered today` · `On shift` · `Balance (open period)` · `On-time 30 d`.

| Tab | Contents |
|---|---|
| Overview | Profile fields, branches, pool membership, courier type, app account state |
| Work | Assignment history — the delivery log |
| Shifts | Shift history and roster variance |
| Money | Ledger, adjustments, statements, payouts |
| Vehicle & documents | Protected fields, expiry dates, photos |
| Restrictions | Blocks, with reason, actor and expiry |
| Audit | `audit.audit_events` filtered to this subject |

### 6.1 Overview tab / create form

Fields, with the legacy dashboard's set preserved because staff already fill it in:

| Field | Type | Required | Source / note |
|---|---|---|---|
| First name, last name | text | yes | principal profile |
| Phone | text, `+998XXXXXXXXX` mask and regex | yes | legacy enforced exactly this pattern; keep it |
| Photo | image upload | no | `media.assets` + `catalog.media_relations` pattern. Legacy made it required; it should not be — a courier who starts tonight should not be blocked on a photo |
| Courier type | select | yes | `courier_types` (ADR 0042) |
| Branches | multi-select combobox | yes, ≥1 | `dispatch_pool_locations`. Legacy called these "vendors" |
| Dispatch pools | multi-select | no | `dispatch_pools` (ADR 0014). Replaces legacy "courier groups" |
| Passport / ID card | text, **protected** | per policy | ADR 0029 `PERSONAL_SENSITIVE`, write-only in the form, masked on read |
| PINFL (ЖШШИР) | text, 14 digits | per policy | protected. Legacy validated `^\d{14}$` |
| Driving licence | text, protected | no | required when the courier type is motorised — a rule Delever and legacy both lack |
| Vehicle registration ID | text | no | |
| Vehicle plate number | text | no | |
| Vehicle fuel type | select | no | legacy had free text; make it an enum for the fuel-cost report |
| Emergency contact | text, protected | yes | |
| Address | structured address | no | uses the V0021 address schema |
| Referral | text | no | legacy required it; make it optional — it blocks onboarding for nothing |
| Notes | repeatable text | no | legacy `notes: string[]`, protected free text |
| Account status | select | yes | `courier_profiles.status` |
| App account | read-only state + `Send invite` / `Reset access` | — | Keycloak courier client (IA 2.6) |

**Layout:** three columns on desktop, as the legacy form had, because that form is muscle
memory. Sections: *Person*, *Documents*, *Vehicle*, *Work*. Sentence-case labels.

**Actions:** `Save` (optimistic concurrency on `version`; a conflict shows what changed and
by whom, never a silent overwrite), `Cancel`, `Suspend`, `Archive`.

### 6.2 Work tab — the delivery log

One row per completed or cancelled assignment. This is what Delever exposes only inside
the courier's own phone app; it belongs in the console too, because the dispute always
arrives at the manager.

| Column | Source |
|---|---|
| Date, time | `courier_assignment_earnings.computed_at`, order times |
| Order | `ordering.orders.public_order_number` |
| Branch | `tenant.locations.display_name` |
| Distance | `courier_assignment_earnings.distance_meters` + `distance_source` chip (`Routing` / `Estimated` / `Manual`) |
| Promised / delivered | `promised_delivery_end`, `shipments.delivered_at` |
| On-time outcome | chip: `On time` / `Late` / `Late — kitchen` / `Unknown` from `on_time_outcome` |
| Earned | `total_minor`, with `fixed / per order / per km` on hover-free expansion |
| Cash collected | signed entry from the ledger |
| Geo | `Verified` / `Unverified` from the ADR 0042 status-transition position check |

`distance_source = MANUAL` renders with a warning caption naming who overrode it and why.
`Late — kitchen` (`LATE_EXCUSED`) renders in neutral tone, never in the error tone: the
courier is not at fault and the screen must not imply they are.

Filters: date range (default 30 days), branch, on-time outcome, `Geo unverified only`.
Sort: newest first — this is a history, not a queue, and here time *is* the severity.

### 6.3 Shifts tab
Rows from `fulfillment.courier_shifts`: date, opened at, closed at, close source
(`SELF` / `SUPERVISOR` / `AUTO_CLOSED`), paid seconds, roster variance, approval state,
cash handover result. `AUTO_CLOSED` rows are tinted amber with the caption *"Auto-closed —
needs approval before it is paid"*, because paying an unreviewed self-opened shift pays
someone who opened the app at home.

### 6.4 Money tab
The ledger, rendered as an append-only list (§13), plus the statement list (§14) and a
`Record an adjustment` action (§11).

### 6.5 Vehicle & documents tab
Protected fields with a **reveal** affordance per field; each reveal writes an
`audit.audit_events` row. Expiry dates with a 30-day warning chip. Photos of documents,
where retained, sit behind the same reveal.

### 6.6 Restrictions tab
`fulfillment.courier_restrictions`: type, reason code, from, until, decided by, status.
`Add restriction` requires a reason code from the managed registry and a confirmation
naming the courier and the effect (*"Alisher will receive no offers until 25.08 18:00"*).

### 6.7 Audit tab
`audit.audit_events` filtered by subject, in the flat four-column shape validated by the
Togora prototype: date · time · action · actor — where the actor column is
**first-class for non-human actors** (`Auto-dispatch`, `System`, `GPS`, `Sweeper`), per
ADR 0027.

---

## 7. Shift board — `/delivery/shifts`

### What it is for
The supervisor's question at the start and end of service: who is actually working, and
who should be but is not.

### Layout
Dashboard-then-list. Top: four tiles (`On shift now` · `Rostered but not opened` ·
`Open past their roster` · `Awaiting approval`). Below: today's shifts as a list, and a
**coverage strip** — 15-minute cells across the service day, one row per branch, cell
tint by `open shifts / rostered shifts`, hovering a cell names the couriers. This is the
Togora heat-strip arithmetic; use a **single-hue sequential ramp**, not four hues, since
coverage is ordinal.

### Columns

| Column | Source |
|---|---|
| Courier | `courier_shifts.courier_id` |
| Branch | `courier_shifts.location_id` |
| Rostered | `courier_roster_entries.planned_start/end` |
| Opened | `courier_shifts.opened_at` + `open_source` |
| Closed | `closed_at` + `close_source` |
| Paid hours | `paid_seconds` formatted `h:mm` |
| Variance | `variance_seconds`, signed, with a chip when over threshold |
| Cash | handover status chip: `Not declared` / `Declared` / `Confirmed` / `Variance` |
| Approval | `approval_request_id` state |
| State | `OPEN` / `CLOSE_REQUESTED` / `RECONCILING` / `CLOSED` / `AWAITING_APPROVAL` / `SETTLED` |

### Filters
Tabs with counts: `Open (6)` · `Needs approval (2)` · `Cash unconfirmed (3)` ·
`Missed roster (1)` · `Closed today (4)` · `All`. Then branch dropdown, courier combobox,
and a date range defaulting to today.

### Sort
```
0  cash variance unresolved
1  awaiting approval
2  auto-closed, unapproved
3  rostered and not opened, start time passed
4  open, past roster end
5  open, normal — longest running first
6  closed
```
Longest-running open shift first inside rank 5, because a courier on hour eleven is the
one who needs relieving.

### Actions

| Action | Needs | Confirm |
|---|---|---|
| Open a shift on a courier's behalf | `courier.shift.open` over another principal + supervisor grant; audited with reason | Yes — this creates paid hours |
| Close a shift | `courier.shift.approve` | Yes when the courier is still carrying orders — the dialog lists them |
| Approve hours | `courier.shift.approve`; the requester may not approve (ADR 0027 four eyes) | Yes, showing rostered vs actual and the variance |
| Adjust paid hours | `courier.shift.approve` + reason code | Yes — states that this changes pay |
| Publish roster | `courier.roster.manage` | Yes, names the week and the courier count |

**Roster vs shift is a first-class distinction on this screen and must be legible.** The
roster is what a manager planned; the shift is what happened; only the shift produces
paid hours (ADR 0042). Rendering them in one column with one clock is how a roster
silently becomes payroll.

### States
- **Enforcement mode banner:** the resolved `courier.shift.enforcement` value for the
  selected branch, shown as a persistent chip: `Enforced — an off-shift courier gets no
  offers`, `Advisory — logged, not blocked`, `Off`. Resolved through ADR 0030; the
  resolved value and version are snapshotted onto every assignment attempt, so tightening
  the policy in October does not make September look illegal. The screen should say which
  mode is live, because "why can't Alisher take this order" is otherwise unanswerable.
- Empty: *"No shifts today. Enforcement is Advisory, so couriers can still take orders."*
- Denied, error, loading: as §3.

### Keyboard
`j/k` rows, `Enter` open the courier, `p` approve, `c` close, `1..5` tabs.

### 7.1 Roster editor — `/delivery/shifts/roster`
A week grid: rows = couriers, columns = seven days, each cell one or more planned
intervals. Drag to create, drag edges to resize, click to edit exact times.
`DRAFT → PUBLISHED` per week per branch, with `Publish` as an explicit action — a draft
roster must never gate a courier. Copy-last-week is one button and is the action that
gets used. Coverage total per column against a target, so the planner sees the hole
before service does.

---

## 8. Shift close and cash reconciliation — `/finance/cash-handovers`

### What it is for
Account for the tenant's money that a courier is carrying, at the moment they stop
carrying it.

### Layout
Queue, then a two-column reconciliation panel. This is a **cashier's screen at a branch**,
used at 23:00 by someone counting notes, so it must be usable with one hand and must show
one number at a time.

### The three figures, always shown separately

Delever's courier hands in a report with nowhere to record it. Ours records three
figures and **never absorbs a variance into another number** (ADR 0042):

| Figure | Field | Who produces it |
|---|---|---|
| Expected | `courier_cash_handovers.expected_minor` | the platform: sum of `CASH_COLLECTED` entries for the shift, each being order total less anything already captured less the loyalty amount applied |
| Declared | `declared_minor` | the courier, in the app |
| Confirmed | `confirmed_minor` | the branch cashier, in this screen |

Two variances, each an explicit ledger entry with a reason code:
`declared − expected` and `confirmed − declared`.

### Queue columns
Courier · Branch · Shift closed at · Expected · Declared · Confirmed · Variance ·
Status (`Awaiting declaration` / `Awaiting confirmation` / `Variance` / `Confirmed`) ·
Orders in the shift (count, links to the filtered order list).

### Sort
`Variance` first, largest absolute first; then `Awaiting confirmation` oldest first; then
`Awaiting declaration`; then confirmed. Money that does not reconcile outranks money that
has not been counted yet.

### Reconciliation panel
Left: the expected breakdown, one row per cash order — order number, total, already
captured, loyalty applied, cash due. Right: a large numeric input for the confirmed
amount, a computed variance that updates live, and a reason-code select that becomes
**required** the moment the variance is non-zero.

### Actions

| Action | Needs | Confirm |
|---|---|---|
| Confirm handover | `courier.cash.confirm`; reason code when variance ≠ 0 | Yes — the dialog states the exact som figure and that the entry is permanent |
| Record a partial handover | same | Yes; leaves the shift in `RECONCILING` |
| Override and close without confirmation | supervisor capability + audited reason | Yes, with strong copy: *"This closes the shift with 1 240 000 som unaccounted. The entry is permanent."* |
| Open the courier's ledger | `courier.ledger.read` | No |

A shift cannot reach `CLOSED` with an unconfirmed handover unless a manager overrides
with an audited reason. Tenants will ask to skip this step; skipping it is how the ledger
stops being true, and the console should make the skip visible rather than easy.

### States
Empty: *"No cash to reconcile. 4 shifts closed today, all card and online."* Denied:
cashier without `courier.cash.confirm` sees the queue read-only with an explanation.
Error on submit: the input keeps its value; never clear a counted figure.

---

## 9. Courier types — `/delivery/courier-types`

### What it is for
Define the vehicle classes that decide which courier is a candidate for which order.

### Layout
Short list plus a modal form. This is configuration, edited monthly.

### Fields

| Field | Type | Source |
|---|---|---|
| Name | text | `courier_types.name` |
| Vehicle class | select: foot, bicycle, scooter, motorcycle, car, van, truck | the legacy `CourierType` enum, kept in full so migration is lossless |
| Minimum distance | metres | `courier_types.distance_band_from` |
| Maximum distance | metres | `distance_band_to` |
| Max concurrent assignments | integer | `courier_types.max_concurrent_assignments` |
| Offer TTL | seconds | `courier_types.offer_ttl_seconds` |
| Max load | text or enum | **not built, no ADR** — see §16, the *gabarit* gap |

List columns: name, vehicle class, distance band, max concurrent, offer TTL, courier
count (clickable, jumps to the courier list filtered to this type).

Delever's Courier Type also carries `Начальная минута` and `Режим работы`, neither of
which is defined anywhere in its documentation. **Do not reproduce them.** The plausible
readings — offer timing and a work mode — are already covered by `offer_ttl_seconds` and
by the shift model, and copying an undefined field imports the ambiguity.

Actions: create, edit, deactivate (blocked while couriers reference it — the dialog names
them and links to them).

---

## 10. Rate cards — `/delivery/rate-cards`

### What it is for
Decide what a courier earns, independently of what the customer pays.

### Layout
List of cards → detail with a component table and a simulator.

**The headline rule, printed on the screen:** *courier earnings never derive from the
customer's delivery charge.* Delever coupled the two and had to ship a correction after
payout disputes. A free-delivery promotion must not pay the courier nothing, and a
distant order priced flat must not underpay the person who drove it. The gap between the
two is margin, and where it is negative it is an ADR 0013 `DELIVERY_COST_SUBSIDY`. This
sentence belongs in the page description, not in a wiki nobody reads.

### Card fields

| Field | Source |
|---|---|
| Name, status, version | `courier_rate_cards` |
| Scope: brand, locations, courier types | `courier_rate_cards` scope columns |
| Valid from / until | `courier_rate_cards.valid_from/valid_until` |
| Priority | `courier_rate_cards.priority` |
| Currency | UZS, whole som |

### Component table (`courier_rate_components`)

| Column | Values |
|---|---|
| Type | `PER_SHIFT_FIXED`, `PER_ORDER`, `PER_KM_BAND`, `PER_ORDER_MINIMUM` |
| From / to metres | for `PER_KM_BAND` only |
| Amount | whole som |
| Priority | integer |

**Band validation is a first-class UI concern.** Bands must cover zero to unbounded with
no gap and no overlap, validated at activation. The editor shows a horizontal band ruler
under the table, tinting gaps in the error tone and overlaps in the warning tone, and the
`Activate` button is unavailable while either exists, with the reason stated. A gap means
an order at exactly the boundary earns nothing, and the courier finds it before the
tenant does.

### Simulator
Inputs: distance, order count, shift length, courier type, branch. Output: the itemised
accrual with each component named. This is the control that stops a rate card being
activated on a guess. It runs against the versioned calculator, not a client-side copy.

### Actions
`Save draft`, `Activate` (ADR 0027 approval; confirm names the scope and the effective
date), `Clone`, `Retire`. **Editing an active card is not possible** — activation creates
a new version. Amounts already accrued are immutable, and the screen says so.

### The three salary models, expressible by zeroing components
Delever publishes guidance describing fixed salary, per-delivery, and mixed. All three
fall out of this model: fixed = `PER_SHIFT_FIXED` only; per-delivery = `PER_ORDER` plus
`PER_KM_BAND`; mixed = all three. Offer them as three **starting templates** in the create
dialog, which is faster than a guide and cannot go stale.

---

## 11. Bonus and penalty rules — `/delivery/adjustments`

### What it is for
Define the automatic adjustments to a courier's balance, and record the manual ones.

### Layout
Two tabs: `Rules` (a rule list with a condition builder) and `Adjustments` (the log of
what was actually applied).

Delever surfaces this concept twice — a fare typed "bonus or penalty" and a separate
bonus/penalty page — and never reconciles them. **We have one mechanism with two
origins:** every adjustment becomes a `courier_ledger_entries` row carrying
`origin = RULE | MANUAL`.

### Rule fields

| Field | Source |
|---|---|
| Name, status, priority, version | rule record (ADR 0042) |
| Effect | `BONUS` or `PENALTY`, and an amount in whole som |
| Window | rolling days, calendar week, calendar month, or one shift |
| Trigger | shift close, period close, or nightly |
| Conditions | typed set: delivered count, `ON_TIME` count, `ON_TIME` rate, `GEO_UNVERIFIED` rate, cash variance count, hours worked |
| Scope | brand, locations, courier types |

Conditions use the shared `ConditionBuilder` (IA component gaps) — the same component as
promotions, auto-add, dispatch rules and automations. **No scripting**, for the reasons
ADR 0018 gives about pricing rules: a rule that cannot be reproduced cannot be defended
in a payout dispute.

Every rule has a **simulator** answering "who would this have hit last week, and for how
much" before it is activated. A penalty rule shipped without that preview is a labour
dispute waiting for a payday.

### Adjustments log columns
Date · Courier · Effect · Amount · Origin (`Rule: <name> v3` or `Manual`) · Reason code ·
Actor · Approval state · Settlement period · Ledger entry id (mono).

### Manual adjustment action
`Record an adjustment` from a courier record or here. Requires an actor, an amount, and a
reason code from the managed registry. **Every manual penalty, and any penalty above the
configured amount, requires ADR 0027 four-eyes approval, and the requester cannot
approve.** A manager who can silently debit a courier's pay is a labour dispute and a
fraud vector in one instrument.

An adjustment cannot be written into a `CLOSED` settlement period; the form says so
before submission, not after, and offers to record it in the open period as a
`PRIOR_PERIOD_ADJUSTMENT` referencing the original.

### Approvals surface
Pending adjustments appear in the shared approvals queue and as a count badge here.
Affordances are driven by the server `actions[]` array — a decided row shows a badge and
**no buttons at all**, rather than disabled ones.

---

## 12. Settlement periods and payout run — `/finance/courier-payouts`

### What it is for
Produce, for one courier and one period, exactly one net figure someone can pay.

### Layout
Period selector, then a table of couriers — the direct replacement for Delever's
Зарплата tab, which is the most important artefact in its Personnel module.

### Columns

| Column | Source | Delever equivalent |
|---|---|---|
| Courier | `courier_settlement_periods.courier_id` | Курьер |
| Delivered | `delivered_count` | Заказы |
| Distance | `distance_meters` → km | KM |
| Hours | `paid_seconds` → `h:mm` | часы |
| On time | `on_time_count` + rate | Вовремя |
| Gross earnings | `gross_earnings_minor` | — |
| Bonuses | positive `BONUS` sum | Бонус |
| Penalties | `PENALTY` sum | Штрафы |
| Cash held | `cash_held_minor` | — (Delever has no such column, which is the defect) |
| Net payable | `net_payable_minor` | К оплате |
| Status | `OPEN` / `CLOSING` / `CLOSED` / `SETTLED` | — |

**Every figure is read from stored facts. Nothing on this screen recomputes anything.**
Two screens showing two different К оплате for one courier is the failure the whole ADR
0042 model exists to prevent, and a report that recalculates is how it happens.

`Cash held` is the column Delever does not have, and it is why its balance is ambiguous.
It answers "we owe him 400 000 and he is holding 900 000 of ours" as one net figure
rather than two numbers nobody can pay against.

### Filters
Period picker (the primary control, not a date range — periods are objects), branch,
courier, and a `Has cash variance` toggle.

### Sort
Unreconciled cash first, then net payable descending. The largest payment and the
unexplained one are the two rows a finance clerk opens.

### Actions

| Action | Needs | Confirm |
|---|---|---|
| Close the period | `courier.settlement.close`; blocked while any shift in the period is unapproved or any handover unconfirmed — the button states which | Yes, with strong copy: **a closed period is never reopened** |
| Generate statements | automatic on close | — |
| Authorise payout | `courier.payout.authorise`; method `CASH_AT_BRANCH` / `BANK_TRANSFER` / `CARD_TRANSFER` | Yes — names courier, amount, method |
| Mark as paid | `courier.payout.authorise` + external reference | Yes |
| Export | column-fidelity CSV/XLSX matching the on-screen columns exactly | No |

**Qoida computes, approves and records the payout; it does not move the money.** A large
share of courier pay here is settled by the courier keeping cash he already collected,
and the payout record makes that a real settlement entry rather than an off-books
arrangement. The screen must say this: *"Recording a payout does not transfer funds."*

### Bulk
`Authorise` and `Mark as paid` across selected rows — offered only when every selected
period is `CLOSED`, has a non-negative net payable, and has no unresolved cash variance.

### States
Period still open: the table renders with a persistent caption *"Provisional — the period
closes on 31.08"* and the close action is unavailable with that reason. Shadow-accrual
mode (the ADR 0042 rollout step): a banner *"Shadow accrual — these figures are not
authoritative"* and a `Compare with the spreadsheet` export.

---

## 13. Courier ledger — inside §6.4, and `/finance/courier-ledger`

### What it is for
Show, line by line, how a balance became what it is.

### Layout
Append-only list, newest first, with a running balance column. **No edit affordance
anywhere**, because the table is `INSERT`/`SELECT` only at the database grant level and a
UI that offers editing lies about the system.

### Columns
Occurred at · Recorded at · Type · Amount (signed, whole som, right-aligned) · Running
balance · Origin · Reason code · Source (order number, shift, payout — each a link) ·
Actor · Approval · Period · Entry id (mono).

Entry types, rendered with plain labels rather than the enum:
`DELIVERY_EARNING`, `SHIFT_EARNING`, `BONUS`, `PENALTY`, `COMMISSION`, `CASH_COLLECTED`,
`CASH_HANDED_OVER`, `CASH_VARIANCE`, `PAYOUT`, `PRIOR_PERIOD_ADJUSTMENT`, `CORRECTION`.

Filters: period, type, origin, date range. Sort: `occurred_at` descending, with
`recorded_at` shown separately so a late-arriving entry is visible as late.

A `PRIOR_PERIOD_ADJUSTMENT` renders with a caption naming the period it corrects and the
original entry, because accountants understand this and couriers find it confusing on a
statement — the caption is for the courier.

---

## 14. Courier statement — `/finance/courier-payouts/:periodId/statement`

### What it is for
The document a courier disputes.

### Layout
Single-column document. It exists to be read on a phone, printed, and argued about.

Contents, all from `courier_settlement_periods` and the ledger lines that produced it:
period dates · courier · gross earnings · adjustments itemised with the rule name or the
person who applied them · cash collected · cash handed over · variances · net payable ·
delivered count · kilometres · hours · on-time count · the statement hash.

**Every bonus, penalty and approved hour names the rule or the person that produced it.**
That single property is the difference between a courier who trusts the number and a
courier who leaves.

Actions: `Download PDF`, `Send to courier` (ADR 0020 channel, not built), `Open the
ledger lines`. No edit. If withholding is required once employment classification lands
(ADR 0042 open input), gross / withholding / net become three lines here and nowhere
else.

---

## 15. Courier performance report — `/reports/couriers`

### What it is for
Compare couriers over a period, for rostering and for rate decisions — not during
service.

### Layout
Filter bar over four tabs. Reports are read sitting down; density beats immediacy.

| Tab | Columns |
|---|---|
| **Leaderboard** | Courier · deliveries · distance · hours · on-time rate · earnings. Top-10 by orders and by distance, as Delever's Дашборд → Сотрудники does |
| **Efficiency** | Deliveries · total basket value carried · min / average / max single-trip distance · total distance · courier type · delivery revenue on those orders · total transit time · longest single delivery |
| **SLA buckets** | Count and percentage of each courier's volume in `< 30 min`, `30–35`, `35–40`, `> 40` |
| **Delivery-fee audit** | Per courier, orders grouped by the customer-facing tariff and zone they were carried under: fee, count, total |

Source: `reporting.agg_sla_bucket_day` with `scope_kind = 'COURIER'`, and the fact rows
carrying `courier_principal_id` (ADR 0043). **These are stored aggregates, not live
queries against the ledger** — the metric layer exists so that a report and a statement
cannot disagree.

Filters: date range (default: last 30 days), branch, courier, courier type. Export to
XLSX with column fidelity.

**The 30/35/40-minute buckets are a reporting projection and nothing else.** They must
never feed pay. On-time for pay is `on_time_outcome`, computed once at delivery from
values snapshotted at acceptance. Delever leaves it ambiguous which of the two is the
truth; this is the disambiguation, and the SLA tab carries a caption saying so.

---

## 16. Courier policy — `/settings/delivery/courier-policy`

### What it is for
The handful of switches that decide what a courier may see, take and do — collected in
one place instead of scattered across five provider pages.

### Layout
Form, grouped, with the **resolution scope selector at the top** (tenant → brand →
location) since these resolve through ADR 0030 and a manager needs to know whether they
are editing the default or an override. Each field shows the inherited value when
overridden.

| Setting | Type | Effect |
|---|---|---|
| Shift enforcement | `ENFORCED` / `ADVISORY` / `OFF` | Whether an open shift gates offer eligibility. Roll out as `ADVISORY` before `ENFORCED`, so false negatives appear in a report rather than as couriers unable to work at dinner |
| Max concurrent assignments | integer, per courier type | The ceiling is enforced by conditional update, not count-then-insert |
| Offer TTL | seconds | An expired offer returns to sourcing and never accrues |
| Show only kitchen-ready orders | toggle | Courier sees and can take only orders the kitchen marked ready |
| Reveal customer location | `before accept` / `after accept` | |
| Acceptance GPS gate | **hard, always** — radius in metres | Accepting from eight kilometres away is never legitimate and blocking it costs an honest courier nothing |
| Pickup / delivery GPS gate | `soft` (default) / `hard`, radius in metres | Soft records `GEO_UNVERIFIED`. A hard gate strands a courier in a stairwell with a customer waiting, and the workaround is marking delivered from the street — worse data and a worse delivery |
| Cash ceiling per courier | som | Above this the dispatch card warns and a handover is prompted |
| Telemetry collection gate | `ON_DUTY` (default) / `ON_ASSIGNMENT` | ADR 0045. `ON_DUTY` is required for the dispatch board to see idle couriers |
| Post-delivery payment check | toggle | Requires payment confirmation before the order closes |

Each row carries a one-sentence consequence line, not a tooltip. A settings page whose
effects are invisible produces support tickets shaped like *"why can't Alisher take this
order"*.

**Deliberately absent: courier billing mode.** Delever's master toggle enables a personal
courier balance from which commissions are debited — a prepaid float the worker tops up.
ADR 0042 rejects it: Qoida does not take deposits from workers, and a courier who cannot
top up cannot work, which trades a payroll problem for a debt-collection problem. The
setting does not exist here, and the parity matrix should record it as refused, not
missing.

---

## 17. What Delever has that we match, beat, or skip

### Match
- **Dispatcher module** — drag an order card onto a courier card to assign; click a
  courier to filter the queue; unassign in place. Genuinely fast, and staff expect it.
- **Bulk courier assignment** from the order list. Delever added it because per-order
  assignment during peaks was unworkable; the same is true here.
- **Live map, 10-second refresh, with name, active order count and battery.** Battery is
  a better idea than it looks: a dispatcher needs to know a phone will die mid-delivery.
- **Courier types by vehicle with a distance band**, and candidate filtering by them.
- **Rate card = fixed + per order + per km**, strictly separate from the customer tariff.
- **Attendance as a gate on order acceptance**, not merely a report.
- **GPS radius validation** on status transitions, with two radii.
- **The Зарплата columns** — orders, km, hours, on-time, penalties, bonus, to be paid —
  with export. This is the artefact accounting actually consumes.
- **Courier efficiency and SLA-bucket reports**, and the top-10 leaderboards.

### Beat
1. **One balance instead of two contradictory ones.** Delever's balance is a wage
   liability on the salary report and a prepaid float in the billing-mode text. Holding
   both produces a courier simultaneously owed wages and in debt for commission. Ours is
   one signed ledger; the answer to "what do we owe" and "what cash is out" is one number.
2. **Cash reconciliation at shift close.** Delever's courier hands in a day report and
   there is nowhere in the admin panel to record what was actually received. Expected,
   declared and confirmed are three separate figures here, and a variance is an explicit
   entry with a reason, never absorbed.
3. **An exact, immutable on-time definition.** Delever never states whether Вовремя is
   the order's promise, a company SLA, or the report's buckets — and it is paid money.
   Ours is computed once at delivery against the promise snapshotted at acceptance, and
   no report recomputes it.
4. **`LATE_EXCUSED`.** When the kitchen handed over after the pickup window closed, the
   courier is not penalised. Penalising a courier for a late kitchen is how a tenant loses
   its couriers, and the branch is the party that can fix it.
5. **Roster and shift as different objects.** Delever's Посещаемость is one screenshot
   with no prose, and the 'часы' column it feeds is paid money. Plan and actual must not
   be one row.
6. **One bonus/penalty mechanism.** Delever surfaces the concept twice with no stated
   relationship. Ours is one ledger entry type with `origin = RULE | MANUAL`.
7. **Four-eyes on manual penalties**, with the requester barred from approving.
8. **In-house and partner couriers on one dispatch surface**, with a partner's declared
   capabilities visible — so an operator learns that neither Yandex nor Noor can
   reschedule a pickup *before* they promise a customer a new time, and learns that Noor
   cannot say what cancelling costs *before* they cancel.
9. **Severity-ordered queues everywhere.** Delever's lists are chronological.
10. **Keyboard-first dispatch.** Delever's is mouse-and-drag only.
11. **Uncertain provider outcomes made visible as the top-priority row.** A Noor create
    that timed out is not idempotent-by-documentation; blind retry books a second courier
    and bills the merchant twice. That belongs on screen, not in a log.

### Skip, with the reason
- **Courier billing mode / prepaid float** — refused by ADR 0042, see §16.
- **`Начальная минута` and `Режим работы` on Courier Type** — undefined in Delever's own
  documentation; the plausible meanings are already covered.
- **Courier mobile-app screens** (profile, earnings, daily report, order history,
  bonus-paid visibility) — a separate deployable. Operations configures and previews it;
  it does not contain it.
- **Careers / vacancies / candidates** — recruiting is not the delivery fleet.
- **Aggregator shift open/close notifications** — belongs to Integrations (IA 10.8/10.9).
- **Operator records and operator reports** — the Personnel section conflates call-centre
  operators with couriers. Qoida splits them: operators are Staff (IA §9), couriers are
  Delivery (IA §3). They share nothing but a sidebar heading in Delever.

### Where the documentation simply does not say
Several Delever pages are **video-only or screenshot-only**, and this specification does
not invent what they contain. Verified on 22.08.2026:
- `personal/couriers` — three embedded videos, no prose. No field list exists in text.
- `personal/courier-bonus-penalty` — one video plus two sentences. **The condition
  language is undocumented**; the typed condition set in §11 is Qoida's own design.
- `personal/courier-attendance` — a single screenshot, no prose at all.
- `admin-panel-1/couriers` (V2) — two videos ("Создание курьера", "Привязка курьера в
  филиал"), no prose.
- `personal/courier-type` and `personal/courier-fare` — field names listed, no list
  columns, filters or actions documented.
- The dispatcher module's drag-and-drop behaviour is described in the parity matrix but
  is **not** in the published `trackcourier` pages, which cover only the map.

---

## 18. What the legacy dashboard did, that staff will expect

From `legacy-archive/qoida-dashboard/src`:

| Legacy behaviour | Where | Carry forward as |
|---|---|---|
| `Все курьеры` / `Группы` / `Площадки` as three sidebar entries | `layout/Sidebar/SidebarMenu.tsx` | Couriers (§5), dispatch pools (§6.1 field), zones (ADR 0037, another section). Keep all three reachable; staff navigate by these names |
| Courier list: index, ФИО, Статус (Активный/Неактивный), edit and delete icons | `pages/Courier/Couriers.page.tsx` | §5 — but with far more columns. Two columns was too few to work from |
| Debounced search across couriers, 500 ms | same | §5 search, 400 ms |
| Three-column create form with exactly the fields in §6.1 | same | §6.1, same grouping and order |
| `+998XXXXXXXXX` phone regex, 14-digit PINFL regex | `schemas/courier.schema.ts` | Keep both, unchanged |
| Full vehicle set: driving licence, registration id, plate, fuel type | same | §6.1 and §6.5 |
| Emergency contact, address, referral, notes, work_time | same | §6.1 — referral demoted to optional |
| Courier groups: name, description, status, areas, vendors | `pages/Courier/CourierGroups.page.tsx` | Dispatch pools with location and zone bindings. "Vendors" means branches |
| Courier areas: name, type (circle / polygon / city), coordinates | `pages/Courier/CourierAreas.page.tsx` | ADR 0037 `service_zones` with `zone_role`. **Circle and city authoring must survive**: `service_zone_versions.authoring_shape` holds the circle or city the manager drew, `area` holds the derived MultiPolygon. Losing circle authoring would be a regression, and ADR 0037's model already anticipates it |
| Assign a courier from the order detail: search box, cards showing ФИО and phone, click to assign | `pages/Order.detail.page.tsx` + `hooks/useApiOrderDetail.ts` | §3 assign combobox, and the same control on order detail. Note the legacy default: candidates were pre-filtered by the order's `vendor_id`, i.e. the branch. Keep that default, with an explicit "search all branches" escape |
| Detach courier with a confirmation dialog | same | §3 unassign, confirmed |
| `courier_note` as a distinct comment channel on the order, beside `internal_note` and `vendor_note` | `types/Order.ts` | Keep three channels. A note to the courier is not a note to the kitchen |
| `DISPATCHER` role that can view couriers but not create, edit or delete | `types/User.ts`, `Couriers.page.tsx` | ADR 0025 capabilities: dispatchers get `courier.read`, `courier.assign`, never `courier.manage`. Affordances omitted, not disabled |
| Courier `online`, `rating`, `rating_count`, `last_order` on the record | `types/Courier.ts` | `online` becomes the live work state (§2); rating stays read-only until reviews have an ADR |

### One legacy behaviour to remove, deliberately

```
toast.success('Курьер успешно создан. Пароль является серийным номером паспорта')
```
— `pages/Courier/Couriers.page.tsx`. **The courier's password was the serial number of
their passport**, a document their employer holds a copy of and which appears in a field
on this very form. Courier app access is provisioned through the Keycloak courier client
(IA 2.6): the console sends an invite or triggers a reset, and never displays, derives or
transmits a credential. IA §3.3 already names this; it is worth naming twice.

---

## 19. Data the backend does not have yet

Nothing in the `fulfillment` schema exists. The schema is created and empty
(`V0001__create_module_schemas.sql`, line 8). Everything below is named exactly as its
ADR names it.

### ADR 0014 — Proposed, implementation in progress (adapters only)
```
fulfillment.delivery_plans            fulfillment.shipments
fulfillment.delivery_quotes           fulfillment.assignment_attempts
fulfillment.delivery_sourcing_jobs
fulfillment.courier_profiles          fulfillment.courier_availability
fulfillment.dispatch_pools            fulfillment.dispatch_pool_couriers
fulfillment.dispatch_pool_locations   fulfillment.dispatch_pool_zones
fulfillment.courier_restrictions      fulfillment.courier_location_observations
```
Blocks: §3 entirely, §4 entirely, §5 and §6 entirely, the *Carried by* column, and any
statement that an order has a courier. `ordering.orders` has no courier or shipment
column today.
Open inputs on the ADR: internal-courier scope, courier PII and location retention.

### ADR 0042 — Proposed, not started
```
fulfillment.courier_shifts            fulfillment.courier_roster_entries
fulfillment.courier_assignment_earnings
fulfillment.courier_ledger_entries    (INSERT/SELECT grant only)
fulfillment.courier_settlement_periods
fulfillment.courier_cash_handovers    fulfillment.courier_payouts
fulfillment.courier_types             fulfillment.courier_rate_cards
fulfillment.courier_rate_components
```
Blocks: §7, §8, §9, §10, §11, §12, §13, §14, and the balance and on-time columns
everywhere else.
Structural open input: **courier employment classification and withholding treatment**
(legal, finance). Until it is answered, §14's statement carries a net figure only; if
couriers are employees, gross / withholding / net become three lines and Qoida becomes a
payroll system of record.

### ADR 0045 — Proposed, not started
```
fulfillment.courier_duty_sessions     fulfillment.courier_positions_live
fulfillment.courier_location_tracks   fulfillment.courier_track_summaries
realtime signal channel (SSE over HTTP/2)
```
Blocks: §4 entirely; the battery, position-age and `STALE` state in §3 and §5; live
counts anywhere. Every live surface must have a working polling path regardless — push is
an accelerator, and the board must be correct at a 15-second poll.
Open input: lawful basis and maximum track retention (legal).

**A trap worth naming in the build.** `courier_location_tracks.distance_meters` and
`courier_track_summaries.distance_meters` are *observed telemetry*.
`courier_assignment_earnings.distance_meters` is the *routing distance quoted at
assignment*. They share a name and answer different questions, and **no accrual,
statement or report may read the telemetry one**. A courier earning must be byte-identical
whether the telemetry for that trip is complete, partial or absent.

### ADR 0037 — Proposed, not started
```
fulfillment.service_zones             fulfillment.service_zone_versions
fulfillment.zone_location_bindings    fulfillment.regions
fulfillment.delivery_tariffs          fulfillment.delivery_tariff_bands
fulfillment.delivery_tariff_time_rules
fulfillment.delivery_fee_resolutions
```
Blocks: the zone chip in §3, zone filters, the zone overlay in §4, the delivery-fee audit
tab in §15, and the migration target for legacy `courier_areas`.

### ADR 0043 — Proposed, not started
```
reporting.agg_sla_bucket_day  (scope_kind = 'COURIER')
courier_principal_id on the order and delivery fact rows
```
Blocks: §15 entirely. Delever's 30/35/40 buckets live here and only here.

### ADR 0039 — Proposed
```
ordering.bulk_operations / ordering.bulk_operation_items
POST /api/v1/operations/order-bulk-actions
```
Blocks: §3 bulk assignment. Bulk courier assignment is named as the first supported
action.

### ADR 0020 — Proposed
Courier-directed messages and statement delivery. Blocks `Message courier` in §3.1 and
`Send to courier` in §14.

### Gaps with no owning ADR at all
1. **Order size and courier capability matching.** Togora's `orderGabarit
   { size, couriersNeeded }` against a courier's `maxGabarit` is a tiny model that decides
   whether a bicycle courier may take a 15 kg order and whether one courier is enough.
   ADR 0042 covers compensation, not capability matching, and ADR 0014's candidate filter
   has nowhere to read a size from. Cheap to model; dispatch is wrong without it.
   Provisional home: `courier_types.max_load` plus an order-level size attribute.
2. **Courier rating and the four-axis review taxonomy** (meal, operator, courier, delivery
   time). The legacy record carries `rating` and `rating_count`; the parity matrix records
   that no ADR owns reviews or feedback intake. §5 shows rating read-only and §15 omits it
   until one exists.
3. **Delivery capacity as a lattice** — windows × capacity lines per zone, which is how a
   pre-booked delivery market is actually planned. ADR 0014 covers scheduled sourcing but
   no slot, window or capacity table exists in the model or the IA. It would serve the
   roster in §7.1 directly.

---

## 20. Cross-cutting conventions for this section

- **Money** is whole som, `.q-tnum`, right-aligned, in `--q-ink`. Never coloured. Format
  `4 200 000 so'm`.
- **Times** are 24h; dates are `DD.MM`; in a table, `18.02 14:32`; the year appears only
  where it matters. Every instant renders in the **branch's** timezone
  (`tenant.locations.timezone`), and a screen spanning branches says which.
- **Absent values are `—`**, never blank and never `0`.
- **Machine data is mono**: ids, phones, external references, entry ids, hashes.
- **Empty states are one full-width row inside the table frame**, keeping the header.
- **Modal state is the id of the record being acted on**, never a boolean, so confirmation
  copy can name the object: *"Alisher Karimov's shift will be closed with 1 240 000 som
  unaccounted. This entry is permanent."*
- **Affordances are omitted rather than disabled** when an action is not permitted,
  driven by the server `actions[]` array (IA 1.2). Disabled-with-no-reason is the worst of
  both.
- **Confirmation is required for anything that changes what a person is paid, anything
  that costs money at a partner, and anything that is not reversible.** Assignment is
  none of those and must not be confirmed — a dispatcher assigns forty times an evening.
- **Counts inside filter controls are computed before filtering**, so they do not collapse
  as the selection narrows.
- **Both languages.** Every label ships ru and uz. Status codes are stable enums;
  Delever's status maps keyed by Russian display string are the mistake — a copy edit must
  not change a colour.

---

## 21. Suggested build order

1. **§5 Courier list and §6 record.** No dispatch dependency beyond `courier_profiles`;
   it is what unblocks migrating the legacy courier table, and staff can start using it
   the day it lands.
2. **§7 Shift board** with enforcement in `ADVISORY`. Rosters, opens, closes, hours — no
   money yet.
3. **§3 Dispatch board** once `delivery_plans`, `shipments` and `assignment_attempts`
   exist. Internal assignment first, partner sourcing second, both in the same queue from
   the first commit so the single-surface property is never retrofitted.
4. **§8 Cash reconciliation.** It is the operationally hardest step and needs the longest
   run-in with real branches.
5. **§9–§11 configuration**, then **§12–§14 settlement in shadow mode** for one location
   across one full period, reconciled line by line against the tenant's spreadsheet before
   the ledger becomes authoritative.
6. **§4 Live map** and **§15 reports** last. Both are valuable and neither blocks service.
