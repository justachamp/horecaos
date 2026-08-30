# ADR 0017: Inventory ledger, reservations, and availability

- Decision status: Accepted
- Implementation status: Partial — V0019 carries `inventory.stock_items`,
  `positions`, `movements`, `reservations` and `reservation_lines`; the
  `inventory` module implements the BINARY and UNTRACKED slice end to end
  (`InventoryService` with a 15-minute `RESERVATION_TTL`, atomic
  reserve/commit/release over `JdbcInventoryStore` with an append-only movement
  ledger and sequence numbers), `InventoryController` exposes stock-item
  creation, availability set and availability read under `inventory.adjust` /
  `inventory.read`, and ordering actually calls the port
  (`CheckoutService`, `OrderInventoryProcess`). Not built: QUANTITY tracking —
  it throws `UnsupportedTrackingModeException`; the durable expiry lease —
  `expireStaleReservations` has no caller and no `@Scheduled` job, so holds
  expire only lazily when the same quote is re-reserved; the POS inventory apply;
  any outbox event; reconciliation tooling; and there is no inventory test class
  at all.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0004, ADR 0005, ADR 0006, ADR 0016
- Supersedes / Superseded by: —
- Open inputs: Negative-stock policy and cancellation restock rules for QUANTITY tracking (product, operations) — neither applies to the BINARY-only first slice; reservation TTL decided 2026-08-21 as 15 minutes, matching the ADR 0018 quote TTL

## Context

Qoida needs location-specific availability without overselling under concurrent
checkout, POS updates, cancellations, and operator corrections. Some restaurants
track exact ingredient or item quantities, while others only mark an item
available/unavailable. A mutable `stock` number alone cannot explain changes,
reconcile failures, or safely reserve supply for carts/orders.

## Decision

Inventory owns an append-only movement ledger, a transactionally maintained
position, and expiring reservations. Tracking mode is configured per location
and stock item:

- `QUANTITY`: enforce on-hand, reserved, and available quantities.
- `BINARY`: enforce an explicit available/unavailable state.
- `UNTRACKED`: inventory never blocks checkout, but catalog publication and
  operational status can still hide an offering.

The first release treats a catalog variant as the stock item. Ingredient/BOM
depletion is a future extension that must not be simulated with modifier hacks.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A single mutable `stock` quantity column | Cannot explain how a position reached its value, loses updates under concurrent checkout, and offers nothing to reconcile against POS observations | Never |
| Derive availability by counting open orders | A scan on every storefront request, and still wrong under concurrency because two checkouts can count the same free unit | Never |
| Hold reservations in Redis/Valkey counters | Not transactional with order creation, and a restart or eviction silently releases or duplicates stock with no ledger evidence | Never for reservation truth |
| Full event sourcing with no materialized position | Every menu render would replay a ledger. The chosen model keeps the immutable ledger and a transactionally maintained position beside it | Never; the position is a projection, not a second authority |
| Optimistic retry without ordered locking on multi-line reservations | Two carts holding overlapping items deadlock or livelock. Deterministic stock-item ordering avoids both | Never |
| Ingredient-level or bill-of-materials depletion in the first release | Correct eventually and genuinely useful for restaurants, but it needs recipes that do not exist yet. Simulating it with modifier arithmetic would be a hack that is hard to unwind | Recipes and yield data exist and a tenant needs ingredient depletion. Extend the stock item type rather than reinterpreting variants |
| Let POS observations overwrite reservations | A provider export would cancel a customer's held order silently | Never |

## Invariants

For quantity tracking:

```text
available = on_hand - reserved
on_hand >= 0 unless an approved location policy permits negative reconciliation
reserved >= 0
ACTIVE reservations cannot exceed reservable supply
```

All mutations are tenant/brand/location scoped. A stock item can reference only
a variant offered by that location. A movement is immutable; correction is a
new compensating movement.

## What is built so far

Stock items, positions, the append-only movement ledger, and the reservation
path, for `BINARY` and `UNTRACKED` tracking only.

**`QUANTITY` is refused at runtime rather than half-implemented.** The schema
accommodates it because the model must, but a quantity path that accepted numbers
without enforcing them would let a location oversell silently — which is worse
than an error nobody can ignore.

**An unlisted variant is unavailable, not available.** Defaulting the other way
would let a location sell anything on the brand's menu simply because nobody had
listed it, and a kitchen would receive orders for dishes it does not make.

**Every availability change writes a movement with its reason.** The position is
derived state that could be rebuilt; the ledger is the record of what happened.
Writing only the position would leave "why was this sold out at 19:00" with no
answer at all. Repeating a toggle that changes nothing writes nothing, so the
ledger holds only real transitions.

