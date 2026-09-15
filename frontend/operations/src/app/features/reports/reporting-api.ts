import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { ApiError } from '../../core/api/problem-details';
import { reportsPaths } from '../../core/api/reports-paths';

/**
 * What ADR 0023 requires every reporting response to declare about itself: the
 * metric versions it used, the business-day boundary and timezone they were
 * computed under, how far the close has got, which metrics are still
 * provisional, and whether a recut disagreed with a stored figure.
 *
 * Mirrors {@code ReportingController.ProvenanceResponse}.
 */
export interface ProvenanceResponse {
  readonly asOf: string;
  readonly closedThrough: string | null;
  readonly lastCloseCompletedAt: string | null;
  readonly businessDayStart: string;
  readonly timezone: string;
  readonly boundaryVersion: number;
  readonly metricVersions: readonly string[];
  readonly provisionalMetrics: readonly string[];
  readonly openDivergences: number;
}

/** Mirrors {@code ReportingController.MetricResponse}. */
export interface MetricResponse {
  readonly metricCode: string;
  readonly name: string;
  readonly version: number;
  readonly grain: string;
  readonly sourceFact: string;
  readonly sourceAvailable: boolean;
  readonly aggregation: string;
  readonly unit: string;
  readonly currencyRule: string;
  readonly roundingRule: string;
  readonly definition: string;
  readonly includes: string;
  readonly excludes: string;
  readonly refundTreatment: string;
  readonly openQuestion: string | null;
  readonly effectiveFrom: string | null;
  readonly provisional: boolean;
  readonly signedBy: string | null;
  readonly signedAt: string | null;
}

/** One day-grain slice's dimension values, plus the figures computed for it. */
export interface RowResponse {
  readonly businessDate: string;
  readonly locationId: string | null;
  readonly channelCode: string | null;
  readonly fulfilmentType: string | null;
  readonly legalEntityId: string | null;
  /**
   * T13 (7.6a): `'NEW'` or `'RETURNING'`, set only on a
   * `revenue.new_vs_returning.v1` row. Optional rather than required-nullable
   * so every other caller's existing fixtures (this field did not exist
   * before this wave) do not have to be revisited to add a null they never
   * cared about.
   */
  readonly customerType?: 'NEW' | 'RETURNING' | null;
  /** Metric code to figure. Absent means the slice had nothing to compute it from — never a zero. */
  readonly values: Readonly<Record<string, number | null>>;
}

/**
 * ADR 0043/ADR 0029, wave P28's own export centre. Mirrors
 * `ReportExportController.ReportExportRequest` — `columns` is what the
 * column chooser asked for, not what the job actually produced; the PII
 * group in it is silently dropped server-side when the requester lacks
 * `customer.pii.export` rather than refusing the whole request. See
 * `ExportColumnChooser` for the report-specific column vocabulary.
 */
export interface ReportExportRequest {
  readonly reportKey: string;
  readonly columns: readonly string[];
  readonly status?: string | null;
  readonly query?: string | null;
  readonly purpose: string;
}

/** Mirrors `ReportExportController.ReportExportQueuedResponse`. */
export interface ReportExportQueuedResponse {
  readonly exportId: string;
  readonly status: string;
}

/**
 * One export job's status — what the export centre's history list polls.
 * Mirrors `ReportExportController.ReportExportStatusResponse`.
 *
 * @property columns the effective columns the artefact actually carries —
 *   never the requested set, so a PII omission is visible here rather than
 *   only inferable from `includesPiiColumns`.
 * @property downloadUrl a short-lived signed URL, present only once
 *   `status` is `COMPLETE`.
 */
export interface ReportExportStatusResponse {
  readonly exportId: string;
  readonly reportKey: string;
  readonly status: 'QUEUED' | 'RUNNING' | 'COMPLETE' | 'FAILED';
  readonly columns: readonly string[];
  readonly includesPiiColumns: boolean;
  readonly rowQuota: number;
  readonly rowCount: number | null;
  readonly truncated: boolean;
  readonly failureReason: string | null;
  readonly createdAt: string;
  readonly completedAt: string | null;
  readonly downloadUrl: string | null;
}

export interface QueryResponse {
  readonly rows: readonly RowResponse[];
  readonly provenance: ProvenanceResponse;
}

export interface BucketResponse {
  readonly businessDate: string;
  readonly locationId: string;
  readonly bucketCode: string;
  readonly orderCount: number;
  readonly shareBasisPoints: number;
}

/**
 * Wave T06 (7.3/7.3a): one branch's median — mirrors
 * `ReportingController.LocationMedianResponse`. `medianSeconds` is null only
 * when the caller asks for the wrong shape; a branch with nothing to compute
 * a median from is simply absent from the parent list, never a row here.
 */
export interface LocationMedianResponse {
  readonly locationId: string;
  readonly medianSeconds: number | null;
}

