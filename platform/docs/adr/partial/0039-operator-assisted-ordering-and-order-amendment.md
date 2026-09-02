# ADR 0039: Operator-assisted ordering, order amendment, and terminal outcome accounting

- Decision status: Accepted
- Implementation status: Partial — V0029 adds `created_by_actor_type` and
  `created_by_actor_id` to `ordering.orders` and creates `order_revisions`,
  `order_outcome_reasons`, `order_outcome_reason_texts`, `order_outcomes`,
  `order_amendments` and `order_amendment_commands`. Attribution is written by
  both `JdbcOrderStore` and the marketplace intake; the revision chain, terminal
  outcome accounting and its reason registry
  (`OrderOutcomeService`, `OrderOutcomeReasonService`, `OrderOutcomeReasonController`),
  and the amendment propose/confirm/withdraw lifecycle
  (`OrderAmendmentService`, `OperationsOrderController`) are built and covered by
  `OrderAmendmentAndOutcomeTests`. Three of the ten amendment commands are
  implemented — `SET_KITCHEN_NOTE`, `SET_CALLBACK_REQUESTED` and
  `SET_CASH_TENDERED`, the three `AmendmentCommandType` marks `built`; the other
  seven, every financial one among them, are declared and refused by name. Not
  built: operator-assisted order *creation* (`POST /api/v1/operations/orders`) and
  the customer lookup beside it, so an operator cannot take an order by phone;
  bulk actions (`POST /api/v1/operations/order-bulk-actions`); and the
  `OrderAmendment*`, `OrderRevisionCreated` and `OrderCallback*` event contracts,
  which exist nowhere in `ordering.api`.
  V0119 (wave 24) adds a platform-curated, code-owned reject-reason reference
  table (`ordering.order_reject_reasons`/`_texts`) — deliberately not
  `order_outcome_reasons`'s tenant-authored shape, since a rejection's
  consequence never varies by reason — read through
  `RejectReasonQueryService`/`JdbcRejectReasonStore`, validated by
  `OrderOutcomeService.reject` (OTHER requires an encrypted note), with the
  operations reject dialog a picker over `GET .../orders/reject-reasons`
  instead of free text; covered by `OrderAmendmentAndOutcomeTests`.
- Date proposed: 2026-08-21
- Date decided: 2026-08-21
- Deciders: Ayubkhon Abbosov (platform architecture), product, finance, legal
- Depends on: ADR 0013, ADR 0015, ADR 0017, ADR 0018, ADR 0019, ADR 0027, ADR 0030, ADR 0031
- Supersedes / Superseded by: —
- Open inputs: Which fiscal correction primitives the OFD providers actually expose per payment type — a correction document, or reversal plus reissue (finance, integration discovery; the answer lands in ADR 0038); whether an operator may record marketing consent taken verbally on a recorded line, and what evidence satisfies it (legal)

## Context

ADR 0019 refused to let a confirmed order be edited and said why: mutating
financial history cascades into payment, fiscal receipts, inventory, and the POS
export. Its alternatives table also recorded what would make the option win —
"a properly modelled amendment flow with its own financial semantics is accepted
in a later ADR". This is that ADR.

The refusal was right for a first release and wrong as a permanent answer,
because here amendment is the main workflow rather than the exception. Both
Delever and the legacy Rayhon dashboard put the amendment actions in the
per-order menu an operator uses all shift: add a dessert to a live order, switch
from Click to cash at the door, correct the entrance number on the address. A
call-centre business that answers "I will place a second order" has just told the
customer their order number, delivery promise, and receipt are about to change.

Three further facts force decisions no existing ADR owns.

**Cancellation is not a status change.** Delever's cancellation reason carries a
write-off type — *со списанием* or *без списания* — deciding whether stock and
money are charged off. ADR 0017 left "cancellation restock rules" as an open
input owned by product and operations. That input is closed here.

**Completion is not a status change either.** Delever's completion reasons
distinguish *Самовывоз выполнен* from *Доставлен сторонней службой*, and both
the courier SLA and the external-logistics settlement report are built on that
distinction. An order ending `COMPLETED` with nothing else recorded cannot tell a
manager whether a courier was owed for it.

