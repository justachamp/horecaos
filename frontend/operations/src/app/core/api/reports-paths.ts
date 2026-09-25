/**
 * Where the reporting surface lives on the platform (ADR 0043).
 *
 * Unlike {@link operationsPaths}, every one of these is tenant-scoped rather
 * than location-scoped — `ReportingController` is mounted at
 * `/api/v1/tenants/{tenantId}/reporting` and enforces `Capability.REPORTING_READ`
 * at `TENANT`, not `LOCATION`. A location filter is a query parameter, never a
 * path segment: a report is a cut over the tenant's data, not a resource that
 * belongs to one branch.
 */

const TENANT_REPORTING = (tenantId: string): string =>
  `/api/v1/tenants/${encodeURIComponent(tenantId)}/reporting`;

export const reportsPaths = {
  /** The metric dictionary: every definition this build knows, signed or provisional. */
  metrics(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/metrics`;
  },

  /** Named metrics over a date range, grouped by named dimensions. */
  queries(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/queries`;
  },

  /** The fixed six-bucket SLA distribution, per branch. */
  slaBuckets(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/sla-buckets`;
  },

  /** Median seconds from confirmation to ready — its own endpoint; a median cannot be composed. */
  preparationTime(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/preparation-time`;
  },

  /** Wave T06 (7.3): every branch's median preparation time from one request, not a fan-out. */
  preparationTimeByLocation(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/preparation-time-by-location`;
  },

  /** Order-grain rows behind 7.2's «Этапы», «Заказы» and «Опоздания» tables. */
  orders(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/orders`;
  },

  /** Every terminal status in range, split by cancellation reason. */
  orderOutcomes(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/order-outcomes`;
  },

  /** Per-variant sales behind 7.7's «Продажи» tab — wave 39. */
  variantSales(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/variant-sales`;
  },

  /** X.19 (w6-reporting-facts, batch 11): the ABC cumulative-revenue-share curve. */
  abcCurve(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/abc-curve`;
  },

  /** T14 (7.7a/7.7b, ADR 0134): start a persisted ABC/XYZ classification run. */
  classificationRuns(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/classification-runs`;
  },

  /** T14: the most recently computed run over an exact window — a read, not a run. */
  classificationRunsLatest(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/classification-runs/latest`;
  },

  /**
   * T12 (7.5): orders taken, revenue, average check, average handling time,
   * delivery/pickup/dine-in and a per-channel breakdown — one row per
   * operator, human or pseudo.
   */
  operatorLeaderboard(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/operator-leaderboard`;
  },

  /** T12 (7.5a): one operator's product mix, drilled down from a leaderboard row. */
  operatorProducts(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/operator-products`;
  },

  /**
   * 7.8's historical average order count by hour, for one location and
   * weekday — wave 48. Not a forecast id despite the section's own name: see
   * `demand-forecast-page.ts`'s doc for the owner's 2026-09-05 decision to
   * ship the honest average now rather than ADR 0043's seasonal-naive model.
   */
  demandHistory(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/demand-history`;
  },

  /** Wave W02: the seasonal-naive forecast, confidence interval and forecast-vs-actual comparison, the model `demand-history` itself deliberately never was. */
  demandForecast(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/demand-forecast`;
  },

  /** Wave W02 (7.8a): the latest forecast run's department or product breakdown. */
  demandForecastBreakdown(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/demand-forecast/breakdown`;
  },

  /**
   * 10.10c: the version card — which bucket definitions the `/sla-buckets`
   * distribution above was computed under. Read-only; `ReportingController`
   * mirrors `SlaBucketController`'s platform-admin read at tenant scope.
   */
  slaBucketSet(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/sla-bucket-set`;
  },

  /** Row 7.10b (wave 10 w5-reports-exports): the geography page's distance histogram, live over reporting.fact_delivery. */
  distanceBuckets(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/distance-buckets`;
  },

  /** Row 7.10b: the published bucket boundaries and version — mirrors `/sla-bucket-set`. */
  distanceBucketSet(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/distance-bucket-set`;
  },

  /**
   * P39 (7.1c/7.3b): takings split by payment method —
   * `payment_mix.amount.v1` over `reporting.fact_order_tender`. Its own
   * endpoint, like `/sla-buckets`: a share-per-method breakdown is several
   * rows per slice, not the typed `/queries` pipeline's one-value-per-cell
   * shape.
   */
  paymentMix(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/payment-mix`;
  },

  /** Wave P27 (7.1): the overview's pickup/delivery elapsed-time tile. */
  fulfilmentTime(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/fulfilment-time`;
  },

  /** Wave 9 w4-reports-distance-crm (7.1): the overview's distance KPI tile — delivery_distance.average.v1. */
  deliveryDistance(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/delivery-distance`;
  },

  /** Wave P27 (7.1a): the tenant's cancellation-reason registry. */
  cancellationReasons(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/cancellation-reasons`;
  },

  /**
   * T13 (7.6): new/distinct customers, repeat share, order frequency,
   * customer value and basket depth — folded over the whole requested
   * range. Its own endpoint, not `/queries`: a distinct-customer count
   * cannot be correctly summed across the channel/fulfilment rows that
   * pipeline rolls `agg_branch_day` up from.
   */
  customerKpis(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/customer-kpis`;
  },

  /** T13 (7.6a): monthly cohorts by first-order month, and their retention curve. */
  customerCohorts(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/customer-cohorts`;
  },

  /** T13 (7.6b): the Recency x Frequency cross-tab, member counts and revenue per cell. */
  customerRfm(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/customer-rfm`;
  },

  /**
   * T11 (7.4/7.4a/7.4b/7.4c, ADR 0125): `CourierReportController`'s own
   * sub-tree — a separate class server-side (see its own doc for why), and a
   * separate path segment here so the four courier reads read as one group
   * rather than four more siblings of `/sla-buckets`.
   */
  courierLeaderboard(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/couriers/leaderboard`;
  },

  courierSlaBuckets(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/couriers/sla-buckets`;
  },

  courierTariffAudit(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/couriers/tariff-audit`;
  },

  courierExternalDeliveryCost(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/couriers/external-delivery-cost`;
  },

  /**
   * ADR 0043/ADR 0029, wave P28: the export centre's own job queue —
   * `POST` queues one under `report.export`, `GET` is the job history the
   * export centre screen renders.
   */
  exports(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/exports`;
  },

  /** One report export's status, by id — what the export centre screen polls. */
  reportExport(tenantId: string, exportId: string): string {
    return `${TENANT_REPORTING(tenantId)}/reports/${encodeURIComponent(exportId)}`;
  },
} as const;

/**
 * 10.10b: `BusinessCalendarController` — a tenant's weekend, its own
 * holidays, and its business-day boundary. Sibling of {@link reportsPaths}'s
 * own base path, same `TENANT` scope, different capability
 * (`TENANT_CONFIGURATION_WRITE` for the three writes).
 */
export const businessCalendarPaths = {
  root(tenantId: string): string {
    return `/api/v1/tenants/${encodeURIComponent(tenantId)}/business-calendar`;
  },
  boundary(tenantId: string): string {
    return `${this.root(tenantId)}/boundary`;
  },
  weekend(tenantId: string): string {
    return `${this.root(tenantId)}/weekend`;
  },
  holidays(tenantId: string): string {
    return `${this.root(tenantId)}/holidays`;
  },
  holiday(tenantId: string, holidayId: string): string {
    return `${this.holidays(tenantId)}/${encodeURIComponent(holidayId)}`;
  },
} as const;
