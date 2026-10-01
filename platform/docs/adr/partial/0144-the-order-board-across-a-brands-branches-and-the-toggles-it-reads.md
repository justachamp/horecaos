# ADR 0144: The order board across a brand's branches, and the toggles it reads

- Decision status: Proposed
- Implementation status: Partial — built (wave 16, gap map rows `1.1`, `1.1c`, `1.1e`):
  `GET /api/v1/operations/tenants/{t}/brands/{b}/orders/board` (`ORDER_READ` at
  `BRAND`) reads the branch board's one statement over a set of branches
  (`JdbcOrderStore.listForLocation` with `OrderListQuery.locationIds`); every row
  carries its `locationId` and its `actions[]` from the caller's grants at that
  branch; `marketplaceBindingId` filters one aggregator binding, with
  `GET .../orders/marketplace-bindings` (branch and brand) as its option read
  model; `late`, `problem`, `callbackRequested` and `fiscalStatus` are the four
  secondary toggles orders.md §2.4 marks not read by ordering, all four now read;
  `ISSUE_INVOICE` is emitted for an order whose payment projection is `PENDING`
  **and** whose live payment intent can be presented (open, provider tender,
  seller set, no attempt in doubt), when the caller holds `PAYMENT_INITIATE` at
  tenant scope, and the existing `POST .../payment/re-presentations` writes an
  audit fact. Console: «Все филиалы»
  with a Филиал column and filter, the binding select, the toggles, the fiscal
  select and the header/row «Выставить счёт». Not built: bulk actions across
  branches (the selection column is withheld on «Все филиалы»), a `BRAND`-scoped
  realtime channel for the brand board (it polls every ten seconds), a branch
  subset for a principal whose grants are scattered branch by branch (they keep
  the branch board), and a per-row fiscal chip.
- Date proposed: 2026-09-30
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-30; Ayubkhon Abbosov (platform owner) decides
- Depends on: [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0030](../built/0030-configuration-and-policy-resolution.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0102](../built/0102-the-order-board-query-reads-what-the-board-shows-wave-p04.md),
  [ADR 0038](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md),
  [ADR 0040](../partial/0040-marketplace-channel-and-partner-api.md),
  [ADR 0013](../partial/0013-payment-refund-and-service-recovery-compensation.md)
- Supersedes / Superseded by: — (extends ADR 0102's decision 4; does not reopen it)
- Open inputs:
  - Whether an operator with grants scattered across branches (not a brand-level
    read) should get a board over just those branches — owner: platform owner.
    Today they keep the branch board, which is what the 403 from the brand board
    tells the console.
  - Whether the brand board is pushed over the ADR 0045 realtime stream at `BRAND`
    scope or stays poll-only — owner: platform owner, once a multi-branch tenant
    runs it on a wallboard.
  - Whether bulk actions across branches are wanted, as one request per branch
    with one merged partial-failure panel — owner: operations product.

## Context

The order board (ADR 0102) is hard-scoped to one location by its path. A tenant
with several branches therefore has no view of them together: a supervisor
switches the shell's branch picker and reads each queue in turn, and the
Филиал column orders.md §2.5 specifies has never had a row to show. The same
screen still lacked four of §2.4's secondary toggles (the spec marks them "not
read by ordering"), a way to filter to one aggregator installation rather than
the coarse origin toggle, and — for an order waiting on an online payment — the
«Выставить счёт» action §4.9 places beside the payment, whose endpoint
(`POST .../orders/{id}/payment/re-presentations`, wave P12) existed but was
neither audited nor offered from `actions[]`.

The constraint that makes the first of these non-obvious is ADR 0102's own
lesson: a filter applied after a page is cut makes a short page ambiguous, and
two implementations of one query drift. So the wider board cannot be a second
query, and the predicates that are policy applied to a clock cannot run in the
browser.

## Decision

1. **The brand board is the branch board's statement with a wider set of
   branches, not a second query.** `OrderListQuery` names `locationIds`; the
   branch endpoint always passes exactly one (its path variable), the brand
   endpoint passes the operator's chosen branches or none for the whole brand.
   The tenant and brand predicates are unconditional; only the location
   predicate widens. Validation and paging live once (`OrderBoardFilters`) and
   both controllers call them. The endpoint needs `ORDER_READ` at `BRAND`; a
   location-scoped grant is refused with 403, which the console reads as "keep
   the branch board" (the same routing answer the live board already uses).

2. **A row's `actions[]` is computed from the caller's grants at that row's
   branch**, once per distinct branch on the page. A brand-level read does not
   lend the mutation buttons of a branch the caller cannot act at.

3. **The aggregator binding is a predicate plus a small read model.**
   `marketplaceBindingId` filters `ordering.orders.marketplace_binding_id`
   (V0038); `GET .../orders/marketplace-bindings` lists the bindings the orders
   in scope arrived through, with counts from ordering's own table and names
   through `MarketplaceBindingLookup` (the seam manual aggregator entry already
   uses), so ordering still names no `integration` table.

4. **The four toggles are read in the statement.** `late` resolves the
   `ordering.lateness` policy (ADR 0030) for each branch in scope and applies
   `OrderLatenessPolicy`'s rule as a predicate — a test holds the SQL to
   `OrderLatenessPolicy.evaluate`. `problem` is an `EXISTS` over
   `ordering.order_process_states`. `callbackRequested` reads `callback_requested`
   (V0029). `fiscalStatus` is an `EXISTS` over `fiscal.fiscal_documents` — a
   fourth cross-schema read beside ADR 0102's three, under the same rule (a read
   model and a read permission; no ordering write path gains a cross-schema
   statement).

