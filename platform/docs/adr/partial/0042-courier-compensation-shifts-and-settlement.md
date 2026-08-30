# ADR 0042: Courier compensation, shifts, and settlement

- Decision status: Accepted
- Implementation status: Partial — V0040 creates the eighteen `fulfillment.courier_*`, `delivery_cost_lines` and `partner_delivery_invoice*` tables with the `INSERT`/`SELECT`-only grants, and the `courier` module implements engagements with ADR 0029-protected registration capture and manual verification (whose evidence media reference is scoped to the attesting tenant by V0069's composite foreign key and by `CourierEngagementService`'s `MediaAvailability` resolution -- before both, one tenant could cite another tenant's private registration scan and the verify endpoint answered whether any given asset id existed), the expiry sweeper and `SUSPENDED_COMPLIANCE` gate (`RegistrationComplianceSweeper`, `CourierDispatchGate`), rate-card validation and approved activation, the deterministic accrual calculator with on-time and pickup-window attribution, the shift lifecycle with breaks and auto-close, the append-only ledger with period stamping, cash declaration, confirmation and variance, rule and manual adjustments under ADR 0027 approval, delivery cost lines with partner invoice import and matching, statement generation and hashing with the no-tax-language rule, payout authorisation, the 30-day confirmation-point deletion, and the operations and courier APIs under ADR 0025 capabilities (`OperationsCourierController`, `CourierShiftController`), covered by `CourierCompensationTests`. **The dispatch half now exists**, which every previous revision of this Implementation status line recorded as missing. `courier.infrastructure.dispatch.InternalFleetAdapter` implements `fulfillment.api.InternalFleetPort` — `JdbcCourierShiftStore.fleetOnShiftAt` enumerates the couriers on an OPEN shift at the branch with their type's `offer_ttl_seconds`, `max_concurrent_assignments` and their deliveries this shift (V0085 indexes that count), `CourierDispatchGate` decides eligibility and is now called in production rather than only from a test, `InternalFleetPort.ActiveAssignments` supplies how loaded each courier is from ADR 0014's own `fulfillment.shipments`, and `telemetry.api.CourierProximityPort` supplies metres from the branch without a coordinate crossing the boundary. `courier.infrastructure.dispatch.CourierShiftAdapter` implements `telemetry.api.CourierShiftPort`, so ADR 0045's duty sessions now open from a shift the courier opened himself and carry the registration validity date as evidence; both adapters are covered by `CourierDispatchPortTests`. Still not built, and this is now the gap that matters: **nothing in production calls `CourierAccrualService.recordDelivery`** — only the test does — so an in-house delivery can now be offered and accepted, but no earning is accrued outside a test; what is missing is the acceptance-to-delivery path, not the dispatch seam. `RegistrationComplianceSweeper` and `ConfirmationPointRetentionJob` still carry no `@Scheduled` annotation, so nothing runs them unattended. Also not built: the conditional-update concurrency ceiling on `max_concurrent_assignments` — the candidate's `hasCapacity()` is a pre-filter and ADR 0014 still owns the compare-and-set that would enforce it — the roster that would let a fleet be enumerated under `ADVISORY` or `OFF` enforcement rather than from open shifts alone, and the courier half of the per-attempt snapshot — V0054's attempt carries `policy_id`, `policy_version` and `shift_enforcement_mode`, `JdbcAssignmentStore` writes the first two, and the enforcement mode is null on every row; roster entries and the ADR 0036 capacity signal; the fourteen ADR 0032 events; `legal_entity_id` resolution — ADR 0038's registry now exists (V0053, `tenancy.api.LegalEntityDirectory`) but `UnresolvedLegalEntityResolver` still answers empty, so the statement's per-entity subtotal is still a single `null` bucket; and the shadow-accrual comparison. See [Implementation checklist](#implementation-checklist)
- Date proposed: 2026-08-21
- Date decided: 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture), product (in-house fleet scope, customer visibility of courier position), finance (settlement and payout calendar), operations (dispatch and shift governance)
- Depends on: ADR 0010, ADR 0013, ADR 0014, ADR 0018, ADR 0020, ADR 0025, ADR 0027, ADR 0029, ADR 0030, ADR 0036, ADR 0037, ADR 0038
- Supersedes / Superseded by: —
- Open inputs: five, **none structural** — see [Open inputs and who answers them](#open-inputs-and-who-answers-them)

## Context

The classification question that kept this ADR `Proposed` is answered. **Couriers
on an in-house fleet are engaged as registered self-employed persons
(самозанятые).** Each courier holds their own registration and invoices the
tenant for work performed. Qoida does not withhold, does not file, and is not a
payroll system of record.

That answer removes one subsystem and adds another. It removes withholding and
net pay from the settlement statement, which the previous draft hedged across
three employment models and could therefore specify none of. It adds
**registration validity as a platform concern**, because a courier whose
registration lapses turns a compliant arrangement into an undeclared one, and the
lapse surfaces as nothing at all — no error, no failed call, no unhappy customer
— unless something checks. The platform is the only party holding the courier's
work history, dispatch decisions, and payment record together, so it is the only
party positioned to notice.

A second answer settles the shape of the fleet. **There is an in-house fleet as
well as Yandex Delivery and Noor.** ADR 0014's `INTERNAL_COURIER` sourcing mode
has a subject, and delivery cost now arrives by two paths that a report must be
able to add without adding them naively. The operations prototype already made
the product call and this ADR honours it: one dispatch surface, one queue, one
sort order, with the carrier as a column rather than a tab.

A third answer narrows the privacy surface more than anything else here.
**Customers do not see a courier's live position** — status milestones only.
Courier location is still processed, because an in-house fleet cannot be
dispatched without it, but it is never disclosed to a customer. The hard case —
showing one identifiable person's live position to another — does not arise, and
what remains is an operational processing purpose between a tenant and a courier
who has agreed to be dispatched.

The forces that produced this ADR in the first place have not changed. Delever's
Personnel section is a courier payroll engine that supports two opposite money
flows and reconciles neither: the courier balance is both a liability the tenant
owes and a prepaid float the courier tops up. Holding both produces a courier
simultaneously owed wages and in debt for commission, and no single figure
anybody can pay. Delever also coupled courier earnings to the customer delivery
charge and shipped a correction after the two diverged and caused payout
disputes. And cash on delivery makes the courier a collection agent, so the cash
he holds and the money he is owed are one person's position.

## Decision

**Couriers are engaged as registered self-employed persons, and a settlement
statement carries gross only.** No withholding line, no net-of-tax line, no
payslip. The statement states what the tenant owes for work performed and is the
document the courier's own invoice is checked against. `engagement_type` exists
on the courier record with exactly one implemented value, `SELF_EMPLOYED`;
`EMPLOYEE` is named and deliberately not implemented, so a tenant that employs
its couriers is told it is out of scope rather than silently mis-served.

**Registration validity is captured, verified, and enforced at dispatch.** A
courier's registration identifier, its attested validity, the evidence document,
and the actor who verified it are held on the engagement record. A lapsed
registration suspends dispatch: the courier receives no new offer and cannot open
a shift. Work already accepted is finished, and earnings already accrued are
never reversed — the work was done and the money is owed. The compliance lever is
refusing new work, not withholding pay.

**Delivery cost has two paths and one grain.** An in-house delivery costs what
the courier accrued for it; a partner delivery costs what Noor or Yandex
invoiced for it. Both are recorded as `delivery_cost_lines` against the shipment,
each carrying its path and its **basis** — `ACCRUED`, `INVOICED`, or `SETTLED`.
A total may only be taken across lines of a stated basis, and every delivery-cost
figure names the basis it was taken at. Adding an invoiced partner figure to an
open internal accrual and presenting one number is the specific error this rule
exists to prevent.

**The `fulfillment` module owns one append-only courier ledger per in-house
courier, and the balance on it is what the tenant owes that courier.** Entries
are signed integer whole som per ADR 0018. Earnings, bonuses, and confirmed cash
handovers are positive; cash collected, penalties, and payouts are negative.
There is one balance, not a wage balance and a cash balance — a courier holding
900 000 som of the tenant's cash while being owed 400 000 som is one net
position, and a design reporting two is wrong before it is inconvenient. **A
prepaid courier float is rejected: Qoida does not take deposits from workers**,
and with ADR 0046 settling loyalty as points only, a courier float would be the
sole customer-or-worker-funded balance in the entire platform.

**Courier earnings never derive from the customer delivery charge.** They resolve
from a versioned rate card scoped by brand, location, and courier type,
snapshotted onto the assignment at acceptance, while the charge resolves in the
ADR 0018 quote under ADR 0037. Neither reads the other. A zero delivery charge
does not reduce an accrual: a courier who drove eleven kilometres is owed for
eleven kilometres regardless of what the basket paid. The gap is margin, and
where it is negative it is an ADR 0013 `DELIVERY_COST_SUBSIDY`.

**A courier opens and closes their own shift, and takes their own breaks.** A
manager may close a shift, approve hours, and suspend an engagement; a manager
may not open a shift on a courier's behalf and may not end a break. A person who
chooses their own hours is engaged differently from a person on a rota, and a
manager who can create shift state can create paid hours.

**The on-time outcome is computed once, at delivery, from values snapshotted at
acceptance.** No report recomputes it, and no report recomputes an amount:
reports aggregate stored facts. Two screens showing two different «К оплате» for
one courier is the failure this rule prevents.

**Qoida computes, approves, and records the payout; it does not move the money.**
A large share of courier pay here is settled by the courier keeping cash he
already collected, and that becomes a real settlement entry rather than an
off-books arrangement. Disbursement is a later ADR 0013 capability; the payout
record is its seam.

This closes ADR 0014's "courier bonus and settlement ownership" and its
"internal-courier scope" open inputs. ADR 0014's `courier_availability` stays a
dispatch concern and must never become the source of paid hours.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Model couriers as employees and build payroll: withholding, net pay, payslips, filing | Builds a tax-filing obligation into a commerce platform for an engagement model no tenant on this platform uses. Withholding is also not a schema detail — it is a legal duty with penalties for getting it wrong, and it would have to be right in a market whose rules nobody here owns | A tenant engages couriers as employees. That is an `EMPLOYEE` engagement type and a payroll ADR of its own, not a widened statement. The statement's gross figure is the input either way |
| Keep hedging: build the statement with `withholding_minor` and `net_minor` nullable, and decide later | A nullable withholding column is a decision that looks like flexibility. Every report, export, and screen would have to handle both shapes forever, and the first accountant to see an empty withholding line will ask who is responsible for it | Never. If employment arrives it arrives as a second engagement type with its own statement, not as nulls on this one |
| Take the courier's word on registration status and model nothing | Costs nothing to build and the failure is invisible: an expired registration produces no error, no failed call, and no complaint. It is discovered by an inspector, and by then every delivery since the lapse is on the record | Never. The reason this is a platform concern is precisely that no other party sees it |
| On lapse, freeze the courier's accrued balance until they re-register | Tempting as leverage and wrong as an instrument: it withholds payment for work already performed to enforce a compliance rule, which is a dispute the tenant loses and probably may not do at all | Never. The correct lever is refusing new work, which is prospective and proportionate |
| Prepaid courier float: the courier tops up, the platform debits commission per delivery | Qoida would hold worker money — a deposit-like arrangement with unassessed regulatory weight — and a courier who cannot top up cannot work, so the platform gains a debt-collection problem in place of a payroll one. It also inverts the sign of every report. ADR 0046 has now removed customer-funded balances entirely, so this would be the platform's only one | A tenant runs couriers as independent businesses paying for order flow and legal confirms the arrangement. The signed ledger can express it; the product policy permitting it is what is refused |
| Derive courier earnings as a share of the customer delivery charge | One number, no second rate card. Rejected because the two must diverge: a free-delivery promotion would pay the courier nothing, and a distant order priced flat would underpay the person who drove it. Delever coupled them and had to correct it after payout disputes | Never. A tenant wanting them equal configures a matching rate card — a choice on record rather than a hidden coupling |
| One delivery-cost number summing in-house accruals and partner invoices | The two are recognised at different instants, have different mutability, and rest on different tax documents. A same-day report under-states partner cost and then jumps weeks later when the invoice lands, and nobody can tell whether the jump is a cost increase or an arrival | Partner invoices arrive on a fixed cadence aligned to the settlement calendar, and both paths can be presented at an `INVOICED` basis. Even then the figure carries its basis label |
| Record partner delivery cost as ledger entries against a synthetic "partner courier" | Reuses one table and destroys its invariants. A partner is a supplier with an invoice, a credit note, and a dispute process; it has no cash bag, no shift, no single balance a person reads on their phone, and no statement it disputes | Never. Two things that are both "what delivery cost" are not therefore the same object |
| Manager-controlled shift state — a manager opens, closes, and ends breaks | Simplest for a dispatcher during service. Rejected on two grounds: directing when a self-employed person works is the fact pattern that reclassifies the engagement, and a manager who can create shift state can create paid hours for someone who was at home | An `EMPLOYEE` engagement type exists. Manager close stays available today because ending service and sending someone home is a safety and premises matter, not a direction to work |
| Pay hours from the roster a manager authored | Simplest, and the roster exists anyway for planning. Rejected because it pays a courier who did not show up, and the person who wrote the roster is usually the person approving the pay. Under self-employment it is worse: it pays for hours nobody was obliged to work | Never for pay. The roster stays as an offer of work and a variance baseline |
| Treat the shift as a report only, with no gate on assignment | Less friction, and dispatch already filters on availability. Rejected because availability is the courier's own toggle and a shift is the courier's declaration that they are working, which the tenant needs before it owes for an assignment | A tenant runs a pure gig model where anyone eligible may take anything — that is the `OFF` enforcement mode, chosen per location, not the default |
| Pay per-kilometre from the GPS track length | Closest to what was driven. Rejected because it pays for detours, for circling the block, and for drift in Tashkent's courtyards, and neither party can see the figure before the trip | A routing provider exposes a settled actual-distance figure with a dispute path and the app captures a track of provable quality |
| Model the courier as a payee inside ADR 0013 | Payments owns provider-executed movement against orders, with refundable-balance invariants meaningless for a settlement position. Compensation has periods, cash custody, and an engagement surface that does not belong beside customer refunds | Qoida disburses through a provider. Even then only the rail joins ADR 0013; the ledger stays here |

## Engagement, registration, and what happens when it lapses

### What the platform holds

```text
fulfillment.courier_engagements
  id, tenant_id, courier_id, engagement_type            -- SELF_EMPLOYED only
  status, engaged_from, engaged_until null
  protected_registration_ref                            -- ADR 0029 ProtectedValue
  registration_valid_until date                         -- clear, see below
  registration_verified_at, registration_verified_by
  verification_method, evidence_media_id null           -- ADR 0010, classified
  reverification_due_on date, warning_state
  version, timestamps
```

`engagement_status`: `PENDING_VERIFICATION -> ACTIVE -> (SUSPENDED_COMPLIANCE |
SUSPENDED_OPERATIONAL | ENDED)`. A courier reaches `ACTIVE` only through a
recorded verification; there is no path from onboarding straight to dispatchable.

The registration identifier is `PERSONAL_SENSITIVE` under ADR 0029 and is
envelope-encrypted, so it can never be queried. `registration_valid_until` and
`reverification_due_on` are therefore held **in clear as bare dates**, because
"which couriers expire this month" is the whole point of holding this data and an
encrypted value cannot answer it. That is a deliberate, named disclosure: a date
beside a courier row is a much smaller fact than a registration number, and the
alternative is an operations screen that cannot exist. The same reasoning ADR
0045 applies to its live position row.

### Verification, and its decay

Two methods, both modelled, one implemented now:

| Method | Status | What it records |
|---|---|---|
| `MANUAL_ATTESTATION` | Implemented | An operator sighted the registration evidence, at an instant, under a named principal, with the document stored as ADR 0010 media under its classification |
| `REGISTRY_LOOKUP` | Modelled, not built | A response from an authoritative source, with the raw response retained as evidence |

Whether a machine-readable source for самозанятость status exists in Uzbekistan
is an open input (below); the manual path ships either way and the port is
shaped so that adding the lookup does not change the engagement model.

A manual attestation is evidence about a past instant, not a standing fact, so it
decays. `reverification_due_on` is the earlier of the attested
`registration_valid_until` and the verification instant plus
`courier.registration.reverification_days`, an ADR 0030 policy value with a
provisional default of 180 days.

### The three states dispatch reads

| `warning_state` | Trigger | Effect on dispatch |
|---|---|---|
| `VALID` | Beyond the warning window | None |
| `EXPIRING` | Inside `courier.registration.warning_days`, default 30 | None. ADR 0020 notifies the courier at day 30, 14, 7, and 1, and the branch manager from day 14 — because a courier who ignores the message is the tenant's problem, not only their own |
| `LAPSED` | Past `registration_valid_until` or past `reverification_due_on` | Engagement moves to `SUSPENDED_COMPLIANCE`, an ADR 0014 `COMPLIANCE_HOLD` restriction is written, the courier is not an eligible candidate, and a shift open is refused with the reason |

**A lapse never strands an order.** Assignments already accepted are carried to
delivery, the shift closes normally, and cash reconciles normally. Only new
offers stop. This is the same reasoning as the soft geo-gate: a hard stop
mid-delivery produces a customer waiting on a stairwell and a courier improvising,
which is worse for everyone than the state it was trying to prevent.

**Earnings that accrued after a lapse are still paid, and the statement says so.**
The settlement period carries `compliance_flag` and the affected lines are
marked, so an accountant sees the exposure before the transfer rather than after.
Authorising a payout for a period carrying the flag requires ADR 0027 four-eyes
approval. Refusing to pay would be using the courier's money to enforce the
tenant's compliance, which is the wrong instrument.

**The operational cost is real and is accepted.** On a Friday evening a lapse
removes a rider from a fleet of six and the branch is short one carrier for the
rest of service. That is why the warning window exists, why it escalates to a
manager and not only to the courier, and why the Fleet screen carries an expiring
count rather than burying it in a report. Refusing to assign work is still the
honest answer: the alternative is dispatching work that nobody may lawfully
invoice for.

An engagement is per tenant. A courier working for two tenants is verified twice
and can be `ACTIVE` in one and `SUSPENDED_COMPLIANCE` in the other. That is
duplicated effort and the only model consistent with tenant isolation and ADR
0029's per-tenant key scope.

### What this is not

The facts above are how the platform models the engagement. They are not a legal
determination that any particular arrangement is self-employment rather than
employment; that determination belongs to the tenant and its counsel. What the
platform can do is avoid contradicting it: the courier sets their own hours, a
roster entry is an offer rather than an instruction, penalties attach to delivery
outcomes rather than to conduct, and no leave, insurance, or payslip mechanism
exists to imply otherwise.

## The settlement statement

A settlement period is `OPEN -> CLOSING -> CLOSED -> SETTLED`, per tenant per
courier, with a length configured through ADR 0030.
`settlement_period_id` is stamped when the entry is written, never derived by a
date query at close time. An entry arriving after close — a late cash
confirmation, a corrected distance — lands in the next open period as a
`PRIOR_PERIOD_ADJUSTMENT` referencing the original and retaining its
`occurred_at`. A closed period is never reopened, because reopening changes a
figure someone has already been paid against.

Closing produces an immutable hashed statement containing exactly this:

1. Tenant, courier, engagement reference, period bounds in the location's IANA
   timezone, period identifier, statement hash, version.
2. **Gross earnings by component**: per-order, per-kilometre, per-shift fixed,
   and minimum top-up, each with its rate card version.
3. **Adjustments**: bonuses positive, penalties negative, each naming its origin
   — the rule and its version, or the actor, reason code, and approval.
4. **Gross total.** This is the figure the courier invoices for. There is no line
   below it for withholding and no line for net of tax.
5. **Cash reconciliation**, presented as a separate block: collected, handed
   over, variance, closing position. Cash offset is a settlement mechanic, not a
   deduction from earnings, and merging the two is how a courier concludes he was
   paid less than he earned.
6. **Amount to transfer** = gross total + adjustments − cash held, labelled in
   those words, with the explicit note that no tax has been deducted and none
   will be.
7. **Basis counts**: delivered orders, on-time count, kilometres, paid seconds,
   shifts closed. These are what a courier actually disputes.
8. **Line-level breakdown**: one row per assignment with order reference,
   distance and its source, rate card version, and on-time outcome.
9. **Legal-entity subtotals.** Ledger entries carry the `legal_entity_id`
   resolved from the location on the business date under ADR 0038, because a
   courier working two branches of two entities is owed by both and the expense
   is booked twice even though the transfer is one.
10. `compliance_flag` and the affected lines, where any work fell after a
    registration lapse.

**What it reconciles against.** The courier's own invoice or receipt for the
period, line 4 to line 4. The tenant's cash book, for line 5. And the
shipment-attributable part of line 2 must equal the sum of that period's
`INTERNAL` delivery cost lines in `reporting.fact_delivery` — the check that
catches an accrual recorded against a shipment nobody billed a cost for.

**What an accountant needs from it**, and therefore what the export carries: the
courier's registration identifier and registered name, the period, the gross
total, the per-entity split, the transfer amount, the basis counts, and the
statement hash. The registration identifier is an ADR 0029 reveal with a declared
purpose and is audited; the stored statement holds a reference, and only the
export resolves it.

## Two cost paths, and how they reconcile

```text
fulfillment.delivery_cost_lines            -- append-only
  id, tenant_id, shipment_id, legal_entity_id, business_date
  cost_path        INTERNAL | PARTNER
  cost_basis       ACCRUED | INVOICED | SETTLED
  amount_minor, currency
  source_type, source_id       -- courier_assignment_earnings | partner invoice line
  courier_id null, provider_code null
  recognised_at, superseded_by null
```

**A shipment has one or more cost lines, never one column.** An order booked with
Noor, cancelled when Noor's courier did not arrive, and delivered by Alisher
carries a partner cancellation charge and an internal earning, and both are real.
A model with a single cost field per shipment silently discards one of them, and
the discarded one is always the surprising one.

**Basis is not a status, it is a claim about the number.**

| Basis | Internal path | Partner path |
|---|---|---|
| `ACCRUED` | Computed at delivery from the snapshotted rate card. May still take a `PRIOR_PERIOD_ADJUSTMENT` until its period closes | The booked price on ADR 0014's winning assignment attempt. An estimate: the partner may bill waiting time, a cancellation, or a surcharge |
| `INVOICED` | Not used | The figure on an imported partner invoice line matched to this shipment |
| `SETTLED` | The period closed and the statement was hashed | The invoice was paid |

**Why naive addition is wrong, precisely.** The internal figure exists at
delivery; the partner figure exists when the invoice arrives, days or weeks
later. A same-day cost report that sums both under-states partner cost and then
jumps when invoices land, and no reader can tell whether the jump is a cost
increase or an arrival. The two also differ in mutability — an accrual moves by
adjustment, an invoice moves only by credit note — and they rest on different tax
documents, a company's invoice on one side and a self-employed person's on the
other, whose combined treatment is a finance and legal input rather than an
arithmetic one.

**So the rule is: a delivery-cost total is taken over a single basis and states
it.** ADR 0043's `reporting.fact_delivery` is the grain where both paths meet; it
already carries `is_external`, `provider_code`, `fee_charged_som`,
`provider_billed_som`, `variance_som`, and `reconciliation_status`. This ADR asks
it for two more columns, `courier_cost_som` and `cost_basis`, and for the rule
that a combined delivery-cost measure renders as two lines and a total, never as
one number.

**Shift-level cost does not decompose to an order.** `PER_SHIFT_FIXED` and
period-level bonuses are not attributable to any single delivery. They are
recorded at the courier-branch-day grain and never spread across orders by an
allocation key. There is consequently no true cost-per-delivery figure for the
in-house path, and that absence is deliberate: a fabricated allocation is a
number that looks reproducible and is not.

### Partner invoices

```text
fulfillment.partner_delivery_invoices
  id, tenant_id, provider_code, provider_invoice_ref, legal_entity_id
  period_start, period_end, total_minor, currency, status, imported_by, imported_at

fulfillment.partner_delivery_invoice_lines
  id, invoice_id, tenant_id, provider_shipment_ref, shipment_id null
  amount_minor, charge_type, match_status, variance_minor null, reason_code null
```

`match_status` is `MATCHED`, `VARIANCE`, `UNBILLED` (Qoida has a shipment the
partner never billed), or `UNMATCHED_LINE` (the partner billed for something
Qoida has no shipment for). The last is the direction reconciliation reports
usually omit and the only one that can hide a charge for a delivery that never
happened. A `VARIANCE` above an ADR 0030 threshold blocks nothing and raises an
operations task; disputing a partner invoice is a human activity and the platform
records evidence for it rather than pretending to automate it.

## Shifts, breaks, and who may change what

```text
roster entry:  DRAFT -> PUBLISHED -> (ACCEPTED | DECLINED) -> (CONSUMED | MISSED | CANCELLED)
shift:         OPEN -> CLOSE_REQUESTED -> RECONCILING -> CLOSED -> SETTLED
               OPEN -> AUTO_CLOSED -> RECONCILING -> CLOSED
               CLOSED -> AWAITING_APPROVAL -> CLOSED     (variance over threshold)
duty state within an OPEN shift:  AVAILABLE | ON_BREAK | AT_CAPACITY | UNREACHABLE
```

| Transition | Who | Why |
|---|---|---|
| Open a shift | The courier only | A self-employed person decides when they work. A manager who can open a shift can create paid hours for someone at home |
| Start and end a break | The courier only | Same reason, and a manager who can end a break is directing rest periods |
| Close a shift | The courier, or a manager with a reason code and an audit fact | Ending service, closing premises, and safety are the tenant's to decide; the reason is recorded and the hours still need approval if they vary |
| `AUTO_CLOSED` | The sweeper, past delivery hours plus a margin | Couriers forget. Auto-closed hours always need approval, since paying an unreviewed self-opened shift pays someone who opened the app at home |
| Approve hours or variance | A manager, never the courier, through ADR 0027 | Maker-checker |
| `AT_CAPACITY`, `UNREACHABLE` | Nobody. Derived from active assignment count and telemetry staleness | A settable derived state is a state that will disagree with what it derives from |
| Suspend the engagement | A manager, or the compliance sweeper on lapse | Above |

- Opening a shift requires an `ACTIVE` engagement, a device position within the
  acceptance radius, and, where policy demands it, a `PUBLISHED` and `ACCEPTED`
  roster entry covering the instant.
- **A roster entry is an offer, not a rota.** `PUBLISHED` invites; `ACCEPTED` and
  `DECLINED` are the courier's answer; `MISSED` is a coverage fact and not a
  disciplinary one. ADR 0036's capacity model therefore reads accepted intent
  plus historical show-rate, not an authored plan — a real weakening, stated
  below in Consequences.
- Paid seconds come from the shift, minus `ON_BREAK` intervals, which are stored
  as `break_seconds`. A `PER_SHIFT_FIXED` component carries a
  `minimum_paid_seconds` qualifier, so a shift opened, spent on break, and closed
  earns nothing fixed.
- `courier.shift.enforcement` resolves through ADR 0030 as `ENFORCED`,
  `ADVISORY`, or `OFF`, and the resolved value and version are snapshotted onto
  every assignment attempt. Without the snapshot, tightening the policy in
  October makes September's assignments look illegal.
- Offer eligibility is the conjunction of an `ACTIVE` engagement, an open shift
  where enforced, duty state `AVAILABLE`, no active ADR 0014 restriction, courier
  type matching the order's distance band, and active assignments below
  `max_concurrent_assignments`. The ceiling is a conditional update —
  count-then-insert races two dispatchers into a third order. An offer expires
  after `offer_ttl_seconds`, returns to ADR 0014 sourcing, and never accrues.
- One dispatch surface covers in-house and partner carriers. The board sorts by
  severity across both, and the carrier is a column. A dispatcher who must choose
  a tab before seeing their work will miss the timed-out Noor booking, which is
  the row that outranks every late order.

## On-time, and who is blamed for lateness

The promise is `promised_delivery_end` from the ADR 0014 plan as it stood when
the courier accepted, copied onto the earning row with the grace period and
policy version.

| Outcome | Condition |
|---|---|
| `ON_TIME` | `delivered_at <= promised_delivery_end + grace` |
| `LATE_EXCUSED` | Late, but the kitchen handed over after the plan's pickup window closed |
| `LATE` | Late with handover inside the pickup window |
| `UNKNOWN` | No promise was recorded on the plan |

`LATE_EXCUSED` exists because penalising a courier for a late kitchen is how a
tenant loses its couriers, and the branch is the party that can fix it. `UNKNOWN`
earns no premium and triggers no penalty: an absent promise is the platform's
failure, and the honest treatment of a missing input is neutral pay, not a guess.
Delever's 30/35/40-minute buckets stay an ADR 0043 reporting projection.

## Rate cards, distance, and adjustments

A rate card is a versioned, priority-ordered set of typed components:
`PER_SHIFT_FIXED` credited once per closed shift meeting its
`minimum_paid_seconds`, `PER_ORDER` per delivered order, `PER_KM_BAND` within a
contiguous distance band, and `PER_ORDER_MINIMUM` as a floor on one order's
accrual. No scripting, for the reasons ADR 0018 gives about pricing rules. Bands
must cover zero to unbounded with no gap and no overlap, validated at activation:
a gap means an order at exactly the boundary earns nothing, and the courier finds
it before the tenant does.

`distance_meters` is the routing distance quoted at assignment, with
`distance_source` recording `ROUTING`, `HAVERSINE_FACTORED` where no routing
provider is bound, or `MANUAL` where operations overrode it with a reason. The
GPS track is evidence, may trigger review, and never pays. ADR 0045 owns that
track; this ADR owns what the courier is owed for going, and the figure it uses
is quoted at assignment so both parties can see it before the trip and argue
about it afterwards.

Bonuses and penalties are one mechanism with two origins. Both become ledger
entries carrying `origin = RULE | MANUAL`. Rule-derived adjustments evaluate a
typed condition set — delivered count, `ON_TIME` count, `GEO_UNVERIFIED` rate,
cash variance count, hours — over an explicit window at an explicit trigger,
versioned and reproducible. Manual adjustments require an actor and a reason code
from a managed registry, and every manual penalty, plus any penalty above the
configured amount, requires ADR 0027 four-eyes approval: a manager who can
silently debit a courier's pay is a labour dispute and a fraud vector in one
instrument. No adjustment may be written into a `CLOSED` period.

**Under a self-employed engagement a penalty is a reduction in the amount
invoiced for, agreed in the engagement terms — not a disciplinary deduction.**
The reason-code registry is therefore deliberately narrow and every code names a
delivery outcome, not a behaviour. Routinely directing and sanctioning how a
self-employed person conducts themselves is the fact pattern that reclassifies
the engagement, and a free-text penalty field is how that arrives one reason code
at a time.

The `COMMISSION` entry type from the earlier draft is removed. With the float
rejected, no arrangement exists in which a courier owes the tenant a commission,
and an unused negative entry type in an append-only ledger is how one appears
without a decision.

## Cash at close

`DELIVERED` on a cash order writes a negative `CASH_COLLECTED` entry for the
amount the courier was told to collect — order total less anything already
captured and less any loyalty amount, which the app must display for exactly this
reason. At close the courier declares the cash handed over and a branch cashier
confirms what was received. Declared minus expected, and confirmed minus
declared, are each recorded as an explicit `CASH_VARIANCE` entry with a reason
code; a variance is never absorbed into another figure. A shift cannot reach
`CLOSED` with an unconfirmed handover unless a manager overrides with an audited
reason. A cash ceiling per courier resolves through ADR 0030 and, when exceeded,
suppresses further cash orders for that courier rather than blocking the courier:
the tenant's exposure is to the cash, not to the person.

Cash custody is independent of engagement type. The money is the tenant's from
the moment the customer hands it over, regardless of how the carrier is engaged.

## Location, disclosure, and retention

Product decided on 2026-08-23 that **customers see status milestones only and
never a courier's position.** No courier coordinate leaves the tenant's
operations surface. That closes the hardest part of the privacy analysis: what
remains is processing between a tenant and a courier who has agreed to be
dispatched, for dispatch and for verifying a status the courier is paid against.

This ADR holds exactly two coordinates per shipment — the pickup confirmation and
the delivery confirmation — classified `PERSONAL_SENSITIVE` under ADR 0029 and
envelope-encrypted. The continuous track belongs to ADR 0045 and survives 72
hours; neither is a payroll input.

**Retention for the two confirmation points: until 30 days after the settlement
period containing the shipment reaches `SETTLED`, then deleted.** The reason is
the only thing they are kept for. A courier disputes a statement, and a statement
exists from period close; thirty days past settlement is one full period plus a
working month for the dispute to be raised and answered. What survives the
deletion is what the accrual was actually computed from — `on_time_outcome`, the
`GEO_UNVERIFIED` flag, `distance_meters`, and `distance_source` — none of which
is personal data, retained with the settlement record under ADR 0029's
`FINANCIAL` rules. Keeping the coordinates beyond that window would be keeping a
movement history because storage is cheap.

**A duty session is opened by the shift and never independently, and a break
suspends collection.** ADR 0045 collects telemetry only while a duty session is
open; tying that window to the shift means a courier is not tracked outside the
hours they chose to work, and a courier on break is not tracked at all. The cost
is that a dispatcher loses the pin of a courier on break. That is correct: a
courier on break is not assignable, so the pin had no operational use.

Offer acceptance is a hard gate on the pickup radius, because accepting from
eight kilometres away is never legitimate and blocking it costs an honest courier
nothing. `PICKED_UP` and `DELIVERED` are soft by default and record
`GEO_UNVERIFIED`: a hard gate strands a courier in a stairwell with a customer
waiting, and the workaround is marking delivered from the street, which yields
worse data and a worse delivery. Either hardens per location through ADR 0030,
and the honest enforcement path is that a sustained `GEO_UNVERIFIED` rate is a
penalty-rule condition applied through approval.

## Physical model

Supporting tables that are ordinary scoped configuration and are not detailed
here: `courier_types` (vehicle class, distance band, `max_concurrent_assignments`,
`offer_ttl_seconds`), `courier_rate_cards` with `courier_rate_components` (scoped
by brand, location, and courier type, with validity, priority, and version),
`courier_roster_entries` (planned start and end, publication, author, courier
response), and `courier_payouts` (period, amount, method, external reference,
authoriser, approval, paid-at).

```text
fulfillment.courier_shifts
  id, tenant_id, location_id, courier_id, engagement_id, roster_entry_id null
  status, duty_state, opened_at, closed_at null, open_source, close_source
  open_point, close_point null
  paid_seconds null, break_seconds null, variance_seconds null
  approval_request_id null, settlement_period_id null, version

fulfillment.courier_shift_breaks
  id, tenant_id, shift_id, started_at, ended_at null, ended_by_source

fulfillment.courier_assignment_earnings
  id, tenant_id, courier_id, shipment_id, assignment_attempt_id, legal_entity_id
  rate_card_id, rate_card_version, courier_type_id
  distance_meters, distance_source, on_time_outcome
  promised_delivery_end null, grace_seconds, on_time_policy_version
  fixed_minor, per_order_minor, per_km_minor, total_minor, currency, computed_at
  unique(assignment_attempt_id)

fulfillment.courier_ledger_entries            -- append-only: INSERT/SELECT only
  id, tenant_id, courier_id, settlement_period_id, legal_entity_id
  entry_type, amount_minor (signed), currency
  source_type, source_id, origin, reason_code null
  occurred_at, recorded_at, idempotency_key, approval_request_id null, created_by
  unique(tenant_id, idempotency_key)

fulfillment.courier_settlement_periods
  id, tenant_id, courier_id, engagement_id, period_start, period_end, status
  gross_earnings_minor, adjustments_minor, cash_held_minor, amount_payable_minor
  delivered_count, on_time_count, distance_meters, paid_seconds, shift_count
  compliance_flag, statement_hash null, closed_by null, closed_at null, version

fulfillment.courier_cash_handovers
  id, tenant_id, shift_id, courier_id, location_id, status
  expected_minor, declared_minor null, confirmed_minor null, variance_minor null
  declared_at null, confirmed_by null, confirmed_at null, reason_code null
```

Entry types: `DELIVERY_EARNING`, `SHIFT_EARNING`, `BONUS`, `PENALTY`,
`CASH_COLLECTED`, `CASH_HANDED_OVER`, `CASH_VARIANCE`, `PAYOUT`,
`PRIOR_PERIOD_ADJUSTMENT`, `CORRECTION`. Payout methods: `CASH_AT_BRANCH`,
`BANK_TRANSFER`, `CARD_TRANSFER`.

The column is `amount_payable_minor`, not `net_payable_minor`. The word "net" on
a worker settlement document means net of tax everywhere it is read, and this
figure is not that.

## APIs, capabilities, and events

```text
POST /api/v1/courier/shifts        (+ /{id}/close, /{id}/breaks, /{id}/cash-declaration)
GET  /api/v1/courier/earnings/summary
GET  /api/v1/courier/statements/{periodId}
POST /api/v1/operations/couriers/{courierId}/engagement            (+ /verify, /suspend)
POST /api/v1/operations/courier-rate-cards                         (+ /{id}/activate)
POST /api/v1/operations/couriers/{courierId}/adjustments
GET  /api/v1/operations/couriers/{courierId}/ledger
POST /api/v1/operations/shifts/{shiftId}/approve
POST /api/v1/operations/cash-handovers/{handoverId}/confirm
POST /api/v1/operations/settlement-periods/{periodId}/close        (+ /statement, /payouts)
POST /api/v1/operations/partner-delivery-invoices                  (+ /{id}/match)
GET  /api/v1/operations/delivery-costs?basis=INVOICED
```

Every mutation carries an idempotency key, expected version, and reason per ADR
0031. New ADR 0025 capabilities: `courier.shift.open` and `courier.shift.break`
(held by the courier over their own record only), `courier.shift.approve`,
`courier.engagement.manage`, `courier.registration.verify`,
`courier.registration.reveal`, `courier.ratecard.manage`,
`courier.adjustment.create`, `courier.adjustment.approve`, `courier.cash.confirm`,
`courier.ledger.read`, `courier.settlement.close`, `courier.payout.authorise`,
`delivery.cost.read`, `partner.invoice.manage`. A courier reads their own ledger
and statement and nobody else's.

Events: `CourierEngagementActivated`/`Suspended`, `CourierRegistrationVerified`,
`CourierRegistrationExpiring`, `CourierRegistrationLapsed`,
`CourierShiftOpened`/`Closed`/`ApprovalRequired`, `CourierBreakStarted`/`Ended`,
`CourierAssignmentEarningComputed`, `CourierLedgerEntryRecorded`,
`CourierCashHandoverDeclared`/`Confirmed`/`VarianceRaised`,
`DeliveryCostLineRecorded`, `PartnerDeliveryInvoiceImported`,
`PartnerDeliveryInvoiceLineMatched`, `CourierSettlementPeriodClosed`,
`CourierPayoutAuthorised`/`Recorded`, `CourierRateCardActivated`. Partition by
courier. Carry identifiers, amounts, and dates only — never a registration
number, an identity document, or a position, per ADR 0029 and ADR 0032.

## What this asks of other ADRs

- **ADR 0043** — two columns on `reporting.fact_delivery`, `courier_cost_som` and
  `cost_basis`, and the rule that a delivery-cost measure renders as two lines
  and a total with its basis named.
- **ADR 0045** — the duty session opens from the shift and never independently,
  and a break suspends collection. Nothing else; the two ADRs hold complementary
  halves and ADR 0045's statement that no accrual reads a track stands.
- **ADR 0014** — `INTERNAL_COURIER` gains its eligibility gate, and its
  "courier bonus and settlement ownership" and "internal-courier scope" open
  inputs close.
- **ADR 0036** — delivery capacity for an in-house fleet is forecast from
  accepted roster offers and historical show-rate, not read from an authored
  rota.
- **ADR 0038** — every ledger entry resolves a `legal_entity_id` from the
  location on the business date, using the same resolution the fiscal assignment
  uses.

## Testing

- Changing a delivery tariff or applying a free-delivery promotion produces a
  byte-identical courier accrual for the same order.
- A rate card with a gap or overlap between distance bands fails activation, and
  a delivered order accrues exactly once under duplicate delivery events.
- A courier whose `registration_valid_until` passes receives no further offer and
  cannot open a shift, while an assignment accepted before the lapse completes,
  accrues, and appears on a statement carrying `compliance_flag`.
- Authorising a payout for a period carrying `compliance_flag` without approval
  is rejected.
- A statement contains no field named or labelled as withholding or net of tax,
  and `amount_payable_minor` equals gross plus adjustments minus cash held.
- A shipment that was booked with a partner, cancelled at a fee, and delivered
  in-house carries two `delivery_cost_lines` and its total is their sum.
- A delivery-cost query without a basis is rejected; a query at `INVOICED`
  excludes internal accruals and reports them as an unbilled count rather than
  omitting them silently.
- A partner invoice line with no matching shipment surfaces as `UNMATCHED_LINE`
  and is not netted into any total.
- A manager cannot open a shift or end a break for a courier; a manager close
  records a reason and an audit fact.
- An off-shift courier receives no offer under `ENFORCED` and one under `OFF`, a
  later policy change does not alter a stored attempt, and concurrent offers
  cannot push a courier past `max_concurrent_assignments`.
- A late delivery whose handover fell outside the pickup window resolves as
  `LATE_EXCUSED` and triggers no penalty rule.
- A closed period's totals are unchanged by an entry recorded afterwards, which
  lands in the next period as `PRIOR_PERIOD_ADJUSTMENT`.
- Cash collected, declared, and confirmed reconcile to zero, and a mismatch
  produces a `CASH_VARIANCE` entry rather than a silently adjusted figure.
- A manual penalty without approval is rejected, and the requester cannot approve.
- No confirmation coordinate is readable 30 days after its settlement period
  reached `SETTLED`, while `on_time_outcome`, `distance_meters`, and the
  statement figure computed from them are unchanged.
- No registration number, coordinate, or identity document is reachable from any
  event payload type, per the ADR 0029 build-time check.
- The application role can `INSERT` and `SELECT` ledger entries and fails on
  `UPDATE` and `DELETE`, per the ADR 0027 evidence test; a courier cannot read
  another courier's ledger; every statement figure equals the sum of its ledger
  lines with no rounding remainder.

## Rollout and rollback

Migration V0040 creates the tables set out above in the `fulfillment` schema,
under the names this ADR gives them; the code lives in a `courier` Java module, separated from
`fulfillment` for the reason ADR 0041 separated the kitchen — zones and tariffs
change when pricing changes, and an engagement, hours and a ledger change when a
labour arrangement or a finance calendar does.

Capture and verify registrations before anything else, because a courier without
a verified engagement cannot be dispatched and the fleet is therefore unusable
until the backfill completes. That ordering constraint belongs in the ADR 0024
cutover plan, not discovered during it.

Run accrual in shadow: compute earnings, hours, and on-time outcomes for one
location across a full settlement period while the tenant keeps paying from its
existing spreadsheet, then reconcile line by line and explain every difference
before the ledger becomes authoritative. Enable shift enforcement as `ADVISORY`
before `ENFORCED`, so the gate's false negatives appear in a report rather than
as couriers unable to work during a dinner rush — the operations prototype shows
`ADVISORY` for this reason. Import partner invoices in report-only mode for one
billing cycle before any variance drives an operations task.

Rollback stops period closing and payout authorisation while accrual continues,
so no history is lost. Registration enforcement rolls back to warning-only, which
restores dispatch and keeps the visibility.

## Consequences

### Positive

- The statement has one shape and one meaning. Gross is gross, the transfer
  amount is labelled as a transfer amount, and nobody has to ask what happened to
  the difference.
- A registration lapse becomes an event with a date, a notification chain, and a
  dispatch consequence, instead of a fact nobody learns until an inspection.
- One balance per courier answers "what do we owe" and "what cash is out" at the
  same time, which is the only pair of questions that can be wrong together.
- Delivery pricing and courier pay move independently, so a promotion cannot
  quietly cut earnings and a rate rise cannot quietly raise a customer's fee.
- Delivery cost is comparable across in-house and partner carriers at a stated
  basis, which is what makes "should this branch run its own riders" answerable.
- Every statement figure traces to an immutable entry with a rate card version, a
  policy version, and an actor, so a disputed payout is answered from evidence,
  and late kitchens stop costing couriers money — the most common reason a
  courier stops trusting the number.

### Negative

- Refusing to dispatch a lapsed courier costs deliveries on the exact evening the
  lapse happens. The warning window reduces the surprise; it does not remove the
  shortage.
- `registration_valid_until` sits in clear beside a courier row so it can be
  queried. That is a deliberate disclosure, and the encrypted identifier beside
  it means two storage regimes for one fact.
- Manual attestation is only as good as the person who performed it. Requiring an
  actor, a method, and a document reference makes a false attestation attributable;
  it does not make it impossible.
- Every delivery-cost figure now carries a basis label, and readers will ignore
  labels. The mitigation is refusing the unlabelled query, which will be
  experienced as the platform being difficult.
- There is no cost-per-delivery figure for the in-house path, because shift-fixed
  cost does not decompose. Someone will ask for one.
- Self-employed autonomy means coverage is forecast, not scheduled. A branch can
  be under-covered on a Friday with nobody in breach of anything, and the
  operational answer is incentives rather than instructions.
- The statement carries no net-of-tax figure, so a courier asking "what will I
  actually receive after tax" is answered outside Qoida, by them or their
  accountant.
- Cash reconciliation adds a step at the end of every shift and needs a person at
  the branch to confirm it. Tenants will ask to skip it, and skipping it is how
  the ledger stops being true.
- Refusing to reopen closed periods puts corrections in a later period, which
  accountants understand and couriers find confusing on a statement.
- The rate card model is deliberately narrow. The first request it cannot
  satisfy, such as a weather surcharge, is a schema and calculator change, not
  configuration.

### Accepted trade-offs

- Computing but not disbursing means someone moves the money outside Qoida, and
  the payout record is only as true as the person who marks it paid. Accepted
  because worker payment is a different rail with different providers from ADR
  0013's customer-payment rail, and building it now would be building it blind.
- Verification is per tenant, so a courier working for two tenants is verified
  twice. Accepted as the price of tenant isolation and per-tenant key scope.
- Paying routing distance rather than travelled distance occasionally underpays a
  legitimately diverted courier. Accepted because it is visible before the trip
  and reproducible after it, and the alternative is unbounded.
- Deleting confirmation coordinates 30 days after settlement means a dispute
  raised in month four is answered from the derived facts alone. Accepted:
  disputes that old are argued about amounts, not about whether the courier was
  at the door.

## Open inputs and who answers them

None is structural. Each sets a value, a label, or an optional adapter, and none
changes a table, a state machine, or the settlement model.

| Input | Owner | Why it is not structural |
|---|---|---|
| Combined tax treatment of a self-employed courier's invoice and a partner's company invoice in one delivery-cost report | finance, legal | Decides how the total is labelled and whether a tax-exclusive line is added to the report. The two cost paths, their basis rule, and the fact grain are unchanged |
| Whether an authoritative machine-readable source for самозанятость registration status exists | operations, legal | Decides whether `REGISTRY_LOOKUP` is implemented. `MANUAL_ATTESTATION` ships regardless and the engagement model is identical either way |
| Whether a courier must issue one invoice per legal entity or one per tenant | finance | Decides how many documents the export produces. The statement already carries per-entity subtotals |
| Re-verification interval, warning window, settlement period length, cash ceiling, penalty approval threshold | finance, operations | ADR 0030 policy values with the provisional defaults stated above |
| Lawful-basis wording for courier location processing, and sign-off on the 30-day post-settlement retention | legal | The disclosure question — whether a customer sees a courier position — is closed by product at "no". What remains is wording and a number, both configurable, with provisional values in use |

ADR 0027's cross-cutting retention-period input applies here as it does
everywhere and is not restated as this ADR's own.

## Implementation checklist

- [x] Add the tables above via Flyway (V0040), with an `INSERT`/`SELECT`-only
      grant on the ledger, the statement, and `delivery_cost_lines`. The
      supersession pointer on a cost line runs forwards, from the replacement to
      what it replaced, because a table the application may `UPDATE` is not
      append-only whatever the column is called.
- [x] Implement the engagement record, registration capture with ADR 0029
      protection and the clear validity dates, manual verification with evidence,
      and the re-verification schedule. `REGISTRY_LOOKUP` is modelled and
      refused at the service boundary rather than half-built.
- [x] Implement the expiry sweeper, the notification ladder to courier and
      manager, and the `SUSPENDED_COMPLIANCE` suspension with its dispatch gate.
      The ADR 0020 transport is a port with a logging implementation: the
      decision to warn is what matters and it is recorded either way.
- [x] Implement rate card validation, approved activation, and the deterministic
      accrual calculator with on-time and pickup-window attribution.
- [x] Implement shift lifecycle including breaks, the auto-close sweep, the
      ADR 0030 enforcement policy snapshotted onto the shift, and the
      eligibility gate.
- [ ] **Not built: the conditional-update concurrency ceiling on
      `max_concurrent_assignments`, and the per-attempt policy snapshot.** Both
      belong on ADR 0014's assignment attempt. The ceiling is carried on
      `courier_types` and now reaches sourcing: `InternalFleetAdapter` puts it on
      every `FleetCandidate` beside the count of shipments the courier is
      carrying, so `hasCapacity()` filters a full courier out before he is
      offered anything. That is a pre-filter and not the invariant — two
      dispatchers racing can still both read "one of two" — and the
      compare-and-set that would close it is ADR 0014's to write on the attempt
      row. The gate returns the resolved enforcement mode and policy version for
      an attempt to be stamped with, and nothing stamps it yet.
- [ ] **Not built: roster entries and the ADR 0036 accepted-intent capacity
      signal.** `courier_roster_entries` and `courier_shifts.roster_entry_id` are
      omitted from V0040 rather than created empty, because their only two
      consumers — the roster-gated shift open and ADR 0036's forecast — do not
      exist, and a nullable foreign key no writer populates is configuration that
      silently does nothing.
- [x] Implement the ledger, period stamping at insert, prior-period adjustments,
      cash declaration, confirmation, and variance. Derived duty states
      (`AT_CAPACITY`, `UNREACHABLE`) are modelled and, as this ADR requires, not
      settable; nothing derives them yet because the inputs are ADR 0014's and
      ADR 0045's.
- [x] Implement rule and manual adjustments with ADR 0027 approval and an
      outcome-based reason-code registry, whose `outcome_basis` is a closed
      column so a behavioural reason cannot be authored.
- [x] Implement `delivery_cost_lines`, partner invoice import and matching
      including `UNMATCHED_LINE`, and the basis-required cost query.
- [x] Implement statement generation, hashing, the audited registration reveal,
      payout authorisation, and the courier and operations APIs with ADR 0025
      capabilities. The statement's no-tax-language rule is enforced on the
      document before it is hashed, not only asserted in a test.
- [ ] **Not built: the ADR 0032 event stream.** The fourteen events this ADR
      names need a catalogue entry, a JSON schema, and a documentation row each
      before they may be published, and event types nothing publishes are the
      same dead configuration this checklist refuses twice above. The audit
      facts, which are written, carry the same content under ADR 0027.
- [ ] **Not built: `legal_entity_id` resolution.** ADR 0038's registry does not
      exist; the column is carried, the resolver is a port answering empty, and
      the statement's per-entity subtotal renders one bucket. Nothing invents an
      entity identifier, because a split that looks computed and is not is worse
      than a visible single bucket.
- [x] Implement the 30-day post-settlement deletion of confirmation coordinates
      and prove the derived facts survive it.
- [ ] **Not built: the shadow-accrual comparison and its mismatch report.** It
      compares against a tenant's existing spreadsheet, which is a cutover
      activity under ADR 0024 rather than a platform feature.

Two further gaps worth naming. `reporting.fact_delivery` does not exist yet, so
the two columns this ADR asks ADR 0043 for are not added; `delivery_cost_lines`
is the source they will be projected from. And the `ENFORCED` shift gate cannot
yet require a roster entry, because there are none — `ENFORCED` means an open
shift and nothing more.

## Exit criteria

For a courier who worked one settlement period, Qoida produces exactly one
transfer amount equal to the sum of its immutable ledger lines, with a gross
total and no withholding or net-of-tax line anywhere on the statement; that
figure is unaffected by any later change to a delivery tariff, rate card,
enforcement policy, or on-time policy; the cash that courier collected is fully
accounted for as collected, handed over, or an explicit variance; every bonus,
penalty, and approved hour names the rule or the person that produced it; a
courier whose registration expired that morning received no offer that afternoon
and was still paid for the delivery he accepted the night before; a delivery-cost
report for the same period states its basis and shows the in-house and partner
paths as separate lines summing to a total that ties to the closed statements and
the matched partner invoices; and no coordinate from that period is readable
thirty days after it settled, while every figure computed from those coordinates
still is.

## References

- ADR 0013 — payment, refund, and compensation; owns money movement.
- ADR 0014 — sourcing, `INTERNAL_COURIER`, assignment attempts, restrictions.
- ADR 0029 — data classification, envelope encryption, reveal and audit.
- ADR 0036 — location serviceability and capacity.
- ADR 0038 — legal entities and per-location fiscal assignment.
- ADR 0043 — `reporting.fact_delivery` and the metric layer.
- ADR 0045 — courier telemetry, the track, and its 72-hour retention.
- `frontend/prototypes/operations/src/Couriers.jsx` — the single dispatch surface
  this ADR honours, and the shift, cash, and fleet screens it implies.
