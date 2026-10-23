# ADR 0178: The pickup and handover are soft by default, a courier is not rated, and a rule needs a reader

- Decision status: Accepted — proposed by Claude (batch 21); accepted by the platform owner 2026-10-14
- Implementation status: Partial — built on the platform and in the operations console: the soft or
  hard pickup and handover gate (`courier.compensation` carries `pickupHandoverGate`, `SOFT` by
  default; `CourierDeliveryService` evaluates the position check once for `POST …/deliveries/{id}/pickup`,
  `…/handover` and the older `…/advance`; V0656 `fulfillment.courier_step_checks` keeps what each check
  came to; `DeliveryAccrualOrderCompletionTrigger` reads it for `geo_unverified`, so the
  `GEO_UNVERIFIED_RATE` basis reads a fact and not the constant `true` it read before; the policy page has
  the select with a consequence line), the refusal of a rule on a basis nothing reads (`POST …/adjustment-reasons`
  answers 422 for `ORDER_UNDELIVERED` and `ORDER_DAMAGED`), no rating on the roster, a courier's correction,
  end of engagement and archive (`OperationsCourierLifecycleController`), a courier's first sign-in and reset
  by text message (V0657 `fulfillment.courier_access_invitations`, `CourierAccessService`, the relay, the
  accept endpoints and the `/courier-access` page, the card on the courier record), the courier's photo
  (`q-media-uploader` in the register form and the record), the rule simulator
  (`POST …/adjustment-rule-simulations` and its panel on the rule form) and the deliveries list
  (`GET …/deliveries`, `/deliveries`). Not done: the reader behind `ORDER_UNDELIVERED` and `ORDER_DAMAGED`
  (see Open inputs: neither code exists in the exception list, no screen records one, and every engagement
  is `SELF_EMPLOYED` by constraint), a photo check for the `PHOTO` kind to report, a real SMS gateway
  account carrying a courier's link, which is the owner's, and the production `CourierAccessMessenger`
  over it, which is unwritten work and not an external fact (`docs/runbooks/external-connections/w3-delivery-maps.md`
  section 8; see the 2026-10-23 addition).
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: proposed by Claude (batch 21); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0029, ADR 0030, ADR 0031, ADR 0042, ADR 0045, ADR 0108, ADR 0116,
  ADR 0146, ADR 0159, ADR 0179
