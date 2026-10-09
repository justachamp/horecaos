import { Injectable, inject } from '@angular/core';

import { LeadReach } from '../../core/api/lead-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { ScopeGrant } from '../../core/auth/session-context';
import { canonicalCapability } from '../staff/scope-coverage';

/** What the signed-in operator may do with the call centre's queue, and where she does it. */
export interface LeadAccess {
  readonly reach: LeadReach;
  /** `customer.lead.manage` at this reach: register, work and (at brand reach) hand over a lead. */
  readonly canManage: boolean;
  /** Reveal the number: `customer.pii.reveal` at this reach. */
  readonly canReveal: boolean;
}

function holds(grant: ScopeGrant, capability: string): boolean {
  const wanted = canonicalCapability(capability);
  return (grant.capabilities ?? []).some((held) => canonicalCapability(held) === wanted);
}

/**
 * Decides which of the two lead routes the signed-in operator uses (ADR 0111, ADR 0025).
 *
 * A grant covers only the routes whose path names its own level, so an owner, an administrator or a
 * brand manager works the brand's whole queue through the brand routes, while a branch manager — who
 * holds `customer.lead.read` at her branch and nowhere wider — sees the leads handed to that branch
 * through the branch's. The brand a company-wide operator is looking at is {@link CurrentBrand}'s,
 * the same resolver the other brand-scoped screens of this section use.
 *
 * **A usability affordance, never an authorization decision**: the server decides every route against
 * the scope in its path; a wrong answer here costs a refused request, never a wider reach.
 */
@Injectable({ providedIn: 'root' })
export class LeadReachResolver {
  private readonly tenant = inject(CurrentTenant);
  private readonly brand = inject(CurrentBrand);

  async resolve(): Promise<LeadAccess | null> {
    await this.tenant.ensureLoaded();
    const scopes = this.tenant.scopes();

    const brandWide = scopes.filter(
      (grant) =>
        (grant.scope.type === 'TENANT' || grant.scope.type === 'BRAND') &&
        holds(grant, 'CUSTOMER_LEAD_READ'),
    );
    if (brandWide.length > 0) {
      await this.brand.ensureLoaded();
      const brand = this.brand.scope();
      if (brand) {
        return {
          reach: { kind: 'BRAND', tenantId: brand.tenantId, brandId: brand.brandId },
          canManage: brandWide.some((grant) => holds(grant, 'CUSTOMER_LEAD_MANAGE')),
          canReveal: brandWide.some((grant) => holds(grant, 'CUSTOMER_PII_REVEAL')),
        };
      }
    }

    const branch = scopes.find(
      (grant) =>
        grant.scope.type === 'LOCATION' &&
        grant.scope.tenantId !== null &&
        grant.scope.brandId !== null &&
        grant.scope.locationId !== null &&
        holds(grant, 'CUSTOMER_LEAD_READ'),
    );
    if (branch?.scope.tenantId && branch.scope.brandId && branch.scope.locationId) {
      return {
        reach: {
          kind: 'LOCATION',
          tenantId: branch.scope.tenantId,
          brandId: branch.scope.brandId,
          locationId: branch.scope.locationId,
        },
        canManage: holds(branch, 'CUSTOMER_LEAD_MANAGE'),
        canReveal: holds(branch, 'CUSTOMER_PII_REVEAL'),
      };
    }
    return null;
  }
}
