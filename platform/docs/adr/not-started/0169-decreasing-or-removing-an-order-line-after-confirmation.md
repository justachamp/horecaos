# ADR 0169: Decreasing or removing an order line after confirmation

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the amendment that removes or reduces a line is
  declared and refused by name, and the inventory verb it needs does not exist.
  `AmendmentCommandType.REMOVE_LINES` is `built() == false`;
  `OperationsOrderController.AmendmentCommandRequest#toCommand` refuses it before
  anything is written; `AmendmentBasket#decreaseRefused` throws
  `QUANTITY_DECREASE_NOT_SUPPORTED` for a plain line lowered and for a combo count
  lowered, and `OrderAmendmentService#reserveIncrease` reserves and commits the
  increase of a quantity and has no counterpart. `InventoryReservationPort` is
  "deliberately three verbs" (`reserveForQuote`, `commit`, `release`) and its
  `release` can only act on a `HELD` reservation: `JdbcInventoryStore#transitionReservation`
  carries `WHERE status = 'HELD'`. The ledger is ready for the movements and nothing
  writes them: `inventory.movements.ck_movement_type` already admits `RETURN` and
  `WASTE` (V0019), the table is append-only for the application role, and
  `source_type`, `reason_code` and an idempotency key are in place. The order side is
  ready too and says so: `ordering.order_outcomes.inventory_movement_id` and `refund_id`
  exist (V0029) and are always null, with a comment that the port "is deliberately three
  verbs ... and writing a return or waste movement needs a fourth that belongs to
  inventory"; `ordering.domain.StockDisposition` names `RETURN_TO_STOCK`, `WRITE_OFF`
  and `NO_EFFECT`, and `OrderStateService` records the disposition of a cancellation
  after commitment without acting on it ("an open item on ADR 0039's checklist rather
  than a silent no-op"). A decrease has the other half of its machinery: ADR 0027
  four-eyes approval above `ordering.amendment_decrease_approval_threshold_minor`
  (`ApprovalAction.ORDERING_AMENDMENT_DECREASE`, wired in `requiresDecreaseApproval`),
  the cut point `ordering.amendment_cut_point_status` (default `READY`), the revision
  append with `order_lines.revision_to`, and `order_revisions.fiscal_correction_required`
  (V0395). `KitchenTicketService#syncAmendedLines` already strikes a `QUEUED` or `STARTED`
  ticket item whose order line stopped being live and remembers a `READY` one as made.
  Nothing restates the money plan after an amendment: only `kitchen` listens to
  `OrderAmendmentApplied`, and `OrderSettlementPort#restateTotal` has one caller,
  `CatchweightReconciliationService`.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0013, ADR 0017, ADR 0019, ADR 0025, ADR 0027, ADR 0029, ADR 0030,
  ADR 0031, ADR 0032, ADR 0038, ADR 0039, ADR 0041, ADR 0043, ADR 0046, ADR 0048,
  ADR 0072, ADR 0136, ADR 0137, ADR 0140, ADR 0158
- Supersedes / Superseded by: — (amends ADR 0017 by adding the one verb its port
  deliberately lacked, and reopens ADR 0017's open input "cancellation restock rules
  for QUANTITY tracking" for the case ADR 0039 did not close: a committed unit taken off
  an order that goes on; amends ADR 0039 by building the `REMOVE_LINES` row of its
  consequence matrix and by adding a third kind, `LINE_REMOVAL`, to its reason
  registry. It does not reopen any rejected row: ADR 0039 rejected "restock as an
  operator checkbox at cancel time", and this record keeps that rejection by taking the
  disposition from a reason the tenant authors once)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with that
  person and the work they block is marked.
  - **Decreasing an order the customer already paid through a provider** (finance,
    integration discovery). The money half is ADR 0048, which says the platform never
    calls a refund API: a person refunds in the provider's console and the platform
    records it. The fiscal half is ADR 0038's `CORRECTION_REQUESTED`/`REFUND` path,
    which is not built and whose provider primitives are the open input ADR 0039 already
    carries. Proposed default: a decrease or removal on an order whose payment
    projection is not `NOT_REQUIRED` is refused with
    `PAID_ORDER_DECREASE_NOT_SUPPORTED`, exactly as an increase is refused today with
    `INCREMENTAL_PAYMENT_NOT_SUPPORTED`; the operator's paths are a partial refund
    through the ADR 0048 console remedy with the line left on the order, or cancel and
    take a new order. Blocks: only this case; cash and pay-at-the-door orders, which are
    most of this market's volume, are unaffected.
  - **Whether a removal needs the customer's recorded agreement** (operations,
    product). ADR 0039 requires the attestation for an increase. A removal can be the
    customer's own request or the restaurant's ("we are out of it"). Proposed default:
    always required, the same attestation field, because the customer ordered the item
    and an unrecorded unilateral removal is the complaint this field answers.
  - **The starter removal reasons and their dispositions** (operations, product).
    Proposed default: three reasons seeded for every tenant and editable by it: "Customer
    changed their mind" (`RETURN_TO_STOCK`, liability customer), "Kitchen error"
    (`WRITE_OFF`, liability tenant), "Order entry error" (`RETURN_TO_STOCK`, liability
    tenant). An `ITEM_UNAVAILABLE` removal reason may never return to stock, because the
    stock is by definition not there; the service refuses that pairing.
  - **Whether the kitchen's own record overrides a reason that says return** (kitchen
    operations). Proposed default: yes. A unit the kitchen has started or finished is
    written off whatever the reason declares, and a unit it has not touched follows the
    reason; where no ticket exists the reason decides. The more conservative of the two
    facts wins, and the preview tells the operator which happened.
  - **What a write-off is worth** (finance). The ledger carries no cost and ADR 0017
    defers recipes and costing. Proposed default: the write-off report is in units by
    variant, reason and liability party, with no currency amount.
  - **Whether the cancellation path adopts the same primitive in the same change**
    (operations). Proposed default: yes, as the second caller, because ADR 0039 already
    decided its dispositions and a primitive with one caller has not been tested as an
    interface. This record does not change a cancellation decision.
  - **Whether removal needs a capability of its own** (platform owner). Proposed
    default: no. `order.amend` at the location, plus the approval threshold, as every
    other financial command has it; a new code is added only if an audit shows a role that
    must amend but never remove.

