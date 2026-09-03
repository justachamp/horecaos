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
}
