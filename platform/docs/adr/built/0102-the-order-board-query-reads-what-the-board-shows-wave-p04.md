# ADR 0102: The order board query reads what the board shows

- Decision status: Proposed
- Implementation status: Built — `GET /api/v1/tenants/{t}/brands/{b}/locations/{l}/orders/board` is the new paged read: a cursor-paginated `Page<OrderSummaryResponse>` filterable by period, status, channel code, fulfilment mode, courier, payment method code, creating actor and external reference. `OrderSummaryResponse` grew from eleven fields to twenty-three, which the released `GET .../orders` returns too — that operation is frozen and marked deprecated rather than changed, because its bare-array response is published in v1 and `OpenApiContractTests` refuses to change it; `GET .../orders/counts` takes the same period; `JdbcOrderStore.listForLocation(OrderListQuery, …)` is the one query behind both, keyset on `(created_at, id)` with the filter set fingerprinted into the cursor; the process-attention level is a correlated subquery over `ordering.order_process_states`, and the courier, payment-method and reference filters are `EXISTS` subqueries over `fulfillment.shipments`, `payments.payment_intents` and `ordering.order_external_references`. Covered by `OrderBoardQueryTests` and `CursorTests`. No migration; every index the filters need already exists. Not built by this decision: the derived `LATE`/`AT_RISK` levels of orders.md §2.7, the tenant-wide reference *search* of §2.8, the courier's name and the branch name on the row, and the signed cursor ADR 0031 asks for
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0019, ADR 0025, ADR 0029, ADR 0031, ADR 0036, ADR 0039, ADR 0040
- Supersedes / Superseded by: —
- Open inputs: whether the board's default period should be the business date rather than "everything" once a tenant has a year of orders (platform owner); whether a tenant-wide order search — the endpoint orders.md §2.8's aggregator lookup actually needs — is a `TENANT`-scoped capability of its own or a widening of `ORDER_READ` (platform owner, with the operations spec's author); the covering index the eventual filter mix wants (platform owner, once a pilot tenant's real filter usage is observable)

## Context

`OperationsOrderController.list` is the query the order board is built on. It
accepts two parameters — a repeated `status` and a `limit` capped at 500 — and
returns a bare JSON array of `OrderSummaryResponse`, which carries eleven
fields: the ids, the status, the fulfilment mode, the channel code, the money
total and its currency, the version, `createdAt`, the approval deadline, and
the server-computed `actions[]`.

Three facts about that sit badly together.

**The data is already in memory and is thrown away.** `JdbcOrderStore.OrderRow`
hydrates forty-six columns on every row of every list call. `promised_at` and
its three companions — ADR 0036's promise, written at checkout by
`CheckoutOrderWriter` on every order since V0023 — are read and dropped.
`payment_status_projection`, `discount_minor`, `fee_minor`,
`customer_account_id`, `guest_reference_hash`, `created_by_actor_type/id` and
`accepted_by_actor_type/id` are read and dropped. orders.md §11 files most of
these under "built, not read by ordering", which is accurate about the module
and slightly generous about the query: the query reads them and the response
does not publish them. `OrderSummaryResponse.of` is where the board's data goes
missing, not the schema.

**The filters the board specifies cannot be expressed.** orders.md §2.4 names a
period, a channel, a fulfilment mode, a courier, a payment method, a
"Мои заказы" toggle over `created_by_actor_id`, a "С проблемой" toggle over
`order_process_states.status`, and an aggregator reference. §2.5 puts Создал and
Принял behind the column picker and the promise under the Время column. §2.7
derives severity from the promise and the clock, and cannot derive anything
from a response that does not carry a promise. Today the console fetches every
order the cap allows and filters in the browser, which is correct only while a
location's whole history fits in one response.

**There is no paging, only a cap.** `limit=500` with no cursor is not a page: it
is the newest five hundred orders and silence about the rest. A location that
outgrows it loses its oldest orders from every screen at once, and nothing in
the response says so. ADR 0031 settled this for the platform — keyset cursors,
never offsets — and this endpoint predates the rule.

Four of the filters cannot be answered from `ordering.orders` alone:

| Filter | Where the answer lives |
|---|---|
| Courier | `fulfillment.shipments.courier_id` (ADR 0014, V0054) |
| Payment method | `payments.payment_intents.payment_method_code` (ADR 0013, V0027) |
| Aggregator reference | `ordering.order_external_references.reference_value_normalised` (ADR 0040, V0038) |
| С проблемой | `ordering.order_process_states.status` (ADR 0019, V0022) |

