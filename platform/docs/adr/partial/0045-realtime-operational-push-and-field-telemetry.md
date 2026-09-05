# ADR 0045: Real-time operational push and field telemetry

- Decision status: Accepted
- Implementation status: Partial — `V0041` creates `fulfillment.courier_duty_sessions`, `courier_positions_live`, the monthly-partitioned `courier_location_tracks` and `courier_track_summaries`, and the `telemetry` module builds the seven-channel catalogue with its startup check (`StreamChannel`, `StreamChannelRegistryCheck`), the SSE endpoint with async dispatch, heartbeats, `Last-Event-Id` resync, jittered reconnect and the 500-stream cap (`OperationsStreamController`, `SseStreamRegistry`), the per-replica `assign()`/seek-to-end consumer and the `RealtimeSignal.v1` contract, stream closure on grant change and token expiry (`RealtimeStreamMaintenance`), duty sessions and their breaks and closures, telemetry ingest with natural-key idempotency, staleness, accuracy and off-duty rules, the live-position read, the audited track reveal, and `TrackRetentionSweeper` on a timer — covered by `CourierTelemetryTests` and `SseStreamRegistryTests`. **A courier can now go on duty**, which every previous revision of this Implementation status line recorded as impossible: ADR 0042's `courier` module supplies `telemetry.api.CourierShiftPort` through `CourierShiftAdapter`, so `CourierComplianceConfiguration`'s stand-in is no longer registered, a duty session opens from the shift the courier opened himself at that branch, and the session copies ADR 0042's `registration_valid_until` as the evidence that somebody checked before collection started. `TelemetryIngestService` can therefore run, and the streams carry ingested positions rather than snapshots and heartbeats alone. This module also now answers `CourierProximityPort` — metres from a named branch, never a coordinate — so ADR 0042's dispatch ranking can prefer the nearer courier without a position leaving the location-scoped capability that guards it; the map's own staleness and accuracy floors decide what counts as a distance, so a stale or coarse fix is absent rather than ranked. Also not built: the kitchen display device principal and its enrolment (an ADR 0025 model change), the on-duty indicator in the courier app (ADR 0035's), and the reverse-proxy configuration, which is `infra/` and ADR 0034's. **Wave 61 recorded a deployment-topology constraint rather than changing anything here**: `RealtimeStreamMaintenance` (`tick`/`onGrantChanged`) drives `SseStreamRegistry`, which this record already designed process-local — a stream lives and dies with the replica holding its socket, by intent, not by omission — and `SseStreamRegistryTests` now has a dedicated case proving a second registry instance is unaffected by a signal, a tick, or a grants change against a first. That design means ADR 0023's `app`/`worker` role split cannot put this maintenance job on `worker`: `worker` never holds an SSE socket, so it would have nothing to maintain, and `app` is the only role that ever could. See ADR 0023's Runtime shape for the resulting rule (the `app` container keeps role `both`, not `app`, until a per-job scheduling gate or this ADR's own deferred gateway removes the need) and the alternatives it weighed and rejected — sticky-session routing and a shared subscriber registry — before choosing to document rather than build. See [Implementation checklist](#implementation-checklist)
- Date proposed: 2026-08-21
- Date decided: 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture), product (in-house fleet scope, customer tracking), operations (dispatch workflow)
- Depends on: ADR 0004, ADR 0014, ADR 0020, ADR 0023, ADR 0025, ADR 0029, ADR 0031, ADR 0032, ADR 0033, ADR 0034, ADR 0035, ADR 0042
- Supersedes / Superseded by: —
- Open inputs: The statutory basis citation for courier location processing under ZRU-547, and confirmation of the retention number below (legal, owner: Ayubkhon Abbosov to obtain). **Not structural**: a narrower answer moves two ADR 0030 configuration values — the collection gate and the track retention days — and changes no table, endpoint, capability, or transport in this document. ADR 0029's production startup check already refuses to boot while either value is flagged provisional.
- Closed inputs: There is an in-house courier fleet alongside Yandex Delivery and Noor (product, 2026-08-23) — the telemetry half of this ADR has a subject. **Customers do not see a courier's live position; status milestones only** (product, 2026-08-23). Couriers are registered self-employed individuals, not employees (business, 2026-08-23) — which decides who the data subject is and what the processing relationship is.

## Context

Most of the platform is request-response and that is correct. Six operational
surfaces are not, and each fails in a specific way when it goes stale:

| Surface | What staleness costs |
|---|---|
| Kitchen order queue | A ticket ten seconds late is ten seconds of cooking time lost on every order, all service |
| Live counters (`В процессе`, `Отменено`) | A supervisor's wall-board that lags is one nobody trusts, so nobody looks at it |
| Dispatcher board with drag-to-assign | Two dispatchers assign the same courier because both cards are stale |
| Dispatcher courier map | A pin thirty seconds old sends a courier to the wrong branch |
| Kitchen display, mid-service | The screen shows cancelled work and nobody knows the connection dropped |
| Stop list | An item goes on stop in the kitchen and an operator keeps selling it |

Nothing decides how any of them receives an update. ADR 0033 is the nearest
decision and it deliberately closes the adjacent door: no server-side session
store, stateless APIs, Valkey barred from event distribution. The legacy
dashboard's ten-second poll for queue counts is therefore the default that wins
by silence if this ADR is not written, and the operations prototype polls today
and says so on the page.

**Two answers on 2026-08-23 changed the shape of the second half of this ADR.**

The first is that **an in-house fleet exists**. ADR 0014's `INTERNAL_COURIER`
sourcing mode is not hypothetical, so courier telemetry has a subject, and ADR
0042's settlement model has two paths that meet on one dispatch surface: what is
owed to an in-house courier, computed here, and what is owed to or by Yandex
Delivery or Noor, which arrives on a partner invoice. The prototype already shows
both in one board with the provider as a column, and that is the shape to honour.

The second is that **customers never see a courier's live position**. That single
answer changes the privacy analysis more than anything else here. Location is
still processed — a dispatcher cannot assign an in-house fleet without knowing
where it is — but it is never disclosed to a customer. The hard case, showing one
identifiable person's live movement to another private person for the duration of
a delivery, does not arise. What remains is an operational processing arrangement
between a platform and a self-employed courier who has agreed to be dispatched
through it, and the analysis in this document is written for that case and no
wider one.

