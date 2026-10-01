# ADR 0143: Walk-in guests open their own table session

- Decision status: Accepted
- Implementation status: Not started — no guest-side call creates a table
  session. The only writer is staff-side: `TableSessionController.open`
  (`POST /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/dine-in/sessions`)
  declares `dinein.session.manage` at `LOCATION` scope, and
  `TableSessionService.open` — which the controller calls — takes an
  `openedBy` string and authorizes nothing itself, so the gate is the
  controller's declaration. `ReservationsPage.submitSeat` always sends a
  `reservationId`; since batch 15 (`w6-dine-in-operator-flows`) two more console
  callers send none and seat a walk-in, as this record says the staff path must
  (the floor plan's "Seat walk-in", and the New Order screen's table picker for a
  DINE_IN order), and none of them consults a booking's hold or a table's `seats`
  -- the host's judgement, with an advisory warning, exactly as the context
  below describes. ADR 0047's API
  sketch names `POST /api/v1/storefront/qr/{tableToken}/sessions` for the guest's
  own open; it was never built, and ADR 0047's own checklist has already moved the
  token exchange from a path segment into a request body
  (`POST /api/v1/storefront/dine-in/qr/token-exchanges`). What is built and this
  record keeps: the table token and its exchange for a short-lived guest token
  (`QrEntryService`), the guest's bill, bill request and round attach
  (`QrEntryController`), the one-live-party-per-table index
  (`ux_session_table_occupied`), the reservation exclusion constraint, and batch
  13's storefront `/dine-in` flow, which for an `ORDER_AND_PAY` table with no live
  session renders `dineIn.notSeated` ("Ask a member of staff to seat you, then
  scan the code again") and disables ordering.
  Status note, 2026-09-30 (documentation pass, no code; the status above is
  unchanged): ordering now has a table binding. Batch 15 built the cart-to-table
  binding this record's Context and Open inputs describe (`V0435`, `PUT
  .../carts/{cartId}/table`, `dinein.api.TableBindingPort`), so nothing here should
  be read as "checkout knows nothing about a table". Read against the tree at
  `acd96539`, both customer storefronts now make the `PUT` (`frontend/storefront`
  and `frontend/storefront-milliy`), but only `frontend/storefront` sends the
  guest's `X-Dine-In-Token` at checkout; `frontend/storefront-milliy` does not, so
  its table-bound checkout should be refused with `TABLE_TOKEN_REQUIRED` (read from
  the two codebases and `CartCheckoutAndOrderTests#aBoundCartWithoutATokenIsRefused`;
  not reproduced against a running stack, and not fixed by this note). Self-seating
  itself remains unbuilt.
- Date proposed: 2026-09-29
- Date decided: 2026-10-01
- Deciders: proposed by Claude (wave batch 14, w7-adrs-stops-dispatch-walkin) as an
  addition to ADR 0047 after batch 13's storefront `DineInTableComponent` recorded
  self-seating as an undecided product question; Ayubkhon Abbosov (platform owner)
  decides. No numbered gap-map row is blocked on this; the nearest are `1.5a`
  (seating from the reservations screen) and `10.5b` (QR dine-in modes and table
  QR cards), both `BUILT` for the staff side.
- Depends on: ADR 0015, ADR 0019, ADR 0025, ADR 0029, ADR 0031, ADR 0033,
  ADR 0036, ADR 0047, ADR 0051
- Supersedes / Superseded by: — (extends ADR 0047; replaces only its never-built
  `POST /api/v1/storefront/qr/{tableToken}/sessions` sketch, the way ADR 0047's
  own checklist already replaced its token-in-path exchange)
- Open inputs:
  - Whether the pilot wants guests to seat themselves at all, and at which
    venues. This record ships the capability off by default per location
    (product, owner).
  - The walk-in horizon: how long before a confirmed booking's hold a walk-in may
    no longer take the table. This record proposes 90 minutes; the right number is
    the venue's typical dwell (operations).
  - Whether a self-seated table may place a cash round. Since batch 15 a cart can
    be bound to a table (`PUT .../carts/{cartId}/table`, see Context) and checkout
    refuses a bound cart whose table nobody sits at (`TABLE_NOT_SEATED`), but the
    binding is optional: an unbound `DINE_IN` cart -- a client that never makes the
    `PUT`, or a direct API caller -- still checks out with no bill and no seating
    check. So nothing in this design prevents a cash `DINE_IN` order for a table
    nobody sat at from a caller that skips the binding, the same exposure any
    signed-in customer has through `POST .../carts`. Whether to require the binding
    for the `QR_TABLE` channel, and whether to require that a self-seating venue's
    `QR_TABLE` channel offer only payment-first methods, are policies to be
    decided, not built here (finance, owner).
  - Presence proof stronger than possession of a printed code — a rotating code
    on the table, a device-side location check — which cost hardware or personal
    data. Not proposed; a trigger is named below (security, product).
  - Whether the host stand wants a realtime signal when a guest opens a session.
    ADR 0045's channel catalogue is closed and code-owned, so a `FLOOR` channel is
    an amendment to that record; until it is decided the host stand reads the live
    list (operations).
  - The session currency for a guest-opened session. No location-currency read
    exists (`ReservationsPage` sends a fixed `'UZS'`, ADR 0055's single-currency
    pilot); this record adds a per-location interim setting (finance).


