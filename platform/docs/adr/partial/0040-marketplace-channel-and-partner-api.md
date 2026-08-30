# ADR 0040: Marketplace channel: inbound aggregator orders and the partner API

- Decision status: Accepted
- Implementation status: Partial — V0038 builds `partner.api_clients`,
  `partner.inbound_orders`, `ordering.order_external_pricing`,
  `order_external_references`, `order_handover_challenges`,
  `integration.provider_activity_watermarks`, and the order authority columns
  (`origin`, `pricing_authority`, `fulfillment_authority`, `entry_mode`) with the
  checks that keep `EXTERNAL` pricing on a `MARKETPLACE` order only. The `partner`
  module implements the inbound ingestion transaction with its rejection codes and
  derived idempotency (`MarketplaceIngestionService`, `JdbcMarketplaceOrderIntake`,
  `POST /api/v1/partner/tenants/{tenantId}/orders`), the narrowed `PARTNER`
  fulfilment lifecycle, handover challenges and normalised external-reference
  search, watermarks and the liveness matrix (`MarketplaceLivenessService`,
  `MarketplaceOperationsController`), and resolution of a client-credentials token
  to a tenant and its live bindings (`PartnerAuthenticationService`), covered by
  `MarketplaceChannelTests`. Not built: any write path for the credential registry
  — `JdbcPartnerStore` only reads `partner.api_clients` and stamps
  `last_authenticated_at`, so a partner client and its rotation are hand-inserted
  SQL, and nothing creates the Keycloak confidential client or mints its first
  secret. ADR 0049 deliberately keeps authorization derived from the active
  credential and live installation bindings, superseding this record's original
  `PARTNER_INTEGRATION` grant design. Not built: the nine ADR 0032
  events, none of which exists in code or in the catalogue, so nothing downstream
  hears about an aggregator order; menu pull, availability push and every outbound
  Camel adapter; manual aggregator order creation (`entry_mode = MANUAL`);
  partner-driven cancellation and status projection with the inventory release and
  the void-or-refund it must trigger; and the OpenAPI document and contract tests
  for the partner surface.