The third fact is the machine. ADR 0034 settled the topology: one colocated
server in Tashkent, Docker Compose, one operator, no rolling deploy, no read
replica, no managed anything. A transport decision that assumes a cloud pub/sub
service or an autoscaled socket tier is not a decision on this platform. Every
cost below is priced against that box.

Notifications to a phone with the application closed are out of scope: that is
ADR 0020's channel, not a stream.

## Decision

**Live operational surfaces receive Server-Sent Events over HTTP/2, and a frame
carries a signal, not state.** The client is told that something in its
subscribed scope changed and re-reads it through the ordinary authorized API. Two
channels are registered exceptions and carry a bounded payload inline.

**There is no customer-facing stream.** Customers poll a token-scoped tracking
endpoint, or receive an ADR 0020 message, and get status milestones only. The
streaming fleet is therefore bounded by staff count — tens of connections — not
by customer count, which is the single most important sizing property of this
decision on a one-machine topology.

**Fan-out across API replicas is Kafka, consumed with manual partition assignment
and no consumer group.** No shared cache, no socket gateway, no third party, no
new infrastructure.

**Every live surface has a polling path that must work; push is an accelerator on
top of it.** This mirrors ADR 0033's rule that PostgreSQL is always the authority
and a cache is disposable: a failed stream degrades the refresh interval, never
the correctness of the screen.

**Courier location is ingested for dispatch and dispute evidence. It is never
published.** Collection runs only while a duty session is open. A live position
is visible inside the platform to a named capability at a location scope and to
nothing else. A track survives **30 days** at coordinate precision and is then
dropped, leaving a per-assignment summary. No customer, no marketing surface, no
ADR 0043 metric, and no report reads either.

**A position the platform did not collect is not stored.** Yandex and Noor
dispatch their own couriers on their own schedules and report through their own
APIs. Where a partner payload carries coordinates, the adapter drops them at the
normalization boundary. Partner shipments appear on the same dispatch board as a
stage and an ETA, and the provider column says why that row has no pin.

**Neither the track nor its summary pays a courier.** ADR 0042 decides what pays,
from the routing distance quoted at assignment and snapshotted onto it, and this
ADR does not qualify that. A telemetry distance moves with detours, with drift in
Tashkent's courtyards, and with a handset that lost signal for a block, and
neither party can see it before the trip. Wiring it into an accrual is how a
courier is paid a different number every time the figure is recomputed, which is
the dispute ADR 0042 exists to prevent.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Show the customer a live courier map, as the legacy iOS app did | Product answered no on 2026-08-23. It is also the hardest thing in this document to justify: a customer watching a named self-employed individual move across the city has been handed a tracking device pointed at a person who is not their employee and cannot decline without losing the work. Milestones answer "where is my order" at a fraction of the exposure | Product reverses, and legal issues a determination covering disclosure to a third party. The design supports it — the position exists and is already scoped — so this is a policy reversal, not a rebuild |
| Short polling everywhere, as the legacy dashboard does at ten seconds | A kitchen sees a new order up to ten seconds late, and the cost is not zero either: forty operator tabs times four widgets at six requests a minute is roughly a thousand authenticated requests per minute per location, each re-resolving grants and re-running the same query against the one primary this platform has | Never as the only mechanism, and never fully — it stays the mandated fallback for operations, the answer wherever HTTP/2 is unavailable, and the only mechanism for customers |
| WebSockets with per-tenant fan-out | More capable, and the capability is not needed. Everything these surfaces send upstream is a mutation that must carry an `Idempotency-Key`, a capability check, an expected version, and an audit record — that is an ADR 0031 HTTP request, and a socket frame would route it around every convention the platform just standardised. Browsers also cannot set an `Authorization` header on the handshake, so authentication becomes a query-string token or a post-connect message, both worse | A surface needs true bidirectional low latency — courier-to-dispatcher chat, or two people editing one delivery zone polygon — or a measured connection count makes SSE's per-connection cost binding |
| Push the full resource state in the frame instead of an identifier | Duplicates every read model onto a second contract to version, test, and PII-check separately, and re-authorizes nothing: a stream opened while the principal held a `LOCATION` grant keeps emitting order contents after that grant is revoked | A surface's fetch amplification is measured to dominate. It then becomes a registered snapshot channel with a declared payload classification, not a general rule |
| Valkey pub/sub for cross-replica fan-out | ADR 0033 bars Valkey from event distribution, and the legacy system's Redis Pub/Sub was removed in favour of Kafka for the same reason: a dropped message on a fire-and-forget bus is invisible. Kafka is deployed and already ordered per key | Never for this. ADR 0033's rule would have to be reopened first |
| A managed or self-hosted realtime service (Pusher, Ably, Centrifugo) | Courier coordinates and order contents would traverse a component with its own ADR 0029 processor classification and ADR 0034 residency profile, for fan-out Kafka plus SSE already provides on hardware that is already paid for. A wall-board holds a connection all shift, the worst possible shape for per-connection billing | Connection counts exceed what the API container can hold, or native mobile push needs a device gateway. Self-hosted Centrifugo is the first candidate then |
| Ingest partner courier coordinates from Yandex and Noor and draw them on the same map | Two costs, no consumer. It creates a `PERSONAL_SENSITIVE` record about a person with no relationship to Qoida, whose transparency notice we did not give; and nothing reads it, because the platform does not dispatch that courier, does not pay that courier from a distance, and will not show the position to a customer | The platform takes over dispatch of a partner's fleet, which is a different commercial arrangement, or a partner contract requires Qoida to hold the track as evidence |
| Forward the partner's own customer tracking link to the customer | Defeats the decision by proxy: Qoida would be the reason a customer is watching a live map, having declined to build one. The reference is kept for operations and support instead | Product reverses the customer-tracking decision, at which point the partner link is the cheapest possible implementation of it |
| Retain every observation at full precision indefinitely | Turns an operational tool into a movement history of identified individuals, exactly what ADR 0029's `PERSONAL_SENSITIVE` class exists to prevent, in exchange for answering disputes that no longer have a path: ADR 0042 never reopens a closed settlement period | A legal or contractual obligation requires a longer track — a retention configuration change, not a redesign |
| Retain the track for 72 hours, as the first draft of this ADR proposed | Too short once the settlement statement is the thing being disputed. A statement is issued at period close and argued about afterwards, so a track that expires before the period it evidences produces a figure nobody can check — which is worse than no evidence, because it looks like evidence | Never below the derived floor in "Retention" |
| Collect nothing; infer position from status transitions | Cheapest and privacy-perfect, and it cannot produce a dispatcher map, a dispatcher board that shows who is free, or evidence to settle a disputed assignment against | It becomes the fallback if the in-house fleet is withdrawn, or if legal declines location processing entirely |

