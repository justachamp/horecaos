# ADR 0043: Reporting, analytics, and the metric layer

- Decision status: Accepted
- Implementation status: Partial — V0031 creates `reporting.metric_definitions`, `fact_order`, `fact_order_line`, `fact_refund`, `agg_branch_day`, `agg_sla_bucket_day`, `close_runs`, `aggregate_divergences` and `business_day_policies` with monthly partitions, and grants the read-only `horecaos_reporting_read` role; `MetricRegistry` with `MetricDefinitionSynchronizer`'s startup refusal, `BusinessDayService`, `DayCloseService` with the settle recut and divergence alert, `DayAggregator`, `ReportQueryService`/`ReportingController` (the typed `GET .../reporting/queries` with provenance and the ADR 0038 and boundary-regime refusals) and `MetricSigningService`/`MetricSignatureController` are built and tested. `DayCloseScheduler` (wave 6) is now `DayCloseService`'s production caller: a five-minute heartbeat closes each active tenant's business day once it is over plus the close delay, and a fifteen-minute one recuts settled days, both behind a durable cross-replica claim (`reporting.day_close_claims`, V0102) — so fact rows are written and the count queries answer in a deployed system, proven by `DayCloseSchedulerTests`. No metric signature has been recorded, so every metric would report provisional. Also not built: the `analytics.events` topic and any behavioural emission, type-2 dimensions, inbox projections for near-real-time counters, the `report.export`/`customer.pii.export`/`forecast.manage` capabilities and their surfaces, asynchronous export, ABC/XYZ/RFM and forecasting, and tender and delivery facts. `fact_refund`'s source is `payments.payment_transactions` refund rows, which no code writes.
- Date proposed: 2026-08-21
- Date decided: 2026-08-21
- Deciders: Ayubkhon Abbosov (platform architecture), finance (metric semantics), product (dashboard scope)
- Depends on: ADR 0004, ADR 0023, ADR 0025, ADR 0027, ADR 0029, ADR 0032, ADR 0033, ADR 0034, ADR 0038, ADR 0039, ADR 0046
- Supersedes / Superseded by: —
- Open inputs: Signed semantics for revenue, average check, and the treatment of cancelled, refunded, and aggregator-commissioned orders (finance) — the registry holding them is decided here and ships version 1 as provisional; lawful basis and retention for behavioural telemetry (legal), inherited from ADR 0029 and running on the provisional default below; whether ADR 0034 phase one has a PostgreSQL streaming replica (operations) — the query path works either way, with a smaller budget if it does not

## Context

Reporting is the largest single area of the competitor's product: eight dashboard
tabs, fourteen named reports, ABC/XYZ classification, RFM segmentation, cohort and
funnel analysis, and per-product demand forecasting. Qoida has no analytics
decision at all.

What exists is a governance requirement without an architecture. ADR 0023 says
reporting "uses read models/replicas or warehouse export" and cannot write to
module schemas, and requires every retained output to declare source, calculation
version, timezone, freshness, tenant filter, and reconciliation. It never says
where the numbers live or who computes them.

Delever answers with a separate columnar store — its forecasting module is named
after ClickHouse — plus an embedded BI workspace. Qoida is PostgreSQL only, on a
self-operated colocated server in ADR 0034 phase one already carrying PostgreSQL,
Kafka, Keycloak, S3-compatible object storage, OpenBao, and eventually Valkey. A
seventh stateful dependency is not a neutral act.

Three things force the decision now, even though the build is post-pilot:

- **Behavioural events cannot be backfilled.** The funnel
  (Входы → Зарегистрировано → Добавляет в корзину → Заказано) and the
  cart-abandonment trigger need client-side telemetry. Emitting it is cheap;
  deciding to emit it in eighteen months means the funnel starts empty then.
- **Two surfaces computing a metric separately become two answers.** A dashboard
  tile and a finance CSV that disagree about average check destroy trust in both,
  and no correction recovers it, because the merchant now checks every number by
  hand.
