import { Injectable, inject } from '@angular/core';
import { firstValueFrom, tap } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../../core/api/catalog-paths';
import { IntentCommandRegistry, command } from '../../../core/api/idempotency';
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

/**
 * Row 1.3a: `OperationsCustomerController.CreateCustomerRequest`. Distinct
 * from `customers-api.ts`'s own `CreateCustomerRequest` — that one carries
 * `brandId` in the body because `CustomersApi.create` posts to the
 * tenant-scoped `CustomerController`; this one's `brandId` is already in the
 * URL, because `LOCATION_STAFF`/`LOCATION_MANAGER` can only ever reach a
 * location-scoped path (see `OperationsCustomerController`'s own doc).
 */
export interface CreateCustomerRequest {
  readonly phone: string;
  readonly displayName?: string | null;
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
  /** Row 2.1b: the coded kitchen-instruction presets this product offers on a line, in display order. */
  readonly commentPresets: readonly CommentPresetOption[];
}

/**
 * Row 2.1b: one preset a product offers, every locale so the screen renders
 * its own — matches `StorefrontCatalogQuery.CommentPresetOption`.
 */
export interface CommentPresetOption {
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
}

/**
 * @property amountMinor null means unpriced — see `new-order-total.ts`'s own doc for why that is never treated as zero.
 * @property onSaleNow row 4.2g: false means this variant's own sale schedule excludes the current moment — shown, but the New Order screen must refuse adding it, distinct from `orderable` (86'd).
 */
