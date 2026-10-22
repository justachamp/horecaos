import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient, QueryParams } from '../../core/api/api-client';
import { deliveryListPaths } from '../../core/api/delivery-paths';
import { CursorState, Page } from '../../core/api/page';

/** The plan states the list filters by (`PlanStatus`, ADR 0014). */
export const DELIVERY_STATES = [
  'PLANNED',
  'WAITING_TO_SOURCE',
  'SOURCING',
  'BOOKING',
  'RETRY_PENDING',
  'SCHEDULED',
  'ASSIGNED',
  'IN_PROGRESS',
  'COMPLETED',
  'MANUAL_ACTION_REQUIRED',
  'CANCELLED',
] as const;

export type DeliveryState = (typeof DELIVERY_STATES)[number];

/** `OperationsDeliveryListController.CostResponse`: one cost line at its basis, never summed across bases. */
export interface DeliveryCost {
  /** `ACCRUED` | `INVOICED` | `SETTLED`. */
  readonly basis: string;
  /** `INTERNAL` (what the courier accrued) or `PARTNER` (what a partner charged). */
  readonly path: string;
  readonly amountMinor: number;
  readonly currency: string;
}

/**
 * Mirrors `OperationsDeliveryListController.DeliveryResponse` (ADR 0164 slice two).
 *
 * No field names a recipient: the destination is the non-PII label ordering projected for the dispatch board, and
 * the order detail pane's own authorised reveal is the only way to a name, a number or an address (ADR 0029).
 */
export interface DeliveryRow {
  readonly planId: string;
  readonly orderId: string;
  readonly orderReference: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly branchName: string;
  readonly planStatus: DeliveryState | string;
  readonly shipmentId?: string | null;
  readonly shipmentStatus?: string | null;
  /** `INTERNAL` (an in-house courier) or `PARTNER`; absent before anything was assigned. */
  readonly sourceType?: string | null;
  readonly courierId?: string | null;
  readonly providerType?: string | null;
  readonly destinationLabel?: string | null;
  readonly distanceMeters?: number | null;
  readonly confirmedAt: string;
  readonly promisedDeliveryStart?: string | null;
  readonly promisedDeliveryEnd?: string | null;
  readonly pickedUpAt?: string | null;
  readonly deliveredAt?: string | null;
  readonly orderTotalMinor: number;
  readonly currency: string;
  readonly prepaid: boolean;
  /** Null when no shipment exists yet: there is nobody to collect it. */
  readonly cashToCollectMinor?: number | null;
  readonly cost?: DeliveryCost | null;
}

/** What the list can be narrowed by; each is optional and the scope follows the ones given (ADR 0144). */
export interface DeliveryFilters {
  readonly brandId?: string | null;
  readonly locationId?: string | null;
  readonly states?: readonly string[];
  readonly courierId?: string | null;
  /** ISO instants; `to` is exclusive. */
  readonly from?: string | null;
  readonly to?: string | null;
}

/** `GET /api/v1/operations/tenants/{t}/deliveries` (ADR 0164 slice two), keyset-paged. */
@Injectable({ providedIn: 'root' })
export class DeliveriesApi {
  private readonly api = inject(ApiClient);

  list(tenantId: string, filters: DeliveryFilters, state: CursorState): Promise<Page<DeliveryRow>> {
    const query: Record<string, string | readonly string[]> = {};
    if (filters.brandId) {
      query['brandId'] = filters.brandId;
    }
    if (filters.locationId) {
      query['locationId'] = filters.locationId;
    }
    if (filters.states && filters.states.length > 0) {
      query['status'] = filters.states;
    }
    if (filters.courierId) {
      query['courierId'] = filters.courierId;
    }
    if (filters.from) {
      query['from'] = filters.from;
    }
    if (filters.to) {
      query['to'] = filters.to;
    }
    return firstValueFrom(
      this.api.page<DeliveryRow>(
        deliveryListPaths.deliveries(tenantId),
        state,
        query as QueryParams,
      ),
    );
  }
}