**Decision record, 2026-10-01.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all" over ADRs 0136–0144. Every open input above is closed on the default this record proposes for it; where a record defers an input to a named owner, that deferral stands as written and implementation proceeds without it. Implementation starts in operations batch 17 the same day.

## Context

**What a guest with a phone can do at a table today.** Scanning the printed code
posts the token once (`POST /api/v1/storefront/dine-in/qr/token-exchanges`) and
receives a guest token, the branch, the table code, the QR mode pinned at that
instant, the tenant's `QR_TABLE` channel code and `openSessionId`. In
`ORDER_AND_PAY` mode that token can read the running bill of the session live at
its own table, ask for the bill, and attach an already-placed order to that
session, provided the caller also holds an ordinary signed-in customer session
whose account placed the order (`requireOwnOrder`, added in batch 13 after a
cross-table attachment exploit). Every one of those calls resolves the table from
the token and never from the request, and every one begins with
`store.findLiveSessionAtTable`. With no live session at the table there is
nothing to read or attach to, `openSessionId` is null, and the storefront tells the
guest to find a waiter.

**Who can create the session, and why the walk-in case is the common one.**
ADR 0047 calls the walk-in "most covers", and the session model was built for it:
`table_sessions.reservation_id` is nullable, and the doc on
`TableSessionService.OpenSession` says `null` is a walk-in.
But the staff path that creates the row is reachable in the console only from a
confirmed booking, so for a pure walk-in the row can be created only by calling
the API by hand. `DineInTableComponent`'s own doc names the gap: opening a
session is capability-gated to an operator, and "creating a real table occupancy
from an unauthenticated scan is a product decision about self-seating and its
interaction with reservation holds that ADR 0047 does not settle." This record is
the answer that doc was waiting for. It does not remove the need for a staff
walk-in seating screen (some guests have no phone, some venues run `VIEW_ONLY`);
it adds the guest's path beside it.

**What already protects the room, and what it does not cover.**

- One live party per table, in the database: `ux_session_table_occupied` on
  `dinein.session_tables (table_id) WHERE left_at IS NULL`. Two guests scanning a
  free table at once cannot both create a session.
- One party per booking: `ux_session_reservation`.
- No double-booked table, in the database: `ex_reservation_table_no_double_booking`
  over `reservation_tables.held_during` for `CONFIRMED` and `SEATED` bookings.
  **That constraint reads only bookings.** A session occupies a table with no end
  time, so nothing stops a session opening on a table whose `CONFIRMED` booking
  starts in ten minutes, and nothing stops a booking being confirmed over a table
  a party is sitting at. `TableSessionService.open` does not consult holds even on
  the staff path; `tableAvailability` returns `booked` and `occupied` as separate,
  explicitly "advisory" flags. Today that is a host's judgement made with the room
  in view. Self-seating moves the decision to a phone, so the check has to exist.
- Capacity is not checked anywhere: `dinein.tables.seats` (1–100) and `joinable`
  exist, and `table_sessions.party_size` is optional and unvalidated against them.
- Nothing distinguishes a session a host opened from one a guest opened, and no
  claim can lapse: a session ends only by a staff `state-action` or a force close.
- The printed token is a permanent bearer credential (ADR 0047 accepts this):
  128 bits, stored as a digest, exchanged for a guest token under a per-token limit
  of 20 a minute (`QrEntryService.EXCHANGE_LIMIT`). `qr_guest_sessions` records no
  address, agent or fingerprint on purpose (ADR 0029), so per-source abuse
  controls are the edge's, and per-token and per-account ones are the module's.
  A photographed code stays valid until the table's token is rotated
  (`dinein.qr.rotate`), which revokes every guest token minted from it.

**What checkout does and does not know about a table.** `POST .../carts` with
`DINE_IN` needs a location and a signed-in customer and nothing about a table, and
a cart that never names one checks out exactly as before: `CheckoutEligibilityGuard`
applies the minimum-order floor to it as it does to a `PICKUP` cart and asks nothing
about seating. Since batch 15 a cart *may* be bound to a table (ADR 0047's
"ordering's cart-to-table binding", built): `PUT .../carts/{cartId}/table`
(`StorefrontOrderingController#bindTable`, `CartService#bindTable`) stores the table
of the guest's `X-Dine-In-Token` in `ordering.cart_fulfillment.dinein_table_id`
(`V0435`) -- never a table id from the request. Both customer storefronts make the
call (corrected 2026-09-30: this sentence said only `frontend/storefront` did, which
stopped being true when `frontend/storefront-milliy` gained its table basket later
in batch 15); an operator-keyed order does not, and instead names the party's
session in the placement itself (`dineInSessionId` on `POST .../orders`). Only
`frontend/storefront` sends the guest token at checkout, so a table-bound
`frontend/storefront-milliy` checkout should be refused (`TABLE_TOKEN_REQUIRED`). For a bound cart the guard re-proves the guest at
checkout from a live token (`TABLE_TOKEN_REQUIRED`, `TABLE_TOKEN_ENDED`,
`TABLE_BINDING_STALE`), refuses when nobody is seated at the table
(`TABLE_NOT_SEATED`, `TableBindingPort#isSeated`), and puts the order on the
seated party's bill in the transaction that creates it
(`TableBindingPortAdapter#attachRound`). So the phantom-order risk -- food cooked
for a table nobody is at, paid in cash -- is closed for a client that binds and
open for one that does not: an unbound `DINE_IN` cart still reaches checkout with
no bill to put the order on, for any signed-in customer who calls the API directly
without the `PUT`. The storefront's `isSeated` guard remains a UI courtesy for
that caller. Self-seating does not create that risk. It makes the UI guard and the
seating check satisfiable by anyone holding a token, which is why this record does
not claim to solve it.