**An operator-created customer has consented to nothing.** ADR 0015 handles that
correctly in principle — absence of a decision is not consent — but cannot record
that an account was typed in by an operator rather than registered by the person,
and no order field separates who entered an order from who accepted it.

The ordering module today contains only `order_acceptance_policies` from
migrations V0003 and V0012. Carts and orders are unbuilt, so this ADR changes a
design rather than retrofitting live data, which is the only reason revisioning
orders is affordable at all.

## Decision

**An amendment is a closed set of intent-named commands, each with a declared
consequence on the quote, the inventory hold, the payment, the fiscal receipt,
and the POS export. Applying one produces a new immutable order revision. It
never edits an existing revision and never creates a second order.**

1. **Revisions, not edits.** Revision 1 is the ADR 0019 checkout snapshot; each
   applied amendment appends a revision carrying a complete recomputed ADR 0018
   pricing snapshot plus the delta against its predecessor. ADR 0019's
   immutability rule survives: an amendment is a new fact, not a mutation.
2. **Intent is declared, never inferred.** A generic patch would force the fiscal
   and POS decisions to be reconstructed by diffing two documents at exactly the
   moment they must be certain.
3. **Every order ends in exactly one recorded outcome** naming the terminal kind,
   the reason, the reason's version, the actor, the stock disposition, and the
   party carrying the cost. Reports read that table, not a status string.
4. **Attribution is written once.** `created_by` records who entered the order,
   `accepted_by` who moved it to `CONFIRMED`. Neither is ever overwritten,
   because a leaderboard a later action can rewrite measures nothing.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Replacement order with a link back, as ADR 0019 chose for the first release | The order number the operator just read out changes mid-call, a fiscal receipt already exists against the old order, the courier may be dispatched against the old identifier, and the funnel counts two orders where one meal was sold | The amendment crosses a legal-entity boundary. Under ADR 0038 a different branch means a different INN, and a replacement order is then the honest model |
| Free-form edit of the live order, as Delever's «Изменить заказ» does | An unbounded edit has no consequence vector. Six months later nobody can say whether a given save re-fiscalized, released stock, or reprinted the kitchen ticket, and the answer differs per save | Never |
| A versioned amendment fact carrying a generic field-level diff | Cheaper, and it loses what the consequence rules need: a diff cannot separate "added a dessert" from "corrected the entrance number", yet the first re-prices, re-reserves and re-prints while the second does none of those | The command set passes roughly fifteen entries and most share one consequence vector; the vector then belongs on the command |
| Cancellation restock as an operator checkbox at cancel time | Under pressure operators pick whatever closes the dialog fastest, and the write-off rate becomes noise instead of a number the kitchen can act on. The disposition belongs to the reason an admin sets once and finance can audit | A tenant shows real per-incident variance within one reason. The override is then allowed, requires ADR 0027 approval, and records the deviation |
| Bulk actions as one all-or-nothing transaction | A lock convoy during exactly the peak that produced the bulk action, and one already-cancelled order fails the other 199. The operator re-runs it and double-assigns the couriers that did succeed | Never for order mutations |

## Amendment commands and their consequences

The closed set. Anything absent is not an amendment and needs a new entry here,
not a new field.

| Command | Quote | Inventory | Payment | Fiscal | POS export |
|---|---|---|---|---|---|
| `ADD_LINES` | Reprice, new quote | Reserve added lines; fail the amendment if unavailable | Charge the difference before apply | Correction if a receipt exists | Amend or cancel-and-resend |
| `CHANGE_LINE_QUANTITY` | Reprice | Reserve or release the delta | Charge or refund difference | Correction if a receipt exists | Amend or cancel-and-resend |
| `REMOVE_LINES` | Reprice | Release, or write off per the removal reason | Refund difference per ADR 0013 | Correction if a receipt exists | Amend or cancel-and-resend |
| `CHANGE_PAYMENT_METHOD` | Totals unchanged | None | Void or refund the old intent, create the new one | Re-fiscalize under the new method's rules | Payment-type field update |
| `CHANGE_DELIVERY_ADDRESS` | Reprice — the ADR 0037 zone fee may change | None | Charge or refund a fee difference | Correction only if the total changed | Address field update |
| `CHANGE_FULFILLMENT_TIME` | Reprice if the time crosses a price plane | Re-evaluate hold expiry | None | None | Time field update |
| `CHANGE_CONTACT` | None | None | None | None | Field update |
| `SET_KITCHEN_NOTE` | None | None | None | None | Field update |
| `SET_CALLBACK_REQUESTED` | None | None | None | None | None |
| `SET_CASH_TENDERED` | None | None | None | None | None |