5. **`ISSUE_INVOICE` is emitted where the existing endpoint can succeed.** Three
   facts, and the payment projection is only the first: the order's
   `payment_status_projection` is `PENDING` (checkout writes it only for a
   payment-first order) and the order has not ended; **the order's live payment
   intent can be presented** — open (`PENDING`/`AUTHORIZING`), a provider tender
   with a seller, and none of its attempts in a state
   `PaymentAttemptStatus.rePresentable()` forbids (`UNCERTAIN`); and the caller
   holds `PAYMENT_INITIATE` **at tenant scope**, the scope the endpoint declares,
   so a location-scoped answer never offers a button the endpoint would refuse.

   The projection cannot decide alone. `PaymentAttemptService.applyToIntent`
   publishes nothing to it for an attempt that expired or whose outcome is
   uncertain, so a Payme reservation that ages out leaves the order in
   `PAYMENT_AUTHORIZING` with the projection still `PENDING` and the intent
   `EXPIRED` — where the endpoint answers `404 NO_PAYMENT_INTENT` (and
   `409 PAYMENT_IN_DOUBT` for an uncertain attempt). The intent is read through
   `PaymentIntentPort.ordersWithPresentablePayment`, one call per page of rows and
   only for rows whose projection is `PENDING` and which have not ended, so
   `ordering` still joins no `payments` table for a second field on a row (the
   `ActiveCourierAssignmentsPort` shape). Whether the seller's merchant account
   still resolves today (`BINDING_UNAVAILABLE`, `BINDING_CHANGED`) is not known
   from rows and is left to the endpoint's own refusal. The endpoint records
   `payment.checkout_reissued` (business class, scoped to the order's branch, the
   provider, the presentation kind and *that* a recipient was named — never the
   phone or the link) and accepts an optional `reason`.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A second brand-board query, free to evolve | Two statements drift; the filter set is the board | Never for the filters; a materialised brand read model if the statement stops scaling |
| Fan out one branch-board call per branch in the browser | N calls per tick, N cursors, no global order, and the cursor cannot be pinned to a filter set | Never |
| One board endpoint that widens with the caller's scope | A 403-as-routing answer becomes a silent scope change; the branch endpoint could quietly widen | Never — scope is part of the path |
| Store lateness as a column | Wrong five seconds after it is written (orders.md §2.7); needs a job | A measured cost the predicate cannot meet |
| Filter «Только опаздывающие» in the browser | A short page cannot be told from "none late"; breaks paging | Never |
| Offer `ISSUE_INVOICE` on the location-scoped capability set | The endpoint declares tenant scope; the button would 403 for a branch-level holder | The endpoint's scope is widened to location by a separate decision |
| Bulk actions across branches now | One request per branch and a merged result panel are their own design | The owner asks for it |