**The threat this design is mostly about is table denial**, not food fraud: a person
with a photograph of a printed code, opening sessions on empty tables so real
guests cannot use them. The constraint that makes one-party-per-table safe is the
same constraint that makes a fake party costly to the room.

## Decision

**A guest may seat themselves at a free table through an explicit action that opens
a provisional session — a claim — which becomes an ordinary session once an
accepted round is on it and lapses if nothing follows. It ships off, per
location.**

1. **Off by default, per location.** `dinein.location_settings.walk_in_self_seat`
   is false until a venue turns it on, with a reason and an audit fact, through the
   same settings endpoint that already carries `qr_mode` (`PUT .../dine-in/settings`,
   `dinein.floorplan.manage`; it takes a reason and an idempotency key today and no
   `If-Match`, which the build adds, ADR 0031). It is meaningful only while
   `qr_mode = ORDER_AND_PAY`.

2. **An explicit, authenticated action; never a side effect of scanning.** A new
   guest route, `POST /api/v1/storefront/dine-in/sessions`, takes the guest token
   in `X-Dine-In-Token` (the table proof) and the customer's ordinary signed-in
   session in `Authorization` (an identity with a verified phone, ADR 0051) — the
   two-credential shape `addRound` already uses — and a body of `{ "partySize" }`
   only. It never accepts a table id, a location or a tenant. Scanning stays
   read-only: link previews and prefetchers that fetch the QR's URL open nothing.

3. **It opens the same row staff open, marked as a claim.** The route calls
   `TableSessionService.open` (no reservation, one table, `openedBy =
   "guest:" + accountId`), and the row carries `origin = GUEST_QR`, the claimant's
   `opened_by_account_id`, and `claim_expires_at = now + claim TTL`. The claim
   occupies the table through the same partial unique index, so a second guest
   scanning the same table gets the live session back with `created = false`
   rather than a second one; this is idempotent by observation, as `requestBill`
   already is, and takes no `Idempotency-Key` (there is no operator to protect).

4. **A claim becomes a session when a round the restaurant has accepted is on it,
   or it lapses — in whatever status it has been moved to.** `addRound` on a claim
   sets `confirmed_at` at once when the attached order is already accepted
   (`CONFIRMED` or later — a cash order at an auto-accepting branch, say).
   Otherwise the sweeper decides when `claim_expires_at` passes, reading the
   statuses of the rounds attached to the claim: an accepted round confirms it
   (`confirmed_by = 'round:<orderId>'`); a round still in flight (`RECEIVED`,
   `PAYMENT_AUTHORIZING`, `AWAITING_APPROVAL`) defers the decision to the next
   sweep, but only until `claim_expires_at + walk_in_payment_defer_minutes` (a
   location setting, default 30). That bound is anchored on the claim's own expiry,
   is not renewable, and is deliberately not `OrderPaymentProcess`'s
   `horecaos.ordering.workers.payment.stale-after`: that is a deploy property which
   flags a stuck order for a person and never ends its payment, so it bounds
   nothing per claim. Past the bound a claim whose rounds are still in flight
   lapses like one with none; no rounds, or only failed, rejected, expired or
   cancelled ones, lapses it at once. Attaching alone confirms nothing:
   `BILLABLE` counts `PAYMENT_AUTHORIZING` orders, and a claim that a guest could
   turn into a held table by starting a payment and abandoning it would be the
   denial this design exists to bound.

   **The sweeper selects every live unconfirmed claim, in any live status.** A
   guest can move a session to `BILL_REQUESTED` with the table's guest token alone —
   `QrEntryController.requestBill` asks for no round and no sign-in — and
   `DineInStateMachine` permits `OPEN -> CLOSED` and `SETTLING -> CLOSED` but not
   `BILL_REQUESTED -> CLOSED` (its only exits are `SETTLING`, `OPEN` and
   `FORCE_CLOSED`). A sweeper indexed on `status = 'OPEN'` and closing with a bare
   `move(..., CLOSED)` would therefore never see, and could not close, a claim one
   tap had moved out of `OPEN`. A lapse is `TableSessionService.move` to `CLOSED`
   with `close_reason_code = 'CLAIM_LAPSED'` from `OPEN` or `SETTLING`; from
   `BILL_REQUESTED` it is `move` to `OPEN` (the machine's existing return-to-service
   edge, so ADR 0047's machine is untouched) and then to `CLOSED`, in one
   transaction and both hops under the system actor with the reason "claim
   lapsed". The audit fact, the table release (`tr_session_close_releases_tables`)
   and the revocation of the table's guest tokens then happen exactly as for a
   staff close; the price is one intermediate `dinein.session.open` fact, which
   reads as a reopening and carries that reason.

   Two guards make that state rare, and the sweeper relies on neither. A guest's
   `bill-requests` call on an unconfirmed claim is refused (`409 RESOURCE_CONFLICT
   { conflict: "CLAIM_UNCONFIRMED" }`), because there is nothing the restaurant has
   accepted to bill; and a staff move of a claim past `OPEN` (`state-actions`)
   confirms it, with `confirmed_by` set to the staff subject, because someone in the
   room has then taken charge of the table.

5. **Reservation holds win at the moment of opening.** The route refuses when a
   `CONFIRMED` booking holds the table for any part of `[now, now + walk-in
   horizon)` (`reservation_tables.held_during`, the interval the exclusion
   constraint already keeps). The refusal is the same generic "ask a member of
   staff" as every other ineligibility: it names no time and no guest. The reverse
   direction is unchanged: confirming a booking never bumps a party already
   seated; the confirm response reports `tableOccupiedNow` so the host sees it,
   and the host decides. Both paths take a row lock on the table
   (`dinein.tables ... FOR UPDATE`, in id order when several) so that a guest
   opening and a host confirming the same table serialize instead of both
   succeeding on stale reads.

6. **Capacity is strict for guests.** `partySize` must be between 1 and the table's
   `seats`. A guest cannot join tables, cannot exceed seats, and cannot open at a
   table that is not `ACTIVE`. A larger party is a host's job (join, or seat
   several tables), on the existing staff path.