Two of those four are outside the `ordering` schema. The ordering module's
persistence has never read another module's tables — `JdbcOrderStore` names
`ordering.*` and nothing else — while `reporting` reads `ordering` and
`payments`, `kitchen` reads `ordering` and `catalog`, and `partner` reads
`ordering` and `integration`. So the practice exists in this codebase for read
models; it has simply never run in this direction.

There is also a scope trap in orders.md §2.8. The aggregator search it
specifies is explicitly tenant-wide — "matched across the tenant", several rows
may come back, disambiguated by provider and branch — because a courier on the
phone reads a code and nobody knows which branch the order is at. That search
cannot live on an endpoint whose capability is `ORDER_READ` at `LOCATION`
without turning a location grant into a tenant-wide order enumerator.

## Decision

**The order list query reads what the board renders, filters in the database,
and pages with a cursor.** Concretely, five parts:

1. **The response publishes every field `OrderRow` already hydrates that is not
   personal data**, plus one derived field. Twelve new fields:
   `promisedAt`, `promiseBasis`, `paymentStatusProjection`, `customerAccountId`,
   `guestReferenceHash`, `feeMinor`, `discountMinor`, `createdByActorType`,
   `createdByActorId`, `acceptedByActorType`, `acceptedByActorId`, and
   `processAttention`. Nothing decrypted, nothing derived from the customer
   snapshot: the customer's name, phone, address and notes stay behind the
   detail read and its ADR 0029 reveals, and the list says only whether the
   order belongs to an account or to a guest.

2. **The query gains eight filters**: `from`/`to` over `created_at`,
   `channelCode`, `fulfillmentMode`, `courierId`, `paymentMethodCode`,
   `createdByActorId`, and `reference`. `GET .../orders/counts` gains the same
   `from`/`to` and nothing else, so a tab badge counts the same population the
   tab lists.

3. **The paged read is a new operation, `GET .../orders/board`**, returning
   `web/api`'s `Page` with a `Cursor` token: keyset on
   `(created_at DESC, id DESC)` — the id breaks the tie because two orders
   placed in the same microsecond are ordinary at a busy branch — with a
   fingerprint of the filter set carried inside the cursor, so changing a filter
   mid-iteration fails loudly instead of producing an incoherent page. The
   existing `GET .../orders` keeps its bare array, its two parameters and its
   five-hundred cap, and is marked deprecated. That is not a preference: it is
   published in v1, and `OpenApiContractTests.assertSchemasCompatible` fails the
   build for a response whose type changes from `array` to `object`. The two
   operations share one query, one row type and one response record, so the
   frozen one still gains every field below.

4. **The order-list read model may read outside `ordering.*`.** Three `EXISTS`
   subqueries — `fulfillment.shipments`, `payments.payment_intents`,
   `ordering.order_external_references` — and one correlated scalar subquery
   over `ordering.order_process_states`. This is a read model and a read
   permission: no ordering write path gains a cross-schema statement, and no
   module gains a Java import it did not have.

5. **The reference parameter is a filter, not a lookup.** It narrows *this
   location's* orders by an external reference. `ORDER_READ` at `LOCATION` is
   unchanged and no row from another location or another tenant can be reached
   through it. orders.md §2.8's tenant-wide aggregator search stays unbuilt and
   needs its own endpoint at its own scope; §11 now says so.

