import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/**
 * `TrackingMode` — `inventory.stock_items.tracking_mode`. `QUANTITY` is
 * enforced exactly when `catalog.use_stock_logic` is on for the tenant (gap
 * map row 4.4c/4.4d); off, a `QUANTITY` item behaves like `UNTRACKED`.
 */
export type TrackingMode = 'BINARY' | 'UNTRACKED' | 'QUANTITY';

/**
 * `tenant.sales_channels.system_type`'s own closed set (V0020), repeated here
 * the same way the backend's own migration comment does — a channel-type
 * threshold names one of these, never a free string.
 */
export type ChannelSystemType =
  | 'WEB'
  | 'IOS'
  | 'ANDROID'
  | 'TELEGRAM'
  | 'KIOSK'
  | 'QR_TABLE'
  | 'CALL_CENTRE'
  | 'AGGREGATOR'
  | 'POS';

/** `InventoryController.ChannelStopThresholdResponse`. */
export interface ChannelStopThreshold {
  readonly channelSystemType: string;
  readonly stopAtOrBelow: number;
}

/**
 * `InventoryController.StockPositionResponse` — one stock item's position at
 * a location (gap map row 4.4c). `onHandQuantity`/`reservedQuantity` are
 * reported as `0` and `remainingQuantity` as `null` for a BINARY/UNTRACKED
 * item; those columns carry no meaning outside QUANTITY tracking.
 */
export interface StockPosition {
  readonly stockItemId: string;
  readonly variantId: string;
  readonly trackingMode: TrackingMode;
  readonly binaryAvailable: boolean | null;
  readonly onHandQuantity: number;
  readonly reservedQuantity: number;
  readonly remainingQuantity: number | null;
  readonly defaultQuantity: number | null;
  readonly lastResetBusinessDate: string | null;
  readonly channelStopThresholds: readonly ChannelStopThreshold[];
}

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

  /** Every stock item at this location, with its position — the Stock page's own read (gap map row 4.4c). */
  listPositions(scope: LocationScope): Observable<readonly StockPosition[]> {
    return this.api
      .get<StockPosition[]>(operationsPaths.inventoryPositions(scope))
      .pipe(map((result) => result.value));
  }

  /** Same check as {@link availability}, plus a channel type's own per-item stop threshold (gap map row 4.4c). */
  availabilityForChannel(
    scope: LocationScope,
    variantIds: readonly string[],
    channel: ChannelSystemType,
  ): Observable<AvailabilityDecision> {
    return this.api
      .get<AvailabilityDecision>(operationsPaths.inventoryAvailabilityForChannel(scope), {
        params: { variantIds, channel },
      })
      .pipe(map((result) => result.value));
  }

  /** An operator's manual on-hand count for a QUANTITY item — `InventoryService#setOnHandQuantity`. */
  setOnHand(
    scope: LocationScope,
    variantId: string,
    quantity: number,
    reasonCode: string,
  ): Observable<void> {
    return this.api.put<{ quantity: number; reasonCode: string }, void>(
      operationsPaths.inventoryOnHand(scope, variantId),
      command({ quantity, reasonCode }),
    );
  }

  /** The daily reset target — null turns the scheduled reset off for this item. */
  setQuantityDefault(
    scope: LocationScope,
    variantId: string,
    defaultQuantity: number | null,
    reasonCode: string,
  ): Observable<void> {
    return this.api.put<{ defaultQuantity: number | null; reasonCode: string }, void>(
      operationsPaths.inventoryQuantityDefault(scope, variantId),
      command({ defaultQuantity, reasonCode }),
    );
  }

  /** Names the remaining quantity a channel type stops selling this item at. */
  setChannelStopThreshold(
    scope: LocationScope,
    variantId: string,
    channelType: ChannelSystemType,
    stopAtOrBelow: number,
    reasonCode: string,
  ): Observable<void> {
    return this.api.put<{ stopAtOrBelow: number; reasonCode: string }, void>(
      operationsPaths.inventoryChannelStopThreshold(scope, variantId, channelType),
      command({ stopAtOrBelow, reasonCode }),
    );
  }

  /** Removes a channel type's stop threshold — that channel goes back to selling to zero. */
  clearChannelStopThreshold(
    scope: LocationScope,
    variantId: string,
    channelType: ChannelSystemType,
    reasonCode: string,
  ): Observable<void> {
    return this.api
      .send<void, void>(
        'DELETE',
        operationsPaths.inventoryChannelStopThreshold(scope, variantId, channelType),
        command(undefined),
        { params: { reasonCode } },
      )
      .pipe(map(() => undefined));
  }
}