7. **Abuse is bounded, not eliminated, by five controls — and the counting ones
   are enforced by the database or under a lock, never by a bare count.** A
   verified signed-in customer who is not blacklisted (`CustomerBlacklistPort`, the
   same check checkout applies); at most one live unconfirmed claim per account per
   branch, which a partial unique index refuses whatever the application read; a
   daily cap on claims opened per account per branch and a branch-wide cap on
   simultaneous unconfirmed claims, both counted only after the transaction holds
   the branch's settings-row lock (Eligibility); and the per-token limit on the
   route. The table-row lock of Decision 5 serializes two claims on one table and
   nothing else: a person holding photographs of several codes fires claims at
   *different* tables, each transaction counting zero peers under READ COMMITTED, so
   a check-then-insert under that lock alone would let all of them through.
   Numbers are settings with proposed defaults (Specification). Rotating a table's
   printed code remains the remedy for a leaked one.

8. **Staff keep every override.** The staff open path is unchanged and may seat
   anyone anywhere, over a hold or over capacity, recording `bookedOver` or
   `overCapacity` in the audit fact. Staff see a claim's origin, expiry and
   confirmation on the live list; can confirm it (`claim-confirmations`, keeping
   the table for a guest who has not ordered yet), close it with the existing
   `state-actions`, turn the feature off per branch, and rotate the table's code.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep sessions staff-only and build the operations "seat a walk-in" screen | Necessary anyway, and not rejected — it stays. As the *only* way in, it makes every QR-ordered table wait for a waiter to start, which is the dependency ADR 0047's channel model exists to measure and remove ("how much hall revenue now arrives without a waiter"), and it leaves each QR table waiting on whichever waiter is free | The pilot shows claim abuse or reservation conflict costing more than the waiter tap saves |
| Open a full session the instant a token is exchanged | A scan is the one action with no identity and no intent: a link preview, a prefetcher or a curious neighbour would occupy the table. Unbounded table denial with no per-account handle | Never |
| Guest requests, staff approves (a `REQUESTED` session that orders cannot use until approved) | Safe, and puts a human tap back in the loop for every table, defeating the reason to build this. The same safety is bought more cheaply by a lapsing claim plus a branch cap | A venue's abuse is bad enough that an approval step is worth its cost — offer it as a per-branch mode then |
| No explicit open: create the session lazily when the first round attaches | Removes the empty-claim problem entirely, but the order is already placed, priced, and possibly cooking before anything checks that the table is free or held. It needs ADR 0047's cart-to-table binding first, so the table can be checked at cart creation | The binding exists since batch 15, but it is optional, is made by a separate `PUT` after the cart is created rather than at creation, and checks only that somebody is seated, not that the table is free or held; the claim step can move to cart creation once the binding is required for `QR_TABLE` carts and this endpoint can shrink |
| Model the claim as a new `CLAIMED` session status | Cleaner to read, but every place that means "live" — `SessionStatus.live()`, `findLiveSessionAtTable`, `ix_sessions_live`, the occupancy predicates, the guest routes' status checks — must learn a fifth value, and a missed one leaves a claim that occupies nothing or blocks nothing. Columns leave the state machine untouched | The claim grows behaviour the status columns cannot carry cleanly |
| Make the claim a real hold in the reservation exclusion constraint (a synthetic booking of `[now, now + horizon)`) | The only design in which the database, not application code, refuses a walk-in over a booking and a booking over a walk-in. A booking row carries an encrypted guest, phone and note and would pollute the day plan, the no-show rate and every reservation report with rows nobody made | Race conditions between guest-open and host-confirm are observed in practice; then a dedicated occupancy range with its own exclusion constraint |
| Presence proof: a rotating code on the table, NFC, or a location check in the browser | Real cost — hardware to maintain, or precise location, which ADR 0029 treats as personal data and which a browser can be made to spoof. Possession of the printed code plus a verified phone plus caps is the proportionate first step | Measured phantom or denial abuse at a venue |
| Let a guest exceed seats, or join tables | A party size that is the guest's word alone can grab the room's best table; joining has no API even for staff | Staff can join tables through an API and a venue asks for guest-side joining |

