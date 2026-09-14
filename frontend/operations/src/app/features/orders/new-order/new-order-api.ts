import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../../core/api/operations-paths';
import { CursorState, Page } from '../../../core/api/page';

/**
 * Types mirror `OperationsOrderController`, `CatalogAuthoringController` and
 * `StorefrontCatalogQuery`'s response records directly — the same
 * hand-copied-from-Java-source practice `customers-api.ts` and
 * `order-detail.ts` already use, for the identical reason: this is three
 * different controllers across two OpenAPI surface groups
 * (`control-plane`/`storefront` for the menu reads, the legacy tenant prefix
 * for orders), and nothing generates one shared client across all three today.
 */

// ------------------------------------------------------------- §5.3 the caller

/** `OperationsOrderController.CustomerLookupCandidateResponse`. */
export interface CustomerLookupCandidate {
  readonly accountId: string;
  readonly maskedDisplayName: string | null;
  readonly lastOrderAt: string | null;
  readonly recentOrderCount: number;
}

// -------------------------------------------------------- §5.5 menu and basket

/**
 * `CatalogAuthoringController.VariantAvailabilityResponse` — the item
 * search's result row (catalog.md §4.6's read, wave P13's new `query` param).
 * No price and no modifiers on this shape; {@link StorefrontMenu} is where
 * those live, because this read also serves the stop list at branch scale and
 * was not the read to extend with pricing data.
 */
export interface MenuItemSearchResult {
  readonly variantId: string;
  readonly productName: string | null;
  readonly category: string | null;
  readonly available: boolean;
  readonly trackingMode: string | null;
}

/** `StorefrontCatalogQuery.StorefrontMenu` (ADR 0016) — the source of truth for prices and modifier groups. */
export interface StorefrontMenu {
  readonly publicationId: string;
  readonly locale: string;
  readonly currency: string | null;
  readonly categories: readonly MenuCategory[];
  readonly products: readonly MenuProduct[];
  readonly modifierGroups: readonly MenuModifierGroup[];
}

export interface MenuCategory {
  readonly categoryId: string;
  readonly code: string;
  readonly name: string;
  readonly parentCategoryId: string | null;
  readonly sortOrder: number;
  readonly productIds: readonly string[];
}

export interface MenuProduct {
  readonly productId: string;
  readonly code: string;
  readonly name: string;
  readonly description: string | null;
  readonly imageUrls: readonly string[];
  readonly variants: readonly MenuVariant[];
  readonly modifierGroupIds: readonly string[];
}

/** @property amountMinor null means unpriced — see `new-order-total.ts`'s own doc for why that is never treated as zero. */
export interface MenuVariant {
  readonly variantId: string;
  readonly sku: string | null;
  readonly unitCode: string | null;
  readonly isDefault: boolean;
  readonly orderable: boolean;
  readonly amountMinor: number | null;
}

export interface MenuModifierGroup {
  readonly modifierGroupId: string;
  readonly code: string;
  readonly name: string;
  readonly required: boolean;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameOptionMultipleTimes: boolean;
  readonly options: readonly MenuModifierOption[];
}

export interface MenuModifierOption {
  readonly optionId: string;
  readonly code: string;
  readonly maximumQuantity: number;
  readonly amountMinor: number | null;
}

// ------------------------------------------------------------ §5.6 placing it

export interface PlaceOrderLine {
  readonly variantId: string;
  readonly quantity: number;
  readonly modifierOptionIds: readonly string[];
  readonly customerNote?: string | null;
}

/**
 * `OperationsOrderController.DestinationRequest` — where a `DELIVERY` order
 * goes, one of the resolved customer's own saved addresses, named by id,
 * never typed ad hoc (row 1.3b).
 */
export interface DestinationRequest {
  readonly customerAddressId: string;
  readonly recipientName: string;
  readonly recipientPhone: string;
  readonly deliveryNote?: string | null;
}

/**
 * `OperationsOrderController.PlaceOrderRequest`. `paymentMethodCode` is
 * checked against the operator channel's own matrix, never hard-coded to
 * cash (wave P14; see `OperatorOrderingService`'s own doc).
 */
export interface PlaceOrderRequest {
  readonly customerAccountId: string;
  readonly channelCode: string;
  readonly fulfillmentMode: string;
  readonly lines: readonly PlaceOrderLine[];
  readonly destination?: DestinationRequest | null;
  readonly paymentMethodCode: string;
  readonly promoCode?: string | null;
}

/** `OperationsOrderController.PlaceOrderResponse`. */
export interface PlaceOrderResult {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly status: string;
  readonly version: number;
  readonly outcome: string;
  readonly warnings: readonly string[];
}

/**
 * New order screen (wave P13, orders.md §5): the phone lookup, the item
 * search, the published menu that prices what the search finds, and the
 * place-order call itself.
 *
 * Kept apart from `order-actions-api.ts`: that file mutates an order that
 * already exists, and every call there carries `expectedVersion`/`If-Match`
 * against one. Nothing here does, because {@link placeOrder} creates the
 * first version of an order that does not exist yet.
 */