**To accept as written:** say "accept 0169". Every open input above is then closed on
its proposed default.

## Context

Row `1.2c` of `platform/docs/operations-gap-map.md` ("Amendments: add items, edit
customer/address/type/pre-order time, change payment type") is PARTIAL because of one
command and one missing verb: *"Held at PARTIAL for the one remaining command,
`REMOVE_LINES`, and for the decrease of a quantity, which has no ADR 0017 return/write-off
primitive either."* Its "Blocked by" text: *"Removing/decreasing a line needs an ADR 0017
primitive (return-to-stock or write-off) the port does not have; that module's decision, not
this wave's."* The open-decision table repeats it: *"An ADR 0017 amendment naming the fourth
primitive."* This is that record.

**What the operator has today, and what it costs.** An operator who must take a dish off a
confirmed order is told, by the refusal text in `AmendmentBasket#decreaseRefused`, to
"withdraw this amendment and place a new order for the corrected quantity". ADR 0039's
Context explains why that is the wrong answer in a call-centre business: a second order
changes the number the operator just read out, the promise, the receipt and the courier's
assignment. Six of the seven financial commands are built; this is the seventh, and the one
an operator reaches for most often after "add a dessert".

**Why the verb cannot be `release`.** ADR 0017's reservation is `HELD`, then `COMMITTED`,
`RELEASED` or `EXPIRED`, and a terminal status is never left: the `status = 'HELD'`
predicate inside the `UPDATE` is what stops "a release arriving after a commit" from
"undoing" a sale. For a `QUANTITY` item, `commit` takes the units out of `on_hand_quantity`
and `reserved_quantity` together and writes a `SALE_COMMITMENT` movement. A unit taken off
the order after that has only two honest futures: the food was not made and goes back on the
shelf, or it was made and is lost. Both are facts the ledger must hold, and neither can be
written by a status flip. ADR 0017's own accepted trade-off states the intent: "a cancellation
after commit creates a return or waste decision instead of silently restocking, which is more
work and more accurate." ADR 0039 decided the cancellation half (`stock_disposition` on a
reason, never a checkbox) and left `order_outcomes.inventory_movement_id` null because the
verb is missing; this record supplies the verb for both callers.

**The window between "confirmed" and "committed" is real.** `OrderStateService` sets
`confirmedAt` in the confirming transaction and enqueues the commit; `OrderInventoryProcess`
carries it out afterwards, durably, with retries. During that window the order is confirmed
and the reservation is still `HELD`. `OrderOutcomeService` treats "committed" as
`order.confirmedAt() != null`, which is the right question for a cancellation (it releases
or disposes the whole claim at once) and the wrong one for a partial decrease: a unit
returned to stock while the commit is still queued would be subtracted again when the commit
runs. The primitive therefore cannot take its branch from the order's status; it has to read
the reservation, in the same transaction, and act on what inventory actually did.