## Consequences

### Positive

- A guest at a free table can start ordering without finding a waiter, and the
  platform can tell those covers apart (`origin`), which is the measurement ADR
  0047 wanted.
- Reservation holds are finally consulted by a session opening; the gap between
  "advisory" and "enforced" narrows from the guest side.
- Table denial is bounded: an unconfirmed claim costs a verified phone number, an
  account-level and a branch-level cap, and fifteen minutes.
- Nothing changes for a venue that leaves the switch off, or for the staff path.

### Negative

- The hold check is not backed by the database. A row lock serializes the two
  application paths; the exclusion constraint still reads bookings only, so a
  path that forgets the lock reopens the race. Failure mode: a booking arrives at
  an occupied table and the host handles it as they do an overstay today.
- Denial is bounded, not prevented. Someone with several verified phones can hold
  claims up to the branch cap, and the cap itself can be used to lock genuine guests
  out of self-seating for fifteen minutes at a time. The mitigations are staff
  (`state-actions`, rotate the code, switch off) and the per-account limits.
- The phantom-cash-order exposure described in Context is not reduced. A self-
  seated table can place a cash `DINE_IN` round exactly as any signed-in customer
  can today; the design names it as an open input and does not close it.
- `walkInAvailable` on the exchange response tells a token holder that self-seating
  is or is not possible at this instant, which lets them distinguish "free" from
  "occupied, held or disabled" without a reason. It names no time, guest or
  booking, and occupancy is already visible in `openSessionId`; the leak is one
  boolean about a table they are physically at.
- A guest whose payment outlasts the deferral bound (`claim_expires_at +
  walk_in_payment_defer_minutes`) loses the claim; the round they
  paid for stays an order but is no longer on a table's bill until staff open a
  session and attach it through the existing operator route.
- `party_size` is the guest's own word.
- A closed claim with no rounds is a `table_sessions` row that reports and
  operational counts must exclude (`CLAIM_LAPSED`), or "sessions opened" and
  "covers" inflate. Business-day reporting (ADR 0043) has to learn `origin`.
- The session row now carries a customer account id. It is an identifier, not the
  name, phone or address ADR 0029 protects, but it is one more place an account
  id lives, and erasure (ADR 0015) must clear it.
- Every self-seat open at a branch takes turns on that branch's settings row, and a
  settings `PUT` queues behind an open in flight. Bounded by the per-token rate limit
  and one short transaction; it is the price of counting caps that cannot be
  enforced by a constraint (the branch cap and the daily cap are aggregates).
- One more scheduled job (the claim sweeper) and one more registry entry each in
  the audit vocabulary and the storefront route allow-list.

### Accepted trade-offs

- A guest with no account cannot self-seat. They could not order either: every
  cart needs a signed-in customer.
- A large party is always a host's decision.
- The claim TTL is short enough to release a table promptly and long enough that a
  guest reading the menu is not evicted; fifteen minutes is a proposal, not a finding.
- Feature is off until a venue opts in; the pilot will not learn about self-seating
  from venues that never enable it.

## Specification

### Physical model (additive; number reserved by the wave that builds it)

