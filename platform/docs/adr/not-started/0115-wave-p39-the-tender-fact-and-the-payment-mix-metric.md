# ADR 0115: The tender fact and the payment-mix metric

- Decision status: Proposed
- Implementation status: Not started — this record is written before V0304
  and the code it describes land in the same wave (`wave138-p39`), per the
  platform's own ADR discipline; both should move to Built together once a
  human has reviewed the shape below rather than only the code.
- Date proposed: 2026-09-14
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction
  of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0043, ADR 0038, ADR 0046, ADR 0023
- Supersedes / Superseded by: —
- Open inputs: finance has not signed `payment_mix.amount.v1` — until then the
  API marks it provisional, matching every other metric this ADR 0043
  extension registers (finance); whether a provider-settled tender (CLICK,
  Payme) should be reported net of the provider's own commission rather than
  at the tenant's registry face value is an open question this record states
  but does not answer — see the metric's own `openQuestion` in
  `MetricRegistry` (platform owner, once ADR 0013's settlement-import work
  exists to supply the commission figure)

## Context

The one figure a restaurant manager uses for cash-collection control — what
share of today's takings came in as cash, card, or online — is absent from
every report. It is not a screen gap: `reporting.fact_order_tender` was
deliberately not built. V0031's own header names it explicitly ("What this
migration deliberately does not build ... `fact_order_tender` ... A fact
table whose producer does not exist is not a head start") and ADR 0043's
"What was not built" section repeats the reason: "a primary-tender column
with no tender fact behind it is the 'convenience mistaken for the whole
settlement' this ADR predicts, so neither was added."

The source data already exists, one layer down, where ADR 0023 forbids a
report from reading it directly. `payments.order_settlements` and
`payments.tenders` (V0042, ADR 0046) are populated in production by
`CheckoutSettlementPlanner`: every order settles through an ordered set of one
or more tenders, each naming a payment-method registry row
(`payments.payment_methods`, ADR 0038, seeded per tenant by wave P33) and
carrying its own amount and status. V0048 adds `refunded_minor` so a tender
remembers how much of itself has already been given back, closing the bug
where a partial refund could be claimed twice. `ordering.orders` itself
carries no payment method at all — ADR 0046 replaced the single-method column
this ADR's own physical model once sketched with the settlement/tender pair,
because a meal paid half in loyalty points and half in cash has no single
answer.

`payments.payment_methods` rows are created lazily, at first checkout, rather
than seeded ahead of time. Building a payment-mix fact before that registry
was reliably populated across a tenant's history would key a mix on whatever
happened to be tendered rather than on the registry the settings screen now
lists (wave P33, `V0244`–`V0245`) — which is why the operations gap map
sequences this wave after P33 rather than letting it run in parallel.

The reports filter bar already renders a locked "Тип оплаты" chip naming
exactly this blocker (`reports-filter-bar.html`, added ahead of this wave)
so a manager who goes looking for the filter is told why it is missing rather
than concluding it does not exist. Unlocking it is this wave's other half.

## Decision

**Build `reporting.fact_order_tender` as a projection over
`payments.order_settlements`/`payments.tenders`, one row per tender on its
ORDER's business date, storing the tender's NET amount rather than a second
movement ledger.** Concretely:

- `V0304` creates the table at the grain ADR 0043's own physical model names
  for it — `order_id, business_date, location_id, legal_entity_id,
  tender_sequence, payment_method_code, settles_from_balance, amount_som,
  tender_status`, plus `tenant_id`, `boundary_version` and
  `metric_calculation_version` matching every sibling fact. `amount_som` is
  `payments.tenders.amount_minor - refunded_minor` (V0048), computed once at
  close time. A fully refunded tender (`tender_status = REVERSED`, which
  `payments.tenders` only reaches once the whole amount has been given back)
  reads zero in place rather than needing a second row — see "Alternatives
  considered" for why this diverges from `fact_refund`'s own grain.
- The producer runs inside `DayCloseService.close`'s existing
  `@Transactional` method, immediately after the order-fact write, reading
  `payments.tenders` the same way `readSourceRefunds` already reads
  `payments.payment_transactions` — the one exception ADR 0043's own header
  states: "the close job is the one thing that reads `ordering` and
  `payments`, and it writes only here." A tender-fact write failing rolls
  back the whole close, exactly like every other fact this method writes; it
  gains no transaction boundary of its own.
- `Grain.Dimension.PAYMENT_METHOD` and `Grain.DAY_LOCATION_LEGAL_ENTITY_PAYMENT_METHOD`
  are added, the latter because this is money and `MetricDefinition`'s own
  constructor already refuses a `UZS_SOM` metric at a grain that omits
  `LEGAL_ENTITY` (ADR 0038) — a branch trading as two legal entities on the
  same evening must never have its payment mix summed into one figure.