## Transport

```text
GET /api/v1/operations/streams
      ?scope=LOCATION:{locationId}&channels=ORDER_QUEUE,COUNTERS
Accept: text/event-stream

event: signal
id: 01J8ZQ…
data: {"channel":"ORDER_QUEUE","scope":"LOCATION:018f…","resourceType":"ORDER",
       "resourceId":"018f…","version":7,"occurredAt":"2026-08-23T14:03:11Z"}
```

One connection per browser tab, subscription set fixed for its life; changing it
means reconnecting, because a mutable subscription needs an upstream channel and
an upstream channel is the WebSocket argument returning through a side door. A
comment frame every 15 seconds stops proxies idling the connection closed.
**There is no replay buffer**: a reconnecting client sends `Last-Event-Id` and
gets a `resync` frame telling it to re-read its whole scope. A buffer is
server-side per-client state, which ADR 0033 spent a section refusing.

Every channel is code-owned and declares scope type, required capability, frame
class, source, and cadence cap. An unregistered channel is a `400`, and startup
fails if a channel names no capability — the shape of ADR 0033's cache registry
and ADR 0031's capability-declaration test.

```text
channel             scope           capability              frame     source                 cadence cap
ORDER_QUEUE         LOCATION        order.read              signal    ordering.events        coalesce 250 ms
ORDER_DETAIL        ORDER           order.read              signal    ordering.events        coalesce 250 ms
DISPATCH_BOARD      LOCATION        delivery.plan.read      signal    ordering + fulfillment coalesce 250 ms
STOP_LIST           LOCATION        catalog.read            signal    catalog.events         coalesce 250 ms
INTEGRATION_ALERTS  TENANT          integration.failure.read signal   integration.failures   coalesce 1 s
COUNTERS            LOCATION|BRAND  order.read              snapshot  derived, recomputed    1 per 2 s
COURIER_POSITIONS   LOCATION        courier.position.read   snapshot  realtime.signals       1 per 5 s
```

There is no customer channel. `COUNTERS` carries its integers inline because a
signal saying "a number changed" followed by a fetch is two round trips for one
integer. `COURIER_POSITIONS` carries positions inline because a signal per
courier per tick would produce N fetches per tick for N couriers. Both are
authorized HTTP responses under ADR 0025, not Kafka payloads — which is why
`COURIER_POSITIONS` may carry values ADR 0032 forbids on a topic. Coalescing is
not an optimisation: a bulk assignment of forty orders, a documented Delever
operation, emits forty domain events, and uncoalesced that is forty frames and
forty fetches per connected operator.

### Authorization and connection lifetime

- The stream closes when the access token expires. One held open all shift on a
  token that expired in five minutes is an authorization hole that looks like a
  working feature.
- The stream closes on ADR 0033's `TenantGrantsChanged` invalidation. Named
  failure: a supervisor whose location scope is revoked keeps watching that
  kitchen's queue until end of shift.
- Connect is rate limited through ADR 0033's `RateLimiter` per principal and per
  tenant; exceeding returns `429` with `RATE_LIMIT_EXCEEDED`.
- A wall-mounted kitchen display has no person at it, so it authenticates as a
  **device principal** enrolled through the OAuth 2.0 device authorization grant:
  a manager approves a device code from their own session, the display holds a
  refresh token bound to a per-device principal with a `LOCATION`-scoped grant,
  and the console revokes it. Never a shared staff login left signed in on a wall
  screen for three months, which is what otherwise happens.
- A display closes its stream outside the location's service hours and falls back
  to polling. A kitchen screen left on overnight holding a connection is an idle
  cost paid for nothing on a box with one of everything.

### Fan-out and latency

Each replica runs one Kafka consumer using `assign()` rather than `subscribe()`,
seeking to the end of every partition at startup. No consumer group, no offsets,
no rebalancing: signals are ephemeral and a missed one heals at the next resync.

At the pilot's scale the API runs as a single container, so this hop is a
loopback — the consumer reads a record the same process published minutes
earlier through the outbox. It exists anyway, because `--scale api=2` is ADR
0034's stated scaling move and a fan-out design that breaks at the second
container is a design with a hidden cliff.

`--scale api=2` and ADR 0023's `app`/`worker` role split are different axes and
this design only covers the first cleanly. Two horizontally scaled replicas of
the same role each run their own consumer, their own `SseStreamRegistry`, and
their own `RealtimeStreamMaintenance` tick against their own connections —
exactly the process-local design above, and nothing here changes for a third
or a tenth replica. A role *split*, where one container stops running
`@Scheduled` methods at all, is not that: it can remove the only tick that
would ever maintain the sockets an `app`-role container holds, because there
is no second process for that maintenance to run on instead. See ADR 0023's
Runtime shape for the resulting deployment rule.

```text
business commit -> outbox row               0 ms   (same transaction, ADR 0004)
outbox relay poll -> Kafka               <= 1 s    p95
Kafka -> replica consumer              <= 200 ms
replica -> browser frame               <= 100 ms
browser fetch of the changed resource  <= 300 ms
                                        ~ 1.6 s    order to screen, p95
```

Against a ten-second poll this is roughly six times better at the median.

### What this costs to operate on one colocated machine

ADR 0034 is the constraint, not a cloud price list. Each cost below is either a
number or a configuration line, because "SSE is cheap" is not an operational
statement.