## Consequences

### Positive

- One query answers both boards; a filter added later reaches both.
- «Все филиалы» costs a supervisor one request per tick, not one per branch.
- The four toggles stop being screen-only promises: each is a predicate in the
  statement, tested for what it must and must not return.
- «Выставить счёт» is one implementation of an idempotent call, now audited.

### Negative

- `OrderListQuery` has twenty components; the compatibility constructor keeps
  the fourteen-argument callers compiling, and both are more to read than one.
- `late` adds an `unnest` join to the board statement only when asked for, and
  needs one policy resolution per branch in scope per request.
- `JdbcOrderStore` now names a fourth table it does not own
  (`fiscal.fiscal_documents`); a schema change there can break an ordering read.
- Most operators will never see «Выставить счёт»: `PAYMENT_INITIATE` is held only
  by tenant owners and tenant finance today.
- The brand board polls; a wallboard on «Все филиалы» is up to ten seconds stale
  for branches other than the one the realtime stream is scoped to.

### Accepted trade-offs

- Bulk selection is withheld on «Все филиалы» rather than half-built.
- Opening a row from another branch switches the console to that branch (the
  detail pane reads through the shell's current branch) — a side effect the
  operator sees in the shell's picker.

## Specification

- `GET /api/v1/operations/tenants/{t}/brands/{b}/orders/board` — every parameter
  of the branch board plus a repeatable `locationId`. `403 INSUFFICIENT_CAPABILITY`
  for a principal without `ORDER_READ` at `BRAND`.
- New parameters on both boards: `marketplaceBindingId`, `late`, `problem`,
  `callbackRequested`, `fiscalStatus` (repeatable; the six values of
  `ck_fiscal_document_status`). An unknown fiscal status is `VALIDATION_FAILED`.
- `GET .../orders/marketplace-bindings` (branch: `ORDER_READ` at `LOCATION`;
  brand: `ORDER_READ` at `BRAND`, optional `locationId`).
- `OrderSummaryResponse.locationId` on every board and detail read.
- `PaymentIntentPort.ordersWithPresentablePayment(tenantId, orderIds)` — the
  payments-side read behind `ISSUE_INVOICE`; the default answers none.
- `POST .../payment/re-presentations` gains an optional `reason` and writes
  `payment.checkout_reissued`.
- No migration: `ix_orders_marketplace`, the partial index on
  `callback_requested`, and `fiscal.fiscal_documents (tenant_id, order_id, …)`
  already serve the predicates.

## Rollout and rollback

Additive. The branch endpoints keep their shapes (one new response field, five
new optional parameters); the brand endpoints are new. Rolling back the console
leaves the API harmless. Rolling back the API first would leave the console's
«Все филиалы» asking for a route that no longer exists and showing the queue's
error band — the console only withdraws the mode on a 403, so roll the console
back first.

## Implementation checklist

- [x] Brand board over the branch statement, per-branch `actions[]`
- [x] Binding filter and option read model
- [x] `late`, `problem`, `callbackRequested`, `fiscalStatus`
- [x] `ISSUE_INVOICE` emitted where the live intent can be presented (not on the
      projection alone); re-presentation audited
- [x] Console: mode, column, filters, toggles, «Выставить счёт»
- [ ] Bulk actions across branches
- [ ] `BRAND`-scoped realtime channel for the brand board

## Exit criteria

A supervisor with a brand-level grant opens Orders, chooses «Все филиалы» and sees
every branch's orders newest first with a Филиал column; narrows to one branch,
one aggregator installation, or only late orders; and a tenant finance user can
issue an unpaid online order's payable link from its row and read the audit fact
back. A branch manager sees none of the mode and loses nothing they had.

## References

- [ADR 0102](../built/0102-the-order-board-query-reads-what-the-board-shows-wave-p04.md)
- orders.md §2.4, §2.5, §2.7, §4.9
- `OrderBoardBrandScopeQueryTests`, `OrderBoardTogglesQueryTests`,
  `OperationsBrandOrderBoardHttpTests` (including
  `issueInvoiceFollowsTheLiveIntentAndNotTheProjectionAlone`),
  `OperationsPaymentReissueAuditHttpTests`