/** @property medians wave T06 (7.3a): each branch's handover_time.median.v1, alongside the bucket counts. */
export interface SlaResponse {
  readonly buckets: readonly BucketResponse[];
  readonly medians: readonly LocationMedianResponse[];
  readonly provenance: ProvenanceResponse;
}

export interface MedianResponse {
  readonly medianSeconds: number | null;
  readonly provenance: ProvenanceResponse;
}

/** Wave T06 (7.3): every branch's median preparation time from one request. */
export interface LocationMedianListResponse {
  readonly rows: readonly LocationMedianResponse[];
  readonly provenance: ProvenanceResponse;
}

/**
 * One payment-mix row — mirrors `ReportingController.PaymentMixRowResponse`.
 *
 * @property locationId null on an `overview` row (folded across every branch
 *   in range, never across legal entities), set on a `byLocation` row.
 */
export interface PaymentMixRowResponse {
  readonly locationId: string | null;
  readonly legalEntityId: string | null;
  readonly paymentMethodCode: string;
  readonly settlesFromBalance: boolean;
  readonly tenderCount: number;
  readonly amountSom: number;
}

/**
 * P39 (7.1c/7.3b): `payment_mix.amount.v1` — the cash-collection control
 * figure. `overview` is what the business-overview card renders; `byLocation`
 * is what a future branch report (7.3b) would split by.
 */
export interface PaymentMixResponse {
  readonly overview: readonly PaymentMixRowResponse[];
  readonly byLocation: readonly PaymentMixRowResponse[];
  readonly provenance: ProvenanceResponse;
}

/**
 * One order, straight off {@code reporting.fact_order}. No name, phone,
 * operator, or courier: reporting has no {@code PERSONAL} field at all
 * (ADR 0029), so a commercial log built from this is honestly short of them
 * rather than silently blank.
 */
export interface OrderRowResponse {
  readonly orderId: string;
  readonly businessDate: string;
  readonly locationId: string;
  readonly legalEntityId: string | null;
  readonly channelCode: string;
  readonly fulfilmentType: string;
  readonly terminalStatus: string;
  readonly grossRevenueSom: number;
  readonly discountSom: number;
  readonly deliveryFeeSom: number;
  readonly taxSom: number;
  readonly netRevenueSom: number;
  readonly itemCount: number;
  readonly occurredAt: string;
  readonly closedAt: string | null;
  readonly secondsToConfirm: number | null;
  readonly secondsToReady: number | null;
  readonly secondsTotal: number | null;
  readonly secondsLate: number | null;
  readonly cancellationReasonCode: string | null;
  /** Wave P27 (7.2): CONFIRMED -> PREPARING, "branch acceptance". */
  readonly secondsToAccept: number | null;
  /** Wave P27 (7.2): PREPARING -> READY, actual cooking — narrower than secondsToReady. */
  readonly secondsPreparing: number | null;
  /** Wave P27 (7.2a): the short number a receipt prints. */
  readonly publicOrderNumber: string | null;
  /** Wave P27 (7.2a): «Предзаказ». */
  readonly isPreorder: boolean;
}

export interface OrderListResponse {
  readonly rows: readonly OrderRowResponse[];
  /** True when the bounded read came back full — there may be more, not a claim that there is. */
  readonly maybeMore: boolean;
  readonly provenance: ProvenanceResponse;
}

export interface OutcomeRowResponse {
  readonly terminalStatus: string;
  readonly cancellationReasonCode: string | null;
  /** Wave P27 (7.1): what the cancellation cost the tenant's stock — ADR 0039. */
  readonly stockDisposition: string | null;
  readonly liabilityParty: string | null;
  readonly count: number;
}

/** Wave P27 (7.1a): one tenant cancellation reason. Mirrors `ReportingController.CancellationReasonResponse`. */
export interface CancellationReasonResponse {
  readonly reasonCode: string;
  readonly internalName: string;
}

export interface OutcomeListResponse {
  readonly rows: readonly OutcomeRowResponse[];
  readonly provenance: ProvenanceResponse;
}

/** Which axis {@link ReportingApi.orders} bounds and sorts its read by. */
export type OrderSort = 'DATE_DESC' | 'DURATION_DESC' | 'LATENESS_DESC';

/** One product's summed sales in range — 7.7's «Продажи» tab. Mirrors `ReportingController.VariantSalesRowResponse`. */
export interface VariantSalesRowResponse {
  readonly variantId: string | null;
  readonly categoryId: string | null;
  readonly productName: string;
  readonly totalQuantity: number;
  readonly totalGrossSom: number;
  readonly totalNetSom: number;
  readonly deliveryQuantity: number | null;
  readonly deliveryNetSom: number | null;
  readonly pickupQuantity: number | null;
  readonly pickupNetSom: number | null;
}

export interface VariantSalesListResponse {
  readonly rows: readonly VariantSalesRowResponse[];
  readonly maybeMore: boolean;
  readonly provenance: ProvenanceResponse;
}

