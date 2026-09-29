# ADR 0142: Dispatch rules

- Decision status: Proposed
- Implementation status: Not started — no dispatch rule exists as a record, a
  document, an endpoint or a screen. What an operator cannot decide today is
  compiled in or deployed as configuration: `DeliveryPlanningService.open`
  writes `SourcingMode.FLEET_FIRST` on every plan; `ShipmentBookingPort.partners`
  returns the branch's delivery bindings ordered by ADR 0026 scope specificity
  and then the binding's `priority` column, and consults neither the order's
  zone nor its source; `SourcingPlanner` fixes the fleet-then-partner ladder;
  the offer, lead and window numbers are an ADR 0030 policy document
  (`fulfillment.sourcing`, settable down to the branch) that nothing can author —
  `DeliverySourcingPolicies.SOURCING` has readers and no writer, so the
  provisional `DeliverySourcingPolicy.DEFAULTS` are in force everywhere; there
  is no grouping of any kind; and the only unpaid-order threshold is a deploy
  property (`horecaos.ordering.workers.payment.stale-after`, PT30M) that flags
  an order for a person and never cancels it. What is built and this record
  keeps: the durable plan and sourcing job, the single-winner attempt journal,
  quote scoring, manual assign and external booking on the dispatch board
  (`ManualDispatchService`, `ManualExternalBookingService`), and the
  `DISPATCH_BOARD` realtime signal for those manual actions.
- Date proposed: 2026-09-29
- Date decided: —
- Deciders: proposed by Claude (wave batch 14, w7-adrs-stops-dispatch-walkin)
  from `platform/docs/operations-gap-map.md` row `3.8`, blocked on an ADR;
  Ayubkhon Abbosov (platform owner) decides.
- Depends on: ADR 0007, ADR 0013, ADR 0014, ADR 0019, ADR 0025, ADR 0026,
  ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0036, ADR 0037, ADR 0042,
  ADR 0045
- Supersedes / Superseded by: — (does not reopen any decision in ADR 0014; where
  the IA's row 3.8 text and ADR 0014 disagree, ADR 0014 stands — see
  "Racing" below)
- Open inputs:
  - What an order that is still unpaid after the window should do — be
    cancelled, or only be flagged — and the window's length. This is ADR 0019's
    own open input ("checkout payment timing, cancellation, approval timeout"),
    which `OrderPaymentProcess` deliberately does not answer by acting as if it
    had. This record builds the place the number lives and defaults it to
    "flag only"; cancelling is refused until product answers (product).
  - What happens to a payment that is captured after the order was cancelled for
    non-payment: ADR 0013's void/refund path already exists for a terminal order
    (`TerminalOrderPaymentVoid`), but whether a late capture may instead revive
    the order is a money-and-promise question this record does not settle
    (finance, product).
  - How a courier is paid for one run carrying several orders, given ADR 0042
    prices per delivery. Grouping is built as an assignment bias, not a pay
    scheme; the pay treatment must be decided before any tenant enables it
    (finance).
  - Whether Yandex Delivery (multiple route points, per ADR 0014's verified
    matrix) or Noor (exactly one origin) accepts several drop-offs from one
    pickup as one booking. Grouping here is in-house only until this is
    answered per partner (integration).
  - The default rule set a new tenant starts with (operations). This record's
    default reproduces today's behaviour exactly.
  - Whether publishing a rule set needs a second person (ADR 0027 / ADR 0050
    approval). A wrong rule can send every order to the most expensive partner;
    this record leaves it unapproved by default and registers no approval action
    (owner).

## Context

**Gap-map row `3.8` — "Dispatch rules" — is `NOT BUILT`, a pilot row, `deferred`:**
*"An operator cannot decide anything about dispatch: which provider serves which
zone, source or branch; when auto-dispatch fires relative to prep time; the
fallback order; courier order grouping and its merge radius; or the unpaid-order
cancellation timeout. All of it is compiled-in defaults, and this is a pilot
row."* Its "What is missing" note says the row *"Needs its own ADR — no decision
record covers an operator-authored, provider-agnostic rule engine; the parity
matrix leaves racing/grouping semantics as an open question."*
`platform/docs/frontend-information-architecture.md` 3.8 describes the intended
screen: *"One provider-agnostic rule engine replacing the near-identical config
duplicated inside all five Delever provider pages. Conditions (source, zone,
branch, timing basis, order status, prep time, delay minutes) → action
(provider, service tier, fallback). Owns: auto-dispatch triggers; cascade/simultaneous
multi-provider search with cheapest selection and loser cancellation;
auto-recreate after late payment; courier order grouping with merge radius;
unpaid-order cancellation timeout."* `operations-spec/settings.md` already tells
the settings screen to show a read-only summary of "the rules currently in force
for the scope, with a link" because these settings were deliberately moved here.
The spec is ahead of the decision.

