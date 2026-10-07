# ADR 0168: A paid-only hold in the kitchen buffer

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the buffer holds tickets for time and for a
  person, never for money. Built: `kitchen.tickets` with `release_mode`
  (`AUTO_ON_CONFIRM`, `SCHEDULED`, `MANUAL_HOLD`, V0030), `KitchenTicketService.decideRelease`
  and `releaseNow`, `KitchenReleaseWorker` (a five-second sweep, `FOR UPDATE SKIP LOCKED`),
  `POST .../kitchen/tickets/{id}/release` and `PUT .../release-schedule`
  (`kitchen.ticket.release`, with `kitchen.ticket.release.override` and a reason to
  push a fire time past the promise), and the buffer screen
  (`buffer-page.ts`, row `2.2`), whose own comment says paid-only hold "is therefore
  not modelled — every held ticket in the buffer is shown, and release is offered
  unconditionally". `OrderForKitchen` carries no payment field, and nothing in
  `kitchen` reads `ordering.orders.payment_status_projection`. The payment facts
  exist elsewhere: `payments.payment_methods` (V0042, `responsibility`),
  `CaptureTiming` (`BEFORE_CONFIRMATION` or `ON_HANDOVER`, code-owned on
  `PaymentMethod`), `PaymentIntentPort.paymentRequiredBeforeConfirmation` and
  `.takesMoneyBeforeHandover`, the projection (`NOT_REQUIRED`, `PENDING`,
  `AUTHORIZED`, `CAPTURED`, `FAILED`, `VOIDED`, `REFUNDED`, V0022, mirrored by
  `PaymentProjectionTrigger`), and three private copies of "money is held"
  (`JdbcDeliveryOrderPort`, `JdbcCourierJobStore`, `JdbcDeliveryCompletionAdapter`,
  each `AUTHORIZED` or `CAPTURED`).
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0013, ADR 0019, ADR 0025, ADR 0027, ADR 0030, ADR 0031, ADR 0039,
  ADR 0041, ADR 0046, ADR 0153