/**
 * T14 (7.7a/7.7b, ADR 0134): one product's persisted ABC/XYZ classification.
 * Mirrors `ProductClassificationController.ClassificationRowResponse`.
 */
export interface ClassificationRowResponse {
  readonly variantId: string;
  readonly categoryId: string | null;
  readonly productName: string;
  readonly revenueGrossSom: number;
  readonly revenueShareBasisPoints: number;
  readonly cumulativeShareBasisPoints: number;
  readonly abcClass: 'A' | 'B' | 'C';
  readonly quantityTotal: number;
  readonly meanQuantityPerBucket: number;
  readonly stddevQuantityPerBucket: number;
  readonly coefficientOfVariationBasisPoints: number;
  readonly xyzClass: 'X' | 'Y' | 'Z';
}

/**
 * T14: one persisted classification run — the window, the recorded
 * thresholds, and every product's class. Mirrors
 * `ProductClassificationController.ClassificationRunResponse`.
 */
export interface ClassificationRunResponse {
  readonly runId: string;
  readonly from: string;
  readonly to: string;
  readonly locationIds: readonly string[];
  readonly metricCode: string;
  readonly abcThresholdABasisPoints: number;
  readonly abcThresholdBBasisPoints: number;
  readonly xyzThresholdXBasisPoints: number;
  readonly xyzThresholdYBasisPoints: number;
  readonly bucketDays: number;
  readonly bucketCount: number;
  readonly computedAt: string;
  readonly rows: readonly ClassificationRowResponse[];
  readonly provenance: ProvenanceResponse;
}

/**
 * One hour-of-day's demand sample behind 7.8 (wave 48). Mirrors
 * {@code ReportingController.HourDemandResponse}.
 *
 * @property ordersByDate ISO business date to that date's order count in this
 *   hour, zero-filled for every date in the parent's `sampleDates` — a date
 *   this location traded on but that had nothing in this hour is a real zero,
 *   never a missing entry.
 * @property averageOrders null whenever the parent's `sampleDates` is shorter
 *   than `minimumSampleSize` — never a number computed from too thin a sample
 *   to mean anything.
 */
export interface HourDemandResponse {
  readonly hourOfDay: number;
  readonly ordersByDate: Readonly<Record<string, number>>;
  readonly totalOrders: number;
  readonly averageOrders: number | null;
}

/**
 * Reports 7.8's honest historical average (ADR 0043's implementation status,
 * owner decision 2026-09-05) — mirrors
 * {@code ReportingController.DemandHistoryResponse}. Not a forecast: every
 * number traces back to `sampleDates`, real business dates a manager could
 * look up in 7.2's order log.
 */
export type HolidayMode = 'INCLUDE' | 'EXCLUDE' | 'WEIGHT';

export interface DemandHistoryResponse {
  readonly locationId: string;
  /** ISO-8601: 1 = Monday .. 7 = Sunday. */
  readonly weekday: number;
  readonly requestedSampleSize: number;
  readonly minimumSampleSize: number;
  readonly sampleDates: readonly string[];
  /** 7.8b: the subset of `sampleDates` a `tenant.public_holidays` rule flagged — populated whatever `holidayMode` was requested. */
  readonly holidayDates: readonly string[];
  readonly holidayMode: HolidayMode;
  readonly hours: readonly HourDemandResponse[];
  readonly provenance: ProvenanceResponse;
}

/**
 * Wave W02 (7.8): one hour of the latest forecast run — mirrors
 * `ReportingController.DemandForecastHourResponse`.
 */
export interface DemandForecastHourResponse {
  readonly operatingHour: number;
  readonly forecastQuantity: number;
  readonly confidenceLow: number;
  readonly confidenceHigh: number;
  readonly actualQuantity: number | null;
  readonly absolutePercentageError: number | null;
}

/** One earlier run's forecast-vs-actual for one business date and hour — mirrors `ReportingController.DemandForecastComparisonResponse`. */
export interface DemandForecastComparisonResponse {
  readonly businessDate: string;
  readonly operatingHour: number;
  readonly forecastQuantity: number;
  readonly actualQuantity: number | null;
  readonly absolutePercentageError: number | null;
}

/**
 * Wave W02 (7.8): the seasonal-naive forecast for one location and weekday.
 * `runId` null and `hours` empty means `ForecastScheduler` has not generated
 * a usable run yet.
 */
export interface DemandForecastResponse {
  readonly locationId: string;
  readonly weekday: number;
  readonly runId: string | null;
  readonly modelVersion: number;
  readonly confidenceLevel: number;
  readonly generatedAt: string | null;
  readonly targetDate: string | null;
  readonly hours: readonly DemandForecastHourResponse[];
  readonly comparisons: readonly DemandForecastComparisonResponse[];
  readonly provenance: ProvenanceResponse;
}

