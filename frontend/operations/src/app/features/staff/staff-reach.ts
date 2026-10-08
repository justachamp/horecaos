import { Injectable, Signal, computed, inject } from '@angular/core';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { canonicalCapability } from './scope-coverage';

const GRANT_MANAGE = canonicalCapability('iam.grant.manage');

/**
 * One branch or brand whose people the signed-in operator administers (ADR 0103).
 * A location carries its brand, because every route that names a branch names the
 * brand above it too.
 */
export interface ManagedPlace {
  readonly type: 'BRAND' | 'LOCATION';
  readonly tenantId: string;
  readonly brandId: string;
  readonly locationId: string | null;
}

/**
 * How far the signed-in operator's `iam.grant.manage` reaches — the one fact the
 * Staff section needs to choose between the company-wide routes and a branch's own.
 *
 * ADR 0103 lets a branch manager and a brand manager hold the capability at their
 * own scope. A grant covers only the routes whose path names its level (ADR 0025),
 * so a manager whose reach stops at one branch cannot use the company-wide grants
 * routes at all; she uses the branch's, which answer about that branch and nothing
 * else. This reads the session's own scopes — the same `GET /session/context` the
 * rail and the route guard read — and decides:
 *
 * - {@link tenantWide}: some grant of hers holds the capability at the company (or
 *   platform) level, so every company-wide route is hers and nothing changes.
 * - {@link scoped}: she holds it at one or more branches or brands and nowhere wider,
 *   so the section shows her places' teams and routes every call through them.
 * - neither: she holds it nowhere; the route guard has already turned her away, and
 *   this answers "company-wide" so a refusal comes from the server, not from here.
 *
 * **A usability affordance, never an authorization decision** — the server
 * re-checks every route against the scope in its path; a wrong answer here costs a
 * refused request, never a wider reach.
 */
@Injectable({ providedIn: 'root' })
export class StaffReach {
  private readonly tenant = inject(CurrentTenant);

  /** Every scope at which she holds `iam.grant.manage`, as the session reports it. */
  private readonly holding = computed(() =>
    this.tenant
      .scopes()
      .filter((grant) =>
        (grant.capabilities ?? []).some(
          (capability) => canonicalCapability(capability) === GRANT_MANAGE,
        ),
      )
      .map((grant) => grant.scope),
  );

  readonly tenantWide: Signal<boolean> = computed(() =>
    this.holding().some((scope) => scope.type === 'TENANT' || scope.type === 'PLATFORM'),
  );

  /**
   * Her brands and branches, a branch left out when a brand of hers already contains
   * it — one brand route answers for both, and asking twice would list the same
   * grant twice.
   */
  readonly places: Signal<readonly ManagedPlace[]> = computed(() => {
    const brands = this.holding().filter((scope) => scope.type === 'BRAND');
    const brandIds = new Set(brands.map((scope) => scope.brandId));
    const places: ManagedPlace[] = [];
    for (const scope of this.holding()) {
      if (scope.tenantId === null || scope.brandId === null) {
        continue;
      }
      if (scope.type === 'BRAND') {
        places.push({
          type: 'BRAND',
          tenantId: scope.tenantId,
          brandId: scope.brandId,
          locationId: null,
        });
      } else if (scope.type === 'LOCATION' && !brandIds.has(scope.brandId)) {
        places.push({
          type: 'LOCATION',
          tenantId: scope.tenantId,
          brandId: scope.brandId,
          locationId: scope.locationId,
        });
      }
    }
    return places;
  });

  readonly scoped: Signal<boolean> = computed(() => !this.tenantWide() && this.places().length > 0);
}