| Cost | Value on this topology | What it means |
|---|---|---|
| Connections | 8–11 per location (1–3 kitchen displays, 1 dispatcher board, 1 wall board, up to 6 operator tabs). Pilot at five locations ≈ 50 | Bounded by staff, because customers do not stream. This is the number that made SSE affordable here |
| Hard cap | 500 concurrent streams per API container; `429` above it | A cap that is hit is a signal to revisit the transport, not to raise the cap silently |
| Warning threshold | 350 concurrent, on the operations dashboard | Deliberately **not** a page. ADR 0034's night-alert budget is three and this is not one of them |
| Per-connection memory | One socket plus an emitter and its subscription entry; tens of KB. 500 streams is single-digit MB | Not the binding constraint |
| Threads | The endpoint dispatches asynchronously and releases the container worker thread. A blocking `SseEmitter` that pins a thread per connection would exhaust the pool at a couple of hundred streams | This is the binding constraint if it is got wrong, and it is invisible until it is not |
| Reverse proxy | `proxy_buffering off` for `text/event-stream`, `proxy_read_timeout` above the 15 s heartbeat, and `worker_connections` raised to account for **two** slots per stream — client-side and upstream | nginx's default of 1024 is reached at ~500 streams on a box with abundant headroom, and the failure looks like a network fault |
| Heartbeat traffic | 500 streams at 15 s is ~33 writes/s | Negligible |
| Fetch amplification | One coalesced signal to 50 connected clients is 50 authenticated reads against the primary — which per ADR 0034 has no read replica and per ADR 0043 also serves reporting | The real cost. Budget: the live surfaces of one location may not exceed 20 reads/s in aggregate. A channel that breaches it becomes a snapshot channel with a declared payload classification |
| Deploy | ADR 0034 has no rolling deploy. `docker compose up -d` drops every stream at once, and every client resyncs at once | Reconnect is jittered over 1–10 s and rate limited per principal. Deploys land in the after-23:30 window when the herd is nearly empty. An unplanned restart at 19:00 does not get that mercy |

## Field telemetry

A courier device posts a batch to `POST /api/v1/courier/telemetry/observations`
every 10 seconds while a duty session is open, carrying buffered observations so
a lift, a basement kitchen, or a tunnel produces a late batch rather than a gap.

| Rule | Value | Why |
|---|---|---|
| Collection gate | `ON_DUTY` default, `ON_ASSIGNMENT` supported | The dispatcher board must see idle couriers to assign them, so assignment-gating breaks the capability outright. Both gates are implemented and the value resolves through ADR 0030, so a narrower answer from legal is a configuration change |
| Duty session prerequisite | A session will not open for a courier whose ADR 0042 self-employment registration record is absent or expired | An expired registration turns a compliant arrangement into an undeclared one, and the platform is the only thing positioned to notice. ADR 0042 owns the record and the validity check; this ADR only refuses to dispatch and to collect without one |
| Cadence | 10 s, limited to one batch per 5 s per courier | Faster drains the battery the dispatcher is watching, and costs the courier mobile data he pays for himself |
| Batch size | 60 observations maximum | Bounds a reconnecting device's catch-up |
| Idempotency | Natural key `(tenant_id, courier_id, captured_at)`, `ON CONFLICT DO NOTHING` | An idempotency record per beacon adds six rows a minute per courier to the ADR 0031 table for no benefit. A narrow, named exemption from that rule |
| Staleness | Observations older than 10 minutes never update the live position | Otherwise a courier's pin jumps back across Tashkent when their phone reconnects |
| Accuracy floor | Worse than 100 m is stored in the track, never drawn on the map | A 900 m accuracy circle rendered as a pin is a confident lie |
| Device telemetry | Battery percent and charging state on the live row only, never in the track | A dispatcher needs to know a phone will die mid-delivery. A battery history is a work-pattern archive with no operational use |
| Off duty | An observation arriving with no open duty session is rejected with `422`, not stored | Collection that continues after a courier signs off is the failure this whole section exists to prevent, and it must fail loudly rather than accumulate quietly |

| Ingest alternative | Why not chosen | Revisit when |
|---|---|---|
| A persistent socket from the device streaming continuously | Holds a radio connection open on a cheap handset — the fastest way to drain the battery the dispatcher is watching — and gains nothing over a 10-second batch feeding a 5-second map | Sub-second courier position becomes a product requirement, most plausibly for in-venue handover |
| Let the courier app decide its own cadence | Every handset model behaves differently and the platform loses the one lever it has over battery, data cost, and write volume | Never; the cadence is server-configured under ADR 0030 and the device obeys it |

## Privacy analysis

This section is the decision, not a preamble to it. Every line is either settled
here or named in `Open inputs` with an owner.

**What is processed.** Latitude and longitude, horizontal accuracy, heading,
speed, capture timestamp, device battery percent and charging state, tied to a
named courier and a duty session. ADR 0029 classifies precise location history as
`PERSONAL_SENSITIVE`, and ADR 0042 classifies courier position identically, so
the two ADRs agree on the class without either deferring to the other.

**Who the data subject is.** A registered self-employed individual under contract
to the tenant, not an employee. That matters twice. The processing cannot rest on
an employment relationship that does not exist; and the courier is a small
counterparty who cannot realistically refuse a term and keep the work, so consent
is not the basis relied on. What is relied on is the performance of the delivery
contract, plus the tenant's interest in evidencing what it pays for.

**Who controls it.** The tenant is the controller of its couriers' location data;
Qoida is the processor. This is stated because it decides who answers a subject
access request and who signs the transparency notice — the tenant, with the
platform supplying the mechanism under ADR 0029's subject-rights machinery.

**The purposes, and this list is closed.**

1. **Dispatch.** Deciding which on-duty courier to assign, and showing a
   dispatcher where the fleet is.
2. **In-flight operations.** Noticing a stalled or off-route delivery while it
   can still be fixed.
3. **Dispute evidence.** Answering "did the courier arrive", "was it delivered",
   and "was the distance real" against a settlement statement or a customer
   claim.

Nothing else. **Not** pay — ADR 0042 accrues on the routing distance quoted at
assignment. **Not** performance ranking or any productivity score. **Not** any
ADR 0043 metric, report, or dashboard; the metric layer has no source binding to
these tables. **Not** marketing. **Not** disclosure to a customer, which is the
answer that shaped this section.

**Who may see a position, and under what capability.** Three capabilities join
ADR 0025's catalogue, and their absence from a role bundle is as much a decision
as their presence:

```text
courier.position.read    the live position of on-duty couriers within a LOCATION scope
courier.track.reveal     one named courier's stored track, for one declared purpose
courier.duty.manage      open or close a duty session on a courier's behalf
```