- Supersedes / Superseded by: — (adds a hold reason to ADR 0041's buffer without
  reopening its three release modes, its promise rule or its override capability;
  reopens exactly one line of ADR 0041's IA-sourced scope, the "paid-only hold" in
  IA §2.2's "Owns", which no record ever decided)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with that
  person and the work they block is marked.
  - **Which payment fact gates the hold** (product, the row's own input). Proposed
    default: a two-part fact, both parts ADR 0013's and already in code. *Timing:* the
    order's payment method takes money before the kitchen (`CaptureTiming
    .BEFORE_CONFIRMATION`, the only value that gates; any other timing, present or
    future, does not). *State:* the order's `payment_status_projection` is `PENDING`
    or `FAILED`, released at `AUTHORIZED` or `CAPTURED`.
  - **What a pay-at-handover order does** (product). Proposed default: never held by
    this rule. There is no moment before handover at which its money is expected, and
    holding it would hold every cash order for ever.
  - **Whether the hold is on by default** (platform owner). Proposed default: on,
    `HOLD_UNTIL_PAID`, with an off switch per scope. It changes behaviour only for an
    order whose payment became required after the order was confirmed, which is one
    path today (Context).
  - **How long a blocked ticket may sit before the board treats it as stuck**
    (operations). Proposed default: ten minutes past its fire instant, a tenant
    setting in the same policy.
  - **The reasons a person may fire a payment-held ticket anyway** (operations).
    Proposed default: a closed registry, `PAYMENT_PROMISED_AT_COUNTER`,
    `CUSTOMER_KNOWN_TO_BRANCH`, `MANAGER_DECISION`, with no free text.
  - **Whether a ticket that has already fired is pulled back** (platform owner).
    Proposed default: never; ADR 0041 has no recall to a held state, and food already
    started cannot be unstarted.
  - **Delever's other half**, "hide unpaid orders from the courier app" (product).
    Proposed default: out of scope; the courier-side switches are row `3.9`'s
    `kitchenReadyOnly` and its siblings in `courier.compensation`.

**To accept as written:** say "accept 0168". Every open input above is then closed
on its proposed default.

## Context

**Row `2.2` — "Buffer — held tickets and the kitchen fire time" — is `PARTIAL`.** What
is built is the buffer list, the fire time as its own field, manual release, and
hold and fire-time edits. What is not: *"Paid-only hold is still deliberately
unmodelled — every held ticket is listed and release offered unconditionally."* Its
"Blocked by" says: *"Which payment fact gates a paid-only hold is unspecified —
orders.md names no rule and ADR 0013's payment-method registry is itself partial; a
product decision, not code."* The information architecture's §2.2 gives the buffer
four things to own: "Буфер; the kitchen fire time as a field distinct from created-at
and promised-at; **paid-only hold**; manual release". The parity matrix describes
Delever's version as a toggle that "hides unpaid orders from both the courier app
and" the kitchen.

**The row is smaller than it reads, and the reason is the thing to decide on.** The
platform already refuses to put an unpaid order in front of a kitchen, at the one
place that is cheap to refuse it. ADR 0013's `CaptureTiming` says it in its own
Javadoc: money from a provider "must have credited the order before the restaurant is
asked to accept it. Anything else lets a kitchen start cooking against a payment that
may never arrive." `CheckoutOrderWriter` sets the projection to `PENDING` when the
method needs it (`paymentProjection`), `CheckoutService` then holds the order in
`PAYMENT_AUTHORIZING` rather than `CONFIRMED`, and `PaymentCaptureConfirmationTrigger`
confirms it on `PaymentCaptured`, in the same transaction in which
`PaymentProjectionTrigger` writes `CAPTURED`. A kitchen ticket is opened only on
`OrderConfirmed`, so for a storefront, operator-keyed or bot checkout (all three go through
`CheckoutService`) the ticket of an online-paid order is paid by construction. A marketplace order arrives
already paid (`JdbcMarketplaceOrderIntake` writes `NOT_REQUIRED` because the
aggregator collected it, tender `MARKETPLACE`). A cash or dine-in order is
`NOT_REQUIRED` and confirmed first on purpose.

**What remains is one path.** An order's payment becomes required *after* it was
confirmed in exactly one place in `src/main`: ADR 0039's `CHANGE_PAYMENT_METHOD`
amendment, `OrderAmendmentService.applyPaymentMethodChange`, which is allowed only
from `NOT_REQUIRED` (cash) to another method (`PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND`
refuses anything else), opens a provider intent, and patches the projection to
`PENDING` when `paymentRequiredBeforeConfirmation` is true. The order stays
`CONFIRMED` or `PREPARING`; its ticket exists; and if the ticket is a pre-order still
waiting in the buffer for its `release_at`, it will fire on time against money that
has not arrived. That is the whole of what a paid-only hold must cover today, and it
is a real defect with a person in the loop (an operator chose the switch).

**What a naive reading of the row would break.**

1. *"Hold while the order is not paid"* holds every cash order for ever, because a
   cash order is `NOT_REQUIRED` and is paid at handover by design. The rule has to
   say which unpaid orders it means.
2. *"Hold while the projection is not `CAPTURED`"* has the same defect, and a second:
   ADR 0153 chooses, as its proposed default, to take a weighed order's money
   **after** the weighing, by an online method (its option C, "confirmed without
   payment, as cash is"). That order will be confirmed with an unpaid online
   intent and must not be held. A gate keyed on the *timing* the method declares, and
   not on "unpaid", is what lets that record land without reopening this one.
3. *"Hold at ticket open"* is a race and, as shown above, pointless: at `OrderConfirmed`
   a legitimately unpaid online order does not exist, and a just-captured order can be
   seen as `PENDING` by a `BEFORE_COMMIT` listener that runs before
   `PaymentProjectionTrigger` does. A hold decided there would delay every paid order
   by a sweep tick. Level-triggered reads after commit do not have the race.
4. *Three private definitions of "money is held"* (`AUTHORIZED` or `CAPTURED`) already
   live in dispatch and courier code. A fourth, in `kitchen`, would be the one that
   drifts. `AUTHORIZED` is read as paid by all three, yet no listener writes it today
   (`PaymentProjectionTrigger` mirrors `CAPTURED`, `FAILED`, `VOIDED`, `REFUNDED`);
   the value is permitted and unwritten, which is a reason to define it once.

## Decision

**A ticket in the buffer is held for payment while its order's payment is required
before the kitchen and has not arrived. The hold is placed by the amendment that made
payment required and by a level-triggered sweep, cleared by the capture or the
sweep, and overridden only by a named person with a coded reason. A ticket that has
already fired is never recalled, and a pay-at-handover order is never held.**

1. **The gating fact is the pair ADR 0013 already defines.** *Required:* the order's
   method has `CaptureTiming.BEFORE_CONFIRMATION`, asked once, when the method is
   chosen or changed, through `PaymentIntentPort.paymentRequiredBeforeConfirmation`,
   whose answer the projection already records as `PENDING` (and not as
   `NOT_REQUIRED`). *Not arrived:* `payment_status_projection` is `PENDING` or
   `FAILED` (a failed attempt leaves the money required and absent; the customer may
   retry). `AUTHORIZED` and `CAPTURED` mean the money is held and release the ticket.
   `NOT_REQUIRED` never holds, whatever else is true; `VOIDED` and `REFUNDED` mean
   money moved back, which is a remedy on a live order and not a reason to starve
   its kitchen. A capture timing that is not `BEFORE_CONFIRMATION` never gates, so a
   method added later gates nothing until a record says it should.
2. **One definition of "money is held", in `ordering.api`** (`PaymentStatusFacts`,
   two static predicates: `moneyIsHeld(status)` and `moneyRequiredAndAbsent(status)`).
   The kitchen uses it, and the three private copies are replaced by it in the same
   change, so the question has one answer.
3. **Placement.** A hold is placed in two ways and never at ticket open. (a) When an
   amendment is applied, `KitchenAmendmentListener` (which already receives every
   `OrderAmendmentApplied` before commit) asks the kitchen to reconcile the order's
   ticket, which reads the projection the same transaction has just patched. (b) A
   sweep, in `KitchenReleaseWorker`'s own tick, reconciles every `HELD` ticket whose
   order is `PENDING` or `FAILED` and carries no hold, so a path nobody anticipated is
   still caught and a missed event costs one tick. The ticket keeps its release mode
   and its `release_at`; it gains `hold_reason = PAYMENT`, the instant, and the
   pinned policy identity (ADR 0030).
4. **Release.** The hold clears (a) when kitchen receives `PaymentCaptured` for the
   order (the event is the proof; no read of the mirror is involved), (b) when the
   sweep reads `AUTHORIZED` or `CAPTURED`, (c) when the order goes terminal (the
   ticket voids by the path it already has), or (d) when the resolved policy for its
   branch is `OFF` (the kill switch). Clearing a hold does not fire the ticket by
   itself: it returns the ticket to its own schedule. `AUTO_ON_CONFIRM` and an
   overdue `SCHEDULED` ticket fire at once through `releaseNow`'s conditional update; a
   `SCHEDULED` ticket not yet due waits for its `release_at`; a `MANUAL_HOLD` ticket
   stays held for the person who held it.
5. **The release schedule, exactly.** `KitchenReleaseWorker`'s due-ticket query adds
   `AND hold_reason IS NULL`: a payment-held ticket never fires on its timer. When
   its `release_at` arrives unpaid, the ticket records `PAYMENT_HOLD_BLOCKED_RELEASE`
   once, raises the `KITCHEN_BOARD` realtime signal, and the buffer row says why. At
   the policy's `overdueAfterMinutes` past that instant it records
   `PAYMENT_HOLD_OVERDUE`, which the board and the stuck list show. **Nothing is
   cancelled and nothing is fired automatically**: the exits are the customer paying,
   an operator cancelling the order with a reason (ADR 0039), or the override below.
   `PUT .../release-schedule` (editing the fire time) still works and does not clear
   the hold. The ticket's `target_ready_at` and prep estimate are untouched, so a late
   payment makes the order late by the promise's own measure, visibly, and never
   quietly.
6. **Override.** `POST .../release` on a payment-held ticket answers `409 PAYMENT_HOLD`
   unless the caller holds `kitchen.ticket.release.override` and sends a reason code
   from the closed registry. It then fires, records `PAYMENT_HOLD_OVERRIDDEN` on the
   ticket and an ADR 0027 `BUSINESS` audit fact naming the person, the reason and
   the order. The override does not touch the payment: the order still shows unpaid.
7. **Pay-at-handover is never held**, whatever its method: cash, a terminal at the
   counter, and a future online method whose money is taken after weighing (ADR 0153).
   A tenant that does not want unconfirmed cash orders on the line has the lever
   it already has, `ordering.acceptance` with `RESTAURANT_APPROVAL`; this record adds
   no second one.
8. **A policy, `kitchen.paid_hold`,** settable at `TENANT`, `BRAND` and `LOCATION`,
   `{ "schema": 1, "mode": "HOLD_UNTIL_PAID" | "OFF", "overdueAfterMinutes": 10 }`,
   replace-not-merge, published whole. Its built-in default is `HOLD_UNTIL_PAID`
   with ten minutes. A ticket pins the policy id and version it was held under.
9. **A fired ticket is never recalled.** The hold applies to `HELD` tickets only. An
   order switched to an online method after its ticket fired shows its payment as
   required on the board and its handover is the operator's to manage; the platform
   does not unstart food.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave the row `PARTIAL` and decline the hold | Costs nothing, and the only path is rare. But it is a real defect (a pre-order cooks against a payment that may never arrive) with an operator in the loop, and the platform's own rule (ADR 0013) is exactly that a kitchen must not | The amendment `CHANGE_PAYMENT_METHOD` is removed or refused after confirmation |
| Hold any order whose projection is not paid | Holds every cash order and every dine-in tab for ever, and would hold ADR 0153's weighed orders when that record lands | Never; the gate is timing plus state |
| Decide the hold at ticket open from the projection | Races the projection listener and delays every paid order; also pointless, because no legitimately unpaid online order is confirmed | Another path confirms an online order before its money, and then the sweep already catches it |
| Read the settlement tenders (`payments.order_settlements`, `payments.tenders`) instead of the projection | Authoritative, and race-free inside the capture transaction (`CapturedMoneyPort.recordCapture` runs first). But the projection is the value every reader of "paid" already uses, a kitchen read of `payments` tables crosses a boundary the others avoid, and the sweep runs after commit, where the two agree | The projection and the tenders are seen to disagree for a settled order |
| A hold at the ticket level via `release_mode = PAYMENT_HOLD` | A fourth release mode that has to remember its schedule: the ticket must come back to `SCHEDULED` or `AUTO_ON_CONFIRM` when paid, which a separate hold reason keeps for free, and `ck_ticket_scheduled_has_instant` says a mode and an instant travel together | Never |
| Auto-fire when payment arrives late, auto-cancel when it never does | Firing is safe, and is what clearing does. Cancelling is not the kitchen's to decide and the order's own lifecycle (ADR 0019, ADR 0170) owns it | A tenant asks for a payment-hold timeout that cancels, and ADR 0170's window is in force |
| An unconditional platform rule with no off switch | Simplest. But a branch that serves regulars on trust will fight it, and the override is per ticket | Never as a reason to remove the policy |
| Hide unpaid orders from couriers too (Delever's second half) | A different reader and a different row (`3.9`): a courier sees an offer, not a ticket | The courier app's offer policy asks for a payment condition |

## Consequences

### Positive

- The kitchen stops being the one reader of an order that can cook against money
  that has not arrived, for the one path in the code where that can happen.
- Pay-at-handover, the weighed-order path ADR 0153 proposes, marketplace orders and
  dine-in are untouched by construction, and so is every paid online order's latency.
- "Money is held" gets one definition shared by kitchen, dispatch and courier code.
- The release schedule keeps its meaning: a hold changes whether the timer may fire,
  not when it is due, and a cleared hold returns the ticket to its own schedule.
- Everything is visible: the buffer row says why, the ticket's events say when, the
  override names a person and a code.

### Negative

- A pre-order for 20:00 whose customer is slow to pay by Click can reach 20:00 held.
  The kitchen then starts late by the minutes the customer took, and the order is late
  by the promise's own measure. That is the intended cost, and a person has to look.
- Two places place a hold (the amendment listener and the sweep), and a third clears
  it (the capture event), so a reconcile bug shows up as a ticket that will not fire.
  The sweep is the correction, and the board's overdue state is the alarm.
- A new `kitchen.tickets` column set and a five-value extension of the ticket-event
  CHECK on a table the release worker scans every five seconds.
- Replacing three private predicates touches dispatch and courier readers for a
  change in kitchen behaviour; each must be proved unchanged.
- `AUTHORIZED` is read as "held" although nothing writes it. If a provider's reserve
  step is ever mirrored as `AUTHORIZED`, the kitchen will start cooking against a
  reservation, which is what the three other readers already do and what ADR 0013's
  two-step methods intend (reserve, then capture) only if the reserve is firm.

### Accepted trade-offs

- A hold on a fired ticket does not exist, so an operator who switches a cooking order
  to Click gets no protection and a visible "unpaid" flag instead.
- The override reason is a code. A branch that wants a sentence writes it in the
  order's internal note (ADR 0113), which is where free text already lives.
- No cancellation timeout for a held ticket. A customer who never pays leaves an
  order in the buffer until a person closes it, as an unanswered approval would.

## Specification

### Physical model

```text
kitchen.tickets   (columns added; V0030's table, V0191's pattern for constraint extension)
  hold_reason               varchar(16)  NULL        -- NULL | 'PAYMENT'
  hold_since                timestamptz  NULL
  hold_policy_id            uuid         NULL, hold_policy_version integer NULL    -- ADR 0030 pinned identity
  hold_blocked_notified_at  timestamptz  NULL        -- the one PAYMENT_HOLD_BLOCKED_RELEASE per hold
  CHECK (hold_reason IS NULL OR hold_reason IN ('PAYMENT'))
  CHECK ((hold_reason IS NULL) = (hold_since IS NULL))
  CHECK (hold_reason IS NULL OR status IN ('HELD', 'VOIDED'))    -- a fired ticket carries no hold; VOIDED keeps what it had
  index (tenant_id, hold_since) WHERE hold_reason IS NOT NULL    -- the sweep's read

kitchen.ticket_events   ck_ticket_event_trigger replaced (drop and re-add; V0191, last extended by V0477) to add
  'PAYMENT_HOLD_PLACED', 'PAYMENT_HOLD_CLEARED', 'PAYMENT_HOLD_BLOCKED_RELEASE',
  'PAYMENT_HOLD_OVERDUE', 'PAYMENT_HOLD_OVERRIDDEN'
```

`tenant_id` is already on both tables and on every row; the existing grants cover the
new columns (`UPDATE` on tickets, `INSERT` on events). The due-ticket query in
`KitchenReleaseWorker`/`JdbcKitchenStore` gains `hold_reason IS NULL`. The policy
`kitchen.paid_hold` is an ADR 0030 policy (no table). The next free migration number
is taken across every active worktree, per `AGENTS.md`.

### Ports

`kitchen.application.port.KitchenPaymentSource` (new, read-only, in the shape of
`KitchenOrderSource`): `paymentStatus(tenantId, orderIds)` returning the projection per
order, implemented in `kitchen.infrastructure.ordering` over `ordering.orders` by a
plain SQL read, the idiom `JdbcCourierJobStore` uses. The ticket and the order are one
tenant by composite key. `ordering.api.PaymentStatusFacts` is a pure class.

### APIs (ADR 0031)

```text
POST /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen/tickets/{ticketId}/release
     unchanged path and capability; 409 PAYMENT_HOLD without an override;
     body gains optional overrideReasonCode (the closed registry); requires
     kitchen.ticket.release.override when the ticket is payment-held
GET  /api/v1/operations/tenants/{tenantId}/kitchen-paid-hold?brandId=&locationId=    ETag = document version
PUT  /api/v1/operations/tenants/{tenantId}/kitchen-paid-hold?brandId=&locationId=    If-Match, Idempotency-Key, whole document
```

`TicketResponse` gains `holdReason` and `holdSince` (additive; a client that ignores
them shows what it showed). `stream=buffer` is unchanged and lists payment-held tickets
with the new fields. A policy write needs `kitchen.paid-hold.manage` (new, ADR 0025,
bundled where `KITCHEN_STATION_MANAGE` is: `TENANT_OWNER`, `TENANT_ADMIN`,
`LOCATION_MANAGER`); the read needs `kitchen.ticket.read`.

### Events, audit, PII, observability

- **Events (ADR 0032):** none new on Kafka. ADR 0041 records that kitchen events are
  ticket events and not published; the hold's five are ticket events, and the
  `KITCHEN_BOARD` realtime signal (ADR 0045) carries the change.
- **Audit (ADR 0027):** the policy publication (`kitchen.paid_hold.published`) and the
  override (`kitchen.ticket.payment_hold.override`) are `BUSINESS` facts with the
  actor, scope, reason code and `ChangeDocuments.diff`. Placement and clearing are
  system facts in the ticket's own event ledger, which is where ADR 0041 puts them.
- **PII (ADR 0029):** none. The hold row holds an order id's ticket, an instant and a
  code. No customer name, phone or amount is added to a ticket, an event, a log line or
  a signal.
- **Observability:** counters `kitchen.payment_hold` by `outcome` (`placed`,
  `cleared_paid`, `cleared_terminal`, `cleared_policy`, `blocked_release`, `overdue`,
  `overridden`), bounded labels only; a gauge of tickets held for payment per
  deployment, never per tenant. The five-second sweep logs once per tick at debug.

### Testing

- **Gate:** `NOT_REQUIRED` (cash, marketplace, dine-in) is never held; `PENDING` and
  `FAILED` hold; `AUTHORIZED` and `CAPTURED` release; `VOIDED` and `REFUNDED` do not
  hold; a method whose timing is not `BEFORE_CONFIRMATION` is never held, including a
  fixture timing added in the test (the fail-open rule).
- **The one path, end to end:** a cash pre-order in the buffer, a `CHANGE_PAYMENT_METHOD`
  to Click, the ticket holds, `PaymentCaptured` clears it, the ticket fires at its own
  `release_at`; the same with the capture arriving after `release_at` (fires at once);
  the capture never arriving (blocked event once, overdue after ten minutes, nothing
  fired, nothing cancelled).
- **No race:** a normally paid order's ticket is never held, even with the opener and
  `PaymentProjectionTrigger` running before commit in either order (seen failing first
  with a hold placed at open).
- **Schedule:** the worker leaves a payment-held due ticket alone; `release-schedule`
  edits do not clear the hold; a `MANUAL_HOLD` ticket stays held after payment; a fired
  ticket is never held.
- **Override:** `409 PAYMENT_HOLD` without the capability or without a registry code;
  with both, the ticket fires and an audit fact names the person and the code.
- **Policy:** `OFF` at a branch clears holds on the next tick; a brand document replaces
  the tenant's; the ticket pins the policy version.
- **Boundaries:** cross-tenant and cross-location reads fail; capability declarations
  are asserted; `PaymentStatusFacts` replaces the three private predicates and the
  dispatch and courier suites still pass unchanged.

## Rollout and rollback

Ship `PaymentStatusFacts` first, replacing the three copies with no behaviour change.
Then the columns, the ticket-event constraint and the placement and clearing in
**shadow**: the sweep and the amendment listener record what they would have held
(`PAYMENT_HOLD_PLACED` with a `shadow` marker in the counter only, no `hold_reason`
written) for a fortnight on the pilot tenants, so the claim that only one path
produces `PENDING` on a live order is proved against real traffic. Then turn
placement on at the platform default `HOLD_UNTIL_PAID`, then the override and the
console chip. Rollback is the platform default to `OFF`: no new holds, and the sweep
clears the ones that exist on its next tick. The columns stay, unread.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] `PaymentStatusFacts` in `ordering.api`; the three private predicates replaced;
      dispatch and courier tests unchanged and green.
- [ ] Migration: the `kitchen.tickets` columns, constraints and index; the extended
      `ck_ticket_event_trigger`.
- [ ] `KitchenPaymentSource` and its adapter; `KitchenTicketService.reconcilePaymentHold`;
      `KitchenAmendmentListener` calls it; the capture listener; the sweep in the
      release worker; the due-ticket query's `hold_reason IS NULL`.
- [ ] `kitchen.paid_hold` policy key, validator and the two operations endpoints;
      `kitchen.paid-hold.manage`; the override registry and the `409 PAYMENT_HOLD`.
- [ ] Buffer screen: the hold chip, the disabled release, the override dialog; the
      board's overdue state; ru / uz-latn / en strings.
- [ ] Shadow counters and the fortnight's read before placement is switched on.
- [ ] Tests listed under Testing, each seen failing first.
- [ ] `buffer-page.ts`'s comment and row `2.2`'s text say the hold exists and which
      fact gates it (the gap map is the owner's document; this record does not edit it).

## Exit criteria

An operator switches a confirmed cash pre-order to Click; its ticket in the buffer shows
"awaiting payment" and cannot be fired by the timer or by a button without an override
and a coded reason; the customer pays and the ticket fires on its own schedule; if
`release_at` passes first the board says the ticket is blocked on payment and, ten
minutes later, stuck. A cash order, a dine-in tab and a marketplace order are never
held, and a normally paid online order's ticket reaches the line exactly as fast as it
did before. Gap-map row `2.2` can be marked `BUILT`.

## References

- ADR 0013 (`CaptureTiming`, the registry), ADR 0019 (checkout payment timing),
  ADR 0025, ADR 0027, ADR 0030, ADR 0031, ADR 0039 (`CHANGE_PAYMENT_METHOD`), ADR 0041
  (the buffer, the override), ADR 0046 (settlement and tenders), ADR 0153 (weighed
  orders: a method that does not gate), ADR 0170 (the unpaid-order window, which
  acts on `PAYMENT_AUTHORIZING` and never on a confirmed order)
- `platform/docs/operations-gap-map.md` rows `2.2`, `3.9`
- `platform/docs/frontend-information-architecture.md` §2.2
- `platform/docs/delever-parity-matrix.md` ("Order acceptance ordering and paid-only
  visibility")
- `V0022`, `V0030`, `V0042`, `V0191`, `V0477`; `KitchenTicketService`, `KitchenReleaseWorker`,
  `KitchenAmendmentListener`, `KitchenTicketOpener`, `KitchenOrderSource`;
  `CheckoutOrderWriter`, `CheckoutService`, `OrderAmendmentService`,
  `PaymentProjectionTrigger`, `PaymentCaptureConfirmationTrigger`; `CaptureTiming`,
  `PaymentMethod`, `PaymentIntentService`, `CapturedMoneyPort`;
  `JdbcDeliveryOrderPort`, `JdbcCourierJobStore`, `JdbcDeliveryCompletionAdapter`;
  `frontend/operations/src/app/features/kitchen/buffer-page.ts`