/** One department or product row of the breakdown — mirrors `ReportingController.DemandForecastBreakdownRowResponse`. */
export interface DemandForecastBreakdownRowResponse {
  readonly categoryId: string | null;
  readonly variantId: string | null;
  readonly productName: string | null;
  readonly operatingHour: number;
  readonly forecastQuantity: number;
  readonly actualQuantity: number | null;
  readonly absolutePercentageError: number | null;
}

/** Wave W02 (7.8a): the latest forecast run's department or product breakdown. */
export interface DemandForecastBreakdownResponse {
  readonly locationId: string;
  readonly weekday: number;
  readonly byProduct: boolean;
  readonly rows: readonly DemandForecastBreakdownRowResponse[];
}

/**
 * T12 (7.5): one operator's completed-order count on one channel — the
 * leaderboard's per-channel column. Mirrors
 * `ReportingController.OperatorChannelCountResponse`.
 */
export interface OperatorChannelCountResponse {
  readonly channelCode: string;
  readonly orderCount: number;
}

/**
 * T12 (7.5): one operator's totals across the range — human or pseudo.
 * Mirrors `ReportingController.OperatorLeaderboardRowResponse`.
 *
 * @property operatorPrincipalId a staff Keycloak subject, or
 *   `"channel:<code>"` as a pseudo-operator when no human touched the order.
 * @property principalKind `'STAFF'` or `'MACHINE'` — the only "kind" this
 *   build can say until the staff-identity ADR lands, so a caller never
 *   renders a bare id with no explanation.
 * @property subject the Keycloak subject for `STAFF`, or the channel code for
 *   `MACHINE` — what a surface prints beside `principalKind`.
 * @property averageCheckSom null when `orderCount` is zero — never a
 *   zero-som average.
 * @property avgHandlingSeconds average seconds from order creation to
 *   confirmation, across orders that recorded one; null when none did.
 * @property avgItemsPerOrder 7.5a's receipt depth (`receipt_depth.v1`).
 */
export interface OperatorLeaderboardRowResponse {
  readonly operatorPrincipalId: string;
  readonly principalKind: 'STAFF' | 'MACHINE';
  readonly subject: string;
  readonly orderCount: number;
  readonly grossRevenueSom: number;
  readonly netRevenueSom: number;
  readonly averageCheckSom: number | null;
  readonly avgHandlingSeconds: number | null;
  readonly deliveryCount: number;
  readonly pickupCount: number;
  readonly dineInCount: number;
  readonly avgItemsPerOrder: number;
  readonly byChannel: readonly OperatorChannelCountResponse[];
}

export interface OperatorLeaderboardResponse {
  readonly rows: readonly OperatorLeaderboardRowResponse[];
  readonly provenance: ProvenanceResponse;
}

/** T12 (7.5a): one operator's product mix. Mirrors `ReportingController.OperatorProductListResponse`. */
export interface OperatorProductListResponse {
  readonly operatorPrincipalId: string;
  readonly rows: readonly VariantSalesRowResponse[];
  readonly maybeMore: boolean;
  readonly provenance: ProvenanceResponse;
}

/**
 * T11 (7.4, ADR 0125): one courier's totals across the range. Mirrors
 * `CourierReportController.LeaderboardRowResponse`. Never a courier's
 * protected name — resolve display through {@code CouriersApi.roster}'s own
 * `displayReference`, keyed on `courierId`, exactly as P19 already does
 * everywhere else in this console.
 *
 * @property onTimeShare null when no delivery in range recorded a promise —
 *   never a zero that would read as "missed every delivery".
 */
export interface CourierLeaderboardRowResponse {
  readonly courierId: string;
  readonly deliveryCount: number;
  readonly minDistanceMeters: number;
  readonly maxDistanceMeters: number;
  readonly avgDistanceMeters: number;
  readonly totalDistanceMeters: number;
  readonly avgTransitHours: number;
  readonly totalTransitSeconds: number;
  readonly onTimeShare: number | null;
}

export interface CourierLeaderboardResponse {
  readonly rows: readonly CourierLeaderboardRowResponse[];
  readonly provenance: ProvenanceResponse;
}

/** T11 (7.4a): the `COURIER` scope of the fixed SLA distribution. Mirrors `CourierBucketResponse`. */
export interface CourierBucketResponse {
  readonly businessDate: string;
  readonly courierId: string;
  readonly bucketCode: string;
  readonly orderCount: number;
  readonly shareBasisPoints: number;
}

export interface CourierSlaResponse {
  readonly buckets: readonly CourierBucketResponse[];
  readonly provenance: ProvenanceResponse;
}

/** T11 (7.4b): one (tariff, courier) group over the audit range. Mirrors `TariffAuditRowResponse`. */
export interface TariffAuditRowResponse {
  readonly tariffId: string;
  readonly tariffVersion: number;
  readonly zoneId: string | null;
  readonly bandSequence: number | null;
  readonly courierId: string | null;
  readonly resolutionCount: number;
  readonly totalFinalFeeMinor: number;
  readonly currency: string;
}

