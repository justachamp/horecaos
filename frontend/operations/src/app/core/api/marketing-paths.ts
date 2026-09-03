import { LocationScope } from './operations-paths';

/**
 * Where ADR 0044's audience surface lives — `OperationsMarketingController`,
 * mounted at `/api/v1/tenants/{tenantId}/brands/{brandId}/marketing`.
 *
 * Brand-scoped, not `LocationScope`'s full three-way scope: an audience is a
 * brand's own segment of its customers, and the location id a call happens to
 * carry is never sent. `Capability.AUDIENCE_READ` is declared at
 * `ScopeType.BRAND` in `Capability.java`, matching this.
 */

const MARKETING = (tenantId: string, brandId: string): string =>
  `/api/v1/tenants/${encodeURIComponent(tenantId)}/brands/${encodeURIComponent(brandId)}/marketing`;

export const marketingPaths = {
  /** `OperationsMarketingController.audienceList` / `.defineAudience`. */
  audiences(scope: LocationScope): string {
    return `${MARKETING(scope.tenantId, scope.brandId)}/audiences`;
  },

  /** `.audienceDetail` / `.redefineAudience`. */
  audience(scope: LocationScope, audienceId: string): string {
    return `${marketingPaths.audiences(scope)}/${encodeURIComponent(audienceId)}`;
  },

  /** `.buildSnapshot` — evaluates the predicates into an immutable, channel-scoped snapshot. */
  audienceSnapshots(scope: LocationScope, audienceId: string): string {
    return `${marketingPaths.audience(scope, audienceId)}/snapshots`;
  },
} as const;
