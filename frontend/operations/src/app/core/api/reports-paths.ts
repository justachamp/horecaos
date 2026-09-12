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

  /**
   * 7.8's historical average order count by hour, for one location and
   * weekday — wave 48. Not a forecast id despite the section's own name: see
   * `demand-forecast-page.ts`'s doc for the owner's 2026-09-05 decision to
   * ship the honest average now rather than ADR 0043's seasonal-naive model.
   */
  demandHistory(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/demand-history`;
  },

  /**
   * 10.10c: the version card — which bucket definitions the `/sla-buckets`
   * distribution above was computed under. Read-only; `ReportingController`
   * mirrors `SlaBucketController`'s platform-admin read at tenant scope.
   */
  slaBucketSet(tenantId: string): string {
    return `${TENANT_REPORTING(tenantId)}/sla-bucket-set`;
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