**An amendment commits its increase immediately, under a quote of its own.**
`OrderAmendmentService#reserveIncrease` calls `reserveForQuote` and `commit` for the
amendment's quote regardless of the order's status, so an order can hold a `HELD` original
reservation and a `COMMITTED` amendment reservation at once, each under a different quote id
(`order_revisions.pricing_quote_id`). A line's units are not tied to one reservation, only to
a variant at a location, and the primitive has to be keyed by what the order no longer needs
rather than by which reservation first held it. It also means a cancellation before
confirmation, which releases `order.pricingQuoteId()` only, leaves an amendment's committed
increase sold; the same verb closes that.

**Money and fiscal facts the amendment must respect.**

- A cash or pay-at-the-door order has a `payments.order_settlements` plan whose single money
  tender is `PLANNED` with no payment intent. `OrderSettlementService#restateTotal` exists for
  exactly that shape (ADR 0137 uses it after weighing) and refuses anything else. No
  amendment calls it today, so the cash the courier app shows (`cashDueMinor`) is the figure
  planned at checkout, before any amendment. A removal that lowers the total and leaves the
  plan alone tells the courier to collect too much.
- An order the customer paid through Click or Payme is the case ADR 0048 keeps outside the
  platform's hands: no refund API, a person executes, the platform records. The fiscal
  receipt for such an order was produced at capture, and ADR 0038's `CORRECTION_REQUESTED`,
  `VOID_REQUESTED`, `CORRECTED` and `VOIDED` states are drawn and unreachable.
- Fiscalization of a cash sale happens at completion (`FiscalObligationService` opens a `SALE`
  obligation for a completed order), and a financial amendment stops at the cut point, so a
  removal on a cash order precedes the receipt; `order_revisions.fiscal_correction_required`
  is the marker V0395 introduced so an amendment "must never silently skip the consequence it
  declares".

**Three facts the kitchen already holds decide a disposition better than a person can.** A
ticket item is `QUEUED`, `STARTED`, `READY` or `CANCELLED` (`TicketItemStatus`); a started or
finished item is a made dish, and the kitchen's amendment sync already records that
distinction for a replaced line. A reason that says "return to stock" for a dish that has
been cooked is a reason applied to a case its author did not mean.

## Decision

**Add one inventory verb, `dispose`, that gives back or writes off the stock an order no
longer needs, reading the reservation to decide what is still a hold and what is already a
sale; build `REMOVE_LINES` and the decrease of `CHANGE_LINE_QUANTITY` on it for orders that
are not paid through a provider; take the stock disposition from a tenant-authored removal
reason, never from a checkbox; and let the kitchen's record of what has been cooked make
the disposition more conservative, never less.**

1. **One verb, three outcomes, keyed by the order.** `InventoryReservationPort` gains
   `dispose(StockDisposalCommand)`: tenant, brand, location, order id, the order's quote
   ids, the units no longer needed per variant, the units the kitchen has already started or
   made, the declared disposition, a reason code, the actor and an idempotency key. For each
   variant, inventory takes the units first from the order's `HELD` reservation lines
   (release: reduce the reservation line and `reserved_quantity`, no movement, exactly what
   `release` does for a whole hold), then applies the declared disposition to the units that
   were already sold. `RETURN_TO_STOCK` appends a `RETURN` movement and raises
   `on_hand_quantity`. `WRITE_OFF` appends the `RETURN` and then a `WASTE` movement in the
   same transaction, so the ledger reads "sale reversed, goods lost" and `on_hand` is net
   unchanged. `NO_EFFECT` writes nothing. Units the kitchen has started or made are written
   off whatever the declared disposition is. Only `QUANTITY`-tracked items have a position to
   move; a `BINARY` or `UNTRACKED` item gets no movement, and the removal fact in `ordering`
   is its record.
2. **Inventory decides from its own data, in one transaction.** The branch is taken from the
   reservation's status and lines at the moment of the call, under the position row lock,
   not from the order's status. A `HELD` hold that has meanwhile expired counts as already
   free. A replay with the same idempotency key returns the first answer and moves nothing
   (the unique key `(tenant_id, stock_item_id, idempotency_key)` already on the ledger).
