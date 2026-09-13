# ADR 0110: Backward status transitions are compensating edges, gated on ORDER_STATE_OVERRIDE

- Decision status: Proposed
- Implementation status: Partial — `OrderStateMachine` declares exactly the two
  edges orders.md §0.2 names, `READY -> PREPARING` and `FULFILLING -> READY`,
  in a table disjoint from the forward graph
  (`OrderStateMachine.compensatingTransitionsFrom`/`isCompensating`).
  `OrderStateService.override` performs the transition — refusing a target the
  compensating table does not name, a stale version, and (for
  `FULFILLING -> READY`, the one edge that crosses the ADR 0036
  `occupiesCapacity()` boundary) a full kitchen — and records both the
  `order_state_history` timeline row and an `ordering.order.state-override`
  audit fact, distinct from an ordinary advance's. `OrderOutcomeService.override`
  resolves the mandatory registry reason and refuses a missing, unknown,
  wrong-kind or archived one. `OperationsOrderController` serves
  `POST .../orders/{orderId}/state-overrides`, declaring
  `Capability.ORDER_STATE_OVERRIDE` alone — never `ORDER_ADVANCE` — so a
  principal lacking it is refused at the endpoint by the same
  `RequiresCapability` interceptor every other mutating endpoint here goes
  through, proved by `OperationsOrderControllerActionCapabilitiesHttpTests`
  through the real HTTP stack; `stateOverride` also now calls the controller's
  own `requireOrderAtLocation(tenantId, orderId, locationId)` guard before
  touching the order — a second-pass adversarial review found this endpoint
  was the one mutating handler on `OperationsOrderController` that resolved
  its order by tenant+id alone, so a principal holding the capability at
  Branch A only could name an order actually belonging to Branch B of the
  same tenant and have the compensating transition, its timeline row and its
  ADR 0036 capacity claim/reclaim applied there; `OperationsOrderControllerActionCapabilitiesHttpTests`
  now proves the cross-location call is refused 404, not merely the
  capability name. `OrderActionsPolicy.availableFor` offers
  `OrderActionCode.OVERRIDE` exactly where the compensating table names an
  edge and the principal holds the capability — `OrderActionsPolicyTests`
  drift-proofs the offered targets against the machine's own table and proves
  `TENANT_ADMIN`/`TENANT_OWNER` are the only two `PlatformRole` bundles that
  hold it among those inspected. The `FULFILLING -> READY` capacity-reclaim
  refusal path is now proved under an actual at-capacity branch too
  (`overridingFulfillingToReadyRefusesWhenTheBranchIsAtCapacity`, alongside
  the successful-reclaim proof already in place). Not built: a frontend
  affordance — this wave is backend-only by brief, and the console has no
  override button or dialog yet; and a dedicated `OutcomeReasonKind` for the
  override reason (see Open inputs).
- Date proposed: 2026-09-13
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0019, ADR 0025, ADR 0027, ADR 0029, ADR 0036, ADR 0039, ADR 0105
- Supersedes / Superseded by: — (amends ADR 0019; see that record's own dated
  status addition rather than a rewritten Decision/Alternatives/Consequences)
- Open inputs:
  - Whether the override's mandatory reason deserves its own `OutcomeReasonKind`
    (e.g. `OPERATIONS_CORRECTION`) rather than reusing `CANCELLATION`-kind
    reasons for a transition that cancels nothing — minting one widens
    `ck_outcome_reason_kind` and `ck_outcome_reason_category`, and this wave
    carries no migration number to do it with (platform owner, next wave that
    touches `ordering.order_outcome_reasons`).
  - Whether `FULFILLING -> READY`'s capacity-reclaim refusal
    (`OrderStateService.KitchenAtCapacityException`) needs its own operator-facing
    message and retry guidance, or whether the generic `RESOURCE_CONFLICT`
    rendering §4.1 already specifies is enough (product, once a branch is
    observed to actually hit it).
  - Whether a third or fourth compensating edge is ever justified (e.g.
    `CONFIRMED -> AWAITING_APPROVAL` for a payment dispute) — this record
    deliberately declares only the two orders.md §0.2 names and takes no
    position on any other (platform owner, product).
  - The console affordance itself: button placement, confirmation copy, and
    the reason picker's UI — none of this wave's brief, and unbuilt (frontend
    owner, whichever wave next touches `order-actions.ts`/`order-queue.ts`).

