import { BrandScope } from './catalog-paths';

/**
 * Where a brand's own referral program and its redemptions live
 * (`ReferralPolicyController`/`ReferralOperationsController`, a new ADR,
 * operations §6.6 Referrals).
 *
 * On the ADR 0031 `/api/v1/operations/**` prefix, brand-scoped like
 * `loyalty-paths.ts` beside it — a referral reward is paid from the brand's
 * own points ledger, the same reason accrual rate and redemption cap are a
 * brand's own policy rather than a location's.
 */
const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const referralPaths = {
  base(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/referrals`;
  },

  programs(scope: BrandScope): string {
    return `${this.base(scope)}/programs`;
  },

  programActivation(scope: BrandScope, programId: string): string {
    return `${this.programs(scope)}/${encodeURIComponent(programId)}/activate`;
  },

  programRetirement(scope: BrandScope, programId: string): string {
    return `${this.programs(scope)}/${encodeURIComponent(programId)}/retire`;
  },

  summary(scope: BrandScope): string {
    return `${this.base(scope)}/summary`;
  },

  redemptions(scope: BrandScope): string {
    return `${this.base(scope)}/redemptions`;
  },
} as const;