3. **The disposition comes from a reason, as ADR 0039 decided for cancellation.** A removal
   or decrease names a `removalReasonId`: a tenant-authored row of
   `ordering.order_outcome_reasons` with the new kind `LINE_REMOVAL`, carrying a system
   category for cross-tenant reporting, a stock disposition (`RETURN_TO_STOCK`, `WRITE_OFF`
   or `NO_EFFECT`) and a liability party. The reason is snapshotted onto the removal fact so
   renaming it next year does not rewrite this year's report. The operator picks a reason;
   they never pick a disposition.
4. **The kitchen can only make it more conservative.** Ordering asks the kitchen, through a
   port ordering declares and `kitchen.infrastructure.ordering` implements, whether each
   affected line is not started, started or made. Units removed are taken from the not-started
   ones first; any removed unit beyond them was started or made and is written off. A
   missing ticket or an unreadable answer is `UNKNOWN` and the reason decides. The amendment's
   preview says which happened before it is applied.
5. **One amendment command, one preview, one revision.** `REMOVE_LINES` takes order line ids
   and a `removalReasonId`; `CHANGE_LINE_QUANTITY` lowering a quantity takes the same reason.
   Both reprice the whole basket through the same pricing path as every other command, append a
   revision whose superseded lines get `revision_to` and are never rewritten, and are refused
   if they would leave the order with no live line (`WOULD_EMPTY_ORDER`: that is a cancellation
   and takes ADR 0039's cancel path with its own reason). A combo goes as a whole combo, in
   whole combos, by the rule `AmendmentBasket` already applies to an increase; a single
   component cannot be removed.
6. **The money follows the order's shape.** On an order whose payment projection is
   `NOT_REQUIRED`, apply calls `OrderSettlementPort#restateTotal` so the cash the courier is
   told to collect matches the new total, and does the same for an increase, which the build
   currently does not. On any other order the command is refused with
   `PAID_ORDER_DECREASE_NOT_SUPPORTED`. A removal whose reprice raises the total (a lost
   free-delivery threshold, for instance) takes the increase rules of ADR 0039: the customer's
   recorded agreement, and refusal on a provider-paid order.
7. **The fiscal consequence is a marker now and a document later.** Every revision a removal
   produces sets `order_revisions.fiscal_correction_required`, as ADR 0039's matrix row
   ("correction if a receipt exists") requires, and the sweep that eventually processes that
   marker treats "no fiscal document exists" as nothing to do. No fiscal document is created
   by this record. The provider-paid case, where a receipt does exist, is the refused case.
8. **A removal is audited and evidenced twice.** An ADR 0027 fact written in the same
   transaction as the revision (`ordering.order.line-removed`), and a first audit fact from
   the inventory module (`inventory.stock.disposed`), which until now recorded none. The
   evidence row is `ordering.order_line_removals`, append-only, one per line per amendment.
9. **The approval threshold stays money.** The existing `ORDERING_AMENDMENT_DECREASE`
   four-eyes approval applies when the total falls by at least the configured amount. A
   write-off has no money value in the ledger, so it adds no threshold of its own; the
   abuse path "remove the expensive lines" is the one the money threshold already closes.
10. **A rollout gate.** `ordering.amendment_line_removal_enabled` (BOOLEAN, scope BRAND, code
    default `false`, ADR 0030). With it off the command is refused as it is today.
11. **The same verb is cancellation's missing half.** After a cancellation commits, the units
    the order still holds (the live lines, which include any amendment's increase) take the
    reason's disposition through `dispose`, and `order_outcomes.inventory_movement_id`
    finally means something. This record decides no cancellation rule that ADR 0039 did not;
    it supplies the verb and the test that both callers agree.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep refusing; "take a second order" | The status quo. A second order changes the number, promise, receipt and courier assignment, which is the case ADR 0039 exists to avoid. Six of seven financial commands exist and this is the one operators reach for after "add a dessert" | The owner decides amendments are not worth the ledger work and cancel-and-retake is the permanent answer |
| Let the operator pick return or write-off in the removal dialog | ADR 0039 rejected exactly this for cancellation: under pressure operators choose whatever closes the dialog fastest and the write-off rate becomes noise | A tenant shows real per-incident variance inside one reason; then an override with an ADR 0027 approval that records the deviation, as ADR 0039 allowed |
| Two port verbs, `returnToStock` and `writeOff` | A second public method for what is one decision with one idempotency key and one transaction, and ordering would have to know whether a unit was held or sold in order to call the right one | A third caller needs different transaction semantics |
| Reopen the committed reservation (`COMMITTED` back to `HELD`, then release) | ADR 0017: a cancellation never reopens a committed reservation. The ledger would lose the fact that the sale happened and a late `commit` could revive stock | Never |
| Write off as one `WASTE` movement | The sale commitment already removed the units from `on_hand`; a lone `WASTE` removes them twice, and a zero-delta waste hides the quantity. The `RETURN` plus `WASTE` pair keeps `on_hand` equal to the sum of the ledger | ADR 0017 adds a negative-stock policy or a distinct "lost after sale" movement type |
| An operator `CORRECTION` through `inventory.adjust` | Not linked to the order or to a reason, needs a capability the call-centre role does not hold, and leaves the sale standing in the ledger | Never as the removal path |
| Branch on the order's status instead of the reservation | The confirmed-but-not-yet-committed window makes a return double-count when the queued commit runs | Commit becomes synchronous with confirmation |
| Allow a decrease on a provider-paid order now, recording an ADR 0048 refund | The money half needs a person at the provider console, and the fiscal half (ADR 0038 correction or refund receipt) is unbuilt and its provider primitives unanswered | Finance answers the fiscal-correction question ADR 0039 carries and the `REFUND` document path is built |
| Treat every removal as a release | Correct before commitment, and after it leaves sold stock off the shelf: a phantom shortage that ends in a wrong sold-out | Never |
| Derive the disposition from the kitchen alone, with no reason registry | A heuristic with no liability party and no category, so the ADR 0043 reports lose "who carries the cost". The kitchen fact is kept, but as an override | Tenants say a reason per removal is overhead and accept a fixed rule |

## Consequences

### Positive

- The seventh financial command is built, and an operator can take a line off a live order
  without a second order, a new number or a new receipt.
- The ledger tells the truth about a removal: a returned unit and a lost unit are different
  facts with a reason and a liability party, and `order_outcomes.inventory_movement_id`, null
  since V0029, is finally filled.
- The disposition follows what actually happened in the kitchen, so "customer changed their
  mind" cannot restock a dish that was cooked.
- The cash a courier is told to collect now matches an amended order, which the existing
  increase path does not achieve.
- One verb serves both a removal and a cancellation after commitment, so the two cannot drift.

### Negative

- `inventory` stops being a module that ordering only asks to hold, sell and release. It gains
  a verb that moves stock after a sale, a first audit fact and a first non-sale ledger caller.
  Getting the held-versus-sold branch wrong either double-counts or loses stock, and the
  window it exists for is a few seconds wide and easy to miss in tests.
- A `LINE_REMOVAL` reason kind widens a table, its constraints and its screen, and a tenant
  that has no active removal reason cannot remove a line until it has one; the starter set
  mitigates, and does not remove, the setup.
- Ordering depends on a kitchen port for its disposition. A tenant that runs no kitchen
  display gets `UNKNOWN` and the reason's disposition, which is the weaker guarantee.
- A provider-paid order still cannot lose a line by amendment. The operator's path is a
  partial refund with the line left on the order, which is correct in the books and unlovely
  on the receipt.
- Reporting must learn that a revision can lose lines, and the order read must keep showing
  superseded lines to the history screens that already show them.

### Accepted trade-offs

- Write-off is in units, not money, because the platform has no costs. The write-off report is
  honest about quantity and silent about value until ADR 0017's recipes and costing exist.
- A removal needs the customer's recorded agreement even when the customer asked for it. That
  is one tick for the operator and evidence for the dispute.
- Units taken off a half-started line are written off after the not-started ones are returned,
  which may write off a dish the kitchen would have finished and the customer would have kept.
  The alternative needs per-unit kitchen state, which the ticket does not carry.
- The cut point stays `READY`. Past it the answer remains a second order, and so does a
  removal after the courier has the food.

## Specification

### Physical model

```text
ordering.order_outcome_reasons   (widened; V0029 table)
  kind                      CANCELLATION | COMPLETION | LINE_REMOVAL
  system_category           for LINE_REMOVAL: CUSTOMER_CHANGED_MIND | ITEM_UNAVAILABLE
                            | KITCHEN_ERROR | ORDER_ENTRY_ERROR | QUALITY_ISSUE | OTHER
  stock_disposition         required for CANCELLATION and LINE_REMOVAL
                            (LINE_REMOVAL admits RETURN_TO_STOCK | WRITE_OFF | NO_EFFECT only)
  liability_party           required for CANCELLATION and LINE_REMOVAL
  customer_refund           CANCELLATION only; null for LINE_REMOVAL
  allowed_fulfillment_modes COMPLETION only
```

`ck_outcome_reason_kind`, `ck_outcome_reason_category`, and the three equivalence checks
(`ck_outcome_reason_disposition`, `_liability`, `_refund`) are dropped and re-added widened
in one migration, the move V0443 made for `catalog.translations`. The service refuses a
`LINE_REMOVAL` reason in category `ITEM_UNAVAILABLE` whose disposition is `RETURN_TO_STOCK`.
The starter reasons are inserted for every existing tenant, with ru, uz-Latn and en customer
texts in `ordering.order_outcome_reason_texts`, and by tenant activation for new ones.

```text
ordering.order_line_removals          -- append-only; SELECT, INSERT only
  id uuid pk, tenant_id, brand_id, location_id, order_id
  amendment_id, revision integer, order_line_id uuid      unique (amendment_id, order_line_id)
  variant_id uuid
  quantity_before numeric(10,3), quantity_after numeric(10,3)        -- after = 0 for a removal
  stock_units integer                                       -- difference of the two ceilings
  released_units integer, returned_units integer, written_off_units integer, no_effect_units integer
  reason_id uuid, reason_version integer, reason_category varchar(32), reason_snapshot_json jsonb
  disposition_declared varchar(24), disposition_effective varchar(24)
  kitchen_state varchar(12)                                 -- NOT_STARTED | STARTED | MADE | UNKNOWN
  liability_party varchar(24)
  inventory_movement_ids uuid[] not null default '{}'
  approval_request_id uuid null
  actor_type varchar(16), actor_id varchar(255), occurred_at timestamptz
  check released + returned + written_off + no_effect = stock_units
```

Quantities and stock units follow the rule the increase already uses
(`AmendmentBasket#increases`): stock units are the difference of the two whole-unit ceilings,
so 1.5 portions lowered to 1 gives back one plate and 1 lowered to 0.5 gives back none, and a
combo's units are its component lines. Tenant and brand are on the row and in its keys; the
migration ends with an explicit `GRANT SELECT, INSERT ... TO horecaos_application`, and the
next free migration number is taken at implementation time from every active worktree
(AGENTS.md). `ordering.order_outcomes.inventory_movement_id` is populated by the cancellation
caller.

No new inventory table. Movements use what V0019 provides: `RETURN` with `quantity_delta > 0`
and `reason_code = 'LINE_REMOVED'`; `WASTE` with `quantity_delta < 0` and `reason_code` equal
to the reason's system category; `source_type = 'ORDER'`, `source_id` the order id; the
idempotency key `dispose:{amendmentId}:{orderLineId}:{stockItemId}:{leg}` with `leg` one of
`return` and `waste`.

### The verb

```java
InventoryReservationPort#dispose(StockDisposalCommand c) -> StockDisposal

StockDisposalCommand(tenantId, brandId, locationId, orderId, List<UUID> orderQuoteIds,
    List<Line(variantId, int units, int unitsStartedOrMade)> lines,
    Disposition declared  /* RETURN_TO_STOCK | WRITE_OFF | NO_EFFECT */,
    String reasonCode, String actorType, @Nullable UUID actorId, String idempotencyKey)

StockDisposal(List<LineResult(variantId, releasedUnits, returnedUnits, writtenOffUnits,
    noEffectUnits, List<UUID> movementIds)>)
```

Per variant, under the position row lock and in the caller's transaction: (1) `held` is the
sum of that variant's reservation lines across the order's quote ids whose reservation is
`HELD`; release `min(units, held)` by reducing the line and `reserved_quantity`; (2) the
`sold = units - released` that remain: `forced = min(sold, unitsStartedOrMade)` are written
off; the rest follow `declared`; (3) a `QUANTITY` item gets the movements, any other mode
gets none and the counts still come back. The call refuses `units` that exceed what the order
holds (`DISPOSAL_EXCEEDS_ORDER_CLAIM`), writes nothing on refusal, and is rejected inside an
import (`ImportSuppression`) as `commit` and `release` already are. `ModularArchitectureTests`
stays green: the verb is declared in `inventory.api`; the kitchen port is declared in
`ordering.application` and implemented in `kitchen.infrastructure.ordering`, the direction
`JdbcKitchenOrderSource` already takes.

### Amendment behaviour

```text
REMOVE_LINES          { orderLineIds[1..10], removalReasonId }
CHANGE_LINE_QUANTITY  { orderLineId, quantity, removalReasonId when quantity < current }
```

`propose` reprices through `repriceFor`, computes the preview (`stockConsequence` per
command: released, returned, written-off and no-effect units, and the kitchen state that
forced a write-off), stores it in the command payload, and requests the ADR 0027 approval
and the customer's attestation as for any decrease. `apply` re-reads everything under the
order's row, calls `dispose` once per affected variant with the amendment's idempotency key,
writes the revision, the `order_line_removals` rows, the audit facts and, for a
`NOT_REQUIRED` order, `restateTotal`; any refusal rolls the whole transaction back. The
kitchen is told by the existing `OrderAmendmentApplied` listener, which already strikes
un-made items and notes made ones. The commands of one amendment are applied in sequence
order and share one transaction.

Stable refusal codes (ADR 0031, Problem Details with `reasonCode`):
`REMOVAL_REASON_REQUIRED`, `REMOVAL_REASON_NOT_FOUND`, `REMOVAL_REASON_ARCHIVED`,
`WOULD_EMPTY_ORDER`, `COMBO_COMPONENT_NOT_REMOVABLE`, `LINE_NOT_LIVE`,
`PAID_ORDER_DECREASE_NOT_SUPPORTED`, `AUTO_ADD_LINE_MANDATORY` (ADR 0158),
`AMENDMENT_PAST_CUT_POINT` and `CUSTOMER_CONFIRMATION_REQUIRED` as today. The generic
`QUANTITY_DECREASE_NOT_SUPPORTED` is retired once the command is enabled.

### APIs, capabilities, events

- `POST /api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/{orderId}/amendments`
  is unchanged in path; `AmendmentCommandRequest` gains `orderLineIds` and `removalReasonId`;
  `AmendmentResponse` gains `stockConsequence`. `ORDER_AMEND` at `LOCATION` scope, an
  `Idempotency-Key` and `If-Match`, as today.
- The reasons endpoints at `/api/v1/operations/tenants/{tenantId}/order-outcome-reasons` accept
  and list `kind = LINE_REMOVAL` and serve its categories; `ORDER_OUTCOME_REASON_MANAGE` writes,
  `ORDER_READ` reads. No new capability.
- One event through the outbox on `inventory.events` (ADR 0032), `InventoryStockDisposed`,
  with a schema file under `src/main/resources/events/inventory.events/` and an `EventCatalog`
  entry before the producer ships: order id, brand, location, disposition, reason category and
  lines of `{variantId, units}`. No customer, address or note; `OrderAmendmentApplied` is
  unchanged. Reporting consumes it for the write-off report ADR 0039 promised.
- Audit (ADR 0027): `ordering.order.line-removed` and `inventory.stock.disposed`, written with
  the change; they carry ids, counts, the reason id and category, the approval id and never a
  customer note.
- PII (ADR 0029): the removal row holds none. A free-text note is not part of this record; if
  one is added later it is `PERSONAL` and encrypted as `note_encrypted` is on the outcome.
- Policy (ADR 0030): `ordering.amendment_line_removal_enabled` is new; the cut point and the
  decrease threshold are reused. A removal persists the reason id and version it used.
- Metrics: `horecaos.inventory.disposed{disposition,mode}` and
  `horecaos.order.line_removals{reason_category,outcome}`; no identifier in a label.

### Front-end contract

The order detail's amend menu gains "Remove line" and the quantity dialog may now lower a
quantity. Both ask for a removal reason, show the preview ("2 portions will be written off:
the kitchen has started them") before apply, and send the customer's recorded agreement. A
tenant with no active removal reason sees a sentence linking to the reasons settings. The
reasons settings screen gains the third kind. Strings in ru, uz-Latn and en.

### Testing

- `StockDisposalTests` against real PostgreSQL: a `HELD` hold released by line; a committed
  unit returned; written off as the `RETURN`/`WASTE` pair with `on_hand` net unchanged; the
  confirmed-but-uncommitted window (return, then the queued `commit`, no double effect);
  an expired hold; `BINARY` and `UNTRACKED` items writing no movement; a replay moving nothing;
  disposal above the order's claim refused; two concurrent disposals on one item serialising.
- `OrderAmendmentAndOutcomeTests`-style cases for each command: a plain line, a decimal portion,
  a combo, the empty order, a combo component, a mandatory auto-added line.
- Kitchen override: a started item forces a write-off whatever the reason says; no ticket falls
  back to the reason.
- `restateTotal` is exercised on removal and, as the regression this record found, on `ADD_LINES`.
- A provider-paid order refuses with the stable code; a cash order applies; approval pending and
  declined paths as for any decrease.
- Migration tests for the widened constraints, including that an `ITEM_UNAVAILABLE` reason
  cannot return to stock.
- A cancellation after commit and an amendment-committed increase both end with a matching
  `inventory_movement_id`, and removing a line then cancelling returns each unit once.

## Rollout and rollback

Land the migration (reason kind, removal table, starter reasons) and the inventory verb with no
caller. Prove `dispose` and the cancellation wiring on a test tenant first, because the
cancellation path already has its decisions and is the cheaper place to find a bug in the verb.
Then enable `ordering.amendment_line_removal_enabled` for one brand. The refusal text changes
from "withdraw and re-place" to the removal path only for brands that have it on. Rollback is the
key: set it false and the command is refused as today. Movements and removal rows already written
are evidence and stay; nothing is deleted, and a mistaken removal is corrected by an amendment
that adds the line back (which reserves it anew), not by editing the ledger.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Migration: widen the reason table's constraints for `LINE_REMOVAL`; starter reasons for
      every tenant and for tenant activation; `ordering.order_line_removals`; explicit grants.
- [ ] `InventoryReservationPort#dispose`, `StockDisposalCommand`, `StockDisposal`; the store
      methods for partial release of a held line, the `RETURN` movement and the `WASTE` movement;
      `ImportSuppression` guard; the first inventory audit fact; the outbox event, its schema
      file and its catalogue entry.
- [ ] Kitchen production-state port in ordering and its implementation in
      `kitchen.infrastructure.ordering`.
- [ ] `AmendmentCommandType.REMOVE_LINES` built; `AmendmentBasket` decrease path and the combo
      rules; preview; `requiresDecreaseApproval` and attestation for removals;
      `restateTotal` on apply for `NOT_REQUIRED` orders, including `ADD_LINES`;
      `PAID_ORDER_DECREASE_NOT_SUPPORTED`.
- [ ] `OrderOutcomeService`/`OrderStateService` call `dispose` after a cancellation commits and
      fill `inventory_movement_id`.
- [ ] Reason service, controller and settings screen for the third kind.
- [ ] Console menu, dialog, preview and strings; the removal-reason empty state.
- [ ] Reporting: the removal fact and the write-off report in units (ADR 0043).
- [ ] Configuration key and its mirrored registry declaration; metrics.
- [ ] Tests above; ADR 0017 and ADR 0039 status lines updated to point here; gap-map row `1.2c`
      re-audited.

## Exit criteria

An operator removes one of two burgers from a confirmed cash order, choosing "Customer changed
their mind": the total falls, the courier app shows the new cash due, the ticket item is struck if
it was not started, a `RETURN` movement is on the ledger for a `QUANTITY` item, a removal row and
two audit facts exist, and repeating the request with the same `Idempotency-Key` writes nothing
more. The same removal after the kitchen has started the dish writes the `RETURN` and `WASTE`
pair and the preview said so before apply. A provider-paid order answers
`PAID_ORDER_DECREASE_NOT_SUPPORTED`. Cancelling that order afterwards disposes of each remaining
unit once. `order_outcomes.inventory_movement_id` is non-null for a cancellation after commit.

## References

- ADR 0013 (refund and compensation), ADR 0017 (ledger, reservations, cancellation restock input),
  ADR 0019, ADR 0025, ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0038 (fiscal
  documents and the unbuilt correction states), ADR 0039 (the consequence matrix, the reason
  registry, the cut point), ADR 0041 (ticket items), ADR 0043, ADR 0046, ADR 0048 (refunds are
  bookkeeping), ADR 0072, ADR 0136, ADR 0137, ADR 0140, ADR 0158
- `platform/docs/operations-gap-map.md` rows `1.2c` and `2.1d` and the open-decision table entry
  "An ADR 0017 amendment naming the fourth primitive"
- `AmendmentCommandType`, `OrderAmendmentService`, `AmendmentBasket`, `OperationsOrderController`
  (`AmendmentCommandRequest`), `OrderOutcomeService`, `OrderStateService`, `OrderInventoryProcess`,
  `StockDisposition`, `InventoryReservationPort`, `InventoryService`, `JdbcInventoryStore`,
  `KitchenTicketService#syncAmendedLines`, `KitchenAmendmentListener`, `OrderSettlementService#restateTotal`,
  `CatchweightReconciliationService`, `FiscalObligationService`
- `V0019` (movements, reservations), `V0029` (outcomes, reasons, amendment commands),
  `V0395` (`fiscal_correction_required`), `V0030` (ticket items), `V0443` (the constraint-widening precedent)