## Context

An operator who advances an order to `READY` by mistake has no way back.
Cancellation is not the answer either: `OrderStateService.cancel`'s reasonless
path is refused once the order is `CONFIRMED` or later (`CancellationNotPermittedException`),
because past that point the stock disposition and the liable party are real
decisions ADR 0019 refuses to guess at (orders.md §0.3). The gap map's own
row for this (`1.1h`) is blunt about the consequence: *"the IA's stated
improvement on Delever's silent reversals is currently a promise with no
machine behind it."*

The legacy dashboard did have a way back, and it is the anti-pattern this
record exists to not repeat: `OrderActionButtons.tsx` rendered a `FaBackward`
button on `READY`, `DELIVERING` and `COMPLETED` that `PUT`s a status straight
backwards with no reason, no confirmation and no distinct record — a silent
one-click reversal indistinguishable, in the database, from the forward
transition it undoes. `OrderStateMachine` is authoritative and forward-only
(`ck_order_status`, `docs/domains/state-machines.md`'s own tenant-omission
list), and `order_state_history` refuses a no-op move
(`ck_order_history_moves`). Reopening the machine to a second, reversed copy of
itself — a `to -> from` entry for every `from -> to` — would produce exactly
what ADR 0019's own consequences section warns against for a different reason:
two ways to reach one state, and a reader of the order's history unable to
tell a correction from an ordinary step by looking at the edge alone.

`Capability.ORDER_STATE_OVERRIDE` already exists in the registry
(`order.state.override`), granted to `TENANT_ADMIN` and `TENANT_OWNER` and to
nobody by default — a capability with nothing to authorize until this record.
ADR 0105 (wave `P05`) made `OrderActionsPolicy.availableFor` principal-aware
for the other five action codes and named this record's scope explicitly in
its own Open inputs: *"whether backward status transitions ... reuse this
policy directly or sit behind a separate override policy — ADR 0019
amendment, wave P41."*

This wave carries no Flyway migration number. Whatever this record decides has
to be expressible entirely in code and in the existing schema — no new table,
no new column, no widened `CHECK` constraint.

## Decision

**A compensating edge is a new forward step that happens to restore an
earlier status, never a literal reversal.** `OrderStateMachine` declares
exactly two, in a table disjoint from the forward graph:

```text
READY      -> PREPARING   (advanced to ready by mistake)
FULFILLING -> READY       (a courier assignment or handover reversed
                            before the food ever left)
```

Nothing further back is declared. An operator who mis-clicked past `CONFIRMED`
corrects the commercial record with an amendment or a reasoned cancellation
(ADR 0039), not a chain of reversals — chasing every possible mistake back to
`RECEIVED` is exactly the generic-undo shape the brief for this wave names as
the trap to avoid, and it is also product scope this record does not open.

The two tables are kept apart on purpose, at the type level:
`OrderStateMachine.transitionsFrom`/`permits` answer the forward graph exactly
as before, and `compensatingTransitionsFrom`/`isCompensating` answer a second
question entirely. `OrderStateService.advance` — every line cook's endpoint —
consults only the first; the new `OrderStateService.override` consults only
the second. Neither ever merges into the other, which is what keeps a reader
of `order_state_history` able to tell a correction from an ordinary advance by
the `(from, to)` pair alone: no forward edge ever produces `READY ->
PREPARING` or `FULFILLING -> READY`, so seeing one in the timeline **is** the
tell, with no extra flag required.

