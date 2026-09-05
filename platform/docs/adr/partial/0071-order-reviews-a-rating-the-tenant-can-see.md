# ADR 0071: Order reviews — a rating attached to the order, read by the tenant, deferred everywhere else

- Decision status: Accepted
- Implementation status: Partial — V0168 creates `reviews.order_reviews` with
  every constraint this record specifies (the tenant/brand/location foreign
  keys matched to their own unique keys, `UNIQUE (tenant_id, order_id)`, the
  1–5 rating CHECK, no `UPDATE`/`DELETE` grant); `Capability.REVIEW_READ`
  exists and is held by `tenant-owner`, `tenant-admin`, and `brand-manager`
  (placed exactly where `REFERRAL_READ` sits, never `location-manager`, for
  the scope-mismatch reason this record's own Decision section gives).
  `reviews.application.ReviewSubmissionService` (submit, ownership and
  `COMPLETED`-only eligibility via `ordering.api.OrderDirectory`, envelope
  encryption via `FieldProtection`/`DataClass.PERSONAL`, the
  pre-check-then-catch race discipline `ReferralRedemptionService` already
  uses) and `ReviewQueryService` (brand-scoped filtered list plus summary,
  read-only) are built and covered by ten PostgreSQL-backed tests proving
  ownership, the `COMPLETED` gate, the one-per-order constraint under a
  direct row-count assertion, tenant isolation, and that the raw
  `comment_protected` column never contains plaintext.
  `StorefrontReviewController` (`POST .../orders/{orderId}/review`,
  `GET .../reviews` for "my reviews", both `@CustomerOwned`, no capability)
  and `OperationsReviewController` (`GET .../reviews` and `.../reviews/summary`,
  both `review.read` at `BRAND` scope) are built, and all five OpenAPI
  baselines are regenerated with the four new paths correctly assigned to the
  `storefront` and `operations` surface groups. The operations §5.4 Reviews
  screen (`frontend/operations`) replaces the not-built page, scoped through
  `CurrentBrand` to match this capability's real scope (not `CurrentLocation`,
  the mismatch this record's Decision section names as the exact failure mode
  to avoid) — location, minimum-rating, and date-range filters, a count/average
  summary header, and links from each row to its order and its customer. §5.5
  Feedback settings stays the honest not-built stub it already was, now
  pointing at this ADR's own moderation decision instead of a missing entity.
  **Not built**, all by this ADR's own decision rather than by omission: the
  storefront submission/read endpoints have no consuming UI — a second
  storefront application is being scaffolded in a concurrent, separate effort
  and will consume this contract, per this wave's own instructions, so
  `CartOrderStatusComponent`'s "no rating backend" comment is now half true —
  the backend exists and the UI does not yet call it; editing or withdrawing a
  submitted review; any moderation, hide, or flag action; an ADR 0043 fact or
  metric entry; and any wiring into §5.2 Customer Detail's own "reviews left"
  row beyond a corrected doc comment naming where the same data now lives.
- Date proposed: 2026-09-05
- Date decided: 2026-09-05
- Deciders: platform owner (directed that reviews be built; owns every open
  input below), Claude (architecture)
- Depends on: ADR 0015 (customer accounts and ownership), ADR 0019 (orders),
  ADR 0025 (capability model), ADR 0029 (PII classification and envelope
  encryption), ADR 0031 (HTTP conventions), ADR 0043 (the fact/metric layer —
  explicitly not extended here), ADR 0048 (order remedies — already the
  compensation mechanism this ADR declines to duplicate)
- Supersedes / Superseded by: —
- Open inputs:
  - Whether a customer may ever edit or withdraw a submitted review (owner:
    platform owner). Default while open: no. A mis-tapped star rating stands;
    neither the customer nor support has a way to change it.
  - Whether a review's rating ever becomes an ADR 0043 fact
    (`average_rating.v1` beside `average_check.v1`) (owner: platform owner).
    Default while open: no. The one screen that needs an average computes it
    itself, uncached and unregistered.
  - Whether a tenant is ever given a way to hide or flag a review (owner:
    platform owner). Default while open: no. Nothing today shows a review to
    anyone but its author and the tenant's own staff, so there is no
    reputational surface yet to moderate.
  - Whether a guest checkout (no customer account) may ever review an order
    (owner: platform owner; named but left open by
    `intent/0004-rating-what-happened`). Default while open: no — there is no
    durable guest identity to own a submission with.

