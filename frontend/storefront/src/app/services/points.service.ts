import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { HorecaOSApiError } from '../core/api/problem-details';
import type { Money } from '../core/money/money';

/**
 * What the customer may spend of their points on one order (ADR 0046, ADR 0185).
 * `maximum` is zero exactly when `redeemable` is false, and `refusal` then says why
 * (`NO_ACCOUNT`, `REDEMPTION_NOT_ENABLED`, `CHANNEL_NOT_ELIGIBLE`, `ACCOUNT_NOT_ACTIVE`,
 * `ORDER_NOT_ELIGIBLE`, `INSUFFICIENT_BALANCE`, `REDEMPTION_BLOCKED_BY_PROMOTION`,
 * and, for a cart, `CART_NOT_PRICED`, `QUOTE_EXPIRED` or `SIGN_IN_REQUIRED`).
 */
export interface PointsOffer {
  readonly accountId: string | null;
  readonly available: Money;
  readonly maximum: Money;
  readonly redeemable: boolean;
  readonly refusal: string | null;
}

/** `StorefrontRedemptionController.CartRedemptionOfferResponse`. */
export interface CartPointsOffer {
  readonly cartId: string;
  /** Whether the cart has a live quote to offer against. */
  readonly priced: boolean;
  /** The figure the order would be placed at; the cash still owed is this less the points spent. */
  readonly orderTotal: Money;
  readonly offer: PointsOffer;
}

export type TenderStatus = 'PLANNED' | 'RESERVED' | 'SETTLED' | 'RELEASED' | 'REVERSED' | 'FAILED';

/** `StorefrontPaymentSplitController.TenderResponse`. */
export interface PaymentTender {
  readonly kind: 'POINTS' | 'MONEY';
  /** The payment method of a `MONEY` tender (`CASH`, `CLICK`, `PAYME`); null for points. */
  readonly methodCode: string | null;
  readonly amount: Money;
  readonly status: TenderStatus;
}

/** `StorefrontPaymentSplitController.PaymentSplitResponse`. */
export interface PaymentSplit {
  readonly orderId: string;
  readonly total: Money;
  /** In the order they settle: the points first. */
  readonly tenders: readonly PaymentTender[];
}

/**
 * The customer's points, as the checkout and the order page need them (ADR 0185).
 *
 * Both reads are conveniences on top of ordering, never a gate in front of it, so both are
 * `quiet`: a failure is returned to the caller, which decides what a missing figure means, and is
 * never shown as a toast over a screen that works without it.
 */
@Injectable({ providedIn: 'root' })
export class PointsService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /**
   * What this cart's customer may spend, read from the quote the cart is bound to, so the
   * maximum is the figure checkout will accept. Nothing is held: the points are spent by sending
   * the amount with the checkout.
   */
  redemptionOffer(cartId: string): Promise<CartPointsOffer> {
    return this.api.get<CartPointsOffer>(`${this.brandPath}/carts/${cartId}/redemption-offer`, {
      quiet: true,
    });
  }

  /**
   * How one of the caller's own orders is being paid. An order with no settlement to show answers
   * 404, which is "nothing to show" and not a failure, so it is `null`; anything else rejects.
   */
  async paymentSplit(orderId: string): Promise<PaymentSplit | null> {
    try {
      return await this.api.get<PaymentSplit>(`${this.brandPath}/orders/${orderId}/payment-split`, {
        quiet: true,
      });
    } catch (failure) {
      if (failure instanceof HorecaOSApiError && failure.status === 404) {
        return null;
      }
      throw failure;
    }
  }
}