- **The business day here is not the calendar day.** Delever's operating window
  defaults to 09:00→09:00. A restaurant that closes at 02:00 and sees those orders
  on the next date concludes the report is broken. Uzbekistan is UTC+5 with no
  daylight saving, which removes one class of bug, but the boundary still has to
  be stored rather than assumed by a hundred queries.

The `reporting` module and schema exist and hold one projection,
`reporting.tenant_summaries`, maintained from `tenancy.events` through the ADR
0005 inbox path. That mechanism is what this ADR extends; nothing else in the
reporting area is built.

## Decision

**Analytics is a star-shaped derived model inside the existing `reporting` schema
of the primary PostgreSQL database, fed by domain events and a scheduled close
job, queried only through a code-owned metric registry.** No columnar store, no
BI tool, no SQL from any client.

- **Facts and dimensions, not live joins over module tables.** A report never
  reads a module schema, and the analytics read path uses a database role with
  `SELECT` on `reporting` and nothing else, so ADR 0023's rule becomes a grant
  rather than a convention.
- **Two write paths.** Event-driven projections keep near-real-time counters
  current through the ADR 0005 inbox. A close job builds day-grain facts after
  each business day ends, then re-derives that day after a settle window so a late
  refund does not leave Tuesday wrong forever.
- **A metric registry is the only definition of a number.** Each metric declares a
  stable id, version, grain, source fact, inclusion rule, currency and rounding
  rule, and unit, in code. Every tile, report column, export, and API response
  names a metric id and composes no aggregate of its own, so `average_check.v1`
  means one thing on every screen and in every month. A definition change is a new
  version; the old stays queryable while facts computed under it are retained, so
  a dashboard someone screenshotted last quarter still reproduces.
- **`business_date` is stored on every fact**, computed at write time from a
  tenant-scoped `reporting.business_day_start` policy resolved through ADR 0030,
  default `00:00` in the tenant timezone, with the event instant beside it.
  Changing the boundary needs an ADR 0027 approval and a full recut, and until the
  recut finishes the API refuses a range spanning two boundary regimes rather than
  silently mixing them.
- **SLA buckets are platform-fixed and versioned, not tenant-configurable.**
  `sla_bucket_set.v1` is Delever's six: ≤30, 30–35, 35–40, 40–50, 50–60, >60
  minutes. Raw elapsed seconds are stored on the fact so a `v2` set can be cut
  retroactively — which is exactly why the buckets are not a setting. A
  tenant-editable bucket rewrites the meaning of every chart already drawn,
  including last quarter's, and nothing records that it happened.
- **Behavioural telemetry ships from day one, on its own topic, pseudonymous.**
  `analytics.events` carries `session_started`, `customer_registered`,
  `cart_item_added`, `checkout_started`, `order_placed`, keyed by session, with
  short Kafka retention because `fact_behaviour` is the durable copy. The subject
  is the ADR 0029 keyed hash of the customer account id — never a phone number,
  never a name — so no `PERSONAL` field exists and ADR 0032's structural PII check
  passes without an exception. They are governed by ADR 0032 but are not domain
  events: no price, entitlement, or state transition may ever read one.
  Provisional retention is 400 days, a year-over-year comparison plus a month of
  margin, and it cannot reach production unconfirmed.
- **Exports are a PII egress path and are treated as one:** asynchronous,
  capability-gated, audited, quota-bounded, delivered as a short-lived presigned
  object-storage URL. Never a synchronous streaming response.
