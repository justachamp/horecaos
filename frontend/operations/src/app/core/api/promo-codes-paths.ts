import { BrandScope } from './catalog-paths';

/**
 * A brand's own promo codes (`PromoCodeController`, ADR 0072, operations
 * §6.2 Promo codes).
 *
 * Already on the ADR 0031 `/api/v1/operations/**` prefix, brand-scoped like
 * `LoyaltyPolicyController` beside it — a promo code's discount shape, value
 * and limits are a brand's own authored policy, not a customer- or
 * order-scoped fact.
 */
const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const promoCodePaths = {
  base(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/promo-codes`;
  },

  activation(scope: BrandScope, couponId: string): string {
    return `${this.base(scope)}/${encodeURIComponent(couponId)}/activate`;
  },

  retirement(scope: BrandScope, couponId: string): string {
    return `${this.base(scope)}/${encodeURIComponent(couponId)}/retire`;
  },

  redemptions(scope: BrandScope, couponId: string): string {
    return `${this.base(scope)}/${encodeURIComponent(couponId)}/redemptions`;
  },

  /**
   * Row 7.9a: `CustomerDiscountHistoryController`, tenant-scoped rather than
   * brand-scoped like the rest of this file — a customer account can hold
   * redemptions against more than one brand under the same tenant, and "how
   * much has this customer been discounted" means everywhere in the tenant.
   */
  customerDiscountHistory(tenantId: string, customerAccountId: string): string {
    return `${OPERATIONS}/tenants/${encodeURIComponent(tenantId)}/customers/${encodeURIComponent(customerAccountId)}/discount-history`;
  },
} as const;
