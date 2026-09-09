import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';

/**
 * A customer's own referral code, and redeeming a friend's (ADR 0067),
 * against `ReferralStorefrontController`.
 *
 * <p>Two calls only, matching the two things `ReferralStorefrontController`
 * actually exposes to a customer principal:
 *
 * <ul>
 *   <li>{@link myReferral} -- the caller's own code (minted on first read,
 *   never requiring a separate create step) and, if this account has ever
 *   redeemed a friend's code, that redemption's own status.
 *   <li>{@link redeem} -- spends a friend's code once. Refused when the code
 *   does not exist, is the caller's own, or this account already holds a
 *   redemption -- see {@link ReferralComponent} for how each is surfaced.
 * </ul>
 *
 * <p><strong>What this does not, and cannot, expose.</strong> The wire
 * response carries a `code` and a `status`/dates -- no reward amount, no
 * currency, and (correctly, per ADR 0029) no hint of who the referrer or
 * referee is beyond the caller's own account. There is also no read here for
 * "how many friends used my code" -- `ReferralQueryService` only ever
 * resolves a redemption *by referee*, the same shape
 * `ReferralStorefrontController.myReferral` calls, and the only read of a
 * redemption *by referrer* lives on the marketer-only
 * `ReferralOperationsController` behind `REFERRAL_READ`, a capability no
 * customer principal can hold. `ReferralComponent` shows exactly what this
 * service can honestly return and nothing invented on top of it.
 */
@Injectable({ providedIn: 'root' })
export class ReferralService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /** The caller's own code, and their own redemption if they have used one. */
  async myReferral(): Promise<MyReferralResponse> {
    return this.api.get<MyReferralResponse>(`${this.brandPath}/referrals/me`);
  }

  /**
   * Redeems a friend's code. Nothing is credited by this call itself -- the
   * reward, if any, fires later on the caller's first order to reach
   * `COMPLETED` (`ReferralOrderCompletionTrigger`), which this client has no
   * way to poll for beyond re-reading {@link myReferral}.
   */
  async redeem(code: string): Promise<RedemptionResponse> {
    return this.api.mutate<RedemptionResponse>('POST', `${this.brandPath}/referrals/redemptions`, {
      body: { code: code.trim() },
      idempotencyKey: newIdempotencyKey(),
    });
  }
}

/** `ReferralStorefrontController.MyReferralResponse`, transcribed from the OpenAPI schema. */
export interface MyReferralResponse {
  readonly code: string;
  readonly redeemedAs: RedemptionResponse | null;
}

/** `ReferralStorefrontController.RedemptionResponse` -- status and dates only, never an amount. */
export interface RedemptionResponse {
  readonly status: 'PENDING' | 'REWARDED' | 'EXPIRED' | 'VOIDED';
  readonly redeemedAt: string;
  readonly expiresAt: string;
  readonly rewardedAt: string | null;
}
