import { BrandScope } from './catalog-paths';

/**
 * Where a brand's own order reviews live (`OperationsReviewController`,
 * ADR 0071, operations §5.4 Reviews).
 *
 * `/api/v1/operations/**` — new this wave, no legacy shape to inherit, the
 * same reasoning `loyalty-paths.ts` and `operations-paths.ts` already give
 * for their own `OPERATIONS`-native additions. Brand-scoped rather than
 * location-scoped: the screen filters *within* a brand's reviews (including
 * by location), the same shape `marketing-paths.ts`'s audience reads use, and
 * `review.read` is itself a `BRAND`-scope capability (ADR 0071).
 */
const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const reviewPaths = {
  list(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/reviews`;
  },

  summary(scope: BrandScope): string {
    return `${this.list(scope)}/summary`;
  },
} as const;