- Date proposed: 2026-08-21
- Date decided: 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture), finance (settlement), legal (fiscal liability on aggregator-collected payments), product (partner programme terms)
- Depends on: ADR 0005, ADR 0016, ADR 0018, ADR 0019, ADR 0025, ADR 0026, ADR 0031, ADR 0032, ADR 0036, ADR 0038
- Supersedes / Superseded by: — / ADR 0049 for partner-client authorization only
- Open inputs: none
- Closed inputs:
  - **The restaurant issues the receipt**, on an aggregator order as on any
    other. Settled 2026-08-23 on evidence rather than by inference: Delever
    models one order document whatever the channel, with `aggregator` as one
    delivery type beside self-pickup, delivery and hall, and every order carrying
    a fiscalization URL under the tenant's own fiscalization INN. That is a
    comparable product in the same market answering the same question, and it is
    a stronger basis than the one this record previously rested on — ADR 0038
    settled who is principal on a *direct* sale, which is a different question
    from who issues a receipt when a third party collected the money. Note the
    consequence rather than only the answer: the money arrived through an account
    that is not the restaurant's merchant account, so nothing links the receipt
    to a settled payment, and that reconciliation is designed below rather than
    discovered later. Superseded reasoning, retained because it is what the first
    draft argued: **ADR 0038 settled it** (business/legal, 2026-08-22, recorded in ADR 0038's closed inputs): the restaurant's legal entity is the seller and the legal principal, and Qoida is an agent and never the issuer. This was the one structural question holding this record at `Proposed`, and it resolves in the direction that keeps the design below intact — see [What ADR 0038 closed](#what-adr-0038-closed).
  - Whether Yandex Eda and Uzum Tezkor admit a third-party platform integration at all, and on what protocol and terms (product, partnerships). Withdrawn as an open input rather than answered: it is a commercial question about counterparties and not a design question, this record is protocol-agnostic by construction, and holding an architecture decision open on somebody else's contract negotiation blocks work that does not depend on it. It stays a rollout risk, in [Rollout and rollback](#rollout-and-rollback).

## Context

Thirty-five accepted decisions describe a platform where every order originates
in a Qoida surface. In Tashkent that is not where the orders are: Uzum Tezkor,
Yandex Eda, Wolt and Express24 carry a large share of restaurant delivery, and
the Click mini-app is a storefront inside an app most customers already have. A
tenant that cannot take those orders keeps a tablet per aggregator on the pass
and re-keys every order into the till.

An inbound aggregator order breaks two accepted premises at once.

**It arrives already priced.** The aggregator computed a discount on its own
side, from campaign data Qoida does not have, funded partly from its own margin.
Delever models this as a promotion type, «Свободная скидка», with no input fields
at all: it exists purely so an external system can push an arbitrary amount. ADR
0018 promises that the same context and clock always produce the same total, and
that every total is reconstructible from stored evidence. A number Qoida cannot
re-derive will never match a quote, and admitting it quietly fills reconciliation
reports with rows that can never be explained.

**Its fulfilment may not be ours.** For a Yandex Eda order, Yandex dispatches the
courier, tells the customer where the food is, and decides when it is cancelled.
ADR 0019 says POS and delivery status "may propose transitions but never directly
overwrite the order", because two authorities over one state machine turn a
provider bug into a commercial fact. That rule was written about systems Qoida
instructs. A marketplace partner is not one of them.

Third, customers and couriers quote the aggregator's order number, not Qoida's,
and ADR 0026's `provider_entity_mappings` cannot hold those: its unique keys make
it a one-to-one map per binding per entity type.

One question was not answerable when this was written, which is why it stood at
`Proposed`. Under Uzbek fiscal rules a receipt is issued by the party that took
the money, and whether a restaurant must still fiscalise a sale Yandex Eda
collected payment for was unresolved. It was structural: a fiscal receipt needs
an ИКПУ per line and a per-branch INN, so if the merchant must fiscalise, the
unmapped-line rule below reverses. It has since been answered.

## What ADR 0038 closed

**The restaurant's legal entity is the seller and the legal principal; Qoida is
an agent and never the issuer.** Business and legal settled that on 2026-08-22
and ADR 0038 records it as a closed input. It is the answer this record was
waiting for, and it decides three things here.

**A marketplace order belongs in `ordering.orders`.** It is a sale by the same
restaurant, under the same legal entity, carrying the same fiscal obligation as
every other sale that branch makes. Had the aggregator been the principal, an
aggregator order would have been a commercially different object — somebody
else's sale that a Qoida kitchen happened to cook — and a separate aggregate
would have been the honest model rather than the expensive one. It is not, so
the decision below stands as written.

**The fiscal obligation resolves without the merchant fiscalising.** ADR 0038
gives each payment method a declared responsibility, and an aggregator-settled
order uses a method whose responsibility is `MARKETPLACE`: the aggregator issues,
where contracted as fiscal agent, and Qoida records the obligation as not
required with the contract reference as evidence. ADR 0038 makes that contract
reference mandatory on such a method at activation, so a tenant cannot tick
"the aggregator fiscalises" and have nothing fiscalise.

**The unmapped-line rule therefore stands rather than reversing.** The tolerance
below — accept the order, flag the line — was conditional on the merchant not
needing an ИКПУ for every line of an aggregator sale. It does not, so a line the
catalogue cannot classify is a menu-sync problem a person fixes at the pass and
not a receipt that cannot be issued. If a tenant's aggregator contract ever
lacks the fiscal-agent clause ADR 0038 requires, that tenant's orders are refused
at method activation and never reach this path at all, which is the right place
for that failure and not here.

## Decision

**An inbound aggregator order is a first-class `ordering.orders` row**, not a
separate aggregate and not a second table, so every operations list, filter,
report, refund path, timeline and audit query keeps working on it unchanged. What
distinguishes it is three immutable flags and a partner attribution.

| Flag | Values | Meaning |
|---|---|---|
| `origin` | `QOIDA`, `MARKETPLACE` | Where the order was placed |
| `pricing_authority` | `QOIDA`, `EXTERNAL` | Who computed the total |
| `fulfillment_authority` | `QOIDA`, `PARTNER` | Who owns the courier and the customer promise |

**`pricing_authority = EXTERNAL` bypasses the ADR 0018 quote engine entirely** —
no quote, no promotion evaluation, no coupon reservation. The partner's amounts
are stored verbatim, and Qoida validates arithmetic only: lines plus fees minus
discounts must equal the stated total, to the som. An order that fails is refused,
because a booked total that does not equal the sum of its lines is what an
accountant finds three months later and nobody can explain.

**`EXTERNAL` is legal only when the order's channel resolves to a binding of
category `MARKETPLACE`,** enforced by a check constraint. This is the boundary
the ADR exists to draw: Delever's «Свободная скидка» works on any channel, and on
Qoida's storefront, bot or operator channels that would mean anyone who can set a
discount field can set any total. Externally priced orders are also excluded from
pricing reconciliation and labelled wherever they are aggregated, because a
report averaging reproducible and non-reproducible totals is lying about both.

**`ordering.orders.pricing_authority` is the only enforcement point, and nothing
else may act as a second one.** ADR 0036's channel-level `externally_priced` is a
default that seeds this column when an order is created on the channel; the
pricing engine reads the order's `pricing_authority` and never the channel flag.
Two switches that both mean "do not price this" diverge the moment a channel is
reconfigured after orders were taken on it, and then a nine-month-old order
re-prices differently from the way it was booked. For the same reason an
externally priced order carries its delivery fee inside the supplied totals and
never enters ADR 0037's fee resolution: ADR 0037's `fee_source =
EXTERNAL_CHANNEL` is withdrawn, because a fee Qoida resolves on an order Qoida
did not price is the same escape hatch under a second name.

**`fulfillment_authority = PARTNER` narrows Qoida's state machine rather than
surrendering it.** Qoida stays the only writer of `orders.status`; partner courier
state is a projection stored beside it. The order runs `RECEIVED → CONFIRMED →
PREPARING → READY → COMPLETED`, skipping `FULFILLING` because Qoida fulfils
nothing, and reaches `COMPLETED` at proven handover rather than at delivery,
because handover is the last event Qoida can observe. One exception is granted
knowingly: **a partner may cancel a confirmed order**, reason
`PARTNER_CANCELLED`. Refusing it leaves a kitchen cooking food for a customer the
aggregator has already refunded.

**A sixth HTTP surface, `/api/v1/partner/**`, joins ADR 0031's five.** Any
aggregator can integrate against it without a Qoida release; provider-specific
adapters exist for partners who will not, and read the same projections so the
two cannot disagree.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Model marketplace orders as a separate aggregate that projects into the order list | Keeps the awkward flags out of `ordering.orders`. Rejected because every operations screen, filter, report, cancellation, refund and audit query needs a second implementation, and the two drift exactly where it matters: money and status | A partner channel appears whose lifecycle shares nothing with a Qoida order — catering that never reaches a kitchen, for instance |
| Refuse aggregator orders; make partners use the storefront | Preserves every ADR 0018 and 0019 premise at zero cost. Rejected because no aggregator will do it, and the tenant's real alternative is a tablet per aggregator with a human re-keying orders | Never, while aggregators are a primary revenue channel in this market |
| Re-derive the aggregator's price and reject on mismatch | Would keep one pricing authority. Rejected because the discount is the partner's own commercial decision computed from data Qoida will not be given, so every legitimate promotional order would be refused | A partner publishes a rule contract Qoida can evaluate deterministically; that partner's channel then moves to `pricing_authority = QOIDA` |
| Copy «Свободная скидка» as a promotion type usable on any channel | Smallest change, fits the existing model. Rejected because it makes an unvalidated external total reachable from Qoida's own channels, destroying the property ADR 0018 exists to guarantee. An operator discount is an actor, a reason and an audit fact — not an unattributed number | Never |
| Static per-partner API key in a header, as Delever's 1C and generic aggregator integrations use | Simplest thing partners accept. Rejected because it cannot be rotated without an outage, scoped, or expired, and in Delever's case is a real user's password in a reversible encoding | Never |
| Per-provider adapters only, with no generic partner API | Matches how aggregators behave today. Rejected because every new partner then becomes a release — which is why Delever built a generic dynamic integration as an escape hatch | Never; the generic API is the contract and adapters are the compatibility layer |

## Provider category, bindings, and credentials

**A `MARKETPLACE` category is added to ADR 0026's enum in
`integration/api/provider/ProviderCategory.java`, bound per location**, because
every aggregator issues a venue identifier per branch. It is distinct from
`DELIVERY`: Yandex Delivery sources a courier for an order Qoida owns, Yandex Eda
sends an order Qoida did not create — same company, opposite direction, two
installations. Capability codes owned by this ADR:
`marketplace.order.receive`, `marketplace.order.status.push`,
`marketplace.menu.read`, `marketplace.menu.push`,
`marketplace.availability.push`, `marketplace.handover.verify`.

Credentials run in both directions, which ADR 0026 does not distinguish today.
Outbound is the existing `secret_reference`. **Inbound is OAuth 2.0 client
credentials** — a Keycloak confidential client per installation, secret in ADR
0028, shown once — never `base64(login:password)` of a real panel user, which is
what Delever's generic aggregator mints and which makes a partner credential
indistinguishable from a human's password. The client carries an ADR 0025 grant
of the role `PARTNER_INTEGRATION` at the scope of its bindings, so a partner
token reads nothing a branch operator at those locations could not. Rotation
issues a new secret against the same client ID and never changes the
installation ID.

## Physical model

```text
ordering.orders  (columns added)
  origin (QOIDA|MARKETPLACE), pricing_authority (QOIDA|EXTERNAL)
  fulfillment_authority (QOIDA|PARTNER), entry_mode (API|MANUAL|IMPORT)
  marketplace_binding_id null references integration.bindings
  check (pricing_authority = 'QOIDA' or origin = 'MARKETPLACE')
  check (origin = 'QOIDA' or marketplace_binding_id is not null)
  check (pricing_authority = 'QOIDA' or pricing_quote_id is null)

ordering.order_external_pricing
  order_id, tenant_id, binding_id, currency, customer_paid_total_minor
  external_subtotal_minor, external_discount_minor, external_fee_minor
  external_tax_minor null, discount_funding (PARTNER|MERCHANT|SPLIT|UNKNOWN)
  partner_commission_minor null, partner_payout_minor null
  arithmetic_verified, raw_totals jsonb

ordering.order_external_references
  id, tenant_id, order_id, binding_id null, issued_by, first_seen_at, version
  reference_type (PARTNER_ORDER_ID|PARTNER_DISPLAY_CODE
                  |PARTNER_VENUE_ORDER_NO|DELIVERY_CLAIM_ID|POS_ORDER_ID)
  reference_value, reference_value_normalised
  unique (tenant_id, binding_id, reference_type, reference_value_normalised)
  index  (tenant_id, reference_value_normalised)

ordering.order_handover_challenges
  id, tenant_id, order_id, binding_id null, version
  challenge_type (CODE|QR|SIGNATURE|NONE), issued_by (PARTNER|QOIDA)
  expected_value_hash null, attempts, max_attempts
  status (PENDING|VERIFIED|BYPASSED|FAILED|EXPIRED)
  verified_at null, verified_by null, bypass_reason_code null

integration.provider_activity_watermarks
  tenant_id, binding_id, location_id, direction (INBOUND|OUTBOUND)
  last_success_at, last_success_reference, last_failure_at, last_failure_code
  stale_after_seconds, observed_median_interval_seconds null, alert_state
  primary key (tenant_id, binding_id, direction)
```

`partner.inbound_orders` stages every push — binding, external order ID,
encrypted raw payload, hash, outcome, rejection code — unique on `(tenant,
binding, external_order_id)`, and is the evidence that a rejected order was
received.
`partner_commission_minor` and `partner_payout_minor` come only from an imported
settlement statement: the push states what the customer paid, what the restaurant
receives is decided weeks later, and reconciling them is ADR 0043's work. The
columns exist so that is not a migration.

### Three names this record chose differently in the build

Recorded here rather than left as a discrepancy between the decision and the
schema, because reading the two should not produce two different beliefs.

**`fulfillment_authority`, not `fulfilment_authority`.** `ordering.orders`
already carries `fulfillment_mode` and `fulfillment_status_projection`, and the
schema is named `fulfillment`. One table holding two spellings of one word is a
column somebody eventually types wrong in a report, and the report is silently
short rather than broken.

**`partner.inbound_orders`, not `ordering.marketplace_inbound_orders`.** Nothing
in `ordering` reads or writes the staging table; it is the partner module's
evidence that a push arrived, including the pushes that were refused and
therefore never became orders. Filing it in `ordering` would put a table only one
module touches in the schema every other module reasons about.

**The promise of a partner-fulfilled order is `NOT_PROMISED`.** The aggregator
made a promise to the customer; Qoida did not. Copying the partner's ETA into
ADR 0036's promise columns would make it indistinguishable from a promise this
platform is accountable for, and every lateness figure derived from those columns
would then be measuring somebody else's punctuality. The partner's expected
pickup time is kept on the staging row, where it reads as what it is. The
consequence is real and accepted: a kitchen screen sequencing by `promised_at`
cannot sequence aggregator orders against storefront ones. Fixing it properly is
a `PARTNER_PROMISE` value in ADR 0036's `ck_order_promise_basis`, which is that
record's vocabulary to extend and not this one's.

**External reference uniqueness** is per `(tenant, binding, reference_type,
normalised value)` — deliberately not global and not per tenant alone, because two
aggregators legitimately issue the same short numeric code on one day and a wider
index would reject the second order outright. Normalisation uppercases and strips
whitespace, hyphens and a leading `#`; search matches the normalised column across
the tenant and may return several rows, disambiguated by provider and branch.
Without it an operator reading `YE-2291-04` off a courier's phone finds nothing
while the order sits four rows above in the same list.

## Ingestion and rejection

A partner POST lands in `marketplace_inbound_orders` and, in the same
transaction, either creates an order or records a rejection. No external call
happens inside it, per ADR 0019.

| Condition | Outcome |
|---|---|
| `(binding, external_order_id)` already present | Return the existing order — the retry and failed-sync recovery path, and the exact moment a duplicate gets created |
| Lines plus fees minus discounts ≠ stated total | Reject, `EXTERNAL_TOTAL_MISMATCH` |
| Venue resolves to no active binding | Reject, `UNKNOWN_VENUE` |
| Branch closed beyond the configured grace window | Reject, `BRANCH_CLOSED` |
| Currency is not the branch's currency | Reject, `CURRENCY_MISMATCH` |
| A line maps to no Qoida catalog node | **Accept.** Store it `UNMAPPED` with the partner's own name and amount and raise a location-visible exception |

Tolerating an unmapped line is the deliberate call: it is normally a menu-sync
lag on one item, and refusing the order means a customer who already paid the
aggregator gets nothing while the branch never learns why. A flagged line is a
problem a person solves in the thirty seconds before the food is cooked. This is
the rule that reverses if legal answers the fiscal open input against us.

**Idempotency.** ADR 0031 requires a client-supplied `Idempotency-Key`; partners
will not send one, so on `/api/v1/partner/**` the key is derived from
`(binding_id, external_order_id)` when the header is absent. A documented
exception, and the stronger key here: Qoida does not control the partner's retry
client, while its order identifier is stable by construction.

**Customer identity.** A marketplace order neither creates nor matches a
`customers.customer_account`. Aggregators proxy customer phone numbers and
recycle the proxy pool, so matching on one would merge unrelated people into a
single record and attach their addresses, history and consent to each other. The
order stores a snapshot flagged `contact_is_proxy`, with the partner as
acquisition source, and ADR 0015's identity model is untouched.

## Handover verification

Delever's screen is written entirely around Yandex order IDs and Yandex-supplied
codes. Generalising costs one table and removes a per-provider screen.

**`ordering.order_handover_challenges` is the platform's only
handover-verification model, and this ADR owns it.** ADR 0041 does not create a
`kitchen.handovers` table; the expo station verifies against this one. Handing a
bag to an aggregator courier and handing it to a customer at the pass are the
same physical act with the same failure — the wrong person leaves with the food —
and two tables would mean two hash schemes, two attempt counters and two answers
to "was this order proven handed over". `binding_id` is null for an order with no
marketplace binding, which is how a pickup or internal-courier handover uses the
same row shape. The capability split stands and both capabilities act on this
table: `kitchen.handover.complete`, owned by ADR 0041, lets the expo station
verify a challenge and close the order; `marketplace.handover.bypass`, owned
here, is the audited override. Completing a handover is a daily act at the pass;
overriding verification is not, and one capability covering both would put the
override in every expo bundle.

The table arrives with this record, and the expo station verifies against it.
One thing is not yet as written: the verification endpoint declares
`order.advance` rather than `kitchen.handover.complete`, because that capability
is ADR 0041's to register and it has not been. `order.advance` is the closest
existing grant and is held by the same people at the same station, so nothing is
loosened by the interim; the declaration moves to the narrower code the moment
ADR 0041 declares it. `marketplace.handover.bypass` is registered here, as
written, because it is this record's to own.

```text
PENDING -> VERIFIED   correct value entered
PENDING -> FAILED     max_attempts exhausted
PENDING -> BYPASSED   supervisor override, reason code required
PENDING -> EXPIRED    order cancelled or aged out
```

The expected value is a peppered hash compared in constant time — a handover code
in a readable column is a code anyone with a read replica can use. `max_attempts`
defaults to 5, and exhaustion requires `marketplace.handover.bypass` and writes an
ADR 0027 audit fact naming the supervisor and reason. `challenge_type = NONE` is
an explicit configured value per binding, never a null, so a branch cannot skip
verification because a field happened to be empty. Where the partner has no
protocol Qoida issues the code and pushes it. The failure this prevents: two
aggregator couriers reach a Chilanzar branch a minute apart, a 420,000 som order
goes to the wrong one, and nobody can prove which took which bag.

## Liveness watermarks

A dead marketplace integration produces no errors, because nothing is being
called. An expired token or a revoked venue looks exactly like a quiet Tuesday
until the manager notices Friday was quiet too.

`provider_activity_watermarks` records the last successful inbound order and
outbound push per `(tenant, binding, direction)`. Staleness compares against
`stale_after_seconds`, resolved per binding through ADR 0030, because a branch
taking two Uzum orders a day and one taking two hundred have different silences;
the observed trailing median sits beside the threshold so the number is set from
evidence rather than guessed. Crossing it emits `MarketplaceChannelWentStale` and
raises an ADR 0006 failure an operator must resolve explicitly — extending ADR
0006, which records failures that happened and has no concept of work that
stopped arriving. `GET /api/v1/operations/marketplace/liveness` returns the
locations × bindings matrix Delever shows as «Отчёт по последнему заказу».

## Partner API

```text
POST {keycloak}/realms/qoida/protocol/openid-connect/token        client_credentials
GET  /api/v1/partner/tenants/{tenantId}/restaurants
GET  /api/v1/partner/tenants/{tenantId}/restaurants/{locationId}/availability
GET  /api/v1/partner/tenants/{tenantId}/restaurants/{locationId}/menu
POST /api/v1/partner/tenants/{tenantId}/orders
GET  /api/v1/partner/tenants/{tenantId}/orders/{externalOrderId}
POST /api/v1/partner/tenants/{tenantId}/orders/{externalOrderId}/cancellations
POST /api/v1/partner/tenants/{tenantId}/orders/{externalOrderId}/status-events
```

The token endpoint is Keycloak's own and not a Qoida path, which is a correction
to this record's first sketch rather than a change of decision. Proxying the
token exchange through `/api/v1/partner/oauth/token` would put Qoida in the
credential path for no benefit: it would have to receive the client secret in
order to forward it, which is exactly the property a confidential client exists
to avoid, and it would give Qoida a second implementation of a protocol Keycloak
already implements correctly. Partners are handed an issuer URL, a client id and
a secret, and what reaches Qoida is a bearer token like every other.

Tenant stays in the path, matched against the client's bound tenant, as ADR 0031
requires of every surface. **Menu is pull-first, order status push-first**: a pull
with an `ETag` against the ADR 0016 published projection costs no per-partner
fan-out state and cannot go stale differently per partner, and push-only partners
get an adapter over the same projection. **Partners read; they never write
catalog** — an aggregator-side promotion is not a Qoida promotion but an external
discount on an order. **Availability is binary**, per ADR 0017; Delever's
per-aggregator "stop at remaining quantity N" has no quantity model behind it
here. Rate limits are per client under ADR 0033, with a burst allowance because
aggregators poll.

An operator may also create a marketplace order by hand (`entry_mode = MANUAL`),
for an unintegrated partner or to recover a failed sync. It needs
`marketplace.order.create.manual`, an active `MARKETPLACE` binding for the
location — credential-free installations are permitted for unintegrated partners
— and a typed `PARTNER_ORDER_ID`. The duplicate rule applies identically, and the
creation is an ADR 0027 audit fact naming the operator, who is typing a total the
platform cannot verify.

## Events

Under ADR 0032, on `orders.events`: `MarketplaceOrderReceived`,
`MarketplaceOrderRejected`, `MarketplaceOrderCancelledByPartner`,
`MarketplaceHandoverVerified`, `MarketplaceHandoverBypassed`. On
`integration.events`: `MarketplaceMenuPublished`, `MarketplaceAvailabilityPushed`,
`MarketplaceChannelWentStale`, `MarketplaceChannelRecovered`. Payloads never carry
the proxied customer contact, the handover code or its hash, or a partner
credential. Inbound pushes are consumed through the ADR 0005 inbox.

## Testing

- An externally priced order whose lines do not sum to its total is rejected, and
  the rejection is stored with the raw payload.
- `pricing_authority = EXTERNAL` on a non-marketplace channel fails at the
  database, not only in the service.
- Two concurrent pushes of one `(binding, external_order_id)` produce one order,
  and a partner cancellation after `CONFIRMED` releases inventory and voids or
  refunds exactly once. A partner courier status never changes `orders.status`.
- Five wrong handover codes reach `FAILED`, bypass without the capability is
  denied, and the expected value never appears in a response, log, or trace.
- Reference search finds an order by a hyphenated, spaced, lowercase rendering of
  the partner's code, and a partner token cannot read an order outside its
  bindings or another tenant's.

## Rollout and rollback

Whether Yandex Eda and Uzum Tezkor will admit a third-party integration at all
is unanswered and is a rollout risk rather than a design one. Every step below
except the last has value without a partner agreement: the credential lifecycle
proves itself against a fake partner client, and manual aggregator orders are
useful precisely to tenants whose aggregators Qoida has not integrated. If no
partner ever signs, what is lost is the inbound API and not the model.

Ship the category, bindings and inbound credential issuance with no live partner,
proving issuance, rotation and scope against a fake partner client per ADR 0007.
Enable manual aggregator orders next: they exercise the order columns, external
references and handover challenge with no partner dependency, and have standalone
value for tenants whose aggregators Qoida has not integrated. Then one real
partner at one branch, inbound only, compared against that partner's own portal
for a week; then menu pull, availability push, status push. Rollback suspends the
binding — orders already received keep their lifecycle and never revert to being
re-keyed mid-order, and the watermark keeps recording, so the channel is visibly
suspended rather than silently stale.

## Consequences

### Positive

- One order table, so every operations screen, report, refund and audit query
  works on aggregator orders the day they arrive.
- The externally priced escape hatch exists exactly once, on exactly the channel
  that requires it, enforced by a constraint, so ADR 0018's promise stays true
  for every channel Qoida prices.
- Support finds an order by the number the customer or courier quotes, however
  they read it back, and a dead integration becomes an alert rather than a quiet
  week.

### Negative

- Three authority flags on `ordering.orders` are three things every future query
  must remember. A report that forgets `pricing_authority` averages reproducible
  and non-reproducible totals together and is quietly wrong.
- Marketplace revenue figures are the partner's figures, so a partner-side bug
  becomes a Qoida number until settlement contradicts it.
- Qoida's `COMPLETED` and the customer's experience of "delivered" are different
  events, minutes to an hour apart, so any delivery-time metric mixing channels
  measures two different things.
- A Keycloak client per partner installation is another identity object with its
  own rotation and expiry lifecycle, and every aggregator's real protocol will
  differ from the generic API, so the adapter layer grows anyway.

### Accepted trade-offs

- Marketplace orders never join a customer account, so cross-channel customer
  analytics is deliberately incomplete rather than deliberately wrong.
- Menu is pull-first, so a push-only partner needs an adapter — accepted to avoid
  per-partner fan-out state for the common case.
- A partner may cancel a confirmed order: a second writer of one transition,
  accepted because the alternative is cooked food nobody collects.

## Implementation checklist

- [x] Resolve the fiscal open input; confirm or reverse the unmapped-line rule.
      ADR 0038 confirmed it — see [What ADR 0038 closed](#what-adr-0038-closed).
- [x] Add `MARKETPLACE` to the provider category and register the capability codes.
- [x] Add order authority columns, external pricing, external references, handover
      challenges, inbound staging and watermarks via Flyway (V0038).
- [x] Implement the ingestion transaction, rejection codes and derived idempotency.
- [x] Implement the narrowed lifecycle for `fulfillment_authority = PARTNER`.
- [x] Implement handover challenges and normalised external-reference search.
- [x] Implement watermarks, staleness evaluation and the liveness matrix endpoint.
- [ ] Implement the credential registry, its rotation lifecycle, and the resolution
      of a client-credentials token to a tenant and a binding set.
- [ ] **Issue the Keycloak confidential client.** The registry row, the status
      lifecycle and the ADR 0028 secret reference are built; creating the client
      in Keycloak and minting its first secret is not. Until it is, a partner
      credential is registered by an operator against a client somebody created
      by hand, which is fine for the first partner and not fine for the tenth.
- [ ] **Grant `PARTNER_INTEGRATION` at the scope of the bindings.** The reach is
      currently derived from the installation's live bindings at authentication
      time, which is the correct answer and is deliberately not a copy — but it
      bypasses ADR 0025's grant table rather than being expressed in it, so a
      partner's reach is not visible in the same place as everybody else's.
- [ ] **Publish the nine events.** `MarketplaceOrderReceived` and the rest need an
      `EventCatalog` entry, a JSON Schema file and a row in
      `docs/domains/events.md` each, per ADR 0032. Nothing downstream hears about
      an aggregator order yet.
- [ ] **Menu pull, availability push, and the outbound adapters.** The read side
      of the partner API and every outbound call to a partner are unbuilt. Under
      ADR 0007 the outbound half is a Camel route with a fake provider suite, and
      it should be built as one rather than as an HTTP client that later grows a
      route.
- [ ] **Manual aggregator order creation.** `entry_mode = MANUAL` and its
      capability exist in the model; the operator path that writes one does not.
      This is the second rollout step and has standalone value for tenants whose
      aggregators Qoida has not integrated.
- [ ] **Partner cancellation and status projection.** The lifecycle states which
      transition a partner may drive; the endpoint that accepts it, the inventory
      release and the void-or-refund it must trigger exactly once are unbuilt.
- [ ] **The OpenAPI document and contract tests for the partner surface.**

## Exit criteria

An aggregator order appears in the operations list beside a storefront order and
behaves identically for filtering, cancellation, refund and audit; no channel
other than `MARKETPLACE` can carry an externally computed total; an order is
findable by the identifier the partner quotes, however it is typed; a handover
cannot complete without a verified challenge or an audited bypass; and a channel
that stops receiving orders alerts before a human notices the silence.

## References

- [ADR 0018: Deterministic pricing, promotions, taxes, and quotes](../partial/0018-deterministic-pricing-promotions-taxes-and-quotes.md)
- [ADR 0019: Cart, checkout, and order orchestration](../partial/0019-cart-checkout-and-order-orchestration.md)
- [ADR 0026: Provider installations, bindings, and secret references](../built/0026-provider-installations-bindings-and-secret-references.md)
- [ADR 0036: Sales channels and location serviceability](../partial/0036-sales-channels-and-location-serviceability.md)
- [ADR 0041: Kitchen execution and production routing](../partial/0041-kitchen-execution-and-production-routing.md)
- [Delever parity matrix](../../delever-parity-matrix.md)
