import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { promoCodePaths } from '../../../core/api/promo-codes-paths';

/** ADR 0072's closed discount-shape set. Mirrors `PromoCodeAuthoringService.DiscountShape`. */
export type DiscountShape = 'PERCENTAGE_OFF_ORDER' | 'FIXED_AMOUNT_OFF_ORDER' | 'FREE_DELIVERY';

/**
 * Mirrors `PromoCodeController.PromoCodeResponse`.
 *
 * `plaintextCode` is present only in the response to {@link PromoCodesApi.draft} —
 * `pricing.coupon_codes` stores only a hash, so this is the one moment the
 * code a marketer just typed is ever readable again. Every later read shows
 * only `codeHint`.
 */
export interface PromoCodeView {
  readonly couponId: string;
  readonly name: string;
  readonly plaintextCode: string | null;
  readonly codeHint: string;
  readonly actionType: string;
  readonly value: number;
  readonly minBasketMinor: number;
  readonly maximumDiscountMinor: number | null;
  readonly currency: string;
  readonly channels: readonly string[];
  readonly locationIds: readonly string[];
  readonly totalLimit: number | null;
  readonly perCustomerLimit: number;
  readonly redeemedCount: number;
  readonly status: string;
  readonly version: number;
  readonly validFrom: string;
  readonly validUntil: string | null;
}

/** Mirrors `PromoCodeController.DraftPromoCodeRequest`. */
export interface DraftPromoCodeRequest {
  readonly name: string;
  readonly code: string;
  readonly shape: DiscountShape;
  readonly value: number;
  readonly maximumDiscountMinor?: number | null;
  readonly currency: string;
  readonly minBasketMinor: number;
  readonly channels?: readonly string[] | null;
  readonly locationIds?: readonly string[] | null;
  readonly totalLimit?: number | null;
  readonly perCustomerLimit: number;
  readonly validFrom?: string | null;
  readonly validUntil?: string | null;
}

/**
 * A brand's promo codes (operations §6.2 Promo codes, `PromoCodeController`,
 * ADR 0072).
 *
 * Draft, then activate — never one call. A drafted code's coupon row is
 * `SUSPENDED` and cannot be redeemed until {@link activate} promotes both the
 * promotion and the coupon together, in one transaction. Unlike a loyalty
 * policy, activating one code never retires another: a brand may run several
 * promo codes at once.
 */
@Injectable({ providedIn: 'root' })
export class PromoCodesApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly PromoCodeView[]> {
    const result = await firstValueFrom(this.api.get<readonly PromoCodeView[]>(promoCodePaths.base(scope)));
    return result.value ?? [];
  }

  async draft(scope: BrandScope, request: DraftPromoCodeRequest): Promise<PromoCodeView> {
    return firstValueFrom(
      this.api.post<DraftPromoCodeRequest, PromoCodeView>(promoCodePaths.base(scope), command(request)),
    );
  }

  async activate(scope: BrandScope, couponId: string): Promise<void> {
    await firstValueFrom(this.api.post<null, void>(promoCodePaths.activation(scope, couponId), command(null)));
  }

  async retire(scope: BrandScope, couponId: string): Promise<void> {
    await firstValueFrom(this.api.post<null, void>(promoCodePaths.retirement(scope, couponId), command(null)));
  }
}
