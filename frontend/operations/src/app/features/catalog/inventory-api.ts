import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** `TrackingMode` — `inventory.stock_items.tracking_mode`. `QUANTITY` exists in the schema but is refused by the service. */
export type TrackingMode = 'BINARY' | 'UNTRACKED' | 'QUANTITY';

/** `inventory.api.AvailabilityDecision.Unavailable` — one item keeping a cart from being fully available. */
export interface UnavailableItem {
  readonly variantId: string;
  /** A stable code (`SOLD_OUT`, `NOT_STOCKED_AT_LOCATION`, `RESERVATION_NO_LONGER_HELD`), not prose. */
  readonly reason: string;
}

/** `inventory.api.AvailabilityDecision` — whether a set of variants can be fulfilled here (ADR 0017). */
export interface AvailabilityDecision {
  readonly available: boolean;
  readonly unavailableItems: readonly UnavailableItem[];
}

/**
 * `GET/POST/PUT .../inventory/**` — `InventoryController` (waves 6/24), on the
 * ADR 0031 `/api/v1/operations/**` prefix (unlike `CatalogApi`/`PricingApi`).
 * The audited stop/86 toggle catalog.md §4.2 tab 6 and §4.6 both read.
 */
@Injectable({ providedIn: 'root' })
export class InventoryApi {
  private readonly api = inject(ApiClient);

  registerStockItem(
    scope: LocationScope,
    variantId: string,
    trackingMode: TrackingMode,
  ): Observable<{ stockItemId: string; trackingMode: TrackingMode }> {
    return this.api.post(
      operationsPaths.inventoryStockItems(scope),
      command({ variantId, trackingMode }),
    );
  }

  /**
   * The audited toggle: `InventoryService#setAvailabilityAudited`. Idempotent
   * — a repeat call with the state already matching is a documented no-op,
   * never a 409, so a double-tap in the kitchen is safe.
   */
  setAvailability(
    scope: LocationScope,
    variantId: string,
    available: boolean,
    reasonCode?: string,
  ): Observable<void> {
    return this.api.put<{ available: boolean; reasonCode?: string }, void>(
      operationsPaths.inventoryVariantAvailability(scope, variantId),
      command({ available, ...(reasonCode ? { reasonCode } : {}) }),
    );
  }

  availability(
    scope: LocationScope,
    variantIds: readonly string[],
  ): Observable<AvailabilityDecision> {
    return this.api
      .get<AvailabilityDecision>(operationsPaths.inventoryAvailability(scope), {
        params: { variantIds },
      })
      .pipe(map((result) => result.value));
  }
}
