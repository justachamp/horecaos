import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { settingsPaths } from '../../../core/api/settings-paths';

export type ClientType = 'PUBLIC' | 'CONFIDENTIAL';
export type AppStatus = 'ACTIVE' | 'SUSPENDED' | 'RETIRED';
export type ConformanceState = 'NOT_RUN' | 'PASSED' | 'FAILED' | 'EXPIRED';
export type Standing = 'NOT_AUTHORISED' | 'AUTHORISED' | 'REVOKED';

/** Mirrors StorefrontAppViews.StorefrontAppConformance. EXPIRED is derived by the server, never stored. */
export interface Conformance {
  readonly status: ConformanceState;
  readonly contractVersion: string | null;
  readonly recordedAt: string | null;
  readonly note: string | null;
}

/**
 * Mirrors StorefrontAppViews.StorefrontAppCatalogueEntry (ADR 0070): one registered app beside what
 * this brand has decided about it. `authorisationVersion` is the `If-Match` value for revoking.
 */
export interface CatalogueEntry {
  readonly appId: string;
  readonly name: string;
  readonly vendor: string;
  readonly clientType: ClientType;
  readonly firstParty: boolean;
  readonly appStatus: AppStatus;
  readonly conformance: Conformance;
  readonly standing: Standing;
  readonly grantedAt: string | null;
  readonly grantedBy: string | null;
  readonly revokedAt: string | null;
  readonly authorisationVersion: number | null;
}

/** Mirrors StorefrontAppViews.StorefrontAppBrandAuthorisationView. */
export interface BrandAuthorisation {
  readonly appId: string;
  readonly tenantId: string;
  readonly brandId: string;
  readonly standing: Standing;
  readonly grantedBy: string;
  readonly grantedAt: string;
  readonly revokedBy: string | null;
  readonly revokedAt: string | null;
  readonly version: number;
}

/**
 * 10.15 Storefront apps (ADR 0070, `OperationsStorefrontAppController`) — the tenant's own choice of
 * storefront: which registered apps may serve a brand. `STOREFRONT_APP_AUTHORISE` is held by the owner
 * and the administrator at tenant scope, which is why every call takes a bare tenant and brand.
 */
@Injectable({ providedIn: 'root' })
export class StorefrontAppsApi {
  private readonly api = inject(ApiClient);

  /** Every app this brand can choose, with its standing for each. A retired app is not offered. */
  async catalogue(tenantId: string, brandId: string): Promise<readonly CatalogueEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CatalogueEntry[]>(settingsPaths.storefrontApps(tenantId, brandId)),
    );
    return result.value ?? [];
  }

  /** Authorises the app for the brand, or authorises it again after the brand withdrew it. */
  async authorise(
    tenantId: string,
    brandId: string,
    appId: string,
    reason: string,
  ): Promise<BrandAuthorisation> {
    return firstValueFrom(
      this.api.post<{ reason: string }, BrandAuthorisation>(
        settingsPaths.storefrontAppAuthorisations(tenantId, brandId, appId),
        command({ reason }),
      ),
    );
  }

  /** Withdraws the authorisation, against the version the screen read. Effective on the app's next request. */
  async revoke(
    tenantId: string,
    brandId: string,
    appId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<BrandAuthorisation> {
    return firstValueFrom(
      this.api.post<{ reason: string }, BrandAuthorisation>(
        settingsPaths.storefrontAppRevocations(tenantId, brandId, appId),
        command({ reason }),
        { expectedVersion },
      ),
    );
  }
}
