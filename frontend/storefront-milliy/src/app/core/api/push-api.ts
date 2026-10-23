import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../config/app-config';
import { ApiClient } from './api-client';

/**
 * A customer's own Web Push opt-in (ADR 0182, D14), a faithful transcription of
 * `StorefrontPushSubscriptionController`, built directly on {@link ApiClient} the way
 * `TelegramLinkApi` and `CustomerApi` are rather than on the generated client: the controller is the
 * contract, and this app has already been burned once by a generated shape that quietly meant something
 * else because two operations shared a class name.
 *
 * Under the same `/storefront/tenants/{tenantId}/brands/{brandId}` root as the customer's other own
 * surfaces. **The account is never in a path**: the server resolves it from the caller's verified
 * session, and a guest -- signed in, with no account at this brand yet -- is answered not-found rather
 * than forbidden.
 */
@Injectable({ providedIn: 'root' })
export class PushApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get pushPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/push`;
  }

  /**
   * Whether push can be offered at all (the platform's sending identity is set up), the key the browser
   * must subscribe under, and whether this customer has a live subscription at this brand.
   */
  status(): Promise<PushStatus> {
    return this.api.get<PushStatus>(`${this.pushPath}`);
  }

  /**
   * Turns push on: the browser's own `PushSubscription.toJSON()`, unchanged. Replaces the customer's
   * earlier subscription, and records their consent to promotional push in the same step. A 400 says why
   * the subscription is not one the platform will send to, in a bounded code.
   */
  async subscribe(subscription: PushSubscriptionBody, idempotencyKey?: string): Promise<void> {
    await this.api.mutate<void, PushSubscriptionBody>('PUT', `${this.pushPath}/subscription`, {
      body: subscription,
      idempotencyKey,
    });
  }

  /** Turns push off and withdraws the consent. 204 whether or not it was on. */
  async unsubscribe(idempotencyKey?: string): Promise<void> {
    await this.api.mutate<void>('DELETE', `${this.pushPath}/subscription`, { idempotencyKey });
  }
}

/** `PushStatusResponse`, from `StorefrontPushSubscriptionController`. */
export interface PushStatus {
  /** False until the platform can send push at all; the prompt is not shown then. */
  readonly available: boolean;
  /** The VAPID application server key, base64url; null when `available` is false. */
  readonly publicKey: string | null;
  readonly subscribed: boolean;
}

/** A browser's `PushSubscription.toJSON()`, which the controller takes as it is. */
export interface PushSubscriptionBody {
  readonly endpoint: string;
  readonly expirationTime?: number | null;
  readonly keys: { readonly p256dh: string; readonly auth: string };
}