- **Live operational counters are not analytics.** The call-centre wall board
  reads operational state directly and belongs to ADR 0045; routing it through a
  day-grain fact would make it both stale and expensive.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A separate columnar store (ClickHouse) from the start, as Delever runs | Correct at Delever's volume, wrong at Qoida's phase-one operating capacity: a seventh self-operated stateful dependency with its own HA, backup, restore rehearsal, and schema migrations, plus a second copy of every fact that can silently diverge from PostgreSQL | Measured, not guessed: p95 of the standard report set exceeds 3 seconds on production-shaped data, `fact_order_line` passes roughly 10^8 rows, or the close job stops finishing inside its window. The facts are shaped so that move is a copy, not a redesign |
| Query the module tables live, with or without materialized views over them | The per-day × per-branch × per-channel matrix is a six-way join over tables indexed for point writes, contending with checkout. Materialized views make it worse: a refresh recomputes 400 days to add one, `REFRESH MATERIALIZED VIEW` takes an `ACCESS EXCLUSIVE` lock, and the `CONCURRENTLY` form needs a unique index and roughly doubles write IO — all on the primary, during service | Never for the dashboard workload. A materialized view stays fine for a small reference cut that is cheap to recompute whole |
| A physical read replica as the whole answer | Moves the CPU but not the shape: OLAP over a normalized OLTP schema is slow wherever it runs, and long analytic queries on a streaming replica are either cancelled by recovery conflicts or force `hot_standby_feedback`, which pushes bloat back onto the primary the replica was meant to protect | It is a complement, not a substitute. When ADR 0034 provides one, the read role points at it and the star schema is unchanged |
| Embed a BI workspace (Metabase, Superset, DataLens) for tenants, as Delever does | Ships a hundred reports free and puts tenant isolation inside a BI tool's row-level permissions instead of ADR 0025 capability grants — one misconfigured question and a tenant reads another tenant's revenue. It also makes every metric definable twice, which is the failure this ADR exists to prevent | Tenant-authored ad-hoc analysis becomes a paid feature, at which point it runs against a per-tenant materialised subset with its own credentials |
| Emit behavioural events only once the funnel dashboard is scheduled | The cheapest item on this list today and unrecoverable later: history cannot be backfilled, so the funnel, cohort retention, and abandonment triggers would all start at zero on the day someone finally asks | Never. This is the one thing deliberately built ahead of its consumer |

## Physical model

Facts carry `tenant_id`, are partitioned monthly by `business_date`, and follow
the partition-upkeep pattern already used for `audit.audit_events`. Dimensions —
`dim_date`, `dim_location`, `dim_channel`, `dim_product`, `dim_staff`,
`dim_legal_entity` — are slowly-changing type 2 with a name snapshot, so renaming
a branch does not rewrite last year's leaderboard.