- `courier.position.read` is held by the dispatcher and location-manager role
  bundles, always at a `LOCATION` scope. A dispatcher sees the couriers of the
  locations they dispatch for and no others.
- `courier.track.reveal` **is in no default role bundle.** It is granted
  deliberately, per person, and every use is an ADR 0029 bulk reveal: a declared
  purpose, recorded, and an ADR 0027 audit entry naming the actor, the courier,
  the window, and the reason. `platform.admin` does not imply it — a solo
  operator with unbounded standing access to a fleet's movement history is the
  exact ADR 0034 concentration risk, and the audit record is what makes access
  answerable rather than assumed.
- A courier sees their own current position and their own track, self-scoped.
- The reporting and support database roles hold no grant on either table, so a
  position cannot be reached by writing SQL against the reporting path.
- Nobody outside these paths. No customer token carries either capability, and
  no partner API surface exposes them.

**Transparency.** The courier is told at onboarding what is collected, when,
who can see it, and for how long, and the app shows a visible on-duty indicator
whenever collection is active. Collection never runs invisibly, and a courier who
signs off can see that it stopped.

**Minimisation and leakage.** No coordinate, accuracy, heading, speed, or battery
value appears in any event payload, log, trace, or metric, per ADR 0029 and ADR
0032. The `realtime.signals` record for a moving courier carries `courierId`,
`occurredAt`, and the scope key; a replica receiving it reads the live row it
already has access to and pushes a `COURIER_POSITIONS` snapshot to the
subscribers authorized for that location.

**Viewing is not always audited, and that is deliberate.** The live map is an
ordinary capability-gated read and is not audited per refresh, because auditing a
five-second map produces more audit rows than the tenant has orders and buries
the reveal that matters. Opening one named courier's historical track is always
audited.

## Retention

```text
live position row     deleted 1 hour after the duty session closes
track, coordinates    30 days, then the daily partition is dropped
track summary         retained with the shipment under ADR 0029 FINANCIAL rules
battery / device      live row only; never written to the track
```

**Thirty days, and the number is derived rather than picked.** The only thing
downstream of a track is a dispute, and a dispute about a delivery is argued
against the ADR 0042 settlement statement that period produced. A statement is
issued at period close and challenged afterwards; ADR 0042 never reopens a closed
period, so the evidence must outlive the close by the dispute window or the
statement is a figure nobody can check. The floor is therefore:

```text
track_retention_days >= settlement_period_days + statement_dispute_days
```

A startup check refuses a production profile where the configured values breach
it, the same mechanism ADR 0029 uses for its provisional retention values. At
the pilot's 7-day period and 7-day dispute window the floor is 14; the configured
value is **30**, which carries the longest period ADR 0030 is expected to allow
plus its dispute window plus a margin for a claim that arrives late. Customer-side
claims — not delivered, delivered to the wrong door — surface through ADR 0013
service recovery within days rather than weeks, so the settlement side is what
sets the number.

Why not longer: past the dispute window nothing reads a track. Holding it anyway
is a movement archive of identified people accumulating for no purpose, which is
the failure ADR 0029's `PERSONAL_SENSITIVE` class exists to prevent, and 30 days
is deliberately shorter than that class's provisional six-month default.

Why not shorter: the first draft of this ADR said 72 hours, which answers a
dispute raised the next day and nothing raised against a statement. A track that
expires before the settlement period it evidences is worse than no track, because
it looks like evidence until someone asks for it.

**What survives.** The per-assignment summary — observed distance, first and last
observation times, and the pickup and delivery confirmation points ADR 0042
already stores against status transitions — stays with the shipment, envelope
encrypted, under the financial retention rules. That is two coordinates per
delivery, not a path, and it is the one place a coordinate outlives 30 days. It
is called out here rather than buried, because it is the line a security review
should question.

**Erasure.** A courier's erasure request is honoured through ADR 0029's
subject-rights path, and a track inside its window is refused with the reason
given: it is evidence behind money that has not yet been settled. At window
expiry it goes with the partition whether or not anyone asked.

## Storage

```text
fulfillment.courier_duty_sessions
  id, tenant_id, courier_id, device_id, status, collection_gate
  registration_checked_at, started_at, ended_at null, version, timestamps

fulfillment.courier_positions_live            -- working set, not history
  tenant_id, courier_id (pk), position geography(Point,4326), accuracy_meters
  heading null, speed_mps null, battery_percent null, device_charging null
  active_assignment_count, captured_at, received_at, duty_session_id

fulfillment.courier_location_tracks           -- partitioned daily on window_start
  id, tenant_id, courier_id, duty_session_id, window_start, window_end
  geohash5_first, geohash5_last                -- ~1.2 km, cleartext, lookup only
  observation_count, distance_meters, created_at
  protected_track                              -- ADR 0029 ProtectedValue

fulfillment.courier_track_summaries           -- survives the track
  id, tenant_id, courier_id, duty_session_id, shipment_id null
  distance_meters, first_observed_at, last_observed_at
  pickup_point null, delivery_point null, created_at
```

There is no subscription table. A stream is process-local state that dies with
its replica, which is precisely why the client must reconnect and resync.

`distance_meters` on both track tiers is observed telemetry and is deliberately
not the `distance_meters` ADR 0042 accrues against — that one is the routing
distance quoted at assignment and lives on the earning row. The columns share a
name and answer different questions, so no accrual, statement, or settlement job
may read the two here.

**The live row stores coordinates in cleartext, a deliberate exception to ADR
0029.** Envelope-encrypted coordinates cannot be queried, and "which couriers are
inside this zone" is the dispatcher board's central question — ADR 0029 names
this difficulty among its own negative consequences. What pays for the exception:
the table holds only couriers with an open duty session and rows are deleted an
hour after it closes, so it is a working set and not a history; access needs
`courier.position.read` at the location scope; and the reporting and support
database roles have no grant on it. The track tier, the part that would
constitute a history, is encrypted, with only a five-character geohash in
cleartext so a time-bounded lookup finds the right rows without decrypting every
one.

## What the customer sees

Milestones, drawn from state the order machine already owns. No map, no pin, no
continuously recomputed ETA, no courier phone number, no vehicle or plate.