No migration. `ix_order_processes_stuck`, `ix_external_reference_search`,
`ux_payment_intent_live_per_order` and the shipments' order index already serve
these predicates, and `ordering.orders` is already indexed on the location and
`created_at`.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A maintained read-model table (`ordering.order_board_rows`) fed by events, one row per order with every board column denormalised | A second copy of the order that can disagree with the first, a projector to keep running, and a backfill for every column added later. The board's population is one location's orders and the predicates are all indexed; there is no measured query problem to solve, and ADR 0033 forbids a correctness decision reading a cache | The board query's p95 passes 200 ms at a real tenant's volume, or the row needs a field no single query can produce |
| Call the `fulfillment` and `payments` module APIs per row instead of reading their tables | N+1 port calls per page, and the courier filter becomes "fetch every order, then ask fulfillment about each" — the exact shape the browser-side filtering this decision removes already had | `fulfillment` or `payments` is extracted into a separate service, at which point the join stops being a join |
| Keep filtering client-side and only widen the response | Works until a location's order history exceeds one response, then fails silently and permanently — which is today's behaviour and the reason the cap is a defect rather than a setting | Never |
| `page`/`size` offset paging | ADR 0031. An operations list changes while it is being paged; offsets skip and duplicate rows, and in an order feed a skipped row is a missed order | Never |
| A signed cursor, as ADR 0031 actually requires | The platform has no `CursorSigner` bean, and giving it one means a new ADR 0028 secret, a new deployment variable and a startup failure mode — a platform decision, not a consequence of widening one query. `AuditController`, `FailureOperationsController`, the migration console and the tenant directory all take the same unsigned shortcut today. The cursor here names no scope of its own: the tenant, brand and location come from the path and the capability check, and the keyset position is resolved from the database inside that scope rather than read out of the token, so a forged cursor can only give its own holder an incoherent page of data they may already read | A `CursorSigner` bean exists — at which point `encodeUnsigned`/`decodeUnsigned` are deleted and the four callers move over together |
| Put the tenant-wide aggregator search on this endpoint, as orders.md §2.8 specifies | Turns an `ORDER_READ`@`LOCATION` grant into a tenant-wide order enumerator: a branch manager could walk every other branch's orders one reference at a time | A tenant-scoped order search endpoint is designed with its own capability |
| Change `GET .../orders` in place to return `Page` | Refused by the build. `OpenApiContractTests` walks the released v1 baseline and fails any response whose type changes — `array` to `object` is exactly that — and the same check runs inside `make openapi-baseline`, so a regenerated baseline cannot hide it. It would also break three console call sites silently, since their specs mock arrays | v2 exists, or the contract gate gains a reviewed break list |
| Keep the bare array and carry `nextCursor` in a response header | Halves the contract: a generated client gets the items typed and the cursor untyped, and every caller has to know to read a header. `Page` exists for this | Never |
| Put the paged read under the ADR 0031 `/api/v1/operations/**` prefix instead of beside the operation it supersedes | A new prefix means a `SecurityConfiguration` entry, a surface-group question and a second place the order board lives, for a path that is the same resource. `/orders/board` sits in the same group, under the same capability, next to `/orders/counts` and `/orders/drafts` | The whole controller moves to the ADR 0031 prefix, which is its own piece of work |

## Consequences

### Positive

- The promise reaches the board, which is the single input orders.md §2.7's
  severity model has been missing. Nothing else was blocking it.
- Seven of §2.4's filters become server-side, so the board stops depending on
  "everything fits in one response".
- The board is pageable, and the endpoint stops silently truncating.
- Attribution (Создал / Принял) and the money breakdown (Скидка / Доставка)
  reach the column picker without a second request per row.
- `orders/counts` and the list can no longer disagree about which period they
  describe.

### Negative

- **One list, two operations.** `GET .../orders` and `GET .../orders/board`
  return the same rows in two envelopes, and will until every caller has moved
  and v2 can drop the first. Two operations over one resource is a thing to
  read twice, and the deprecation note is the only thing saying which to use.
- **The console gains nothing until it moves.** The three call sites —
  `features/orders/order-queue.ts`, `features/today/live-board.ts`,
  `features/delivery/dispatch-board-page.ts` — keep working unchanged and keep
  fetching the capped array. They gain the twelve new fields for free; they gain
  the filters and the paging only by switching path, which is a console wave's
  work, not this one's.
- **`/board` clamps `limit` to `Page.MAXIMUM_LIMIT` (200)**, where the frozen
  operation allows 500. A caller asking for more gets 200 and a `nextCursor`
  rather than 500 and silence — better behaviour, and still a difference between
  the two operations that somebody will trip over once.
- **`JdbcOrderStore` now names three tables it does not own.** A schema change
  in `fulfillment.shipments` or `payments.payment_intents` can now break an
  ordering query, and nothing but this ADR records that coupling.
- **The external-reference normalisation is restated a third time.**
  `partner.domain.ExternalReference#normalise` owns it,
  `tenancy…JdbcGlobalLookup#partnerForm` restates it, and this adds a third
  copy, for the same reason the second one exists: the owning type is internal
  to its module. `OrderBoardQueryTests` holds this copy to the same answers as
  `JdbcGlobalLookup`'s, which is the guard, not a fix.
- **The filter combinations have no covering index.** Each predicate is
  individually indexed and the population is one location's orders; a mix that
  needs a composite index will show up as a slow board before it shows up
  anywhere else.
- **`processAttention` costs a correlated subquery per returned row.** Bounded
  by the page size and served by `ix_order_processes_stuck`, and still a
  per-row cost the previous query did not have.

