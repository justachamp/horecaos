# ADR 0172: Bulk courier assignment

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — the machinery is built for two other actions
  and the single-order assignment is built; their join is not. Built: ADR 0039's
  `POST .../tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/bulk-actions`
  (`OperationsOrderController.bulkAction`, `Capability.ORDER_BULK_ACTION`, held by
  `LOCATION_MANAGER` and by no other tenant role bundle, so not by `COURIER_DISPATCHER`), `OrderBulkActionService`
  (N independent calls, one transaction each, `MAX_ORDERS = 200`, per-item audit facts,
  a replay of the same `Idempotency-Key` answered from `ordering.bulk_operations` and
  `bulk_operation_items`, V0193), the closed enum `BulkActionType { ADVANCE, CANCEL }`
  and its mirror `ck_bulk_operation_action_type`, whose own Javadoc says what is
  deliberately absent; V0029's header calls bulk courier assignment "ADR 0039's first
  supported action"; the single assignment `ManualDispatchService.assign` behind
  `POST .../dispatch/plans/{planId}/assign` (`delivery.manual_assign`); the server-driven
  `ASSIGN_COURIER` entry of an order row's `actions[]` (`OrderActionsPolicy`, row `1.1e`),
  offered when the order is a delivery with a plan nobody carries; and a client-side
  loop on the dispatch board (`dispatch-board-page.ts`, row `3.1`: "one
  `DispatchApi.assign` call per plan (no bulk endpoint exists ...)"). The selection bar and the
  result panel are built (`order-queue-bulk-bar.ts`, `order-queue-bulk-result.ts`,
  «Повторить проблемные» as a new submission of the failed items,
  `order-bulk-actions-api.ts`), though `orders.md` §2.10's "Built today" paragraph still
  says nothing renders the outcome list. Not built: the action, any call from `ordering`
  into assignment, and the bar's button. Two findings that
  shape this record, each from reading `ManualDispatchService` and
  `JdbcAssignmentStore`: `max_concurrent_assignments` is read only to build
  `FleetCandidate` (`grep -a` finds it at no compare-and-set), so a manual assignment
  past a courier's ceiling succeeds today; and the single `assign` opens its attempt
  under a random idempotency key and checks no plan status of its own.
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0014, ADR 0025, ADR 0027, ADR 0029, ADR 0031, ADR 0039, ADR 0042,
  ADR 0045, ADR 0105, ADR 0142, ADR 0144
- Supersedes / Superseded by: — (amends ADR 0039, which is not edited: it adds the one
  member its "Bulk actions" section names and its `BulkActionType` Javadoc leaves out,
  `ASSIGN_COURIER`. ADR 0039's closed set, its 200-order cap, its one-transaction-per-item
  rule and its rejected all-or-nothing alternative all stand. ADR 0142 is not edited and
  nothing in it is reopened: rules decide automated sourcing at plan creation, and this
  record says only how a person's assignment relates to them.)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with that
  person and the work they block is marked.
  - **Where the action lives** (platform owner). Proposed default: `ASSIGN_COURIER` on
    ADR 0039's own endpoint and tables, called into `fulfillment` through a port, in the
    direction `ShipmentCancellationPort` already runs.
  - **Who may use it** (platform owner, operations). Proposed default: both
    `order.bulk-action` and `delivery.manual_assign`, checked together. A
    `COURIER_DISPATCHER` keeps the dispatch board's own per-plan loop and does not gain
    `order.bulk-action`, which would also let them cancel and advance orders in bulk.
  - **How many orders a courier may be given** (operations). Proposed default: no more
    than the courier type's `max_concurrent_assignments` counts, in the order the
    selection was sent; the rest answer `COURIER_AT_CAPACITY`. The single assignment is
    not changed by this record.
  - **Which courier states refuse an item** (operations). Proposed default: exactly the
    refusals `CourierDispatchGate` already names, except `OUTSIDE_DISTANCE_BAND`, which
    is recorded as a note and never refuses a person's choice.
  - **What a person's assignment does to a dispatch rule** (operations). Proposed
    default: it overrides it, visibly. The item records the rule the plan matched and a
    note when that rule excluded the fleet. It never edits, re-evaluates or reroutes.
  - **Bulk reassignment and bulk unassignment** (product). Proposed default: declined
    for now. An order a courier carries is refused (`ALREADY_ASSIGNED`).
  - **The reason code** (operations). Proposed default: a required code, the same
    `[A-Z0-9_]{1,48}` shape as the single assignment's, pre-filled by the console with
    `OPERATIONS_BULK_ASSIGN`.

**To accept as written:** say "accept 0172". Every open input above is then closed
on its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

**Row `1.1f` — "Bulk selection and bulk courier assignment" — is `PARTIAL`.** Selection,
`POST .../orders/bulk-actions` for `ADVANCE` and `CANCEL`, and the §2.10 result panel are
built and capability-gated; *"Bulk courier assignment remains explicitly unbuilt —
`BulkActionType` has no such member (ADR 0039's own scope) — the bar states this rather
than offering a button that would 400."* The spec (`orders.md` §2.10) lists it first:
«Назначить курьера» is valid "when every row is delivery, non-terminal, and the courier
is on shift", needs no confirmation because "it is reversible", and the matrix scores it
"Match, and beat": "Delever ships select-and-save; ADR 0039 adds per-item idempotency
and a partial-failure result panel". The parity matrix records Delever's version: on the
Orders page, select several orders, press «Назначить курьера», pick one courier, and all
selected orders are attached to that courier in one action.

**ADR 0039 promised this and then could not build it.** Its "Bulk actions" section says
"Bulk courier assignment is the first supported action" and V0029 repeats it, while
deferring the build because assignment "needs ADR 0014, which does not exist". ADR 0014
exists now, and so does the single assignment, so the reason for the omission is gone and
the enum, the CHECK and the doc comment that says "refused rather than half-supported"
(for `COMPLETE`, `REJECT` and `AMEND`) are the only places that still say no.

**What the single assignment does, read in the code.** `ManualDispatchService.assign`
takes a plan id, a courier id, the plan version the caller last read, a reason code and an
actor. It refuses a stale version (`STALE_VERSION`); refuses a courier whose live
engagement is not `dispatchable` (`COURIER_NOT_ELIGIBLE`); opens an
`assignment_attempts` row with `source_type INTERNAL`, status `REQUESTED` and a random
idempotency key, so "an operator's click is authoritative the instant it lands", the
courier app's accept flow being a different build; refuses a plan somebody already has
(`ALREADY_BEING_SOURCED` when `open` finds a live attempt, `ALREADY_ASSIGNED` when
`JdbcAssignmentStore.win` finds a shipment); moves the plan to `ASSIGNED`; records the
`fulfillment.dispatch.assign` audit fact; and publishes a `DISPATCH_BOARD` signal. Three
things it does not do, each of which a hundred-order version would turn from a rare edge
into a routine one.

1. *It does not look at the courier's load.* `max_concurrent_assignments` (1 to 10,
   default 1, V0040) reaches `FleetCandidate` and nothing else. ADR 0042 says "the ceiling
   is a conditional update — count-then-insert races two dispatchers into a third
   order"; no conditional update exists. One person assigning eight orders to a courier
   whose type allows three succeeds eight times.
2. *It does not name its attempt.* The idempotency key is `UUID.randomUUID()`. A bulk
   item that crashed between `open` and `win` and is retried by "Повторить проблемные"
   would open a second attempt, which is exactly what ADR 0039's per-item key
   `{bulkKey}:{orderId}` exists to prevent.
3. *It checks no plan status of its own.* A `CANCELLED` or `COMPLETED` plan is protected
   only by the attempt and winner compare-and-set, and its status move is a conditional
   update from whatever the plan's status was. A test must show what the single path does
   to such a plan; whatever it does, a bulk must not rely on it.

**What dispatch rules do and do not decide.** ADR 0142's rules are evaluated once, when a
plan is created, and the decision (rule id, mode, ladder, pinned document version) is
stored on `fulfillment.delivery_plans`. They govern the automated sourcing ticks. They
say nothing about a person: ADR 0014 says `MANUAL_ACTION_REQUIRED` exists for exactly
the order a human must place, and the single assignment ignores the rule today. A bulk
inherits the same relation, so the question is only what to record, and what to refuse.
Two plan states are not the rule's to override: a partner attempt that is `UNCERTAIN`
(ADR 0014: an unreconciled booking stops further booking, because two couriers on one
order is the failure its single-winner rule exists to prevent), and a plan a courier or
partner already carries.

**What the board already knows.** An order row's `actions[]` carries `ASSIGN_COURIER` when
the order is a delivery, in a state where a plan can exist, the plan has no in-house
courier (`ActiveCourierAssignmentsPort`, `fulfillment`'s answer to ordering's board
read) and the caller holds `delivery.manual_assign`. The selection bar already offers an
action only when it is valid for every selected row, reading `actions[]` and never
inventing its own rule (`order-queue-selection.ts`). That is the whole of the client's
predicate for this action.

## Decision

**`ASSIGN_COURIER` becomes the third member of ADR 0039's `BulkActionType`: N independent
assignments of selected delivery orders to one named courier, each in its own
transaction, each with a per-item outcome, each under a derived idempotency key,
requiring both `order.bulk-action` and `delivery.manual_assign`. A person's assignment
overrides dispatch rules and is recorded as having done so, and nothing in it ever
creates a run.**

1. **The request.** `BulkActionRequest` gains `courierId`, required exactly when
   `actionType` is `ASSIGN_COURIER` and refused otherwise, and the existing `reasonCode`
   becomes required for it (`[A-Z0-9_]{1,48}`, as the single assignment's). Each item is
   an order id and the order version the operator saw (`expectedVersion`), as for
   `ADVANCE` and `CANCEL`. The cap of 200 stands; the working ceiling is the courier's.
   One branch per request, as the endpoint's path is: the selection column is withheld on
   «Все филиалы» (ADR 0144), and an order of another branch answers
   `ORDER_NOT_FOUND_AT_LOCATION`.
2. **The call into `fulfillment`.** `ordering` calls a new
   `fulfillment.api.CourierAssignmentPort.assignForOrder(tenantId, brandId, locationId,
   orderId, courierId, reasonCode, attemptKey, bulkOperationId, actor)`, the direction
   `ShipmentCancellationPort` already takes (`ordering` depends on `fulfillment.api`,
   never the reverse). It resolves the order's plan (`JdbcDeliveryPlanStore.findByOrder`)
   and returns an `Outcome` carrying the plan id, the shipment id, a problem code or
   `null`, and zero or more notes. It is implemented in `fulfillment` over the existing
   assignment primitives, so the single and the bulk path share `JdbcAssignmentStore.open`
   and `win` and the one single-winner guarantee.
3. **Each item is decided in this order, and the first refusal is its answer.**
   `ORDER_NOT_FOUND_AT_LOCATION` (existing); `STALE_VERSION` (the order moved since the
   operator looked); `NOT_A_DELIVERY_ORDER`; `NO_DELIVERY_PLAN` (the plan opens on
   `OrderConfirmed`, so an unconfirmed order has none); `PLAN_NOT_OPEN` (`COMPLETED` or
   `CANCELLED`); `ALREADY_ASSIGNED_TO_THIS_COURIER` (a distinct, harmless code so a
   re-selection reads as it is); `ALREADY_ASSIGNED` (carried by anyone else, a courier or
   a partner); `ALREADY_BEING_SOURCED` (a live automated attempt: the bulk never
   pre-empts a sourcing tick); `PARTNER_ATTEMPT_UNRECONCILED` (an uncertain partner
   attempt on the plan); `COURIER_NOT_ELIGIBLE` (Decision 5); `COURIER_AT_CAPACITY`
   (Decision 4); then the assignment itself. A race lost at `open` or `win` is reported
   as `ALREADY_BEING_SOURCED` or `ALREADY_ASSIGNED`, never as an error. Every other
   failure is `VALIDATION_FAILED` or `UNEXPECTED_FAILURE`, the existing codes.
4. **The courier's ceiling is honoured, in submission order, and is the one new refusal
   the single path does not have.** Within the item's transaction, `fulfillment` locks the
   courier's row (`FOR UPDATE`), counts the courier's shipments not `DELIVERED` or
   `CANCELLED`, and refuses with `COURIER_AT_CAPACITY` when the count has reached the
   courier type's `max_concurrent_assignments`. Two bulks aimed at one courier serialise
   on that lock, and replaying a bulk answers identically because the count is of
   committed state and the order of items is the order sent. **This record does not change
   the single assignment**: an operator assigning one order at a time can still exceed a
   ceiling, as today, because that is a person acting on one order with the board in
   front of them; an act applied to a hundred is the one that needs a guard, the same
   reasoning `ORDER_BULK_ACTION` gives. Extending the ceiling to the single path and to
   automated `win` is the follow-up named under Alternatives, not part of this change.
5. **Eligibility is the platform's one definition, asked of `courier` through a port.**
   `fulfillment` declares `CourierAssessmentPort.assess(tenantId, brandId, locationId,
   courierId, distanceMeters)`, implemented in `courier` beside `InternalFleetAdapter` over
   `CourierDispatchGate` and the courier type, answering whether the courier is eligible,
   the refusals the gate names, and the type's ceiling. `COURIER_NOT_ELIGIBLE` is
   returned, with the named refusals in the item's detail, for `COURIER_NOT_ACTIVE`,
   `REGISTRATION_LAPSED`, `ENGAGEMENT_NOT_ACTIVE`, `DUTY_STATE_*` and, where the branch
   enforces shifts, `NO_OPEN_SHIFT`, exactly as the gate's own `eligible` says; under an
   advisory or off enforcement a missing shift is a note, not a refusal.
   `OUTSIDE_DISTANCE_BAND` is a note and never a refusal: the band is a ranking input for
   the automated lane, and a person who sends a scooter across town has decided to.
   `fulfillment` does not acquire a dependency on `courier`; it owns the port.
6. **A person's assignment overrides a dispatch rule, and says so.** The item does not
   evaluate, edit or re-run `fulfillment.dispatch_rules`. It reads the plan's stored
   decision (the matched rule id and mode, `delivery_plans.dispatch_decision`), records
   the rule id in the item and the audit fact, and adds the note `OVERRODE_DISPATCH_RULE`
   when the stored mode never asks the fleet (`PARTNER_ONLY`) or asks a partner first
   (`PARTNER_FIRST`). `MANUAL` and the fleet-first modes carry no note: assigning them
   is what the plan was waiting for. Nothing here can book a partner; assignment is
   in-house only, as the single path is.
7. **A bulk never makes a run.** The attempt is `DIRECT` for pay (ADR 0170), carries no
   `run_key`, and reads no other order's drop-off. An operator who puts three orders on
   one courier has paid three full cards, by design: the bias that creates a run is the
   automated lane's, behind a published rule, a decided pay mode and a named decrypt
   purpose.
8. **Idempotency is per item and survives a crash.** Each attempt is opened under the key
   `bulk:{first 16 hex of sha256(bulkKey)}:{orderId}` (`uq_attempt_idempotency` is
   `varchar(160)`, and a client's `Idempotency-Key` may be 255), so a retried `PENDING`
   item reaches the same attempt rather than opening a second. A replay of the whole bulk
   under the same key changes nothing and returns the recorded outcome, applied and
   failed alike, as for the other two actions.
9. **The audit is one fact per item plus the assignment's own.** The existing per-item
   fact (`ordering.order.bulk-action-item.assign_courier`, `BUSINESS`, correlated by the
   bulk id) is written for every item, applied or failed, and `fulfillment.dispatch.assign`
   is written for each assignment that happened, its change document carrying the bulk
   id, the courier, the rule and the notes. The summary fact
   `ordering.order.bulk-action.assign_courier` carries the counts. A bulk never collapses
   into one fact that loses which orders were touched (ADR 0027).
10. **The console completes row `1.1f` by extending what is built.**
    `q-order-queue-bulk-bar` offers «Назначить курьера» exactly when every selected row's
    `actions[]` contains `ASSIGN_COURIER` and the session holds `order.bulk-action` and
    `delivery.manual_assign` at the location (a prediction; the server decides), opens a
    courier picker that shows each courier's duty state, current load and ceiling (the
    roster the dispatch board already fetches) with the reason defaulted, and sends one
    request. `q-order-queue-bulk-result` renders the outcome it already renders for the other
    two actions: «N назначено · M проблем», the problems with their codes translated in ru,
    uz-latn and en, the notes beside the applied rows, and «Повторить проблемные» sending
    only the failed items as a new submission (the same key would replay the failures, by
    ADR 0039's own design). The dispatch board keeps its own per-plan loop in this record.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave `BulkActionType` closed and keep the board's client-side loop as the only bulk | Costs nothing, and the dispatch board already works. But the orders board has no bulk assignment at all, the loop has no idempotency, no per-item audit and no ceiling, and ADR 0039 promised the audited version | The orders board stops being where operators select orders to dispatch |
| A sibling endpoint on `DispatchController` under a new `delivery.bulk_assign`, with its own result tables | The right door for a dispatcher who does not hold `order.bulk-action`, and the capability would be the declared one. But it duplicates the idempotency table, the replay logic, the audit shape and the result panel ADR 0039 built, and `ordering.bulk_operations` cannot be reused from `fulfillment` without a module cycle | A person who should bulk-assign but not bulk-cancel exists in practice (a dispatcher role), and the cost of a second table is accepted |
| Give `COURIER_DISPATCHER` `order.bulk-action` | The dispatcher could then cancel and advance orders in bulk; ADVANCE and CANCEL items call single-order services that do not re-check capabilities, so the role bundling is the whole control | Never; the capability is deliberately coarse |
| One request, one transaction, all or nothing | ADR 0039 rejected it for a lock convoy at the peak that caused the bulk, and for one cancelled order failing the other nine | Never |
| Enforce the courier ceiling in `JdbcAssignmentStore.win` for every path | The honest place for ADR 0042's "conditional update", and it would protect the automated lane's race too. But it adds a refusal to an automated path that already treats a lost `win` as a normal answer, and to the single assignment, which dispatchers rely on to overload in a crisis | The automated lane is seen to exceed ceilings in production, or a dispatcher override with a reason and an audit is designed |
| Refuse `OUTSIDE_DISTANCE_BAND` | Consistent with the gate. A person sending a courier across town is a decision the platform has no better information about than the dispatcher, and the single path already allows it | A courier type's band becomes a safety or legal limit |
| Reassign: move carried orders from one courier to another in the same bulk | Two acts (unassign, assign) with a window in which food has no carrier, and an unassign is refused once the courier has picked up. A courier already holding the bag is not reassigned from under them | A dispatcher asks for it and unassign-then-assign is made one atomic act |
| Evaluate the dispatch rules again and refuse an item the rule would not allow | Makes a person's assignment obey a rule meant for the automated lane, and the one case it hurts is the one the human override is for: a plan the rules could not place | A tenant asks for a rule that binds people, which would be an approval policy (ADR 0027), not a dispatch rule |
| Let a bulk create a run so a single courier is paid by ADR 0170's increment | Would need to decrypt other orders' drop-offs for an operator action and pay a courier less because a dispatcher clicked once. Pay follows the automated lane's stamped offer | Dispatchers want grouped pay and a tenant decides it applies to manual runs |

## Consequences

### Positive

- Row `1.1f` finishes: the orders board assigns a selection to one courier in one audited
  act with the result panel it already has, as the spec promised and ADR 0039 named first.
- The same machinery, the same idempotency table, the same replay rule and the same cap
  carry a third action; nothing about ADVANCE or CANCEL changes.
- A bulk cannot overload a courier past the type's ceiling, and cannot open two attempts
  for one order after a crash.
- A person's choice is respected and visible: the rule it overrode is on the item and in the
  audit, so "why did this go to a courier when the rule said Yandex" is a lookup.
- The two findings about the single path are written down and testable.

### Negative

- `ordering` gains a call into `fulfillment` for a mutation, not a read. Every other
  `ordering → fulfillment.api` call is after the order's own change has committed; this
  one is the whole act, so it has to be the item's own transaction and its failures have
  to map to problem codes without leaking an exception message.
- The ceiling guard exists on the bulk path and not on the single one, so the platform
  has two answers to "may I give this courier a fourth order", and the dispatch board's
  loop can still produce the fourth. The inconsistency is named, not hidden.
- A dispatcher who is not a location manager cannot use the new bar, and the board's loop
  stays unaudited as a bulk. The record picks the smaller authorisation change over the
  better door.
- `bulk_operations` and its items gain nullable columns that two of the three actions
  never write.
- A hundred `DISPATCH_BOARD` signals (bounded by the ceiling, in practice a handful) and a
  roster read per item for the gate; the courier's row lock is held for the length of one
  item's transaction.

### Accepted trade-offs

- A courier assigned an order before the kitchen is ready sits with an unready order until
  the courier-side `kitchenReadyOnly` switch (row `3.9`) or the dispatcher's own judgement
  decides; the bulk does not gate on readiness, as the single assignment does not.
- Re-running only the failed items needs a new key, so a partially applied bulk is
  replayed as a record and finished as a new act.
- No bulk unassign. Taking back a mistaken selection is one plan at a time.

## Specification

### Physical model

```text
ordering.bulk_operations        (columns added; V0193's table)
  courier_id  uuid NULL,  reason_code varchar(48) NULL
  CHECK ((action_type = 'ASSIGN_COURIER') = (courier_id IS NOT NULL))
  ck_bulk_operation_action_type replaced (drop and re-add; V0193 is applied) to admit 'ASSIGN_COURIER'
  FK (courier_id, tenant_id) -> fulfillment.couriers (id, tenant_id)
ordering.bulk_operation_items   (columns added)
  plan_id uuid NULL, shipment_id uuid NULL,   -- references, no FK: fulfillment rows are cancelled, never deleted
  item_note varchar(64) NULL                  -- a non-fatal annotation, e.g. OVERRODE_DISPATCH_RULE, OUTSIDE_DISTANCE_BAND
```

`ck_bulk_item_applied_version` is unchanged: an assignment does not move the order's own
version, so an applied item records the version the order already had. Every row already
carries `tenant_id`; the existing grants (`SELECT, INSERT, UPDATE`) cover the new columns.
`ordering` stores no courier name, no address, no phone. No change in `fulfillment`'s
tables; the attempt's `uq_attempt_idempotency` and `ux_attempt_one_offered` are what the
item relies on. The next free migration number is taken across every active worktree, per
`AGENTS.md`.

### Ports

```text
fulfillment.api.CourierAssignmentPort        assignForOrder(...) -> Outcome(planId?, shipmentId?, problemCode?, notes[])
                                             implemented in fulfillment; called by ordering after nothing, as the whole act
fulfillment.api.CourierAssessmentPort        assess(tenant, brand, location, courier, distanceMeters)
                                             -> Assessment(eligible, refusals[], concurrencyCeiling)   implemented in courier
```

`ManualDispatchService.assign` gains an overload taking the attempt's idempotency key and a
correlation id; the single endpoint keeps passing a random key and the plan id. A shared
`PlanAssignability` guard (the status set `PLANNED`, `WAITING_TO_SOURCE`, `SCHEDULED`,
`RETRY_PENDING`, `MANUAL_ACTION_REQUIRED` as open; `ASSIGNED` and `IN_PROGRESS` as carried;
`SOURCING` and `BOOKING` as being sourced; `COMPLETED` and `CANCELLED` as not open) is
used by the bulk path and, in the same change, by the single path once a test has shown
what that path does to a terminal plan.

### API (ADR 0031)

```text
POST /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/bulk-actions
     Idempotency-Key required; 202 with bulkOperationId, counts, replayed, items[]
     body: actionType ASSIGN_COURIER, courierId, reasonCode, orders[{orderId, expectedVersion}] (1 to 200)
     capability: order.bulk-action (declared) and delivery.manual_assign (checked in the controller, the
     pattern the kitchen board uses for its override); a missing second grant answers 403 for the request
item: { orderId, itemStatus: APPLIED | FAILED, problemCode, resultingOrderVersion, planId?, shipmentId?, note? }
```

Problem codes (stable, never free text): `STALE_VERSION`, `ORDER_NOT_FOUND_AT_LOCATION`,
`NOT_A_DELIVERY_ORDER`, `NO_DELIVERY_PLAN`, `PLAN_NOT_OPEN`, `ALREADY_ASSIGNED`,
`ALREADY_ASSIGNED_TO_THIS_COURIER`, `ALREADY_BEING_SOURCED`, `PARTNER_ATTEMPT_UNRECONCILED`,
`COURIER_NOT_ELIGIBLE`, `COURIER_AT_CAPACITY`, `VALIDATION_FAILED`, `UNEXPECTED_FAILURE`.
Request-level refusals are `VALIDATION_FAILED` (a missing courier or reason, a courier from
another tenant) before any item runs. `make openapi-baseline` regenerates the five documents;
the path already belongs to a surface group.

### Events, audit, PII, observability

- **Events (ADR 0032):** none new. The assignment's consequences are local state read by the
  boards; `DISPATCH_BOARD` (ADR 0045) is signalled per item as the single path does.
- **Audit (ADR 0027):** Decision 9. The reason is the request's `reasonCode`, never prose.
- **PII (ADR 0029):** none stored or returned. The request and the response carry order and
  courier ids and codes. The gate and the assignment never decrypt an address or a name; the
  plan's non-PII label is not even read.
- **Observability:** `ordering.bulk.item` counters by `action` and `outcome` (the problem
  code), bounded labels only; the bulk's duration and size as histograms; no courier or
  tenant label.

### Testing

- **Items:** each problem code reached by a fixture and each seen failing first; an order
  from another location answers `ORDER_NOT_FOUND_AT_LOCATION`; a stale version refuses;
  a confirmed pickup order answers `NOT_A_DELIVERY_ORDER`; a plan carried by this courier
  answers the harmless code and by another, `ALREADY_ASSIGNED`; a live automated attempt
  answers `ALREADY_BEING_SOURCED` and the bulk never pre-empts it; an uncertain partner
  attempt answers `PARTNER_ATTEMPT_UNRECONCILED` and opens nothing.
- **Ceiling:** eight orders to a courier whose type allows three apply three, in submission
  order, and refuse five; two simultaneous bulks to one courier together never exceed the
  ceiling; replaying the bulk answers identically; the single assignment is unchanged.
- **Idempotency:** a crash between `open` and `win` followed by a retry under the same bulk
  key reaches the same attempt; a replay of the whole bulk changes nothing; two bulks with
  distinct keys over one order produce one winner.
- **Eligibility:** a suspended or lapsed courier refuses every item; an enforced shift with
  no open shift refuses and an advisory one notes; `OUTSIDE_DISTANCE_BAND` notes and
  applies.
- **Rules:** a plan whose stored rule said `PARTNER_ONLY` is assigned with the note and the
  rule id in the audit fact; the rule document is untouched; a bulk creates no `run_key`
  and the attempt is `DIRECT`.
- **Authorisation:** `order.bulk-action` without `delivery.manual_assign`, and the reverse,
  are both refused; a `COURIER_DISPATCHER` is refused; cross-tenant and cross-location
  fixtures fail; the capability declaration is asserted.
- **Console:** the bar offers the action only when every row's `actions[]` has
  `ASSIGN_COURIER`, and says why otherwise; the result panel shows each code translated in
  three locales; «Повторить проблемные» sends only the failures under a new key.

## Rollout and rollback

Add the columns and widen the CHECK first (inert). Then the two ports and the
`ASSIGN_COURIER` path behind the existing endpoint with the action refused by default
(`horecaos.ordering.bulk.assign-courier.enabled`, false), exercised by the integration tests
against a real database, and by one pilot branch with the property on. Then the console bar
and the result panel's assignment strings. Rollback is the property off: the endpoint
answers `VALIDATION_FAILED` for the action again, the recorded operations stay readable, and
no assignment already made is undone (unassign remains the single, per-plan act).

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Migration: the CHECK, the two columns on `bulk_operations`, the three on
      `bulk_operation_items`.
- [ ] `BulkActionType.ASSIGN_COURIER`; `OrderBulkActionService` branch, request validation,
      per-item audit and summary fact; `OperationsOrderController` request fields and the
      second-capability check.
- [ ] `fulfillment.api.CourierAssignmentPort` and its implementation over the assignment
      store; `ManualDispatchService.assign`'s overload (attempt key, correlation id);
      `PlanAssignability`; the courier-row lock and count.
- [ ] `fulfillment.api.CourierAssessmentPort` and `courier`'s implementation over
      `CourierDispatchGate` and the courier type.
- [ ] A test that shows what the single assignment does to a `CANCELLED` and a `COMPLETED`
      plan, and the guard that follows from it.
- [ ] `q-order-queue-bulk-bar` action and courier picker, `q-order-queue-bulk-result`
      strings for the new codes and notes (ru / uz-latn / en), «Повторить проблемные» for
      this action; the bar's "states this rather than offering a button that would 400"
      text removed.
- [ ] `make openapi-baseline`; `orders.md` §2.10 and the capabilities table updated by the
      change that builds this.
- [ ] Update ADR 0039's status line to say bulk courier assignment is built.
- [ ] Tests listed under Testing, each seen failing first.

## Exit criteria

On the orders board, a location manager selects six confirmed delivery orders and presses
«Назначить курьера», picks a courier whose type carries four, and sends. The result panel
reads four assigned and two problems, the two named `COURIER_AT_CAPACITY` in their own
language, one of the four noted as having overridden the dispatch rule that preferred a
partner; each of the four has a shipment on that courier and a `fulfillment.dispatch.assign`
audit fact naming the bulk, the reason and the rule. Pressing «Повторить проблемные» after
another courier is picked sends only the two. Sending the whole request again under the
same key changes nothing. A dispatcher without `order.bulk-action` is refused, and still
assigns one plan at a time from the board. Gap-map row `1.1f` can be marked `BUILT`.

## References

- ADR 0014, ADR 0025, ADR 0027, ADR 0029, ADR 0031, ADR 0039 ("Bulk actions",
  `BulkActionType`), ADR 0042 (the ceiling, the dispatch gate), ADR 0045 (`DISPATCH_BOARD`),
  ADR 0105 (`actions[]`), ADR 0142 (rules, the stored decision), ADR 0170 (a person's
  assignment is `DIRECT`)
- `platform/docs/operations-gap-map.md` rows `1.1e`, `1.1f`, `3.1`, `3.8`, `3.9`
- `platform/docs/operations-spec/orders.md` §2.10 and its capability and open-work tables
- `platform/docs/delever-parity-matrix.md` ("Bulk courier assignment")
- `V0029`, `V0040`, `V0054`, `V0193`; `OrderBulkActionService`, `BulkActionType`,
  `JdbcBulkOperationStore`, `OperationsOrderController`, `OrderActionsPolicy`,
  `ActiveCourierAssignmentsPort`; `ManualDispatchService`, `DispatchController`,
  `JdbcAssignmentStore`, `JdbcDeliveryPlanStore`, `JdbcCourierEligibilityStore`,
  `ShipmentCancellationPort`; `CourierDispatchGate`, `InternalFleetAdapter`;
  `frontend/operations/src/app/features/orders/order-queue-selection.ts`,
  `.../delivery/dispatch-board-page.ts`
