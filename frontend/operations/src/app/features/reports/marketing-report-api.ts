import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import { promoCodePaths } from '../../core/api/promo-codes-paths';
import { promotionPaths } from '../../core/api/promotion-paths';
import { reportsPaths } from '../../core/api/reports-paths';
import { ProvenanceResponse } from './reporting-api';

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
 * Row 7.9: one promotion over a closed date range. Mirrors
 * `PromotionReportController.SummaryRowResponse`. Money is whole minor units of
 * the platform's currency (`...Som`, as the endpoint names them).
 *
 * `averageCheckWithSom` and `averageCheckWithoutSom` are a comparison over the
 * same brand and period, not a causal uplift: customers who use a promotion
 * differ from those who do not.
 */
export interface PromotionSummaryRow {
  readonly brandId: string;
  readonly promotionId: string;
  readonly promotionCode: string;
  /** `AUTOMATIC` (the redemption ledger) or `COUPON` (a typed promo code). */
  readonly sourceKind: string;
  readonly redemptions: number;
  readonly uniqueCustomers: number;
  readonly discountSom: number;
  readonly markupSom: number;
  readonly revenueWithSom: number;
  readonly averageCheckWithSom: number | null;
  readonly averageCheckWithoutSom: number | null;
}

export interface PromotionSummary {
  readonly rows: readonly PromotionSummaryRow[];
  readonly provenance: ProvenanceResponse;
}

/**
 * Row 7.9: one line of the redemption log. Mirrors
 * `PromotionReportController.RedemptionRowResponse`. `customerSubject` is the
 * ADR 0029 keyed pseudonym, never an account id: a customer cannot be opened from
 * here, and that is the honest limit.
 */
export interface PromotionRedemptionRow {
  readonly redemptionId: string;
  readonly businessDate: string;
  readonly brandId: string;
  readonly promotionId: string;
  readonly promotionCode: string;
  readonly definitionVersion: number;
  readonly sourceKind: string;
  readonly orderId: string;
  readonly customerSubject: string | null;
  readonly discountSom: number;
  readonly markupSom: number;
  readonly currency: string;
  readonly redeemedAt: string;
  /** The order's status in `fact_order`, null while it has none; a cancelled order is still listed. */
  readonly orderStatus: string | null;
  readonly channelCode: string | null;
}

export interface PromotionRedemptionLog {
  readonly rows: readonly PromotionRedemptionRow[];
  readonly provenance: ProvenanceResponse;
}

/**
 * Who redeemed it: the customer account behind one redemption, as the audited
 * reveal answers it. Mirrors `PromotionController.RedemptionCustomerResponse`.
 *
 * It carries the account id and nothing about the person; a contact value stays
 * behind the customer card's own `customer.pii.reveal`. `customerAccountId` is
 * `null` for a guest order, which has no account to open.
 */
export interface RedemptionCustomer {
  readonly redemptionId: string;
  readonly promotionId: string;
  readonly sourceKind: string;
  readonly orderId: string;
  readonly customerAccountId: string | null;
}

/**
 * Row 7.9's own read: `CustomerDiscountHistoryController`, tenant-scoped and
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

  /**
   * 7.9, per promotion: redemptions, unique customers, discount and markup given,
   * revenue with the promotion, and the average check with and without it.
   * Cancelled, rejected, expired and payment-failed orders are excluded; the log
   * keeps them. Built at day close, so it lags by up to a business day.
   */
  async promotionSummary(
    tenantId: string,
    range: { readonly from: string; readonly to: string },
    brandId: string | null,
  ): Promise<PromotionSummary> {
    return (
      await firstValueFrom(
        this.api.get<PromotionSummary>(reportsPaths.promotionSummary(tenantId), {
          params: { from: range.from, to: range.to, brandId: brandId ?? undefined },
        }),
      )
    ).value;
  }

  /** 7.9, the redemption log: newest first, at most `limit` rows (the endpoint caps it at 500). */
  async promotionRedemptions(
    tenantId: string,
    range: { readonly from: string; readonly to: string },
    promotionId: string | null,
    limit = 200,
  ): Promise<PromotionRedemptionLog> {
    return (
      await firstValueFrom(
        this.api.get<PromotionRedemptionLog>(reportsPaths.promotionRedemptions(tenantId), {
          params: {
            from: range.from,
            to: range.to,
            promotionId: promotionId ?? undefined,
            limit,
          },
        }),
      )
    ).value;
  }

  /**
   * 7.9, «who redeemed it»: resolves one row of the redemption log to the customer account it
   * belongs to. Needs `customer.read` at the row's brand and a stated purpose, and the platform
   * writes a security audit fact against the customer account, so the account's own access log
   * shows who looked and why. A guest order answers with no account.
   *
   * Scoped by the *row's* brand and promotion, not the operator's current one: the log is read
   * across the tenant and the reveal is authorized where the redemption happened.
   */
  async revealRedemptionCustomer(
    scope: BrandScope,
    promotionId: string,
    redemptionId: string,
    purpose: string,
  ): Promise<RedemptionCustomer> {
    return firstValueFrom(
      this.api.post<{ readonly purpose: string }, RedemptionCustomer>(
        promotionPaths.redemptionCustomerReveal(scope, promotionId, redemptionId),
        command({ purpose }),
      ),
    );
  }
}
