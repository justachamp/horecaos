import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { BrandView, LocationView, TenantsApi } from './tenants-api';

interface BrandRow {
  readonly brand: BrandView;
  readonly locations: readonly LocationView[];
  readonly locationsLoaded: boolean;
}

/**
 * IA 2.3 Brands & locations -- the ownership tree, and provisioning on the
 * tenant's behalf.
 *
 * Owns exactly what the backend owns and no more: brand/location hierarchy
 * and activation. Per-brand currency/locale/fiscal-regime and business-type
 * assignment (both named in the IA row) are not modeled anywhere in the
 * tenancy schema -- currency and timezone are set once, tenant-wide, at
 * creation, and there is no business-type column on either `Tenant` or
 * `Brand`. This screen does not invent fields the API cannot save.
 */
@Component({
  selector: 'app-tenant-brands',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tenant-brands.html',
  styleUrl: './tenant-brands.css',
})
export class TenantBrands {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId')!;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly rows = signal<readonly BrandRow[]>([]);
  protected readonly actionError = signal<string | null>(null);

  protected readonly creatingBrand = signal(false);
  protected readonly brandCode = signal('');
  protected readonly brandSlug = signal('');
  protected readonly brandName = signal('');
  protected readonly brandSubmitting = signal(false);

  protected readonly creatingLocationFor = signal<string | null>(null);
  protected readonly locationCode = signal('');
  protected readonly locationSlug = signal('');
  protected readonly locationName = signal('');
  protected readonly locationTimezone = signal('Asia/Tashkent');
  protected readonly locationSubmitting = signal(false);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const brands = await this.tenantsApi.getBrands(this.tenantId);
      this.rows.set(brands.map((brand) => ({ brand, locations: [], locationsLoaded: false })));
      await Promise.all(brands.map((brand) => this.loadLocations(brand.id)));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadLocations(brandId: string): Promise<void> {
    try {
      const locations = await this.tenantsApi.getLocations(this.tenantId, brandId);
      this.rows.update((rows) =>
        rows.map((row) => (row.brand.id === brandId ? { ...row, locations, locationsLoaded: true } : row)),
      );
    } catch {
      this.rows.update((rows) =>
        rows.map((row) => (row.brand.id === brandId ? { ...row, locationsLoaded: true } : row)),
      );
    }
  }

  protected openCreateBrand(): void {
    this.creatingBrand.set(true);
    this.actionError.set(null);
  }

  protected closeCreateBrand(): void {
    this.creatingBrand.set(false);
  }

  protected canSubmitBrand(): boolean {
    return (
      !this.brandSubmitting() &&
      this.brandCode().trim().length > 0 &&
      this.brandSlug().trim().length > 0 &&
      this.brandName().trim().length > 0
    );
  }

  protected async submitBrand(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitBrand()) {
      return;
    }
    this.brandSubmitting.set(true);
    this.actionError.set(null);
    try {
      const brand = await this.tenantsApi.createBrand(this.tenantId, {
        code: this.brandCode().trim(),
        slug: this.brandSlug().trim(),
        displayName: this.brandName().trim(),
      });
      this.rows.update((rows) => [...rows, { brand, locations: [], locationsLoaded: true }]);
      this.creatingBrand.set(false);
      this.brandCode.set('');
      this.brandSlug.set('');
      this.brandName.set('');
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.brandSubmitting.set(false);
    }
  }

  protected async activateBrand(brandId: string): Promise<void> {
    this.actionError.set(null);
    try {
      const activated = await this.tenantsApi.activateBrand(this.tenantId, brandId);
      this.rows.update((rows) =>
        rows.map((row) => (row.brand.id === brandId ? { ...row, brand: activated } : row)),
      );
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected openCreateLocation(brandId: string): void {
    this.creatingLocationFor.set(brandId);
    this.actionError.set(null);
  }

  protected closeCreateLocation(): void {
    this.creatingLocationFor.set(null);
  }

  protected canSubmitLocation(): boolean {
    return (
      !this.locationSubmitting() &&
      this.locationCode().trim().length > 0 &&
      this.locationSlug().trim().length > 0 &&
      this.locationName().trim().length > 0 &&
      this.locationTimezone().trim().length > 0
    );
  }

  protected async submitLocation(event: Event): Promise<void> {
    event.preventDefault();
    const brandId = this.creatingLocationFor();
    if (brandId === null || !this.canSubmitLocation()) {
      return;
    }
    this.locationSubmitting.set(true);
    this.actionError.set(null);
    try {
      const location = await this.tenantsApi.createLocation(this.tenantId, brandId, {
        code: this.locationCode().trim(),
        slug: this.locationSlug().trim(),
        displayName: this.locationName().trim(),
        timezone: this.locationTimezone().trim(),
      });
      this.rows.update((rows) =>
        rows.map((row) =>
          row.brand.id === brandId ? { ...row, locations: [...row.locations, location] } : row,
        ),
      );
      this.creatingLocationFor.set(null);
      this.locationCode.set('');
      this.locationSlug.set('');
      this.locationName.set('');
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.locationSubmitting.set(false);
    }
  }

  protected async activateLocation(brandId: string, locationId: string): Promise<void> {
    this.actionError.set(null);
    try {
      const activated = await this.tenantsApi.activateLocation(this.tenantId, brandId, locationId);
      this.rows.update((rows) =>
        rows.map((row) =>
          row.brand.id === brandId
            ? {
                ...row,
                locations: row.locations.map((location) =>
                  location.id === locationId ? activated : location,
                ),
              }
            : row,
        ),
      );
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }
}