```text
dinein.location_settings   (columns added)
  walk_in_self_seat        boolean  not null default false
  walk_in_claim_ttl_minutes integer not null default 15    check 2..60
  walk_in_horizon_minutes  integer not null default 90     check 0..480
  walk_in_max_unconfirmed  integer not null default 5      check 0..100
  walk_in_daily_claims_per_account integer not null default 3   check 1..20
  walk_in_payment_defer_minutes integer not null default 30  check 0..120
  session_currency         char(3)  not null default 'UZS' -- interim, ADR 0055; replaced by a location-currency read

dinein.table_sessions      (columns added)
  origin                   varchar(12) not null default 'STAFF'      check IN ('STAFF','GUEST_QR')
  opened_by_account_id     uuid null       -- the claimant; GUEST_QR only
  claim_expires_at         timestamptz null
  confirmed_at             timestamptz null
  confirmed_by             varchar(128) null   -- 'round:<orderId>' or the staff subject
  check ( origin = 'STAFF' AND opened_by_account_id IS NULL AND claim_expires_at IS NULL
       OR origin = 'GUEST_QR' AND opened_by_account_id IS NOT NULL
          AND (confirmed_at IS NOT NULL OR claim_expires_at IS NOT NULL) )
  index (claim_expires_at) WHERE origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL
        -- every live status, not OPEN alone: ck_session_closed_at makes "closed_at IS NULL"
        -- exactly "not CLOSED or FORCE_CLOSED", and a status list would go stale the way
        -- ix_sessions_live's does the day a sixth status is added
  unique index ux_claim_account_branch (tenant_id, location_id, opened_by_account_id)
        WHERE origin = 'GUEST_QR' AND confirmed_at IS NULL AND closed_at IS NULL
        -- one live unconfirmed claim per account per branch, enforced by the database;
        -- its (tenant_id, location_id) prefix also serves the branch-cap count
  index (tenant_id, location_id, opened_by_account_id, opened_at) WHERE origin = 'GUEST_QR'
        -- the daily-cap count
```

The close reason `CLAIM_LAPSED` is a value in the existing free `close_reason_code
varchar(64)`; `qr_guest_sessions.revoked_reason` reuses `SESSION_CLOSED`. No change
to `ux_session_table_occupied`, `ux_session_reservation` or the reservation
constraint. GRANTs are unchanged (no new table).

### The guest route

```text
POST /api/v1/storefront/dine-in/sessions
  X-Dine-In-Token: <guest token>      Authorization: Bearer <customer session>
  { "partySize": 2 }
-> 200 { sessionId, status, currency, totalMinor, roundCount, orderIds,   -- GuestBillResponse
         origin, created, claimExpiresAt | null, confirmed }
-> 404 (same generic body as every dead code) when the token is unknown, expired, rotated or VIEW_ONLY
-> 409 RESOURCE_CONFLICT { conflict: "TABLE_NOT_AVAILABLE" }
       -- self-seat off, table held, cap reached, blacklisted, not ACTIVE: one answer, no reason
-> 422 VALIDATION_FAILED { seats }   -- the party is larger than the table, or smaller than one
-> 429 RATE_LIMIT_EXCEEDED
```

`AdmissionResponse` gains `walkInAvailable` (boolean; `true` only when the feature
is on, the table is `ACTIVE`, unoccupied and not held inside the horizon). The
route is exactly the path `/api/v1/storefront/dine-in/sessions`: it is added to
`EndpointCapabilityDeclarationTests.isGuestBearerEndpoint` by exact match, and
`StorefrontReadAuthenticationTests` gains its case. It declares no capability for
the same reason its three siblings do — there is no principal to hold one; the
guest token proves the table and the customer session proves the person.

### Eligibility, in one transaction

```text
1. resolve guest token -> table, mode                     (refuse: VIEW_ONLY)
2. lock the branch's dinein.location_settings row FOR NO KEY UPDATE and read it;
   walk_in_self_seat must be true   (no row = off; nothing to lock)
3. lock the dinein.tables row FOR UPDATE
4. live session at table? -> return it, created = false
5. table ACTIVE; 1 <= partySize <= seats
6. no CONFIRMED booking with held_during && [now, now + horizon)
7. account not blacklisted; caps, counted now that this transaction holds the
   step-2 lock, so every earlier claim at the branch is committed and visible:
     - no other live unconfirmed claim by this account at this branch
     - claims opened by this account at this branch in the last 24h < daily cap
     - live unconfirmed claims at this branch < walk_in_max_unconfirmed
8. TableSessionService.open(no reservation, [tableId], partySize, session_currency,
     openedBy = "guest:" + accountId, claim = (accountId, now + claim TTL)) -- the
     claim columns travel in the INSERT, not in a second statement
```

**Why step 2 is a lock and not a read.** The counts in step 7 are aggregates over
rows that do not exist yet, and a row lock on the one table being claimed says
nothing about a claim on another table. The settings row is the one row every claim
at a branch has in common and the row the caps are defined on, so locking it makes
the branch's claims take turns: each waits for the previous transaction to commit or
roll back and then counts, on a fresh READ COMMITTED snapshot, everything it left.
The lock order is fixed — settings row, then table row — and nothing else takes both
in the other order (`ReservationService` confirm and amend take table rows only, the
staff settings `PUT` takes the settings row only), so there is no cycle. The lock is
held for one short transaction of indexed reads and one insert, behind a per-token
rate limit; a settings `PUT` queues behind it. The per-account cap does not rest on
the lock alone: `ux_claim_account_branch` refuses a second live unconfirmed claim by
one account whatever any transaction counted, so a path that forgets the lock
reopens the branch and daily caps at worst and never the account cap. A unique
violation on that index answers exactly as a reached cap does — `409
TABLE_NOT_AVAILABLE`, no reason.

The route's per-token limit uses ADR 0033's `RateLimiter` (`strictPerMinute(5)`,
keyed on the digest before the lookup, like the exchange). The per-account daily
cap is a count over `table_sessions`, so it survives a cache flush and is the same
number an operator can query. Neither stores an address.

