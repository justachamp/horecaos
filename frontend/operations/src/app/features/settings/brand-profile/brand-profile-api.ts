import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

export type BrandLocaleCode = 'ru' | 'uz-Latn' | 'en';

/** Mirrors uz.horecaos.platform.tenancy.application.TenantControlPlaneService.BrandLocaleView. */
export interface BrandLocaleView {
  readonly locale: BrandLocaleCode;
  readonly description: string | null;
  readonly isDefault: boolean;
}

/** Mirrors uz.horecaos.platform.tenancy.application.TenantControlPlaneService.BrandView. */
export interface BrandView {
  readonly id: string;
  readonly tenantId: string;
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
  readonly contactPhone: string | null;
  readonly telegramHandle: string | null;
  readonly logoAssetId: string | null;
  readonly bannerAssetId: string | null;
  readonly locales: readonly BrandLocaleView[];
  readonly version: number;
}

export interface ReviseBrandRequest {
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
}

export interface UpdateBrandProfileRequest {
  readonly contactPhone?: string;
  readonly telegramHandle?: string;
  readonly logoAssetId?: string;
  readonly bannerAssetId?: string;
  readonly locales: readonly {
    readonly locale: BrandLocaleCode;
    readonly description?: string;
    readonly isDefault: boolean;
  }[];
}

/**
 * 10.1 Brand profile, 10.12 languages and regional formats.
 *
 * `getBrand`/`list` read `OperationsBrandController` (operations surface,
 * wave 26). The two writes are cross-surface, the same shape {@link
 * LocationsApi.describePlace} already established for the location place
 * write: `reviseBrand` reuses `TenantControlPlaneController.reviseBrand`,
 * already built and shipped for the control-plane console (If-Match against
 * the brand's own version); `updateProfile` reuses the new `.../profile`
 * endpoint P32 added beside it for contact, media and the 10.12
 * supported-locale set — carries no `If-Match` of its own, since it is
 * storefront content edited as often as a menu description rather than an
 * identity two people could race on.
 */
@Injectable({ providedIn: 'root' })
export class BrandProfileApi {
  private readonly api = inject(ApiClient);

  async getBrand(scope: LocationScope): Promise<BrandView> {
    const result = await firstValueFrom(this.api.get<BrandView>(settingsPaths.brand(scope)));
    return result.value;
  }

  async list(tenantId: string): Promise<readonly BrandView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly BrandView[]>(
        settingsPaths.brands({ tenantId, brandId: '', locationId: '' }),
      ),
    );
    return result.value ?? [];
  }

  async reviseBrand(
    scope: LocationScope,
    request: ReviseBrandRequest,
    expectedVersion: number,
  ): Promise<BrandView> {
    return firstValueFrom(
      this.api.put<ReviseBrandRequest, BrandView>(
        settingsPaths.brandRevise(scope),
        command(request),
        { expectedVersion },
      ),
    );
  }

  async updateProfile(
    scope: LocationScope,
    request: UpdateBrandProfileRequest,
  ): Promise<BrandView> {
    return firstValueFrom(
      this.api.put<UpdateBrandProfileRequest, BrandView>(
        settingsPaths.brandProfileWrite(scope),
        command(request),
      ),
    );
  }
}
