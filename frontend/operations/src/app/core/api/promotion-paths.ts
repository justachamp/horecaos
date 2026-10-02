import { BrandScope } from './catalog-paths';

/**
 * A brand's automatic promotions and markups (`PromotionController`, ADR 0140,
 * operations §6.1 Promotions) and the 7.9 promotion reports.
 *
 * The authoring surface is brand-scoped on the ADR 0031 `/api/v1/operations/**`
 * prefix, beside `promo-codes-paths.ts` (ADR 0072's coupon-gated face of the same
 * `pricing.promotions` row). The two reporting reads are tenant-scoped under the
 * reporting prefix, because a report spans brands and reads
 * `reporting.fact_promotion_redemption` alone (ADR 0023).
 */
const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const promotionPaths = {
  base(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/promotions`;
  },

  one(scope: BrandScope, promotionId: string): string {
    return `${this.base(scope)}/${encodeURIComponent(promotionId)}`;
  },

  /** `POST` with an `If-Match`: draft to validated, or the refusals that keep it a draft. */
  validate(scope: BrandScope, promotionId: string): string {
    return `${this.one(scope, promotionId)}/validate`;
  },

  /** `POST`: 200 when activated, 202 when a second person has been asked (ADR 0027). */
  activate(scope: BrandScope, promotionId: string): string {
    return `${this.one(scope, promotionId)}/activate`;
  },

  suspend(scope: BrandScope, promotionId: string): string {
    return `${this.one(scope, promotionId)}/suspend`;
  },

  resume(scope: BrandScope, promotionId: string): string {
    return `${this.one(scope, promotionId)}/resume`;
  },

  archive(scope: BrandScope, promotionId: string): string {
    return `${this.one(scope, promotionId)}/archive`;
  },

  /** `PUT`: reorder priorities within one stacking group. */
  priority(scope: BrandScope): string {
    return `${this.base(scope)}/priority`;
  },

  redemptions(scope: BrandScope, promotionId: string): string {
    return `${this.one(scope, promotionId)}/redemptions`;
  },

  /** `POST`, no writes: the real engine over a synthetic cart, with the decision trace. */
  simulate(scope: BrandScope): string {
    return `${this.base(scope)}/simulate`;
  },

  /** Report 7.9: one row per promotion over a closed date range (`REPORTING_READ`). */
  reportSummary(tenantId: string): string {
    return `/api/v1/tenants/${encodeURIComponent(tenantId)}/reporting/promotions/summary`;
  },

  /** Report 7.9: the redemption log, newest first, at most 500 rows. */
  reportRedemptions(tenantId: string): string {
    return `/api/v1/tenants/${encodeURIComponent(tenantId)}/reporting/promotions/redemptions`;
  },
} as const;