**Holds are per owner and idempotent.** A unique constraint on
`(tenant, owner_type, owner_id)` means a retried checkout returns the existing
hold rather than taking a second one. Terminal transitions carry a
`status = 'HELD'` predicate in the statement, so a release arriving after a
commit cannot undo it.

**Reservations expire at 15 minutes, matching the ADR 0018 quote TTL.** A hold
outliving its quote would keep stock back for a price nobody can still accept.

Not yet built: quantity tracking and its ledger reconciliation, POS observations,
waste and correction flows, and the scheduled sweep that expires abandoned holds
— `expireStaleReservations` exists and has no scheduler yet.

## Physical model

### `inventory.stock_items`

```text
id, tenant_id, brand_id, location_id, variant_id
tracking_mode, unit_code, status, external_mapping_id null
version, created_at, updated_at
unique(tenant_id, location_id, variant_id)
```

### `inventory.positions`

```text
stock_item_id, tenant_id, brand_id, location_id
on_hand_quantity, reserved_quantity, binary_available null
position_sequence, version, updated_at
```

### `inventory.movements`

```text
id, tenant_id, brand_id, location_id, stock_item_id
sequence_number, movement_type, quantity_delta
source_type, source_id, idempotency_key, reason_code
actor_type, actor_id null, occurred_at, recorded_at
unique(tenant_id, stock_item_id, sequence_number)
unique(tenant_id, stock_item_id, idempotency_key)
```

Movement types include receipt, sale commitment, release/return, waste,
correction, POS reconciliation, and administrative adjustment.

### `inventory.reservations`

```text
id, tenant_id, brand_id, location_id, owner_type, owner_id
status, expires_at, idempotency_key, version, timestamps
unique(tenant_id, owner_type, owner_id)
```

### `inventory.reservation_lines`

```text
reservation_id, tenant_id, stock_item_id, quantity
status, committed_movement_id null, released_at null
unique(reservation_id, stock_item_id)
```

## Reservation lifecycle

```text
ACTIVE -> COMMITTED
ACTIVE -> RELEASED
ACTIVE -> EXPIRED
```

Terminal states are immutable. Extending expiry is a versioned command with a
maximum lifetime policy. Committing creates sale movements and reduces both
on-hand and reserved in one transaction. Releasing/expiring reduces reserved
only. An order cancellation after commit creates a separate return or waste
decision; it does not reopen the reservation.

## Atomic reservation algorithm

For a deterministic, sorted set of stock-item IDs:

1. Validate tenant, location, offering, tracking mode, and requested quantities.
2. Lock positions in stock-item order or use conditional SQL updates.
3. For each quantity item, update only where
   `on_hand_quantity - reserved_quantity >= requested`.
4. Create reservation/lines and advance position versions.
5. Insert a PII-free outbox event in the same transaction.
6. On any failed item, roll back the entire reservation.

The command is retried through its unique owner/idempotency key. It never calls
Kafka, POS, or another external system inside the transaction.

## Expiration and restart safety

A PostgreSQL-backed expiry job stores due time, lease owner, lease expiry, and
attempt count. Workers use `FOR UPDATE SKIP LOCKED`, recheck status/expiry under
lock, and release exactly once. Kafka may announce `InventoryReservationExpired`
after commit but is not the timer or authority.

## POS reconciliation

POS stock observations enter ADR 0012 staging. Apply creates idempotent
`POS_RECONCILIATION` movements or binary status changes with the sync run and
external source reference. It never overwrites reservations. If an observation
would make available stock negative, the location policy chooses:

- record the position and create an oversold operational exception;
- quarantine the change for review; or
- clamp sellable availability to zero while retaining actual evidence.

The policy cannot silently delete or fabricate reservations.

## Availability projection

Storefront availability combines:

```text
active catalog publication
active location offering and schedule
location operational status
inventory position/tracking mode
temporary sales suspension
```

The projection returns a reason code and version, not only a boolean. Quantity
need not be exposed publicly. Consumers invalidate by variant/location event and
fall back to a short TTL.

## APIs and commands

```text
PUT  /api/v1/operations/locations/{locationId}/inventory/{variantId}/availability
POST /api/v1/operations/locations/{locationId}/inventory/{variantId}/adjustments
GET  /api/v1/operations/locations/{locationId}/inventory
GET  /api/v1/operations/inventory/{stockItemId}/movements

ReserveInventory(owner, lines, expiresAt, idempotencyKey)
CommitInventoryReservation(reservationId, expectedVersion)
ReleaseInventoryReservation(reservationId, reason, expectedVersion)
```