- Supersedes / Superseded by: — (refines ADR 0042 where it says the pickup and handover are soft by
  default, and answers ADR 0108's «by construction until a reader exists». ADR 0108 carries a dated
  status addition pointing here; ADR 0042's text is unchanged)
- Open inputs: each is closed on its proposed default if the owner accepts the record as written;
  those that name someone else stay with them.
  - **Which existing exception code, or which new one, means «undelivered» and «damaged»**
    (platform owner, with counsel). The owner's value reads the exception reason codes that already
    exist, recorded by the operator who closes the exception. The analysts found that V0054's closed
    list (`NO_PROVIDER`, `LATE_ASSIGNMENT`, `PROMISE_UNREACHABLE`, `AWAITING_RECONCILIATION`,
    `ABANDONED_HOLD`, `LATE_RESTAURANT`, `COURIER_NO_SHOW`, `ADDRESS_ISSUE`, and since then
    `PROVIDER_CANCEL_UNCERTAIN`, `PROVIDER_CANCEL_FAILED`) contains neither word, that only
    `ADDRESS_ISSUE` is ever raised, and that `JdbcDeliveryExceptionStore.resolve` has no caller, so no
    operator surface records an outcome at close. Proposed default: nothing is invented to fill the
    gap; the two bases stay manual-only and the API refuses a rule on them (Decision 6) until the owner
    names the codes and a screen records them.
  - **How a courier can be employed at all** (platform owner, counsel). Decision 6 scores only employed
    couriers. `courier_engagements.engagement_type` exists and V0040's `ck_engagement_type` admits only
    `SELF_EMPLOYED`, so today no courier could be scored on these two bases and a reader would read
    nothing. Proposed default: the constraint is widened with the reader, not before, after counsel says
    what an employed courier changes (V0040: outcomes never behaviour), and a courier with no value is
    not scored.
  - **Whether a location may want a photo at the handover** (operations). The courier client's
    contract names a `PHOTO` check; no policy asks for one and no endpoint takes one. Proposed
    default: `PHOTO` is reported by no response until a policy asks.
  - **The SMS account and sender that carry a courier's first sign-in link** (platform owner,
    business; ADR 0146's open input). Built against a fake; see the runbook.

**To accept as written:** say «accept 0178». Every open input above is then closed on its proposed
default.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction
"accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner
chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

Three questions about couriers had no answer on the record, and one had an answer nobody built.

ADR 0042 says «`PICKED_UP` and `DELIVERED` are soft by default and record `GEO_UNVERIFIED`: a hard
gate strands a courier in a stairwell with a customer waiting, and the workaround is marking delivered
from the street, which yields worse data and a worse delivery. Either hardens per location through ADR
0030.» Batch 18 built the position checks the other way round: every step refused, in every location,
with nothing recorded. couriers.md §16 said so («is not a setting on this policy document and is not
built»). The accrual's `geo_unverified` flag was the constant `true` for every delivery, because
nothing recorded the facts it should have read, so a bonus or penalty rule on `GEO_UNVERIFIED_RATE`
read a hundred per cent for every courier.

The original console had a courier's `rating` and `rating_count`; couriers.md §18 carried it forward
as «stays read-only until reviews have an ADR», and the information architecture (row 3.3) still
promises «online status and rating (read-only)». ADR 0159 keeps a courier out of review data by
default, and `platform/intent/0004` says a courier rating needs the owner and counsel.

ADR 0108 made `ORDER_UNDELIVERED` and `ORDER_DAMAGED` manual-only «by construction until a reader
exists», and its text, couriers.md §11 and the gap map each described the reader as a thing to be
written. The rule API did not agree: it stored a rule on either basis, and the rule then never fired.

## Decision

1. **The pickup and the handover are held by a mode, `SOFT` unless a location says `HARD`.**
   `courier.compensation` gains `pickupHandoverGate`; a document without it reads `SOFT`, which is
   ADR 0042's default and what every stored document means. Under `SOFT` a position check that fails
   is recorded as failed and the step goes ahead; under `HARD` it refuses the step. The mode is set at
   tenant, brand or location through ADR 0030 like every other field of the document.
2. **Only the position check softens.** The cash step (`postDeliveryPaymentCheckRequired`) is the
   tenant's own switch for a settlement invariant and stays a 422 in both modes; a `PHOTO` check is in
   the contract's vocabulary and nothing asks for one. Accepting an offer and arriving at the branch
   are hard whenever the GPS switch is on, as ADR 0042 and couriers.md §16 say.
3. **A refusal under `HARD` is `409 GATE_FAILED`** on `POST …/deliveries/{id}/pickup` and `…/handover`,
   the two endpoints the courier client's contract names, with the failed check kinds as the problem
   detail and `failedChecks` and `reason` as data. Under `SOFT` the same calls answer 200 with
   `checks: [{kind, outcome}]` for each check the location asks for. The older
   `POST …/deliveries/{id}/advance` takes the same steps under the same mode and keeps its older
   `422` and `reason` under `HARD`, so a client written to it still branches.
4. **What each check came to is kept**, per shipment and step, in `fulfillment.courier_step_checks`
   (V0656), written in the transaction that moves the shipment. The accrual's `geo_unverified` is
   *not* verified unless the pickup and the handover each recorded a passed position check; an order
   an operator completed by hand, or a location that never asked for the check, is unverified.
   `GEO_UNVERIFIED_RATE` reads that flag.
5. **The platform shows no courier rating** (owner's decision **D5**: decline for v1). ADR 0159 keeps
   couriers out of review data; the information architecture's phrase «online status and rating»
   loses «rating»; couriers.md §5/§18/§19(2) lose the read-only rating. Nothing in the roster, the
   record or a response carries a score of a courier by customers. The on-time percentage in
   couriers.md §5 is not a rating: it is a count of the courier's own recorded deliveries, with a late
   kitchen excluded, and stays a column of the list.
6. **`ORDER_UNDELIVERED` and `ORDER_DAMAGED` read the delivery-exception reason codes that already
   exist, recorded by the operator who closes the exception; only employed couriers are scored; the
   API refuses `rule_*` fields on a basis with no evaluator reader** (owner's decision **D6**).
   Until a reader exists the third clause is what ships: `POST …/adjustment-reasons` answers
   `422 UNPROCESSABLE_STATE`, reason `BASIS_HAS_NO_EVALUATOR_READER`, to a request that gives
   either basis any rule field, and stores nothing. The first two clauses are conditions on the
   reader that will replace the guard, and the Open inputs say what is missing before that can be
   written: the codes are not in the list and no screen records an outcome.

### Implementation decisions taken in this wave

These are the builder's, taken at the smallest default, and are here so a reviewer can disagree with them.

7. **A courier's first sign-in is a link texted to their phone, never a credential the console sets or
   shows** (couriers.md §18). A manager with `courier.engagement.manage` asks for an `INVITE` or a
   `RESET`; a relay texts a one-time link, valid 72 hours, whose token exists only in the text (the
   platform keeps its SHA-256); the courier opens it, chooses a password and the identity provider sets it
   (`StaffAccounts.setPassword`, then `logoutEverywhere`). One live invitation per courier, a newer one
   voids the older, a second request inside a minute is refused. The accept page is a route of the
   operations console for now (`/courier-access#token=…`, the token in the fragment) and moves to the
   courier client by changing the link's origin. The text leaves through `CourierAccessMessenger`, a
   port with no default implementation: until the owner connects the SMS gateway's `COURIER` purpose
   (ADR 0146) an invitation waits with `SMS_NOT_CONNECTED` and the console says so. Only the `local`
   profile has a fake.
8. **A courier is corrected, has their engagement ended and is archived by a manager, and each is
   guarded.** `PUT …/couriers/{id}` changes the display reference or the type under `expectedVersion`;
   `POST …/engagement-ending` and `…/archival` are refused with a stable reason while the courier is on
   an open shift or carrying a delivery, and an archive also while the balance is not zero. Archiving
   ends the engagement in the same step. Nothing is deleted.
9. **A bonus or penalty rule can be previewed before it is saved.** `POST …/adjustment-rule-simulations`
   replays the evaluator's own arithmetic (the same static methods it posts from) over shifts that closed
   or settlement periods that ended in the last 1 to 31 days, writes nothing, and names the couriers by
   display reference with the sum they would have lost. A basis nothing reads is refused as the rule API
   refuses it.
10. **The deliveries list is the plan, its shipment and the order's non-PII facts, newest first, one row
    per plan, with the cost at its basis and the cash to collect** (ADR 0164 Decision 6, slice two). Its
    scope follows its filters (ADR 0144). It is built now, ahead of the first courier-service tenant, on the
    owner's instruction for this batch; ADR 0164's own condition was that tenant, and its decision is not
    edited.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep the gates hard in every location (what batch 18 built) | ADR 0042 chose soft by default for a reason that has not changed: a courier who cannot be placed marks the step from the street, and the data is worse. It also left the accrual unable to say anything but «unverified» | A tenant with a measured pattern of marking delivered from the street asks for `HARD`; the mode is the lever and needs no new decision |
| Soften the cash step too | The amount a courier hands in is the settlement's, and the tenant who switched the check on said the order may not close without it. Softening it would turn an explicit tenant choice into a default that waives it | A tenant asks for a cash step that warns and does not stop; it would be its own policy field |
| Persist the outcome as columns on `fulfillment.shipments` | The shipment is fulfillment's aggregate; its port, its adapter and `DeliveryCompletionPort` would each carry a courier concept, and courier would read fulfillment's table for a courier fact. A sibling table keyed by the shipment keeps the fact where it is interpreted | The check outcomes are wanted on the dispatcher's board and a join per row costs more than a column |
| Remove `…/advance`'s pickup and handover steps now that the dedicated endpoints exist | No client is deployed to either, but the old door has an audited, tested contract, and removing it is a breaking change with nothing gained | The courier client ships and no other caller of `…/advance` for those two steps remains |
| Show a courier rating from reviews | ADR 0159's default, `platform/intent/0004`, and ADR 0042's reclassification risk (a self-employed courier scored on how they conduct themselves) all point the same way; no source of the data exists on either side | The owner and counsel decide a rating, and an ADR says what it is made of and who sees it |
| Build the `ORDER_UNDELIVERED` / `ORDER_DAMAGED` reader now, mapping `COURIER_NO_SHOW` and `ADDRESS_ISSUE` onto them | `ADDRESS_ISSUE` is not the courier's fault and `COURIER_NO_SHOW` is not «undelivered»; a mapping would turn a labelling guess into a deduction from someone's pay (V0040) | The owner names the codes and a screen records them |
| Leave the rule API as it was | A rule that is stored and never fires reads as protection on a payday it does not give | — |

## Consequences

### Positive

- A courier who cannot be placed finishes the delivery and is told the position was not confirmed;
  the data records that, which is better than a delivery marked from the street.
- `GEO_UNVERIFIED_RATE` reads what happened. A tenant that has the GPS switch off sees every delivery
  as unverified, which is true and which the rule simulator makes visible before a rule is activated.
- A direct caller can no longer define a rule that looks like protection and does nothing.
- The mode is one field and one ADR 0030 resolution; nothing else about the policy page changed.

### Negative

- Two doors take the same two steps and answer a hard refusal differently (`409` on the new
  endpoints, `422` on `…/advance`). Both exist until the courier client is the only caller.
- Under `SOFT`, which is the default, a courier can pick up from the wrong place and the step happens.
  The record is the control: a sustained unverified rate is a penalty-rule condition applied through
  approval (ADR 0042), which only works once the GPS switch is on.
- A tenant that turned the GPS switch on under batch 18 and relied on its refusing the pickup now has
  a `SOFT` pickup until it sets `HARD`. The switch's consequence line says so.
- `PHOTO` is a kind the contract names that no response carries; a client that expects a photo
  outcome gets none.
- `ORDER_UNDELIVERED` and `ORDER_DAMAGED` stay a manager's manual entry, which is the state they were
  in; D6's first two clauses are promises about a reader nobody has written.

### Accepted trade-offs

- The cash step does not soften, so the two modes differ by one check only. If a tenant wants the
  cash step to warn, that is a second field.
- A step already taken answers with what it came to the first time. A courier who taps pickup a
  second time from the right place does not improve the record.

## Specification

**Policy.** `CourierCompensationPolicy.pickupHandoverGate` (`GateMode`: `SOFT`, `HARD`, nullable,
read as `SOFT`). `GET`/`PUT …/courier-policy` carry `pickupHandoverGate` (the response always names the
effective mode; a write that omits it stores none). An unknown value is `400 VALIDATION_FAILED`.

**Checks.** `StepCheck(kind GEO|PHOTO|PAYMENT, outcome PASSED|FAILED|SKIPPED, reason)`.
`DeliveryGate.geoCheck`: `SKIPPED` when the GPS switch is off; `PASSED` when the courier is within the
status-change radius of the branch (pickup) or the customer's door (handover) with an accurate enough
fix; `FAILED` with the `GateRefusal` otherwise, including a missing position, a coarse one, and a place
with no coordinate. `DeliveryGate.paymentStepCheck`: `SKIPPED` unless the tenant requires the
confirmation and cash is due, then `PASSED` once confirmed and `FAILED` before.

**Endpoints** (`/api/v1/courier/tenants/{t}/brands/{b}/locations/{l}`, capability
`courier.delivery.advance`, `If-Match`, `Idempotency-Key`; body `{}` or `{position}`):

| Call | `SOFT` | `HARD` |
|---|---|---|
| `POST …/deliveries/{id}/pickup` | 200, delivery with `checks` (the checks the location asks for) | position check failed: `409 GATE_FAILED`, detail `GEO`, `failedChecks`, `reason`; nothing changes, nothing is recorded |
| `POST …/deliveries/{id}/handover` | as above; cash unconfirmed is `422 PAYMENT_CONFIRMATION_REQUIRED` in both modes | as above |
| `POST …/deliveries/{id}/advance` (`PICKED_UP`, `DELIVERED`) | 200, records the same | position check failed: `422` with the `reason`, as before |
| `POST …/deliveries/{id}/advance` (`PICKUP_PENDING`) | held whenever the GPS switch is on, in both modes | |

A step already taken answers 200 with the checks it recorded. A check the location does not ask for is
absent from `checks` (a non-passed entry always means «asked for and not confirmed») and is recorded as
`SKIPPED`.

**Record.** `fulfillment.courier_step_checks(tenant_id, shipment_id, courier_id, step PICKED_UP|DELIVERED,
kind, outcome, reason_code, gate_mode, policy_id, policy_version, recorded_at)`, unique on
`(tenant_id, shipment_id, step, kind)`, insert and select only. `reason_code` is the name of a refusal;
no coordinate, accuracy or distance is stored, logged or put in an audit fact (ADR 0029, ADR 0045).
`courier.delivery.advanced` records `geoCheck` and `gateMode`, not a position.

**Accrual.** `JdbcCourierStepCheckStore.geoVerified` is true only when both steps recorded a passed
`GEO` check; `DeliveryAccrualOrderCompletionTrigger` writes `geo_unverified = NOT geoVerified`.

**Rule API.** `AdjustmentRuleEvaluator.hasReader(basis)` is true for `DELIVERED_VOLUME`,
`LATE_DELIVERY`, `ON_TIME_RATE`, `GEO_UNVERIFIED_RATE`, `CASH_VARIANCE`. A request giving any `rule*`
field on another basis is `422 UNPROCESSABLE_STATE` with `reason: BASIS_HAS_NO_EVALUATOR_READER`.

**Rating.** No endpoint, response or screen carries one.

**Courier access** (all under `/api/v1/operations`). `POST …/tenants/{t}/couriers/{id}/access-invitations`
`{kind: INVITE|RESET, locale}` (`courier.engagement.manage`) answers 202 and queues; `GET …/couriers/{id}/access`
(`courier.read`) answers `{state, kind, requestedAt, sentAt, expiresAt, acceptedAt, lastErrorCode,
smsConnected}` and never a number or a link; `POST /courier-access/inspect` and `/courier-access/accept`
(unauthenticated, the token in the body, ten a minute per caller address) read and spend the link. States:
`NONE`, `QUEUED`, `SENT`, `EXPIRED`, `ACCEPTED`, `VOIDED`, `FAILED`; failure codes: `SMS_NOT_CONNECTED`,
`NO_PHONE_NUMBER`, `COURIER_GONE`, `IDENTITY_UNAVAILABLE`, `SMS_UNAVAILABLE` and whatever the gateway's
refusal code is (`SMS_PURPOSE_NOT_PERMITTED`). Audit facts (`courier.access.*`) name the invitation and never
the number, the token or the password.

**Lifecycle.** `PUT …/couriers/{id}` `{expectedVersion, displayReference?, courierTypeId?, reason}`;
`POST …/couriers/{id}/engagement-ending` and `…/archival` `{reason}`; refusals `422 UNPROCESSABLE_STATE` with
`reason` `COURIER_HAS_OPEN_SHIFT`, `COURIER_HAS_ACTIVE_DELIVERIES`, `COURIER_BALANCE_NOT_ZERO`. The roster
carries each courier's `version`.

**Simulation.** `POST …/tenants/{t}/adjustment-rule-simulations` (`courier.adjustment.reason.manage`, no
`Idempotency-Key`, nothing written) `{outcomeBasis, ruleComparator, ruleThreshold, ruleWindow,
ruleAmountMinor, ruleCurrency, lookbackDays?, locationId?}` answers `{windowsReplayed, couriersHit,
totalAmountMinor, truncated, includesOpenPeriods, hits[]}`.

**Deliveries.** `GET …/tenants/{t}/deliveries?brandId&locationId*&status*&courierId&from&to&cursor&limit`
(`delivery.plan.read` at the scope the filters name) answers a keyset page of rows; see
`OperationsDeliveryListController`.

## Rollout and rollback

Merge. Existing tenants read `SOFT`; the only visible change is that a position check the courier
fails no longer blocks the pickup or handover and is recorded. Setting `pickupHandoverGate: HARD` at a
location restores the refusal. The migration only adds a table; rolling back the code leaves it unread.

## Implementation checklist

- [x] `pickupHandoverGate` on the policy document, the API and the policy page, in three languages
- [x] One gate evaluation behind `pickup`, `handover` and `advance`; `409 GATE_FAILED`
- [x] V0656 and the record of each check
- [x] `GEO_UNVERIFIED_RATE` reads the record
- [x] The rule API refuses a rule on a basis with no reader
- [x] No rating anywhere; IA row 3.3 and couriers.md say so
- [x] A courier's first sign-in and reset by text message, against a fake gateway
- [x] Correct, end and archive a courier; the photo
- [x] The rule simulator
- [x] The deliveries list and its console screen
- [ ] A reader for `ORDER_UNDELIVERED` and `ORDER_DAMAGED` (blocked on the open inputs)
- [ ] A `PHOTO` check (blocked on a policy that asks for one)

## Exit criteria

A location left alone lets a courier who is out of place finish a delivery and the accrual says the
position was unverified; the same location set to `HARD` answers `409 GATE_FAILED` and the shipment
does not move; a rule on `GEO_UNVERIFIED_RATE` counts the deliveries whose pickup and handover were
both measured and passed as verified; a rule on `ORDER_DAMAGED` is refused at the API.

## Status addition (2026-10-23)

Three corrections from the review of batch 21's delivery wave; this record's Decision, Alternatives and
Consequences are unchanged.

- **The missing SMS adapter is not waiting on the owner's account alone.** The Implementation status line
  and the open input above filed the whole gap under the owner. The account and the approved sender are
  external; `CourierAccessMessenger`'s production implementation is not, and it is not written because
  three things it needs are decided nowhere: which brand's SMS installation texts a courier
  (`NotificationGateway` resolves an account through a brand or location binding; the courier row, the
  invitation and `AccessMessage` carry no brand), which route carries a one-time bearer link (the ADR 0182
  courier path persists an intent with its variables on a row, while Decision 7 keeps the token out of every
  table), and what «connected» means once a bean always exists (`smsConnected()` is bean presence today;
  readiness is per brand). Decision 7 stands as written and is what the code does: with no bean an
  invitation waits as `SMS_NOT_CONNECTED`. The runbook row says the same.
- **Decision 8 now voids the sign-in link when the courier leaves.** V0657 defines `VOIDED` as «replaced by
  a newer invitation, or the courier left, before it was spent», but only the first was implemented.
  `CourierLifecycleService.endEngagement` and `.archive` now call `JdbcCourierAccessStore.voidLive` in their
  own transaction (and the audit fact carries `accessInvitationsVoided`), and `CourierAccessService.accept`
  and `CourierAccessRelay` require a live engagement as `issue` does, so an engagement that ended some other
  way is covered too. Proved by `CourierAccessEndpointTests` through the lifecycle endpoints.
- **Decision 9's preview is tenant-wide.** A reason is a tenant-level registry row (it has no location
  column) that `AdjustmentRuleEvaluator` reads at every branch's shift close, so the rule form no longer
  sends `locationId` and the replay covers every branch; the optional `locationId` stays on the endpoint for
  a question about one branch. `truncated` now also says that more shifts or periods closed in the lookback
  than the 5,000 newest the replay holds (`CourierRuleSimulator.MAX_WINDOWS`), so a «nobody» from a cut
  answer is not read as safe.

## References

ADR 0042, ADR 0108, ADR 0159, ADR 0179; couriers.md §5, §11, §16, §18, §19;
`platform/intent/0004-rating-what-happened`;
`docs/runbooks/external-connections/w3-delivery-maps.md`.