Rules the matrix does not carry:

- **Financial commands stop at a cut point**, default `READY`, resolved through
  ADR 0030 so a location can stop earlier. Past it the answer to "add a dessert"
  is a second order, honestly presented as one.
- **An increased total requires the customer's recorded agreement.** The operator
  attests it on the call; for an online-paid order the incremental payment must
  succeed before the revision commits. Charging more than the customer agreed to
  is the failure this prevents.
- **A decrease above a configured amount needs ADR 0027 four-eyes approval**, or
  "remove the two most expensive lines" is an unreviewed refund path.
- **One open amendment per order**, on a partial unique index; two operators on
  one order is routine, and the second loses the ADR 0031 version compare-and-set.
- **Never apply while a POS export attempt is unacknowledged.** The failure is a
  kitchen holding two tickets for one order and cooking the first. Where the ADR
  0011 adapter declares no amend capability and cancel-and-resend is unsafe at
  that stage, the amendment applies in Qoida and raises `MANUAL_ACTION_REQUIRED`
  telling the operator to phone the kitchen.

```text
DRAFT -> PRICED
PRICED -> AWAITING_CUSTOMER_CONFIRMATION -> AWAITING_PAYMENT -> APPLIED
PRICED -> APPLIED                     (no increase, no payment required)
any non-terminal -> REJECTED | EXPIRED
```

Expiry follows the ADR 0018 quote TTL of 15 minutes: an amendment holding an
inventory reservation against an unpriceable quote is the problem that TTL
already solves.

## Terminal outcomes

One row per order, unique on `order_id`. `COMPLETED`, `CANCELLED`, `REJECTED`,
and `EXPIRED` from the ADR 0019 state machine each require one, written in the
same transaction as the state transition.

The reason registry is tenant-managed and versioned, and every row carries a
system category from a closed platform-owned enum, because fifty near-duplicate
tenant reasons are inevitable and cross-tenant reporting cannot rest on free
text. Cancellation reasons additionally carry:

| Field | Values | What it decides |
|---|---|---|
| `stock_disposition` | `RELEASE`, `RETURN_TO_STOCK`, `WRITE_OFF`, `NO_EFFECT` | Which ADR 0017 movement the cancellation writes |
| `liability_party` | `TENANT`, `CUSTOMER`, `COURIER_PARTNER`, `PLATFORM` | Who carries the cost in the ADR 0043 reports |
| `customer_refund` | `FULL`, `NONE`, `DISCRETIONARY` | The default ADR 0013 refund posture |
| `internal_name` / `customer_text` | free text / localised ru, uz-Latn, en | What the operator picks versus what the customer is told |

The two texts are genuinely different statements. *Не дозвонились* is what the
operator needs in the list; the customer gets the softened wording the tenant
wrote. Publishing the internal name to a customer is what the split prevents.

**This closes ADR 0017's open input on cancellation restock.** Before the
inventory reservation is committed, cancellation always releases it and the
disposition is ignored. After commitment the disposition decides:
`RETURN_TO_STOCK` writes a return movement, `WRITE_OFF` a waste movement,
`NO_EFFECT` nothing — correct only for `UNTRACKED` items. A cancellation never
reopens a committed reservation, exactly as ADR 0017 says.

Completion reasons carry an `allowed_fulfillment_modes` set, validated on use.
Without it "Самовывоз выполнен" lands on a delivery order and both the courier
SLA report and the external-logistics settlement quietly lose that order.

## Operator-assisted ordering

**Lookup by phone is a POST with the number in the body**, never a query string,
resolving through the ADR 0015 keyed `normalized_hash`. That index is
deliberately not unique — a household shares a phone, a recycled number changes
owner — so a lookup may return several accounts and the operator picks one from
masked name plus last-order date. Picking is a selection, never a merge; ADR
0015's prohibition on automatic phone-based merging is not relaxed. Every lookup
is a `SECURITY`-class ADR 0027 audit fact, because this screen is a PII surface
pointed at the tenant's entire customer base.