@Injectable({ providedIn: 'root' })
export class NewOrderApi {
  private readonly api = inject(ApiClient);

  /** orders.md §5.3: a `POST` with the phone in the body, never a query string. */
  async lookupCustomerByPhone(
    scope: LocationScope,
    phone: string,
  ): Promise<readonly CustomerLookupCandidate[]> {
    const result = await firstValueFrom(
      this.api.post<{ phone: string }, { candidates: readonly CustomerLookupCandidate[] }>(
        operationsPaths.orderCustomerLookups(scope),
        command({ phone }),
      ),
    );
    return result.candidates;
  }

  /**
   * orders.md §5.5's item search — `CatalogAuthoringController.variantsAtLocation`,
   * this wave's new `query` parameter, so the picker can search a catalog of
   * thousands rather than paging through it. Carries no price or modifiers;
   * {@link menu} supplies those for whichever variant the operator picks.
   */
  searchItems(
    scope: LocationScope,
    state: CursorState,
    query: string,
    locale: string,
  ): Promise<Page<MenuItemSearchResult>> {
    return firstValueFrom(
      this.api.page<MenuItemSearchResult>(
        catalogPaths.variantsAtLocation(toBrandScope(scope), scope.locationId),
        state,
        { locale, query: query.trim() === '' ? undefined : query.trim() },
      ),
    );
  }

  /**
   * The full published menu (`StorefrontCatalogController`, ADR 0016) — this
   * screen's only source of price and modifier data, and the category grid's
   * data source for a caller who is browsing aloud rather than naming a dish
   * (orders.md §5.5). `channelCode` is the tenant's operator channel; see
   * `new-order-page.ts`'s own doc for how that is resolved.
   */
  async menu(scope: LocationScope, channelCode: string, locale: string): Promise<StorefrontMenu> {
    const result = await firstValueFrom(
      this.api.get<StorefrontMenu>(
        catalogPaths.storefrontMenu(toBrandScope(scope), scope.locationId),
        {
          params: { locale, channel: channelCode },
        },
      ),
    );
    return result.value;
  }

  /**
   * orders.md §5.6's Создать — `POST .../orders`, `ORDER_PLACE` at `LOCATION`
   * scope. Reuses `CartService` + `CheckoutService` end to end
   * (`OperatorOrderingService`): pricing, availability and the quote are all
   * computed and accepted inside this one call, so a rejection (an item that
   * went out of stock between the search and the click, say) comes back as
   * an `ApiError` naming what changed rather than a silent wrong total.
   */
  placeOrder(scope: LocationScope, request: PlaceOrderRequest): Promise<PlaceOrderResult> {
    return firstValueFrom(
      this.api.post<PlaceOrderRequest, PlaceOrderResult>(
        operationsPaths.orders(scope),
        command(request),
      ),
    );
  }

  /**
   * Row 1.3g (ADR 0040) — records an aggregator's own order under its
   * `AGGREGATOR`-type channel, with externally-set pricing, never run
   * through the ordinary cart/quote pipeline {@link placeOrder} uses.
   */
  aggregatorEntry(
    scope: LocationScope,
    request: AggregatorOrderRequest,
  ): Promise<PlaceOrderResult> {
    return firstValueFrom(
      this.api.post<AggregatorOrderRequest, PlaceOrderResult>(
        operationsPaths.orderAggregatorEntries(scope),
        command(request),
      ),
    );
  }

  /**
   * ADR 0064: links the order this call just placed to the claimed
   * screen-pop card that started it (`OperationsOrderController
   * .recordCallProvenance`), write-once server-side. `new-order-page.ts`'s
   * own doc explains where `callEventId` comes from — this had zero callers
   * anywhere before wave W01, so no phone order's provenance was ever
   * recorded and every operator KPI that depends on the join lost it.
   */
  recordCallProvenance(scope: LocationScope, orderId: string, callEventId: string): Promise<void> {
    return firstValueFrom(
      this.api.post<{ callId: string }, void>(
        operationsPaths.orderCallProvenance(scope, orderId),
        command({ callId: callEventId }),
      ),
    );
  }
}

// ---------------------------------------------------------- §5.6 aggregator entry (1.3g)

/** `OperationsOrderController.AggregatorOrderLineRequest`. */
export interface AggregatorOrderLine {
  readonly variantId?: string | null;
  readonly nameSnapshot: string;
  readonly quantity: number;
  readonly unitAmountMinor: number;
  readonly externalItemReference?: string | null;
}

/** `OperationsOrderController.AggregatorOrderRequest`. */
export interface AggregatorOrderRequest {
  readonly channelCode: string;
  readonly externalOrderId: string;
  readonly lines: readonly AggregatorOrderLine[];
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly discountMinor: number;
  readonly feeMinor: number;
  readonly totalMinor: number;
}

function toBrandScope(scope: LocationScope): BrandScope {
  return { tenantId: scope.tenantId, brandId: scope.brandId };
}