### Accepted trade-offs

- The cursor is unsigned. What that gives up is stated in the alternatives
  table: it cannot detect a hand-edited cursor, only an incoherent one. What it
  cannot give up — reaching another tenant's or another location's rows — is
  held by the path and the capability check, and `OrderBoardQueryTests` asserts
  it with a cursor minted in one tenant and replayed in another.
- `guestReferenceHash` is published. It is a keyed hash and not a reference, it
  is the only way the row can say "Гость" without reading the encrypted
  snapshot, and `DraftCartResponse` already publishes it on the drafts list.
- The period filter is on `created_at` and not on a business date. ADR 0043
  owns business days; a board filtered by a business date and a report filtered
  by one must agree, and making them agree is that decision's work, not this
  one's.

## Specification

### Request

`GET /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/board`
— `@RequiresCapability(ORDER_READ, scope = LOCATION)`, the same capability and
the same scope as the frozen `GET .../orders` beside it.

| Parameter | Type | Meaning |
|---|---|---|
| `status` | repeated string | As today. An unknown value is a 422, never a silent empty page |
| `from` | instant | `created_at >= from` |
| `to` | instant | `created_at < to`, half-open so two adjacent periods neither overlap nor drop an order |
| `channelCode` | string | `channel_code_snapshot`, the snapshot and not the live channel |
| `fulfillmentMode` | `DELIVERY`/`PICKUP`/`DINE_IN` | |
| `courierId` | uuid | An order with a shipment assigned to this courier |
| `paymentMethodCode` | string | An order with a payment intent of this method |
| `createdByActorId` | string | orders.md §2.4's Мои заказы, with the caller's own subject supplied by the client |
| `reference` | string | Normalised, then matched against `reference_value_normalised` for this order |
| `cursor` | string | The previous page's `nextCursor` |
| `limit` | integer | `Page.limitOrDefault`: 50 by default, 200 at most |

`GET .../orders/counts` gains `from` and `to` with the same meaning.

### Response

`Page<OrderSummaryResponse>` from `/board`, a bare array of the same record from
the frozen `/orders`. `OrderSummaryResponse` keeps its eleven fields and adds
twelve, on both:

| Field | Source |
|---|---|
| `promisedAt`, `promiseBasis` | `ordering.orders.promised_at`, `promise_basis` (ADR 0036) |
| `paymentStatusProjection` | `ordering.orders.payment_status_projection` |
| `customerAccountId`, `guestReferenceHash` | the account/guest discriminator, never a name or a number |
| `feeMinor`, `discountMinor` | the money breakdown beside the existing `totalMinor` |
| `createdByActorType`, `createdByActorId` | V0029 |
| `acceptedByActorType`, `acceptedByActorId` | V0029 |
| `processAttention` | `MANUAL_ACTION_REQUIRED`, `FAILED_RETRYABLE`, or absent |

`processAttention` states the worse of the two when both are present, so the
board's `BLOCKED` rail is decided by the value and not by a client's guess at
precedence.

**No field of this response is personal data.** The rule is enforceable by
reading the record: an accessor whose name suggests a name, a phone, an email,
an address or a note does not belong on a list that renders on a screen in a
branch. `OrderBoardQueryTests` asserts it over the record's components rather
than over one instance, so a field added later fails the test.

### Query

One statement, `ordering.orders` unaliased so the existing column list is
reused verbatim:

```sql
SELECT <the OrderRow columns>,
       (SELECT CASE
                 WHEN bool_or(s.status = 'MANUAL_ACTION_REQUIRED') THEN 'MANUAL_ACTION_REQUIRED'
                 WHEN bool_or(s.status = 'FAILED_RETRYABLE')       THEN 'FAILED_RETRYABLE'
               END
          FROM ordering.order_process_states s
         WHERE s.tenant_id = orders.tenant_id AND s.order_id = orders.id
           AND s.status IN ('MANUAL_ACTION_REQUIRED', 'FAILED_RETRYABLE')) AS process_attention
  FROM ordering.orders
 WHERE tenant_id = :tenantId AND brand_id = :brandId AND location_id = :locationId
   AND (:statusFilterEmpty OR status = ANY(:statuses))
   AND (CAST(:from AS timestamptz) IS NULL OR created_at >= CAST(:from AS timestamptz))
   …
   AND (:unbounded OR (created_at, id) < (CAST(:beforeCreatedAt AS timestamptz), CAST(:beforeId AS uuid)))
 ORDER BY created_at DESC, id DESC
 LIMIT :limit
```

