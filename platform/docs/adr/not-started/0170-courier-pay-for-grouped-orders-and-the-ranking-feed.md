# ADR 0170: Courier pay for grouped orders and the ranking feed

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the bias is built and nothing feeds it, and
  the unpaid-order window can only flag. Built (ADR 0142, batch 17): `Grouping`
  (`mergeRadiusMeters` 50 to 5000, `maxOrdersPerRun` 2 to 4, `maxWaitSeconds` 0 to
  900) on `DispatchRulesDocument`; `SourcingPlanner.groupedRanking`, which ranks a
  candidate whose `FleetCandidate.groupableWithMetres` is within the radius, and
  under the run's order ceiling, ahead of emptier hands, tested; and a publish-time
  refusal of any `grouping` unless `horecaos.fulfillment.dispatch.grouping-enabled`
  is true, which no profile sets (`DispatchRulesAuthoringService`,
  `DispatchRulesValidator`). Not built: anything that sets `groupableWithMetres`
  (`InternalFleetAdapter` builds each `FleetCandidate` with it null,
  `JdbcActiveAssignments` only counts carried shipments), `shipments.run_key`
  (`grep -a` finds it nowhere in `src/main`), any pay treatment of a run
  (`AccrualCalculator.forDelivery(card, distanceMeters)` is called once per
  assignment attempt and `courier_assignment_earnings` is unique on
  `(tenant_id, assignment_attempt_id)`, V0040), any earning quote on the courier's
  offer (`CourierJobsPort.Offer` carries `distanceMeters` and the order's total, and no earning), and a
  `CANCEL` action on the unpaid window (`PaymentWindowPolicy.violations()` refuses it;
  `OrderPaymentProcess.sweepRow` only moves the row to `MANUAL_ACTION_REQUIRED`).
  Also not true today and relevant: `max_concurrent_assignments` defaults to 1
  (`fulfillment.courier_types`, V0040, 1 to 10), so a courier already carrying an
  order is not a candidate at all (`FleetCandidate.hasCapacity`).
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0013, ADR 0014, ADR 0019, ADR 0025, ADR 0026, ADR 0027,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0037, ADR 0042, ADR 0050, ADR 0108,
  ADR 0125, ADR 0142, ADR 0147
- Supersedes / Superseded by: — (amends ADR 0142, which is not edited. It reopens: its
  Open Input "how a courier is paid for one run carrying several orders"; the
  "ranking bias feed" half of its Decision 6, which it left unbuilt; the bounded wait
  inside the same decision, which this record defers to zero; its Decision 7's
  refusal of `CANCEL` on the unpaid window; and ADR 0019's Open Input "checkout
  payment timing, cancellation" for that one case only. ADR 0019's approval-timeout
  and scheduled-order inputs, ADR 0042's one-accrual-per-delivery rule and ADR
  0014's single-winner rule stand.)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with that
  person and the work they block is marked.
  - **How a courier is paid for one run of several orders** (finance, the row's own
    input). Proposed default: *first full, then increment*, quoted on the offer. The
    first order of a run pays exactly as today. Each later order pays its per-order
    component and minimum floor in full and its per-kilometre component on the
    shorter of its own distance and its distance to the nearest drop-off already in
    the run. The basis is decided when the offer is made, shown to the courier, and
    never reclassified against them. The mechanism is the platform's; the amounts
    are the tenant's rate card.
  - **Whether a tenant's engagement terms already allow a pay basis that depends on
    the run** (legal; the tenant's, for the engagement it signs). Proposed default:
    publishing the scheme needs an explicit acknowledgement recorded in the audit
    fact, and the platform asserts nothing about the tenant's contracts.
  - **The facts that feed the ranking bias** (engineering, platform owner). Proposed
    default: the courier's own un-picked-up shipments from the same branch, their
    drop-off points read once under a named decrypt purpose, a straight-line distance,
    one number out per candidate, live on every offer round.
  - **The bounded wait** (operations). Proposed default: deferred. `maxWaitSeconds`
    is refused at publish above zero until a month of ranking-only data says how often
    a groupable courier existed.
  - **Whether a partner may take several drop-offs as one booking** (integration,
    ADR 0142's input). Proposed default: no; grouping is in-house only.
  - **What happens to an order still unpaid after the window** (product, ADR 0019's
    input). Proposed default: `CANCEL` becomes an available action, never the
    default; the platform default stays `FLAG_ONLY` at 30 minutes, and `CANCEL`
    needs a window of at least 15.
  - **What a payment captured after the window does** (finance, product). Proposed
    default: it never revives the order (the code already leaves it as it is); the
    money stays on the settlement and a finance worklist item says a refund is due.
  - **Which courier types may run** (operations). Proposed default: none changed;
    runs are inert until a courier type with `max_concurrent_assignments` of at least
    2 exists and a tenant creates one.

**To accept as written:** say "accept 0170". Every open input above is then closed
on its proposed default.

## Context

**Row `3.8` — "Dispatch rules" — is `PARTIAL`, and names what is left.** *"Courier
order grouping: finance's answer to how a courier is paid for one run carrying
several orders (ADR 0142 open input) and a feed for the ranking bias. Unpaid-order
cancellation: ADR 0019's open input (product)."* Its "Why PARTIAL" says: a rule with
`grouping` is refused at publish "and nothing populates the data the ranking bias
reads (`FleetCandidate.groupableWithMetres`). The unpaid-order cancellation timeout
is not built either: `CANCEL` on the window is refused at publish (ADR 0019's open
input) and the window only decides when an order reaches the stuck list."

**Grouping is three questions, and they are different kinds of question.**

*How is a courier paid for a run?* ADR 0042 settles that earnings "never derive from
the customer delivery charge" and "resolve from a versioned rate card ... snapshotted
onto the assignment at acceptance". `AccrualCalculator.forDelivery` is pure: the
`PER_ORDER` components, the `PER_KM_BAND` components over the order's distance
(accumulated across bands in thousandths of a minor unit and rounded once), and the
`PER_ORDER_MINIMUM` floor. `CourierAccrualService.recordDelivery` runs it once per
`assignment_attempt` and writes one `courier_assignment_earnings` row, one ledger
entry and one `ACCRUED` delivery-cost line; a second delivery event "must not pay".
`distance_meters` is "the routing distance quoted at assignment", and the courier is
shown it before the trip so both parties "can see it before the trip and argue about
it afterwards". Three orders to one building would today pay three times the
per-kilometre amount for the same road. That is not a bug, it is the rate card doing
exactly what ADR 0042 says, and it is the cost a tenant enabling grouping expects to
reduce. It is also the pay of a self-employed person: ADR 0042 treats a reduction as
"agreed in the engagement terms", names a free-text penalty as the way a
self-employment engagement gets reclassified, and has no "pay differently when the
platform decides" concept. The scheme therefore has to be one the courier sees
before accepting.

*What feeds the bias?* `SourcingPlanner.groupedRanking` ranks "groupable within
radius first, then the current order", using `groupableWithMetres`: "the straight-line
distance between this plan's drop-off and the nearest drop-off of an un-picked-up plan
from the same branch that this courier already carries". Two constraints make this
more than a query. The drop-off is personal data in ADR 0029's envelope, decryptable
in `ordering` and revealed only "with a recorded purpose" (`DeliveryOrderPort
.customerLocation(tenantId, orderId, purpose)`, which today returns the whole address
and the delivery instructions). And `fulfillment` may not read `courier` tables, nor
`courier` read `fulfillment` (`courier`'s `package-info`), so the feed is computed on
the `fulfillment` side of `InternalFleetPort.ActiveAssignments`, which already counts
a courier's carried shipments.

*What does an unpaid order do?* An order in `PAYMENT_AUTHORIZING` is a payment-first
order (`CaptureTiming.BEFORE_CONFIRMATION`) awaiting capture. `OrderPaymentProcess`
arms a row when it enters and `sweep` moves it to `MANUAL_ACTION_REQUIRED` after the
window (the `ordering.payment_window` policy of ADR 0142 Decision 7, the deploy
property `horecaos.ordering.workers.payment.stale-after`, 30 minutes, as the
fallback). `OrderStateMachine` already allows `PAYMENT_AUTHORIZING -> PAYMENT_FAILED`
and `-> CANCELLED`; `OrderOutcome` already pairs terminal kind `PAYMENT_FAILED` with
the system category `PAYMENT_NOT_RECEIVED` (V0029) — the platform's own word for this
case, distinct from a customer or an operator cancelling, and the reason ADR 0039
separated cancelled, rejected, expired and payment-failed in the first place. Nothing
writes `PAYMENT_FAILED` today (`OrderStateService` points at "the payment path that
records a failed authorization", which does not exist). The consequences of any
terminal order are already uniform in `applyConsequences`: inventory hold released,
promo redemption released, kitchen slot freed, the `ORDER_PAYMENT` row settled, the
outcome recorded. What a terminal `PAYMENT_FAILED` does *not* do is close the
provider's side: `TerminalOrderPaymentVoid` listens for `OrderCancelled` and
`OrderExpired` only. And a payment that lands anyway is already handled well:
`OrderStateService.paymentCaptured` leaves an ended order "as it is", and
`CapturedMoneyPort.recordCapture` makes the money a recorded, refundable fact on the
settlement; neither provider offers a void (Payme is inbound only; Click's reversal
needs a captured payment), so the real lever is that the order is no longer payable.

## Decision

**Grouped runs are paid first-full-then-increment on a basis stamped on the offer; the
ranking bias is fed by one number per candidate computed in `fulfillment` under a named
decrypt purpose; the bounded wait is deferred; and the unpaid window may cancel, as a
`PAYMENT_FAILED` order, under guards, with a late capture never reviving it.**

### Pay

1. **A run is the set of shipments sharing a `run_key`**, in-house only. The key is
   created when a courier accepts an offer made under the grouping bias to a courier who
   at that moment carries an un-picked-up shipment from the same branch within the
   rule's radius; the new shipment joins the run of the nearest such shipment, and the
   earlier one is given the key at the join. There is no `Trip`, no route order and no
   stored coordinates (ADR 0142's rejection of a trip aggregate stands).
2. **The pay basis is stamped on the assignment attempt when the offer is created and
   never lowered afterwards.** `pay_basis` is `DIRECT` (the order's own distance, as
   today) or `RUN_INCREMENT`, with the `run_merge_distance_m` the feed computed. It is
   `RUN_INCREMENT` only when the resolved `courier.compensation` document has
   `runPayMode = FIRST_FULL_THEN_INCREMENT` and the candidate was groupable at offer
   creation. The first order of a run, every manually assigned order, every partner
   shipment, every order of a tenant on `PER_ORDER`, and every order offered while the
   bias found nothing, is `DIRECT`.
3. **The earning of a `RUN_INCREMENT` order feeds the existing calculator a shorter
   distance and nothing else.** `distance paid = min(direct distance, run_merge_distance_m)`;
   `AccrualCalculator.forDelivery(card, distancePaid)` is unchanged, so the per-order
   component and the minimum floor are paid in full and only the per-kilometre line
   falls. The earning row stores the distance paid in `distance_meters` and the order's
   own distance in a new `direct_distance_meters`, with `pay_basis` and `run_key`, so
   a statement line (ADR 0042, "line-level breakdown") shows both and the reason.
4. **It only ever improves for the courier after the offer.** If, by the time the order
   is delivered, the run's earlier shipment was cancelled before it was picked up, the
   order accrues `DIRECT`. If the earlier shipment is cancelled after a later order
   was already delivered, the later order stays as quoted. The quote is the pay.
5. **The courier is told before accepting.** `CourierJobsPort.Offer` and the courier
   app's offer view gain `quotedEarningMinor` and `payBasis`, computed by the
   `courier` module from the offer's distance and the card that resolves at the offer
   (the same resolution `recordDelivery` makes at `acceptedAt`). An offer for which no
   card resolves carries no quote, and a courier without a resolvable card is not
   offered a grouped order (today the failure is at delivery, "a delivery cannot be
   accrued against nothing").
6. **A tenant chooses the mode explicitly, and cannot enable grouping without
   choosing.** `courier.compensation` (ADR 0030, `CourierPolicies.COMPENSATION`) gains
   `runPayMode`, nullable: `null` is "not decided" and behaves as `PER_ORDER`,
   `PER_ORDER` keeps full pay for every order, `FIRST_FULL_THEN_INCREMENT` is Decision 2.
   Publishing the second needs `acknowledgeEngagementTerms = true`, recorded in the
   ADR 0027 fact, and passes through a registered approval action,
   `courier.run-pay.change`, `ALLOW_WITHOUT_APPROVAL` when no policy exists (ADR 0050)
   so a tenant that wants two signatures can have them. The console pre-fills
   `FIRST_FULL_THEN_INCREMENT` when a dispatch rule's grouping section opens, and the
   dispatch-rules publisher **refuses a `grouping` action unless the resolved
   `courier.compensation` for that scope has a non-null `runPayMode`**. The deploy
   property `horecaos.fulfillment.dispatch.grouping-enabled` stays as the platform's
   kill switch.
7. **Cash and the cost line are per order, unchanged.** Each order's `CASH_COLLECTED`
   entry is written at its delivery; each shipment gets its own `ACCRUED` cost line,
   now the incremental amount, which sum to what the run cost. ADR 0042's statement
   that there is no true cost-per-delivery figure in-house is untouched.

### The feed

8. **`groupableWithMetres` is computed in `fulfillment` and is one integer.** A new
   method on `InternalFleetPort.ActiveAssignments`, `groupable(tenantId, planId,
   courierIds, radiusMeters, maxOrdersPerRun)`, returns for each courier that qualifies
   the distance in metres to the nearest of their carried shipments' drop-offs, over
   shipments of the same `location_id` in `ASSIGNED` or `PICKUP_PENDING` (not yet
   picked up), only when `activeAssignments < maxOrdersPerRun` and the distance is
   within `mergeRadiusMeters`. It is asked only when the resolved action has a
   `grouping`; otherwise nothing is read. `InternalFleetPort.candidates` gains an optional
   `GroupingProbe(planId, mergeRadiusMeters, maxOrdersPerRun)` argument (null when the
   resolved action has no grouping, and the overload every existing caller uses stays),
   `InternalFleetAdapter` merges the result into each `FleetCandidate`, and
   `SourcingPlanner` is unchanged.
9. **The decrypt has its own purpose and returns a point.** `DeliveryOrderPort` gains
   `dropoffPoint(tenantId, orderId, purpose)`, which decrypts the address document,
   returns latitude and longitude and nothing else (no address line, no instructions,
   no name or phone), and records the purpose `DISPATCH_GROUPING` (ADR 0029, ADR 0027)
   per call. The other order's coordinates are never stored; the one number that is,
   is the attempt's `run_merge_distance_m`.
10. **Distance is straight-line by default and road distance when ADR 0147 is bound.**
    The record states which source produced it on the attempt's `decision_reason`. A
    failure to decrypt, or an order whose destination has no coordinate, makes the
    candidate not groupable, never an error and never a reason to ask nobody.
11. **No wait.** `maxWaitSeconds` above zero is refused at publish until the first month
    of grouped offers shows how often a groupable courier existed. Grouping is a
    ranking bias only. ADR 0142's promise bound, "never past `latest_assignment_at`", is
    therefore trivially kept.
12. **Live, never cached.** The feed is computed on each offer round from committed
    state (ADR 0033: no correctness decision reads cache). The decrypts per round are
    bounded by the couriers carrying un-picked-up shipments at one branch times
    `maxOrdersPerRun − 1`, and by nothing at all for a tenant with no grouping rule.

### The unpaid window

13. **`CANCEL` becomes an available action on `ordering.payment_window`**, publishable
    with a window of at least 15 minutes and at most the existing 1440. The platform
    default stays `FLAG_ONLY` at 30 minutes, so nothing changes for a tenant that
    publishes nothing.
14. **What `CANCEL` does is end the order as `PAYMENT_FAILED`.** `OrderStateService`
    gains `paymentWindowReached(tenantId, orderId)`, in the shape of
    `approvalDeadlineReached`: one conditional update naming `PAYMENT_AUTHORIZING` as
    the expected status, trigger `SYSTEM`, reason `PAYMENT_WINDOW_ELAPSED`, an
    `OrderOutcome` of kind `PAYMENT_FAILED` and category `PAYMENT_NOT_RECEIVED`, then
    `applyConsequences` as for any terminal order. It is not `CANCELLED`: that status
    and its reason registry are for a person's decision, and a payment that never came
    is its own commercial fact (ADR 0039). The console says "Cancelled: not paid".
15. **Guards: the sweep cancels only what it is sure about.** The order is still
    `PAYMENT_AUTHORIZING`; the window has elapsed since entry; the policy in force at
    the order's branch says `CANCEL`; and no payment attempt on the order is
    `UNCERTAIN` (ADR 0007: a question about money is owned by its resolver, and an
    order is never ended over an unanswered one, it stays flagged and is rechecked).
    Any other state leaves the order flagged exactly as today.
16. **The provider side is closed by an event.** A new in-process `OrderPaymentLapsed`
    (`ordering.api`, an `OrderingEvent`, with a schema file and a catalogue entry per
    ADR 0032) is published after the transition. `TerminalOrderPaymentVoid` handles it
    as it handles `OrderCancelled` and `OrderExpired` (`voidAnyLivePayment`, never
    twice, `UNCERTAIN` left alone), and the customer's notification names the reason.
17. **A capture that lands afterwards never revives the order.** This ratifies what
    `paymentCaptured` already does. The money is recorded on the settlement as it is
    today and, new, a finance worklist item (`ORDER_CAPTURED_AFTER_LAPSE`, the ADR 0058
    operations-alert mechanism) says a refund is due, through ADR 0048's remedy. No
    automatic refund: Payme's refund is a provider-console act the platform back-records,
    and a refund is a money movement a person confirms.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| **Per order, unchanged.** Every order in a run pays its own full card | Zero code, never a dispute, and the safest for the courier. But a run of three to one building pays three times the road, so grouping saves the tenant nothing in courier cost and only helps speed. It remains available as the mode `PER_ORDER`, and is what a tenant that has not decided gets | A tenant says grouping is for throughput and cost parity is what it wants (it can then simply not choose the increment) |
| **Per run with a split.** Price the run once and divide it | Needs the run's route, which ADR 0142 refuses to model, and the run's end, so no accrual could be written at each delivery: it would break "a delivery accrues exactly once, when it happens", the quote-at-assignment rule and the per-order cash entry. The split key (by order, by distance, equal) is a fresh argument every statement | A `Trip` aggregate with route order exists (ADR 0142's own trigger: a routing provider bound and the courier app able to accept a run) |
| **First full, increment on a fixed discount (a percentage of the next orders' pay)** | Easy to explain, but it discounts the per-order component too, which pays for a stop, not a road; and it needs a new rate-card component type, which the card's validation, its versions and the courier-type screen all learn | A tenant's pay genuinely is a flat fee per additional order |
| **Increment decided at delivery, not at the offer** | Lets the platform reclassify after the courier has driven, and makes the courier's pay depend on whether another order was cancelled behind them. The offer-stamped basis is a quote the courier accepted | Never as a reason to lower pay after acceptance |
| **Decide the basis from the delivery order** (the first delivered is the full one) | A courier could deliver the far order last and be paid for the near one in full; the incentive is perverse and it makes pay a function of a choice made on the road | Never |
| **Feed the bias by decrypting every candidate's carried orders into a cache** | A cache of drop-off coordinates is a store of personal data with no purpose limit, and ADR 0033 forbids a correctness decision on cache state | Never |
| **Feed it from a coarse drop-off cell stored on the plan** | A 150 metre cell of a home is still a location; the one-number-out design stores nothing about a place | A cell is classified and retained under ADR 0029 on its own merits |
| **Implement the bounded wait now** | "Waits at most `maxWaitSeconds` for such a courier" has no definition without predicting a courier who is not yet a candidate; the first build cannot say what it is waiting for | A month of ranking-only offers shows groupable couriers regularly existing a few minutes later |
| **Cancel the unpaid order as `CANCELLED` through `OrderStateService.cancel`** | Reuses the existing path and the existing `OrderCancelled` listener, with no new event. But it files a payment that never came in the same bucket as a customer's change of mind, with a reason from a registry, and ADR 0039 separated them on purpose | The cancellation funnel is decided not to need the distinction |
| **Revive the order on a late capture** | The inventory hold is released, the quote has expired, the kitchen slot is freed and the customer was told it was cancelled; reviving it is a second checkout inside a first one | A tenant wants "hold the order for a day" and a payment-first order that survives its window, which is a different state, not a revival |
| **Refund a late capture automatically** | Moves money without a person; Payme's refund is not an API the platform can call | Both providers offer a refund API the platform can call idempotently, and finance wants it |

## Consequences

### Positive

- A tenant can switch on grouping with its cost consequence visible: the first order
  of a run pays as always and the courier sees, before accepting, what each added
  order pays.
- No change to `AccrualCalculator`, to the one-accrual-per-attempt constraint or to
  the cash entry; the new behaviour is a distance the existing function is handed and
  a stamp it reads.
- The bias gets a feed that stores nothing about where anyone lives and reveals a
  point, with a purpose, only when a rule needs it.
- The unpaid window stops being a flag nobody reads and becomes a decision a tenant can
  make, with the platform's own terminal state, the inventory and promo consequences
  it already draws, and the provider's side closed.
- A late capture is handled as what it is, money to give back, and surfaced.

### Negative

- A tenant that switches to `FIRST_FULL_THEN_INCREMENT` pays couriers less for the same
  kilometres. That is the purpose and also a labour-relations event; the offer quote
  and the acknowledgement make it visible and do not make it welcome.
- A courier offered a run order sees a lower figure than the same order alone. Some
  will decline, and a fleet that declines grouped orders makes grouping a ranking that
  buys nothing. Whether the quote is acceptable is learned, not designed.
- The decrypt purpose is one more thing to defend, and `DeliveryOrderPort` grows a
  narrower method beside `customerLocation` without removing the broader one.
- The pay basis lives on three tables (attempt, shipment, earning). A stamp and its
  earning can disagree if a code path writes one and not the other; the earning's
  CHECK ties `pay_basis` to `direct_distance_meters` and a test replays a run end to
  end.
- Ending an order as `PAYMENT_FAILED` by timer is the platform's first writer of that
  status. Reports already treat it as "not a sale", and anything that assumed it was
  unreachable (a screen, an export, a customer message) needs reading.
- The window's `CANCEL` can end an order whose customer was about to pay on a slow
  provider page. The 15 minute floor, the `UNCERTAIN` guard and a non-payable order at
  the provider reduce the harm and do not remove it.

### Accepted trade-offs

- A lead order cancelled after followers were delivered leaves the followers on their
  quoted basis; the tenant absorbs the difference rather than the courier.
- Manual and bulk assignment never create a run (ADR 0172). An operator who puts three
  orders on one courier pays three full cards. The operator is not the feed and does
  not decrypt drop-offs.
- No wait means a groupable courier a few minutes behind is not waited for.
- A tenant with `max_concurrent_assignments` of 1 on every courier type gets no runs,
  and nothing says so except the simulator's warning. The ceiling is the tenant's to
  raise.

## Specification

### Physical model

```text
fulfillment.assignment_attempts   (columns added)
  pay_basis             varchar(16) NOT NULL DEFAULT 'DIRECT'    -- CHECK IN ('DIRECT','RUN_INCREMENT')
  run_merge_distance_m  integer NULL                              -- CHECK (pay_basis = 'RUN_INCREMENT') = (run_merge_distance_m IS NOT NULL)
fulfillment.shipments             (columns added, ADR 0142's own names)
  run_key uuid NULL, run_merge_distance_m integer NULL
  index (tenant_id, run_key) WHERE run_key IS NOT NULL
fulfillment.courier_assignment_earnings   (columns added; distance_meters keeps meaning "the distance paid")
  pay_basis varchar(16) NOT NULL DEFAULT 'DIRECT', run_key uuid NULL,
  direct_distance_meters integer NULL    -- CHECK (pay_basis = 'RUN_INCREMENT') = (direct_distance_meters IS NOT NULL)
courier.compensation document     runPayMode: null | 'PER_ORDER' | 'FIRST_FULL_THEN_INCREMENT'   -- ADR 0030, no table
ordering.payment_window document  no schema change; PaymentWindowPolicy.violations() permits CANCEL at >= 15 minutes
```

Every row already carries `tenant_id`; the grants of the three tables cover the new
columns. `ck_plan_mode`, `uq_earning_attempt` and the single-winner indexes are
untouched. A migration adds nothing to `reporting`; `fact_delivery` already carries the
per-delivery cost the earning produces. Numbers are taken across every active worktree
per `AGENTS.md`.

### APIs (ADR 0031)

```text
GET/PUT /api/v1/operations/tenants/{tenantId}/courier-policy?brandId=&locationId=   runPayMode, acknowledgeEngagementTerms
GET/PUT /api/v1/operations/tenants/{tenantId}/payment-window?brandId=&locationId=   action CANCEL now publishable
PUT     /api/v1/operations/tenants/{tenantId}/dispatch-rules                         grouping needs a decided runPayMode and maxWaitSeconds = 0
courier app offer view                                                                + quotedEarningMinor, payBasis
POST    .../dispatch-rules/simulations                                               + the candidate's groupableWithMetres and the basis it would be offered under
```

New Problem Details codes: `RUN_PAY_MODE_UNDECIDED`, `GROUPING_WAIT_NOT_AVAILABLE`,
`PAYMENT_WINDOW_TOO_SHORT_FOR_CANCEL`. No new capability: the policy writes reuse
`delivery.policy.write`, the payment window `order.payment-window.manage`, the rules
`delivery.dispatch_rules.write`.

### Events, audit, PII, observability

- **Events (ADR 0032):** `OrderPaymentLapsed` v1 (`ordering.api`), keyed on the order id,
  with a schema file and a `docs/domains/events.md` row before the producer ships,
  carrying ids, the lapsed window and the status; no name, phone, address or amount.
  Nothing else is new: the run stamps are local state, read by the board.
- **Audit (ADR 0027):** the courier policy publication records `runPayMode` and the
  acknowledgement; the window publication records `CANCEL`; each lapse writes a
  `BUSINESS` fact `ordering.order.payment-window-lapsed` naming the window and the
  policy version; each `DISPATCH_GROUPING` reveal records its purpose.
- **PII (ADR 0029):** the only new reveal returns a point, with a purpose, and is
  never logged, stored, evented or carried into a metric. The attempt stores a distance.
  The notification of a lapsed order follows ADR 0020's templates and carries no amount.
- **Observability:** counters `fulfillment.grouping.offers` (`groupable`, `not_groupable`,
  `no_card`), `courier.run_pay.basis` (`direct`, `run_increment`), `ordering.payment_window`
  (`flagged`, `lapsed`, `skipped_uncertain`), bounded labels. The reveal count is
  reported as a counter by purpose.

### Testing

- **Pay:** the first order of a run accrues byte-identically to today; a later order's
  earning equals `forDelivery(card, min(direct, merge))` with the per-order component
  and floor intact; a run of three under a three-band card; the basis is stamped at
  offer and is not lowered by a later cancellation; a lead cancelled before pickup
  makes the follower `DIRECT`; `PER_ORDER`, a manual assignment and a partner
  shipment are always `DIRECT`; a courier with no resolvable card is not offered a
  grouped order; a duplicate delivery event still accrues once; cash is per order.
- **Policy:** a `grouping` rule is refused with `RUN_PAY_MODE_UNDECIDED` while the
  resolved `runPayMode` is null and with `GROUPING_WAIT_NOT_AVAILABLE` above zero
  wait; `FIRST_FULL_THEN_INCREMENT` without the acknowledgement is refused; an
  existing document without the field resolves as `PER_ORDER`.
- **Feed:** a courier with a nearby un-picked-up shipment from the same branch is
  groupable, one with only a picked-up shipment, a shipment from another branch, a
  courier at the run ceiling, or a distance past the radius is not; only a distance
  leaves `fulfillment`; the reveal records `DISPATCH_GROUPING` and returns no address
  text; nothing is read for a tenant with no `grouping`; a candidate whose address has
  no coordinate is simply not groupable.
- **Window:** `FLAG_ONLY` is today's behaviour; `CANCEL` below 15 minutes is refused;
  an order with an `UNCERTAIN` attempt is flagged and not ended; an order that is
  captured in the same instant settles at one outcome (the conditional update); a lapse
  releases the inventory hold and the promo claim, frees the kitchen slot, writes the
  outcome and publishes `OrderPaymentLapsed`; the provider's open attempt is closed once;
  a capture afterwards leaves the order `PAYMENT_FAILED`, records the money and raises
  the worklist item; a lapsed order reads as "not a sale" in the facts.
- **Boundaries:** cross-tenant reads and writes fail; capability declarations are
  asserted; `ModularArchitectureTests` stays green with the feed on the `fulfillment`
  side.

## Rollout and rollback

Ship in this order, each step inert until the next. First the window's `CANCEL` path in
**shadow** for a fortnight (the sweep logs and counts what it would have ended and ends
nothing), with `OrderPaymentLapsed`, its listener and the worklist item built and
tested; then enable `CANCEL` as an option. Independently, add the pay columns and the
stamp with every attempt `DIRECT`, which proves accruals unchanged against the
regression fixture on real deliveries; then `runPayMode` and the quote on the offer;
then the feed, with `grouping-enabled` still false, exercised in staging with seeded
couriers and the simulator; then the validator's refusal lifts for a tenant that has
decided its mode, on the first tenant with a courier type of ceiling two or more.
Rollback: the window back to `FLAG_ONLY` (orders already ended stay ended); the platform
property false stops new grouped offers, and runs in flight finish on the basis they
were stamped with; `runPayMode` back to `PER_ORDER` changes attempts created afterwards
only.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Migration: the attempt, shipment and earning columns and CHECKs; the shipment
      index.
- [ ] `CourierCompensationPolicy.runPayMode`, its validator, the approval action
      `courier.run-pay.change`, the acknowledgement, the courier-policy page's control.
- [ ] `DeliveredAssignment` and `DeliveryCompletionPort.InternalDelivery` carry
      `payBasis`, `runKey`, `runMergeDistanceMeters`; `CourierAccrualService` feeds the
      calculator the distance paid and stores both; statement lines show the basis.
- [ ] `Offer.quotedEarningMinor` and `payBasis`; the courier module computes the quote;
      the offer view; a candidate with no resolvable card is not offered a grouped order.
- [ ] `InternalFleetPort.ActiveAssignments.groupable`, the optional `GroupingProbe` on
      `InternalFleetPort.candidates`, `DeliveryOrderPort.dropoffPoint` with the
      `DISPATCH_GROUPING` purpose; `InternalFleetAdapter` merges the result;
      the stamp written when the offer is created; `run_key` assigned at acceptance.
- [ ] `DispatchRulesValidator`: `RUN_PAY_MODE_UNDECIDED`, `GROUPING_WAIT_NOT_AVAILABLE`;
      the simulator shows the feed result and the basis; a warning when no courier type at
      the scope can carry the rule's `maxOrdersPerRun`.
- [ ] `PaymentWindowPolicy` permits `CANCEL` at 15 minutes or more;
      `OrderStateService.paymentWindowReached`; the sweep calls it under the guards;
      `OrderPaymentLapsed` with its schema and catalogue entry; `TerminalOrderPaymentVoid`
      handles it; the customer notification; the `ORDER_CAPTURED_AFTER_LAPSE` worklist item.
- [ ] Console: the pay-mode control with the acknowledgement; the window card's `CANCEL`;
      "Cancelled: not paid" on the order; ru / uz-latn / en strings.
- [ ] Shadow counters for the window; the regression fixture proving all-`DIRECT` accruals.
- [ ] Update ADR 0142's status line to say its pay and feed inputs are decided here.
- [ ] Tests listed under Testing, each seen failing first.

## Exit criteria

A tenant on a courier type that can carry three orders publishes a rule with grouping,
having chosen first-full-then-increment and acknowledged its engagement terms. Two
orders from one branch to neighbouring doors are offered to the same courier; the
second offer shows a smaller figure and why; the courier accepts; the second delivery
accrues the full per-order component and the floor and a per-kilometre amount on the
short distance, the statement line showing both distances, and the first delivery
accrues exactly as it would have alone. Separately, a tenant publishes a 30 minute
`CANCEL` window: an online order nobody pays ends as "Cancelled: not paid" with its
stock and promo released and the provider's side closed; a payment landing after that
is recorded, the order stays ended and finance sees a refund due. Gap-map row `3.8`
can be marked `BUILT` for the grouping and unpaid-window parts.

## References

- ADR 0007, ADR 0013, ADR 0014 (single winner, partner verification), ADR 0019 (open
  input), ADR 0025, ADR 0026, ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0037,
  ADR 0039 (terminal outcomes), ADR 0042 (accrual, rate cards, statements), ADR 0050,
  ADR 0058, ADR 0108, ADR 0125, ADR 0142 (Decisions 6 and 7), ADR 0147, ADR 0172
- `platform/docs/operations-gap-map.md` rows `3.8`, `3.9`, `3.1`
- `platform/docs/delever-parity-matrix.md` ("Courier order batching")
- `platform/docs/frontend-information-architecture.md` 3.8
- `V0029`, `V0040`, `V0054`, `V0465`; `AccrualCalculator`, `CourierAccrualService`,
  `CourierPolicies`, `CourierCompensationPolicy`, `CourierDeliveryService`,
  `CourierJobsPort`, `InternalFleetPort`, `InternalFleetAdapter`, `JdbcActiveAssignments`,
  `SourcingPlanner`, `DispatchRulesValidator`, `DispatchRulesAuthoringService`,
  `DeliveryOrderPort`, `JdbcDeliveryOrderPort`; `PaymentWindowPolicy`,
  `OrderPaymentProcess`, `PaymentWindowAuthoringService`, `OrderStateService`,
  `OrderStateMachine`, `OrderOutcome`, `TerminalOrderPaymentVoid`, `CapturedMoneyPort`