export interface MenuVariant {
  readonly variantId: string;
  readonly sku: string | null;
  readonly unitCode: string | null;
  readonly isDefault: boolean;
  readonly orderable: boolean;
  readonly onSaleNow: boolean;
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
  /** Row 2.1b: the coded presets the operator picked from the product's own offered subset. */
  readonly commentPresetCodes: readonly string[];
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
 *
 * @property requestedFor row 1.3d: an ISO instant to promise instead of now,
 *   or absent for an ordinary immediate order.
 * @property overrideOutOfHours row 1.3d: true once the operator has already
 *   been shown `BRANCH_CLOSED_AT_REQUESTED_TIME_CONFIRM` and chosen to place
 *   the order anyway. Sending it on the first attempt is harmless — the
 *   backend only reads it when the branch turns out to be closed at
 *   `requestedFor` — but `new-order-page.ts` only sets it true on the
 *   confirmed resubmit, so a stray false-positive warning is never silently
 *   skipped.
 */
export interface PlaceOrderRequest {
  readonly customerAccountId: string;
  readonly channelCode: string;
  readonly fulfillmentMode: string;
  readonly lines: readonly PlaceOrderLine[];
  readonly destination?: DestinationRequest | null;
  readonly paymentMethodCode: string;
  readonly promoCode?: string | null;
  readonly requestedFor?: string | null;
  readonly overrideOutOfHours?: boolean;
}

// -------------------------------------------------------- §5.6 delivery fee preview

/**
 * `DeliveryFeeController.DeliveryFeeView`, the fields the composer's running
 * total needs — the rest (`minBasketMinor`, `distanceMeters`, evidence-shaped
 * fields) belong to the storefront's own fuller cart display, not a phone
 * order's one-line estimate.
 */
export interface DeliveryFeeQuote {
  readonly available: boolean;
  readonly feeMinor: number | null;
  readonly reasonCode: string | null;
}

/**
 * `DeliveryFeeController.DeliveryFeeQuoteRequest` — the point and basket to
 * price delivery for, sent as the POST body since 2026-09-21 (audit
 * follow-up (b)): the point used to be a query-string parameter, which put a
 * customer's coordinate in the URL (ADR 0029). See {@link
 * NewOrderApi.deliveryFeeQuote}.
 */
interface DeliveryFeeQuoteRequest {
  readonly lat: number;
  readonly lon: number;
  readonly currency: string;
  readonly subtotalMinor: number;
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
 * first version of an order that does not exist yet — which makes this the
 * critic's named case for HK: with no `expectedVersion` to make a stale
 * retry fail loudly, a fresh `Idempotency-Key` per call was the only thing
 * standing between a lost response and a second, independent order for the
 * same basket — and the identical shape applies to {@link createCustomer}'s
 * create-on-miss, which likewise inserts unconditionally with no natural
 * conflict to reject a duplicate. {@link placeOrder}, {@link aggregatorEntry}
 * and {@link createCustomer} each hold their own `IntentCommandRegistry`
 * (this service is `providedIn: 'root'`, so it outlives the component across
 * the lifetime of one operator's draft): a retried call with an unchanged
 * request body reuses the held key, a body that differs (the operator edited
 * the form) mints a fresh one, and a successful response forgets the held
 * command so the next order — even an
 * accidental repeat of the same basket — gets its own key.
 */
@Injectable({ providedIn: 'root' })
export class NewOrderApi {
  private readonly api = inject(ApiClient);
  private readonly placeOrderIntents = new IntentCommandRegistry<PlaceOrderRequest>();
  private readonly aggregatorEntryIntents = new IntentCommandRegistry<AggregatorOrderRequest>();
  private readonly createCustomerIntents = new IntentCommandRegistry<CreateCustomerRequest>();

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
    const intent = this.placeOrderIntents.next('draft', request);
    return firstValueFrom(
      this.api
        .post<PlaceOrderRequest, PlaceOrderResult>(operationsPaths.orders(scope), intent)
        .pipe(tap(() => this.placeOrderIntents.forget('draft'))),
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
    const intent = this.aggregatorEntryIntents.next('draft', request);
    return firstValueFrom(
      this.api
        .post<AggregatorOrderRequest, PlaceOrderResult>(
          operationsPaths.orderAggregatorEntries(scope),
          intent,
        )
        .pipe(tap(() => this.aggregatorEntryIntents.forget('draft'))),
    );
  }

  /**
   * Row 1.3a: create-on-miss, through the location-scoped endpoint
   * `LOCATION_STAFF`/`LOCATION_MANAGER` can actually reach —
   * `OperationsCustomerController#create`, not `CustomersApi.create`, which
   * 403s for this screen's own persona. See that controller's own doc.
   *
   * Held on {@link createCustomerIntents} like {@link placeOrder} and {@link
   * aggregatorEntry} above, for the identical reason (batch 8 review 2,
   * finding b-new-order): `CustomerIdentityService.createAccountWithoutPrincipal`
   * inserts unconditionally, with no phone-uniqueness check, so a retry that
   * minted a fresh `Idempotency-Key` per call created a second, duplicate
   * customer account for the same phone whenever the first response was lost.
   */
  async createCustomer(scope: LocationScope, request: CreateCustomerRequest): Promise<string> {
    const intent = this.createCustomerIntents.next('draft', request);
    const response = await firstValueFrom(
      this.api
        .post<CreateCustomerRequest, { id: string }>(
          operationsPaths.orderIntakeCustomers(scope),
          intent,
        )
        .pipe(tap(() => this.createCustomerIntents.forget('draft'))),
    );
    return response.id;
  }

  /**
   * Row 1.3's delivery fee estimate — the same unauthenticated preview
   * `ui-cart.service.ts`'s own `refreshDeliveryFee` calls for the storefront's
   * cart, reused here rather than a second delivery-fee client. Best effort
   * and never bound to the order: the fee this shows is a preview for the
   * caller on the phone, and the fee that actually settles is resolved fresh,
   * inside the checkout transaction, from the destination
   * `OperatorOrderingService.place` sets — not from this read.
   *
   * `POST` with the point in the body since 2026-09-21 (audit follow-up
   * (b)): `DeliveryFeeController.quote` no longer maps `GET`, and a query
   * string would put the customer's coordinate on the wire and in browser
   * history (ADR 0029). The endpoint writes nothing and needs neither a
   * capability nor a stable `Idempotency-Key` (see its own doc), so a fresh
   * key per call via the plain {@link command} helper is correct here, unlike
   * {@link createCustomer} above.
   */
  async deliveryFeeQuote(
    scope: LocationScope,
    point: { lat: number; lon: number },
    currency: string,
    subtotalMinor: number,
  ): Promise<DeliveryFeeQuote> {
    const result = await firstValueFrom(
      this.api.post<
        DeliveryFeeQuoteRequest,
        {
          outcome: string;
          reasonCode: string | null;
          available: boolean;
          feeMinor: number | null;
          currency: string;
        }
      >(
        catalogPaths.deliveryFee(toBrandScope(scope), scope.locationId),
        command({ lat: point.lat, lon: point.lon, currency, subtotalMinor }),
      ),
    );
    return {
      available: result.available,
      feeMinor: result.feeMinor,
      reasonCode: result.reasonCode,
    };
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

/**
 * `OperationsOrderController.AggregatorOrderRequest`.
 *
 * @property fulfillmentMode row 1.3g (wave 11 w5-fulfillment-destination):
 *   `'PICKUP'` (the default when omitted) or `'DELIVERY'`.
 * @property customerAccountId required, and only meaningful, when
 *   `fulfillmentMode` is `'DELIVERY'`: whose saved address `destination`
 *   names — the order itself still matches no customer account.
 * @property destination required exactly when `fulfillmentMode` is
 *   `'DELIVERY'` — the identical {@link DestinationRequest} shape the
 *   address pane above already sends for a native order, reused rather
 *   than a second, untyped address path.
 */
export interface AggregatorOrderRequest {
  readonly channelCode: string;
  readonly externalOrderId: string;
  readonly lines: readonly AggregatorOrderLine[];
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly discountMinor: number;
  readonly feeMinor: number;
  readonly totalMinor: number;
  readonly fulfillmentMode?: string | null;
  readonly customerAccountId?: string | null;
  readonly destination?: DestinationRequest | null;
}

function toBrandScope(scope: LocationScope): BrandScope {
  return { tenantId: scope.tenantId, brandId: scope.brandId };
}