**The override is gated on `Capability.ORDER_STATE_OVERRIDE` alone, never
`ORDER_ADVANCE`**, at a dedicated endpoint, `POST
.../orders/{orderId}/state-overrides`, distinct from `POST .../state-actions`
for the same reason `ORDER_CANCEL` already has its own endpoint separate from
`ORDER_ADVANCE`: a different capability, a mandatory reason, and a console
affordance that must not be reachable from the ordinary advance button.
`OrderActionsPolicy.availableFor` gains a matching `OVERRIDE` branch, gated on
the same capability and offering exactly the targets
`compensatingTransitionsFrom` names — so the array is never wrong in either
direction: an `ORDER_ADVANCE`-only principal never sees it, and an
`ORDER_STATE_OVERRIDE` holder sees it exactly where the machine allows it.

**The reason is mandatory and comes from the registry, never free text.**
`OrderOutcomeService.override` resolves a `reasonId` against
`ordering.order_outcome_reasons`, requiring it to be `ACTIVE` and, narrowly,
of kind `CANCELLATION` — reused rather than a new kind, because minting
`OPERATIONS_OVERRIDE` (or similar) as a third value widens
`ck_outcome_reason_kind`, and this wave carries no migration number to do that
with. The consequence columns a cancellation reason carries (stock
disposition, liability, refund posture) are never read by `override` — only
the reason's identity and version are, exactly as little as a `COMPLETION`
reason's fulfilment-mode list would be if this method accepted one, which is
exactly why it does not. This reuse is recorded as an explicit open input
above, not smuggled in as if it were the natural home for the concept.

**Every override writes its own audit fact and its own timeline row.** The
audit action code is `ordering.order.state-override`, distinct from
`ordering.order.state-action` — an ordinary advance — so "how many times has
this branch had to correct itself" is a query against one action code rather
than a guess from status pairs. The timeline row is an ordinary
`ordering.order_state_history` insert with `TransitionTrigger.OPERATIONS_ACTION`
(the CHECK constraint this column carries, widened once already for
`KITCHEN_PROGRESS` in V0087, names no `OVERRIDE` value and this wave has no
migration to add one) — the actor type is correctly "a person under this
capability", and the `(from, to)` pair itself is what marks the row a
correction, per the point above.

**Terminal orders stay terminal.** No compensating edge is declared from any
terminal status — not by a guard somebody could forget to add, but by the
compensating table simply having no entry for one. A cancelled or completed
order is never reopened by this record; correcting one is, again, a fresh
amendment or a new order, never a status write backward into a closed book.

