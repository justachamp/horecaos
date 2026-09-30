import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';
import type { MoneyGrouping, MoneySymbolPlacement } from '../../../core/format/regional-format';

export type BrandLocaleCode = 'ru' | 'uz-Latn' | 'en';

/** Mirrors uz.horecaos.platform.tenancy.application.TenantControlPlaneService.BrandLocaleView. */
export interface BrandLocaleView {
  readonly locale: BrandLocaleCode;
  readonly description: string | null;
  readonly isDefault: boolean;
}

/**
 * Mirrors uz.horecaos.platform.tenancy.application.TenantControlPlaneService.RegionalFormatsView
 * (row 10.12): how this brand's operators read money and phone numbers in the console.
 */
export interface RegionalFormatsView {
  readonly moneySymbolPlacement: MoneySymbolPlacement;
  readonly moneyGrouping: MoneyGrouping;
  /** One `#` per digit with `+ ( ) - .` and spaces kept as written; null shows a number as it arrives. */
  readonly phoneDisplayPattern: string | null;
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
  /** Row 10.12. Optional so a fixture, or an older platform that sends none, reads as the defaults. */
  readonly regionalFormats?: RegionalFormatsView;
  readonly version: number;
}

/** Mirrors uz.horecaos.platform.tenancy.web.TenantProfileController.TenantMarketView — row 10.1, read-only. */
export interface TenantMarketView {
  readonly countryCode: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
}

export interface ReviseBrandRequest {
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
}

/**
 * `TenantControlPlaneController.RegionalFormatsRequest`. An absent placement or grouping means the
 * default and an absent pattern shows a number as it arrives, so this screen always sends all
 * three it holds and leaves the pattern out to clear it.
 */
export interface ReviseRegionalFormatsRequest {
  readonly moneySymbolPlacement: MoneySymbolPlacement;
  readonly moneyGrouping: MoneyGrouping;
  readonly phoneDisplayPattern?: string;
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

  /** Row 10.1 — the tenant's own country/currency/timezone, read-only. */
  async tenantProfile(tenantId: string): Promise<TenantMarketView> {
    const result = await firstValueFrom(
      this.api.get<TenantMarketView>(settingsPaths.tenantProfile(tenantId)),
    );
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

  /**
   * Row 10.12 — the brand's regional display formats, its own write beside {@link updateProfile}
   * (which never touches them) and, like it, without an `If-Match`: a display preference is not an
   * identity two people could race on.
   */
  async reviseRegionalFormats(
    scope: LocationScope,
    request: ReviseRegionalFormatsRequest,
  ): Promise<BrandView> {
    return firstValueFrom(
      this.api.put<ReviseRegionalFormatsRequest, BrandView>(
        settingsPaths.brandRegionalFormats(scope),
        command(request),
      ),
    );
  }
}