### Staff surface

```text
PUT  .../dine-in/settings                                     adds the walk_in_* fields; dinein.floorplan.manage, reason, and an If-Match the endpoint lacks today
GET  .../dine-in/sessions                                     adds origin, claimExpiresAt, confirmedAt to each row (never the account id)
POST .../dine-in/sessions/{sessionId}/claim-confirmations     dinein.session.manage, If-Match, reason -> sets confirmed_at / confirmed_by
POST .../dine-in/sessions/{sessionId}/state-actions           existing; CLOSED releases a claim
POST .../dine-in/tables/{tableId}/qr-token-rotations          existing; the remedy for a leaked code
```

`ReservationService.move(... CONFIRMED ...)` and a confirmed booking's amendment take
the same table row lock, and their response gains `tableOccupiedNow`; they do not
refuse. `TableSessionService.open` on the staff path records `bookedOver` and
`overCapacity` in the `ChangeDocuments.created` map of its existing audit fact.

### The sweeper

`TableSessionClaimSweeper`, `@Scheduled`, log-and-continue, with its own switch
(`horecaos.dinein.claim-sweeper.enabled`), in the genre of
`InventoryReservationSweeper`. One conditional read selects live unconfirmed `GUEST_QR`
claims past `claim_expires_at` in any live status (`OPEN`, `BILL_REQUESTED` or
`SETTLING`); for each, a new `SessionOrderSource` read returns
the statuses of its attached rounds (the module's existing way to read order facts
without importing ordering), and the sweeper confirms, defers or lapses as Decision
4 says, closing through `TableSessionService.move` under a system actor — by way of
`OPEN` when the claim is in `BILL_REQUESTED`. A lapse racing an attach loses
cleanly: `move` is a conditional update on the expected version, and `addRound`
already refuses a closed session with the stable "takes no more rounds" answer
that `dineIn.roundAttachRetry` renders. A lapse racing a guest's bill request
loses or wins cleanly for the same reason, and either outcome is handled: the
next sweep sees a claim that moved to `BILL_REQUESTED` and lapses it.

### Audit, events, observability

`dinein.session.opened` (existing) gains `origin` and `walkIn` in its diff map;
new facts `dinein.session.claim-confirmed` and `dinein.session.claim-lapsed`, both
through `ChangeDocuments`. No external event: ADR 0047 deferred the dine-in
contracts and nothing subscribes. Counters with bounded labels: claims opened,
confirmed, lapsed, and refused by class (the class is internal; the response
stays generic). A branch whose lapse rate is high is the signal to look at abuse.

### Storefront