| Milestone | Source | Shown as |
|---|---|---|
| Order accepted | `OrderConfirmed`, ADR 0019 `CONFIRMED` | Confirmed, with the promised delivery window |
| Being prepared | ADR 0019 `PREPARING`, proposed by ADR 0041's ticket reaching `IN_PRODUCTION` | The kitchen has started |
| Ready | ADR 0019 `READY` | Ready to collect, for a pickup order; for delivery, shown only as awaiting courier |
| On the way | ADR 0019 `FULFILLING`, entered at handover to the courier | On the way, with the courier's first name |
| Delivered | ADR 0019 `COMPLETED` | Delivered |
| Cancelled | `OrderCancelled` with its ADR 0039 outcome reason category | Cancelled, with the reason category and any refund status |

`FULFILLING` is the milestone that replaces the map, and it is worth naming why
it is the right one: it is entered at handover, which is a recorded business
transition, not something derived from where a phone is. Nothing on the customer
surface is computed from a coordinate, so there is no lower-resolution back door
through which a position leaks.

**The ETA is the promised window, plus a revision.** Where operations revises the
promise, the customer sees the new window and that it changed. There is no
per-minute countdown recomputed from the courier's progress: a continuously
updating ETA is a coarse position feed wearing a clock face, and the decision was
that the customer does not get a position feed.

**How a milestone reaches a customer.** ADR 0020 owns delivery and this ADR does
not build a second path. Ordering and fulfillment publish their semantic events;
`notifications` resolves the template, the recipient's preferences, and the
consent gate, and routes to a channel. Confirmation and cancellation are
`TRANSACTIONAL_REQUIRED`. The intermediate milestones — preparing, on the way —
are `TRANSACTIONAL_OPTIONAL`, so a customer who does not want three messages per
order can turn them off without losing the two that matter.

A tracking page is also available on a token scoped to one order, expiring two
hours after a terminal state, returning the same milestone list and window. **It
polls at 20 seconds.** It is not a stream, because a page whose content changes
perhaps five times across forty minutes does not need one, and an unbounded
number of customer connections is precisely the thing this platform's one machine
should not be holding open.

**This is a downgrade from the legacy iOS app, which showed a live courier map.**
Someone will ask where the map went, and the answer is on the record: product
decided on 2026-08-23 that customers see milestones only, and the map is not
absent because it was hard. The costs are real and belong in the open — support
will field "where is my order" calls the map used to absorb, and a customer
standing at a door with cooling food has less information than they had before.
The mitigations are the milestone at handover, the promised window with its
revision, and support staff who can see the position when someone calls.

## Partner shipments

Yandex Delivery and Noor dispatch their own couriers, report on their own
schedules, and differ from each other: per ADR 0014, Noor pushes a webhook on
every stage change and Yandex is polled. Neither is a position feed on Qoida's
cadence, and neither would be useful if it were.

**A coordinate arriving from a partner is dropped at the adapter boundary.** The
normalization step in `YandexDeliveryAdapter` and `NoorDeliveryAdapter` removes
any position field before a shipment record is written, and a contract test
asserts that no partner coordinate reaches storage. The reasoning is that nothing
consumes it: the platform does not dispatch that courier, does not pay from a
telemetry distance, and does not show a position to a customer. What would remain
is a `PERSONAL_SENSITIVE` record about a person with no relationship to Qoida and
no transparency notice from anyone here.

What the platform does keep and use is the normalized `ShipmentStatusChanged`
stage, the partner's ETA where it supplies one, and the ADR 0014 `TrackShipment`
reference. **The tracking reference is operations-facing and is never sent to a
customer.** Forwarding a partner's live map would defeat by proxy the decision
not to build one; support uses it when a customer calls.

**One board, two resolutions.** The dispatch board shows in-house and partner
shipments together with the provider as a column, as the operations prototype
does. An in-house row carries a live position and the battery of the handset
carrying the order; a partner row carries a stage, an ETA, and the provider name,
and the column is what tells a dispatcher why one row has a pin and the other
does not. This is the surface where ADR 0042's two settlement paths meet: the
in-house row will produce a ledger entry computed here, and the partner row will
be reconciled against an invoice.

## Kitchen display states

```text
LIVE      stream open, last frame under 30 s old
DEGRADED  stream lost, polling at 5 s, quiet inline indicator
STALE     no successful read for over 60 s, full-width banner naming the data age
```

`STALE` refuses actions that depend on fresh state rather than queueing them.
**There is no offline write queue.** A queued "mark ready" that syncs six minutes
later tells a dispatcher the food is ready when it has gone cold, and a courier
is sent to collect it. A blocked action is visible; a false ready is not. Banner
strings are ru, uz-Latn, and en per ADR 0035.

## What this extends in other ADRs

- **ADR 0031** — telemetry ingest is exempt from the mandatory `Idempotency-Key`
  record, idempotent on a natural key instead. Named here and nowhere else.
- **ADR 0032** — a fourth topic class, `{domain}.signals`: seconds of retention,
  no replay, no business meaning, never catalogued as facts. `realtime.signals`
  is its only member.
- **ADR 0029** — one column of `PERSONAL_SENSITIVE` data is stored unencrypted,
  with the compensating controls above.
- **ADR 0025** — three capabilities are added, one of which is in no default role
  bundle; and device principals are a non-human principal type, used by a wall
  display.
- **ADR 0020** — customer milestones are ordinary notification classes on the
  existing delivery path. No new channel, no new template engine.
- **ADR 0014** — its courier location sketch gains a physical shape, a collection
  gate, and retention tiers; and its partner adapters gain a normalization rule
  that drops partner coordinates.
- **ADR 0042** — nothing is taken from it. It owns the registration record, the
  rate card, the accrual, and the statement; this ADR owns the track, refuses a
  duty session without a valid registration, and supplies no figure that pays.

## Testing

- A stream closes within the agreed bound of token expiry and of a grants change,
  including the revoked-location-scope case above.
- Forty bulk assignments produce at most one frame per 250 ms coalescing window,
  and a reconnect with `Last-Event-Id` yields a `resync` rather than a silent gap.
- An observation older than the live row never overwrites the live position; a
  replayed batch creates no duplicate rows; an observation with no open duty
  session is rejected.
