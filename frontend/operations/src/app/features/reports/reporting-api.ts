import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
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
  /** Metric code to figure. Absent means the slice had nothing to compute it from — never a zero. */
  readonly values: Readonly<Record<string, number | null>>;
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

export interface SlaResponse {
  readonly buckets: readonly BucketResponse[];
  readonly provenance: ProvenanceResponse;
}

export interface MedianResponse {
  readonly medianSeconds: number | null;
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
  readonly count: number;
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
export interface DemandHistoryResponse {
  readonly locationId: string;
  /** ISO-8601: 1 = Monday .. 7 = Sunday. */
  readonly weekday: number;
  readonly requestedSampleSize: number;
  readonly minimumSampleSize: number;
  readonly sampleDates: readonly string[];
  readonly hours: readonly HourDemandResponse[];
  readonly provenance: ProvenanceResponse;
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

export interface QueryParams {
  readonly from: string;
  readonly to: string;
  readonly metric: readonly string[];
  readonly groupBy?: readonly string[];
  readonly locationId?: readonly string[];
  readonly channelCode?: readonly string[];
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
    params: RangeParams & { readonly sort: OrderSort; readonly limit?: number },
  ): Promise<OrderListResponse> {
    const result = await firstValueFrom(
      this.api.get<OrderListResponse>(reportsPaths.orders(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
          channelCode: params.channelCode,
          sort: params.sort,
          limit: params.limit,
        },
      }),
    );
    return result.value;
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
    params: { readonly locationId: string; readonly weekday: number; readonly sampleSize?: number },
  ): Promise<DemandHistoryResponse> {
    const result = await firstValueFrom(
      this.api.get<DemandHistoryResponse>(reportsPaths.demandHistory(tenantId), {
        params: {
          locationId: params.locationId,
          weekday: params.weekday,
          sampleSize: params.sampleSize,
        },
      }),
    );
    return result.value;
  }

  async variantSales(
    tenantId: string,
    params: RangeParams & { readonly limit?: number },
  ): Promise<VariantSalesListResponse> {
    const result = await firstValueFrom(
      this.api.get<VariantSalesListResponse>(reportsPaths.variantSales(tenantId), {
        params: {
          from: params.from,
          to: params.to,
          locationId: params.locationId,
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
}