- `payment_mix.amount.v1` is registered in `MetricRegistry`, append-only,
  `Aggregation.DISTRIBUTION` (several rows per slice, like
  `sla_bucket_set.v1`), inclusion rule `SETTLED_OR_REVERSED_TENDERS` — a
  `PLANNED`, `RESERVED`, `RELEASED` or `FAILED` tender never moved money and
  would overstate takings if counted.
- A bespoke `GET .../reporting/payment-mix` answers it, refused from the
  typed `/queries` pipeline by name (the same move `sla_bucket_set.v1` and
  `prep_time.median.v1` already make) because a share-per-method breakdown is
  several rows per slice, not the query's one-value-per-cell shape. One
  grouped SQL read (`JdbcReportingStore.readPaymentMix`) answers both halves
  of the row this wave and `7.3b` need: `overview` folds every branch into
  one row per (legal entity, payment method) — never across two entities —
  and `byLocation` keeps the branch split so `7.3b`'s cash-reconciliation
  half can be answered from the same call once its own wave (`T06`) builds
  the screen.
- The reports filter bar's "Тип оплаты" chip is unlocked in the same wave,
  reading `payments.payment_methods` (ADR 0038's tenant registry, via the
  existing settings API) rather than a fixed enum.

**Note on ADR 0013.** ADR 0013's own implementation status lists "settlement
import and daily reconciliation" as not built — a provider statement matched
line-by-line against what HorecaOS believes it collected. `payment_mix.amount.v1`
is explicitly **not** that reconciliation: it reports what the tenant's own
registry recorded as tendered, at face value, never against what a provider
actually remits after its own commission or fee (stated as the metric's own
`openQuestion`). When ADR 0013's settlement-import work is eventually built,
it should read `reporting.fact_order_tender` as its "what we believe we
collected" side of the comparison rather than re-deriving a second copy from
`payments.tenders` — the same "read the fact, not the module schema" rule
ADR 0023 already states for every other reporting consumer — and a
`revenue.net_of_commission`-shaped `payment_mix.amount.v2` would be the
natural place to fold a provider's commission in once that data exists,
mirroring how `revenue.net_of_commission.v2` is already deferred in ADR 0043
pending ADR 0040.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A refund-of-tender fact table, mirroring `fact_refund`'s own grain (one row per refund movement, on the refund's own date) | `fact_refund` exists because an order refunded partly on Tuesday and again on Friday needs both dates recorded — it answers "what moved, and when." Payment mix answers a different question: "what is currently in the till for this order, by method" — a cash-reconciliation snapshot, not a movement ledger. Netting in place keeps that snapshot a single join-free read; a movement ledger would need every payment-mix reader to replay the whole history of a tender to answer the same question `amount_som` already gives in one column | A report is asked for that needs point-in-time replay — "what did the mix look like as of last Tuesday before Friday's refund landed" — which this shape cannot answer and a movement ledger could |
| Add `payment_method_code` to `agg_branch_day`'s own key, folding payment mix into the existing typed `/queries` pipeline | `agg_branch_day` is already keyed by `channel_code` and `fulfilment_type`; a third key dimension multiplies the row count of every existing report that groups by less than all three, and every stored day would need a recut the day this shipped. A separate fact and its own bespoke endpoint leaves every existing dashboard, aggregate, and stored divergence untouched | Never, on current evidence — the two facts answer genuinely different report shapes (`agg_branch_day` is revenue/order counts; this is a payment-method breakdown that revenue metrics must never be summed against, per ADR 0043's own "revenue sums fact_order, never fact_order_tender" rule) |
| Read `payments.tenders` directly from a report query, computing the mix at request time | Forbidden outright — ADR 0023 says a report never reads a module schema, and ADR 0043 makes that a grant rather than a convention: `horecaos_reporting_read` has no `USAGE` on `payments` at all. Even setting the rule aside, computing at request time means the figure can change between two reads of the same closed day, which is the exact disagreement the metric registry exists to prevent | Never |
| Count a tender at its planned amount regardless of status, rather than filtering by `tender_status` at the metric layer | Simpler to write, wrong to read: a `FAILED` checkout attempt would inflate takings by money nobody actually paid. Filtering at the metric layer rather than at fact-write time keeps the fact itself a complete capture (matching `fact_order`'s own "every terminal status, metrics decide inclusion" discipline), so a future inclusion-rule change is a code review rather than a migration | Never — this is the same "facts capture everything, metrics decide inclusion" rule the rest of ADR 0043 already follows |

## Consequences

### Positive

- The one number a restaurant uses for cash-collection control exists,
  answerable from the same provenance-carrying, versioned-metric discipline
  every other figure in Reports already follows — no second, informal
  computation of "how much cash did we take today."
- `7.3b`'s branch-level cash reconciliation is unblocked at the data layer:
  `byLocation` already carries the per-branch split its own wave needs,
  without that wave having to touch `payments.*` or invent its own
  aggregation.
- The reports filter bar's locked chip becomes a real filter, closing a gap a
  manager could see but not use.

### Negative

- A second read path into `payments` alongside `readSourceRefunds` — one more
  join a future refactor of the settlement/tender shape has to keep in sync
  with the reporting module, and one more place `ADR 0023`'s boundary is an
  exception rather than a rule.
- `amount_som` being net-in-place means a manager cannot see, from this fact
  alone, how much of a tender was ever refunded — only what remains. That
  history already exists on `payments.tenders.refunded_minor` and
  `payments.payment_transactions`, one layer down, but this fact does not
  re-expose it, which is a real loss for anyone who eventually wants a
  refund-by-payment-method report rather than a mix-by-payment-method one.
- `payment_mix.amount.v1` reports the tenant's own registry face value, not a
  provider-reconciled figure. A tenant whose CLICK or Payme settlement runs
  differently from what HorecaOS recorded will not see that difference here
  — see the "Note on ADR 0013" above.
- One more append-only entry in `MetricRegistry` that finance has not yet
  signed, joining every other metric this build ships provisional.

### Accepted trade-offs

- Cross-surface consistency over flexibility, the same trade ADR 0043 already
  accepts: this wave cannot invent a new payment-mix definition later without
  a new version, and that is the whole point of the registry.
- A snapshot over a ledger. `amount_som`'s net-in-place shape answers "what is
  in the till" cheaply and correctly, and deliberately does not answer "what
  moved and when" — that question already has an owner one layer down.

## Specification

See `V0304__create_reporting_fact_order_tender.sql` for the physical model
(table, indexes, the widened `reporting.ensure_fact_partition` allowlist, and
grants), `ReportingFacts.TenderFact` for the in-process shape,
`JdbcReportingStore.readSourceTenders`/`insertTenderFact`/`readPaymentMix` for
the read/write SQL, `DayCloseService.derive`/`close` for the producer, and
`MetricRegistry`'s `payment_mix.amount.v1` entry for the metric's full
definition, inclusion rule, and stated open question. `ReportingController`'s
`GET .../reporting/payment-mix` is documented inline against `ReportQueryService.paymentMix`.

No new capability: `Capability.REPORTING_READ` at `TENANT` scope, already
declared on every sibling `ReportingController` endpoint, covers the new one
too — the fact producer runs inside `DayCloseService` as the close job's own
system principal and needs none.

## Rollout and rollback

Ships behind nothing — the fact producer runs unconditionally inside the next
close, and the endpoint and frontend card ship in the same wave. Rollback is
the same shape ADR 0043 already states for every fact here: the table is
derived and rebuildable from `payments.tenders`, so dropping it and rebuilding
from a future close is cheap. Rolling back the migration itself would need a
new forward migration to drop the table (never an edit to `V0304`), which is
unnecessary unless the whole feature is reverted.

## Implementation checklist

- [ ] `V0304` creates `reporting.fact_order_tender`, widens
      `reporting.ensure_fact_partition`'s allowlist, and grants both
      `horecaos_application` and `horecaos_reporting_read`.
- [ ] `DayCloseService` writes a tender fact per order inside `close`'s
      existing transaction, next to the order-fact write.
- [ ] `payment_mix.amount.v1` is registered in `MetricRegistry` and answered
      by `GET .../reporting/payment-mix`, refused from `/queries` by name.
- [ ] The reports filter bar's payment-type chip reads
      `payments.payment_methods` and is no longer locked.
- [ ] The business-overview page's payment card reads the new endpoint in
      place of its "not built" placeholder.
- [ ] Finance signs `payment_mix.amount.v1` (open input above; tracked the
      same way every other unsigned metric already is, not blocking this
      wave).

## Exit criteria

A split-tender order's `fact_order_tender` rows sum to the order total; a
refund against one of those tenders nets the corresponding row down in place
without a second row appearing; `GET .../reporting/payment-mix` answers both
an `overview` (folded across branches, never across legal entities) and a
`byLocation` split from one call; and the reports filter bar's payment-type
chip is a working filter rather than a locked notice.

## References

- [ADR 0043: Reporting, analytics, and the metric layer](../partial/0043-reporting-analytics-and-the-metric-layer.md)
- [ADR 0038: Legal entities, fiscal receipts, and product classification](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md)
- [ADR 0046: Loyalty points and split tender](../partial/0046-loyalty-points-and-split-tender.md)
- [ADR 0023: Production operating model, observability, security, and recovery](../partial/0023-production-operating-model-observability-security-and-recovery.md)
- [ADR 0013: Payment, refund, and service-recovery compensation](../partial/0013-payment-refund-and-service-recovery-compensation.md)
- [docs/operations-gap-map.md](../../operations-gap-map.md), rows `7.1c`, `7.3b`