**No match creates an account with `origin = 'OPERATOR'`**, extending ADR 0015's
schema, which has no origin column today:

```text
customer.customer_accounts  (added columns)
  origin              SELF_SERVICE | OPERATOR | IMPORT | AGGREGATOR | MIGRATION
  created_by_actor_id null for self-service
```

Such an account has no principal link, an `UNVERIFIED` contact point, and zero
consent decisions, so it is non-contactable for marketing until the person
themselves acts on a self-service channel. Transactional messages about the order
they just placed by phone use the contact point given for it under the
`ORDER_TRANSACTIONAL` purpose, which is not consent-gated. Whether an operator
may capture marketing consent verbally is the open input above; until legal
answers, they may not, and the UI offers no control for it.

**Change-due (Сдача) is an operational hint, not money.** The order carries
`cash_tendered_expected_minor` in whole som per ADR 0018, change owed is
`tendered − total` recomputed on every revision, and it is never a payment
transaction because no money has moved. An amendment pushing the total above the
tendered amount raises `CASH_TENDERED_INSUFFICIENT`, which the operator
acknowledges rather than being blocked by — the customer can hand over more.

**The callback flag creates work, not a status.** `callback_requested` surfaces
in the order list and is cleared only by an operator, recording
`callback_resolved_at` and `callback_resolved_by`. Making it a status would put a
customer-service concern inside a commercial state machine ADR 0019 keeps small.

What an operator may do when creating an order — offered payment methods and
fulfilment modes, whether change-due is captured, whether the callback toggle
appears, whether orders may be entered for another location — is a policy key set
resolved through ADR 0030 and snapshotted onto the order like every other ADR
0019 policy reference.

## Bulk actions

A bulk action is N independent commands under one `bulk_operation_id`, each in
its own transaction, each with a per-item idempotency key derived as
`{bulkKey}:{orderId}`. That derivation is what makes a re-run after partial
failure safe: items that already succeeded replay their stored ADR 0031
responses instead of executing twice. The endpoint returns `202` with the bulk id
and a per-item outcome list, never a single success or failure, and caps the
batch at 200. Bulk courier assignment is the first supported action; bulk
cancellation uses the same reason registry and approval thresholds as a single
cancellation, applied per item.

## Physical model

```text
ordering.orders  (added columns)
  current_revision, created_by_actor_type/id, accepted_by_actor_type/id
  accepted_at, callback_requested, callback_resolved_at/by
  cash_tendered_expected_minor null

ordering.order_revisions
  order_id, revision, source (CHECKOUT|AMENDMENT), amendment_id null
  pricing_quote_id, subtotal/tax/discount/fee/total_minor, created_by, created_at
  unique(order_id, revision)

ordering.order_lines  (extended)  ... plus revision_from, revision_to null

ordering.order_amendments
  id, tenant_id, order_id, status, base_revision, applied_revision null
  quote_id null, delta_total_minor, requires_approval, approval_request_id null
  customer_confirmation (attested_by, attested_at, channel)
  idempotency_key, expires_at, version, timestamps

ordering.order_amendment_commands
  amendment_id, sequence, command_type, payload_json, rejected_reason_code null

ordering.order_outcome_reasons  (+ _texts: reason_id, locale, customer_text)
  id, tenant_id, kind (CANCELLATION|COMPLETION), system_category, internal_name
  stock_disposition null, liability_party null, customer_refund null
  allowed_fulfillment_modes null, status, version, timestamps

ordering.order_outcomes
  order_id, kind, reason_id, reason_version, reason_snapshot_json
  actor_type/id, stock_disposition, liability_party
  inventory_movement_id null, refund_id null, occurred_at
  unique(order_id)

ordering.bulk_operations / bulk_operation_items
  bulk_id, action_type, actor, requested_count
  item -> order_id, item_status, item_problem_code null, item_idempotency_key
```

The reason snapshot on the outcome is deliberate duplication: renaming a reason
in the registry next year must not rewrite last year's funnel.

## APIs and events

