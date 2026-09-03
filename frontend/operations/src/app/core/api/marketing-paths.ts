import { BrandScope } from './catalog-paths';

/**
 * Where the Marketing section's endpoints live (`OperationsMarketingController`,
 * ADR 0044).
 *
 * Brand-scoped throughout — every call here takes a bare {@link BrandScope},
 * never a full `LocationScope`, because the frequency cap is per brand, quiet
 * hours are in the brand's timezone, and two brands under one tenant are two
 * businesses to the customer. This controller was never moved onto the ADR
 * 0031 `/api/v1/operations` prefix, so it sits beside `customers`/`orders` on
 * the legacy `/api/v1/tenants` one — see `operations-paths.ts`'s own doc for
 * the split this application tracks in one place per surface.
 */
const TENANT = '/api/v1/tenants';

function enc(value: string): string {
  return encodeURIComponent(value);
}

function base(scope: BrandScope): string {
  return `${TENANT}/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/marketing`;
}

export const marketingPaths = {
  audiences(scope: BrandScope): string {
    return `${base(scope)}/audiences`;
  },

  audience(scope: BrandScope, audienceId: string): string {
    return `${this.audiences(scope)}/${enc(audienceId)}`;
  },

  audiencePredicates(scope: BrandScope, audienceId: string): string {
    return `${this.audience(scope, audienceId)}/predicates`;
  },

  audienceSnapshots(scope: BrandScope, audienceId: string): string {
    return `${this.audience(scope, audienceId)}/snapshots`;
  },

  campaigns(scope: BrandScope): string {
    return `${base(scope)}/campaigns`;
  },

  campaign(scope: BrandScope, campaignId: string): string {
    return `${this.campaigns(scope)}/${enc(campaignId)}`;
  },

  campaignEstimates(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/estimates`;
  },

  campaignSubmissions(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/submissions`;
  },

  campaignApprovals(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/approvals`;
  },

  campaignLaunches(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/launches`;
  },

  campaignHalts(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/halts`;
  },

  campaignResumptions(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/resumptions`;
  },

  campaignRecipients(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/recipients`;
  },

  suppressions(scope: BrandScope): string {
    return `${base(scope)}/suppressions`;
  },

  suppressionLifts(scope: BrandScope, suppressionId: string): string {
    return `${this.suppressions(scope)}/${enc(suppressionId)}/lifts`;
  },
} as const;
