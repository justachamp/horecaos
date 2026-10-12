import { Injectable, inject } from '@angular/core';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ScopeGrant } from '../../../core/auth/session-context';
import { canonicalCapability } from '../../staff/scope-coverage';

/**
 * Where the signed-in operator may work service-recovery cases (ADR 0159, ADR 0025).
 *
 * `locationIds` is null for the whole brand -- the operator holds `recovery.case.manage` at the brand
 * or above, so the brand's worklist and counts answer for them -- and otherwise lists the branches
 * where they hold it and nowhere wider: a branch manager's grant covers only the routes whose path
 * names her branch, so she works those branches' own worklists and the brand's is refused.
 */
export interface RecoveryReach {
  readonly scope: BrandScope;
  readonly locationIds: readonly string[] | null;
}

function holdsManage(grant: ScopeGrant): boolean {
  return (grant.capabilities ?? []).some(
    (held) => canonicalCapability(held) === 'RECOVERY_CASE_MANAGE',
  );
}

/**
 * Decides which of the two worklist readings the signed-in operator uses for the brand in front of
 * them: the brand's, or one branch's at a time.
 *
 * The Reviews tab is offered to anyone who holds the capability anywhere, so this is what keeps a
 * branch manager from being offered a tab whose every request is refused: the brand routes are
 * `BRAND`-scope and ADR 0025 scopes cover downwards, never up, so a `LOCATION` grant answers only
 * the routes of its own branch.
 *
 * **A usability affordance, never an authorization decision**: the server decides every route
 * against the scope in its path; a wrong answer here costs a refused request, never a wider reach.
 */
@Injectable({ providedIn: 'root' })
export class RecoveryReachResolver {
  private readonly tenant = inject(CurrentTenant);

  async resolve(brand: BrandScope): Promise<RecoveryReach | null> {
    await this.tenant.ensureLoaded();
    const scopes = this.tenant.scopes();

    const brandWide = scopes.some(
      (grant) =>
        holdsManage(grant) &&
        grant.scope.tenantId === brand.tenantId &&
        (grant.scope.type === 'TENANT' ||
          (grant.scope.type === 'BRAND' && grant.scope.brandId === brand.brandId)),
    );
    if (brandWide) {
      return { scope: brand, locationIds: null };
    }

    const branches: string[] = [];
    for (const grant of scopes) {
      if (
        grant.scope.type === 'LOCATION' &&
        grant.scope.tenantId === brand.tenantId &&
        grant.scope.brandId === brand.brandId &&
        grant.scope.locationId !== null &&
        holdsManage(grant) &&
        !branches.includes(grant.scope.locationId)
      ) {
        branches.push(grant.scope.locationId);
      }
    }
    return branches.length > 0 ? { scope: brand, locationIds: branches } : null;
  }
}