**What the code does today, by question the row asks.**

| The row's question | What answers it now | Operator can change it? |
|---|---|---|
| Which provider serves this order | `DeliverySourcingService.source` calls `ShipmentBookingPort.partners(tenant, brand, location)` → `installations.candidateBindings`, sorted by binding scope specificity then `integration.bindings.priority`. ADR 0014's "Provider selection" says to filter candidates by "service zone" among other things; the code filters by capability and binding status only | Only by editing binding rows; not by zone or source |
| Fleet or partners, in what order | `SourcingMode` (`FLEET_FIRST`, `FLEET_ONLY`, `PARTNER_ONLY`, `MANUAL`) exists on `delivery_plans.sourcing_mode`; `DeliveryPlanningService.open` always writes `FLEET_FIRST`. **No mode puts partners first**: `usesFleet()` and `usesPartners()` are unordered flags, `SourcingPlanner.decide` runs the fleet lane before the partner lane in the one mode that has both, and `PARTNER_ONLY` never reaches the fleet, so it escalates to `MANUAL_ACTION_REQUIRED` when its partners are exhausted | No |
| When auto-dispatch fires | `PickupPlan.forOrder`: `source_at = ready − preparationLeadSeconds − safetyBufferSeconds`, floored at confirmation, from the `fulfillment.sourcing` document | The document is scope-resolved to the branch but has no writer or screen |
| The fallback order | Fleet lane bounded by the handover deadline and `offerRounds`, then partners ranked by `QuoteScoring` (eligibility, price, pickup ETA, configured binding order, id) when more than one is bound | No |
| Grouping and merge radius | None. `SourcingPlanner`'s fleet ranking is *emptiest hands first, then nearest*; `max_concurrent_assignments` on the courier type is the only ceiling. Nothing prefers a courier already going the same way | No |
| Unpaid-order cancellation | `OrderPaymentProcess.sweep` flags an order in `PAYMENT_AUTHORIZING` past a deploy property as `MANUAL_ACTION_REQUIRED`. It never cancels | Deploy configuration only |

**What ADR 0014 has already decided, and this record must not reopen.**

- A `DeliveryPlan` is separate from the `Shipment` and from `AssignmentAttempt`s;
  a durable job, not Kafka, is the alarm clock.
- The selection service returns a decision and Camel performs the call; a
  selection must be "explainable and reproducible from stored evidence".
- Partners are asked for non-binding quotes in parallel when safe; **a live
  booking is created with the selected winner only.** ADR 0014's alternatives
  table rejects "Book every partner and cancel the losers" with one revisit
  trigger: *a specific partner contract provides free, idempotent, immediate
  cancellation in writing, and only for that partner.* Its "Verified partner
  capabilities" section says that trigger is met for Yandex Delivery alone, whose
  unaccepted claim is a hold, and not for Noor, whose create dispatches a courier.
- An uncertain partner attempt stops further booking until reconciled; the
  confirmed order is never cancelled because sourcing failed — it escalates to
  `MANUAL_ACTION_REQUIRED`.
- Every decision snapshots the policy identity: `delivery_plans.policy_id` and
  `policy_version`, and each attempt carries them.

**The IA's row and ADR 0014 disagree on one thing**, and it matters: 3.8 lists
"simultaneous multi-provider search with cheapest selection **and loser
cancellation**". A quote race with cheapest selection is what ADR 0014 built
(`QuoteScoring`). A *booking* race with cancellation of the losers is what it
rejected. This record adopts the first and keeps the second closed.

**Two items in the row are not dispatch.** The unpaid-order timeout acts on an
order in `PAYMENT_AUTHORIZING`, which is `ordering`'s state under ADR 0019 and
ADR 0013's payment gate; dispatch plans do not exist yet for such an order,
because `DeliveryPlanTrigger` opens a plan only on `OrderConfirmed`, and a
payment-first order is confirmed only after capture. "Auto-recreate after late
payment" therefore has nothing to recreate: a late payment is a late
confirmation, and the existing trigger plans it. The residual case, a capture
that arrives after the order was cancelled, is the payment module's void path.
The screen at IA 3.8 may show the unpaid window beside the dispatch rules, but
its owner is `ordering`.

## Decision

**Dispatch rules are one ordered rule document per scope, in the ADR 0030 policy
machinery, evaluated by one pure function at plan creation.**