**`FULFILLING -> READY` re-claims the ADR 0036 kitchen slot it gave up.**
`OrderStatus.occupiesCapacity()` states plainly that `FULFILLING` does not
occupy the concurrent-order ceiling ("a courier holding the bag is not a
kitchen constraint") and `READY` does. Reverting the edge therefore has to
re-claim a slot, through the same `LocationCapacityPort` a checkout claims
from — and can lose, exactly as a checkout can: another order may have filled
the branch to capacity while this one had a courier. `override` refuses that
case (`KitchenAtCapacityException`) rather than silently letting the branch's
own concurrent-order count run over what it configured. `READY -> PREPARING`
needs no such handling: both ends already occupy the slot.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A literal reversal — declare `PREPARING -> READY` backward as `READY -> PREPARING` in the same forward table `ALLOWED` uses | Produces two nested state machines (a forward one and its mirror) and a reader of `order_state_history` who cannot tell a correction from an ordinary advance by the edge alone; the legacy dashboard's own failure mode, restated in a typed enum instead of a REST `PUT` | Never |
| A generic `UNDO` action that reverts "whatever the last transition was" | The brief's own named trap. An undo with no declared target status cannot say what it is undoing to a reader six months later, and it is exactly what makes the fiscal and POS consequences of a future amendment unanswerable — every compensating edge here is a named transition to a named status instead | Never |
| Reuse `ORDER_ADVANCE` and the existing `state-actions` endpoint, distinguishing an override only by target status | `ORDER_ADVANCE` reaches every line cook (`LOCATION_STAFF` and above); a mis-typed target on that one endpoint would let the same population that advances orders also reverse them, which is exactly the "almost nobody" boundary `Capability.ORDER_STATE_OVERRIDE`'s own doc draws against `ORDER_ADVANCE` | Never — the two are different powers over the same order, the same reasoning ADR 0105 already applied to keep `CANCEL` off `ADVANCE`'s endpoint |
| Mint a dedicated `OutcomeReasonKind.OPERATIONS_OVERRIDE` now | Requires widening `ck_outcome_reason_kind` and `ck_outcome_reason_category`, and this wave carries no reserved migration number to do it with | The next wave that touches `ordering.order_outcome_reasons` for any reason, if the reused `CANCELLATION` vocabulary is found not to fit an operator's actual corrections (open input above) |
| Allow every terminal status a compensating edge back to its predecessor (e.g. `CANCELLED -> CONFIRMED`) | Reopens a closed commercial record — exactly what ADR 0019's immutable-order model and this record's own "terminal orders stay terminal" trap exist to prevent. A cancelled order's stock, payment and fiscal consequences may already be in flight; reopening it would need to unwind all three, which is a different, much larger decision than a kitchen-path correction | Never, as stated; a genuine "I cancelled the wrong order" support workflow is a new order or a documented manual remedy, not a machine edge |
| Skip the `FULFILLING -> READY` capacity reclaim; leave the slot released | Cheaper, but silently lets a branch's live concurrent-order count run under what `location_capacity_holds` actually reports once a courier assignment is reversed — an accounting gap ADR 0036 exists to prevent | Never; the reclaim is built in this record for exactly this reason |

## Consequences

### Positive

- The gap map's headline complaint about `1.1h` is answered precisely, not
  generically: a named, capability-gated, reasoned, audited way back for the
  two mistakes orders.md §0.2 names, and no others.
- `order_state_history` stays a single, honest timeline: a correction is
  identifiable by its `(from, to)` pair alone, with no separate "is this a
  reversal" flag a future reader could fail to check.
- `OrderActionsPolicy`'s principal-aware shape (ADR 0105) is extended, not
  reopened — the `OVERRIDE` branch reads the same `grantedCapabilities` input
  every other branch does, so the P05 drift-test guarantees ("an actor is
  never offered a button its own grants would refuse") now cover a sixth
  code with zero changes to the other five.
- The ADR 0036 kitchen ceiling cannot silently drift out of true because of
  this record's own new edge — the one case where reverting a transition
  changes capacity occupancy is handled explicitly rather than left as a
  gap nobody would notice until a branch overbooked itself.

### Negative

- Reusing a `CANCELLATION`-kind reason for a transition that cancels nothing
  is a real modelling compromise, not a preference: a tenant admin authoring
  reasons sees "Ошибка оператора" sitting in the same list as "Нет товара"
  and "Клиент отменил", distinguished only by an internal category
  (`OTHER`) rather than a kind of its own. A reason author who does not read
  this record's Open inputs could reasonably ask why a correction cites a
  cancellation reason.
- `KitchenAtCapacityException`'s refusal path exists and is exercised on the
  open (non-full) branch, but this wave ships no test that actually fills a
  branch to its concurrent-order ceiling and then reverts a `FULFILLING`
  order into it — building that fixture (a location with
  `max_concurrent_orders` configured and holds pre-claimed to the ceiling)
  was judged a worse trade than shipping the two required, well-tested edges
  and naming the gap plainly.
- No console affordance ships in this record. `Capability.ORDER_STATE_OVERRIDE`
  is real and enforced at the endpoint, and `actions[]` correctly offers
  `OVERRIDE` to a principal who holds it — but nothing in
  `frontend/operations` renders a button, a confirmation dialog or a reason
  picker for it yet, so a tenant admin cannot use this from the console today
  despite the API being complete.

### Accepted trade-offs

Two edges only, not a general "go back N steps" mechanism: a `PREPARING ->
RECEIVED` or `COMPLETED -> FULFILLING` correction, if ever needed, is a new
ADR amendment naming a new edge, not a parameter this record's shape already
accepts. That is deliberate — the brief's trap is specifically against a
mechanism general enough to make every future mistake correctable by
construction, which is what turns "amend the record" into "silently rewrite
history" the moment the wrong two statuses are named. The cost is that a
correction this record does not anticipate stays a support workflow rather
than a self-service button, which is judged the safer failure mode for a
change to commercial history.

## Specification

**Domain — `OrderStateMachine`** (`ordering.domain`):

```java
public static Set<OrderStatus> compensatingTransitionsFrom(OrderStatus from);
public static boolean isCompensating(OrderStatus from, OrderStatus to);
```

Backed by a second `EnumMap`, `COMPENSATING`, disjoint from `ALLOWED`:
`READY -> {PREPARING}`, `FULFILLING -> {READY}`. No terminal status appears as
a key. `permits`/`transitionsFrom` are unchanged.

**Application — `OrderStateService`**:

```java
@Transactional
public DecisionResult override(
        UUID tenantId, UUID orderId, OrderStatus target, int expectedVersion,
        String reasonCode, UUID reasonId, int reasonVersion,
        String actorType, String actorId, @Nullable String correlationId);
```

Guards, in order: version match (`StaleOrderException`), `isCompensating`
(`OrderStateMachine.IllegalTransitionException` otherwise — the same exception
`advance` throws, so the controller's existing catch block needs no new
branch for this case), then, only when the edge crosses the
`occupiesCapacity()` boundary (today, only `FULFILLING -> READY`), a
`LocationCapacityPort.claimCapacity` call refused as `KitchenAtCapacityException`
on `AT_CAPACITY`. On success: the conditional `UPDATE` every transition in
this class uses, an `order_state_history` row
(`TransitionTrigger.OPERATIONS_ACTION`), and an `AuditFact` with action code
`ordering.order.state-override` carrying `fromStatus`, `toStatus`, `reasonId`,
`reasonVersion` and `compensating: true` in its `changed` map. No inventory,
payment, settlement or event consequence is applied — every compensating edge
stays within `PREPARING`/`READY`/`FULFILLING`, none terminal, none changing
the inventory reservation, and none of the three publishes an event today
regardless of how it was reached (ADR 0019's own `applyConsequences` `default`
case).

**Application — `OrderOutcomeService`**:

```java
public record OverrideCommand(UUID reasonId, String actorType, String actorId, @Nullable String correlationId) {}

@Transactional
public OrderStateService.DecisionResult override(
        UUID tenantId, UUID orderId, OrderStatus target, int expectedVersion, OverrideCommand command);
```

Resolves `reasonId` against `OrderOutcomeReasonService`, requiring
`kind == CANCELLATION` and `status == ACTIVE` (`IllegalArgumentException`
otherwise, matching `cancel`'s own wording style), then delegates to
`OrderStateService.override` with the reason's `systemCategory`/`id`/`version`.

**Web — `OperationsOrderController`**:

```text
POST /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/{orderId}/state-overrides
  @RequiresCapability(ORDER_STATE_OVERRIDE, LOCATION, mutating = true)
  If-Match: "<expectedVersion>"
  { "targetStatus": "PREPARING" | "READY", "reasonId": "<uuid>" }
  -> DecisionResponse (reused, unchanged shape)
```

`ACTIONS_POLICY_CAPABILITIES` gains `ORDER_STATE_OVERRIDE` as its fifth
member, read once per request exactly like the other four.

**Application — `OrderActionsPolicy`**:

```java
if (grantedCapabilities.contains(Capability.ORDER_STATE_OVERRIDE)) {
    for (OrderStatus target : OrderStateMachine.compensatingTransitionsFrom(status)) {
        actions.add(new OrderAction(OrderActionCode.OVERRIDE, target));
    }
}
```

A new `OrderActionCode.OVERRIDE`, the sixth value, targeting
`POST .../state-overrides` — emitted today, unlike five of `OrderActionCode`'s
other declared values (ADR 0105).

## Rollout and rollback

No migration. Rollback is reverting the touched files
(`OrderStateMachine.java`, `OrderStateService.java`, `OrderOutcomeService.java`,
`OrderActionCode.java`, `OrderActionsPolicy.java`, `OperationsOrderController.java`)
and their tests; nothing downstream depends on `OrderActionCode.OVERRIDE`
existing, and no other wave's brief names this endpoint. Because
`Capability.ORDER_STATE_OVERRIDE` was already registered and granted to
nobody by default before this record, rollout requires no grant migration
either — a tenant admin already holds it the moment this ships, by virtue of
`TENANT_ADMIN`/`TENANT_OWNER`'s existing bundle.

## Implementation checklist

- [x] `OrderStateMachine` declares `READY -> PREPARING` and
      `FULFILLING -> READY` as compensating, in a table disjoint from the
      forward graph
- [x] `OrderStateService.override`: guards version, compensating-edge
      membership, and (for the one edge that needs it) kitchen capacity;
      records the timeline row and the audit fact
- [x] `OrderOutcomeService.override`: resolves and validates the mandatory
      registry reason
- [x] `POST .../state-overrides`, declaring `ORDER_STATE_OVERRIDE` alone
- [x] `OrderActionsPolicy`/`OrderActionCode` widened with `OVERRIDE`,
      emitted (unlike five of the enum's other declared values)
- [x] `OrderStateMachineTests`: the two edges named exactly, no others
      declared, no compensating edge is also a forward edge, terminal
      statuses declare none
- [x] `OrderAmendmentAndOutcomeTests`: an override writes both the audit
      fact and the timeline row; refuses an unknown, wrong-kind or archived
      reason; refuses a non-compensating target and never reopens a
      terminal order
- [x] `OrderActionsPolicyTests`: offered targets drift-proofed against the
      machine; never offered without the capability; never offered on a
      terminal status even when granted; only `TENANT_ADMIN`/`TENANT_OWNER`
      hold the capability among the roles this suite inspects
- [x] `OperationsOrderControllerActionCapabilitiesTests`/`...HttpTests`:
      the fifth capability is asked for at `LOCATION` scope; a principal
      holding `ORDER_ADVANCE` alone is refused at the endpoint with a real
      HTTP 403, not merely omitted from `actions[]`
- [x] A test that fills a branch to its ADR 0036 ceiling and proves
      `FULFILLING -> READY` is refused as `KitchenAtCapacityException`,
      leaving the order and its capacity hold untouched
- [x] `stateOverride` calls `requireOrderAtLocation` before invoking
      `outcomes.override(...)`, and a regression test proves a same-tenant,
      cross-location order id is refused 404 rather than transitioned
- [ ] A dedicated `OutcomeReasonKind` for the override reason, if the
      reused `CANCELLATION` vocabulary proves not to fit (open input above)
- [ ] The console affordance: button, confirmation dialog and reason
      picker in `frontend/operations` (open input above; unowned)

## Exit criteria

An operator holding `ORDER_STATE_OVERRIDE` can move a `READY` order back to
`PREPARING` or a `FULFILLING` order back to `READY`, citing a registry reason,
and the result is a distinct, audited, timelined fact — proved today by
`OrderStateMachineTests`, `OrderActionsPolicyTests`,
`OrderAmendmentAndOutcomeTests` and
`OperationsOrderControllerActionCapabilitiesHttpTests`. A principal lacking
the capability is refused at the endpoint, not merely unable to see the
button, and a principal holding it only at a sibling branch is refused 404
rather than transitioning another branch's order. No other status ever gains
a way back, and no terminal order is ever reopened. Fully met once a console
affordance exists to reach this endpoint at all (open input above).

## References

- `docs/operations-spec/orders.md` §0.2, §4.3, §4.11, §11 (point 3), §10
- `docs/operations-gap-map.md` row `1.1h`; wave `P41`
- ADR 0019 (order lifecycle; this record's amendment), ADR 0025 (capability
  model), ADR 0027 (audit evidence), ADR 0036 (concurrent-order ceiling), ADR
  0039 (outcome reasons and cancellation), ADR 0105 (`actions[]` as a
  capability array, wave `P05`)