export interface TariffAuditResponse {
  readonly rows: readonly TariffAuditRowResponse[];
  readonly provenance: ProvenanceResponse;
}

/**
 * T11 (7.4c): one order's external-delivery cost cut. Mirrors
 * `ExternalDeliveryCostRowResponse`.
 *
 * @property reconciliationStatus `UNBILLED` when no invoice line exists at
 *   all yet — never `PENDING`, which means a line was imported and not yet
 *   matched.
 * @property reconcileActionAvailable false for a genuinely `UNBILLED` row:
 *   there is no invoice line yet to reconcile against.
 */
export interface ExternalDeliveryCostRowResponse {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly orderTotalMinor: number;
  readonly currency: string;
  readonly chargedDeliveryMinor: number;
  readonly shipmentId: string;
  readonly providerType: string | null;
  readonly providerEstimatedMinor: number | null;
  readonly providerBilledMinor: number | null;
  readonly varianceMinor: number | null;
  readonly reconciliationStatus: string;
  readonly reconcileActionAvailable: boolean;
}

export interface ExternalDeliveryCostResponse {
  readonly rows: readonly ExternalDeliveryCostRowResponse[];
  readonly totalVarianceMinor: number;
  readonly provenance: ProvenanceResponse;
}

/**
 * T13 (7.6): the six built KPI-tile figures — folded over the whole
 * requested range, never a day-grain breakdown. Mirrors
 * `ReportingController.CustomerKpiResponse`. `customers.ltv.v1` is not
 * here: it is registered `sourceAvailable = false`.
 *
 * @property repeatShareBasisPoints null when distinctCustomers is zero —
 *   never a zero that would read as "nobody came back".
 * @property customerValueSom null when distinctCustomers is zero.
 * @property basketDepth null when there were no orders to divide items by.
 */
export interface CustomerKpiResponse {
  readonly newCustomers: number;
  readonly distinctCustomers: number;
  readonly repeatShareBasisPoints: number | null;
  readonly orderFrequency: number | null;
  readonly customerValueSom: number | null;
  readonly basketDepth: number | null;
  readonly provenance: ProvenanceResponse;
}

/**
 * T13 (7.6a): one month-offset point on a cohort's retention curve. Mirrors
 * `ReportingController.RetentionPointResponse`.
 *
 * @property retainedBasisPoints null when the cohort's own size is zero.
 */
export interface RetentionPointResponse {
  readonly monthOffset: number;
  readonly orderMonth: string;
  readonly customerCount: number;
  readonly retainedBasisPoints: number | null;
}

/** T13 (7.6a): one cohort and its retention curve. Mirrors `ReportingController.CohortResponse`. */
export interface CohortResponse {
  readonly cohortMonth: string;
  readonly size: number;
  readonly points: readonly RetentionPointResponse[];
}

/** T13 (7.6a): the cohort/retention grid. Mirrors `ReportingController.CohortListResponse`. */
export interface CohortListResponse {
  readonly cohorts: readonly CohortResponse[];
  /** The widest range a single call tracks — narrow the request past this and the server refuses. */
  readonly windowMonths: number;
  readonly provenance: ProvenanceResponse;
}

/** T13 (7.6b): platform-fixed Frequency bands — never tenant-configurable. */
export type FrequencyBand = 'F1_SINGLE' | 'F2_FEW' | 'F3_FREQUENT';

/** T13 (7.6b): platform-fixed Recency bands, in days before the range's own `to`. */
export type RecencyBand = 'R1_RECENT' | 'R2_LAPSING' | 'R3_AT_RISK';

/** T13 (7.6b): one (Recency, Frequency) cell of the cross-tab. Mirrors `ReportingController.RfmCellResponse`. */
export interface RfmCellResponse {
  readonly recencyBand: RecencyBand;
  readonly frequencyBand: FrequencyBand;
  readonly memberCount: number;
  readonly revenueSom: number;
}

/**
 * T13 (7.6b): the whole R×F grid, all nine cells even when empty. Mirrors
 * `ReportingController.RfmGridResponse`. Distinct from 5.3's segment
 * builder: a marketer can size one segment there and read this whole grid
 * to decide which cell is worth targeting.
 */
export interface RfmGridResponse {
  readonly cells: readonly RfmCellResponse[];
  readonly totalCustomers: number;
  readonly provenance: ProvenanceResponse;
}

export interface QueryParams {
  readonly from: string;
  readonly to: string;
  readonly metric: readonly string[];
  readonly groupBy?: readonly string[];
  readonly locationId?: readonly string[];
  readonly channelCode?: readonly string[];
  /** Wave P27: previously only a groupBy dimension with no filter to go with it. */
  readonly legalEntityId?: readonly string[];
}

export interface RangeParams {
  readonly from: string;
  readonly to: string;
  readonly locationId?: readonly string[];
  readonly channelCode?: readonly string[];
}

