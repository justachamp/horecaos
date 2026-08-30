import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';
import { LangService } from './lang.service';

/**
 * The online-payment handoff: opening a Click or Payme checkout for an order
 * that was just created with one of those payment methods.
 *
 * `POST /orders/{orderId}/payment-sessions` (`StorefrontPaymentController`) is a
 * separate call from checkout on purpose -- checkout creates the order and this
 * opens (or re-opens) the one payable attempt against it, so a customer who
 * abandons the checkout page and comes back is handed the same attempt rather
 * than a second charge.
 */
@Injectable({ providedIn: 'root' })
export class PaymentSessionService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly lang = inject(LangService);

  /** Payment method codes that hand the customer off to a provider checkout. */
  private static readonly ONLINE_METHODS: ReadonlySet<string> = new Set(['CLICK', 'PAYME']);

  static requiresOnlineSession(paymentMethodCode: string): boolean {
    return PaymentSessionService.ONLINE_METHODS.has(paymentMethodCode);
  }

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /**
   * Opens the payment attempt for an order and returns where to send the
   * customer's browser.
   *
   * `returnUrl` must satisfy the platform's own `^https://` validation. That is
   * only ever true when this storefront is itself served over https -- a local
   * `ng serve` on `http://localhost` cannot complete this round trip, which is
   * why local development uses CASH (see the README's "Online payments in local
   * development" section). The call is still made rather than pre-empted here:
   * a clear `VALIDATION_FAILED` from the platform is a more honest failure than
   * a client-side guess about which origins count as "really https".
   */
  async open(orderId: string): Promise<PaymentSessionResponse> {
    return this.api.mutate<PaymentSessionResponse>(
      'POST',
      `${this.brandPath}/orders/${orderId}/payment-sessions`,
      {
        body: {
          returnUrl: this.returnUrl(orderId),
          language: this.lang.langId(),
          presentation: 'PAYMENT_LINK',
        },
        idempotencyKey: newIdempotencyKey(),
      },
    );
  }

  /**
   * Where Click sends the browser back after the customer pays or cancels.
   *
   * Derived from `window.location.origin` rather than `APP_CONFIG`, which has
   * no notion of a public URL -- see `app-config.ts`. Payme ignores this and
   * takes its return address from the `Referer` header instead, per the
   * platform's own doc comment on `PaymentSessionRequest`.
   */
  private returnUrl(orderId: string): string {
    const origin = typeof window !== 'undefined' ? window.location.origin : '';
    return `${origin}/cart/payment-return/${orderId}`;
  }
}

export interface PaymentSessionResponse {
  readonly attemptId: string;
  readonly merchantTransId: string;
  readonly provider: string;
  readonly presentation: string;
  /** Where to send the browser, or null for a push-only presentation. */
  readonly checkoutUrl: string | null;
  readonly qrPayload: string | null;
  readonly expiresAt: string;
  readonly amountMinor: number;
  readonly currency: string;
  readonly rePresented: boolean;
  readonly presentationCount: number;
}