`DineInAdmission` gains `walkInAvailable`. `DineInTableComponent` shows a "Sit at
this table" control with a party-size stepper when `canOrder && !isSeated &&
walkInAvailable`, requires sign-in first (the component already sends a guest to
login to order), calls the route, updates the stored admission with the returned
`openSessionId`, and shows the claim's remaining time. The existing "ask for the
bill" control (`DineInService.requestBill`) is not offered while the claim is
unconfirmed, matching the `CLAIM_UNCONFIRMED` refusal. `dineIn.notSeated` remains
the message for every other case. ru / uz-latn / en strings. `frontend/storefront-milliy`
has no dine-in flow and is out of scope.

### Testing

- Two guests opening one free table concurrently: exactly one session, the other
  gets it back with `created = false`.
- A `CONFIRMED` booking starting inside the horizon: refused; just outside it:
  allowed; a `REQUESTED` booking and a `CANCELLED` one: allowed. A host confirming
  a booking while a guest opens the same table: one order of operations wins, and
  the loser sees the winner's state.
- Party of 5 at a 4-seat table refused; 4 accepted; a non-`ACTIVE` table refused.
- Blacklisted account refused; second claim by one account at one branch refused;
  daily cap; branch cap; per-token rate limit; all refusals share one response.
- Concurrency, with real threads against a real PostgreSQL (Testcontainers), a
  latch releasing every caller at once and no mock of the store: one verified
  account firing N claims at N different free tables of one branch ends with
  exactly one live claim, the other N-1 answered `TABLE_NOT_AVAILABLE`; M accounts
  each claiming a different table against `walk_in_max_unconfirmed = K` (M > K) ends
  with exactly K live unconfirmed claims. Seen failing first: the one-account test
  with the unique index and the lock both removed, the branch-cap test with the lock
  removed. The one-account test must also pass with the lock removed and the index
  kept, because the index, not the lock, is what holds that cap.
- A claim with no round lapses at the TTL and frees the table. A round already
  `CONFIRMED` at attach confirms it at once. A round in `PAYMENT_AUTHORIZING`
  neither confirms it nor lets it lapse until `claim_expires_at +
  walk_in_payment_defer_minutes`, and a round that then fails lapses it; a round
  still in flight past that bound lapses the claim, and a second sweep does not
  extend the bound. A lapse racing an attach produces one outcome. The clock is
  advanced, not asserted at an instant.
- Tap-and-leave: a claim with no round, moved to `BILL_REQUESTED` by the guest's
  own token (the call is refused with `CLAIM_UNCONFIRMED`; the test then puts the
  row in that status directly, as a race would) and a second claim moved there by
  a staff `state-action` (which confirms it) — the first lapses at the TTL through
  `OPEN` and frees the table, the second is left alone. The table can be claimed
  again after the lapse. The test fails against a sweeper that selects
  `status = 'OPEN'` or closes with a single `move(..., CLOSED)`.
- `VIEW_ONLY`, feature off, expired, rotated and archived tokens all refuse with the
  dead-code response; rotation revokes a claim's guest token.
- A guest cannot supply a table, tenant or location; another table's token cannot
  reach this claim; cross-tenant reads fail.
- The staff path still seats anyone, and audits `bookedOver` and `overCapacity`.
- `EndpointCapabilityDeclarationTests` and `ModularArchitectureTests` stay green
  with the new route and the `customers.api` dependency.

## Rollout and rollback

Migration and code ship with `walk_in_self_seat = false` everywhere; nothing
changes. Turn it on at one venue with a short claim TTL and the branch cap at a
small number, watch claims opened, confirmed and lapsed for a week against what the
host stand reports, then widen. The staff walk-in seating screen is built
regardless. Rollback is the setting: no new claims are accepted, existing ones
lapse by TTL or are closed by staff, and confirmed sessions are ordinary sessions
that need nothing done.

## Implementation checklist

- [ ] Flyway: the `location_settings` and `table_sessions` columns and checks
      above; indexes, including `ux_claim_account_branch`; interim
      `session_currency`.
- [ ] `QrEntryController` route, `QrEntryService`/`TableSessionService` open path
      for a claim (claim columns in the INSERT), the eligibility transaction with
      the settings-row lock before the table-row lock, the mapping of a
      `ux_claim_account_branch` violation to `TABLE_NOT_AVAILABLE`, and the table row
      lock (also taken by `ReservationService` confirm and amend).
- [ ] `AdmissionResponse.walkInAvailable`; storefront service, component, tests and
      strings.
- [ ] `claim-confirmations` endpoint; `origin`, `claimExpiresAt`, `confirmedAt` on
      the live list; settings fields with `If-Match` and audit.
- [ ] `TableSessionClaimSweeper` (every live status; the lapse by way of `OPEN` for
      `BILL_REQUESTED`) and the pending-order read on `SessionOrderSource`; the
      `CLAIM_UNCONFIRMED` refusal in `QrEntryController.requestBill`; staff moves
      past `OPEN` confirm a claim in `TableSessionService.move`.
- [ ] `EndpointCapabilityDeclarationTests` allow-list entry (exact path) and
      `StorefrontReadAuthenticationTests` case.
- [ ] Reporting: exclude `CLAIM_LAPSED` from opened-session and cover counts; carry
      `origin` into the business-day facts (ADR 0043).
- [ ] Erasure (ADR 0015) clears `opened_by_account_id`.
- [ ] Tests listed under Testing, each seen failing first.
- [ ] Record on ADR 0047 that this extends it; decide the cash-round policy and the
      realtime signal before enabling at a second venue.

## Exit criteria

At a venue that has enabled it, a guest who scans a free table, signs in, states a
party size and taps once is seated: their next order attaches to a bill at that
table with no staff action. A guest cannot take a table a confirmed booking holds
inside the horizon, cannot exceed its seats, and cannot hold one indefinitely by
tapping and leaving: the table returns to the room within the claim TTL — whatever
status the claim has been moved to in the meantime — or within the payment bound
(`claim_expires_at + walk_in_payment_defer_minutes`) when a round's payment is
still in flight. A host
sees every self-seated table, who is still unconfirmed, and can close, confirm or
disable it. With the setting off, behaviour is unchanged.

## References

- ADR 0047 (dine-in; the API sketch this record replaces, the accepted
  possession-of-a-printed-code model, what was not built), ADR 0015 (customer
  identity and erasure), ADR 0019 (checkout; its cart-to-table binding was built in batch 15 under ADR 0047), ADR 0025, ADR
  0029 (no address or fingerprint on `qr_guest_sessions`), ADR 0031, ADR 0033
  (rate limits), ADR 0036 (`QR_TABLE` channel), ADR 0043 (business day), ADR 0045
  (closed channel catalogue), ADR 0051 (customer sessions)
- `platform/docs/operations-gap-map.md` rows `1.5a`, `10.5b`
- `TableSessionController#open`, `TableSessionService#open`, `QrEntryController`,
  `QrEntryService`, `ReservationService`, `JdbcDineInStore#tableAvailability`,
  `JdbcSessionOrderSource`, `CheckoutEligibilityGuard`, `TableBindingPortAdapter`,
  `V0034__create_dinein_floorplan_reservations_and_sessions.sql`,
  `V0435__cart_fulfillment_table_binding.sql`
- `frontend/storefront/src/app/pages/dine-in/dine-in-table/dine-in-table.component.ts`
  and `services/dine-in.service.ts`; `frontend/operations/src/app/features/orders/reservations-page.ts`
