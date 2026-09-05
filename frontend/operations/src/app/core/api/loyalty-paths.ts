import { BrandScope } from './catalog-paths';

/**
 * Where a brand's own loyalty policy lives (`LoyaltyPolicyController`,
 * ADR 0046, operations §6.3 Loyalty).
 *
 * Already on the ADR 0031 `/api/v1/operations/**` prefix — this controller is
 * new this wave and has no legacy shape to inherit, the same reasoning
 * `operations-paths.ts` gives for `dispatchQueue`. It sits beside
 * `LoyaltyOperationsController`'s own `/tenants/{tenantId}/customers/...`
 * path under the same controller package, brand-scoped rather than
 * customer-scoped because accrual rate and redemption cap are a brand's own
 * policy, not one customer's balance.
 */
const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const loyaltyPolicyPaths = {
  base(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/loyalty`;
  },

  accrualRules(scope: BrandScope): string {
    return `${this.base(scope)}/accrual-rules`;
  },

  accrualRuleActivation(scope: BrandScope, ruleId: string): string {
    return `${this.accrualRules(scope)}/${encodeURIComponent(ruleId)}/activate`;
  },

  accrualRuleRetirement(scope: BrandScope, ruleId: string): string {
    return `${this.accrualRules(scope)}/${encodeURIComponent(ruleId)}/retire`;
  },

  redemptionPolicies(scope: BrandScope): string {
    return `${this.base(scope)}/redemption-policies`;
  },

  redemptionPolicyActivation(scope: BrandScope, policyId: string): string {
    return `${this.redemptionPolicies(scope)}/${encodeURIComponent(policyId)}/activate`;
  },

  redemptionPolicyRetirement(scope: BrandScope, policyId: string): string {
    return `${this.redemptionPolicies(scope)}/${encodeURIComponent(policyId)}/retire`;
  },
} as const;