- A duty session cannot open for a courier whose registration record is missing or
  expired.
- No coordinate, accuracy, battery, or track value is reachable from any event
  payload type, asserted by ADR 0029's existing structural check.
- **No customer-reachable response contains a coordinate.** The tracking token
  response, every ADR 0020 template variable set, and every marketplace channel
  payload are asserted against the position field set. This is the test that
  keeps the 2026-08-23 decision true after the tenth feature lands on top of it.
- A partner webhook or poll response carrying a position produces a stored
  shipment with no position, asserted in both adapter contract tests.
- `courier.track.reveal` is absent from every default role bundle; a reveal
  without a declared purpose fails; every reveal writes an audit entry.
- A track past its retention window is unreadable at coordinate precision while
  its summary survives; a configuration whose settlement period plus dispute
  window exceeds the track retention fails the production startup check.
- No courier accrual, statement, or settlement figure reads `distance_meters`
  from a track or a track summary: an ADR 0042 earning computed for an assignment
  is byte-identical whether the telemetry for it is complete, partial, or absent.
- The kitchen display refuses an action in `STALE`; every channel declares a
  capability or startup fails.
- **Equivalence**: a polling-only client and a streaming client reach identical
  state for every channel. This is the test that keeps the fallback real.

## Rollout and rollback

Ship the read APIs and polling clients first, so every surface works at a five-
to ten-second refresh before any stream exists. Add the stream endpoint behind a
per-tenant ADR 0030 flag; the client uses it only when the flag is on and the
connection succeeds. Kitchen queue first — highest value, single scope, one
capability — then counters, the stop list, and the dispatch board.

Customer milestones ship independently of all of it, because they are ADR 0020
messages and a polled page, and they have no dependency on the stream.

Telemetry ingest ships last, behind the collection gate, and only once the
courier transparency notice exists and the ADR 0042 registration check is live.
Rollback for the transport is turning the flag off: every operational surface
returns to polling with no code change, which is the entire reason polling is
built first. Rollback for telemetry is closing all duty sessions, which stops
collection at the source rather than at a screen.

## Consequences

### Positive

- A kitchen sees an order in about 1.6 seconds instead of up to ten, on
  infrastructure that already exists and hardware that is already paid for.
- One transport serves every operational surface: one authorization model, one
  metrics set, one failure mode, one thing for one operator to understand.
- Because customers do not stream, the connection count is bounded by staff and
  the transport is affordable on a single machine. The privacy decision and the
  sizing decision turned out to be the same decision.
- Signal-not-state means the stream cannot leak what the API would not return,
  because the API is what returns it.
- The privacy position is concrete: a closed purpose list, named capabilities, a
  derived retention number, and an audited reveal. A reviewer can disagree with a
  specific line instead of asking what the policy is.

### Negative

- **Support absorbs what the map used to.** "Where is my order" was self-serve in
  the legacy app and is now a phone call to someone with
  `courier.position.read`. That is a staffing cost, and it lands on the tenant.
- A held connection per open tab is a dimension replica sizing did not have, and
  ADR 0034 has no rolling deploy — every stream dies together on every deploy and
  every client resyncs together. Jittered reconnect bounds the herd; it does not
  remove it, and an unplanned 19:00 restart does not get the quiet window.
- Any HTTP/1.1 hop caps a browser at six connections per origin, and a tab
  holding one open for the stream leaves five for everything else. HTTP/2 at the
  reverse proxy becomes an operational requirement on the ADR 0034 topology, as
  do three proxy settings whose failure looks like a network fault.
- Signal-not-state turns one Kafka record into one authenticated read per
  connected client, against a primary with no read replica; coalescing bounds
  that amplification without removing it. And seek-to-end with no consumer group
  means a restarting replica loses signals for the gap's duration, so a missed
  stop-list signal can leave an item sellable on one operator's screen until that
  client's next resync.
- Storing live coordinates unencrypted is a real exception to ADR 0029 and will
  be the first thing a security review objects to. It should be.
- Telemetry is a continuous write stream: a hundred couriers on a ten-hour shift
  is on the order of 360,000 observations a day per tenant, on a box that also
  runs PostgreSQL, Kafka, Keycloak, MinIO, and OpenBao. Daily partitions dropped
  wholesale keep that manageable; a `DELETE` sweep would not.
- A dispute raised on day thirty-one is answered with a distance and two
  confirmation points, not a track. Someone will ask for the track.
- Refusing partner coordinates means the dispatch board is honest about having
  two resolutions, and a dispatcher watching a delayed Yandex shipment can see
  only a stage. There is no operational lever there beyond calling the partner.

### Accepted trade-offs

- Every live operational surface keeps two code paths — streaming and polling —
  forever, and both must be tested. That is the cost of push never being
  load-bearing, and the same trade ADR 0033 made when it refused to let a cache
  hold the only copy of anything.
- The dispatcher board sees idle couriers, which requires duty-session tracking
  rather than the narrower assignment-only gate a privacy review would prefer.
  That gate is implemented and configurable, so the decision is reversible
  without a redesign.
- Two coordinates per delivery outlive the 30-day track, because they are the
  evidence behind a paid figure. That is a genuine extension of location
  retention into the financial window, and it is stated rather than hidden inside
  a settlement table.

## Implementation checklist

Built in migration `V0041` and `uz.horecaos.platform.telemetry`.

- [x] Register the channel catalogue in code, with scope type, capability, frame
      class, source, and cadence cap, and fail startup on a channel with no
      capability. `StreamChannel` declares all seven; the constructor refuses a
      null capability and `StreamChannelRegistryCheck` fails startup, because a
      load-bearing constructor argument is invisible to the next person adding a
      channel.
- [x] Implement the SSE endpoint with async dispatch, the 15 s heartbeat, the
      `Last-Event-Id` resync frame, and jittered reconnect guidance to clients.
      `OperationsStreamController` returns an `SseEmitter`, so the container
      worker thread is released; `SseStreamRegistry` coalesces to each channel's
      cap, heartbeats, and sends `closing` frames carrying a 1–10 s reconnect
      jitter.