/**
 * The tenant's reporting surface (ADR 0043) — a thin typed wrapper over
 * {@link reportsPaths}. Every method here is a pure GET; nothing in this
 * service composes a number of its own. A tile computing its own average is
 * exactly the failure the metric registry exists to prevent, so this class
 * only ever forwards a metric id and renders what comes back.
 */
@Injectable({ providedIn: 'root' })
export class ReportingApi {
  private readonly api = inject(ApiClient);

  async metrics(tenantId: string): Promise<readonly MetricResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly MetricResponse[]>(reportsPaths.metrics(tenantId)),
    );
    return result.value ?? [];
  }

  async query(tenantId: string, params: QueryParams): Promise<QueryResponse> {
    const result = await firstValueFrom(
      this.api.get<QueryResponse>(reportsPaths.queries(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          metric: params.metric,
          groupBy: params.groupBy,
          locationId: params.locationId,
          channelCode: params.channelCode,
          legalEntityId: params.legalEntityId,
        },
      }),
    );
    return result.value;
  }

  async slaBuckets(tenantId: string, params: RangeParams): Promise<SlaResponse> {
    const result = await firstValueFrom(
      this.api.get<SlaResponse>(reportsPaths.slaBuckets(tenantId), {
        params: { from: params.from, to: params.to, locationId: params.locationId },
      }),
    );
    return result.value;
  }

  async preparationTime(tenantId: string, params: RangeParams): Promise<MedianResponse> {
    const result = await firstValueFrom(
      this.api.get<MedianResponse>(reportsPaths.preparationTime(tenantId), {
        params: { from: params.from, to: params.to, locationId: params.locationId },
      }),
    );
    return result.value;
  }

  /**
   * Wave T06 (7.3): every branch's median preparation time from one request —
   * replaces the branch leaderboard's previous one-{@link preparationTime}
   * -call-per-branch fan-out.
   */
  async preparationTimeByLocation(
    tenantId: string,
    params: RangeParams,
  ): Promise<LocationMedianListResponse> {
    const result = await firstValueFrom(
      this.api.get<LocationMedianListResponse>(reportsPaths.preparationTimeByLocation(tenantId), {
        params: { from: params.from, to: params.to, locationId: params.locationId },
      }),
    );
    return result.value;
  }

  /** P39 (7.1c/7.3b): takings split by payment method. */
  async paymentMix(
    tenantId: string,
    params: {
      readonly from: string;
      readonly to: string;
      readonly locationId?: readonly string[];
      readonly paymentMethodCode?: readonly string[];
    },
  ): Promise<PaymentMixResponse> {
    const result = await firstValueFrom(
      this.api.get<PaymentMixResponse>(reportsPaths.paymentMix(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          paymentMethodCode: params.paymentMethodCode,
        },
      }),
    );
    return result.value;
  }

  async orders(
    tenantId: string,
    params: RangeParams & {
      readonly sort: OrderSort;
      readonly limit?: number;
      /** Wave P27: pushed into the query — previously filtered client-side over an already-fetched page. */
      readonly fulfilmentType?: readonly string[];
      readonly legalEntityId?: readonly string[];
      /** Wave P27 (7.2a): cursor paging past the bounded read — both present or both absent. */
      readonly afterOccurredAt?: string;
      readonly afterOrderId?: string;
    },
  ): Promise<OrderListResponse> {
    const result = await firstValueFrom(
      this.api.get<OrderListResponse>(reportsPaths.orders(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          channelCode: params.channelCode,
          fulfilmentType: params.fulfilmentType,
          legalEntityId: params.legalEntityId,
          sort: params.sort,
          limit: params.limit,
          afterOccurredAt: params.afterOccurredAt,
          afterOrderId: params.afterOrderId,
        },
      }),
    );
    return result.value;
  }

  /** Wave P27 (7.1): the overview's pickup/delivery elapsed-time tile. */
  async fulfilmentTime(
    tenantId: string,
    params: RangeParams & { readonly fulfilmentType: 'DELIVERY' | 'PICKUP' },
  ): Promise<MedianResponse> {
    const result = await firstValueFrom(
      this.api.get<MedianResponse>(reportsPaths.fulfilmentTime(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          fulfilmentType: params.fulfilmentType,
        },
      }),
    );
    return result.value;
  }

  /** Wave P27 (7.1a): resolves a cancellation reason code to its tenant-chosen label. */
  async cancellationReasons(tenantId: string): Promise<readonly CancellationReasonResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CancellationReasonResponse[]>(
        reportsPaths.cancellationReasons(tenantId),
      ),
    );
    return result.value ?? [];
  }

  async orderOutcomes(tenantId: string, params: RangeParams): Promise<OutcomeListResponse> {
    const result = await firstValueFrom(
      this.api.get<OutcomeListResponse>(reportsPaths.orderOutcomes(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          channelCode: params.channelCode,
        },
      }),
    );
    return result.value;
  }

  /** 7.8's historical average — see {@link DemandHistoryResponse}'s own doc. */
  async demandHistory(
    tenantId: string,
    params: {
      readonly locationId: string;
      readonly weekday: number;
      readonly sampleSize?: number;
      readonly holidayMode?: HolidayMode;
    },
  ): Promise<DemandHistoryResponse> {
    const result = await firstValueFrom(
      this.api.get<DemandHistoryResponse>(reportsPaths.demandHistory(tenantId), {
        params: {
          locationId: params.locationId,
          weekday: params.weekday,
          sampleSize: params.sampleSize,
          holidayMode: params.holidayMode,
        },
      }),
    );
    return result.value;
  }

  /** Wave W02: the seasonal-naive forecast, its confidence interval and the forecast-vs-actual comparison — see {@link DemandForecastResponse}'s own doc. */
  async demandForecast(
    tenantId: string,
    params: {
      readonly locationId: string;
      readonly weekday: number;
      readonly comparisonLimit?: number;
    },
  ): Promise<DemandForecastResponse> {
    const result = await firstValueFrom(
      this.api.get<DemandForecastResponse>(reportsPaths.demandForecast(tenantId), {
        params: {
          locationId: params.locationId,
          weekday: params.weekday,
          comparisonLimit: params.comparisonLimit,
        },
      }),
    );
    return result.value;
  }

  /** Wave W02 (7.8a): the latest forecast run's department or product breakdown. */
  async demandForecastBreakdown(
    tenantId: string,
    params: {
      readonly locationId: string;
      readonly weekday: number;
      readonly dimension: 'CATEGORY' | 'VARIANT';
    },
  ): Promise<DemandForecastBreakdownResponse> {
    const result = await firstValueFrom(
      this.api.get<DemandForecastBreakdownResponse>(
        reportsPaths.demandForecastBreakdown(tenantId),
        {
          params: {
            locationId: params.locationId,
            weekday: params.weekday,
            dimension: params.dimension,
          },
        },
      ),
    );
    return result.value;
  }

  /**
   * Wave T14 (7.7): `fulfilmentType` now reaches the query, previously
   * accepted nowhere and the filter bar's control read by nothing — see
   * `product-analytics-page.ts`'s own doc for the defect this replaced.
   */
  async variantSales(
    tenantId: string,
    params: RangeParams & { readonly fulfilmentType?: readonly string[]; readonly limit?: number },
  ): Promise<VariantSalesListResponse> {
    const result = await firstValueFrom(
      this.api.get<VariantSalesListResponse>(reportsPaths.variantSales(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          fulfilmentType: params.fulfilmentType,
          limit: params.limit,
        },
      }),
    );
    return result.value;
  }

  /** T12 (7.5): the operator leaderboard, human and pseudo rows together. */
  async operatorLeaderboard(
    tenantId: string,
    params: RangeParams,
  ): Promise<OperatorLeaderboardResponse> {
    const result = await firstValueFrom(
      this.api.get<OperatorLeaderboardResponse>(reportsPaths.operatorLeaderboard(tenantId), {
        params: { from: params.from, to: params.to, locationId: params.locationId },
      }),
    );
    return result.value;
  }

  /** T12 (7.5a): one operator's product mix, drilled down from a leaderboard row. */
  async operatorProducts(
    tenantId: string,
    params: RangeParams & { readonly operatorPrincipalId: string; readonly limit?: number },
  ): Promise<OperatorProductListResponse> {
    const result = await firstValueFrom(
      this.api.get<OperatorProductListResponse>(reportsPaths.operatorProducts(tenantId), {
        params: {
          operatorPrincipalId: params.operatorPrincipalId,
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          limit: params.limit,
        },
      }),
    );
    return result.value;
  }

  /** T11 (7.4): the courier leaderboard, courierId only — resolve display through `CouriersApi.roster`. */
  async courierLeaderboard(
    tenantId: string,
    params: { readonly from: string; readonly to: string },
  ): Promise<CourierLeaderboardResponse> {
    const result = await firstValueFrom(
      this.api.get<CourierLeaderboardResponse>(reportsPaths.courierLeaderboard(tenantId), {
        params: { from: params.from, to: params.to },
      }),
    );
    return result.value;
  }

  /** T11 (7.4a): the `COURIER` scope of the fixed SLA distribution. */
  async courierSlaBuckets(
    tenantId: string,
    params: { readonly from: string; readonly to: string },
  ): Promise<CourierSlaResponse> {
    const result = await firstValueFrom(
      this.api.get<CourierSlaResponse>(reportsPaths.courierSlaBuckets(tenantId), {
        params: { from: params.from, to: params.to },
      }),
    );
    return result.value;
  }

  /** T11 (7.4b): the delivery-sum-by-tariff audit. */
  async courierTariffAudit(tenantId: string, params: RangeParams): Promise<TariffAuditResponse> {
    const result = await firstValueFrom(
      this.api.get<TariffAuditResponse>(reportsPaths.courierTariffAudit(tenantId), {
        params: { from: params.from, to: params.to, locationId: params.locationId },
      }),
    );
    return result.value;
  }

  /** T13 (7.6): the KPI-tile figures — new/distinct customers, repeat share, order frequency, customer value, basket depth. */
  async customerKpis(
    tenantId: string,
    params: {
      readonly from: string;
      readonly to: string;
      readonly locationId?: readonly string[];
      readonly legalEntityId?: readonly string[];
    },
  ): Promise<CustomerKpiResponse> {
    const result = await firstValueFrom(
      this.api.get<CustomerKpiResponse>(reportsPaths.customerKpis(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          legalEntityId: params.legalEntityId,
        },
      }),
    );
    return result.value;
  }

  /** T13 (7.6a): monthly cohorts by first-order month, and their retention curve. */
  async customerCohorts(tenantId: string, params: RangeParams): Promise<CohortListResponse> {
    const result = await firstValueFrom(
      this.api.get<CohortListResponse>(reportsPaths.customerCohorts(tenantId), {
        params: { from: params.from, to: params.to, locationId: params.locationId },
      }),
    );
    return result.value;
  }

  /** T13 (7.6b): the Recency x Frequency cross-tab, member counts and revenue per cell. */
  async customerRfm(
    tenantId: string,
    params: {
      readonly from: string;
      readonly to: string;
      readonly locationId?: readonly string[];
      readonly legalEntityId?: readonly string[];
    },
  ): Promise<RfmGridResponse> {
    const result = await firstValueFrom(
      this.api.get<RfmGridResponse>(reportsPaths.customerRfm(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          legalEntityId: params.legalEntityId,
        },
      }),
    );
    return result.value;
  }

  /** T11 (7.4c): per-order external-delivery cost — the one courier report that finds money. */
  async courierExternalDeliveryCost(
    tenantId: string,
    params: RangeParams,
  ): Promise<ExternalDeliveryCostResponse> {
    const result = await firstValueFrom(
      this.api.get<ExternalDeliveryCostResponse>(
        reportsPaths.courierExternalDeliveryCost(tenantId),
        {
          params: { from: params.from, to: params.to, locationId: params.locationId },
        },
      ),
    );
    return result.value;
  }

  // ---------------------------------------------- row 7.2e: the export centre

  /** Queues a report export under `report.export`; returns immediately with an id to poll. */
  async requestExport(
    tenantId: string,
    request: ReportExportRequest,
  ): Promise<ReportExportQueuedResponse> {
    return firstValueFrom(
      this.api.post<ReportExportRequest, ReportExportQueuedResponse>(
        reportsPaths.exports(tenantId),
        command(request),
      ),
    );
  }

  /**
   * T14 (7.7a/7.7b, ADR 0134): computes and persists a new ABC/XYZ run.
   * `Capability.REPORTING_CLASSIFICATION_RUN`, not `reporting.read` — a run
   * writes rows, so this is a `POST` with its own idempotency key rather
   * than a side effect of opening a tab.
   */
  async runClassification(
    tenantId: string,
    params: {
      readonly from: string;
      readonly to: string;
      readonly locationIds?: readonly string[];
    },
  ): Promise<ClassificationRunResponse> {
    return firstValueFrom(
      this.api.post<
        { readonly from: string; readonly to: string; readonly locationIds?: readonly string[] },
        ClassificationRunResponse
      >(
        reportsPaths.classificationRuns(tenantId),
        command({ from: params.from, to: params.to, locationIds: params.locationIds }),
      ),
    );
  }

  /** One export job's status — what the export centre screen polls. */
  async exportStatus(tenantId: string, exportId: string): Promise<ReportExportStatusResponse> {
    const result = await firstValueFrom(
      this.api.get<ReportExportStatusResponse>(reportsPaths.reportExport(tenantId, exportId)),
    );
    return result.value;
  }

  /** The export centre's own job history, newest first. */
  async recentExports(
    tenantId: string,
    limit = 50,
  ): Promise<readonly ReportExportStatusResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ReportExportStatusResponse[]>(reportsPaths.exports(tenantId), {
        params: { limit },
      }),
    );
    return result.value ?? [];
  }

  /**
   * The most recently computed run over this exact window, or `null` when
   * nobody has run one yet — a plain `reporting.read`, unlike {@link
   * runClassification}.
   */
  async latestClassification(
    tenantId: string,
    params: { readonly from: string; readonly to: string; readonly locationId?: readonly string[] },
  ): Promise<ClassificationRunResponse | null> {
    try {
      const result = await firstValueFrom(
        this.api.get<ClassificationRunResponse>(reportsPaths.classificationRunsLatest(tenantId), {
          params: { from: params.from, to: params.to, locationId: params.locationId },
        }),
      );
      return result.value;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }
      throw error;
    }
  }
}