Manual adjustments require reason, expected version, scoped permission, and
strong audit. Checkout uses internal application ports, not Operations HTTP.

## Events

```text
InventoryPositionChanged
InventoryAvailabilityChanged
InventoryReserved
InventoryReservationCommitted
InventoryReservationReleased
InventoryReservationExpired
InventoryReconciliationRequired
```

Coalesce high-frequency position events when safe, but never drop ledger facts.
Partition by stock item for positions and reservation ID for lifecycle events.

## Testing

- Hundreds of concurrent reserve attempts never oversell a finite position.
- Multi-line failure rolls back all lines and retry returns the original result.
- Commit/release/expiry races produce one terminal outcome.
- Expiry worker crash/lease recovery does not leak or double-release stock.
- Binary and untracked modes follow their distinct rules.
- POS reconciliation retains reservations and exposes negative conflicts.
- Ledger replay reconstructs the expected position; reconciliation detects any
  divergence.
- Cross-tenant, cross-brand, and wrong-location links fail at SQL boundaries.

## Observability and operations

Expose reservation success/conflict/expiry rates, position-lag and projection-
lag gauges, reconciliation counts, lease age, and low/zero availability counts
with bounded labels. Operations can inspect a position, ledger, active
reservations, and source references, but cannot edit historical movements.

## Rollout and rollback

Begin with `UNTRACKED` projections, then binary status for one location, then
quantity tracking for explicitly reconciled variants. Shadow reservation checks
before enforcing checkout. Rollback disables enforcement and new mutations but
retains ledger/reservations for reconciliation; active reservations are safely
released by a runbook rather than deleted.

## Consequences

### Positive

- Overselling is prevented by conditional SQL rather than by hope, and hundreds
  of concurrent checkouts settle deterministically.
- Every position is explainable from immutable movements, which makes POS
  reconciliation possible.
- Untracked and binary modes let restaurants that do not count stock use the
  platform without pretending to.

### Negative

- The ledger grows quickly for busy locations and needs retention, partitioning,
  and a reconciliation job to stay affordable.
- Reservation expiry is a durable scheduler with leases, which is more moving
  parts than a timestamp check.
- Ordered locking constrains how reservation calls may be composed, and getting
  the order wrong reintroduces deadlocks.

### Accepted trade-offs

- Quantity tracking is enabled per variant only after explicit reconciliation,
  so most items start untracked and availability is coarser at first.
- A cancellation after commit creates a return or waste decision instead of
  silently restocking, which is more work and more accurate.

## Implementation checklist

- [ ] Approve tracking modes, units, negative-stock, expiry, and cancellation rules.
- [ ] Add stock, position, movement, reservation, line, and expiry-job tables. All five data tables exist in V0019; there is no expiry-job or lease table.
- [ ] Implement quantity/binary value objects and reservation lifecycle. `TrackingMode`, `AvailabilityDecision` and the HELD/COMMITTED/RELEASED/EXPIRED lifecycle are built for BINARY and UNTRACKED; QUANTITY raises `UnsupportedTrackingModeException`.
- [x] Implement raw-SQL atomic reserve/commit/release and ordered locking. `InventoryService.reserveForQuote` / `commit` / `release` over `JdbcInventoryStore`, idempotent per quote id and writing a movement row for every change.
- [ ] Implement durable expiry leases and restart recovery. `InventoryService.expireStaleReservations` exists and nothing calls it — there is no `@Scheduled` sweep and no lease; a hold is only noticed to be stale when its own quote is re-reserved.
- [ ] Connect reviewed POS inventory apply as ledger facts. ADR 0012 has no apply path; `pos` writes nothing to `inventory.*`.
- [ ] Build storefront availability and Operations inspection projections. `GET .../inventory/availability` is the Operations read; the storefront menu does not carry availability and there is no ledger-inspection projection.
- [ ] Publish outbox events and add metrics, alerts, audit, and runbooks. The module references no outbox, records no audit fact and registers no metric.
- [ ] Build position-versus-ledger reconciliation tooling. Nothing recomputes a position from `inventory.movements`.
- [ ] Add race, failure, replay, isolation, and production-shaped load tests. There is no test class under `src/test/java/uz/qoida/platform/inventory` at all.

## Exit criteria

Qoida can enforce binary or quantity availability per location, reserve several
items atomically without overselling, survive retry/crash/expiry races, explain
every position through immutable facts, and reconcile POS observations without
destroying order reservations.