- [x] Implement the per-replica Kafka consumer with `assign()` and seek-to-end,
      and the `realtime.signals` topic class in ADR 0032's registry.
      `RealtimeSignalConsumer` builds its consumer from the shared factory's
      properties with `group.id` and `auto.offset.reset` stripped;
      `EventContract.Retention.SIGNAL` and the `RealtimeSignal.v1` schema are
      registered, and the schema's `additionalProperties: false` is what stops a
      coordinate being added to a signal later.
- [x] Close a stream on token expiry and on `TenantGrantsChanged`; rate limit
      connects per principal and per tenant. `RealtimeStreamMaintenance` listens
      `AFTER_COMMIT` — deliberately unlike `GrantAuditListener`, because closing a
      socket is an external effect that must not happen for a change that rolls
      back.
- [ ] **Not built.** Enrol the kitchen display device principal through the device
      authorization grant, and stop its stream outside service hours. The device
      principal is an ADR 0025 model change — a non-human principal type with its
      own enrolment and revocation — and the stream works today for a human
      principal. Until it lands, a wall display signs in as a person, which is the
      shared-login problem this bullet exists to end.
- [ ] **Not built here.** Configure the reverse proxy: HTTP/2, `proxy_buffering
      off` for `text/event-stream`, read timeout above the heartbeat, and
      `worker_connections` sized for two slots per stream. This is `infra/` and
      the ADR 0034 host, not application code; the three settings are named here
      because each one's failure looks like a network fault rather than like a
      configuration mistake.
- [x] Add the 500-stream cap and the 350-stream dashboard threshold, and confirm
      neither becomes a night page. `SseStreamRegistry` refuses past the cap with
      `RATE_LIMIT_EXCEEDED` and publishes `horecaos.realtime.streams.open` and
      `.headroom`; the threshold logs a warning and raises no alert, because ADR
      0034's night-alert budget is three and this is not one of them.
- [x] Add `courier.position.read`, `courier.track.reveal`, and
      `courier.duty.manage` to the ADR 0025 registry; place the first and third
      in role bundles and the second in none. `PLATFORM_ADMIN` is now
      `complementOf(COURIER_TRACK_REVEAL)` — a superuser who implicitly holds the
      reveal never has to ask for it, so nothing is ever recorded and the control
      reduces to a comment.
- [x] Implement duty sessions, gated on the ADR 0042 registration check.
      `CourierShiftPort` is the seam; until ADR 0042 supplies it the stand-in
      refuses every open, which is the correct direction to fail. **The visible
      on-duty indicator in the courier app is not built** — the ingest response
      carries the collection gate and the suspended flag the indicator needs, and
      the app is ADR 0035's.
- [x] Implement telemetry ingest with the natural-key idempotency, the staleness
      and accuracy rules, and the off-duty rejection. The natural key is the
      one-minute window rather than the observation, because that is the grain the
      track stores; a replayed batch changes nothing and a batch completing a
      partly written minute replaces it.
- [x] Implement the live table, the daily-partitioned encrypted track, and the
      per-assignment summary, with the reporting and support roles ungranted.
      `V0041` grants nothing to `horecaos_reporting_read` and revokes explicitly, so
      a future schema-wide grant cannot pick these tables up by accident.
- [x] Implement the retention jobs and the startup check on
      `track_retention_days >= settlement_period_days + statement_dispute_days`,
      report-only first per ADR 0029. `TrackRetentionSweeper` drops whole daily
      partitions and expires live rows an hour after sign-off;
      `TrackRetentionFloorCheck` recomputes the floor at every start from
      `SettlementCalendarPort` and checks every stored value at every scope, so
      lengthening the settlement calendar moves the floor rather than leaving a
      copied constant behind.
- [ ] **Not built.** Write the courier transparency notice with the tenant,
      covering what is collected, who sees it, and the 30 days. It is a document
      rather than code, and ADR 0045's rollout makes it a precondition for
      switching ingest on rather than for building it.
- [ ] **Not built here.** Implement the customer milestone notifications on the
      ADR 0020 path and the token-scoped polled tracking page with the promised
      window and its revision. Owned by `notifications` and `ordering`: every
      milestone is an existing ADR 0019 state, and this ADR adds no channel and no
      template engine. The property this ADR is responsible for — that no
      customer-reachable response contains a coordinate — holds by construction,
      because nothing outside `telemetry` can read a position.
- [ ] **Not built here.** Add the partner coordinate-stripping normalization and
      its contract tests to both delivery adapters. `YandexDeliveryAdapter` and
      `NoorDeliveryAdapter` are `integration.camel.delivery` and belong to ADR
      0014. Nothing in `telemetry` can store a partner coordinate: ingest requires
      an open duty session, and a partner's courier has none.
- [x] Add the customer-surface coordinate assertion to the test suite.
      `TelemetryDecisionTests` asserts structurally, with ADR 0029's classification
      scanner, that no coordinate, accuracy, speed, or battery value is reachable
      from the signal payload, and that a courier who cannot be drawn is reported
      by identity with no coordinate at all rather than approximately.

## Exit criteria

A kitchen queue, a wall-board counter, a stop list, a dispatcher board, and a
courier map all update within the stated budget without polling; every one still
updates correctly with the stream disabled; a revoked grant or an expired token
closes the stream within the agreed bound; the box holds the full pilot
connection set without breaching its own CPU trigger in ADR 0034.

No coordinate or device telemetry value reaches a Kafka topic, a log, a trace, or
any customer-reachable response; a customer receives the milestone set above and
a promised window and no position; a partner-supplied coordinate is not stored; a
courier's track is unrecoverable at coordinate precision after 30 days while the
distance and confirmation points a settlement dispute depends on remain; every
reveal of a stored track names an actor, a courier, a window, and a purpose; and
no courier earning figure changes when a track is dropped, because no earning
figure was ever computed from one.

## References

- ADR 0004 transactional outbox; ADR 0014 delivery sourcing and partner
  orchestration; ADR 0019 order state machine; ADR 0020 notification delivery;
  ADR 0025 capability model; ADR 0027 audit and approval; ADR 0029 PII protection
  and retention; ADR 0030 configuration and policy resolution; ADR 0031 HTTP API
  conventions; ADR 0032 event contract governance; ADR 0033 caching and shared
  runtime state; ADR 0034 hosting topology; ADR 0035 frontend platform; ADR 0041
  kitchen execution; ADR 0042 courier compensation and settlement; ADR 0043
  reporting and the metric layer.
