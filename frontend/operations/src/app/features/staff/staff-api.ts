import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { staffPaths } from '../../core/api/staff-paths';
import { Scope } from './scope-coverage';

/** `ResourceScope.ScopeType` — re-exported so callers do not reach into `operations-paths.ts` for it. */
export type ScopeType = 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';

/**
 * Mirrors `uz.horecaos.platform.iam.application.GrantManagementService.GrantView`
 * (V0127 added `reason`, `validFrom`, `validUntil`, `revokedAt`, `revokedBy`,
 * `revokedReason` — the fields staff-and-access.md §2's row states, §3's
 * assignment cards and the restore action all read).
 */
export interface GrantView {
  readonly id: string;
  readonly principalSubject: string;
  readonly roleCode: string;
  readonly scopeType: ScopeType;
  readonly scopeId: string | null;
  readonly status: 'ACTIVE' | 'REVOKED';
  readonly grantedBy: string;
  readonly reason: string;
  readonly validFrom: string;
  readonly validUntil: string | null;
  readonly revokedAt: string | null;
  readonly revokedBy: string | null;
  readonly revokedReason: string | null;
}

/** Mirrors `GrantController.GrantRequest`. */
export interface GrantRequest {
  readonly principalSubject: string;
  readonly roleCode: string;
  readonly brandId?: string;
  readonly locationId?: string;
  readonly reason: string;
  readonly validUntil?: string;
}

/** Mirrors `GrantController.ReasonRequest`. */
export interface ReasonRequest {
  readonly reason: string;
}

/** Mirrors `TenantRoleCatalog.RoleDescriptor` — the eight tenant-visible jobs, capability codes only. */
export interface RoleDescriptor {
  readonly code: string;
  readonly scopeType: ScopeType;
  readonly capabilities: readonly string[];
}

/** Mirrors `TelegramStaffLinkService.StaffLinkView`. */
export interface TelegramStaffLinkView {
  readonly principalSubject: string;
  readonly telegramUserId: number;
  readonly linkedAt: string;
}

/** Mirrors `TenantControlPlaneService.BrandView`, the fields this screen needs. */
export interface BrandSummary {
  readonly id: string;
  readonly displayName: string;
}

/** Mirrors `LocationsApi.LocationView`, the fields this screen needs. */
export interface LocationSummary {
  readonly id: string;
  readonly brandId: string;
  readonly displayName: string;
}

/** Where a job is given — resolved names, for the "Где работает" column and scope picker. */
export interface ScopeDirectory {
  readonly brands: readonly BrandSummary[];
  readonly locations: readonly LocationSummary[];
}

/**
 * The Staff section's one API seam: grants (Люди, Карточка), the role
 * catalogue (Должности), and staff Telegram links.
 */
@Injectable({ providedIn: 'root' })
export class StaffApi {
  private readonly api = inject(ApiClient);

  /** Active grants only unless `includeInactive` — see V0127's own doc on why the default stayed active-only. */
  async listGrants(tenantId: string, includeInactive = false): Promise<readonly GrantView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly GrantView[]>(staffPaths.grants(tenantId), {
        params: { includeInactive },
      }),
    );
    return result.value ?? [];
  }

  async grant(tenantId: string, request: GrantRequest): Promise<{ grantId: string }> {
    return firstValueFrom(
      this.api.post<GrantRequest, { grantId: string }>(
        staffPaths.grants(tenantId),
        command(request),
      ),
    );
  }

  async revoke(
    tenantId: string,
    grantId: string,
    reason: string,
  ): Promise<{ changed: boolean; outcome: string }> {
    const response = await firstValueFrom(
      this.api.send<ReasonRequest, { changed: boolean; outcome: string }>(
        'DELETE',
        staffPaths.grant(tenantId, grantId),
        command({ reason }),
      ),
    );
    return response.body as { changed: boolean; outcome: string };
  }

  async roles(tenantId: string): Promise<readonly RoleDescriptor[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RoleDescriptor[]>(staffPaths.roles(tenantId)),
    );
    return result.value ?? [];
  }

  async telegramLinks(tenantId: string): Promise<readonly TelegramStaffLinkView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TelegramStaffLinkView[]>(staffPaths.telegramStaffLinks(tenantId)),
    );
    return result.value ?? [];
  }

  /**
   * Every brand and location in the tenant, for the "Где работает" column and
   * the job dialog's scope picker. No single endpoint returns "every
   * location" — `OperationsBrandController.locations` is per-brand — so this
   * fans out to one call per brand, which is the tenant's own brand count
   * (single digits for the pilot's single-location tenants and not
   * meaningfully more for the multi-brand ones this wave does not target).
   */
  async scopeDirectory(tenantId: string): Promise<ScopeDirectory> {
    const brandsResult = await firstValueFrom(
      this.api.get<readonly BrandSummary[]>(staffPaths.brands(tenantId)),
    );
    const brands = brandsResult.value ?? [];

    const perBrand = await Promise.all(
      brands.map((brand) =>
        firstValueFrom(
          this.api.get<readonly LocationSummary[]>(staffPaths.brandLocations(tenantId, brand.id)),
        ).then((result) => result.value ?? []),
      ),
    );

    return { brands, locations: perBrand.flat() };
  }
}

/** Builds a `Scope` from a role's level and the picked brand/location, for `scope-coverage.ts` checks. */
export function scopeFor(
  scopeType: ScopeType,
  tenantId: string,
  brandId: string | null,
  locationId: string | null,
): Scope {
  switch (scopeType) {
    case 'PLATFORM':
      return { type: 'PLATFORM', tenantId: null, brandId: null, locationId: null };
    case 'TENANT':
      return { type: 'TENANT', tenantId, brandId: null, locationId: null };
    case 'BRAND':
      return { type: 'BRAND', tenantId, brandId, locationId: null };
    case 'LOCATION':
      return { type: 'LOCATION', tenantId, brandId, locationId };
  }
}
