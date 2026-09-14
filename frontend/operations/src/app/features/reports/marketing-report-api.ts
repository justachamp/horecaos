import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { promoCodePaths } from '../../core/api/promo-codes-paths';

/**
 * Row 7.9a: one row of {@link CustomerDiscountHistory}. Mirrors
 * `CustomerDiscountHistoryController.CustomerCouponRedemptionResponse`.
 */
export interface DiscountHistoryRedemption {
  readonly redemptionId: string;
  readonly brandId: string;
  readonly couponId: string;
  readonly codeHint: string | null;
  readonly promotionId: string;
  readonly promotionName: string;
  readonly orderId: string | null;
  readonly status: 'RESERVED' | 'REDEEMED' | 'RELEASED';
  readonly amountMinor: number;
  readonly currency: string;
  readonly reservedAt: string;
  readonly redeemedAt: string | null;
  readonly releasedAt: string | null;
}

/** Mirrors `CustomerDiscountHistoryController.CurrencyTotalResponse`. */
export interface DiscountHistoryCurrencyTotal {
  readonly currency: string;
  readonly amountMinor: number;
}

/** Mirrors `CustomerDiscountHistoryController.CustomerDiscountHistoryResponse`. */
export interface CustomerDiscountHistory {
  readonly redemptions: readonly DiscountHistoryRedemption[];
  readonly totalsRedeemed: readonly DiscountHistoryCurrencyTotal[];
}

/**
 * Row 7.9a's own read: `CustomerDiscountHistoryController`, tenant-scoped and
 * pricing-owned, so it does not fit `MarketingApi` (brand-scoped, ADR 0044)
 * or `CustomersApi` (the customer record's own module). Named after the
 * report screen that is this wave's own consumer; the customer detail pane
 * (`P40`, not yet merged) calls the same endpoint through its own service
 * once it lands.
 */
@Injectable({ providedIn: 'root' })
export class MarketingReportApi {
  private readonly api = inject(ApiClient);

  async discountHistory(
    tenantId: string,
    customerAccountId: string,
  ): Promise<CustomerDiscountHistory> {
    return (
      await firstValueFrom(
        this.api.get<CustomerDiscountHistory>(
          promoCodePaths.customerDiscountHistory(tenantId, customerAccountId),
        ),
      )
    ).value;
  }
}