Every cross-schema predicate is an `EXISTS` carrying `tenant_id` as well as
`order_id`, so a row that somehow held a foreign order id still cannot be
reached across a tenant boundary. The `CASE`/`bool_or` form is deliberate: a
`max(status)` would give the same answer today by alphabetical accident and a
different one the day a third status is added.

### Cursor

`Cursor.sortKey` is the last order id of the previous page and nothing else;
`Cursor.filterHash` is a truncated, base64url-encoded SHA-256 of the canonical
rendering of the whole filter set. The token is
`base64url(sortKey|filterHash)`, minted by `Cursor#encodeUnsigned` and read by
`Cursor#decodeUnsigned`, which returns empty for a malformed token or one whose
filter hash does not match the request.

The `created_at` half of the keyset position is **not** carried in the token. It
is read back from the named order by `JdbcOrderStore#locationOrderCursor`, under
the same tenant, brand and location predicates the page itself applies —
exactly how `listForCustomer`'s cursor already works. That costs one small
indexed read per page and buys two things a self-describing token cannot: the
client cannot choose the keyset position, and a cursor naming another location's
or another tenant's order resolves to nothing rather than to a window. An
order's `created_at` never changes, so the resolution is stable for the life of
the order.

An unusable cursor is a 422 naming the cursor, never a silently reset first page
— a reset page looks to an operator like the list jumping back to the top for no
reason.

`nextCursor` is null when the page came back short, and is minted from the last
row otherwise. A full page that happens to be the last one costs the caller one
empty request; guessing the other way loses them every order after it.

## Rollout and rollback

No migration, no data change and no breaking contract change, so rollback is
reverting the code and regenerating the baselines. Nothing in the platform or
the console has to move on any schedule: `GET .../orders` answers exactly as it
did, with more fields on each row. `JdbcOrderDirectory` and the Telegram order
digest call `counts(…)`, whose existing three-argument form is unchanged and
still means "no period".

The one thing to watch on the way out is the deprecation: a frozen operation
with no removal date is an operation that never goes. It goes when v2 does, and
until then the description on it names its replacement.

## Implementation checklist

- [x] `Cursor#encodeUnsigned` / `#decodeUnsigned`, with the shortcut documented on both
- [x] `JdbcOrderStore.OrderListQuery`, `OrderBoardRow`, `listForLocation(…)`, `counts(…, from, to)`
- [x] `JdbcOrderStore#normalisedExternalReference`, held to `JdbcGlobalLookup#partnerForm`'s answers
- [x] `OrderQueryService#forLocation` over the new query; `#counts` with a period
- [x] `OperationsOrderController#board` returning `Page`, `#list` frozen and deprecated, `#counts` taking a period, `OrderSummaryResponse` widened
- [x] All five OpenAPI baselines and both generated clients regenerated
- [x] `OrderBoardQueryTests`: one test per filter, cursor stability under a concurrent insert, a cross-tenant reference and a cross-tenant cursor refused, and the no-PII assertion over the response record
- [x] orders.md §11 records what this closes and what it does not
- [ ] The three console call sites move to `/orders/board` (a console wave, not this one)
- [ ] A tenant-wide order search endpoint for orders.md §2.8 (unowned)

## Exit criteria

- A board request with a period, a channel, a mode, a courier, a payment method
  and a creator returns only the orders matching all six, and the SQL that
  produced it names each predicate — not a stream filter after the fact.
- Paging a location's orders while new orders are being inserted returns every
  order that existed when the first page was taken, exactly once — including
  when several of them share one microsecond.
- One aggregator code issued at two tenants returns each tenant's own order and
  only that, and a cursor naming another tenant's or another location's order
  resolves to nothing.
- `OrderSummaryResponse` carries no component whose name suggests personal
  data, asserted over the record rather than over an instance.

## References

- [ADR 0019](../partial/0019-cart-checkout-and-order-orchestration.md) — the ordering module and its read path
- [ADR 0031](0031-http-api-conventions.md) — cursor pagination, Problem Details, the `Page` envelope
- [ADR 0036](../partial/0036-sales-channels-and-location-serviceability.md) — `promised_at` and its basis
- [ADR 0039](../partial/0039-operator-assisted-ordering-and-order-amendment.md) — the attribution and callback columns
- [ADR 0040](../partial/0040-marketplace-channel-and-partner-api.md) — `order_external_references` and its normalisation
- `docs/operations-spec/orders.md` §2.4, §2.5, §2.7, §2.8, §11