```text
reporting.fact_order
  tenant_id, order_id, business_date, occurred_at, location_id, brand_id
  legal_entity_id                          -- ADR 0038; resolved per location and date
  channel_code, fulfilment_type, terminal_status
  primary_payment_method_code, tender_count, is_split_tender
                                           -- primary tender denormalised; ADR 0046
  cancellation_reason_code null, operator_principal_id null
  stock_disposition null, liability_party null      -- ADR 0039 order_outcomes
  courier_principal_id null, customer_subject_hash null, is_first_order
  gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som
  aggregator_commission_som null          -- awaits ADR 0040; null is not zero
  refunded_som, refunded_on_business_date null, line_count, item_count
  seconds_to_confirm, seconds_to_ready, seconds_total, distance_metres null
  metric_calculation_version, source_order_version

reporting.fact_order_line       order_id, line_id, business_date, location_id,
                                variant_id, category_id, quantity, gross_som,
                                discount_som, net_som
reporting.fact_order_tender     order_id, business_date, location_id,
                                legal_entity_id, tender_sequence,
                                payment_method_code, settles_from_balance,
                                amount_som, tender_status
reporting.fact_delivery         order_id, business_date, location_id, is_external,
                                provider_code null, courier_principal_id null,
                                fee_charged_som, provider_billed_som null,
                                variance_som null, reconciliation_status
                                (PENDING|MATCHED|VARIANCE|UNBILLED)
reporting.fact_promotion_redemption
                                business_date, promotion_id, coupon_id null,
                                order_id, customer_subject_hash null,
                                discount_som, order_gross_som
reporting.fact_behaviour        business_date, occurred_at, event_type, session_id,
                                customer_subject_hash null, channel_code,
                                attributes_json          -- INTERNAL class only

reporting.agg_branch_day        business_date, location_id, channel_code,
                                fulfilment_type, order_count, cancelled_count,
                                gross_som, net_som, avg_seconds_total,
                                distinct_customers, new_customers
reporting.agg_sla_bucket_day    business_date, scope_kind (LOCATION|COURIER),
                                scope_id, bucket_set_version, bucket_code,
                                order_count, share_basis_points
reporting.classification_run    kind (ABC|XYZ|RFM), window_from/to,
                                parameters_json, metric_version, status
reporting.classification_result run_id, subject_kind (PRODUCT|CUSTOMER),
                                subject_id, class_code, measures_json
reporting.forecast_run          location_id, model_version, horizon_days,
                                window_start/end_time, holiday_calendar_version
reporting.fact_forecast         run_id, business_date, location_id, variant_id,
                                forecast_quantity, forecast_revenue_som,
                                actual_quantity null, absolute_percentage_error null
reporting.holidays              name, date_from/to, calendar_version
reporting.metric_definitions    metric_id, version, grain, source_fact, aggregation,
                                inclusion_rule_code, currency_rule, rounding_rule,
                                unit, signed_by null, signed_at null, effective_from
reporting.report_exports        requested_by, report_id, filter_json, column_set,
                                metric_versions_json, row_count null, status,
                                object_key null, expires_at, timestamps
```

Money is whole som, matching ADR 0018. `net_revenue_som` is stored, never
recomputed at query time, because two places computing it is how two surfaces come
to disagree. A classification is stored with the run and parameters that produced
it, so a manager who disputes a product being class C is shown its cumulative
Pareto share and the window used. An ABC list with no recorded thresholds is an
opinion.

**Financial reports group by legal entity, not by tenant.** ADR 0038 assigns
fiscal identity per location and business date, so one tenant can trade as two
companies on the same evening. `legal_entity_id` is snapshotted onto the fact from
the assignment that priced the order and is never re-resolved, matching what the
receipt said. A tenant-grouped revenue or tax total for a multi-entity tenant sums
two taxpayers into a number that reconciles to neither filing, which is a figure
someone would otherwise carry into a tax return. Operational cuts — SLA,
leaderboard, forecast — stay grouped by location and are unaffected.

**Payment is a grain, not a column.** ADR 0046 settles one order with an ordered
set of tenders, so the single `payment_method` column this ADR previously carried
has no answer for a meal paid half in points and half in cash: it either drops a
tender or counts the order twice, once under each method. `fact_order_tender`
holds one row per tender, and `payment_method_code` is the ADR 0038 tenant
payment-method registry code the tender references — reporting does not keep a
second enum of payment types, because a second enum is how the dashboard and the
settlement report end up naming the same tender differently. The primary tender —
largest settled amount, lowest `tender_sequence` breaking a tie — is denormalised
onto `fact_order` so the common single-tender cut needs no join, and
`is_split_tender` marks the rows where that column is a simplification rather than
the whole settlement. Revenue metrics sum `fact_order`, never
`fact_order_tender`: tender amounts already sum to the order total, and summing
both is how revenue doubles.

**A cancellation report has to say what the cancellation cost.**
`stock_disposition` and `liability_party` are copied onto the fact from ADR 0039's
`order_outcomes`, with the outcome's own values — `RELEASE`, `RETURN_TO_STOCK`,
`WRITE_OFF`, `NO_EFFECT`, and `TENANT`, `CUSTOMER`, `COURIER_PARTNER`, `PLATFORM`.
Without them every cancellation reads alike, and a reservation released before
production is reported as costing the same as four cooked dishes binned at the
pass. Both are `NULL` where no outcome was recorded, and `NULL` is not
`NO_EFFECT`.

