import { Injectable, Signal, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { settingsPaths } from '../../core/api/settings-paths';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { BrandView } from './brand-profile/brand-profile-api';
import { LocationView } from './locations/locations-api';

/** The level a settings screen is currently writing to (settings.md §1.1's "Уровень редактирования"). */
export type SettingsEditingLevel = 'BRAND' | 'LOCATION';

/**
 * settings.md §1.1's scope bar, as shared state: which brand and which
 * location (or "Все филиалы", i.e. brand-level) every settings screen reads
 * and writes against, synced to `?brand=&location=` so a link pasted into a
 * chat opens the same thing (wave P31, gap map row `10/X.1`).
 *
 * `settings-shell.ts` used to read only `CurrentLocation` — the operator's
 * own branch, with no way to change it and no brand-level option at all,
 * which is exactly the gap this class closes. `q-scope-bar` is the
 * presentational half; this is the state and the API calls behind it.
 *
 * **Resolution**, deliberately simple rather than mirroring `CurrentLocation`'s
 * fallback ladder: this screen is for people who administer settings, who by
 * construction hold at least a `BRAND`-scoped grant (`TENANT_CONFIGURATION_*`
 * is never granted narrower than `TENANT`), so the brand list always comes
 * from `OperationsBrandController.list`, never inferred from a single
 * `LOCATION` grant. A brand or location named in the query string that this
 * tenant does not have falls back to the first option — the same defensive
 * stance {@link locationId} already needs for a location that belonged to a
 * brand the query string no longer names.
 */
@Injectable({ providedIn: 'root' })
export class SettingsScope {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(ApiClient);

  private readonly queryBrandId = signal<string | null>(null);
  private readonly queryLocationId = signal<string | null>(null);
  private readonly brandsSig = signal<readonly BrandView[]>([]);
  private readonly locationsSig = signal<readonly LocationView[]>([]);
  private readonly loadingBrands = signal(false);
  private readonly deniedSig = signal(false);
  private brandsLoadedForTenant: string | null = null;
  private locationsLoadedForBrand: string | null = null;

  readonly brands: Signal<readonly BrandView[]> = this.brandsSig.asReadonly();
  readonly locations: Signal<readonly LocationView[]> = this.locationsSig.asReadonly();

  /** Hidden entirely when the tenant has exactly one brand — a picker with one option is noise. */
  readonly showBrandPicker: Signal<boolean> = computed(() => this.brandsSig().length > 1);

  /** The brand in effect: the query param when it names a brand this tenant has, else the first one. */
  readonly brandId: Signal<string | null> = computed(() => {
    const brands = this.brandsSig();
    if (brands.length === 0) {
      return null;
    }
    const requested = this.queryBrandId();
    return requested && brands.some((brand) => brand.id === requested) ? requested : brands[0].id;
  });

  /** `null` means "Все филиалы" — editing at BRAND level. A location outside the current brand is dropped. */
  readonly locationId: Signal<string | null> = computed(() => {
    const requested = this.queryLocationId();
    if (!requested) {
      return null;
    }
    return this.locationsSig().some((location) => location.id === requested) ? requested : null;
  });

  readonly level: Signal<SettingsEditingLevel> = computed(() =>
    this.locationId() ? 'LOCATION' : 'BRAND',
  );

  readonly loading: Signal<boolean> = this.loadingBrands.asReadonly();
  readonly denied: Signal<boolean> = this.deniedSig.asReadonly();

  constructor() {
    this.route.queryParamMap.subscribe((params) => {
      this.queryBrandId.set(params.get('brand'));
      this.queryLocationId.set(params.get('location'));
    });

    void this.loadBrands();

    // Re-fetches locations whenever the resolved brand changes, including the
    // first time it resolves from "no query param yet" to a real id — the
    // same "re-read inside an effect keyed on the input" idiom
    // `location-detail-pane.ts` uses, because a plain constructor-only load
    // only ever sees the value at construction time.
    effect(() => {
      const brandId = this.brandId();
      const tenantId = this.tenant.tenantId();
      if (brandId && tenantId) {
        void this.loadLocations(tenantId, brandId);
      }
    });
  }

  /** Switches the brand, clearing any location selection — a different brand's branches are a different set. */
  setBrand(brandId: string): void {
    void this.router.navigate([], {
      queryParams: { brand: brandId, location: null },
      queryParamsHandling: 'merge',
    });
  }

  /** Switches the location, or pass `null` for "Все филиалы" (brand-level editing). */
  setLocation(locationId: string | null): void {
    void this.router.navigate([], {
      queryParams: { location: locationId },
      queryParamsHandling: 'merge',
    });
  }

  private async loadBrands(): Promise<void> {
    this.loadingBrands.set(true);
    try {
      await this.tenant.ensureLoaded();
      const tenantId = this.tenant.tenantId();
      if (!tenantId) {
        this.deniedSig.set(this.tenant.denied());
        return;
      }
      if (this.brandsLoadedForTenant === tenantId) {
        return;
      }
      const result = await firstValueFrom(
        this.api.get<readonly BrandView[]>(
          settingsPaths.brands({ tenantId, brandId: '', locationId: '' }),
        ),
      );
      this.brandsSig.set(result.value ?? []);
      this.brandsLoadedForTenant = tenantId;

      // The URL should reflect the level it will actually edit, per
      // settings.md §1.1 ("a link pasted into a chat opens the same thing") —
      // so a first load with no `?brand=` in the query string writes the
      // resolved default back rather than leaving the URL silently disagree
      // with what the scope bar shows.
      const resolved = this.brandId();
      if (resolved && this.queryBrandId() !== resolved) {
        void this.router.navigate([], {
          queryParams: { brand: resolved },
          queryParamsHandling: 'merge',
        });
      }
    } catch {
      this.deniedSig.set(true);
    } finally {
      this.loadingBrands.set(false);
    }
  }

  private async loadLocations(tenantId: string, brandId: string): Promise<void> {
    if (this.locationsLoadedForBrand === brandId) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<readonly LocationView[]>(
          settingsPaths.locations({ tenantId, brandId, locationId: '' }),
        ),
      );
      this.locationsSig.set(result.value ?? []);
      this.locationsLoadedForBrand = brandId;
    } catch {
      this.locationsSig.set([]);
    }
  }
}
