import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../config/app-config';
import { ApiClient, versionFromETag } from './api-client';
import type { Money } from '../money/money';

/**
 * The storefront surface, as `StorefrontCatalogController` and
 * `StorefrontOrderingController` actually serve it today.
 *
 * These types are hand-written and that is a known defect. ADR 0031 requires
 * typed clients generated from the OpenAPI document and ADR 0035 requires the
 * generated output to be committed and CI to fail if regeneration produces a
 * diff. Neither the published document artifact nor the generator exists yet, so
 * what is here is a faithful transcription of two controllers on one day.
 * Replace the whole file with generated output; do not extend it by hand.
 *
 * Two things in these shapes are worth noticing before building a screen on
 * them:
 *
 * - The ordering controller returns money as loose `*Minor` longs beside a
 *   `currency` string, not as ADR 0031's `{amountMinor, currency}` object. The
 *   conversion happens here, at the edge, so nothing above this file ever holds
 *   a bare number that means money.
 * - A cart line reports `hasCustomerNote`, never the note. The text is personal
 *   data and is only revealed through an endpoint that records a purpose for it.
 */
@Injectable({ providedIn: 'root' })
export class StorefrontApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /**
   * The published menu for one location.
   *
   * Unauthenticated by design, and cached by the server for thirty seconds with
   * the publication id as its ETag, so it changes the moment a location's
   * availability does.
   */
  menu(locationId: string, locale: string): Promise<StorefrontMenu> {
    return this.api.get<StorefrontMenu>(
      `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/locations/${locationId}/menu`,
      { query: { locale }, anonymous: true },
    );
  }

  createCart(input: {
    locationId: string;
    fulfillmentMode: FulfillmentMode;
    channel?: string;
    idempotencyKey?: string;
  }): Promise<CartResponse> {
    return this.api.mutate<CartResponse>('POST', `${this.brandPath}/carts`, {
      body: {
        locationId: input.locationId,
        channel: input.channel ?? this.config.channel,
        fulfillmentMode: input.fulfillmentMode,
      },
      idempotencyKey: input.idempotencyKey,
    });
  }

  readCart(cartId: string): Promise<CartResponse> {
    return this.api.get<CartResponse>(`${this.brandPath}/carts/${cartId}`);
  }

  putLine(input: {
    cartId: string;
    lineKey: string;
    expectedVersion: number;
    variantId: string;
    quantity: number;
    modifierOptionIds?: readonly string[];
    customerNote?: string;
    idempotencyKey?: string;
  }): Promise<CartResponse> {
    return this.api.mutate<CartResponse>(
      'PUT',
      `${this.brandPath}/carts/${input.cartId}/lines/${encodeURIComponent(input.lineKey)}`,
      {
        body: {
          variantId: input.variantId,
          quantity: input.quantity,
          modifierOptionIds: input.modifierOptionIds ?? [],
          customerNote: input.customerNote,
        },
        expectedVersion: input.expectedVersion,
        idempotencyKey: input.idempotencyKey,
      },
    );
  }

  /**
   * Prices the cart and binds the quote to it.
   *
   * Checkout accepts only this quote for this cart, so the returned
   * `contextHash` must be carried through unchanged; a client cannot present a
   * quote priced for a different, cheaper basket.
   */
  priceCart(cartId: string, expectedVersion: number): Promise<PricedCartResponse> {
    return this.api.mutate<PricedCartResponse>(
      'POST',
      `${this.brandPath}/carts/${cartId}/pricing`,
      {
        expectedVersion,
      },
    );
  }

  /**
   * @param idempotencyKey required, and required to be the *same* key on a
   *        retry. Repeating the request returns the same order; a settled
   *        business rejection returns the same rejection rather than running
   *        again against a cart that has since changed.
   */
  checkout(input: {
    cartId: string;
    cartVersion: number;
    quoteId: string;
    contextHash: string;
    paymentMethodCode?: string;
    idempotencyKey: string;
  }): Promise<CheckoutResponse> {
    return this.api.mutate<CheckoutResponse>('POST', `${this.brandPath}/checkouts`, {
      body: {
        cartId: input.cartId,
        cartVersion: input.cartVersion,
        quoteId: input.quoteId,
        contextHash: input.contextHash,
        paymentMethodCode: input.paymentMethodCode,
      },
      idempotencyKey: input.idempotencyKey,
    });
  }

  readOrder(orderId: string): Promise<OrderResponse> {
    return this.api.get<OrderResponse>(`${this.brandPath}/orders/${orderId}`);
  }
}

export type FulfillmentMode = 'DELIVERY' | 'PICKUP' | 'DINE_IN' | (string & {});

export interface StorefrontMenu {
  readonly publicationId: string;
  readonly [key: string]: unknown;
}

export interface CartLineResponse {
  readonly lineKey: string;
  readonly variantId: string;
  readonly quantity: number;
  /** Whether a note exists. Never the note itself. */
  readonly hasCustomerNote: boolean;
}

export interface CartResponse {
  readonly cartId: string;
  readonly locationId: string;
  readonly status: string;
  readonly currency: string;
  /** The server's fact, not the client's guess. See CartStore.adopt. */
  readonly fulfillmentMode: FulfillmentMode;
  readonly version: number;
  readonly quoteId: string | null;
  readonly contextHash: string | null;
  readonly expiresAt: string | null;
  readonly lines: readonly CartLineResponse[];
}

export interface PricedCartResponse {
  readonly cartId: string;
  readonly cartVersion: number;
  readonly quoteId: string;
  readonly contextHash: string;
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly taxMinor: number;
  readonly totalMinor: number;
  readonly expiresAt: string;
}

export interface CheckoutResponse {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly status: string;
  readonly version: number;
  readonly outcome: 'ACCEPTED' | 'REPLAYED' | 'REJECTED' | (string & {});
  /** Platform gaps that apply to this order, such as an unwired payments port. */
  readonly warnings: readonly string[];
}

export interface OrderLineResponse {
  readonly lineNumber: number;
  readonly productName: string;
  readonly variantName: string;
  readonly quantity: number;
  readonly unitAmountMinor: number;
  readonly finalAmountMinor: number;
  readonly modifiers: readonly string[];
}

export interface OrderResponse {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly status: string;
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly taxMinor: number;
  readonly totalMinor: number;
  readonly version: number;
  readonly createdAt: string;
  readonly confirmedAt: string | null;
  readonly lines: readonly OrderLineResponse[];
  readonly warnings: readonly string[];
}

/** The edge where loose minor units become money that carries its currency. */
export function orderTotal(order: OrderResponse | PricedCartResponse): Money {
  return { amountMinor: order.totalMinor, currency: order.currency };
}

export { versionFromETag };