## Metric semantics

These ship as version 1. Finance signs them before any tenant-visible surface uses
them; until then the registry marks them provisional and the API says so.

| Metric | Definition as shipped | Failure it prevents |
|---|---|---|
| `revenue.gross.v1` | Sum of `gross_revenue_som` over `COMPLETED` orders, on their `business_date` | Counting cancelled and rejected orders as revenue, which inflates every branch comparison |
| `revenue.net.v1` | `gross − discount − refunded`, refunds attributed to the refund's business date | Yesterday's closed report silently changing when a refund lands today |
| `average_check.v1` | `revenue.gross.v1 ÷ completed order count`, same filter, same date attribution | Two tiles dividing different numerators by different denominators |
| `orders.count.v1` | Completed orders only; cancelled and rejected are separate metrics, never a subtraction inside this one | A funnel whose stages do not sum to the total |
| `delivery_cost_variance.v1` | `provider_billed_som − fee_charged_som` on external deliveries, `UNBILLED` excluded and counted separately | An unbilled order reading as a zero-variance match |

Aggregator-commissioned orders keep `aggregator_commission_som` as `NULL` until
ADR 0040 supplies it. `NULL` is not zero, `revenue.net.v1` does not subtract it,
and a `revenue.net_of_commission.v2` arrives with the commission fact rather than
being faked from it now.

## Freshness, APIs, and exports

Near-real-time counters run seconds behind their domain event. Day-grain facts
close at business-day end plus sixty minutes and are re-derived after twenty-four
hours; classifications and forecasts run nightly, or on demand within an ADR 0033
quota. Every response declares its metric versions, business-day boundary,
timezone, and `as_of` instant — a report that cannot state its freshness is not
shipped, per ADR 0023. The settle recut compares the re-derived day against the
stored one and alerts on divergence rather than overwriting, because a projection
that quietly corrects itself hides the bug that caused the drift.

```text
GET  /api/v1/reporting/metrics        POST /api/v1/reporting/queries
GET  /api/v1/reporting/reports/{reportId}
POST /api/v1/reporting/exports        GET  /api/v1/reporting/exports/{exportId}
GET  /api/v1/reporting/forecasts      POST /api/v1/reporting/holidays
POST /api/v1/reporting/classifications/{kind}/runs
```

`POST /queries` takes a typed object — metric ids, dimension ids, filters, grain,
date range — and never SQL or a fragment of one. The moment a client can send an
expression, the metric registry becomes decoration and the disagreement this ADR
exists to prevent returns through the front door. Unknown ids are rejected with
`UNKNOWN_METRIC`, not ignored. Lists use ADR 0031 signed cursors, and query and
export endpoints are rate limited per principal under ADR 0033, which already
names report generation as an application-limited operation.

Capabilities added to the ADR 0025 registry: `report.read`, `report.export`,
`customer.pii.export`, `forecast.manage`, and platform-scoped `metric.manage`. An
export requesting a name or phone column without `customer.pii.export` is
**rejected** with `CAPABILITY_REQUIRED` naming the column, not silently narrowed:
a manager reading a customer list that quietly lost its phone column will assume
the phone numbers do not exist. Every export writes an ADR 0027 `BUSINESS` audit
fact with requester, report, filter, column set, metric versions, and row count —
the row count being the point, since ADR 0029 already names the difference between
an agent viewing one customer and exporting fifty thousand as the case audit
exists to catch. Exports are capped by rows per principal per day; exceeding the
cap requires an ADR 0027 approval rather than a larger default.

## Forecasting

