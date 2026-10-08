import { Injectable, inject } from '@angular/core';
import { Observable, map, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry, command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { I18n } from '../../core/i18n/i18n';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { toCatalogLocale } from './catalog-domain';

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

/**
 * `InventoryController.UnlistedOfferingResponse` — one dish a location offers
 * `AVAILABLE` that inventory has never listed, so it reads as unavailable
 * (`NOT_STOCKED_AT_LOCATION`) whatever the tenant's stock-logic setting.
 */
export interface UnlistedOffering {
  readonly variantId: string;
  readonly productName: string;
  readonly variantName?: string | null;
  readonly sku?: string | null;
}

/**
 * `InventoryController.UnlistedOfferingsResponse` — the stock page's report
 * (gap map row 4.4c). `totalCount` is the whole backlog; `items` may be a
 * page of it (`hasMore`).
 */
export interface UnlistedOfferingsReport {
  readonly totalCount: number;
  readonly hasMore: boolean;
  readonly items: readonly UnlistedOffering[];
}

/** `InventoryController.BackfillResponse` — what one location-wide list-all call did. */
export interface LocationBackfillResult {
  readonly candidateCount: number;
  readonly listedCount: number;
  /** The call hit its per-call cap; the location may still have more — call again. */
  readonly mayHaveMore: boolean;
}

/** `InventoryVariantListingController.BackfillResponse` — what one per-variant list-everywhere call did. */
export interface VariantBackfillResult {
  readonly candidateCount: number;
  readonly listedCount: number;
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
 * `GET/POST/PUT .../inventory/**` — `InventoryController` (waves 6/24). Never
 * moved onto the ADR 0031 `/api/v1/operations/**` prefix, unlike
 * `CatalogApi`/`PricingApi` — every path here is built on
 * {@link operationsPaths}'s own {@code LEGACY_TENANT_PREFIX} (see that
 * builder's own class doc on its `inventory*` group). The audited stop/86
 * toggle catalog.md §4.2 tab 6 and §4.6 both read.
 */
@Injectable({ providedIn: 'root' })
export class InventoryApi {
  private readonly api = inject(ApiClient);
  private readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  /**
   * One `Idempotency-Key` per operator intent (ADR 0031) for the two
   * list-everything actions. Both are the "click the button again" shape: a
   * lost response leaves the operator unsure whether it landed, and a fresh
   * key on the retry would re-run the whole backfill instead of replaying the
   * stored answer. So a retry of the same action reuses its key, and a
   * success forgets it — whatever is clicked next is a new intent (and for a
   * location with more than one call's worth of backlog, the next call must
   * run, not replay page one).
   */
  private readonly listAllIntents = new IntentCommandRegistry<undefined>();

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
      .get<AvailabilityDecision>(operationsPaths.inventoryAvailability(scope), {
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

  /**
   * The stock page's "unlisted offered dishes" report (gap map row 4.4c):
   * which offered variants at this location have no inventory listing, with
   * the exact backlog size. Also the runbook's dry run.
   *
   * Names come back in the operator's own console language: the endpoint
   * resolves them in `locale` and defaults to `uz` when it is omitted, so
   * without it a Russian-console operator would be shown Uzbek names.
   */
  unlistedOfferings(scope: LocationScope): Observable<UnlistedOfferingsReport> {
    return this.api
      .get<UnlistedOfferingsReport>(operationsPaths.inventoryUnlistedOfferings(scope), {
        params: { locale: toCatalogLocale(this.i18n.locale(), this.registry) },
      })
      .pipe(map((result) => result.value));
  }

  /**
   * The stock page's bulk "list all" — `POST .../inventory/listing-backfill`,
   * the idempotent backfill endpoint. Lists up to one call's cap; check
   * `mayHaveMore` and call again for a bigger backlog.
   */
  backfillLocationListing(scope: LocationScope): Observable<LocationBackfillResult> {
    const id = `location:${scope.tenantId}:${scope.brandId}:${scope.locationId}`;
    return this.api
      .post<undefined, LocationBackfillResult>(
        operationsPaths.inventoryListingBackfill(scope),
        this.listAllIntents.next(id, undefined),
      )
      .pipe(tap(() => this.listAllIntents.forget(id)));
  }

  /**
   * The product editor's own "not listed at N branches" read (gap map row
   * 4.1) — `InventoryVariantListingController`, brand-scoped: every branch
   * offering this variant AVAILABLE that has never listed it.
   */
  unlistedLocations(scope: LocationScope, variantId: string): Observable<readonly string[]> {
    return this.api
      .get<{ locationIds: readonly string[] }>(
        operationsPaths.inventoryVariantListing(scope, variantId),
      )
      .pipe(map((result) => result.value.locationIds));
  }

  /**
   * The Availability tab's own one-click action behind {@link unlistedLocations}'s
   * banner: lists this variant at every branch it names. Idempotent — a
   * branch already listed, or one that deliberately marked this variant sold
   * out, is left exactly as it is.
   */
  backfillVariantListing(
    scope: LocationScope,
    variantId: string,
  ): Observable<VariantBackfillResult> {
    const id = `variant:${scope.tenantId}:${scope.brandId}:${variantId}`;
    return this.api
      .post<undefined, VariantBackfillResult>(
        operationsPaths.inventoryVariantListingBackfill(scope, variantId),
        this.listAllIntents.next(id, undefined),
      )
      .pipe(tap(() => this.listAllIntents.forget(id)));
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