```text
POST     /api/v1/operations/orders
POST     /api/v1/operations/customer-lookups
POST     /api/v1/operations/orders/{orderId}/amendments
POST     /api/v1/operations/orders/{orderId}/amendments/{amendmentId}/confirmation
DELETE   /api/v1/operations/orders/{orderId}/amendments/{amendmentId}
POST     /api/v1/operations/orders/{orderId}/cancellation
POST     /api/v1/operations/orders/{orderId}/completion
POST     /api/v1/operations/order-bulk-actions
GET      /api/v1/operations/orders/{orderId}/revisions
GET/POST /api/v1/control-plane/order-outcome-reasons

OrderAmendmentProposed / Applied / Rejected, OrderRevisionCreated
OrderCallbackRequested / OrderCallbackResolved
```

Every mutation carries an `Idempotency-Key`, the expected order version, and a
declared ADR 0025 capability at location scope, per ADR 0031. `OrderCancelled`
and `OrderCompleted` from ADR 0019 gain `reasonCode`, `systemCategory`,
`stockDisposition`, and `liabilityParty` as additive fields under ADR 0032. No
event carries a customer name, phone, address, or the internal reason text.

## Testing

- Applying an amendment leaves revision N−1 byte-identical, and a report pinned
  to revision 1 still reconciles to the original total.
- An amendment whose added line is unavailable applies nothing: no reservation,
  no quote acceptance, no revision. Two concurrent amendments on one order
  produce one applied result and one `STALE_VERSION`.
- An amendment attempted while a POS export is unacknowledged waits or raises
  `MANUAL_ACTION_REQUIRED`; it never applies underneath the export.
- Cancellation before commitment releases; after commitment it writes exactly the
  movement the reason's disposition names, and never both.
- A bulk of 200 with 3 failures reports 197 applied and 3 problems, and a re-run
  under the same bulk key changes nothing.
- An operator-created account returns false from `hasConsent` for every marketing
  purpose, and no operations endpoint can grant one.

## Rollout and rollback

Reason registries and terminal outcome recording go first, behind the existing
ADR 0019 cancellation and completion paths: additive, and they make the
cancellation funnel and write-off report possible before any amendment exists.
Then attribution and operator-created customers; then the non-financial commands,
which exercise the revision machinery with no money at risk; then the financial
commands one at a time, `ADD_LINES` last because it both reserves stock and
re-fiscalizes; then bulk actions.

Rollback disables amendment commands per location through the ADR 0030 key.
Revisions already created stay; the order simply accepts no new ones.

## Consequences

### Positive

- The call-centre workflow works as operators expect, without inventing a second
  order the customer never placed.
- Every order carries a terminal outcome with a reason, a stock disposition, and
  a liability party, so the cancellation funnel and the write-off report are
  derivable rather than reconstructed from status strings.
- ADR 0017's cancellation restock input is closed, and the answer lives on the
  reason rather than in an operator's judgement at 20:30 on a Friday.

### Negative

- Revisioned orders make every read revision-aware. A report joining order lines
  without pinning a revision double-counts, and the mistake stays invisible until
  someone reconciles a total by hand.
- Ten commands times five consequence axes is fifty code paths with their own
  failure modes. This is the most expensive capability in the post-pilot set.
- Tenant-managed reason registries drift into dozens of near-duplicates. The
  system category contains the damage for reporting but not for the dropdown.
- POS adapters without an amend capability turn an operator convenience into a
  phone call to the kitchen, and the operator will blame Qoida for it.
- Phone lookup is a broad PII read surface, and auditing every lookup adds real
  volume to the ADR 0027 store during peak hours.

### Accepted trade-offs

- The command set is closed, so a request fitting none of the ten needs an ADR
  entry rather than a configuration change. That is the price of every command
  having a defined fiscal consequence.
- Financial amendments stop at the cut point even when the kitchen could still
  accommodate the change, because past it the fiscal and POS consequences stop
  being reliably reversible.

## Exit criteria

An operator can add a dessert to a live order, switch it to cash, and correct the
address, and afterwards the order shows every revision with its own reproducible
total; every closed order carries one outcome row naming the reason, the stock
disposition, and the liable party; a re-run bulk action changes nothing; and an
operator-created customer receives no marketing message.