1. **One document, `fulfillment.dispatch_rules`, an ordered list of rules with a
   mandatory default.** It is an ADR 0030 policy (`PolicyKey`), settable at
   `TENANT`, `BRAND` and `LOCATION`, resolved replace-not-merge (ADR 0030: "no
   partial merging of policy documents by default"), published as a whole with
   `If-Match` through `PolicyAuthor`, and versioned so that
   `PolicyResolver.pinned` returns the exact document any past plan used. It sits
   beside `fulfillment.sourcing`, which keeps the timing *numbers* (lead, buffer,
   window width, offer rounds, offer ceiling, slack) and gains the writer and
   screen it has lacked. The rules choose *which lane, which partners, in what
   order, and when to start*; the numbers say *how long each step takes*.

2. **Rules are data in a closed vocabulary, not code.** A rule's `when` is a
   conjunction over: order source (`tenant.sales_channels.system_type`, and
   optionally specific channel ids), delivery zone (`fulfillment.service_zones`
   ids, from the order's fee-resolution evidence), branch (narrower than the
   document's own scope), preparation minutes, branch-to-door distance, the local
   day and time at confirmation, and whether the order is prepaid. Its `then` is:
   sourcing mode (one of five, Decision 9), the partner set (installation ids,
   ordered, with an exclude list) and how a partner is chosen (`LADDER` in the
   order given, or `CHEAPEST` from non-binding quotes), the dispatch start (a basis
   and a bounded offset from it), and a grouping policy. There is no expression language, no script,
   and no provider name: a rule names an ADR 0026 *installation*, and the
   evaluator can only order or remove candidates `ShipmentBookingPort` already
   returned, so a rule cannot book a partner the branch has no active binding
   for.

3. **Rules are evaluated once, when the plan is created, and the result is
   stored on the plan.** `DeliveryPlanningService.open` evaluates the document in
   force for the order's location and writes the matched rule id and the resolved
   action on the plan beside the pinned policy identity; every later sourcing
   tick applies that stored decision. Editing a rule therefore never changes what
   happens to an order already in flight. The live facts a tick reads (who is on
   shift, which partner is healthy, what a quote says) are what they always were,
   and so is the timing document: `DeliverySourcingService.source` re-resolves
   `fulfillment.sourcing` on every tick and records the version it used on each
   attempt, and this record does not change that.

4. **The simulator is the evaluator.** `DispatchRuleEvaluator.evaluate(document,
   facts)` is a pure function in `fulfillment.domain.sourcing`, in the genre of
   `SourcingPlanner` and `QuoteScoring`: no clock, no database, no port. Runtime
   and simulator call the same method. The simulator takes a scope, an optional
   *draft* document and a scenario (typed facts, or the id of a recent plan whose
   facts are re-read), and returns the matched rule, why each earlier rule did
   not match (its first failing condition), the resolved action, the computed
   `source_at`, and the candidate ladder. It calls no provider and requests no
   quote, and says so.

5. **Racing: quote races yes, booking races no.** `CHEAPEST` asks every eligible
   partner for a non-binding quote and books only the winner — ADR 0014 as built.
   A `holdBeforeConfirm` action field is reserved for a Yandex-style hold race
   and is refused at publish while `SourcingPlanner` cannot emit
   `BookingIntent.HOLD` (it cannot, by its own class doc, until
   `assignment_attempts` records holds). A live simultaneous booking with
   cancellation of the losers is not a rule option at all; it would need ADR
   0014's revisit trigger to fire for a named partner and a new ADR.

6. **Grouping is an assignment bias for the in-house fleet, bounded by the
   promise.** With grouping enabled by a rule, when the fleet lane offers a plan,
   couriers already carrying an un-picked-up plan from the same branch whose
   drop-off lies within `mergeRadiusMeters` of this one, and who are under their
   `max_concurrent_assignments`, rank ahead of emptier hands. The order waits at
   most `maxWaitSeconds` for such a courier and never past the point where the
   plan's pickup window or `latest_assignment_at` would be missed. Shipments that
   travel together share a `run_key`; there is no `Trip` aggregate and no route
   ordering in this record. Only a distance is stored, never the other order's
   coordinates.

7. **The unpaid-order window is an `ordering` policy, shown on the same screen.**
   `ordering.payment_window` (ADR 0030 policy, `TENANT`/`BRAND`/`LOCATION`) holds
   the window and an action, `FLAG_ONLY` or `CANCEL`. `OrderPaymentProcess`
   reads it in place of the deploy property. `CANCEL` is a named value refused at
   publish until ADR 0019's open input is answered; until then the window only
   controls when an order reaches the stuck list.

8. **Dispatch start is a named basis plus a bounded offset.** `LEAD` (today's
   formula and the default), `CONFIRMATION` (source at once) or `READY` (source at
   the estimated ready instant), each with an offset between −60 and +30 minutes.
   `PickupPlan.CALCULATION_VERSION` is bumped when the rule engine ships, as its
   own doc requires for any formula change. A rule whose basis and offset leave
   no time for a partner lane it enables is refused at publish, and the
   simulator says so for a draft.

9. **Lane order is part of the mode: `PARTNER_FIRST` is a fifth `SourcingMode`, and
   it is the one change to `SourcingPlanner` in this record.** The row asks for "the
   fallback order", and its flagship case — a named partner first, the fleet second
   — cannot be said with the four modes that exist: `FLEET_FIRST` asks the fleet
   first, and `PARTNER_ONLY` never reaches the fleet, so a plan whose partners
   refuse it escalates instead of falling back to couriers. `PARTNER_FIRST` runs the
   partner lane first (a `LADDER` or `CHEAPEST` quotes, single winner, exactly as
   ADR 0014 has it) and offers the in-house fleet only when that lane has ended
   with a definite answer: every eligible partner refused, or none is bound. The
   default stays `FLEET_FIRST`, the owner's 2026-08-23 decision. The planner
   changes, and only these things change:
   - the partner lane is tried first and the fleet lane second;
   - an uncertain partner attempt still escalates to operations before either lane
     runs (`AWAITING_RECONCILIATION`), so an unreconciled booking never falls
     through to a courier — two couriers on one order is what ADR 0014's rule
     exists to prevent;
   - the fleet lane of `PARTNER_FIRST` has no partner behind it to protect, so
     its deadline is `latest_assignment_at`, as in `FLEET_ONLY`, and not
     `pickup_window_end − partnerLeadSeconds`. `SourcingPlanner.handoverDeadline`
     today returns `latest_assignment_at` only `if (!mode.usesPartners())`; keyed
     that way, a `PARTNER_FIRST` fleet lane would open already past its deadline and
     refuse with `FLEET_BUDGET_SPENT`. The test is "does a partner lane *follow*
     the fleet lane", not "does the mode use partners";
   - offer rounds, offer TTL, capacity and ranking in the fleet lane are as built,
     and so is the partner lane's booking-window logic (`bookWith`);
   - reasons: the partner booking carries a new `PARTNER_FIRST_MODE`, and the
     `OfferInternal` that follows carries the partner lane's end
     (`PARTNERS_EXHAUSTED` or `NO_PARTNER_CONFIGURED`), so the attempt journal says
     why a courier was asked.

   This does not reopen ADR 0014: no second party is booked, the winner is single,
   and nothing is cancelled. `delivery_plans.sourcing_mode` is the decision's mode,
   and its `ck_plan_mode` CHECK (V0054) lists four values, so the build widens it in
   a new migration (drop and re-add; V0054 is applied).

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A settings page per provider, as Delever has | The IA and `settings.md` reject it by name: near-identical config duplicated across five pages, and "consequently cannot express provider fallback at all". A per-provider page has no way to say "Yandex only for the far zone, then Noor" | Never |
| A `fulfillment.dispatch_rules` row-per-rule table with CRUD | Per-rule audit and per-rule concurrency, at the price of atomic publish, atomic reorder and pinned-version replay: with rows, "what rules did plan X run under" needs a history table the policy machinery already is. `PolicyAuthor`/`PolicyResolver.pinned`, `If-Match` and the courier-policy screen pattern exist | A scope regularly holds more than about a hundred rules, or different roles must own different rules |
| An expression language (JSONLogic, CEL) or scripting for conditions | `operations-spec/couriers.md` lists dispatch rules among the rule kinds that share one `ConditionBuilder` and says "No scripting": a rule that cannot be reproduced cannot be defended in a dispute, and it asks every rule kind to ship with a simulator. An arbitrary expression also cannot be linted for "never matches" or diffed field by field for the audit fact, and needs a sandbox. The closed vocabulary is what the simulator can explain | A condition operators genuinely need cannot be expressed, twice, and adding it to the vocabulary would be a release each time |
| Bind a provider to a zone on the zone itself (IA 3.6 "zone → courier-service binding") | One column, no new document. But it can express only zone, not source, prep time, time of day or fallback, and zone versions are immutable for tariff evidence. Zone stays a *condition* here | Never as the mechanism; a zone screen may offer a shortcut that writes a rule |
| Re-evaluate the rule document on every sourcing tick | An edit would silently reroute orders already promised, and "why did this order go to Noor" would depend on when it was asked. ADR 0030 says a durable decision persists the policy identity it used; ADR 0014 wants a selection reproducible from stored evidence | Operators need "apply this change to open orders" — then an explicit re-source action, audited, never a silent re-read |
| Live simultaneous booking with loser cancellation (IA 3.8) | ADR 0014 rejected it and its trigger is met for Yandex holds only. Noor's create dispatches a courier, so a cancelled loser is a paid cancel | A specific partner contract gives free, idempotent, immediate cancellation in writing; then a new ADR |
| Put the timing numbers into the rules document | One place to edit, but a lead time changed without the buffer that goes with it is "a mistake made by editing one of a pair" (`DeliverySourcingPolicy`'s own doc); mixing them makes every timing tweak a republish of routing | Operators find editing two documents confusing in practice |
| Put the unpaid-order window in the dispatch document | Dispatch plans do not exist for an unpaid order. The window acts on an `ordering` state and would make `fulfillment` own a rule about `PAYMENT_AUTHORIZING`, inverting the dependency `DeliveryPlanTrigger` exists to keep | Never |
| A first-class `Trip` aggregate with stop ordering for grouping | The courier app's accept and advance flow is unbuilt (row `3.9`'s four locked switches say so), ADR 0037's `ROAD` distance needs a routing binding that is unbuilt, and a trip without route ordering is a label. A `run_key` and a ranking bias deliver the behaviour the row asks for | A routing provider is bound and the courier app can accept a run |
| An ordered `lanes` list on the action (`["PARTNERS","FLEET"]`) instead of a fifth mode | Admits sequences the planner cannot honour (an empty list, a repeated lane, `MANUAL` in the middle), and `delivery_plans.sourcing_mode`, its CHECK, `SourcingRequest` and the planner's tests all key on a closed enum. Two lanes and `MANUAL` give exactly five valid plans (fleet only, partners only, either order of both, manual), and a name per plan is what the existing enum already is | A third lane exists (a marketplace-native courier, customer pickup) and lane order stops being a two-element question |
| Name providers in rules (`"YANDEX"`, `"NOOR"`) | `check_provider_branching` and ADR 0026 exist to stop exactly this; a renamed or second installation of one provider would need a release | Never |

## Consequences

### Positive

- An operator can answer the row's five questions from one screen, and the answer
  is the same code the runtime runs, so what the simulator shows is what happens.
- Every plan says which rule matched and under which document version; "why did
  this order go to Noor" is a lookup, not an investigation.
- `fulfillment.sourcing`, in force everywhere as provisional defaults with no way
  to change it, gets its writer; the `DISPATCH_BOARD` signal gets its
  automated-path producer.
- Behaviour is unchanged until someone edits: the built-in default rule is
  today's `FLEET_FIRST`, `LEAD`, binding-order-then-cheapest behaviour.
- The unpaid window stops being a deploy property.

### Negative

- Rule order matters. A broad rule above a narrow one shadows it, and a wrong set
  can send every order to the dearest partner or to nobody (`MANUAL`). Publish-time
  lint (unreachable rules, a rule that can never source) and the simulator reduce
  this; they do not remove it.
- A snapshot at plan creation means an edit does not help an order that is already
  stuck, which will surprise an operator who fixes a rule and watches the queue.
- A rule can name an installation that is later suspended or unbound at a branch;
  the ladder then skips it. The plan's stored decision must say "skipped: no
  active binding" or the skip is invisible.
- Grouping reads another order's drop-off. Both are ADR 0029 personal data; the
  read is a decrypt with a recorded purpose inside `fulfillment`, only a distance
  is stored, and it is one more purpose to defend.
- Grouping trades delivery speed for cost. The bound that protects the promise is
  a policy value, and a tenant can set it badly.
- `DeliveryOrderPort.DeliveryOrder` must carry the order's channel and zone,
  which is a change on `ordering`'s side of a boundary ADR 0029 made deliberate.
- `SourcingPlanner` is no longer untouched: `PARTNER_FIRST` is a behavioural change
  to the ADR 0014 planner whose handover-deadline rule differs from `FLEET_FIRST`'s,
  and a keying mistake there fails silently as an immediate `FLEET_BUDGET_SPENT`.
  The regression fixture over the four existing modes must not move.
- Two documents (`fulfillment.sourcing`, `fulfillment.dispatch_rules`) and a third
  in `ordering` on one screen.
- New capabilities are code-owned registry entries: each is a release (ADR 0025).

### Accepted trade-offs

- No live booking race, ever, without a new ADR: an operator wanting Delever's
  "call all taxis at once" gets quote racing and a possibly slower assignment.
- Grouping is a ranking bias, not a route: two orders in one run may be visited
  in either order.
- `CANCEL` on the unpaid window is unavailable until product answers ADR 0019.
- Whole-document publish: two administrators editing one scope race on `If-Match`,
  and the loser re-reads.

## Specification

### The document

```json
{
  "schema": 1,
  "rules": [
    {
      "id": "far-zone-yandex-first",
      "name": "Far zone, evenings: Yandex, then own fleet",
      "enabled": true,
      "when": {
        "sources":     ["WEB", "TELEGRAM"],
        "channelIds":  [],
        "zoneIds":     ["…"],
        "locationIds": [],
        "prepMinutes": { "min": 0, "max": 45 },
        "distanceMeters": { "min": 6000 },
        "localTime":   { "days": ["MON","TUE","WED","THU","FRI"], "from": "18:00", "to": "23:00" },
        "prepaid":     true
      },
      "then": {
        "mode": "PARTNER_FIRST",
        "partners": { "order": ["<installationId>", "<installationId>"], "exclude": [], "selection": "LADDER" },
        "dispatchAt": { "basis": "LEAD", "offsetSeconds": 0 },
        "grouping": null
      }
    }
  ],
  "default": {
    "mode": "FLEET_FIRST",
    "partners": { "order": [], "exclude": [], "selection": "CHEAPEST" },
    "dispatchAt": { "basis": "LEAD", "offsetSeconds": 0 },
    "grouping": null
  }
}
```

An omitted condition matches anything; every present condition must match. A rule
with `zoneIds` does not match an order with no zone evidence. `rules[].id` is
stable, operator-visible and unique in the document; it is what a plan records.
`partners.order` empty means the binding order ADR 0026 already returns. `mode` is
one of `FLEET_FIRST`, `FLEET_ONLY`, `PARTNER_ONLY`, `PARTNER_FIRST`, `MANUAL`
(Decision 9); the example above sends far-zone evening orders to a named partner
first and, if every listed partner refuses, to the fleet. The
action `grouping` is `{ "mergeRadiusMeters", "maxOrdersPerRun", "maxWaitSeconds" }`
or null. Every name in a condition is validated at publish: installations must be
this tenant's, category `DELIVERY`, and not archived; zones must exist and be
`DELIVERY`-role; sources must be members of ADR 0036's closed set.

### Evaluation

```text
facts    = (sourceSystemType, channelId, zoneId?, brandId, locationId,
            preparation, distanceMeters, confirmedAtLocal, prepaid)
rule     = first enabled rule in document order whose present conditions all match,
           else document.default
decision = { ruleId | "DEFAULT", mode, partnerLadder, selection, dispatchAt, grouping }
```

`DeliveryPlanningService.open` evaluates once and writes the decision. The
evaluator's inputs beyond the order are the branch's bound delivery installations
(to resolve `partners.order` and record skips) and the `fulfillment.sourcing`
document (for `LEAD` and the lane deadlines). `DeliverySourcingService.source`
then: applies `exclude` and `order` to the list `ShipmentBookingPort.partners`
returns; when `selection = CHEAPEST` and more than one candidate remains, quotes
and scores exactly as today (`QuoteScoring`, version unchanged); when `LADDER`,
keeps the given order and asks no quote. Everything after a decision is as ADR
0014 wrote it: single winner, uncertain attempts stop, exhaustion escalates and
never cancels the order.

Grouping, when enabled: in the fleet lane, `FleetCandidate` gains
`groupableWithMetres` (null when the courier has no un-picked-up plan from this
branch), and the ranking becomes *groupable within radius first, then the current
order*. The read that computes it is the only new decrypt purpose. A plan that
would wait past `min(maxWaitSeconds, latest_assignment_at − now)` stops waiting.

### Physical model (additive; numbers reserved by the wave that builds it)

```text
fulfillment.delivery_plans   (columns added)
  dispatch_policy_id uuid null, dispatch_policy_version integer null   -- a pair, like policy_id / policy_version
  dispatch_rule_id   varchar(64) null      -- null = the built-in default
  dispatch_decision  jsonb null            -- the resolved action and the skips; no coordinates, no names
fulfillment.shipments        (columns added)
  run_key uuid null, run_merge_distance_m integer null
fulfillment.delivery_plans   ck_plan_mode replaced (drop and re-add; V0054 is applied) to
                             admit PARTNER_FIRST; sourcing_mode is written from the decision
ordering: policy key ordering.payment_window   -- ADR 0030; no new table
```

`branch_zone` on `delivery_plans` is the location's IANA timezone, not a delivery
zone; this record never reads it as one. GRANTs follow `V0035`.

### APIs (ADR 0031)

```text
GET  /api/v1/operations/tenants/{tenantId}/dispatch-rules?brandId=&locationId=          ETag = document version
PUT  /api/v1/operations/tenants/{tenantId}/dispatch-rules?brandId=&locationId=          If-Match, Idempotency-Key, whole document
POST /api/v1/operations/tenants/{tenantId}/dispatch-rules/simulations                   no side effect; scope + optional draft + scenario or planId
GET  /api/v1/operations/tenants/{tenantId}/dispatch-rules/usage?days=                   plans per rule id, from delivery_plans
GET/PUT .../sourcing-policy?brandId=&locationId=                                        the timing document's missing writer
GET/PUT .../payment-window?brandId=&locationId=                                         ordering's policy
```

These mirror `GET`/`PUT .../courier-policy` in `OperationsCourierController`,
including its explicit per-scope authorization (a `@RequiresCapability` scope is
fixed per method, and brand and location are optional here). The simulator is a
`POST` because its body is large; it declares the read capability and is
`non-mutating`, so ADR 0031's idempotency rules do not apply to it.

### Capabilities (ADR 0025)

New `delivery.dispatch_rules.read` and `delivery.dispatch_rules.write`, bundled
into the roles that hold `DELIVERY_POLICY_*` today. Not `DELIVERY_POLICY_READ` /
`_WRITE`: their own documentation names them as the *courier compensation policy*
capability and says why that document got its own grant ("different objects read
by different people"); a rule that routes orders to a paying partner is a third
object, and the codebase already gives each policy family its own grant
(`ORDER_ACCEPTANCE_POLICY_MANAGE`, `LOYALTY_POLICY_MANAGE`,
`REFERRAL_POLICY_MANAGE`). The timing document reuses the new pair;
`ordering.payment_window` gets its own `order.payment-window.manage` rather than
borrowing the acceptance-policy grant.

### Audit, events, observability

`fulfillment.dispatch_rules.published` and `fulfillment.sourcing.published` as ADR
0027 facts whose `ChangeDocuments.diff` names rules added, removed, reordered and
edited by id. No new Kafka event: the plan is local state and its decision is
read by the board. The automated sourcing path (`DeliverySourcingRunner`) starts
publishing the `DISPATCH_BOARD` signal that only manual actions publish today.
Counters carry bounded labels only (matched-default versus matched-rule, decision
mode); per-rule volumes come from the `usage` read, not from metric labels.

### Testing

- Evaluator truth table: first-match order, shadowing, default, an omitted
  condition, a zone condition on an order with no zone, local-time windows across
  midnight in the branch timezone, and determinism (same facts, same decision).
- Publish validation: unknown installation, another tenant's installation, a
  non-`DELIVERY` installation, an unreachable rule, a basis and offset that leave
  no partner time, `holdBeforeConfirm` while unsupported, `CANCEL` on the payment
  window while unanswered.
- A plan records the rule and pinned version; editing the document afterwards
  changes nothing about that plan's later ticks.
- `PARTNER_FIRST` (`SourcingPlannerTests`, then `DeliverySourcingServiceTests`
  against the real journal): with a partner bound and couriers available, the first
  decision books the partner with `PARTNER_FIRST_MODE` and offers no courier; a
  refused partner moves to the next, and when all are refused the decision is
  `OfferInternal` with reason `PARTNERS_EXHAUSTED`; with no partner bound it is
  `OfferInternal` with `NO_PARTNER_CONFIGURED`; an uncertain partner attempt
  escalates `AWAITING_RECONCILIATION` and offers no courier; at a `now` later than
  `pickup_window_end − partnerLeadSeconds` the fleet lane still opens (the case a
  `handoverDeadline` keyed on `usesPartners()` gets wrong, seen failing first), and it
  ends at `latest_assignment_at` with `PROMISE_UNREACHABLE`; fleet and partners
  both exhausted escalate. `PARTNER_ONLY` is pinned unchanged: exhausted partners
  escalate and no courier is asked. The end-to-end case is the exit criterion: a
  far-zone evening order matches the rule, the plan records `PARTNER_FIRST` and the
  rule id, the partner refuses, a courier is offered.
- `LADDER` asks no quote; `CHEAPEST` is `QuoteScoring` unchanged; an installation
  with no active binding at the branch is skipped and the skip is recorded.
- Grouping: a courier with a nearby un-picked-up plan outranks an emptier one
  inside the radius and not outside it; the wait ends at the promise bound; the
  stored data holds a distance and no coordinate.
- With no document published, every plan's decision equals today's
  `SourcingPlanner` behaviour (a regression fixture over `DeliverySourcingTests`).
- The unpaid window: `FLAG_ONLY` matches today's sweep; the deploy property is only
  a fallback default.
- Cross-tenant reads and writes fail; capability declaration test.

## Rollout and rollback

Ship the writer and screen for `fulfillment.sourcing` first — it changes no
behaviour until someone publishes, and it is the smallest useful step. Then the
evaluator and simulator with only the built-in default in force, recording the
decision on every plan so the default's behaviour is proven equal on real orders
before any rule can differ. The `PARTNER_FIRST` planner change (Decision 9) ships with the evaluator and is
inert until a rule can select it. Then rule authoring for `LADDER` and `CHEAPEST`,
partner selection by source, zone and branch, with one tenant. Then dispatch
start bases. Grouping ships last and only after the pay treatment is decided.
Rollback is unpublishing: with no rule document the default applies, plans already
created keep their stored decision, and nothing about the sourcing journal or the
single-winner indexes changes.

## Implementation checklist

- [ ] Flyway: the `delivery_plans` and `shipments` columns above, granted.
- [ ] `DispatchRuleEvaluator` and the document types in `fulfillment.domain.sourcing`;
      the `PolicyKey` `fulfillment.dispatch_rules`; startup registry check.
- [ ] `DeliveryOrderPort.DeliveryOrder` gains channel id, channel system type and
      zone id (from the fee-resolution evidence).
- [ ] `DeliveryPlanningService.open` evaluates and stores the decision; bump
      `PickupPlan.CALCULATION_VERSION`.
- [ ] `DeliverySourcingService` applies `exclude`/`order`/`selection`.
- [ ] `SourcingMode.PARTNER_FIRST`; `SourcingPlanner` lane order and
      `handoverDeadline` keyed on "a partner lane follows the fleet lane";
      `SourcingDecision.PARTNER_FIRST_MODE`; the `ck_plan_mode` migration; the
      comment in `DeliverySourcingService.source` that quotes are taken "once the
      fleet lane has been conceded" made true of both orders.
- [ ] Publish-time validation and lint; the simulator endpoint; the `usage` read.
- [ ] `delivery.dispatch_rules.read` / `.write` and role bundles; the writer and
      screen for `fulfillment.sourcing`.
- [ ] `ordering.payment_window` policy key and its `order.payment-window.manage`
      capability; `OrderPaymentProcess` reads it in place of the deploy property.
- [ ] `DISPATCH_BOARD` signal from `DeliverySourcingRunner`.
- [ ] Console screen IA 3.8 on the shared `q-condition-builder` / `q-rule-list`
      components (gap-map row `X.25`) with the simulator panel; the read-only
      summary card at `settings.md` Card 3; ru / uz-latn / en strings.
- [ ] Grouping: `FleetCandidate` field, the ranking change, `run_key` — after the
      pay decision.
- [ ] Tests listed under Testing, each seen failing first.
- [ ] Update ADR 0014's status line to record that its "service zone" filter and
      sourcing-mode choice are now operator-authored.

## Exit criteria

An operator can publish a `PARTNER_FIRST` rule that sends far-zone evening orders
to a named partner first and, when that partner refuses, the fleet second, run the simulator on a real recent order and
see that rule match and why the earlier rules did not, publish it, and find on the
next such order's plan the rule id and the document version it ran under. Timing
numbers for a branch are editable in the console. With no rules published, every
order is sourced exactly as it is today. Gap-map row `3.8` can be marked `BUILT`
for the part the unpaid window and grouping do not block, with those two named as
their own rows.

## References

- ADR 0007, ADR 0014 (sourcing, single winner, verified partner capabilities,
  provider selection), ADR 0019 (checkout payment timing, open input), ADR 0025,
  ADR 0026 (bindings and their order), ADR 0027, ADR 0029, ADR 0030 (policy
  documents), ADR 0031, ADR 0036 (`system_type`), ADR 0037 (zones and fee
  evidence), ADR 0042 (dispatch gate, courier types), ADR 0045 (`DISPATCH_BOARD`)
- `platform/docs/operations-gap-map.md` rows `3.1`, `3.8`, `3.9`
- `platform/docs/frontend-information-architecture.md` 3.6, 3.8;
  `platform/docs/operations-spec/settings.md` Card 3 (Автоматизация);
  `platform/docs/operations-spec/couriers.md` (no scripting)
- `platform/docs/delever-parity-matrix.md` (multi-provider dispatch, courier order
  batching, order conditions)
- `DeliveryPlanningService`, `DeliverySourcingService`, `DeliverySourcingPolicy`,
  `DeliverySourcingPolicies`, `SourcingPlanner`, `QuoteScoring`, `PickupPlan`,
  `ShipmentBookingPort`, `ManualDispatchService`, `OrderPaymentProcess`,
  `V0054__delivery_plans_shipments_and_sourcing.sql`
