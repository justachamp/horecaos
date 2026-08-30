# ADR 0048: Refunds as bookkeeping, and the order-remedy model

- Decision status: Accepted
- Implementation status: Partial — V0052 creates `payments.order_remedies`,
  `payments.remedy_entitlements` and `payments.entitlement_redemptions` with the
  amount-split, attestation-evidence, verification-source and capped-percentage
  invariants as check constraints and the reconciliation worklist as a partial
  index; `OrderRemedyService` records a console refund, a delivery-fee
  reimbursement and a future-discount grant, weighs each against everything the
  order has already given back through `JdbcRemedyStore.moneyRemediedMinor`,
  takes its cumulative cap from `OrderSettlementService.refund` (V0048
  `tenders.refunded_minor`) rather than reimplementing one, and raises the
  ADR 0027 facts `payments.remedy.record`, `payments.remedy.future-discount` and
  `payments.remedy.verify`; `RemedyEntitlementService` implements
  `RemedyEntitlementPort` with a redemption row keyed to its order and a
  conditional `UPDATE` bounded by `uses_consumed < uses_granted`;
  `OperationsRemedyController` exposes four mutations and three reads at
  `/api/v1/operations/tenants/{tenantId}/…` under `refund.request`,
  `refund.execute` and `payment.read`; and `RefundAndRemedyTests` covers the
  invariants in twenty-two tests against PostgreSQL. **Four things stand between
  this and an operator.** `OrderSettlementService.plan` still has no production
  caller, so `payments.order_settlements` is never written and every money remedy
  fails at `require` with "The order has no settlement" — the refund endpoint
  cannot succeed against a real order today. No `DeliveryFeeBasisPort`
  implementation is wired, so `DeliveryFeeBasisConfiguration` supplies the
  stand-in that answers empty and every reimbursement records a null
  `delivery_fee_basis_minor`. Nothing calls `RemedyEntitlementPort` outside
  `RefundAndRemedyTests` — ADR 0018 pricing neither asks what is available nor
  redeems — so a granted future discount cannot be spent by any checkout. And
  nothing schedules `RemedyEntitlementService.expireLapsed`, so `status` stays
  `ACTIVE` past `expires_at` while the read paths carry the window check.
  Also not built: the future-discount liability report (no query in the tree
  aggregates outstanding entitlement exposure), ADR 0013's settlement import and
  the daily reconciliation that would move a row off the unverified worklist, an
  `evidence_reference` for the cabinet export, any event or outbox row, and a
  refund runbook under [`docs/runbooks/`](../../runbooks/).
- Date proposed: 2026-08-25
- Date decided: 2026-08-25
- Deciders: Ayubkhon Abbosov (platform architecture, owner of the 2026-08-25 decision), finance (settlement, liability, and who bears a reimbursement), support operations (the people who actually perform the refund), product (the remedy set)
- Depends on: ADR 0013, ADR 0018, ADR 0025, ADR 0027, ADR 0029, ADR 0031, ADR 0043, ADR 0046
- Supersedes / Superseded by: Supersedes the refund and service-recovery compensation decision of [ADR 0013](../partial/0013-payment-refund-and-service-recovery-compensation.md) — its `payments.refunds` physical model, its `execution_channel` of `PROVIDER_CONSOLE | PLATFORM`, and its deferral of every non-refund remedy to a later `recovery` module. Everything else in ADR 0013 — the provider port, the payment state machine, uncertainty resolution, the som/tiyin boundary, and the cash `NOT_APPLICABLE` fiscal state — is untouched and remains current
- Open inputs: Whether Click and Payme publish a machine-readable settlement statement per legal entity at all, which decides whether the verification path can ever be automated or stays a second human assertion forever (integration discovery, finance); who bears a delivery-fee reimbursement — tenant, courier partner, or platform — which decides whether it is a refund line or a cost allocation on the ADR 0043 metric layer (finance, product; carried forward unanswered from ADR 0013); where the future-discount liability is reported and on what basis it is valued — granted exposure, remaining exposure, or expected redemption (finance, with ADR 0043); whether a cabinet action may be evidenced by an upload held as an ADR 0029 protected reference, and what retention that evidence needs (legal, finance); the remedy reason-code vocabulary and whether the approval threshold is per tenant rather than the single platform-wide `qoida.payments.remedy-approval-threshold-minor` default of 200 000 (product, finance). **None is structural** — each changes a column, a report, or a configuration scope, not the model