## Context

Three surfaces now want a review, and none of them can have one, because no
review or feedback entity, migration, or owning ADR exists anywhere in this
platform:

- The customer storefront design being built asks a customer to rate their
  order after delivery ("Buyurtmani baholang" / "rate the delivery"). The
  storefront that shipped before it went further and named the gap out loud:
  `CartOrderStatusComponent`'s own doc comment records that the screen used to
  render "a five-star rating prompt... none of which the platform has ever
  sent: there is no rating backend".
- Operations §5.2 Customer detail lists "reviews left" as part of a customer's
  whole record, and the wave that built the rest of that screen recorded the
  same gap: `CustomerDetailPane`'s own doc names it "no review/feedback entity
  exists in this codebase yet".
- Operations §5.4 Reviews and §5.5 Feedback settings both route to the shared
  not-built page today, each carrying a comment naming this exact ADR as
  missing (`app.routes.ts`).

`docs/frontend-information-architecture.md`'s own §5.4 row describes something
considerably larger than "a rating": a service-recovery kanban ("New → In
progress → Resolved / Closed unresolved"), four scored dimensions ("meal,
operator, courier, delivery time"), and compensation actions ("promo code,
bonus, discount"). That is Delever feature parity, and it is not what the
owner asked for or what the storefront design shows — the storefront asks one
question about one order, not four. Building the kanban and its compensation
actions on top of an entity that does not exist yet, before a single review
row has ever been written, would be exactly the "kanban with nothing behind
it" `app.routes.ts`'s own comment already declined to build. It also
duplicates work this platform already did: `payments.order_remedies`
(ADR 0048) already lets staff record a console refund, a delivery-fee
reimbursement, or a future-discount grant against an order, audited and
capped — which is what "compensation actions" against a bad review actually
are. A second, review-specific compensation mechanism would answer the same
question twice.

What forces a decision now, rather than deferring further, is that the same
question — "what is a review of" — has three different pressures pointing at
three different answers: the storefront rates an *order*, §5.2 wants reviews
on a *customer*, and a future menu screen would want them per *product*.
Picking wrong here is expensive to unpick later: a review keyed to the wrong
entity cannot be re-keyed without either losing information (which order was
this about?) or fabricating it.

## Decision

**A review belongs to exactly one order, is written by the order's own
customer exactly once, is immutable once submitted, and is never shown to
anyone but its author and the tenant's own staff.**

- **What is reviewed.** The order. `reviews.order_reviews` carries
  `order_id` with a `UNIQUE (tenant_id, order_id)` constraint — one row per
  order, ever. The row also denormalizes `brand_id`, `location_id`, and
  `customer_account_id` from the order at submission time (the same
  snapshot discipline `reporting.fact_order` already uses for exactly this
  reason: a query never has to join back into `ordering` to know which brand,
  branch, or customer a review is about). This one shape serves all three
  pressures without becoming three tables: a location's reviews are the rows
  where `location_id` matches, a customer's "reviews left" (§5.2) are the
  rows where `customer_account_id` matches, and both are the same table
  filtered differently. A product-level or courier-level review is
  deliberately not built — see Alternatives.
- **Who may submit, and how many times.** Only the account that placed the
  order, and only once the order's status is `COMPLETED` — not merely
  terminal (`CANCELLED`, `REJECTED`, `EXPIRED`, and `PAYMENT_FAILED` are also
  terminal but never produced a meal or a delivery to have an opinion about).
  The storefront endpoint resolves the caller's own account exactly the way
  `StorefrontOrderingController` and `ReferralStorefrontController` already
  do — `CustomerOwned` over `CurrentCustomer`, never a capability, because a
  customer rating their own order is not delegated staff authority (ADR
  0025's own distinction). It reads the order through
  `ordering.api.OrderDirectory#summary`, refuses when the account named there
  is not the caller's, refuses when the status is not `COMPLETED`, and the
  database's own `UNIQUE (tenant_id, order_id)` refuses a second attempt even
  under a race — the service checks first for a clean error message, but the
  constraint is what actually prevents the spam surface a submission
  endpoint with no limit would otherwise be.
  [`intent/0004-rating-what-happened`](../../../intent/0004-rating-what-happened/intent.md)
  named two edge cases in this rule explicitly, and both are answered here
  rather than left as accidents of the implementation: **a refunded order is
  still reviewable**, because ADR 0048 refunds and remedies never move
  `ordering.orders.status` back off `COMPLETED` — an order that went wrong and
  was put right is exactly the intent's own "one a tenant most wants to hear
  about", and this rule does not exclude it. **A guest checkout cannot review
  at all** — `@CustomerOwned` requires a resolvable account, and a guest order
  carries `guest_reference_hash` with no `customer_account_id` to own it with.
  Extending reviews to guests would need a durable guest identity this
  platform does not have, and is left exactly where the intent left it, an
  open question rather than a silent gap.
- **Free text is classified `PERSONAL` and envelope-encrypted.** A review
  comment is a customer's own words about a real visit, and words about a
  visit name people — a server, a courier, another guest. `comment_protected`
  is protected exactly the way `conversations.conversation_messages
  .body_protected` already is: `FieldProtection.protect` with
  `DataClass.PERSONAL`, keyed and bound to the row by
  `FieldProtection.RecordRef`, revealed only by the read paths that render it
  (the operations Reviews screen; a customer never needs to re-read their own
  comment through the API this ADR adds, though nothing prevents it — no
  reveal-audit fact is written per `FieldProtection.reveal`'s own contract,
  which requires one only for `PERSONAL_SENSITIVE` and `FINANCIAL`). The
  rating itself (an integer 1–5) is not personal data and stays a plain
  column, queryable and filterable without decryption — the same split
  `fact_order`'s own money columns draw against its encrypted fields
  elsewhere in this platform.
- **Retention is indefinite, tied to the order it describes, not to a
  conversational retention window.** `conversations.conversations` carries
  its own `retention_months` because a chat is ongoing and ADR 0059 chose to
  let a tenant shorten it. A review is a one-time, immutable statement about
  a specific, already-closed commercial transaction — closer to an order
  line snapshot than to a chat log — so it is kept exactly as long as the
  order itself is, with no separate sweep. If a platform-wide retention
  policy is ever adopted it would apply here the same way it would apply to
  `ordering.orders`; this ADR does not invent a review-specific one.
- **No moderation, no kanban, no compensation workflow.** A submitted review
  is immediately visible to any operator holding `REVIEW_READ`; there is no
  status, no assignee, and no hide action. This is a decision, not an
  oversight: nothing built by this ADR or any other ever shows a review to
  another customer, a public page, or an aggregator feed, so there is no
  reputational exposure to protect against yet, and building a moderation
  control for a threat that does not exist is exactly the kind of
  "publishing pipeline nobody asked for" this ADR was warned against. When a
  tenant wants to act on a bad review — refund, reimburse the delivery fee,
  grant a future discount — the order the review is attached to already has
  `OperationsRemedyController`'s tools (ADR 0048) reachable from Order
  Detail; this ADR adds no second console for the same action.
- **No fact-layer entry.** ADR 0043 owns the metric registry precisely so
  that two surfaces never compute the same number two ways. The one screen
  this ADR builds (operations §5.4) computes its own average and count
  directly against `reviews.order_reviews`, uncached and unregistered,
  because it is the only caller. A `fact_review` or an `average_rating.v1`
  metric is future work for whichever ADR 0043 wave gives it a second
  caller, not a pre-registered decoration here.
- **Module: a new leaf module, `reviews`.** Not folded into `ordering`
  (which AGENTS.md scopes to "carts, orders, immutable order snapshots,
  transitions, and notes" — a review is customer-authored feedback with its
  own PII posture and its own read surface, not an order transition or an
  internal note), not folded into `customers` (which owns the account, not
  what the account said about a specific visit — the same reasoning that
  already keeps `conversations` a sibling of `customers` rather than a
  package inside it). `reviews` depends one-way on `ordering.api`
  (`OrderDirectory`) and `customers.api` (`CurrentCustomer`,
  `CustomerAccountRef`, `CustomerOwned`) for the small reads it needs and
  exposes no API of its own — nothing outside this module reads a review yet,
  matching how `referral` and `conversations` shipped with no `api` package
  either, until something needed one.
- **Capability: `review.read`, brand scope, held by `tenant-owner`,
  `tenant-admin`, and `brand-manager`.** No write capability exists because
  nothing on the operations side ever writes a review — submission is
  customer-owned, not staff-delegated. Placed exactly where `referral.read`
  already sits (`Capability.REFERRAL_READ`'s own three-role bundle): a
  review is the same class of read as a referral redemption — a brand's own
  reputation signal, not one customer's private record (`customer.read`) and
  not an order's commercial state (`order.read`) — and a comment can name a
  person the same way a booking's guest name does, which is why
  `RESERVATION_READ` is its own capability rather than folded into a
  broader read, and why this one is too. Not granted to `location-manager`:
  its bundle's own scope is `LOCATION`, and this ADR's screen (like
  `referral`'s and `CustomerOrderHistoryController`'s before it) is a
  brand-wide list a `LOCATION`-scoped grant cannot satisfy without the tenant
  granting that role at `BRAND` scope specifically — the same documented
  caveat `CONVERSATION_INBOX_MANAGE` already carries, not repeated here to
  avoid a fourth capability whose only purpose is restating it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| The four-dimension service-recovery kanban with compensation actions (statistics.md's and the IA's own original §5.4 spec, mirroring Delever) | Ships a triage workflow and a second compensation mechanism before a single review row exists, duplicating `payments.order_remedies` (ADR 0048) for the compensation half and inventing unrequested case-management state for the other | A tenant with real review volume asks for a triage state ADR 0048 plus a status filter genuinely cannot answer |
| Reviews attached to the customer account, not the order | §5.2's "reviews left" is really "reviews of orders this customer placed" — keying to the account loses the order (and with it the location and what was bought), and every consumer that cares which visit a review was about would have to reconstruct it | A review is ever asked for that is *not* about a specific order (a general brand review) |
| Reviews attached to the location, one row per (customer, location) | Collapses every repeat visit into one mutable row: destroys "which order was this about", makes "once per order" inexpressible as a constraint, and cannot answer §5.2's per-order history at all | Never — this shape loses information the order-scoped one keeps for free |
| Per-product line-item reviews | No product or menu page exists to display or request one yet; multiplies the write surface (one review per line) for a feature with zero current callers — the same "becoming four features" this ADR was told to avoid | A storefront or public product page is built that would show or request a per-product rating |
| Per-courier reviews | Courier quality already has a dedicated, operationally-driven program (ADR 0042's engagement, ratecards, and adjustments) fed by on-time and complaint data, not customer stars; a second customer-facing judgment channel before the first is even wired up invites two disagreeing answers to "is this courier good" | ADR 0042's program is judged and found insufficient for courier quality |
| A moderation/publishing pipeline (hide, approve, feature, respond) in v1 | Nothing built by this ADR — or by anything else — ever shows a review to another customer or in public; a hide control defends a reputational surface that does not exist yet | A review is displayed anywhere outside operations: a public product page, a widget, an aggregator feed |
| Registering `average_rating.v1` in the ADR 0043 metric registry now | The registry exists so two surfaces never compute one number two ways; one screen computing its own average is not that yet, and pre-registering a metric with a single caller is the "decoration" ADR 0043's typed-query API was built to refuse | A second surface (a dashboard tile, an export) needs the same average and the two must agree |
| Storing the comment in the clear (no envelope encryption) | A review is free text about a real visit and will eventually name a server, a courier, or another guest — ADR 0029's `PERSONAL` classification exists for exactly this shape of text, and `conversations.conversation_messages` already established the identical pattern for the identical risk | Never — this is a direct application of an existing rule, not a close call |
| Allowing more than one review per order (update-in-place, or accumulate) | Turns "once per order" from a database constraint into "at most one *current* review", which needs edit history and last-write-wins semantics for no expressed need, and reopens the spam surface a hard limit exists to close | The "may a customer edit their review" open input above is resolved in favor of editing |

## Consequences

### Positive

- The storefront's "rate your order" prompt, §5.2's "reviews left", and §5.4's
  Reviews screen all read one entity — there is no reconciliation between
  three views of the same fact because there is only one fact.
- Every mechanism this ADR needs already exists and is proven: ADR 0015
  ownership for submission, ADR 0029 envelope encryption exactly as
  `ConversationMessageStore` already performs it, ADR 0048's remedy console
  for anything staff want to do about a bad rating. Zero new cross-cutting
  machinery, which is also why this ships as one migration and one small
  module.
- A tenant gets a real, filterable signal — rating, comment, location, date,
  linked order and customer — on day one, replacing a not-built page rather
  than gesturing at a bigger one that would have shipped later or not at all.
- The database, not the service layer, is what makes "once per order"
  actually hold: `UNIQUE (tenant_id, order_id)` refuses a race the way a
  service-layer check alone cannot.

### Negative

- No moderation means an abusive or defamatory comment is visible to every
  operator holding `review.read` from the moment it is submitted, with no
  hide button anywhere in this build. A tenant that gets one has to reach
  for existing customer-management tools (blacklist, a support call) rather
  than a purpose-built control.
- No editing or withdrawal: a customer who taps 1 star meaning to tap 5 has
  no way to change it, and neither does support on their behalf. The review
  stands exactly as submitted, forever, in this version.
- No fact-layer entry means a future "average rating this month" dashboard
  tile is a new, unregistered query today, and reconciling it into ADR
  0043's registry later is exactly the "two surfaces computing a metric
  separately" risk that ADR itself warns produces two disagreeing answers.
- One review per order forever forecloses the four-dimension richness
  (meal / operator / courier / delivery time) the original specification
  imagined. A tenant that wants a split rating does not get one from this
  ADR, and adding it later is a schema change — a new table alongside this
  one, most likely — not a configuration flag.
- The service-recovery workflow (columns, an assignee, a resolution state)
  that §5.4 originally promised is not built. A bad review sits in a
  filtered list; nothing tracks whether anyone acted on it beyond whatever
  ADR 0048 remedy record an operator separately created against the same
  order.

### Accepted trade-offs

- A real, narrow v1 is chosen over Delever-parity breadth. A tenant that
  compares feature lists literally will see the gap, and that gap is
  deliberate rather than an oversight this record hides.
- A silent, unmoderated read surface is accepted over a control nobody has
  asked to operate yet. It is revisited the day a tenant asks for one, or the
  day a review is shown anywhere outside operations — whichever comes first.

## Specification

### Physical model

```text
reviews.order_reviews
  id uuid primary key
  tenant_id uuid not null
  brand_id uuid not null              -- denormalized from the order at submission
  location_id uuid not null           -- denormalized from the order at submission
  order_id uuid not null              -- the order this review is about
  customer_account_id uuid not null   -- the order's own customer at submission
  rating smallint not null            -- 1..5, CHECK-enforced
  comment_protected text              -- nullable; ADR 0029 PERSONAL, FieldProtection-serialized
  submitted_at timestamptz not null default now()

  UNIQUE (tenant_id, id)
  UNIQUE (tenant_id, order_id)                                  -- once per order, ever
  FOREIGN KEY (tenant_id, brand_id)         REFERENCES tenant.brands (tenant_id, id)
  FOREIGN KEY (tenant_id, brand_id, location_id)
                                             REFERENCES tenant.locations (tenant_id, brand_id, id)
  FOREIGN KEY (order_id, tenant_id)         REFERENCES ordering.orders (id, tenant_id)
  FOREIGN KEY (customer_account_id, tenant_id)
                                             REFERENCES customer.customer_accounts (id, tenant_id)
  CHECK (rating BETWEEN 1 AND 5)
```

No `version` column and no update statement anywhere in this module: the row
is written once by `INSERT` and never touched again, which is what "immutable
once submitted" means at the schema level, not only in prose.

### APIs

Storefront (`storefront` OpenAPI group, `@CustomerOwned`, no capability):

```text
POST /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/orders/{orderId}/review
GET  /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/reviews
```

The first accepts `{ rating, comment? }`, refuses with `RESOURCE_NOT_FOUND`
when the order is not the caller's own, `UNPROCESSABLE_STATE` when it is not
`COMPLETED`, and `RESOURCE_CONFLICT` when it already has a review. It carries
`@Idempotent` per ADR 0031 the same way every other storefront mutation does.
The second is the caller's own submissions, newest first, cursor-paginated —
"my reviews", the storefront-side mirror of `StorefrontOrderingController`'s
"my orders".

Operations (`operations` OpenAPI group, `review.read` at `BRAND` scope):

```text
GET /api/v1/operations/tenants/{tenantId}/brands/{brandId}/reviews
```

Filterable by `locationId`, `minRating`/`maxRating`, and a submitted-at range;
cursor-paginated per ADR 0031; each row carries the order's public order
number and the identifiers needed to open that order or that customer from
the Reviews screen, never a decrypted customer name or phone (an operator
who needs that already has Customer Detail's own reveal path).

### Testing

Against real PostgreSQL, per this platform's own standing rule that a
tenant-isolation or uniqueness claim proven against a mock proves nothing:

- A customer with a `COMPLETED` order of their own submits a review
  successfully; the same customer's second attempt against the same order is
  refused with `RESOURCE_CONFLICT`, and the row count stays one.
- A customer cannot submit a review for another customer's order
  (`RESOURCE_NOT_FOUND`, the same answer ownership checks give elsewhere in
  this platform for "not yours" and "does not exist" alike), and cannot
  submit one for their own order that is not yet `COMPLETED`
  (`UNPROCESSABLE_STATE`).
- Tenant B cannot read Tenant A's reviews through either endpoint, and a
  direct cross-tenant `order_id` reference is rejected by the schema, not
  merely by application code.
- An operator without `review.read` is refused `INSUFFICIENT_CAPABILITY`; one
  who holds it at `BRAND` scope reads and filters correctly; the encrypted
  comment round-trips through `FieldProtection` and is never observed as
  plaintext at rest.

## Rollout and rollback

Additive: one migration, one new schema, no existing table touched. Rollback
is turning off the two new routes and, if genuinely abandoned, dropping the
schema — nothing else in the platform derives from or writes to
`reviews.order_reviews` yet, so there is nothing downstream to unwind.

## Implementation checklist

- [x] `V0168` creates `reviews.order_reviews` with the constraints and grant above
- [x] `Capability.REVIEW_READ`; granted to `tenant-owner`, `tenant-admin`, `brand-manager`
- [x] `reviews` module: `ReviewSubmissionService`, `ReviewQueryService`, `JdbcReviewStore`
- [x] Storefront controller: submit, list own
- [x] Operations controller: list/filter, replacing §5.4's not-built route
- [x] Operations §5.4 Reviews screen; §5.5 Feedback settings stays the honest not-built stub this ADR does not change
- [x] All five OpenAPI baselines regenerated (`make openapi-baseline`)
- [x] Testcontainers coverage per the Testing section above
- [ ] A storefront UI actually calls the submission/read endpoints — out of
      this wave's scope by direct instruction; tracked here so it is not
      mistaken for done

## Exit criteria

A customer with a `COMPLETED` order submits exactly one rating, optionally
with a comment, through the storefront API, and a second attempt against the
same order is refused; a customer cannot submit or read a review for an order
that is not their own; an operator holding `review.read` at `BRAND` scope
lists and filters a brand's reviews on the operations Reviews screen, seeing
each against its order and its customer; an operator without the capability
is refused; the comment never appears as plaintext in the database, a log, an
event, or a trace.

## References

- [`intent/0004-rating-what-happened`](../../../intent/0004-rating-what-happened/intent.md) —
  the originating scoping document (2026-08-28), which this ADR delivers the
  "cafe half" of; its courier-rating question is deliberately left open here
  too, for the employment-classification reason it already gives
- `docs/frontend-information-architecture.md` §5.2, §5.4, §5.5
- `docs/operations-spec/statistics.md`, the skip table's "Reviews" row and the
  "Отчёт по комментариям" note
- `frontend/storefront/src/app/pages/cart/cart-order-status/cart-order-status.component.ts`
- `frontend/operations/src/app/features/customers/customer-detail-pane.ts`
- `frontend/operations/src/app/app.routes.ts` (the `reviews` and
  `feedback-settings` not-built entries)