Deliberately not machine learning. The model is seasonal-naive over a trailing
window with a day-of-week and hour-of-day profile, multiplied by a holiday factor
derived from prior occurrences of the same named holiday and bounded by a floor
and a ceiling. It is written down, reproducible, and explainable to a kitchen
manager who wants to know why it asked for 40 portions. Every row stores its
`model_version` and run, and the actual is written back after the day closes, so
`absolute_percentage_error` is a stored fact and accuracy is a number the product
can argue about rather than a feeling. The operating window is the tenant's
business day, so a 09:00→09:00 tenant forecasts its own night without an
off-by-one-day error.

## Testing

- Recomputing a closed business day from the same facts reproduces byte-identical
  aggregates.
- A build-time test asserts every metric a surface references resolves to a
  registry id, and that no reporting query outside the registry contains an
  aggregate.
- A refund three days after the order moves `revenue.net.v1` on the refund date
  and leaves `revenue.gross.v1` on the order date unchanged.
- Boundary tests at 08:59 and 09:01 for a 09:00 tenant, and 23:59 and 00:01 for a
  default tenant, in Asia/Tashkent.
- A tenant-scoped query cannot return another tenant's row, and a `LOCATION` grant
  cannot read a sibling location's branch report.
- Fact totals reconcile to `ordering` totals for a sampled day — the check that
  catches a projection which silently dropped events.
- A split-tender order writes one `fact_order` row whose `gross_revenue_som` is
  counted once, and `fact_order_tender` rows whose amounts sum to the order total.
- A tenant with two legal entities produces revenue and tax totals per entity that
  sum to the tenant total, and no financial report groups by tenant alone.
- An export without `customer.pii.export` is rejected, and every completed export
  has exactly one audit fact with a matching row count.
- The close job finishes inside its window on production-shaped volume, and the
  read role fails on any write or any module-schema select.

## Rollout

Behavioural events and `fact_behaviour` ship first, with no interface consuming
them, precisely because that is the only step which cannot be done later. Then
`fact_order`, `fact_order_line`, the close job, and the settle recut, behind a
flag and reconciled against `ordering` before anything reads them. Then the metric
registry and typed query API with three reports — daily operations, branch SLA,
branch leaderboard. Then ABC, XYZ, RFM, cohorts, and the funnel; then forecasting
and the holiday calendar. Exports come last, with capabilities, quotas, and audit
in place before the first file is produced.

Rollback disables the surfaces and stops the close job. Facts are derived, so they
can be dropped and rebuilt from events and module tables; that property is what
makes rollback cheap and it is worth protecting.

## Consequences

### Positive

- One versioned definition of every number, named identically in the dashboard,
  the export, and the API.
- PostgreSQL stays the only analytical store: no second copy of the truth to
  reconcile, no seventh dependency to operate, back up, and restore.
- Behavioural history starts accumulating before anyone asks for it, which is the
  only time it can start.
- Reporting cannot write, cannot read module schemas, and cannot leak a phone
  number into a file without a capability and an audit fact carrying the row count.
- The facts are shaped so adopting a columnar store later is an export rather than
  a redesign.

### Negative

- PostgreSQL now serves two workloads on one cluster, so a pathological analytics
  query becomes a checkout latency incident. Statement timeouts, a separate role,
  quotas, and eventually a replica reduce this; nothing removes it while there is
  one cluster.
- The star schema is a second copy of order data. It will drift, and the
  reconciliation job that catches drift is permanent work producing no feature.
- Payment now spans two grains. Every report author has to know that revenue comes
  from `fact_order` and payment mix from `fact_order_tender`, and the denormalised
  primary tender is a convenience that will be mistaken for the whole settlement at
  least once.
- Adding a chart requires a release, because a metric cannot be defined outside
  code. That is the intended trade and it will feel slow the first time a manager
  asks for a cut nobody anticipated.
- There is no ad-hoc BI. Delever ships an embedded workspace, and a merchant
  comparing the two will experience this as a missing feature, because it is one.