## Context

[ADR 0013](../partial/0013-payment-refund-and-service-recovery-compensation.md) read both
provider contracts in full and concluded, correctly, that neither Click nor Payme
offers a refund primitive in the shape the platform needs. Click's
`payment/reversal` takes no amount, requires an online-card payment, and is bounded
by the reporting month. Payme has no outbound refund call at all: the cabinet's
refund button calls the *merchant's* `CancelTransaction`, which Qoida can only
veto. That finding has not changed and is not reopened here.

What ADR 0013 then decided was that refunds are "deferred to the provider console
**for the cutover**". The word is load-bearing, and the physical model carries it:
`payments.refunds` has an `execution_channel` of `PROVIDER_CONSOLE | PLATFORM`,
annotated "PLATFORM unimplemented at cutover". That is the shape of a payment
operation the platform is on its way to performing and has not got to yet — a
temporary manual step with a machine behind it.

**The owner decided on 2026-08-25 that there is no machine behind it, and there
will not be one.** Staff refund in the provider's own cabinet because that is
where the money is, and where the person with the authority to move it sits. Qoida
records what they did. The record exists so the order, the settlement and the
analytics are not broken by money that moved outside the system — not so a
customer is refunded, because the customer was already refunded, in the cabinet,
before anybody opened Qoida.

That reframing changes what the record has to be good at. A deferred payment
operation needs to be resumable. A bookkeeping record needs to be *distinguishable
from a fact*, because the platform is asserting something it did not observe and
cannot check.

**The operator's problem is also wider than a refund, and always was.** A cold
pizza or a forty-minute-late delivery is usually answered by reimbursing the
delivery fee, or by giving the customer something off their next order — not by
returning the price of the goods. ADR 0013 listed those as recovery remedies and
gated them behind a `recovery` module, ADR 0015 identity, and ADR 0018 benefit
reservation. The gate was the wrong trade: three endpoints do not need a module,
and support had one remedy where it needed three.

**And a future discount is not a refund with a delay.** It costs the tenant nothing
today, may cost nothing ever, and is bounded by nothing the settlement service
knows about. Weighed by its immediate cash cost it is always zero, which puts every
grant under any approval threshold — and the ten-use grant is the one shape of
this remedy that most deserves a second pair of eyes.

Migration [V0052](../../../src/main/resources/db/migration/V0052__record_remedies_horecaos_did_not_perform.sql)
implements this decision. Until this record existed, its only written trace was
that file's header comment.

## Decision

- **Qoida never calls a payment provider's refund API.** Not at cutover, not
  after it. There is no `PLATFORM` execution channel and nothing to grow into;
  `ExecutionChannel` is `PROVIDER_CONSOLE | CASH_DRAWER | BANK_TRANSFER`, which
  are three places a *person* moves money, not three capabilities the platform
  has.
- **A refund is a bookkeeping and remedy record, and its purpose is the books.**
  The customer is made whole in the cabinet. The row exists so that the order's
  settlement, the tender balances, the loyalty ledger and every report over them
  stay whole afterwards.
- **Every remedy amount is split at the moment of recording into the part the
  platform settled itself and the part it is taking on trust.**
  `platform_settled_minor + attested_money_minor = amount_minor`, as a check
  constraint, so no report can produce a single refund figure without having
  discarded the distinction on purpose.
- **Three remedies and no fourth**: `ORDER_REFUND`,
  `DELIVERY_FEE_REIMBURSEMENT`, `FUTURE_DISCOUNT`. Full and partial are the same
  command — "full" is the amount that happens to equal what is left, and a
  separate full-refund entry point is a second place for the cap to be got wrong.
- **A future discount is not money.** `amount_minor = 0` and
  `settlement_basis = 'NOT_MONEY'` by constraint, so it cannot be summed into a
  refund figure by a query that forgot to filter. Its exposure lives on the
  entitlement, and what an approver weighs is uses × per-use maximum, never zero.
- **An attestation is born `UNVERIFIED` and only an outside source moves it.**
  `verification_state` is an explicit value, never a null that would read as
  "unknown", and `CONFIRMED`/`DISPUTED` require a named source and a timestamp.
  Only a row with attested money can be verified at all — verifying a points
  reversal would be verifying our own ledger against itself.
- **The cumulative cap is the settlement service's.**
  `OrderSettlementService.refund` unwinds ADR 0046 tenders in reverse settlement
  order against `tenders.refunded_minor`, and what it returns as money is exactly
  the part Qoida did not perform. The remedy service does not apportion; a second
  opinion about the same money diverges on the first split-tender partial refund.
- **The remedy is keyed to the order, not to a capture.** An order settles through
  an ordered set of tenders, not one payment intent, so `payments.order_remedies`
  carries `order_id` and no `payment_intent_id` or `payment_transaction_id`. This
  is a real loss and is stated as one under [Consequences](#negative).
- **Service recovery lives in `payments`, not in a separate `recovery` module.**
  The three remedies are decided beside a refund, by the same operator, under the
  same approval threshold and against the same cap. The module split ADR 0013
  specified is right for a recovery *case* — a lifecycle, an SLA, a versioned
  policy — and is overhead for three endpoints that need the tender cap anyway.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep ADR 0013's `payments.refunds` with its `PROVIDER_CONSOLE \| PLATFORM` channel | The `PLATFORM` value is a promise the providers cannot keep, and a nullable enum member that nothing ever writes reads to every later maintainer as work in progress rather than as a decision. The table shape also assumes one capture per refund, which ADR 0046 split tender had already made false | Click publishes a partial-refund endpoint, **or** Payme exposes an outbound refund the platform may drive. Either makes `PLATFORM` real, and it arrives as a new ADR that supersedes this one |
| Build platform-initiated refunds now | Unchanged from ADR 0013 and still decisive: a primitive that exists on neither provider in the needed shape is not a foundation. Nothing about the remedy model changes that | Same trigger as the row above |
| Record one `amount_minor` and no attested/settled split | The single figure is the failure. A refund that came back as loyalty points is a movement Qoida performed and can prove; a refund that came back as card money is one person's word. Summed into one column they are indistinguishable, and every downstream report inherits the confusion silently | Never. If a settlement import ever discharges attestations automatically the columns stay — the split is what the import matches against |
| Leave `verification_state` null until something can set it | Null means "unknown". The entire point is that this is *known*-unverified: the platform is aware it has recorded an assertion, and a reversal of the decision must be able to find the affected rows by query. ADR 0013 made the same call for the cash `NOT_APPLICABLE` fiscal state, for the same reason | Never |
| Refuse to record any refund until reconciliation exists | Leaves the platform in the state ADR 0013 already rejected: after a console refund its state is indistinguishable from an unrefunded capture, the cap has nothing to fail against, and a second console refund is invisible. An unverifiable record is worse than a verified one and much better than none | Never |
| Model the future discount as ADR 0046 loyalty points | Points are a currency: pooled with earned balance, spendable on anything, with a lot ledger and an expiry policy of their own, and with the cash-like properties the tender ordering exists to contain. An apology grant is one brand's promise to one customer, for N uses, inside a window, scoped to the subtotal or the fee. Converting it to points makes it fungible with money the customer earned | Revisit if the remedy set collapses to "take some som off", at which point the entitlement is a worse points ledger |
| Put the remedies in the separate `recovery` module ADR 0013 specified | It would need the settlement cap, the tender ordering, the points reversal and the approval hash — everything that makes the cap correct — reached across a module boundary, to add a case id that nothing yet has a lifecycle for | When a recovery case with its own states, SLA and versioned remedy policy is actually built. The boundary is right for that; it is overhead for three endpoints |
| One `POST /remedies` endpoint with a `type` field | The three take different inputs — a refund needs a cabinet reference, a future discount needs a window and a use count — and a body carrying the union validates none of them properly | Never |
| Grant a percentage discount with no per-use maximum | One console click creating an unbounded liability: 20% off a delivery fee is 2 000 som and 20% off a catering subtotal is 400 000. An approver cannot weigh what has no ceiling | Never |
| Hold (reserve) an entitlement use during checkout | A hold leaks when a cart is abandoned, and the failure it prevents — two carts racing for the last use — is already prevented by the conditional `UPDATE` bounded by `uses_consumed < uses_granted`. The cost is that the loser was quoted a price it cannot have, which ADR 0031 already shapes as `PRICE_CHANGED` | If pricing observes that evaporating discounts are common. That is a pricing-side measurement, not a payments-side guess |

## Consequences

### Positive

- Orders stay immutable. No remedy adjusts an order total; the commercial record
  and the remedy are separate rows, and why money moved is queryable.
- Analytics can tell three different things apart: a refund of goods, a
  delivery-fee reimbursement, and a discount granted against a future order. A
  tenant asking what late delivery cost them last month gets the second, not the
  first three summed.
- Attested money is reportable apart from settled money everywhere, including in
  the totals report, which also carries the unverified subtotal per type.
- The approval threshold aggregates over the order, so repeated small refunds
  cannot walk around it, and an approval is bound by hash to the order, type,
  amount and reason code it approved — it cannot be spent on a different remedy.
- The redemption cap is a database constraint plus a conditional update, so two
  concurrent orders cannot both take the last use of a grant.
- Every remedy, grant and verification is immutable ADR 0027 audit evidence.

### Negative

**A bookkeeping refund can disagree with reality, and it can do so in both
directions.** This is the cost of the decision and it is not mitigated away.

- *Recorded, never performed.* An operator records a refund they did not make.
  The customer is not paid, the books say they were, the tender headroom is
  consumed, and the order looks handled. Nothing inside the platform can detect
  it.
- *Performed, never recorded.* An operator refunds in the cabinet and never opens
  Qoida. There is no row. The settlement statement is short by an amount with
  nothing to explain it, the cumulative cap still shows the full headroom, and a
  second refund can be recorded — and possibly performed — against money that has
  already gone back.

What the attestation / `verification_state` split **does** address:

- It names a claimant distinct from the recorder. `executed_by` and `executed_at`
  are the operator's own claim about the cabinet; `recorded_by` and `recorded_at`
  are Qoida's observation of the claim being made. An investigation can tell the
  two people apart.
- It refuses to record attested money without who moved it, when, and through
  which channel — enforced twice, in `OrderRemedyService.requireAttestation` and
  in `ck_remedy_attestation_is_evidenced`.
- It forces a `provider_reference` on `PROVIDER_CONSOLE`, because only a cabinet
  issues an identifier and without one nothing can ever match a settlement line.
- It keeps the assertion out of the settled figure by constraint, so the gap
  cannot be flattened by a careless `SUM`.
- It makes the exposure a queryable, aging worklist —
  `GET /remedies/unverified`, oldest first, over the partial index
  `ix_remedy_unverified_attestation` — rather than a footnote. Age is the signal:
  an attestation from this morning is ordinary; one from six weeks ago that no
  settlement file matched is a refund that may never have happened.

What it **does not** address, stated plainly:

- **None of it is evidence.** `executed_by` is a string the recorder typed.
  `provider_reference` is validated for presence and against no provider. The
  platform is recording a better-structured assertion, not a verified fact.
- **Verification is a second human assertion.**
  `POST /remedies/{remedyId}/verification` takes a free-text `source` and is
  guarded by `refund.execute` rather than `refund.request`, so it is a different
  person under a different capability — a real segregation-of-duties control, and
  still two people rather than a machine reading a statement.
- **The second failure mode is structurally invisible.** A console refund that was
  never recorded produces no row, and a worklist of rows cannot list a row that
  does not exist. Every control in this design operates on records that were
  created; none of them looks at the provider's side of the ledger.
- **`CASH_DRAWER` and `BANK_TRANSFER` attestations have nothing to reconcile
  against at all.** Neither produces a line in a provider settlement file, so even
  a working import would leave them permanently `UNVERIFIED`.

**What would close it, and is not built:** a settlement import, per legal entity,
per provider, per period — ADR 0013's `payments.settlement_imports` and
`payments.settlement_lines`, neither of which exists. It has to match in *both*
directions to be a control rather than a comfort:

1. attested remedy → settlement line, which discharges `UNVERIFIED` to `CONFIRMED`
   or raises `DISPUTED`, and needs a join key the remedy row does not currently
   carry beyond `provider_reference`;
2. unmatched settlement reversal → no remedy row, which is the only way the
   platform can ever see the refund nobody recorded.

Until both run on a schedule, the unverified worklist is a list of things one
person said and, sometimes, another person agreed with.

**The future discount is a liability the platform creates and must honour later,
and today nothing reports it.**

- The data exists. `payments.remedy_entitlements` holds every grant with
  `uses_granted`, `uses_consumed`, a per-use ceiling and an `expires_at`, and the
  bound an approver weighed — uses × per-use maximum — is reconstructible per row.
- **No query aggregates it.** `JdbcRemedyStore.totalsByType` reads
  `payments.order_remedies` only, where a `FUTURE_DISCOUNT` row carries
  `amount_minor = 0` by constraint. So `GET /reports/remedies` shows future
  discounts as a count with zero money against them. That is correct and it is not
  a liability figure. Nothing else in the tree sums outstanding exposure — not
  payments, not ADR 0043 reporting.
- **Worse, a granted entitlement cannot currently be spent.**
  `RemedyEntitlementPort` has no production caller: ADR 0018 pricing neither calls
  `available` nor `redeem`, and only `RefundAndRemedyTests` exercises them. The
  liability today is therefore a promise recorded against a customer's account
  that no checkout can honour — the worse half of the failure, because the
  customer was told they had it.
- `RemedyEntitlementService.expireLapsed` has no scheduled caller, so `status`
  stays `ACTIVE` past `expires_at`. Nothing over-spends, because `redeem` and
  `spendableEntitlements` both check the window independently; the column drifts,
  and a liability report written against `status = 'ACTIVE'` would over-count.

Other costs:

- **A remedy names no provider transaction.** `payments.order_remedies` has no
  `payment_intent_id` and no `payment_transaction_id`. `provider_reference` is the
  only join key to a settlement line, and it exists only on `PROVIDER_CONSOLE`.
- **There is no `evidence_reference`.** ADR 0013 specified an ADR 0029 protected
  reference to the console export or screen capture; V0052 carries none, so the
  cabinet evidence lives outside the platform.
- **Nothing publishes an external event.** The remedies raise audit facts and
  write no outbox row, so no external consumer learns that an order was refunded.
  One in-process exception since 2026-08-30: `recordMoneyRemedy` publishes
  `ordering.api.PaymentRefunded` so ordering's `payment_status_projection`
  follows the refund — synchronous, never via Kafka, and it drives no order
  state. Reporting would have to read the table.
- **`reason` is operator free text** — stored on the row and passed to
  `AuditFact.because`. Nothing stops an operator typing a customer's name or phone
  number into it, and ADR 0029 does not cover it. It reaches no event, log, trace
  or metric today only because there is no event; a future emitter must not carry
  it. Recorded here so the next person to add one knows.
- **The delivery-fee ceiling is not enforced in practice.** With no
  `DeliveryFeeBasisPort` wired, every reimbursement is bounded by the settled
  tenders and not by the fee actually charged, and records
  `delivery_fee_basis_minor = NULL` to say so — which is at least findable by
  query later.

### Accepted trade-offs

- **One person below the threshold is enough.** A holder of `refund.request` can
  create a record the platform will believe, with no second pair of eyes, for any
  amount under `qoida.payments.remedy-approval-threshold-minor` — one number,
  defaulting to 200 000, for the whole platform. The threshold is the control and
  it is coarse.
- **`refund.approve` is declared and guards nothing here, deliberately.**
  `ApprovalService.decide` takes a request id and nothing else, so a
  payments-local approve endpoint would let a refund approver decide a loyalty
  adjustment by pasting a different id. The approvals console belongs to the audit
  module, which is the only place that can scope a decision to the action code it
  was raised under. Until it exists, an over-threshold remedy returns `PENDING`,
  writes nothing, and the maker resubmits the identical request once it is
  decided.
- **Nothing can move a row off the unverified worklist automatically in this
  build.** `recordVerification` exists so a finance user can close a row by hand
  against a statement they are looking at. A worklist that only grows is a control
  people learn to ignore, so the manual discharge ships before the import.
- **`payments.order_remedies` is `SELECT, INSERT, UPDATE` and never `DELETE`.** A
  remedy recorded in error is corrected by a `DISPUTED` verification and a
  compensating record, not by removal.

## Specification

### Physical model

Created by
[V0052](../../../src/main/resources/db/migration/V0052__record_remedies_horecaos_did_not_perform.sql).
Every table is tenant-owned, every unique and foreign key includes `tenant_id`,
and each carries a `GRANT` for `qoida_application`.

```text
payments.order_remedies
  id, tenant_id, brand_id, order_id
  remedy_type (ORDER_REFUND|DELIVERY_FEE_REIMBURSEMENT|FUTURE_DISCOUNT)
  reason_code, reason, currency
  amount_minor                       -- whole som (ADR 0018); what the customer got back
  attested_money_minor               -- asserted by a person, unobserved by the platform
  platform_settled_minor             -- performed here, provable from our own ledger
  settlement_basis (OPERATOR_ATTESTED|PLATFORM_SETTLED|MIXED|NOT_MONEY)
  execution_channel null (PROVIDER_CONSOLE|CASH_DRAWER|BANK_TRANSFER)
  provider_reference null, executed_by null, executed_at null    -- the claim
  verification_state (UNVERIFIED|CONFIRMED|DISPUTED)
  verification_source null, verified_at null
  delivery_fee_basis_minor null      -- null is "no ceiling established", never zero
  recorded_by, recorded_at, approval_request_id null              -- the observation
  idempotency_key, version, timestamps
  unique(tenant_id, id), unique(tenant_id, idempotency_key)

payments.remedy_entitlements         -- one per FUTURE_DISCOUNT remedy
  id, tenant_id, brand_id, remedy_id, customer_account_id
  applies_to (SUBTOTAL|DELIVERY_FEE|BOTH), benefit_kind (PERCENT|FIXED_AMOUNT)
  percent_basis_points null, amount_minor null, maximum_minor null, currency
  uses_granted, uses_consumed, starts_at, expires_at
  status (ACTIVE|EXHAUSTED|EXPIRED|REVOKED), version, timestamps
  unique(tenant_id, remedy_id)       -- two grants behind one decision would be
                                     -- twice the liability an approver weighed

payments.entitlement_redemptions     -- append-only: SELECT, INSERT only
  id, tenant_id, entitlement_id, order_id
  subtotal_discount_minor, delivery_discount_minor, currency, redeemed_at
  unique(tenant_id, entitlement_id, order_id)   -- one use is one order
```

The invariants that carry the decision are constraints, not conventions:

| Constraint | What it prevents |
|---|---|
| `ck_remedy_amount_is_split` | A refund figure that has quietly merged asserted money with settled money |
| `ck_remedy_basis_matches_split` | A `settlement_basis` that disagrees with the two columns beneath it |
| `ck_remedy_future_discount_is_not_money` | A grant being summed into a refund total by a query that forgot to filter |
| `ck_remedy_attestation_is_evidenced` | An assertion in the ledger with nobody attached to it |
| `ck_remedy_console_reference` | A cabinet refund that can never be matched to a settlement line |
| `ck_remedy_only_attestations_are_verified` | "Verifying" a points reversal — our own ledger against itself |
| `ck_entitlement_percent_is_capped` | An unbounded liability created by one console click |
| `ck_entitlement_uses_within_grant` | Two concurrent orders both passing a check made in application code |
| `uq_entitlement_redemption_order` | A retried order placement counting as a second use |

`ix_remedy_unverified_attestation` is partial — `WHERE attested_money_minor > 0
AND verification_state = 'UNVERIFIED'` — because the worklist is precisely the
small set of rows that assert money moved with nothing backing them.

### Operations APIs

Under `/api/v1/operations/tenants/{tenantId}`, ADR 0031 conventions, ADR 0025
capabilities at `TENANT` scope. Every mutation carries an idempotency key.

```text
POST /orders/{orderId}/refunds                      refund.request
POST /orders/{orderId}/delivery-fee-reimbursements  refund.request
POST /orders/{orderId}/future-discounts             refund.request
POST /remedies/{remedyId}/verification              refund.execute
GET  /orders/{orderId}/remedies                     payment.read
GET  /remedies/unverified                           payment.read
GET  /reports/remedies?from=&to=                    payment.read
```

The capability split follows what the acts are. `refund.request` creates a
remedy — a support power, where whether it takes effect at once or waits for a
second pair of eyes is the ADR 0027 threshold's business. `refund.execute` guards
the one remaining executive act in a design where nothing executes a payment:
declaring that money Qoida asserted had moved really did. That belongs to finance
and not to the person who made the assertion.

Every mutation's response reports `approvalStatus`, and on a money remedy reports
`attestedMoney` and `platformSettledMoney` separately, so a console can say on
screen what part of this the platform is taking on trust rather than showing a
refund that looks executed.

### Ordering and the cap

`OrderRemedyService.recordMoneyRemedy` runs in one transaction, in this order,
and the order matters:

1. resolve the order **constrained on the tenant the caller was authorised
   against** — `OrderDirectory.summary(tenantId, orderId)` answers empty for
   another tenant's order, which is the same answer as "does not exist";
2. check the currency against the order's;
3. on a reimbursement, check the narrower delivery-fee ceiling;
4. weigh `amount + everything this order has already given back` against the
   approval threshold, **before** anything is written — a control that looked
   only at the command in front of it is walked around by anyone who can count;
5. call `OrderSettlementService.refund`, which claims tender headroom in the
   statement, reverses points through the balance tenders first, and returns what
   the money tenders absorbed;
6. **then** demand attestation, because what evidence is needed depends on how the
   tenders absorbed the amount and only the settlement service knows that. A
   refusal here rolls the whole transaction back, tender headroom included;
7. insert the remedy and raise the audit fact.

A refund that came back entirely as loyalty points needs no cabinet reference: it
was performed here, in this transaction, against the lots that were spent. Asking
for one produces a field somebody fills in with a plausible string.

### Events, audit, and PII

No event and no outbox row. Three ADR 0027 facts —
`payments.remedy.record`, `payments.remedy.future-discount`,
`payments.remedy.verify` — each at `ResourceScope.brand`, targeting
`payments.order_remedy`, carrying the amounts, the basis, the reason code and the
approval id under which it was allowed. No customer identifier and no PII in any
`changed` map. `reason` reaches the audit fact and nothing else; see the note
under [Negative](#negative) before adding an emitter.

### Testing

`RefundAndRemedyTests` runs twenty-two tests against PostgreSQL with a fixed
clock, covering the split, the born-unverified attestation, the refusals for a
missing claimant and a missing cabinet reference, the points-only refund that is
not asked for evidence it cannot have, the worklist and its discharge, the
cumulative cap taken from the settlement service rather than reimplemented,
repeated small refunds that cannot walk around the threshold, an approval that
cannot be reused for another remedy type, the fee ceiling and the unchecked
ceiling recorded as null, another tenant's order answering not-found rather than
forbidden, and the entitlement's per-order idempotency, exhaustion, scope, per-use
maximum and mandatory cap.

## Rollout and rollback

V0052 is applied and the schema is append-only, so rollback is not a migration.
Withdrawing this decision means ceasing to record remedies and writing a further
ADR that supersedes this one; the rows already written stay, because they are the
record of money that moved.

The one live configuration is
`qoida.payments.remedy-approval-threshold-minor` (default 200 000). Lowering it
sends more remedies to approval and blocks nothing; raising it widens what one
person can do alone.

Turning any of the four unbuilt seams on is additive and needs no change here: a
real `DeliveryFeeBasisPort` bean replaces the stand-in through
`@ConditionalOnMissingBean`; a pricing caller for `RemedyEntitlementPort` makes
grants spendable; a scheduled `expireLapsed` corrects the status column; a
settlement import calls the existing `recordVerification`.

## Implementation checklist

- [x] Add `payments.order_remedies`, `payments.remedy_entitlements` and `payments.entitlement_redemptions` with the split, attestation, verification, cap and per-order-redemption invariants as constraints, and grants for `qoida_application` (V0052).
- [x] Record a refund and a delivery-fee reimbursement, full or partial, splitting the amount through `OrderSettlementService.refund` and demanding attestation only for money the platform did not move (`OrderRemedyService`).
- [x] Grant a future discount bounded by uses, window and a per-use maximum, approved against its exposure rather than its zero cash cost (`OrderRemedyService.grantFutureDiscount`).
- [x] Expose the three remedies, the manual verification, the unverified worklist and the totals report under `refund.request`, `refund.execute` and `payment.read` (`OperationsRemedyController`).
- [x] Raise ADR 0027 facts for every record, grant and verification, with no PII in the change map.
- [x] Cover the invariants against PostgreSQL (`RefundAndRemedyTests`, twenty-two tests).
- [ ] Give `OrderSettlementService.plan` a production caller, so an order has a settlement and a refund can be recorded against a real order at all. **This is the blocker**: today every money remedy fails at `require` with "The order has no settlement".
- [ ] Wire a real `DeliveryFeeBasisPort` from ordering, so a reimbursement is bounded by the fee actually charged and stops recording a null basis.
- [ ] Call `RemedyEntitlementPort` from ADR 0018 pricing — `available` at quote, `redeem` at placement — so a granted future discount can be spent.
- [ ] Schedule `RemedyEntitlementService.expireLapsed`, so `status` stops drifting past `expires_at`.
- [ ] Report the outstanding future-discount liability: exposure remaining per brand and per expiry window, on the ADR 0043 metric layer, once finance answers the valuation basis.
- [ ] Build the settlement import and the two-directional daily reconciliation, so an attestation can be discharged by a file and an unrecorded console refund becomes visible.
- [ ] Add `evidence_reference` as an ADR 0029 protected reference once legal answers what evidence and what retention.
- [ ] Write the refund runbook under `docs/runbooks/`, naming what an operator does in each cabinet before they open Qoida.

## Exit criteria

- A support user records a partial refund against a real order that has a
  settlement; the tender headroom drops by exactly that amount, a second refund
  for more than what is left is refused, and the response shows what part the
  platform settled and what part it is taking on trust.
- The same user's ten-use future-discount grant appears in the customer's next
  quote, takes no more than its per-use maximum, and a retried placement of that
  order spends one use rather than two.
- `GET /remedies/unverified` lists exactly the attested rows older than the
  settling period, and a finance user with `refund.execute` — and not the person
  who recorded it — moves one to `CONFIRMED` against a named source.
- `GET /reports/remedies` returns refunds, reimbursements and grants as separate
  lines, with attested money reported apart from settled money on each, and the
  grants carrying no money at all.
- A finance user can state the platform's outstanding future-discount liability
  from a report rather than from a query somebody wrote that morning.
- A console refund performed and never recorded is surfaced by reconciliation
  within one settlement cycle. **Nothing in this build meets this criterion**, and
  it is the one that closes the gap this decision opens.

## References

- [ADR 0013: Payment, refund, and service-recovery compensation](../partial/0013-payment-refund-and-service-recovery-compensation.md) — superseded here on refunds and remedies; current on the provider port, the payment state machine, uncertainty resolution and the som/tiyin boundary
- [ADR 0046: Loyalty points and split tender](../partial/0046-loyalty-points-and-split-tender.md) — the settlement, the tenders, and the cap this decision borrows rather than reimplements
- [ADR 0018: Deterministic pricing, promotions, taxes, and quotes](../partial/0018-deterministic-pricing-promotions-taxes-and-quotes.md) — owns what a future discount is worth against a cart
- [ADR 0027: Audit evidence and approval model](../partial/0027-audit-evidence-and-approval-model.md) — the approval threshold and the immutable facts
- [ADR 0025: Fine-grained authorization and the capability model](../built/0025-fine-grained-authorization-and-capability-model.md) — `refund.request`, `refund.execute`, `payment.read`
- [ADR 0029: PII protection, envelope encryption, and key rotation](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md) — why `reason` is called out above
- [ADR 0031: HTTP API conventions](../built/0031-http-api-conventions.md)
- [ADR 0043: Reporting, analytics, and the metric layer](../partial/0043-reporting-analytics-and-the-metric-layer.md) — where the liability report belongs
- [ADR 0000: ADR process and status model](../meta/0000-adr-process-and-status-model.md)
- [V0052 — record remedies HorecaOS did not perform](../../../src/main/resources/db/migration/V0052__record_remedies_horecaos_did_not_perform.sql), whose header comment was the only written trace of this decision until now
- [V0048 — record how much of a tender was refunded](../../../src/main/resources/db/migration/V0048__record_how_much_of_a_tender_was_refunded.sql)