- Behavioural events cost Kafka throughput, storage, and a retention argument with
  legal before any dashboard exists to justify them.
- The forecast is deliberately unsophisticated. Someone will ask for machine
  learning and the answer will be a stored error rate rather than a model.
- Fixed SLA buckets will not fit a tenant whose promise is 45 minutes, and the
  answer — raw seconds are stored, a `v2` set can be cut retroactively — is a
  release rather than a setting.

### Accepted trade-offs

- Cross-surface consistency is chosen over flexibility. A tenant cannot invent a
  metric, redefine average check, or move an SLA boundary, and that is the point.
- Day-grain facts close on a schedule rather than compute live, so most of the
  dashboard is minutes to an hour stale. Live counters are a different surface
  with a different owner (ADR 0045).

## Implementation checklist

- [x] Add the metric registry as code (`reporting.domain.MetricRegistry`), mirrored into `reporting.metric_definitions` with a startup check that refuses to run against a definition edited in place.
- [x] Add facts, aggregates, and monthly partition upkeep via Flyway (V0031): `fact_order`, `fact_order_line`, `fact_refund`, `agg_branch_day`, `agg_sla_bucket_day`, `close_runs`, `aggregate_divergences`, `business_day_policies`.
- [x] Implement the close job, the settle recut, and the divergence alert.
- [x] Implement the typed query API, with the ADR 0038 refusal, the boundary-regime refusal, and provenance on every response.
- [x] Create the read-only `horecaos_reporting_read` role and prove it cannot write and cannot reach a module schema.
- [ ] Have finance sign metric definitions version 1 and record the signature. The mechanism ships (`POST /api/v1/platform-admin/reporting/metric-signatures/{metricCode}`, audited under ADR 0027); no signature has been recorded, so every metric reports as provisional.
- [ ] Add the `analytics.events` topic, schemas, and catalogue entries per ADR 0032, and emit the events from storefront, bot, and app channels. **Not started, and it is the one item on this list that cannot be done later** — see "What was not built" below.
- [ ] Add the type-2 dimension tables. Facts carry snapshotted names and codes today, which is enough for the reports built here.
- [ ] Implement projections on the ADR 0005 inbox path for near-real-time counters.
- [ ] Add `report.export`, `customer.pii.export`, and `forecast.manage` to the ADR 0025 registry, with the surfaces they gate.
- [ ] Implement asynchronous export with quotas, audit, and presigned delivery.
- [ ] Implement ABC, XYZ, and RFM runs, the forecast model, the holiday calendar, and error write-back.
- [ ] Confirm behavioural retention with legal before production.
- [ ] Schedule the close and the recut. `DayCloseService` is invoked by its caller today; nothing runs it on a timer, so no facts exist in an unattended deployment.

## What was built, and the decisions this ADR left open

**Where this deviates from the physical model above, and why.**

- **A refund is a grain, not a column.** The sketch put `refunded_som` and
  `refunded_on_business_date` on `fact_order`. That pair has no answer for an
  order refunded partly on Tuesday and again on Friday: it either attributes both
  to one date or holds one total against the wrong day, and nothing detects
  either. `reporting.fact_refund` holds one row per refund on the refund's own
  business date, which is the same argument this ADR already makes for
  `fact_order_tender`.
- **`agg_branch_day` is keyed by legal entity.** The sketch was not. Without the
  entity in the key the aggregate can produce a tenant-wide money total for a
  multi-entity tenant, which is precisely the figure ADR 0038 says must not
  exist. With it, the entity split is the cheap query and a combined total has to
  be asked for deliberately.
- **`gross_revenue_som` is the pre-discount figure.** `ordering.orders.total_minor`
  is already net of discount, so reading it as gross would subtract the discount
  twice and put `revenue.net.v1` below what the restaurant took. Stated on the
  column and in the registry definition, because "revenue" reading higher than
  takings on a promotion-heavy day is otherwise a support ticket.
- **`seconds_late` is signed, and null is a third state.** Null means no promise
  was made or the order never closed. ADR 0036 keeps lateness derived and unstored
  on the order; a closed order's lateness is the different question that ADR says
  belongs here.
- **No default partition on the fact tables**, unlike `audit.audit_events`. A
  default partition quietly absorbs rows for an unprovisioned month and then
  blocks that month's partition until somebody finds and moves them. Facts are
  derived and rebuildable, so a loud insert failure is the cheaper error.
- **The typed query is `GET .../reporting/queries`, not `POST /queries`.** It is a
  pure read, and the ADR 0031 build gate treats every `POST` as effectful and
  requires an `Idempotency-Key` — a header a read has no use for. Repeated query
  parameters keep the contract that matters: metric ids and dimension names, never
  SQL. A body-carrying query endpoint needs an exemption in that gate, which is a
  change to a shared build rule and is not taken here.
- **`report.read` is the already-registered `reporting.read`.** A second code for
  the same power would let a role hold one name and not the other. Only
  `metric.manage` was added.
- **`MEDIAN` and `DISTRIBUTION` metrics have their own endpoints.** A median
  cannot be composed from per-slice medians and a distribution is several rows per
  slice, so neither fits the query's one-value-per-cell shape. `prep_time.median.v1`
  and `sla_bucket_set.v1` are refused by the typed query, by name, pointing at the
  endpoint that answers them.
- **The recut does not write.** It re-derives the day from `ordering` and
  `payments`, compares against the stored aggregate on gross revenue, net revenue,
  and completed order count, and records a divergence row. The stored figure stays
  on the screen, because somebody has already acted on it.

## What was not built

Listed rather than half-built, because a table with no producer reads to the next
author as a broken projection.

- **Behavioural telemetry.** `analytics.events`, `fact_behaviour`, the funnel, and
  cohort retention. This ADR argues it should ship first because history cannot be
  backfilled, and that argument still stands — the emitters live in the storefront,
  the bot, and the app, which this change does not own. **Every day this is
  deferred is a day of funnel history that does not exist.**
- **`fact_order_tender`** (ADR 0046), and therefore the denormalised primary
  tender on `fact_order`. A primary-tender column with no tender fact behind it is
  the "convenience mistaken for the whole settlement" this ADR predicts, so
  neither was added.
- **`fact_delivery`** (ADR 0042). `delivery_cost_variance.v1` is declared in the
  registry with `sourceAvailable = false` and is refused by the query with a
  message naming the ADR, rather than answered with a zero that reads as a
  perfectly reconciled month.
- **`fact_promotion_redemption`**, which has no owning ADR at all.
- **Classifications (ABC, XYZ, RFM), forecasting, and the holiday calendar.**
- **Exports**, and with them `report.export` and `customer.pii.export`. Exports
  are the last item in this ADR's own rollout and the capabilities are not added
  ahead of them: a capability that gates nothing reads as a control that exists.
- **Type-2 dimension tables.** Facts snapshot the names and codes they need
  (`channel_code`, `product_name_snapshot`), which gives the same property — a
  rename does not rewrite last year's chart — for the reports built here.
- **`stock_disposition` and `liability_party`** are columns on `fact_order` and are
  null on every row until ADR 0039's `order_outcomes` exists. Null is not
  `NO_EFFECT`.
- **`aggregator_commission_som`** is null until ADR 0040. Null is not zero, and
  `revenue.net.v1` does not subtract it.
- **A schedule.** The close and the recut are services with no timer.

## Exit criteria

Every number on every Qoida surface resolves to a versioned metric id; a closed
business day recomputes to identical totals; each report states its metric
versions, timezone, business-day boundary, and freshness; no reporting query
touches a module schema or a writable connection; every export containing a
customer name or phone number required a capability and left an audit fact with
its row count; and behavioural history exists for every day since the pilot rather
than since the dashboard was built.
